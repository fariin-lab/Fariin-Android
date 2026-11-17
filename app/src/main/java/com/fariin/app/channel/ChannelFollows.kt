package com.fariin.app.channel

import com.fariin.app.data.Conversation
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * The channels I follow (Kulan-iOS ChannelFollows): users/{me}/channels (server written by
 * followChannel; mine to mark read / muted / pinned / archived), my picks on posts (`rx` on each
 * entry, and users/{me}/channelRx for channels I run), and a live document per followed channel so
 * the chat list can show it (followers are not in `users`, so the normal chat list never has them).
 */
object ChannelFollows {
    data class Entry(
        val read: Int, val muted: Boolean, val mutedUntil: Double = 0.0,
        val pinned: Boolean = false, val archived: Boolean = false, val at: Double = 0.0,
    ) {
        fun isMuted(now: Double = System.currentTimeMillis().toDouble()): Boolean = muted && (mutedUntil <= 0 || mutedUntil > now)
    }

    const val MAX_ENTRIES = 1000L
    const val MAX_LIVE = 300
    /** A LOW phone keeps a live document for this many channels only (each one is a listener whose
     *  every answer re-saves the follow list); the rest keep their last saved document. */
    const val MAX_LIVE_LOW = 50
    /** Channel document answers are gathered this long before the list is published and saved. */
    const val PUBLISH_MS = 250L

    fun liveCap(cls: com.fariin.app.perf.PerfClass) = if (cls == com.fariin.app.perf.PerfClass.LOW) MAX_LIVE_LOW else MAX_LIVE

    /**
     * Which channels get a live document: the pinned first, then the most recently active (the later
     * of when I followed it and its last post), up to [cap]. Pure, unit tested.
     */
    fun pickLive(entries: Map<String, Entry>, lastActive: Map<String, Double>, cap: Int): Set<String> =
        entries.entries.sortedWith(compareByDescending<Map.Entry<String, Entry>> { it.value.pinned }
            .thenByDescending { maxOf(it.value.at, lastActive[it.key] ?: 0.0) })
            .take(cap).map { it.key }.toSet()

    private val _entries = MutableStateFlow<Map<String, Entry>>(emptyMap())
    val entries: StateFlow<Map<String, Entry>> = _entries
    /** Followed channels' documents as conversations (my read count lives in `entries`). */
    private val _docs = MutableStateFlow<Map<String, Map<String, Any?>>>(emptyMap())
    val docs: StateFlow<Map<String, Map<String, Any?>>> = _docs
    /** cid -> mid -> my emoji, from my entries' `rx` and channelRx (merged). */
    private val _rx = MutableStateFlow<Map<String, Map<String, String>>>(emptyMap())
    val rx: StateFlow<Map<String, Map<String, String>>> = _rx
    /** A pick on screen before the server has it (cid -> mid -> emoji, "" = taken back). */
    private val _pending = MutableStateFlow<Map<String, Map<String, String>>>(emptyMap())
    val pending: StateFlow<Map<String, Map<String, String>>> = _pending
    /** Counts the server answered for a post, held [SERVED_HOLD_MS] (iOS answerHolds, 75 s), then the
     *  post's own counts show again: the minute batch writes the totals onto the post by then. */
    private val _served = MutableStateFlow<Map<String, Map<String, Int>>>(emptyMap())
    val served: StateFlow<Map<String, Map<String, Int>>> = _served
    /** My follow (+1) or leave (-1) until the channel's stored followerCount moves (ChannelCountBias). */
    private val _bias = MutableStateFlow<Map<String, Int>>(emptyMap())
    val biasFlow: StateFlow<Map<String, Int>> = _bias

    @Volatile private var uid: String? = null
    private var listReg: ListenerRegistration? = null
    private var adminRxReg: ListenerRegistration? = null
    private val docRegs = HashMap<String, ListenerRegistration>()
    private var followRx: Map<String, Map<String, String>> = emptyMap()
    private var adminRx: Map<String, Map<String, String>> = emptyMap()
    // Read and written by the channel document answers on the listen thread too.
    private val storedAtBias = java.util.concurrent.ConcurrentHashMap<String, Int>()
    /** The channels with a live document now; an answer for any other is dropped. */
    @Volatile private var live: Set<String> = emptySet()
    /** Bumped by stop(), so an answer for the account before is never kept. */
    @Volatile private var gen = 0
    // Listen thread only: answers waiting for the next publish.
    private val inbox = HashMap<String, Map<String, Any?>>()
    private var inboxFromServer = false
    private val throttle = com.fariin.app.perf.Throttle(PUBLISH_MS)
    /** Followed channels past the live cap that already had their one plain read. */
    private val fetchedOnce = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    /** Which answer each `_served` entry is, so an older timer never drops a newer answer. */
    private val servedToken = HashMap<String, Long>()
    private var servedClock = 0L
    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private const val SERVED_HOLD_MS = 75_000L

