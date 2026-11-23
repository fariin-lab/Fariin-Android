package com.fariin.app.call

import android.content.Context

/**
 * L-51: the ring follows Settings > Call Ringtone. That row opens the Calls notification channel's
 * own page, so the sound picked there is the ring of both 1:1 and group calls. The channel's
 * starting sound (the short notification sound, for missed-call alerts) or none means the phone's
 * own ringtone.
 */
object RingtoneChoice {
    /** The system's "default notification sound" address, as the channel stores it. */
    const val DEFAULT_NOTIFICATION = "content://settings/system/notification_sound"

    /** Pure, for the tests: the uri text to ring with. */
    fun pick(channelSound: String?, defaultNotification: String?, defaultRingtone: String?): String? =
        channelSound?.takeIf { it.isNotEmpty() && it != defaultNotification && it != DEFAULT_NOTIFICATION } ?: defaultRingtone

    fun uri(c: Context): android.net.Uri? {
        val ring = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE)
        return try {
            val nm = c.getSystemService(android.app.NotificationManager::class.java)
            val ch = nm?.getNotificationChannel(com.fariin.app.push.NotifChannel.CALLS.id)?.sound
            val notif = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
            pick(ch?.toString(), notif?.toString(), ring?.toString())?.let { android.net.Uri.parse(it) }
        } catch (_: Exception) { ring }
    }
}
