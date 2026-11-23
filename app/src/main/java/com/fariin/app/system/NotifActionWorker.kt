package com.fariin.app.system

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.fariin.app.R
import com.fariin.app.data.Conversation
import com.fariin.app.data.CryptoHub
import com.fariin.app.data.NotifActionRules
import com.fariin.app.data.ThreadRepository
import com.fariin.app.local.SendQueue
import com.fariin.app.push.NotificationPoster
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The network half of a notification action, run by WorkManager (expedited, so it starts at once
 * and is allowed to finish with the app closed). The same code as the chat screen:
 *  - Reply: [ThreadRepository.sendQueued], so the message is sealed for the chat (enc1 for a 1:1,
 *    the group seal for a group) and written with the chat screen's exact fields, then the chat is
 *    marked read (answering a chat means it was read, as other messengers do).
 *  - Mark as read: [ThreadRepository.markReadNow] (unreadCount.<me> 0, lastRead.<me> when Read
 *    Receipts are on).
 * Offline or slow: retried with backoff; after that the words stay in the send queue, which the
 * app drains on its next start. Refused by the rules: a short "not sent" notification.
 */
class NotifActionWorker(c: Context, params: WorkerParameters) : CoroutineWorker(c, params) {

    override suspend fun doWork(): Result {
        val c = applicationContext
        val kind = inputData.getString(KIND) ?: return Result.failure()
        val cid = inputData.getString(CID) ?: return Result.failure()
        val me = inputData.getString(ME) ?: return Result.failure()
        if (com.fariin.app.Fb.auth.currentUser?.uid != me) return Result.failure()
        val crypto = CryptoHub.get(c)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repo = ThreadRepository.openOrQuiet(cid, me, crypto, scope)
            // The newest line the notification showed (H-95): the read mark goes there, not to now.
            val shownMs = inputData.getLong(SHOWN_MS, 0L)
            val convRef = com.fariin.app.Fb.db.collection("conversations").document(cid)
            when (kind) {
                KIND_READ -> {
                    // A channel I only follow keeps its read mark on my follow entry (K-74).
                    followerRead(cid, me, convRef)?.let { ok -> return if (ok) Result.success() else retryOrStop() }
                    val done = if (shownMs > 0) withTimeoutOrNull(STEP_MS) { markShownRead(convRef, cid, me, shownMs) }
                        else withTimeoutOrNull(STEP_MS) { repo.markReadNow(); true }
                    if (done != true) return retryOrStop()
                    return Result.success()
                }
                KIND_REPLY -> {
                    val id = inputData.getString(CLIENT_ID) ?: return Result.failure()
                    SendQueue.init(c)
                    val e = SendQueue.all(me).firstOrNull { it.clientId == id } ?: return Result.success()  // already sent
                    // What the chat screen would refuse: a channel, a person I blocked, an unanswered
                    // request, an account on the Restore page (K-42, K-84, K-100, K-86).
                    replyRefusal(convRef, cid, me)?.let { why ->
                        SendQueue.remove(me, e.clientId)
                        NotificationPoster.showReplyFailed(c, cid, why)
                        return Result.success()
                    }
                    // A group's limits hold for a reply from the shade too: the chat's composer checks
                    // them, the rules check only who may send. Held back = not sent, and it says why.
                    groupHold(cid, me, e.text)?.let { why ->
                        SendQueue.remove(me, e.clientId)
                        NotificationPoster.showReplyFailed(c, cid, why)
                        return Result.success()
                    }
                    runCatching { crypto.ensureReady() }
                    return when (withTimeoutOrNull(STEP_MS) { repo.sendQueued(e) } ?: ThreadRepository.QueuedResult.LATER) {
                        ThreadRepository.QueuedResult.SENT -> {
                            // Answering marks read up to the line it answered, not later ones (H-95).
                            if (shownMs > 0) withTimeoutOrNull(STEP_MS) { markShownRead(convRef, cid, me, shownMs) }
                            else withTimeoutOrNull(STEP_MS) { repo.markReadNow() }
                            Result.success()
                        }
                        ThreadRepository.QueuedResult.REFUSED -> {
                            NotificationPoster.showReplyFailed(c, cid)
                            Result.success()
                        }
                        ThreadRepository.QueuedResult.LATER -> retryOrStop()
                    }
                }
            }
            return Result.failure()
        } finally {
            scope.cancel()
        }
    }

    /**
     * The composer's group holds (ThreadScreen gatedSend): links off for me, and slow mode's wait
     * since my last message. Null = may send, or the group could not be read (the send then goes
     * on and the rules decide).
     */
    private suspend fun groupHold(cid: String, me: String, text: String): String? {
        if (!com.fariin.app.crypto.Wire.isGroupCid(cid)) return null
        val conv = com.fariin.app.Fb.db.collection("conversations").document(cid)
        val d = withTimeoutOrNull(STEP_MS) { runCatching { conv.get().await().data }.getOrNull() } ?: return null
        val now = System.currentTimeMillis().toDouble()
        if (com.fariin.app.data.GroupAdmins.hasLink(text)) {
            com.fariin.app.data.GroupAdmins.heldBack(d, me, com.fariin.app.data.GroupAdmins.SendKind.LINKS, now)?.let { return it }
        }
        if (!com.fariin.app.data.GroupAdmins.slows(d, me)) return null
        val recent = withTimeoutOrNull(STEP_MS) {
            runCatching { conv.collection("messages").orderBy("createdAt", Query.Direction.DESCENDING).limit(30).get().await() }.getOrNull()
        } ?: return null
        val lastMine = recent.documents.firstOrNull { it.get("authorId") as? String == me && it.get("type") as? String != "system" }
            ?.let { com.fariin.app.data.Conversation.millis(it.get("createdAt")) }?.takeIf { it > 0 }
        return com.fariin.app.data.GroupAdmins.slowWait(d, me, lastMine, now)
    }

    /** The chat as the server has it now; null when it could not be read (the rules decide then). */
    private suspend fun convNow(convRef: DocumentReference, cid: String): Conversation? =
        withTimeoutOrNull(STEP_MS) { runCatching { convRef.get().await() }.getOrNull() }
            ?.takeIf { it.exists() }?.data?.let { Conversation.fromFirestore(cid, it) }

    private suspend fun replyRefusal(convRef: DocumentReference, cid: String, me: String): String? {
        val conv = convNow(convRef, cid)
        val user = withTimeoutOrNull(STEP_MS) {
            runCatching { com.fariin.app.Fb.db.collection("users").document(me).get().await() }.getOrNull()
        }
        val deletion = (user?.get("deletionScheduledFor") as? com.google.firebase.Timestamp)?.toDate()?.time ?: 0L
        val banned = user?.get("banned") as? Boolean ?: false
        val now = System.currentTimeMillis()
        if (conv == null) return NotifActionRules.replyRefusal(isGroup = true, isChannel = false, blockedByMe = false,
            startedBy = "", accepted = true, reqOff = false, lastSender = "", me = me,
            deletionAtMs = deletion, banned = banned, nowMs = now)
        return NotifActionRules.replyRefusal(
            isGroup = conv.isGroup, isChannel = conv.isChannel, blockedByMe = conv.isBlockedByMe(me),
            startedBy = conv.startedBy, accepted = conv.accepted, reqOff = conv.reqOff, lastSender = conv.lastSender,
            me = me, deletionAtMs = deletion, banned = banned, nowMs = now)
    }

    /**
     * K-74: Mark as read on a post of a channel I only follow: `read` on my follow entry goes to
     * the channel's post count. Null = not a channel I only follow; true = done; false = try again.
     */
    private suspend fun followerRead(cid: String, me: String, convRef: DocumentReference): Boolean? {
        if (!com.fariin.app.crypto.Wire.isGroupCid(cid)) return null
        val entryRef = com.fariin.app.Fb.db.collection("users").document(me).collection("channels").document(cid)
        val entry = withTimeoutOrNull(STEP_MS) { runCatching { entryRef.get().await() }.getOrNull() } ?: return null
        if (!entry.exists()) return null
        val conv = withTimeoutOrNull(STEP_MS) { runCatching { convRef.get().await() }.getOrNull() } ?: return false
        // The owner and admins are members: their read is a member's, below.
        if ((conv.get("users") as? List<*>)?.contains(me) == true) return null
        val posts = (conv.get("postCount") as? Number)?.toInt() ?: return true
        val target = NotifActionRules.followerRead((entry.get("read") as? Number)?.toInt(), posts) ?: return true
        return withTimeoutOrNull(STEP_MS) { runCatching { entryRef.update("read", target).await(); true }.getOrDefault(false) } ?: false
    }

    /**
     * Mark as read up to the shown line (H-95): my unread count goes to 0 and, with Read Receipts on
     * and nobody blocked, `lastRead.<me>` moves to that line's time (only ever forward).
     */
    private suspend fun markShownRead(convRef: DocumentReference, cid: String, me: String, shownMs: Long): Boolean {
        val snap = withTimeoutOrNull(STEP_MS) { runCatching { convRef.get().await() }.getOrNull() }
        val conv = snap?.takeIf { it.exists() }?.data?.let { Conversation.fromFirestore(cid, it) }
        val update = hashMapOf<String, Any>("unreadCount.$me" to 0)
        if (conv?.markedUnread?.get(me) == true) update["markedUnread.$me"] = FieldValue.delete()
        try { convRef.update(update).await() } catch (_: Exception) { return false }
        if (!com.fariin.app.data.PrivacySettings.readReceipts() || conv == null || conv.isBlockedByMe(me)) return true
        val mine = ((snap?.get("lastRead") as? Map<*, *>)?.get(me) as? com.google.firebase.Timestamp)?.toDate()?.time
        val at = NotifActionRules.readMark(shownMs, mine) ?: return true
        return try {
            convRef.set(mapOf("lastRead" to mapOf(me to com.google.firebase.Timestamp(java.util.Date(at)))), SetOptions.merge()).await()
            true
        } catch (_: Exception) { false }
    }

    /** A few tries in the background; then the send queue keeps the words for the next start. */
    private fun retryOrStop(): Result = if (runAttemptCount < MAX_TRIES) Result.retry() else Result.success()

    /** Android 11 and older run expedited work as a foreground service, which needs a notice. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val c = applicationContext
        val nm = c.getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(NotificationChannel(SENDING_CHANNEL, "Sending", NotificationManager.IMPORTANCE_MIN)
            .apply { description = "Shown for a moment while a reply from a notification is sent" })
        val n = NotificationCompat.Builder(c, SENDING_CHANNEL)
            .setSmallIcon(R.drawable.ic_chat)
            .setContentTitle("Sending")
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        return ForegroundInfo(FOREGROUND_ID, n)
    }

    companion object {
        const val KIND = "kind"
        const val CID = "cid"
        const val ME = "me"
        const val CLIENT_ID = "clientId"
        /** The newest shown line's time (ms), H-95; 0 from an older build's action. */
        const val SHOWN_MS = "shownMs"
        const val KIND_REPLY = "reply"
        const val KIND_READ = "read"
        private const val SENDING_CHANNEL = "sending"
        private const val FOREGROUND_ID = 7301
        private const val MAX_TRIES = 4
        private const val STEP_MS = 20_000L
    }
}
