package com.fariin.app.data

import android.content.Context
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/*
 * GLOW, the relationship behind Glow Stories (iOS GlowService + GlowModels).
 *   GLOWERS = people who gave ME a glow   (glows where to   == me)
 *   GLOWING = people I gave a glow        (glows where from == me)
 * One document per edge, `glows/{from}_{to}` = {from, to, createdAt}. The id is deterministic so
 * giving twice is one edge and removing is one delete. Counts are public, the name lists are the
 * owner's alone (the rules let a `list` through only when the query pins one end to the caller).
 * Nothing here reads or writes the conversations: friends and glows never merge.
 */

/** One person, resolved for a list row (iOS GlowPerson). */
data class GlowPerson(
    val id: String,
    val name: String,
    val handle: String = "",
    val photoUrl: String? = null,
    /** When the glow was given; 0 when unknown. */
    val atMs: Long = 0,
)

/** Loading, loaded, failed: an empty list ("nobody yet") and a failure ("could not ask") differ. */
sealed class GlowLoad<out T> {
    data object Loading : GlowLoad<Nothing>()
    data class Loaded<T>(val value: T) : GlowLoad<T>()
    data object Failed : GlowLoad<Nothing>()

    val valueOrNull: T? get() = (this as? Loaded<T>)?.value
}

/** One of an author's live stories, as the profile card and the Glowing grid draw it. */
data class PostedStory(
    val id: String,
    val thumbUrl: String,
    val mediaUrl: String = "",
    val blurThumb: String = "",
    val createdAtMs: Long,
    val expiresAtMs: Long,
    val isVideo: Boolean = false,
    /** Nil while unknown: the tile then draws no badge rather than a confident zero. */
    val views: Int? = null,
    val audience: String = "everyone",
)

/** One card in the Stories tab's Glowing grid: a glow person and their newest live story. */
data class GlowStoryCard(val person: GlowPerson, val story: PostedStory)

data class GlowState(
    val glowers: Set<String> = emptySet(),
    val glowing: Set<String> = emptySet(),
    /** True once BOTH listeners delivered their first snapshot. */
    val hasLoaded: Boolean = false,
    /** True while either listener's latest callback was an error. */
    val hasFailed: Boolean = false,
) {
    /** Either direction, which IS the Glowers story audience (his ruling). */
    val relationship: Set<String> get() = glowers + glowing
    /** Before the listeners answer, isGlowing is false for everybody: hold the doors inert. */
    val known: Boolean get() = hasLoaded && !hasFailed
}

/** The pure parts, kept out of Compose and Firestore so they can be tested. */
object GlowRules {
    /** A view count badge (iOS GlowCount.short): one decimal under 10K / 10M, whole above. */
    fun countShort(n: Int): String = when {
        n < 1_000 -> "$n"
        n < 1_000_000 -> { val k = n / 1_000.0; if (k < 10) String.format(Locale.US, "%.1fK", k) else "${k.toInt()}K" }
        else -> { val m = n / 1_000_000.0; if (m < 10) String.format(Locale.US, "%.1fM", m) else "${m.toInt()}M" }
    }

    /** The intro sheet's sentence names the person: "Ana's", or "their" without a name. */
    fun introName(name: String): String = name.trim().let { if (it.isEmpty()) "their" else "$it's" }

    fun introBody(name: String): String =
        "A Glow lets you see ${introName(name)} stories without being in each other's chats. " +
            "They can see yours when you post to your Glowers. " +
            "Either of you can take it back at any time."

    /** The stats card's second line: the first two faces' names, else the invitation. */
    fun byLine(names: List<String>): String {
        val n = names.take(2).filter { it.isNotEmpty() }
        return if (n.isEmpty()) "See who glowed you" else "by " + n.joinToString(", ")
    }

    fun statsLine(glowers: Int, glowing: Int): String =
        "${countShort(glowers)} Glowers  ·  ${countShort(glowing)} Glowing"

    /** The people list's search: name or username, trimmed, any case. */
    fun matches(p: GlowPerson, query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return true
        return p.name.lowercase().contains(q) || p.handle.lowercase().contains(q)
    }

