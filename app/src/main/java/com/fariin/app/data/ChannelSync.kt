package com.fariin.app.data

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * CHANNEL SYNC (CHANNEL-SYNC-SPEC.md 5-8; iOS ThreadRepository's channel extension on the
 * channel-link branch). The thin adapter between Firestore and [ChannelHistory], used ONLY for a
 * channel its member or follower opens. Groups, 1:1 chats, visitors and a channel whose docs carry no
 * `chg` yet keep ThreadRepository's window path, untouched.
 *
 * - Open: a store this run of the app already holds shows at once, then the listeners.
 * - Probe: a channel with no store waits for the newest window's first server answer. Docs carrying
 *   `chg`: the store starts from that window. None: today's window path for this visit.
 * - Sync: `messages where chg > lastChg order by chg limit 200`, each doc applied as add / edit /
 *   delete; hard deletes and clears from `changes`; the newest window applied as changes too, so a
 *   send shows at once. Nothing here drops a row for being absent from some window.
 * - Fill: while fewer than 40 visible posts sit above the first hole, read the hole (pages of 60,
 *   createdAt-descending), the whole span marked read, deleted posts included.
 * - Too long: 200 changes waiting at attach. The newest page goes on top, a hole under it, and the
 *   old rows stay until a page proves them current.
 *
 * The store lives in memory for this run of the app (per account and channel, [Registry]); a cold
 * start paints from the saved thread (LocalCache) and probes again.
 */
