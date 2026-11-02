package com.fariin.app.data

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

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
) {
    sealed interface State {
        data object Loading : State
        data class Loaded(val conversations: List<Conversation>, val fromCache: Boolean) : State
        data class Failed(val message: String) : State
    }

    fun listen(uid: String, limit: Long = PAGE_SIZE): Flow<State> = callbackFlow {
        trySend(State.Loading)
        val reg = db.collection("conversations")
            .whereArrayContains("users", uid)
            .orderBy("updatedAt", Query.Direction.DESCENDING)
            .limit(limit)
            .addSnapshotListener { snap, error ->
                if (error != null) {
                    trySend(State.Failed(error.message ?: "Could not load chats"))
                    return@addSnapshotListener
                }
                if (snap == null) return@addSnapshotListener
                val convs = snap.documents.map { parse(it) }
                trySend(State.Loaded(convs, snap.metadata.isFromCache))
            }
        awaitClose { reg.remove() }
    }

    private fun parse(doc: DocumentSnapshot): Conversation {
        // ESTIMATE, as iOS reads `data(with: .estimate)`: a pending server time shows as now
        // instead of dropping the chat to the bottom.
        val data = doc.getData(DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) ?: emptyMap()
        return Conversation.fromFirestore(doc.id, data)
    }

    companion object {
        const val PAGE_SIZE = 300L
    }
}