    fun entry(cid: String): Entry? = _entries.value[cid]
    fun follows(cid: String): Boolean = _entries.value.containsKey(cid)

    /**
     * The saved follow list and channel documents, before the listeners answer (iOS d15f501ef: a
     * followed channel popped into the chat list 0.15 s after the first frame and pushed the rows
     * below down). Kept until the live answers replace them.
     */
    fun seedFromDisk(me: String) {
        if (_entries.value.isNotEmpty() || _docs.value.isNotEmpty()) return
        val (e, d) = com.fariin.app.local.LocalCache.peekFollows(me) ?: com.fariin.app.local.LocalCache.loadFollows(me)
        if (e.isEmpty()) return
        _entries.value = e.associate { (cid, m) -> cid to Entry(
            read = (m["read"] as? Number)?.toInt() ?: 0, muted = m["muted"] as? Boolean ?: false,
            mutedUntil = (m["mutedUntil"] as? Number)?.toDouble() ?: 0.0,
            pinned = m["pinned"] as? Boolean ?: false, archived = m["archived"] as? Boolean ?: false,
            at = (m["at"] as? Number)?.toDouble() ?: 0.0) }
        _docs.value = d.toMap()
    }

    private fun saveToDisk() {
        val me = uid ?: return
        val e = _entries.value.map { (cid, x) -> cid to mapOf<String, Any?>("read" to x.read, "muted" to x.muted,
            "mutedUntil" to x.mutedUntil, "pinned" to x.pinned, "archived" to x.archived, "at" to x.at) }
        val d = _docs.value.filterKeys { it in _entries.value }.toList()
        com.fariin.app.local.LocalCache.saveFollows(me, e, d)
    }

    fun start(me: String) {
        if (uid == me && listReg != null) return
        stop()
        uid = me
        seedFromDisk(me)
        val db = com.fariin.app.Fb.db
        listReg = db.collection("users").document(me).collection("channels").limit(MAX_ENTRIES)
            .addSnapshotListener { snap, _ ->
                snap ?: return@addSnapshotListener
                val next = HashMap<String, Entry>()
                val rx = HashMap<String, Map<String, String>>()
                for (d in snap.documents) {
                    val data = d.data ?: continue
                    (data["rx"] as? Map<*, *>)?.let { r ->
                        val m = r.entries.mapNotNull { (k, v) -> if (k is String && v is String && v.isNotEmpty()) k to v else null }.toMap()
                        if (m.isNotEmpty()) rx[d.id] = m
                    }
                    next[d.id] = Entry(
                        read = (data["read"] as? Number)?.toInt() ?: 0, muted = data["muted"] as? Boolean ?: false,
                        mutedUntil = (data["mutedUntil"] as? Number)?.toDouble() ?: 0.0,
                        pinned = data["pinned"] as? Boolean ?: false, archived = data["archived"] as? Boolean ?: false,
                        at = Conversation.millis(data["at"]),
                    )
                }
                _entries.value = next
                if (!snap.metadata.isFromCache) saveToDisk()
                followRx = rx
                mergeRx()
                attachDocs(next)
            }
        adminRxReg = db.collection("users").document(me).collection("channelRx").limit(MAX_LIVE.toLong())
            .addSnapshotListener { snap, _ ->
                snap ?: return@addSnapshotListener
                adminRx = snap.documents.mapNotNull { d ->
                    val r = d.get("rx") as? Map<*, *> ?: return@mapNotNull null
                    d.id to r.entries.mapNotNull { (k, v) -> if (k is String && v is String) k to v else null }.toMap()
                }.toMap()
                mergeRx()
            }
    }

