package com.fariin.app.system

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.graphics.drawable.IconCompat
import com.fariin.app.R

/**
 * The buttons under a chat notification: Reply (typed in the shade, sent by
 * [NotifActionWorker] through the normal send path, the app can stay closed) and Mark as read
 * (the same two writes as opening the chat). iOS has neither on its notifications; Android users
 * expect both, so they are added the Android way (semantic actions, so a watch or a car can use
 * them too).
 *
 * App Lock: a reply shows nothing the notification does not already show, but it does act as
 * the account. With App Lock on, Android 12 and up asks for the phone's own unlock before the
 * reply goes (setAuthenticationRequired); older Android has no such ask, so the Reply button is
 * left out there. Mark as read stays: it reveals nothing and sends nothing.
 */
object NotifActions {
    const val ACTION_REPLY = "com.fariin.app.action.NOTIF_REPLY"
    const val ACTION_READ = "com.fariin.app.action.NOTIF_READ"
    const val EXTRA_CID = "cid"
    const val EXTRA_ME = "me"
    const val KEY_TEXT = "reply_text"

    /** What the policy allows: pure, so the App Lock rule is testable. */
    data class Allowed(val reply: Boolean, val replyNeedsUnlock: Boolean, val markRead: Boolean)

    fun allowed(appLockOn: Boolean, sdk: Int, chatNotification: Boolean): Allowed = when {
        !chatNotification -> Allowed(reply = false, replyNeedsUnlock = false, markRead = false)
        !appLockOn -> Allowed(reply = true, replyNeedsUnlock = false, markRead = true)
        sdk >= Build.VERSION_CODES.S -> Allowed(reply = true, replyNeedsUnlock = true, markRead = true)
        else -> Allowed(reply = false, replyNeedsUnlock = false, markRead = true)
    }

    private fun intent(c: Context, action: String, cid: String, me: String) =
        Intent(c, NotifActionReceiver::class.java).setAction(action).putExtra(EXTRA_CID, cid).putExtra(EXTRA_ME, me)
            // One PendingIntent per chat and action (the data makes them distinct).
            .setData(android.net.Uri.parse("fariin-notif://$action/$cid"))

    fun replyAction(c: Context, cid: String, me: String, needsUnlock: Boolean): NotificationCompat.Action {
        // Mutable: Android writes the typed words into it.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        val pi = PendingIntent.getBroadcast(c, ("r$cid").hashCode(), intent(c, ACTION_REPLY, cid, me), flags)
        val input = RemoteInput.Builder(KEY_TEXT).setLabel("Message").build()
        return NotificationCompat.Action.Builder(IconCompat.createWithResource(c, R.drawable.ic_chat), "Reply", pi)
            .addRemoteInput(input)
            .setAllowGeneratedReplies(true)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .setAuthenticationRequired(needsUnlock)
            .build()
    }

    fun readAction(c: Context, cid: String, me: String): NotificationCompat.Action {
        val pi = PendingIntent.getBroadcast(c, ("m$cid").hashCode(), intent(c, ACTION_READ, cid, me),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Action.Builder(IconCompat.createWithResource(c, R.drawable.ic_chat), "Mark as read", pi)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()
    }

    /** Adds the allowed buttons to a chat notification. */
    fun addTo(b: NotificationCompat.Builder, c: Context, cid: String, me: String, appLockOn: Boolean) {
        val a = allowed(appLockOn, Build.VERSION.SDK_INT, chatNotification = me.isNotEmpty())
        if (a.reply) b.addAction(replyAction(c, cid, me, a.replyNeedsUnlock))
        if (a.markRead) b.addAction(readAction(c, cid, me))
    }
}