    /** The row's strong line is the handle, the name under it; no handle puts the name on top. */
    fun rowTitle(p: GlowPerson): String = if (p.handle.isEmpty()) p.name else "@${p.handle}"
    fun rowSubtitle(p: GlowPerson): String? = p.name.takeIf { p.handle.isNotEmpty() && it.isNotEmpty() }

    /**
     * The notifications page's date rule (iOS GlowEventRow.stampText): "now", "today", "3d", "2w",
     * then "1 Sep" this calendar year and "16 Jul 2025" another.
     */
    fun stampText(atMs: Long, nowMs: Long, zone: TimeZone = TimeZone.getDefault(), locale: Locale = Locale.getDefault()): String {
        val secs = (nowMs - atMs) / 1000
        if (secs < 60) return "now"
        val days = (secs / 86_400).toInt()
        if (days < 7) return if (days < 1) "today" else "${days}d"
        val weeks = days / 7
        if (weeks < 5) return "${weeks}w"
        val a = Calendar.getInstance(zone).apply { timeInMillis = atMs }
        val b = Calendar.getInstance(zone).apply { timeInMillis = nowMs }
        val pattern = if (a.get(Calendar.YEAR) == b.get(Calendar.YEAR)) "d MMM" else "d MMM yyyy"
        return java.text.SimpleDateFormat(pattern, locale).apply { timeZone = zone }.format(java.util.Date(atMs))
    }

    /** People resolved in the order asked for; a partial answer is still an answer, only a total
     *  failure with people to show is a failure. */
    fun resolved(uids: List<String>, found: List<GlowPerson?>): GlowLoad<List<GlowPerson>> {
        val out = found.filterNotNull()
        return if (out.isEmpty() && uids.isNotEmpty()) GlowLoad.Failed else GlowLoad.Loaded(out)
    }

    /** Expired cards are dropped on read, so a cache of any age is correct; all stale reads as none. */
    fun liveCards(cards: List<GlowStoryCard>, nowMs: Long): List<GlowStoryCard>? =
        cards.filter { it.story.expiresAtMs > nowMs }.ifEmpty { null }

    /** The card's face: 44 on a 168 card, scaled with the card (iOS GlowStoryCardView.avatarRatio). */
    fun cardAvatar(cardWidth: Float): Float = if (cardWidth <= 0f) 44f else Math.round(cardWidth * 44f / 168f).toFloat()
    /** The name's inset from the card's corner, derived from the corner radius. */
    fun nameInset(corner: Float): Float = maxOf(10f, kotlin.math.ceil(0.47f * corner))
    fun ringWidth(face: Float, seen: Boolean): Float = (face * 2f / 44f).let { if (seen) maxOf(1f, it * 0.66f) else it }
}

object Glow {
    private val _state = MutableStateFlow(GlowState())
    val state: StateFlow<GlowState> = _state
    private val _seenUpTo = MutableStateFlow(0L)
    /** When the notifications page was last opened; per account, on this phone only. */
    val seenUpTo: StateFlow<Long> = _seenUpTo
    private var uid = ""
    private var glowersReg: ListenerRegistration? = null
    private var glowingReg: ListenerRegistration? = null
    private var glowersLoaded = false
    private var glowingLoaded = false
    private var glowersFailed = false
    private var glowingFailed = false
    private var prefs: android.content.SharedPreferences? = null
    private val db get() = FirebaseFirestore.getInstance()

    private fun seenKey(u: String) = "glowSeenUpTo.$u"

