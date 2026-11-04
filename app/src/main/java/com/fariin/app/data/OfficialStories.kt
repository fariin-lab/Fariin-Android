package com.fariin.app.data

import android.content.Context
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

/**
 * Fariin's own stories (iOS OfficialStories): a row in the Stories list like a friend's, opened in
 * the same viewer, with no reply bar, heart or repost. The server decides who sees what
 * (`officialStoriesFeed`). This phone sends no build or iOS version: those numbers are iOS's, so a
 * story aimed at certain iOS builds never reaches Android, while Everyone, country and welcome
 * stories do. The last answer is kept per account and shown at once; `config/officialStoriesStamp`
 * moving re-asks. Seen is this phone's own; a watch writes one view under the official story.
 */
object OfficialStories {
    const val ID_PREFIX = "official-"
    private const val PREFS = "fariin_official_stories"
    private const val VIEW_SALT = "fariin-oview-v1:"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _stories = MutableStateFlow<List<Story>>(emptyList())
    val stories: StateFlow<List<Story>> = _stories
    private var app: Context? = null
    private var seededFor: String? = null
    private var lastFetch = 0L
    private var fetching = false
    private var stamp: ListenerRegistration? = null
    private var lastStamp: Timestamp? = null
    private var expiry: Job? = null

    fun isOfficial(s: Story) = s.authorUid == OfficialChannel.CID && s.id.startsWith(ID_PREFIX)

    /** From the Stories list and on a return to the front; [force] after a change or an expiry. */
    fun refresh(c: Context, force: Boolean = false) {
        app = c.applicationContext
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        if (seededFor != uid) { seededFor = uid; publish(load(uid), save = false) }
        if (stamp == null) stamp = FirebaseFirestore.getInstance().document("config/officialStoriesStamp")
            .addSnapshotListener { snap, _ ->
                val at = snap?.getTimestamp("at") ?: return@addSnapshotListener
                val was = lastStamp
                lastStamp = at
                if (was != null && was != at) app?.let { refresh(it, force = true) }
            }
        if (fetching || (!force && System.currentTimeMillis() - lastFetch < 5 * 60_000)) return
        fetching = true
        scope.launch {
            val got = fetch()
            fetching = false
            if (got != null) { lastFetch = System.currentTimeMillis(); publish(got) }
        }
    }

    /** Sign-out: the next account must not inherit this one's answer. */
    fun reset(c: Context) {
        expiry?.cancel(); stamp?.remove(); stamp = null; lastStamp = null; lastFetch = 0
        seededFor?.let { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("feed.$it").apply() }
        seededFor = null
        _stories.value = emptyList()
    }

    private suspend fun fetch(): List<Story>? = runCatching {
        val r = FirebaseFunctions.getInstance(AuthRepository.FUNCTIONS_REGION).getHttpsCallable("officialStoriesFeed")
            .call(mapOf("region" to Locale.getDefault().country.uppercase())).await().getData() as? Map<*, *>
        (r?.get("stories") as? List<*>).orEmpty().mapNotNull { (it as? Map<*, *>)?.let(::story) }
    }.onFailure { android.util.Log.w("OfficialStories", "feed failed", it) }.getOrNull()

    private fun story(r: Map<*, *>): Story? {
        val id = (r["id"] as? String)?.takeIf { it.isNotEmpty() } ?: return null
        val media = (r["mediaUrl"] as? String)?.takeIf { it.isNotEmpty() } ?: return null
        val publish = (r["publishAt"] as? Number)?.toLong() ?: return null
        val expires = (r["expiresAt"] as? Number)?.toLong() ?: return null
        return Story(
            id = ID_PREFIX + id, authorUid = OfficialChannel.CID, createdAtMs = publish, expiresAtMs = expires,
            mediaUrl = media, thumbUrl = r["thumbUrl"] as? String ?: "", isVideo = r["kind"] == "video",
            caption = r["caption"] as? String ?: "", audienceLabel = "everyone", allowsReplies = false,
            durationSec = (r["duration"] as? Number)?.toDouble() ?: 0.0,
            buttonText = r["buttonText"] as? String ?: "", buttonUrl = r["buttonUrl"] as? String ?: "",
        )
    }

    private fun publish(list: List<Story>, save: Boolean = true) {
        val now = System.currentTimeMillis()
        val live = list.filter { it.expiresAtMs > now }.sortedBy { it.createdAtMs }
        if (save) seededFor?.let { save(it, live) }
        _stories.value = live
        // Off the list the moment the first one runs out, rather than at the next ask.
        expiry?.cancel()
        live.minOfOrNull { it.expiresAtMs }?.let { next ->
            expiry = scope.launch { delay(maxOf(1_000L, next - System.currentTimeMillis())); app?.let { refresh(it, force = true) } }
        }
    }

    /** The row for the Stories list, or null (none live, or the Fariin chat is blocked: its news is not wanted). */
    fun group(c: Context, list: List<Story>): StoryGroup? {
        if (list.isEmpty() || OfficialChannel.view.value.state.blocked) return null
        val seen = seenIds(c)
        val lastSeen = list.takeWhile { it.id in seen }.lastOrNull()?.createdAtMs
        return StoryGroup(OfficialChannel.CID, OfficialChannel.NAME,
            "android.resource://${c.packageName}/${com.fariin.app.R.drawable.official_mark}", list, lastSeen)
    }

    /** A watch: seen on this phone, and one view document (named only with read receipts on). */
    fun recordView(c: Context, s: Story, receipts: Boolean) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val raw = s.id.removePrefix(ID_PREFIX).takeIf { it.isNotEmpty() && s.id.startsWith(ID_PREFIX) } ?: return
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = seenIds(c)
        if (s.id in seen) return
        p.edit().putStringSet("seen.$uid", (seen + s.id).toSet()).apply()
        val viewId = if (receipts) uid else MessageDigest.getInstance("SHA-256").digest((VIEW_SALT + uid).toByteArray())
            .joinToString("") { "%02x".format(it) }
        FirebaseFirestore.getInstance().collection("officialStories").document(raw).collection("views").document(viewId)
            .set(mapOf("at" to FieldValue.serverTimestamp(), "named" to receipts))
            .addOnFailureListener { android.util.Log.w("OfficialStories", "view not written", it) }
        _stories.value = _stories.value.toList()   // the ring redraws as seen
    }

    private fun seenIds(c: Context): Set<String> {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return emptySet()
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet("seen.$uid", emptySet()).orEmpty()
    }

    private fun save(uid: String, list: List<Story>) {
        val a = JSONArray()
        for (s in list) a.put(JSONObject().put("id", s.id).put("c", s.createdAtMs).put("e", s.expiresAtMs).put("m", s.mediaUrl)
            .put("t", s.thumbUrl).put("v", s.isVideo).put("cap", s.caption).put("d", s.durationSec).put("bt", s.buttonText).put("bu", s.buttonUrl))
        app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putString("feed.$uid", a.toString())?.apply()
    }

    private fun load(uid: String): List<Story> = runCatching {
        val a = JSONArray(app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getString("feed.$uid", null) ?: return emptyList())
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Story(id = o.getString("id"), authorUid = OfficialChannel.CID, createdAtMs = o.getLong("c"), expiresAtMs = o.getLong("e"),
                mediaUrl = o.getString("m"), thumbUrl = o.optString("t"), isVideo = o.optBoolean("v"), caption = o.optString("cap"),
                audienceLabel = "everyone", allowsReplies = false, durationSec = o.optDouble("d", 0.0),
                buttonText = o.optString("bt"), buttonUrl = o.optString("bu"))
        }
    }.getOrDefault(emptyList())
}