    fun stop() {
        listReg?.remove(); listReg = null
        adminRxReg?.remove(); adminRxReg = null
        docRegs.values.forEach { it.remove() }; docRegs.clear()
        gen++
        live = emptySet()
        fetchedOnce.clear()
        com.fariin.app.perf.ListenThread.exec.execute { inbox.clear(); inboxFromServer = false; throttle.reset() }
        _entries.value = emptyMap(); _docs.value = emptyMap(); _rx.value = emptyMap(); _pending.value = emptyMap()
        // The next account never sees this one's served counts or follower-count guesses.
        _served.value = emptyMap(); _bias.value = emptyMap()
        synchronized(servedToken) { servedToken.clear() }
        storedAtBias.clear()
        followRx = emptyMap(); adminRx = emptyMap()
        uid = null
    }

    private fun mergeRx() {
        val out = HashMap<String, Map<String, String>>(followRx)
        for ((cid, m) in adminRx) out[cid] = (out[cid] ?: emptyMap()) + m
        _rx.value = out
    }

    /**
     * The chosen channels ([pickLive]) get a live document. Their answers run on the listen thread
     * and are gathered for [PUBLISH_MS] before the list is published and saved once: with 300
     * listeners each answer used to copy the whole map and re-save every document on the main thread.
     */
    private fun attachDocs(entries: Map<String, Entry>) {
        val ctx = runCatching { com.google.firebase.FirebaseApp.getInstance().applicationContext }.getOrNull()
        val cls = ctx?.let { com.fariin.app.perf.DeviceClass.get(it) } ?: com.fariin.app.perf.PerfClass.AVERAGE
        val docsNow = _docs.value
        val active = docsNow.mapValues { (_, d) -> Conversation.millis(d["updatedAt"]) }
        val want = pickLive(entries, active, liveCap(cls))
        live = want
        for (gone in docRegs.keys - want) {
            docRegs.remove(gone)?.remove()
            // Still followed, only past the cap (a LOW phone): its last document stays so the row stays.
            if (gone !in entries) _docs.update { it - gone }
        }
        val db = com.fariin.app.Fb.db
        val g = gen
        for (cid in want - docRegs.keys) {
            docRegs[cid] = db.collection("conversations").document(cid)
                .addSnapshotListener(com.fariin.app.perf.ListenThread.exec) { s, _ -> onDoc(g, cid, s?.data, s?.metadata?.isFromCache == false) }
        }
        // Past the cap with nothing saved: one plain read, so the row still shows.
        for (cid in entries.keys) {
            if (cid in want || docsNow.containsKey(cid) || !fetchedOnce.add(cid)) continue
            db.collection("conversations").document(cid).get()
                .addOnSuccessListener(com.fariin.app.perf.ListenThread.exec) { s -> onDoc(g, cid, s.data, !s.metadata.isFromCache) }
        }
    }

    /** Listen thread. */
    private fun onDoc(g: Int, cid: String, d: Map<String, Any?>?, fromServer: Boolean) {
        if (d == null || g != gen) return
        if (d["channel"] != true) return
        if (cid !in live && cid !in fetchedOnce) return
        (d["followerCount"] as? Number)?.toInt()?.let { stored ->
            val at = storedAtBias[cid]
            if (at != null && at != stored) { storedAtBias.remove(cid); _bias.update { it - cid } }
        }
        inbox[cid] = d
        if (fromServer) inboxFromServer = true
        val wait = throttle.offer(android.os.SystemClock.uptimeMillis())
        if (wait == 0L) publish(g) else if (wait > 0) com.fariin.app.perf.ListenThread.later(wait) { publish(g) }
        // iOS attachDocListener: the channel's shared wallpaper lands now, not when the chat opens.
        runCatching { com.google.firebase.FirebaseApp.getInstance().applicationContext }.getOrNull()
            ?.let { com.fariin.app.data.ChatLookStore.syncGroupWallpaper(it, cid, d["wallpaper"] as? String) }
    }

    /** Listen thread: one publish and at most one save for every answer gathered. */
    private fun publish(g: Int) {
        throttle.ran(android.os.SystemClock.uptimeMillis())
        if (inbox.isEmpty()) return
        val batch = HashMap(inbox); inbox.clear()
        val save = inboxFromServer; inboxFromServer = false
        if (g != gen || uid == null) return
        val keep = live
        _docs.update { it + batch.filterKeys { cid -> cid in keep || cid in fetchedOnce } }
        if (save) saveToDisk()
    }

