package com.fariin.app.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.tasks.await

/**
 * Whether a chat (and a post in it) can still be opened, checked before an Activity tap opens it
 * (iOS OpenGate, audit X1): a row about a post or a channel that is gone says so and stays put,
 * instead of a channel whose sheet cannot load, or a blank chat. A read that fails offline does
 * not decide anything: it opens as before.
 */
object OpenGate {
    enum class Result { OPEN, REFUSED, CHAT_GONE, POST_GONE }

    /** The words for a refusal, "" for none. */
    fun notice(r: Result): String = when (r) {
        Result.CHAT_GONE -> "This channel is no longer available"
        Result.POST_GONE -> "This post was deleted"
        else -> ""
    }

    suspend fun check(cid: String, mid: String?): Result {
        val me = FirebaseAuth.getInstance().currentUser?.uid ?: return Result.REFUSED
        // The official chat has no conversation document; every account has it.
        if (cid == OfficialChannel.CID) return Result.OPEN
        val conv = FirebaseFirestore.getInstance().collection("conversations").document(cid)
        val known = CryptoHub.listedConversation(cid) != null ||
            com.fariin.app.channel.ChannelFollows.docs.value.containsKey(cid)
        if (!known) {
            try {
                val snap = conv.get().await()
                if (!snap.exists()) return Result.CHAT_GONE
                @Suppress("UNCHECKED_CAST")
                val users = snap.get("users") as? List<String> ?: return Result.REFUSED
                // A public channel opens for anyone.
                val publicChannel = snap.getBoolean("channel") == true && snap.getBoolean("isPublic") == true
                if (!publicChannel && me !in users) return Result.REFUSED
            } catch (e: FirebaseFirestoreException) {
                return if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) Result.REFUSED else Result.OPEN
            } catch (_: Exception) { return Result.OPEN }
        }
        // The post: gone when its document is missing or a tombstone, read from the server only.
        if (!mid.isNullOrEmpty() && '/' !in mid) {
            val m = runCatching { conv.collection("messages").document(mid).get().await() }.getOrNull()
            if (m != null && !m.metadata.isFromCache && (!m.exists() || m.getBoolean("deleted") == true)) return Result.POST_GONE
        }
        return Result.OPEN
    }
}
