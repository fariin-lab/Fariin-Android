package com.fariin.app.data

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import com.fariin.app.local.LocalCache


/**
 * The chat list's live window, the same query as iOS ConversationsRepository.attach():
 * conversations where `users` contains me, newest `updatedAt` first, limited to one page.
 *
 * Read only. Nothing here writes to Firestore.
 *
 * TODO (iOS has these): paging past the first 300, separate listeners for pinned chats older
 * than the window, the disk cache for an instant cold start, channels I follow (they are not
 * in `users`), the block list re-filter, burning expired disappearing messages.
 */
class ConversationsRepository(
    private val db: FirebaseFirestore = com.fariin.app.Fb.db,
    /** Off in tests and the demo; on in the app (LocalCache.init runs at start). */
    private val useDiskCache: Boolean = true,
) {
    sealed interface State {
        data object Loading : State
        data class Loaded(val conversations: List<Conversation>, val fromCache: Boolean) : State
        data class Failed(val message: String) : State
    }

    fun listen(uid: String, limit: Long = PAGE_SIZE): Flow<State> = callbackFlow {
        // The phone's copy first (iOS ConversationsDiskCache), so a cold start draws the list at
        // once, offline too. Its previews are still sealed; the rows open them as they draw.
        com.fariin.app.StartClock.mark("repo.listen")
        val saved = if (useDiskCache) withContext(Dispatchers.IO) { LocalCache.loadChats(uid) } else emptyList()
        com.fariin.app.StartClock.mark("repo.saved ${saved.size}")
        if (saved.isEmpty()) trySend(State.Loading)
        // Seeded too, so a chat opened from the saved list draws as itself (a channel as a channel) at once.
        else trySend(State.Loaded(saved.map { (id, d) -> ThreadSeed.noteConv(id, d); Conversation.fromFirestore(id, d) }, fromCache = true))
        // The cache generation at attach: a snapshot parsed on the background thread while a sign-out
        // wipes the cache must not write that account's chats (or seeds) back (review 2026-11-06).
        val gen = LocalCache.currentGeneration
        // Which chats showed unread in the last snapshot (only touched on the list's one parse thread).
        val wasUnread = HashSet<String>()
        val reg = db.collection("conversations")
            .whereArrayContains("users", uid)
            .orderBy("updatedAt", Query.Direction.DESCENDING)
            .limit(limit)
            // Metadata changes too: the server's ack of an offline send flips hasPendingWrites
            // without touching the data, and the row's clock must turn into a tick then.
            // Parsed on one background thread, in order (battery/lag audit 2026-11-05): up to 300
            // chats were read on the main thread for every change, ack and tick.
            .addSnapshotListener(parseThread, com.google.firebase.firestore.MetadataChanges.INCLUDE) { snap, error ->
                if (error != null) {
                    // Firestore ends a refused listener for good; the caller attaches a new one
                    // (ChatsViewModel, iOS SessionRecovery). Until then the list keeps what it has.
                    close(error)
                    return@addSnapshotListener
                }
                if (snap == null) return@addSnapshotListener
                if (LocalCache.currentGeneration != gen) return@addSnapshotListener
                // An empty answer from Firestore's own offline store is no answer: it never blanks the
                // saved list, and with nothing saved it must not read as "No chats yet" before the
                // server has spoken (iOS keeps loading; 2026-11-10 parity). Loading stays up instead.
                if (snap.metadata.isFromCache && snap.isEmpty) return@addSnapshotListener
                // Each document's data read once, for the row and for the saved copy.
                val datas = snap.documents.map { it.id to (it.getData(DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) ?: emptyMap()) }
                val convs = snap.documents.mapIndexed { i, d -> parse(d, datas[i].second) }
                trySend(State.Loaded(convs, snap.metadata.isFromCache))
                // Settings > Chats > Automatically Archive (iOS sweeps every chat-list snapshot).
                if (useDiskCache) UnknownChatArchiver.sweep(uid, convs)
                // A request I answered the old way is written as accepted (iOS healAnsweredRequests).
                // Server data only, as iOS.
                if (useDiskCache && !snap.metadata.isFromCache) AnsweredRequests.heal(convs, uid)
                // iOS syncGroupWallpapers on the list snapshot: a group's shared wallpaper (and its
                // photo) is ready before the chat is opened. Unchanged values cost nothing.
                if (useDiskCache) runCatching { com.google.firebase.FirebaseApp.getInstance().applicationContext }.getOrNull()?.let { app ->
                    for (cv in convs) if (cv.isGroup) ChatLookStore.syncGroupWallpaper(app, cv.id, cv.groupWallpaper)
                    // A 1:1's Fariin Plus shared wallpaper (and its sealed photo), ready before it opens.
                    for ((id, d) in datas) if (!com.fariin.app.crypto.Wire.isGroupCid(id)) SharedWallpapers.sync(app, id, d["sharedWallpaper"])
                }
                if (useDiskCache && !snap.metadata.isFromCache && LocalCache.currentGeneration == gen) LocalCache.saveChats(uid, datas)
                // Read on another device (iOS: the badge follows reads elsewhere): a chat whose unread
                // drops to 0, not marked unread by hand, loses its notification here too. Only on a
                // drop, so a snapshot that is behind a fresh push never takes that push away.
                val readNow = convs.filter { it.id in wasUnread && !it.hasUnreadMark(uid) }.map { it.id }
                wasUnread.clear()
                for (cv in convs) if (cv.hasUnreadMark(uid)) wasUnread += cv.id
                if (useDiskCache && readNow.isNotEmpty()) runCatching { com.google.firebase.FirebaseApp.getInstance().applicationContext }.getOrNull()?.let { app ->
                    for (cid in readNow) runCatching { com.fariin.app.push.NotificationPoster.clear(app, cid) }
                }
            }
        awaitClose { reg.remove() }
    }

    // ESTIMATE, as iOS reads `data(with: .estimate)`: a pending server time shows as now
    // instead of dropping the chat to the bottom.
    private fun parse(doc: DocumentSnapshot, data: Map<String, Any?>): Conversation {
        ThreadSeed.noteConv(doc.id, data)   // a chat opened from here draws its ticks from the first frame
        val conv = Conversation.fromFirestore(doc.id, data)
        return if (doc.metadata.hasPendingWrites()) conv.copy(localPending = true) else conv
    }

    companion object {
        const val PAGE_SIZE = 300L
        private val parseThread: java.util.concurrent.Executor =
            java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "chat-list").apply { isDaemon = true } }
    }
}