    /** Same uid is a no-op; another uid tears the old pair down first. */
    fun start(c: Context, newUid: String) {
        if (newUid == uid) return
        stop()
        uid = newUid
        if (newUid.isEmpty()) return
        prefs = c.applicationContext.getSharedPreferences("glow", Context.MODE_PRIVATE)
        _seenUpTo.value = prefs?.getLong(seenKey(newUid), 0L) ?: 0L
        // Two listeners, not one or-query: the rules can only prove a list pinned on one field.
        glowersReg = db.collection("glows").whereEqualTo("to", newUid).addSnapshotListener { snap, _ ->
            if (uid != newUid) return@addSnapshotListener
            if (snap == null) { glowersFailed = true; publish(); return@addSnapshotListener }
            glowersLoaded = true; glowersFailed = false
            publish(glowers = snap.documents.mapNotNull { it.getString("from") }.toSet())
        }
        glowingReg = db.collection("glows").whereEqualTo("from", newUid).addSnapshotListener { snap, _ ->
            if (uid != newUid) return@addSnapshotListener
            if (snap == null) { glowingFailed = true; publish(); return@addSnapshotListener }
            glowingLoaded = true; glowingFailed = false
            publish(glowing = snap.documents.mapNotNull { it.getString("to") }.toSet())
        }
    }

    private fun publish(glowers: Set<String>? = null, glowing: Set<String>? = null) {
        val s = _state.value
        _state.value = s.copy(
            glowers = glowers ?: s.glowers, glowing = glowing ?: s.glowing,
            hasLoaded = glowersLoaded && glowingLoaded, hasFailed = glowersFailed || glowingFailed,
        )
    }

    fun stop() {
        glowersReg?.remove(); glowersReg = null
        glowingReg?.remove(); glowingReg = null
        glowersLoaded = false; glowingLoaded = false; glowersFailed = false; glowingFailed = false
        _state.value = GlowState()
        _seenUpTo.value = 0L
        uid = ""
    }

    fun isGlowing(other: String) = _state.value.glowing.contains(other)

    /**
     * Give a glow. Optimistic; a refusal rolls the set back. CREATE-ONLY in the rules, so an edge
     * already in `glowing` sends nothing. True when it landed or there was nothing to send.
     */
    suspend fun give(other: String): Boolean {
        val me = uid
        if (me.isEmpty() || other == me || other.isEmpty() || isGlowing(other)) return true
        _state.value = _state.value.copy(glowing = _state.value.glowing + other)
        return try {
            db.collection("glows").document("${me}_$other")
                .set(mapOf("from" to me, "to" to other, "createdAt" to FieldValue.serverTimestamp())).await()
            // Counted for the rules' daily glow limit, only once it has landed.
            if (uid == me) countDaily(me, "glows")
            true
        } catch (_: Exception) {
            if (uid == me) _state.value = _state.value.copy(glowing = _state.value.glowing - other)
            false
        }
    }

    /** Take a glow back (removable like a follow). A refusal puts it back if it was there. */
    suspend fun remove(other: String): Boolean {
        val me = uid
        if (me.isEmpty() || other == me || other.isEmpty()) return true
        val was = isGlowing(other)
        _state.value = _state.value.copy(glowing = _state.value.glowing - other)
        return try {
            db.collection("glows").document("${me}_$other").delete().await(); true
        } catch (_: Exception) {
            if (uid == me && was) _state.value = _state.value.copy(glowing = _state.value.glowing + other)
            false
        }
    }

    /** A glower I no longer want: the `to` side may delete the edge too. */
    suspend fun removeGlower(other: String): Boolean {
        val me = uid
        if (me.isEmpty() || other == me || other.isEmpty()) return true
        val was = _state.value.glowers.contains(other)
        _state.value = _state.value.copy(glowers = _state.value.glowers - other)
        return try {
            db.collection("glows").document("${other}_$me").delete().await(); true
        } catch (_: Exception) {
            if (uid == me && was) _state.value = _state.value.copy(glowers = _state.value.glowers + other)
            false
        }
    }

    fun markSeen() {
        val u = uid.ifEmpty { return }
        val now = System.currentTimeMillis()
        _seenUpTo.value = now
        prefs?.edit()?.putLong(seenKey(u), now)?.apply()
    }

