package com.fariin.app.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** One announcement's kind (iOS AnnouncementKind). */
enum class AnnouncementKind(val raw: String, val label: String) {
    FEATURE("feature", "New Feature"),
    RELEASE("release", "Release Notes"),
    MAINTENANCE("maintenance", "Maintenance"),
    SECURITY("security", "Security"),
    NEWS("news", "News");

    companion object {
        fun from(raw: String?): AnnouncementKind = entries.firstOrNull { it.raw == raw } ?: NEWS
    }
}

/** A button under an announcement (iOS AnnouncementButton). Times are server ms. */
data class AnnouncementButton(val label: String, val action: String, val value: String, val until: Double? = null) {
    /** Nothing draws a button we cannot honour. appStore is iOS only, so it never shows here. */
    fun isUsable(now: Double): Boolean = when (action) {
        LINK -> value.startsWith("http://") || value.startsWith("https://")
        SCREEN -> false   // no Android pages wired to the iOS screen names yet
        REJECT_OWNERSHIP -> value.isNotEmpty() && (until ?: 0.0) > now
        else -> false
    }

    companion object {
        const val LINK = "link"
        const val APP_STORE = "appStore"
        const val SCREEN = "screen"
        const val REJECT_OWNERSHIP = "rejectOwnership"
        private val known = setOf(LINK, APP_STORE, SCREEN, REJECT_OWNERSHIP)

        fun from(m: Map<*, *>): AnnouncementButton? {
            val label = m["label"] as? String ?: return null
            val action = m["action"] as? String ?: return null
            if (label.isEmpty() || action !in known) return null
            return AnnouncementButton(label, action, m["value"] as? String ?: "",
                m["until"]?.let { Conversation.millis(it) }?.takeIf { it > 0 })
        }
    }
}

