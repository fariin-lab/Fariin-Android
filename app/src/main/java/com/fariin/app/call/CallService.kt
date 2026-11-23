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
 * While my screen is shared in a 1:1 call it also carries the media projection type, which
 * Android 14 and later want in place before the capture starts (after the system's consent).
 */
class CallService : Service() {
    enum class Mode { CALL }

    companion object {
        private const val EXTRA_VIDEO = "video"
        private const val EXTRA_SCREEN = "screen"
        const val ONGOING_ID = 7301

        /** Runs once on the main thread after the next start with [screen]: true if the projection type took. */
        private var screenReady: ((Boolean) -> Unit)? = null

        fun start(c: Context, mode: Mode, video: Boolean, screen: Boolean = false, onScreenReady: ((Boolean) -> Unit)? = null) {
            if (screen) screenReady = onScreenReady
            val i = Intent(c, CallService::class.java).putExtra(EXTRA_VIDEO, video).putExtra(EXTRA_SCREEN, screen)
            try { ContextCompat.startForegroundService(c, i) } catch (_: Exception) {
                if (screen) { screenReady = null; onScreenReady?.invoke(false) }
            }
        }

        fun stop(c: Context) { c.stopService(Intent(c, CallService::class.java)) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val video = intent?.getBooleanExtra(EXTRA_VIDEO, false) == true
        val screen = intent?.getBooleanExtra(EXTRA_SCREEN, false) == true
        val ready = if (screen) screenReady.also { screenReady = null } else null
        CallNotifications.ensureChannel(this)
        var types = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            types = ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            if (screen) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (granted(Manifest.permission.RECORD_AUDIO)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                if (video && granted(Manifest.permission.CAMERA)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
        }
        // Started with startForegroundService, so a type refused (screen share without its grant,
        // mic or camera while the app is not in front: answering from a headset, car or watch on a
        // locked phone) must still end in startForeground, or Android kills the app mid-accept
        // (review 2026-11-05). Tried with fewer types each time: all, without the screen, the call
        // type alone, none.
        val tries = buildList {
            add(types)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(types and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION.inv())
                add(ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
            }
            add(0)
        }.distinct()
        var started = -1
        for (t in tries) {
            try { ServiceCompat.startForeground(this, ONGOING_ID, CallNotifications.ongoing(this), t); started = t; break } catch (_: Exception) {}
        }
        val shareOk = started >= 0 && (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || started and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION != 0)
        ready?.invoke(shareOk)
        if (started < 0) stopSelf()
        return START_NOT_STICKY
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    override fun onTaskRemoved(rootIntent: Intent?) {
        // The app swiped away: the call ends, a group call too (H-112: the mic stayed live in the room).
        if (CallManager.ui.value.status.live) CallManager.hangUp()
        if (GroupCall.ui.value.cid != null) GroupCall.leave()
        super.onTaskRemoved(rootIntent)
    }
}

/** The incoming-call full-screen notification and the ongoing-call notification. */
object CallNotifications {
    private const val ONGOING_CHANNEL = "call_ongoing"
    /** The ring's own channel: high importance (heads-up, full screen) and NO sound. Not "silent":
     *  Android never opens the full-screen call screen for a silent notification, so a locked phone
     *  rang with nothing to answer (owner's Pixel 2026-11-05). The ringtone is CallManager's. */
    private const val RING_CHANNEL = "call_ring"
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
    private fun ensureRingChannel(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(RING_CHANNEL) != null) return
        nm.createNotificationChannel(NotificationChannel(RING_CHANNEL, "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "The screen that rings for a call"
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        })
    }

    fun showIncoming(c: Context, callId: String, name: String, video: Boolean, photo: android.graphics.Bitmap? = null) {
        val person = Person.Builder().setName(name).setImportant(true)
            .apply { if (photo != null) setIcon(androidx.core.graphics.drawable.IconCompat.createWithBitmap(photo)) }
            .build()
        ensureRingChannel(c)
        val n = NotificationCompat.Builder(c, RING_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_fariin)
            .setContentTitle(name)
            .setContentText(if (video) "Incoming video call" else "Incoming call")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            // H-154: if the app dies while it rings, the ring still goes when the ring time is over.
            .setTimeoutAfter(CallWire.CALLEE_RING_MS + 5_000L)
            .setFullScreenIntent(activity(c, null, 1), true)
            .setContentIntent(activity(c, null, 1))
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(person, activity(c, ACTION_DECLINE, 2), activity(c, ACTION_ACCEPT, 3)))
            .build()
        try { NotificationManagerCompat.from(c).notify(callId, INCOMING_ID, n) } catch (_: SecurityException) {}
        lastIncoming = callId
    }

    private var lastIncoming: String? = null

    /**
     * H-154: an incoming-call notification left by a process that died while it rang. Run when the
     * call side starts: any ring notification that is not the call this process is ringing goes.
     */
    fun clearStale(c: Context, ringingId: String?) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        try {
            nm.activeNotifications.filter { it.id == INCOMING_ID && it.tag != ringingId }.forEach { nm.cancel(it.tag, INCOMING_ID) }
        } catch (_: Exception) {}
    }

    fun cancelIncoming(c: Context) {
        lastIncoming?.let { NotificationManagerCompat.from(c).cancel(it, INCOMING_ID) }
        lastIncoming = null
    }

    fun ongoing(c: Context): Notification {
        val ui = CallManager.ui.value
        // K-25: a group or link call names its group and says video while my camera or screen is on.
        val g = GroupCall.ui.value
        val group = g.cid != null && !ui.status.live
        val title = (if (group) g.title else ui.peerName).ifEmpty { "Fariin" }
        val video = if (group) g.cameraOn || g.sharing else ui.status.video
        val person = Person.Builder().setName(title).build()
        return NotificationCompat.Builder(c, ONGOING_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_fariin)
            .setContentTitle(title)
            .setContentText(if (group) (if (video) "Group video call" else "Group call") else if (video) "Video call" else "Voice call")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(activity(c, null, 4))
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(person, activity(c, ACTION_HANGUP, 5)))
            .build()
    }
}