    /** iOS MessageRequests.countDaily: `users/{me}/limits/{name}`, a 24 h window. Best effort. */
    private suspend fun countDaily(me: String, name: String) {
        val ref = db.collection("users").document(me).collection("limits").document(name)
        try {
            val snap = ref.get().await()
            val start = snap.getTimestamp("windowStart")?.toDate()?.time
            if (snap.exists() && start != null && System.currentTimeMillis() - start < 24 * 3600_000L) {
                ref.update("count", FieldValue.increment(1)).await()
            } else {
                ref.set(mapOf("windowStart" to FieldValue.serverTimestamp(), "count" to 1)).await()
            }
        } catch (_: Exception) {}
    }

    /** The newest glows aimed at me; throws on a failed read (the page tells failed from empty). */
    suspend fun fetchRecentGlowers(limit: Long = 50): List<Pair<String, Long>> {
        val me = uid.ifEmpty { return emptyList() }
        val snap = db.collection("glows").whereEqualTo("to", me)
            .orderBy("createdAt", Query.Direction.DESCENDING).limit(limit).get().await()
        return snap.documents.mapNotNull { d ->
            d.getString("from")?.let { it to (d.getTimestamp("createdAt")?.toDate()?.time ?: 0L) }
        }
    }

    // ---- readers -------------------------------------------------------------------------------

    /** Rows for uids, all asked together, in the order given (iOS GlowPeopleLoader). Names come
     *  from the server: a glower is very often somebody you have never chatted with. */
    suspend fun people(uids: List<String>, dates: Map<String, Long> = emptyMap()): GlowLoad<List<GlowPerson>> {
        if (uids.isEmpty()) return GlowLoad.Loaded(emptyList())
        val found = coroutineScope {
            uids.map { u ->
                async {
                    try {
                        db.collection("users").document(u).get().await().data?.let { d ->
                            val p = Person.from(u, d)
                            GlowPerson(u, p.name, p.handle, p.photoUrl, dates[u] ?: 0L)
                        }
                    } catch (_: Exception) { null }
                }
            }.awaitAll()
        }
        return GlowRules.resolved(uids, found)
    }

    /** Somebody's live stories from the public mirror `users/{uid}/publicStories`, newest first. */
    suspend fun postedStories(uid: String): GlowLoad<List<PostedStory>> {
        if (uid.isEmpty()) return GlowLoad.Loaded(emptyList())
        return try {
            val snap = db.collection("users").document(uid).collection("publicStories")
                .whereGreaterThan("expiresAt", Timestamp.now())
                .orderBy("expiresAt", Query.Direction.DESCENDING).limit(60).get().await()
            GlowLoad.Loaded(snap.documents.map { d ->
                val media = d.getString("mediaUrl") ?: ""
                PostedStory(
                    id = d.id,
                    thumbUrl = d.getString("thumbUrl")?.takeIf { it.isNotEmpty() } ?: media,
                    mediaUrl = media,
                    blurThumb = d.getString("blurThumb") ?: "",
                    createdAtMs = d.getTimestamp("createdAt")?.toDate()?.time ?: System.currentTimeMillis(),
                    expiresAtMs = d.getTimestamp("expiresAt")?.toDate()?.time ?: System.currentTimeMillis(),
                    isVideo = d.getString("type") == "video",
                    views = ViewCounts.get(d.id),
                    audience = d.getString("audience") ?: "everyone",
                )
            })
        } catch (_: Exception) { GlowLoad.Failed }
    }

    /** My own live stories, every audience, from the feed already in memory (newest first). */
    fun myPosted(mine: List<Story>): List<PostedStory> = mine.sortedByDescending { it.createdAtMs }.map { s ->
        PostedStory(s.id, s.listThumb, s.mediaUrl, s.blurThumb, s.createdAtMs, s.expiresAtMs, s.isVideo,
            ViewCounts.get(s.id), s.audienceLabel)
    }

    /** One card per glow person, their newest live story. */
    suspend fun storyCards(uids: List<String>): List<GlowStoryCard> = coroutineScope {
        uids.map { u ->
            async {
                val p = people(listOf(u)).valueOrNull?.firstOrNull() ?: return@async null
                val newest = postedStories(u).valueOrNull?.firstOrNull() ?: return@async null
                GlowStoryCard(p, newest)
            }
        }.awaitAll().filterNotNull()
    }

