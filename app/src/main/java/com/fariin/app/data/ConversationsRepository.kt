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
        val saved = if (useDiskCache) withContext(Dispatchers.IO) { LocalCache.loadChats(uid) } else emptyList()
        if (saved.isEmpty()) trySend(State.Loading)
        else trySend(State.Loaded(saved.map { (id, d) -> Conversation.fromFirestore(id, d) }, fromCache = true))
        val reg = db.collection("conversations")
            .whereArrayContains("users", uid)
            .orderBy("updatedAt", Query.Direction.DESCENDING)
            .limit(limit)
            // Metadata changes too: the server's ack of an offline send flips hasPendingWrites
            // without touching the data, and the row's clock must turn into a tick then.
            .addSnapshotListener(com.google.firebase.firestore.MetadataChanges.INCLUDE) { snap, error ->
                if (error != null) {
                    trySend(State.Failed(error.message ?: "Could not load chats"))
                    return@addSnapshotListener
                }
                if (snap == null) return@addSnapshotListener
                // An empty answer from Firestore's own offline store never blanks the saved list.
                if (snap.metadata.isFromCache && snap.isEmpty && saved.isNotEmpty()) return@addSnapshotListener
                val convs = snap.documents.map { parse(it) }
                trySend(State.Loaded(convs, snap.metadata.isFromCache))
                if (useDiskCache && !snap.metadata.isFromCache) {
                    LocalCache.saveChats(uid, snap.documents.map {
                        it.id to (it.getData(DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) ?: emptyMap())
                    })
                }
            }
        awaitClose { reg.remove() }
    }

    private fun parse(doc: DocumentSnapshot): Conversation {
        // ESTIMATE, as iOS reads `data(with: .estimate)`: a pending server time shows as now
        // instead of dropping the chat to the bottom.
        val data = doc.getData(DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) ?: emptyMap()
        val conv = Conversation.fromFirestore(doc.id, data)
        return if (doc.metadata.hasPendingWrites()) conv.copy(localPending = true) else conv
    }

    companion object {
        const val PAGE_SIZE = 300L
    }
}