class ChannelSync(
    private val cid: String,
    private val me: String,
    private val convRef: DocumentReference,
    private val scope: CoroutineScope,
    private val pageSize: Long,
    /** Every doc ThreadRepository holds from its window path (adopted at the probe). */
    private val held: () -> List<DocumentSnapshot>,
    /** The list changed: ThreadRepository rebuilds (`loaded` true when the store painted the open). */
    private val onChange: (loaded: Boolean?) -> Unit,
    /** loadingOlder / canLoadOlder for the view (null = leave). */
    private val setOlder: (loading: Boolean?, can: Boolean?) -> Unit,
) {
    private enum class Mode { PROBING, SYNCED, OFF }

    @Volatile private var mode = Mode.PROBING
    private var history: ChannelHistory<DocumentSnapshot>? = null
    private var headReg: ListenerRegistration? = null
    private var chgReg: ListenerRegistration? = null
    private var goneReg: ListenerRegistration? = null
    private var chgToken = 0
    private var goneToken = 0
    private var firstServer = false
    private var attachedAt = 0L
    private var tooLongRunning = false
    @Volatile private var loading = false
    private var fillPages = 0
    private var olderNeedsRetry = false
    private var stopped = false

    /** The rows ThreadRepository draws instead of its window, or null while it should use its own. */
    fun docsOrNull(): List<DocumentSnapshot>? {
        val h = history ?: return null
        if (mode != Mode.SYNCED) return null
        return synchronized(h) { h.values() }
    }

    fun start() {
        val h = Registry.get(me, cid)
        history = h
        val synced = synchronized(h) { h.isSynced }
        if (synced) {
            mode = Mode.SYNCED
            synchronized(h) { if (h.size > BOUND_AT) h.bound(DISK_KEEP, ::visible) }
            onChange(if (h.size > 0) true else null)
            setOlder(null, synchronized(h) { h.topHole != null })
            attachListeners()
        } else {
            mode = Mode.PROBING
        }
        attachHead()
    }

    fun stop() {
        stopped = true
        headReg?.remove(); headReg = null
        chgReg?.remove(); chgReg = null
        goneReg?.remove(); goneReg = null
        chgToken++; goneToken++
        // The bound the disk would keep (iOS 300 visible): reopening finds the same rows and holes.
        history?.let { h -> synchronized(h) { if (h.size > BOUND_AT) h.bound(DISK_KEEP, ::visible) } }
    }

    /** Back in front or back online: pick up from the last change applied. */
    fun reattach() {
        if (stopped || mode != Mode.SYNCED || System.currentTimeMillis() - attachedAt < 2_000) return
        attachListeners()
        if (olderNeedsRetry) { olderNeedsRetry = false; fill() }
    }

    // ---- Probe and listeners ----------------------------------------------------------------

    /** The newest window: the probe's answer, then applied as changes (never a delete pass). */
    private fun attachHead() {
        headReg = convRef.collection("messages")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(pageSize)   // the same query as ThreadRepository's window: one listen on the wire
            .addSnapshotListener(MetadataChanges.INCLUDE) { snap, _ ->
                if (snap == null || stopped) return@addSnapshotListener
                when (mode) {
                    Mode.OFF -> return@addSnapshotListener
                    Mode.PROBING -> if (!snap.metadata.isFromCache) probe(snap.documents)
                    Mode.SYNCED -> {
                        val h = history ?: return@addSnapshotListener
                        // Not my unconfirmed writes: the pending row shows the send, and a write the
                        // server refuses must not stay in the store.
                        val docs = snap.documents.filter { !it.metadata.hasPendingWrites() }
                        val changed = synchronized(h) { applyAll(h, docs) }
                        if (changed > 0) commit()
                    }
                }
            }
    }

    private fun probe(docs: List<DocumentSnapshot>) {
        val h = history ?: return
        val top = docs.mapNotNull { chgOf(it) }.maxOrNull()
        if (top == null) {
            // No `chg` yet (server not stamped this channel): today's window path for this visit.
            mode = Mode.OFF
            headReg?.remove(); headReg = null
            return
        }
        val now = System.currentTimeMillis()
        val windowIds = docs.mapTo(HashSet()) { it.id }
        synchronized(h) {
            // Rows held outside the window (older pages): kept, not yet proven. Adopted BEFORE the
            // page, so a short window (the whole channel) drops the ones the server no longer has.
            h.adoptUnverified(held().filter { it.id !in windowIds }.mapNotNull { docOf(it) })
            h.applyPage(docs.mapNotNull { docOf(it) }, ChannelHistory.FUTURE, full = docs.size >= pageSize, fromServer = true, nowMs = now)
            h.lastChg = maxOf(h.lastChg ?: top, top)
            h.lastGoneChg = maxOf(h.lastGoneChg ?: top, top)
        }
        mode = Mode.SYNCED
        attachListeners()
        commit()
    }

    private fun attachListeners() {
        firstServer = true
        attachedAt = System.currentTimeMillis()
        attachChg(fresh = true)
        attachGone()
    }

    /** `fresh`: a new attach, whose first full answer means "too long". Otherwise a re-base. */
    private fun attachChg(fresh: Boolean = false) {
        val h = history ?: return
        val from = synchronized(h) { h.lastChg } ?: return
        val token = ++chgToken
        if (!fresh) firstServer = false
        chgReg?.remove()
        chgReg = convRef.collection("messages")
            .whereGreaterThan("chg", from)
            .orderBy("chg")
            .limit(CH_LIMIT)
            .addSnapshotListener { snap, err ->
                if (token != chgToken || stopped) return@addSnapshotListener
                if (snap == null) {
                    val code = (err as? FirebaseFirestoreException)?.code
                    if (code == FirebaseFirestoreException.Code.PERMISSION_DENIED || code == FirebaseFirestoreException.Code.FAILED_PRECONDITION) {
                        // Refused, or the index is missing: today's window path for this visit, and the
                        // store must not seed a later open frozen.
                        refuse()
                    }
                    return@addSnapshotListener
                }
                val fromCache = snap.metadata.isFromCache
                val docs = snap.documents
                val wasFirst = !fromCache && firstServer
                synchronized(h) {
                    applyAll(h, docs)
                    // Only the server's answer moves the counter: the local cache can miss a change.
                    if (!fromCache) docs.mapNotNull { chgOf(it) }.maxOrNull()?.let { top -> h.lastChg = maxOf(h.lastChg ?: top, top) }
                }
                if (wasFirst) firstServer = false
                commit()
                if (!fromCache && docs.size >= CH_LIMIT) { if (wasFirst) tooLong() else attachChg() }
                else if (!fromCache && docs.size >= CH_REBASE_AT) attachChg()
            }
    }

    /** Every change record in `chg` order: hard deletes `{mid, gone: true}`, clears `{clear: true, before}`. */
    private fun attachGone() {
        val h = history ?: return
        val from = synchronized(h) { h.lastGoneChg ?: h.lastChg } ?: return
        val token = ++goneToken
        goneReg?.remove()
        goneReg = convRef.collection("changes")
            .whereGreaterThan("chg", from)
            .orderBy("chg")
            .limit(CH_LIMIT)
            .addSnapshotListener { snap, _ ->
                // Not readable (rules not there yet): deletes for everyone still arrive as edits.
                if (snap == null || token != goneToken || stopped) return@addSnapshotListener
                val now = System.currentTimeMillis()
                var removed = 0
                synchronized(h) {
                    for (d in snap.documents) {
                        if (d.getBoolean("clear") == true) {
                            val ms = (d.get("before") as? Number)?.toLong() ?: continue
                            removed += h.clearBefore(ms * 1000, now).size
                            continue
                        }
                        if (d.getBoolean("gone") != true) continue
                        val mid = d.getString("mid") ?: continue
                        if (h.remove(mid, chgOf(d) ?: d.id.toLongOrNull(), now) == ChannelHistory.Outcome.REMOVED) removed++
                    }
                    if (!snap.metadata.isFromCache) {
                        snap.documents.mapNotNull { chgOf(it) ?: it.id.toLongOrNull() }.maxOrNull()?.let { top ->
                            h.lastGoneChg = maxOf(h.lastGoneChg ?: top, top)
                        }
                    }
                }
                if (removed > 0) commit()
                if (!snap.metadata.isFromCache && snap.size() >= CH_REBASE_AT) attachGone()
            }
    }

    private fun refuse() {
        mode = Mode.OFF
        headReg?.remove(); headReg = null
        chgReg?.remove(); chgReg = null
        goneReg?.remove(); goneReg = null
        Registry.forget(me, cid)
        history = null
        // The window path draws again; its own listener's rows are what shows now.
        onChange(null)
    }

    /** More than 200 changes waited: `chSeq` first (a change during the read comes again), then the
     *  newest page on top; the rows already held stay, below a hole. */
    private fun tooLong() {
        if (tooLongRunning) return
        tooLongRunning = true
        scope.launch {
            try {
                val seq = convRef.get(Source.SERVER).await().getLong("chSeq")
                val snap = convRef.collection("messages").orderBy("createdAt", Query.Direction.DESCENDING)
                    .limit(pageSize).get(Source.SERVER).await()
                val h = history ?: return@launch
                if (stopped) return@launch
                val docs = snap.documents
                synchronized(h) {
                    h.resetForTooLong()
                    h.applyPage(docs.mapNotNull { docOf(it) }, ChannelHistory.FUTURE, full = docs.size >= pageSize,
                        fromServer = true, nowMs = System.currentTimeMillis())
                    val newLast = seq ?: docs.mapNotNull { chgOf(it) }.maxOrNull() ?: (h.lastChg ?: 0L)
                    h.lastChg = maxOf(h.lastChg ?: newLast, newLast)
                    if (seq != null) h.lastGoneChg = maxOf(h.lastGoneChg ?: seq, seq)
                }
                commit()
                attachChg()
                if (seq != null) attachGone()
            } catch (_: Exception) {
                // Tried again on the next attach.
            } finally { tooLongRunning = false }
        }
    }

    // ---- History ----------------------------------------------------------------------------

    /** The hole under the newest block, one page (spec 6). False: not this path (window path runs). */
    fun loadOlder(): Boolean {
        if (mode != Mode.SYNCED) return false
        fillPages = 0
        readHole()
        return true
    }

    private fun readHole() {
        val h = history ?: return
        val hole = synchronized(h) { h.topHole }
        if (hole == null) { setOlder(null, false); return }
        if (loading) return
        loading = true
        setOlder(true, null)
        scope.launch {
            var fromServer = false
            try {
                var q = convRef.collection("messages").orderBy("createdAt", Query.Direction.DESCENDING)
                if (hole.upper != ChannelHistory.FUTURE) q = q.startAt(timestampOf(hole.upper))
                val snap = q.limit(pageSize).get().await()
                fromServer = !snap.metadata.isFromCache
                if (!stopped && mode == Mode.SYNCED && history === h) synchronized(h) {
                    h.applyPage(snap.documents.mapNotNull { docOf(it) }, hole.upper, full = snap.size() >= pageSize,
                        fromServer = fromServer, nowMs = System.currentTimeMillis())
                }
            } catch (_: Exception) {}
            // A failed or cache-only page is asked again when the network comes back.
            olderNeedsRetry = !fromServer
            loading = false
            setOlder(false, synchronized(h) { h.topHole != null })
            onChange(null)
            fill()
        }
    }

    /** Fewer than 40 visible posts above the first hole: read the hole (spec 6). Bounded per open. */
    private fun fill() {
        val h = history ?: return
        if (stopped || mode != Mode.SYNCED || loading || olderNeedsRetry || fillPages >= FILL_CAP) return
        if (!synchronized(h) { h.needsFill(WANT_VISIBLE, ::visible) }) return
        fillPages++
        readHole()
    }

    // ---- Applying ---------------------------------------------------------------------------

    /** Docs as changes to the store; the count of rows that changed. */
    private fun applyAll(h: ChannelHistory<DocumentSnapshot>, docs: List<DocumentSnapshot>): Int {
        val now = System.currentTimeMillis()
        var n = 0
        for (d in docs) {
            val doc = docOf(d) ?: continue
            when (h.applyChange(doc, now)) {
                ChannelHistory.Outcome.ADDED, ChannelHistory.Outcome.EDITED, ChannelHistory.Outcome.REMOVED -> n++
                else -> {}
            }
        }
        return n
    }

    private fun commit() {
        val h = history ?: return
        setOlder(null, synchronized(h) { h.topHole != null })
        onChange(null)
        fill()
    }

    /** What the reader would see of a post: not deleted, not a notice, not hidden by me. */
    private fun visible(d: ChannelHistory.Doc<DocumentSnapshot>): Boolean =
        !d.deleted && d.value.getString("type") != "system" && !HiddenMessages.isHidden(d.id)

    /** Registry of this run's channel stores, per account and channel (iOS ChannelHistoryRegistry). */
    object Registry {
        private const val MEMORY_CAP = 24
        private val boxes = LinkedHashMap<String, ChannelHistory<DocumentSnapshot>>(16, 0.75f, true)

        @Synchronized
        fun get(uid: String, cid: String): ChannelHistory<DocumentSnapshot> {
            val k = "$uid/$cid"
            boxes[k]?.let { return it }
            val h = ChannelHistory<DocumentSnapshot>()
            boxes[k] = h
            // The least recently opened leaves memory.
            while (boxes.size > MEMORY_CAP) boxes.remove(boxes.keys.first())
            return h
        }

        @Synchronized
        fun forget(uid: String, cid: String) { boxes.remove("$uid/$cid") }

        /** Sign-out: every account's posts go. */
        @Synchronized
        fun removeAll() { boxes.clear() }
    }

    companion object {
        private const val CH_LIMIT = 200L
        private const val CH_REBASE_AT = 150
        private const val WANT_VISIBLE = 40
        private const val FILL_CAP = 30
        private const val DISK_KEEP = 300
        private const val BOUND_AT = 400

        fun chgOf(d: DocumentSnapshot): Long? = (d.get("chg") as? Number)?.toLong()

        fun micros(t: Timestamp): Long = t.seconds * 1_000_000L + t.nanoseconds / 1_000

        fun timestampOf(micros: Long): Timestamp {
            val s = Math.floorDiv(micros, 1_000_000L)
            val us = Math.floorMod(micros, 1_000_000L)
            return Timestamp(s, (us * 1_000).toInt())
        }

        /** A doc as the store sees it; null without a server `createdAt` (a send not yet stamped). */
        fun docOf(d: DocumentSnapshot): ChannelHistory.Doc<DocumentSnapshot>? {
            val at = d.getTimestamp("createdAt") ?: return null
            return ChannelHistory.Doc(d.id, micros(at), d.getBoolean("deleted") == true, chgOf(d), d)
        }
    }
}