    /** A person's whole live set as one ring for the viewer, oldest to newest. */
    suspend fun storyGroup(person: GlowPerson): StoryGroup? {
        val rows = postedStories(person.id).valueOrNull.orEmpty()
        if (rows.isEmpty()) return null
        return StoryGroup(person.id, person.name, person.photoUrl, rows.reversed().map { s ->
            Story(id = s.id, authorUid = person.id, createdAtMs = s.createdAtMs, expiresAtMs = s.expiresAtMs,
                mediaUrl = s.mediaUrl.ifEmpty { s.thumbUrl }, thumbUrl = s.thumbUrl, isVideo = s.isVideo,
                // Not addressed to me through my chats: no reply bar.
                allowsReplies = false, blurThumb = s.blurThumb)
        })
    }

    /** View counts for my own tiles: the server's counter, else the receipts counted. */
    suspend fun withViewCounts(me: String, rows: List<PostedStory>): List<PostedStory> = coroutineScope {
        val service = StoryService(me)
        val who: (String) -> Pair<String, String?> = { "" to null }
        val counts = rows.map { r ->
            async { service.viewSummary(r.id, who)?.count ?: service.fetchViewers(r.id, who)?.size }
        }.awaitAll()
        val fresh = HashMap<String, Int>()
        val out = rows.mapIndexed { i, r ->
            val n = counts[i]
            if (n == null) r.copy(views = r.views ?: 0) else { fresh[r.id] = n; r.copy(views = n) }
        }
        ViewCounts.put(fresh, out.map { it.id }.toSet())
        out
    }

    /** The last count a story was known to have, so the badge is on the first frame. */
    object ViewCounts {
        private val map = HashMap<String, Int>()
        fun get(id: String): Int? = synchronized(map) { map[id] }
        fun put(counts: Map<String, Int>, alive: Set<String>) = synchronized(map) {
            map.putAll(counts); map.keys.retainAll(alive)
        }
    }

    /** What the Glowing grid saw last time, per account, so it opens on it (iOS GlowStoriesCache). */
    object Cache {
        private fun file(c: Context, uid: String) = File(c.filesDir, "glow-stories-$uid.json")

        fun read(c: Context, uid: String): List<GlowStoryCard>? {
            if (uid.isEmpty()) return null
            return try {
                val arr = JSONArray(file(c, uid).readText())
                val cards = (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    val p = o.getJSONObject("p"); val s = o.getJSONObject("s")
                    GlowStoryCard(
                        GlowPerson(p.getString("id"), p.optString("name"), p.optString("handle"), p.optString("photo").ifEmpty { null }),
                        PostedStory(s.getString("id"), s.optString("thumb"), s.optString("media"), s.optString("blur"),
                            s.optLong("created"), s.optLong("expires"), s.optBoolean("video"), null, s.optString("audience", "everyone")),
                    )
                }
                GlowRules.liveCards(cards, System.currentTimeMillis())
            } catch (_: Exception) { null }
        }

        fun write(c: Context, uid: String, cards: List<GlowStoryCard>) {
            if (uid.isEmpty()) return
            val arr = JSONArray()
            cards.forEach { card ->
                arr.put(JSONObject()
                    .put("p", JSONObject().put("id", card.person.id).put("name", card.person.name)
                        .put("handle", card.person.handle).put("photo", card.person.photoUrl ?: ""))
                    .put("s", JSONObject().put("id", card.story.id).put("thumb", card.story.thumbUrl)
                        .put("media", card.story.mediaUrl).put("blur", card.story.blurThumb)
                        .put("created", card.story.createdAtMs).put("expires", card.story.expiresAtMs)
                        .put("video", card.story.isVideo).put("audience", card.story.audience)))
            }
            runCatching { file(c, uid).writeText(arr.toString()) }
        }

        /** Signing out takes every account's grid with it (iOS clear sweeps the prefix). */
        fun clear(c: Context) {
            c.filesDir.listFiles()?.filter { it.name.startsWith("glow-stories-") }?.forEach { it.delete() }
        }
    }
}
