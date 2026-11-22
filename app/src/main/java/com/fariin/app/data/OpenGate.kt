package com.fariin.app.data

import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.tasks.await

/**
 * Whether a chat (and a post in it) can still be opened, checked before an Activity tap opens it
 * (iOS OpenGate, audit X1): a row about a post or a channel that is gone says so and stays put,
 * instead of a channel whose sheet cannot load, or a blank chat. A read that fails offline does
 * not decide anything: it opens as before.
 */
object OpenGate {
    /**
     * VISITOR (iOS 2026-11-21): a public channel I am not in and do not follow. It opens as its
     * visitor page (read only, with Follow), not as my chat.
     */
    enum class Result { OPEN, VISITOR, REFUSED, CHAT_GONE, POST_GONE }

    /** What the read of the conversation document said, for [decide]. */
    enum class Doc {
        /** Not read: the chat is in my list or a channel I follow. */
        KNOWN,
        /** Read, and I am in it, or it is a public channel. */
        OPENABLE,
        /** Read, a private chat I am not in (or no member list). */
        NOT_MINE,
        MISSING,
        /** A deleted channel keeps its doc, archived (server grouparchive). */
        ARCHIVED,
        /** The rules refused the read; the server's public page found a public channel. */
        DENIED_PUBLIC,
        /** The rules refused the read; no public page either. */
        DENIED,
        /** Offline or any other error: decides nothing. */
        FAILED,
    }

    /** The words for a refusal, "" for none. */
    fun notice(r: Result): String = when (r) {
        Result.CHAT_GONE -> "This channel is no longer available"
        Result.POST_GONE -> "This post was deleted"
        else -> ""
    }

    /** The rule alone (iOS OpenGate.check), from the document's answer and whether the post is gone. */
    fun decide(doc: Doc, postGone: Boolean): Result = when (doc) {
        Doc.MISSING, Doc.ARCHIVED -> Result.CHAT_GONE
        Doc.NOT_MINE, Doc.DENIED -> Result.REFUSED
        Doc.FAILED -> Result.OPEN
        Doc.KNOWN, Doc.OPENABLE -> if (postGone) Result.POST_GONE else Result.OPEN
        Doc.DENIED_PUBLIC -> if (postGone) Result.POST_GONE else Result.VISITOR
    }

    suspend fun check(cid: String, mid: String?): Result {
        val me = com.fariin.app.Fb.auth.currentUser?.uid ?: return Result.REFUSED
        // The official chat has no conversation document; every account has it.
        if (cid == OfficialChannel.CID) return Result.OPEN
        val conv = com.fariin.app.Fb.db.collection("conversations").document(cid)
        val known = CryptoHub.listedConversation(cid) != null ||
            com.fariin.app.channel.ChannelFollows.docs.value.containsKey(cid)
        val doc = if (known) Doc.KNOWN else try {
            val snap = conv.get().await()
            @Suppress("UNCHECKED_CAST")
            val users = snap.get("users") as? List<String>
            // A public channel opens for anyone.
            val publicChannel = snap.getBoolean("channel") == true && snap.getBoolean("isPublic") == true
            when {
                !snap.exists() -> Doc.MISSING
                snap.get("archivedAt") != null -> Doc.ARCHIVED
                users == null -> Doc.NOT_MINE
                !publicChannel && me !in users -> Doc.NOT_MINE
                else -> Doc.OPENABLE
            }
        } catch (e: FirebaseFirestoreException) {
            if (e.code != FirebaseFirestoreException.Code.PERMISSION_DENIED) Doc.FAILED
            else {
                // The rules refuse the document to anyone not in it or following it, public or not;
                // the server's public page answers instead (publicGroupPreview).
                val pv = com.fariin.app.channel.ChannelService.preview(cid)
                if ((pv as? com.fariin.app.channel.ChannelService.PreviewResult.Found)?.preview?.isChannel == true) Doc.DENIED_PUBLIC
                else Doc.DENIED
            }
        } catch (_: Exception) { Doc.FAILED }
        if (doc == Doc.FAILED) return Result.OPEN
        return decide(doc, !mid.isNullOrEmpty() && postGone(cid, mid))
    }

    /** The post: gone when its document is missing or a tombstone, read from the server only. */
    suspend fun postGone(cid: String, mid: String): Boolean {
        if (cid.isEmpty() || '/' in cid || mid.isEmpty() || '/' in mid) return false
        val m = runCatching { com.fariin.app.Fb.db.collection("conversations").document(cid)
            .collection("messages").document(mid).get().await() }.getOrNull() ?: return false
        return !m.metadata.isFromCache && (!m.exists() || m.getBoolean("deleted") == true)
    }
}