    /** Each followed channel as a conversation for the chat list. */
    fun conversations(): List<Conversation> = _docs.value.mapNotNull { (cid, d) ->
        val e = _entries.value[cid] ?: return@mapNotNull null
        val conv = Conversation.fromFirestore(cid, d)
        val uid = com.fariin.app.Fb.auth.currentUser?.uid ?: ""
        // My pin and archive on a channel I follow live on MY follow entry (a follower may not write
        // the channel's document); the list reads them as it does a chat's own (2026-11-06).
        conv.copy(followerRead = e.read, followerMuted = e.isMuted(),
            pinnedBy = if (uid.isEmpty()) conv.pinnedBy else conv.pinnedBy + (uid to e.pinned),
            archivedBy = if (uid.isEmpty()) conv.archivedBy else conv.archivedBy + (uid to e.archived))
    }

    // ---- Reactions ----------------------------------------------------------------------------

    /** My pick as shown: one on its way first, then the server's. */
    fun myReaction(cid: String, mid: String): String? {
        _pending.value[cid]?.get(mid)?.let { return it.ifEmpty { null } }
        return _rx.value[cid]?.get(mid)
    }

    fun confirmedReaction(cid: String, mid: String): String? = _rx.value[cid]?.get(mid)

    fun setPending(cid: String, mid: String, emoji: String?) =
        _pending.update { it + (cid to ((it[cid] ?: emptyMap()) + (mid to (emoji ?: "")))) }

    fun settle(cid: String, mid: String, counts: Map<String, Int>?, confirmed: String?) {
        // An answer that lands after sign-out belongs to the old account.
        if (uid == null) return
        _pending.update { p -> p + (cid to ((p[cid] ?: emptyMap()) - mid)) }
        _rx.update { r ->
            val m = (r[cid] ?: emptyMap()).toMutableMap()
            if (confirmed == null) m.remove(mid) else m[mid] = confirmed
            r + (cid to m)
        }
        if (counts != null) {
            val key = "$cid/$mid"
            val token = synchronized(servedToken) { (++servedClock).also { servedToken[key] = it } }
            _served.update { s -> s + (key to counts) }
            main.postDelayed({
                val still = synchronized(servedToken) { (servedToken[key] == token).also { if (it) servedToken.remove(key) } }
                if (still) _served.update { s -> s - key }
            }, SERVED_HOLD_MS)
        }
    }

    fun dropPending(cid: String, mid: String) = _pending.update { p -> p + (cid to ((p[cid] ?: emptyMap()) - mid)) }

    /** The counts a post shows: the server's last answer while it is newer than the post's own. */
    fun countsFor(cid: String, mid: String, post: Map<String, Int>): Map<String, Int> = _served.value["$cid/$mid"] ?: post

    // ---- Counts -------------------------------------------------------------------------------

    fun bias(cid: String, delta: Int) {
        if (storedAtBias[cid] == null) storedAtBias[cid] = (_docs.value[cid]?.get("followerCount") as? Number)?.toInt() ?: -1
        _bias.update { it + (cid to ((it[cid] ?: 0) + delta)) }
    }

    /** The server's stored + pending replaces the guess (ChannelService.adoptServerCount). */
    fun adoptCount(cid: String, r: Map<*, *>?) {
        val stored = (r?.get("followerCount") as? Number)?.toInt() ?: return
        val pending = (r["pending"] as? Number)?.toInt() ?: return
        storedAtBias[cid] = stored
        _bias.update { it + (cid to pending) }
    }

    /** A stored follower count with my own change on it. */
    fun adjusted(cid: String, stored: Int): Int = maxOf(0, stored + (_bias.value[cid] ?: 0))

    fun noteLocal(cid: String, read: Int? = null, muted: Boolean? = null, mutedUntil: Double? = null,
                  pinned: Boolean? = null, archived: Boolean? = null) {
        _entries.update { all ->
            val e = all[cid] ?: return@update all
            all + (cid to e.copy(read = read ?: e.read, muted = muted ?: e.muted, mutedUntil = mutedUntil ?: e.mutedUntil,
                pinned = pinned ?: e.pinned, archived = archived ?: e.archived))
        }
    }
}