/**
 * iOS MessageRequests.healAnsweredRequests: a request I started, still not accepted, where the
 * other person has the last word AND the oldest message is mine ("I asked, they answered") gets
 * `accepted: true` written, as an Accept would. When they wrote first, the oldest message is
 * theirs and nothing is written: the request waits for my Accept. One read per chat, once per
 * chat per session.
 */
object AnsweredRequests {
    private val healed = java.util.Collections.synchronizedSet(HashSet<String>())
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    fun heal(convs: List<Conversation>, me: String) {
        if (me.isEmpty()) return
        for (c in convs) {
            if (c.convType == "group" || c.startedBy != me || c.accepted) continue
            if (c.lastSender.isEmpty() || c.lastSender == me) continue
            if (!healed.add(c.id)) continue
            scope.launch {
                runCatching {
                    val ref = com.fariin.app.Fb.db.collection("conversations").document(c.id)
                    val first = ref.collection("messages").orderBy("createdAt").limit(1).get().await()
                    val author = first.documents.firstOrNull()?.getString("authorId")
                    if (author != me) return@runCatching
                    ref.set(mapOf("accepted" to true), com.google.firebase.firestore.SetOptions.merge()).await()
                    // If Automatically Archive put it away, accepting takes it back out (iOS accept).
                    UnknownChatArchiver.undoAutoArchive(me, c.id)
                }
            }
        }
    }
}

/**
 * iOS needWholeList("requests"): nothing a person owns may become unreachable. The live list holds
 * the newest [ConversationsRepository.PAGE_SIZE] chats only, so the Message Requests page reads
 * the older ones itself, one page per read, until the server has nothing older (capped as iOS at
 * 20 pages in all). One-shot reads: the main list's listener is not touched.
 */
object OlderChats {
    private const val MAX_PAGES = 19   // the live page + 19 = iOS maxWindow (20 pages)

    /** My chats older than the live window, oldest-first pages appended; empty when the window holds them all. */
    suspend fun beyondWindow(me: String, window: List<Conversation>): List<Conversation> {
        val mine = window.filter { me in it.users }
        if (mine.size < ConversationsRepository.PAGE_SIZE) return emptyList()
        var after = mine.minOfOrNull { it.updatedAtMillis } ?: return emptyList()
        val out = ArrayList<Conversation>()
        val db = com.fariin.app.Fb.db
        repeat(MAX_PAGES) {
            // One ms past the mark: a doc in the same ms as the window's oldest is read again, never skipped.
            val ms = after.toLong() + 1
            val at = com.google.firebase.Timestamp(Math.floorDiv(ms, 1000L), (Math.floorMod(ms, 1000L) * 1_000_000).toInt())
            val snap = db.collection("conversations")
                .whereArrayContains("users", me)
                .orderBy("updatedAt", Query.Direction.DESCENDING)
                .startAfter(at)
                .limit(ConversationsRepository.PAGE_SIZE)
                .get().await()
            val page = snap.documents.map {
                Conversation.fromFirestore(it.id, it.getData(DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) ?: emptyMap())
            }
            out += page
            if (page.size < ConversationsRepository.PAGE_SIZE) return out
            after = page.minOf { it.updatedAtMillis }
        }
        return out
    }
}
