package com.fariin.app.data

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
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
                // iOS syncGroupWallpapers on the list snapshot: a group's shared wallpaper (and its
                // photo) is ready before the chat is opened. Unchanged values cost nothing.
                if (useDiskCache) runCatching { com.google.firebase.FirebaseApp.getInstance().applicationContext }.getOrNull()?.let { app ->
                    for (cv in convs) if (cv.isGroup) ChatLookStore.syncGroupWallpaper(app, cv.id, cv.groupWallpaper)
                }
                if (useDiskCache && !snap.metadata.isFromCache && LocalCache.currentGeneration == gen) LocalCache.saveChats(uid, datas)
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
