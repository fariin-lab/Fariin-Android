package com.fariin.app.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.fariin.app.local.SendQueue
import com.fariin.app.push.NotificationPoster
import com.google.firebase.auth.FirebaseAuth
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Reply and Mark as read from the notification shade. The receiver itself only does the quick,
 * local part (a broadcast gets a few seconds):
 *  - Reply: the words go into the durable [SendQueue] on disk first (as a send from the chat
 *    screen does), the notification shows my line at once, then [NotifActionWorker] sends it.
 *    If the worker can not finish (offline, killed), the queue still holds the words and the
 *    next start or reconnect sends them, exactly like a text typed in the chat.
 *  - Mark as read: the notification goes, the worker writes the read state.
 */
class NotifActionReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val cid = intent.getStringExtra(NotifActions.EXTRA_CID) ?: return
        val me = FirebaseAuth.getInstance().currentUser?.uid ?: return
        // A notification from an account that is no longer signed in does nothing.
        if (intent.getStringExtra(NotifActions.EXTRA_ME) != me) { NotificationPoster.clear(c, cid); return }
        when (intent.action) {
            NotifActions.ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(NotifActions.KEY_TEXT)?.toString()?.trim()
                if (text.isNullOrEmpty()) { NotificationPoster.showMyReply(c, cid, null); return }
                val e = SendQueue.Entry(UUID.randomUUID().toString().uppercase(), cid, text, System.currentTimeMillis().toDouble())
                SendQueue.init(c)
                SendQueue.add(me, e)
                // Android keeps a spinner on the reply field until the notification is posted again.
                NotificationPoster.showMyReply(c, cid, text)
                enqueue(c, NotifActionWorker.KIND_REPLY, cid, me, e.clientId)
            }
            NotifActions.ACTION_READ -> {
                NotificationPoster.clear(c, cid)
                enqueue(c, NotifActionWorker.KIND_READ, cid, me, null)
            }
        }
    }

    private fun enqueue(c: Context, kind: String, cid: String, me: String, clientId: String?) {
        val req = OneTimeWorkRequestBuilder<NotifActionWorker>()
            .setInputData(workDataOf(NotifActionWorker.KIND to kind, NotifActionWorker.CID to cid,
                NotifActionWorker.ME to me, NotifActionWorker.CLIENT_ID to clientId))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        val name = if (kind == NotifActionWorker.KIND_REPLY) "notif-reply-$clientId" else "notif-read-$cid"
        WorkManager.getInstance(c).enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, req)
    }
}
