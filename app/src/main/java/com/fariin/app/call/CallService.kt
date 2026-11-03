package com.fariin.app.call

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.fariin.app.R
import com.fariin.app.push.NotifChannel
import com.fariin.app.ui.call.CallActivity

/**
 * Keeps the process, the microphone and the camera alive while a call is on (the Android side of
 * what CallKit's audio session does on iOS). Shows the ongoing-call notification with Hang Up.
 */
class CallService : Service() {
    enum class Mode { CALL }

    companion object {
        private const val EXTRA_VIDEO = "video"
        const val ONGOING_ID = 7301

        fun start(c: Context, mode: Mode, video: Boolean) {
            val i = Intent(c, CallService::class.java).putExtra(EXTRA_VIDEO, video)
            try { ContextCompat.startForegroundService(c, i) } catch (_: Exception) {}
        }

        fun stop(c: Context) { c.stopService(Intent(c, CallService::class.java)) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val video = intent?.getBooleanExtra(EXTRA_VIDEO, false) == true
        CallNotifications.ensureChannel(this)
        var types = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            types = ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (granted(Manifest.permission.RECORD_AUDIO)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                if (video && granted(Manifest.permission.CAMERA)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
        }
        try {
            ServiceCompat.startForeground(this, ONGOING_ID, CallNotifications.ongoing(this), types)
        } catch (_: Exception) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    override fun onTaskRemoved(rootIntent: Intent?) {
        CallManager.hangUp()
        super.onTaskRemoved(rootIntent)
    }
}

/** The incoming-call full-screen notification and the ongoing-call notification. */
object CallNotifications {
    private const val ONGOING_CHANNEL = "call_ongoing"
    private const val INCOMING_ID = 7300
    const val ACTION_ACCEPT = "com.fariin.app.call.ACCEPT"
    const val ACTION_DECLINE = "com.fariin.app.call.DECLINE"
    const val ACTION_HANGUP = "com.fariin.app.call.HANGUP"

    fun ensureChannel(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(ONGOING_CHANNEL) != null) return
        nm.createNotificationChannel(NotificationChannel(ONGOING_CHANNEL, "Ongoing calls", NotificationManager.IMPORTANCE_LOW)
            .apply { description = "Shown while you are on a call" })
    }

    private fun activity(c: Context, action: String?, req: Int): PendingIntent {
        val i = Intent(c, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (action != null) i.action = action
        return PendingIntent.getActivity(c, req, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /**
     * The ring: a high-priority call notification on the Calls channel with a full-screen intent
     * that opens the incoming screen over the lock screen. Silent itself: CallManager plays the
     * looping ringtone (the channel's sound would play only once).
     */
    fun showIncoming(c: Context, callId: String, name: String, video: Boolean) {
        val person = Person.Builder().setName(name).setImportant(true).build()
        val n = NotificationCompat.Builder(c, NotifChannel.CALLS.id)
            .setSmallIcon(R.drawable.ic_stat_fariin)
            .setContentTitle(name)
            .setContentText(if (video) "Incoming video call" else "Incoming call")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setSilent(true)
            .setFullScreenIntent(activity(c, null, 1), true)
            .setContentIntent(activity(c, null, 1))
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(person, activity(c, ACTION_DECLINE, 2), activity(c, ACTION_ACCEPT, 3)))
            .build()
        try { NotificationManagerCompat.from(c).notify(callId, INCOMING_ID, n) } catch (_: SecurityException) {}
        lastIncoming = callId
    }

    private var lastIncoming: String? = null

    fun cancelIncoming(c: Context) {
        lastIncoming?.let { NotificationManagerCompat.from(c).cancel(it, INCOMING_ID) }
        lastIncoming = null
    }

    fun ongoing(c: Context): Notification {
        val ui = CallManager.ui.value
        val person = Person.Builder().setName(ui.peerName.ifEmpty { "Fariin" }).build()
        return NotificationCompat.Builder(c, ONGOING_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_fariin)
            .setContentTitle(ui.peerName.ifEmpty { "Fariin" })
            .setContentText(if (ui.status.video) "Video call" else "Voice call")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(activity(c, null, 4))
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(person, activity(c, ACTION_HANGUP, 5)))
            .build()
    }
}
