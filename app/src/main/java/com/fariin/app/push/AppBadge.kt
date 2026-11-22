package com.fariin.app.push

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.fariin.app.MainActivity
import com.fariin.app.R

/**
 * The app icon number (iOS setBadgeCount). Android has no call for it: the launcher reads the
 * number on our notifications. So every message notification sits in one group per account, and
 * that group's summary carries the total (setNumber). Launchers that only draw a dot ignore it.
 * Total 0: the summary carries no number.
 */
object AppBadge {
    private const val PREFS = "app_badge"
    private const val SUMMARY_TAG = "fariin.summary"
    private const val SUMMARY_ID = 2

    fun group(me: String) = "fariin.messages.$me"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The last total this phone knew for that account. */
    fun lastKnown(c: Context, me: String): Int = prefs(c).getInt(me, 0)

    /** The chat list changed while the app runs: its total, from AppBadgeRules.total. */
    fun sync(c: Context, me: String, total: Int, force: Boolean = false) {
        if (me.isEmpty()) return
        val t = maxOf(0, total)
        if (!force && prefs(c).getInt(me, -1) == t) return
        prefs(c).edit().putInt(me, t).apply()
        refresh(c, me)
    }

    /** A message push with the app in the background. The push has no count on Android, so the
     *  last known total plus this message (AppBadgeRules.afterPush). */
    /** True while the chat list is being listened to (MainShell on screen or kept in the back). Its
     *  total already holds a new message, so a push adding one more counted it twice. */
    @Volatile var listLive: Boolean = false

    fun notePush(c: Context, me: String, countsHere: Boolean) {
        if (me.isEmpty() || !countsHere || listLive) return
        prefs(c).edit().putInt(me, com.fariin.app.data.AppBadgeRules.afterPush(lastKnown(c, me), true, listLive)).apply()
    }

    /** Sign-out: the account's number goes with it. */
    fun forget(c: Context) = prefs(c).edit().clear().apply()

    /** Posts the group summary with the total, or takes it down when no message notification is left. */
    @SuppressLint("MissingPermission")   // checked through areNotificationsEnabled
    fun refresh(c: Context, me: String) {
        if (me.isEmpty()) return
        val nmc = NotificationManagerCompat.from(c)
        val key = group(me)
        val children = try {
            nmc.activeNotifications.count { it.notification.group == key && it.tag != SUMMARY_TAG }
        } catch (_: Exception) { return }
        if (children == 0 || !nmc.areNotificationsEnabled()) {
            nmc.cancel(SUMMARY_TAG, SUMMARY_ID)
            return
        }
        val total = lastKnown(c, me)
        val tap = PendingIntent.getActivity(c, SUMMARY_TAG.hashCode(),
            Intent(c, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(c, NotifChannel.MESSAGES.id)
            .setSmallIcon(R.drawable.ic_stat_fariin)
            .setColor(Color.rgb(0x1E, 0x88, 0xE5))
            .setContentTitle("Fariin")
            .setContentText(if (total == 1) "1 unread message" else if (total > 1) "$total unread messages" else "New messages")
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setGroup(key)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setNumber(total)
        nmc.notify(SUMMARY_TAG, SUMMARY_ID, b.build())
    }
}
