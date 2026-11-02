package com.fariin.app.push

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.fariin.app.MainActivity
import com.fariin.app.R
import kotlinx.coroutines.flow.MutableStateFlow

/** Where a notification tap should land, and which chat is open now (iOS AppRouter.activeChatId). */
object PushRouter {
    const val EXTRA_OPEN_CID = "openCid"
    @Volatile var activeChatId: String? = null
    /** A tap on a chat notification, waiting for the signed-in shell to open it. */
    val pendingOpen = MutableStateFlow<String?>(null)
}

/**
 * Draws a [NotifSpec] the way the iOS extension draws its Communication Notification: the sender's
 * face on the left (MessagingStyle with a Person icon), the group's name over a group, the earlier
 * lines of the same chat kept under it, and the chat as a long-lived conversation shortcut so
 * Android lists it in the Conversations section and lets the person set each chat's own sound
 * and priority (Android's per-chat channel).
 */
object NotificationPoster {
    private const val ID = 1
    private const val AVATAR_PX = 192

    fun ensureChannels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        val msgs = NotificationChannel(NotifChannel.MESSAGES.id, NotifChannel.MESSAGES.title, NotificationManager.IMPORTANCE_HIGH)
            .apply { description = "New messages in your chats" }
        val calls = NotificationChannel(NotifChannel.CALLS.id, NotifChannel.CALLS.title, NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Calls you get"
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
            )
        }
        nm.createNotificationChannels(listOf(msgs, calls))
    }

    /**
     * @param photos the sender's and the group's photo bytes (null = the grey face)
     */
    @SuppressLint("MissingPermission")   // checked through areNotificationsEnabled
    fun post(c: Context, spec: NotifSpec, senderPhoto: ByteArray?, groupPhoto: ByteArray?, me: String) {
        ensureChannels(c)
        val nmc = NotificationManagerCompat.from(c)
        if (!nmc.areNotificationsEnabled()) return

        val tap = Intent(c, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (spec.opensChat) putExtra(PushRouter.EXTRA_OPEN_CID, spec.key)
        }
        val pending = PendingIntent.getActivity(c, spec.key.hashCode(), tap,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val b = NotificationCompat.Builder(c, spec.channel.id)
            .setSmallIcon(R.drawable.ic_chat)
            .setColor(Color.rgb(0x1E, 0x88, 0xE5))
            .setContentTitle(spec.title)
            .setContentText(spec.body)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(if (spec.channel == NotifChannel.CALLS) NotificationCompat.CATEGORY_CALL else NotificationCompat.CATEGORY_MESSAGE)
        spec.expiresAtMs?.let { at ->
            val left = at - System.currentTimeMillis()
            if (left > 0) b.setTimeoutAfter(left)
        }

        if (spec.plain) {
            b.setStyle(NotificationCompat.BigTextStyle().bigText(spec.body))
            nmc.notify(spec.key, ID, b.build())
            return
        }

        val senderIcon = IconCompat.createWithBitmap(circle(senderPhoto, group = false))
        val sender = Person.Builder().setName(spec.senderName).setKey(spec.senderUid).setIcon(senderIcon).build()
        val mePerson = Person.Builder().setName("You").setKey(me.ifEmpty { "me" }).build()

        // The chat's earlier lines stay under the new one, as one conversation.
        val earlier = nmc.activeNotifications.firstOrNull { it.tag == spec.key && it.id == ID }?.notification
            ?.let { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(it) }
        val style = NotificationCompat.MessagingStyle(mePerson)
        earlier?.messages?.takeLast(6)?.forEach { style.addMessage(it) }
        style.addMessage(spec.line, System.currentTimeMillis(), sender)
        if (spec.isGroup) {
            style.setConversationTitle(spec.groupName ?: spec.title)
            style.isGroupConversation = true
        }
        b.setStyle(style)

        // The conversation shortcut (needed for Android's Conversations section). A group's face is
        // the group photo; a 1:1's is the person.
        val chatIcon = if (spec.isGroup) IconCompat.createWithBitmap(circle(groupPhoto, group = true)) else senderIcon
        val open = Intent(c, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
            .putExtra(PushRouter.EXTRA_OPEN_CID, spec.key)
        val shortcut = ShortcutInfoCompat.Builder(c, spec.key)
            .setShortLabel(spec.title)
            .setLongLived(true)
            .setIcon(chatIcon)
            .setIntent(open)
            .apply { if (!spec.isGroup) setPerson(sender) }
            .build()
        try { ShortcutManagerCompat.pushDynamicShortcut(c, shortcut) } catch (_: Exception) {}
        b.setShortcutId(spec.key)
        if (spec.isGroup) b.setLargeIcon(circle(groupPhoto, group = true))

        nmc.notify(spec.key, ID, b.build())
    }

    /** iOS Push.clear(cid): the chat was opened, its notification goes. */
    fun clear(c: Context, cid: String) = NotificationManagerCompat.from(c).cancel(cid, ID)

    fun clearAll(c: Context) {
        NotificationManagerCompat.from(c).cancelAll()
        try { ShortcutManagerCompat.removeAllDynamicShortcuts(c) } catch (_: Exception) {}
    }

    /** The photo cut to a circle, or the soft grey face with the person (or people) glyph, the
     *  way the app's own avatar draws someone with no photo (iOS Avatars.placeholder). */
    fun circle(bytes: ByteArray?, group: Boolean): Bitmap {
        val n = AVATAR_PX
        val out = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val src = bytes?.let { decode(it) }
        if (src != null) {
            cv.drawOval(RectF(0f, 0f, n.toFloat(), n.toFloat()), p)
            p.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            val side = minOf(src.width, src.height)
            val sx = (src.width - side) / 2
            val sy = (src.height - side) / 2
            cv.drawBitmap(src, android.graphics.Rect(sx, sy, sx + side, sy + side), RectF(0f, 0f, n.toFloat(), n.toFloat()), p)
            return out
        }
        p.color = Color.rgb(0xDB, 0xDB, 0xE0)
        cv.drawOval(RectF(0f, 0f, n.toFloat(), n.toFloat()), p)
        p.color = Color.rgb(0x9E, 0x9E, 0xA6)
        if (group) {
            person(cv, p, n * 0.36f, n * 0.54f, n * 0.30f)
            person(cv, p, n * 0.64f, n * 0.54f, n * 0.30f)
        } else {
            person(cv, p, n * 0.5f, n * 0.54f, n * 0.42f)
        }
        return out
    }

    /** A filled head and shoulders, centred on (cx, cy), `size` tall. */
    private fun person(cv: Canvas, p: Paint, cx: Float, cy: Float, size: Float) {
        val head = size * 0.24f
        cv.drawCircle(cx, cy - size * 0.22f, head, p)
        val w = size * 0.42f
        val top = cy + size * 0.08f
        val path = Path().apply {
            moveTo(cx - w, top + size * 0.36f)
            cubicTo(cx - w, top - size * 0.02f, cx + w, top - size * 0.02f, cx + w, top + size * 0.36f)
            close()
        }
        cv.drawPath(path, p)
    }

    private fun decode(bytes: ByteArray): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var sample = 1
        while (o.outWidth / (sample * 2) >= AVATAR_PX && o.outHeight / (sample * 2) >= AVATAR_PX) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (_: Exception) { null }
}
