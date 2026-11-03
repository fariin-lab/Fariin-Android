package com.fariin.app.push

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** One in-app banner: the chat it opens, the name, the words, the face (a URL the Avatar loads). */
data class InAppBanner(val cid: String, val title: String, val body: String, val photo: String?)

/** Settings > Notifications > In-App Notifications (prefs "fariin_push", each absent = on). */
data class InAppPrefs(val sound: Boolean = true, val vibrate: Boolean = true, val preview: Boolean = true)

sealed class InAppRoute {
    /** Nothing at all (NotifBuilder skipped it: the open chat, signed out, my own message...). */
    data object Nothing : InAppRoute()
    /** The system notification, as before: the app is behind, or the push is not a chat message. */
    data object System : InAppRoute()
    /** In front, a chat message: no system notification; each of these on its own switch. */
    data class InApp(val banner: InAppBanner?, val sound: Boolean, val vibrate: Boolean) : InAppRoute()
}

/**
 * The foreground half of iOS PushManager.willPresent, with no Android types so the unit tests run it.
 * - The chat on screen never alerts (NotifBuilder already skipped it).
 * - In front, a CHAT message gets our banner and never the system one; anything without a chat to
 *   open (and a call) keeps the system notification, there is nothing for ours to route to.
 * - In-App Preview off: no banner; In-App Sounds and In-App Vibrate still apply on their own.
 * - App Lock showing: no banner (on iOS the lock's shield covers it), the sound and buzz still come.
 * - Message Preview off: the push carries no name, so the banner says what the push says ("Fariin",
 *   the server's hint), as iOS; the face is the chat's own from this phone's list, as iOS.
 * Muted chats are the server's job on both: it sends them no push.
 */
object InAppRules {
    fun route(
        decision: NotifDecision,
        inFront: Boolean,
        locked: Boolean,
        prefs: InAppPrefs,
        localPhoto: String? = null,
    ): InAppRoute {
        val s = (decision as? NotifDecision.Show)?.spec ?: return InAppRoute.Nothing
        if (!inFront) return InAppRoute.System
        if (!s.opensChat || s.channel != NotifChannel.MESSAGES) return InAppRoute.System
        val banner = if (prefs.preview && !locked) {
            val photo = if (s.plain) localPhoto else if (s.isGroup) s.groupPhoto ?: localPhoto else s.senderPhoto ?: localPhoto
            InAppBanner(s.key, s.title.ifBlank { "New message" }, s.body, photo)
        } else null
        return InAppRoute.InApp(banner, prefs.sound, prefs.vibrate)
    }
}

/**
 * The banner on screen (ui/components/InAppBannerHost draws it) and the in-app sound and buzz, after
 * iOS InAppBannerCenter: a second message replaces the first and restarts the clock, rather than
 * stacking; it stays 5 seconds; a finger on it holds the clock.
 */
object InAppNotify {
    private const val PREFS = "fariin_push"
    /** iOS InAppBannerCenter.visibleFor. */
    const val VISIBLE_MS = 5_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var hideJob: Job? = null

    val banner = MutableStateFlow<InAppBanner?>(null)
    /** One buzz per message, done by the host's view (a haptic, so no vibrate permission). */
    val buzz = MutableSharedFlow<Unit>(extraBufferCapacity = 4)

    fun prefs(c: Context): InAppPrefs {
        val p = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return InAppPrefs(p.getBoolean("notif.inAppSound", true), p.getBoolean("notif.inAppVibrate", true),
            p.getBoolean("notif.inAppPreview", true))
    }

    fun present(c: Context, r: InAppRoute.InApp) {
        r.banner?.let { b -> scope.launch { banner.value = b; armHide() } }
        if (r.vibrate) buzz.tryEmit(Unit)
        if (r.sound) playTone(c, r.banner?.cid)
    }

    fun dismiss() { scope.launch { hideJob?.cancel(); hideJob = null; banner.value = null } }

    /** A drag holds the banner; one let go back home starts a fresh clock (iOS holdWhileDragging). */
    fun hold() { scope.launch { hideJob?.cancel(); hideJob = null } }
    fun release() { scope.launch { if (banner.value != null && hideJob == null) armHide() } }

    /** Sign-out: nothing of the last account stays up. */
    fun reset() = dismiss()

    private fun armHide() {
        hideJob?.cancel()
        hideJob = scope.launch { delay(VISIBLE_MS); banner.value = null; hideJob = null }
    }

    /**
     * The chat's CHOSEN message sound (iOS SoundStore.sound(cid, .message)): the conversation's own
     * channel when the person gave it one in Android's settings, else the Messages channel. A
     * channel set to no sound is a silent banner. Silent and vibrate modes play nothing.
     */
    private fun playTone(c: Context, cid: String?) {
        try {
            val audio = c.getSystemService(AudioManager::class.java)
            if (audio?.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
            NotificationPoster.ensureChannels(c)
            val nm = c.getSystemService(NotificationManager::class.java) ?: return
            val ch = (if (cid != null && Build.VERSION.SDK_INT >= 30) nm.getNotificationChannel(NotifChannel.MESSAGES.id, cid) else null)
                ?: nm.getNotificationChannel(NotifChannel.MESSAGES.id)
            val uri: Uri = (if (ch != null) ch.sound else RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)) ?: return
            val tone = RingtoneManager.getRingtone(c.applicationContext, uri) ?: return
            tone.audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            tone.play()
        } catch (_: Exception) {}
    }
}