/** Who an announcement is for, read on the phone (iOS AnnouncementAudience). */
data class AnnouncementAudience(
    val scope: String = "everyone",
    val countries: List<String> = emptyList(),
    val ppm: Int = 1_000_000,
    val minBuild: Int = 0,
    val maxBuild: Int = 0,
) {
    companion object {
        fun from(m: Map<*, *>?): AnnouncementAudience = AnnouncementAudience(
            scope = (m?.get("scope") as? String)?.takeIf { it in setOf("everyone", "countries", "chosen") } ?: "everyone",
            countries = (m?.get("countries") as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
            ppm = (m?.get("ppm") as? Number)?.toInt() ?: 1_000_000,
            minBuild = (m?.get("minBuild") as? Number)?.toInt() ?: 0,
            maxBuild = (m?.get("maxBuild") as? Number)?.toInt() ?: 0,
        )
    }
}

/** One announcement (iOS Announcement). Plain text: a public broadcast. Times are server ms, 0 = none. */
data class Announcement(
    val id: String,
    val kind: AnnouncementKind = AnnouncementKind.NEWS,
    val title: String = "",
    val body: String = "",
    val mediaUrl: String? = null,
    val buttons: List<AnnouncementButton> = emptyList(),
    val audience: AnnouncementAudience = AnnouncementAudience(),
    val publishAt: Double = 0.0,
    val expiresAt: Double? = null,
    val deleted: Boolean = false,
    val editedAt: Double? = null,
    val createdAt: Double = 0.0,
    /** A copy written to users/{me}/announcements: picked by hand, so no country or rollout check. */
    val isPersonal: Boolean = false,
) {
    /** The words as the card reads them, for Copy and Forward: the title on its own line, then the body. */
    val plainText: String get() = listOf(title, body).filter { it.isNotEmpty() }.joinToString("\n")

    /** The chat list line: "Title: the message" on one line. */
    val preview: String get() {
        val text = body.lines().joinToString(" ").trim()
        return when {
            title.isEmpty() -> text
            text.isEmpty() -> title
            else -> "$title: $text"
        }
    }

    /** Display order: the publish time, else the write time. */
    val sortAt: Double get() = if (publishAt > 0) publishAt else createdAt

    /** When it reached the reader, what unread is measured against (a backdated post arrives when written). */
    val arrivedAt: Double get() = maxOf(sortAt, createdAt)

    /** Is this for me, on this build, right now? `now` is server ms. */
    fun reaches(uid: String, build: Int, region: String?, now: Double): Boolean {
        if (deleted) return false
        if (publishAt > now) return false
        if (expiresAt != null && expiresAt <= now) return false
        // The build limits are iPhone build numbers (the panel has no Android field yet), so a post
        // aimed at some builds is not for this phone at all. `build` stays for when it gets one.
        if (audience.minBuild > 0 || audience.maxBuild > 0) return false
        if (isPersonal) return true
        if (audience.scope == "countries" && (region == null || region !in audience.countries)) return false
        // A broadcast never carries `chosen`; if one does, silence is the safe reading.
        if (audience.scope == "chosen") return false
        if (audience.ppm < 1_000_000 && bucket(id, uid) >= audience.ppm) return false
        return true
    }

    companion object {
        /** The rollout bucket: SHA-256 of "salt.uid", first 8 bytes big-endian, mod 1_000_000. */
        fun bucket(salt: String, uid: String): Int {
            val d = MessageDigest.getInstance("SHA-256").digest("$salt.$uid".toByteArray(Charsets.UTF_8))
            var v = 0UL
            for (i in 0 until 8) v = (v shl 8) or (d[i].toULong() and 0xFFUL)
            return (v % 1_000_000UL).toInt()
        }

        fun from(id: String, d: Map<String, Any?>, personal: Boolean = false) = Announcement(
            id = id,
            kind = AnnouncementKind.from(d["kind"] as? String),
            title = d["title"] as? String ?: "",
            body = d["body"] as? String ?: "",
            mediaUrl = (d["mediaUrl"] as? String)?.takeIf { it.isNotEmpty() },
            buttons = (d["buttons"] as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.let(AnnouncementButton::from) } ?: emptyList(),
            audience = AnnouncementAudience.from(d["audience"] as? Map<*, *>),
            publishAt = Conversation.millis(d["publishAt"]),
            expiresAt = d["expiresAt"]?.let { Conversation.millis(it) }?.takeIf { it > 0 },
            deleted = d["deleted"] as? Boolean ?: false,
            editedAt = d["editedAt"]?.let { Conversation.millis(it) }?.takeIf { it > 0 },
            createdAt = Conversation.millis(d["createdAt"]),
            isPersonal = personal,
        )
    }
}

/** My half of the channel, users/{me}/officialChannel/state. Only what this slice reads. */
data class OfficialChannelState(
    val muted: Boolean = false,
    /** When a timed mute ends, server ms; 0 = Always. */
    val mutedUntil: Double = 0.0,
    val lastReadAt: Double = 0.0,
    val clearedAt: Double = 0.0,
    val hiddenIds: Set<String> = emptySet(),
) {
    fun isMutedNow(now: Double) = muted && (mutedUntil == 0.0 || now < mutedUntil)

    /** A snapshot merged onto what the phone holds: the two watermarks only move forward. */
    fun merged(incoming: OfficialChannelState) = incoming.copy(
        lastReadAt = maxOf(lastReadAt, incoming.lastReadAt),
        clearedAt = maxOf(clearedAt, incoming.clearedAt),
    )

    companion object {
        fun from(d: Map<String, Any?>?) = OfficialChannelState(
            muted = d?.get("muted") as? Boolean ?: false,
            mutedUntil = Conversation.millis(d?.get("mutedUntil")),
            lastReadAt = Conversation.millis(d?.get("lastReadAt")),
            clearedAt = Conversation.millis(d?.get("clearedAt")),
            hiddenIds = (d?.get("hiddenIds") as? List<*>)?.filterIsInstance<String>()?.toSet() ?: emptySet(),
        )
    }
}

/**
 * The official Fariin chat (iOS OfficialChannel / OfficialChannelStore): broadcast announcements,
 * personal copies and my state, merged into what this person sees. Read only: nobody replies.
 */
object OfficialChannel {
    const val CID = "official-fariin"
    const val NAME = "Fariin"
    const val SUBTITLE = "Official Chat"
    const val CANNOT_REPLY = "Only Fariin can send messages"

    data class View(
        val visible: List<Announcement> = emptyList(),
        val state: OfficialChannelState = OfficialChannelState(),
        val loaded: Boolean = false,
    ) {
        val unread: Int get() = unreadCount(visible, state)
        val latest: Announcement? get() = visible.lastOrNull()
    }

    /** The pure filter (iOS recompute): personal wins on content, a deletion wins from either side. */
    fun visible(
        broadcast: List<Announcement>, personal: List<Announcement>, state: OfficialChannelState,
        uid: String, build: Int, region: String?, now: Double, joinedAt: Double? = null,
    ): List<Announcement> {
        val byId = LinkedHashMap<String, Announcement>()
        for (a in broadcast) byId[a.id] = a
        for (a in personal) byId[a.id] = if (byId[a.id]?.deleted == true) a.copy(deleted = true) else a
        return byId.values
            .filter { it.reaches(uid, build, region, now) }
            // Nothing broadcast before the account existed.
            .filter { it.isPersonal || joinedAt == null || it.sortAt >= joinedAt }
            .filter { it.arrivedAt > state.clearedAt && it.id !in state.hiddenIds }
            .sortedWith(compareBy<Announcement> { it.sortAt }.thenBy { it.id })
    }

    fun unreadCount(visible: List<Announcement>, state: OfficialChannelState): Int =
        visible.count { it.arrivedAt > state.lastReadAt }

    /** The read watermark to write on open, or null when it would not move forward. */
    fun readMark(visible: List<Announcement>, state: OfficialChannelState): Double? =
        visible.maxOfOrNull { it.arrivedAt }?.takeIf { it > state.lastReadAt }

    private val _view = MutableStateFlow(View())
    val view: StateFlow<View> = _view

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var uid: String? = null
    private var build = 0
    private var regs = listOf<ListenerRegistration>()
    private var broadcast = listOf<Announcement>()
    private var personal = listOf<Announcement>()
    private var state = OfficialChannelState()
    private val answered = HashSet<String>()
    private var timer: Job? = null

    private var app: android.content.Context? = null

    fun start(context: android.content.Context, me: String, appBuild: Int) {
        if (uid == me && regs.isNotEmpty()) return
        stop()
        app = context.applicationContext
        uid = me
        build = appBuild
        val db = FirebaseFirestore.getInstance()
        val user = db.collection("users").document(me)
        // No `where` on publishAt: a query fixed at attach time misses the next send. `reaches` decides.
        regs = listOf(
            db.collection("announcements").orderBy("publishAt", Query.Direction.DESCENDING).limit(100)
                .addSnapshotListener { snap, _ ->
                    if (snap != null) broadcast = snap.documents.map { Announcement.from(it.id, it.data ?: emptyMap()) }
                    answer("broadcast")
                },
            user.collection("announcements").orderBy("publishAt", Query.Direction.DESCENDING).limit(100)
                .addSnapshotListener { snap, _ ->
                    if (snap != null) personal = snap.documents.map { Announcement.from(it.id, it.data ?: emptyMap(), personal = true) }
                    answer("personal")
                },
            // An error keeps what we have (a nil snapshot is not a missing document).
            user.collection("officialChannel").document("state").addSnapshotListener { snap, _ ->
                if (snap != null) state = state.merged(OfficialChannelState.from(snap.data))
                answer("state")
                // Fires on launch, on my mute and on my other phone's mute: the one place topics follow.
                if (snap != null) syncTopics()
            },
        )
    }

    fun stop() {
        regs.forEach { it.remove() }
        regs = emptyList()
        timer?.cancel(); timer = null
        uid = null
        broadcast = emptyList(); personal = emptyList(); state = OfficialChannelState()
        answered.clear()
        _view.value = View()
    }

    private fun answer(source: String) {
        answered.add(source)
        recompute()
    }

    private fun recompute() {
        val me = uid ?: return
        val now = System.currentTimeMillis().toDouble()
        val joined = FirebaseAuth.getInstance().currentUser?.metadata?.creationTimestamp?.toDouble()?.takeIf { it > 0 }
        val next = visible(broadcast, personal, state, me, build, java.util.Locale.getDefault().country.ifEmpty { null }, now, joined)
        // One timer for the next moment something falls due or expires.
        timer?.cancel()
        val boundary = (broadcast + personal).flatMap { listOfNotNull(it.publishAt, it.expiresAt) }.filter { it > now }.minOrNull()
        if (boundary != null) timer = scope.launch { delay(maxOf(250L, (boundary - now).toLong() + 250L)); recompute() }
        _view.value = View(next, state, loaded = answered.size >= 3)
    }

    /** Opening the chat reads everything on screen: the watermark is the latest arrival shown. */
    fun markRead() {
        val me = uid ?: return
        val at = readMark(_view.value.visible, state) ?: return
        state = state.copy(lastReadAt = at)   // optimistic, so the badge clears on the tap
        _view.value = _view.value.copy(state = state)
        FirebaseFirestore.getInstance().collection("users").document(me)
            .collection("officialChannel").document("state")
            .set(mapOf("lastReadAt" to at), SetOptions.merge())
    }

    private fun syncTopics() {
        val c = app ?: return
        com.fariin.app.push.OfficialPushTopics.sync(c, state.isMutedNow(System.currentTimeMillis().toDouble()))
    }

    /** The bell: on or off with no end (iOS setMuted; a timed mute is the profile's, not built yet). */
    fun setMuted(muted: Boolean) {
        val me = uid ?: return
        state = state.copy(muted = muted, mutedUntil = 0.0)
        recompute()
        syncTopics()   // at once, not only when the write comes back
        FirebaseFirestore.getInstance().collection("users").document(me)
            .collection("officialChannel").document("state")
            .set(mapOf("muted" to muted, "mutedUntil" to 0, "mutedAt" to System.currentTimeMillis().toDouble()), SetOptions.merge())
    }

    /** Forward notes' words to chats, one forwarded text each, in order, sealed per chat. Outlives the page. */
    fun forward(context: android.content.Context, me: String, texts: List<String>, targets: List<String>) {
        val crypto = CryptoHub.get(context.applicationContext)
        scope.launch {
            for (cid in targets) {
                val repo = ThreadRepository.openOrQuiet(cid, me, crypto, scope)
                for (t in texts) try { repo.sendForwardedText(t, forwarded = true) } catch (_: Exception) {}
            }
        }
    }

    /** Delete for me: hidden on this account only, the announcement stays for everybody else. */
    fun hide(ids: List<String>) {
        val me = uid ?: return
        if (ids.isEmpty()) return
        state = state.copy(hiddenIds = state.hiddenIds + ids)
        recompute()
        FirebaseFirestore.getInstance().collection("users").document(me)
            .collection("officialChannel").document("state")
            .set(mapOf("hiddenIds" to com.google.firebase.firestore.FieldValue.arrayUnion(*ids.toTypedArray())), SetOptions.merge())
    }

    const val REJECT_OFFLINE = "Could not reject ownership. Check your connection and try again."
    const val REJECT_TITLE = "Reject ownership?"
    const val REJECT_MESSAGE = "The group goes back to its previous owner, or to its longest-standing admin if they have left. You keep the role you had before."
    fun rejectDone(returning: Boolean) = if (returning) "Done. The group is back with its previous owner."
        else "Done. The group now belongs to its longest-standing admin."

    /** groups:rejectGroupOwnership {cid} -> {returning}; a refusal's words are the server's. */
    suspend fun rejectOwnership(cid: String): Boolean {
        try {
            val r = FirebaseFunctions.getInstance(AuthRepository.FUNCTIONS_REGION).getHttpsCallable("rejectGroupOwnership")
                .withTimeout(20, TimeUnit.SECONDS).call(mapOf("cid" to cid)).await()
            return (r.data as? Map<*, *>)?.get("returning") as? Boolean ?: true
        } catch (e: FirebaseFunctionsException) {
            val reason = OwnerTransfer.reasonOf(e.details)
            if (reason.isNotEmpty()) throw OwnerTransfer.Refusal(reason, e.message?.takeIf { it.isNotBlank() } ?: REJECT_OFFLINE)
            throw OwnerTransfer.Refusal("", REJECT_OFFLINE)
        } catch (e: OwnerTransfer.Refusal) {
            throw e
        } catch (_: Exception) {
            throw OwnerTransfer.Refusal("", REJECT_OFFLINE)
        }
    }
}
