package com.fariin.app.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import com.fariin.app.R

/**
 * The short sound when a message I sent reaches the server (iOS ChatSounds): played once the server
 * has it, never at the tap, so a message that never goes through makes no sound and a photo makes it
 * when its upload is done. Settings > Notifications > Sound When Sent.
 *
 * Android: it plays on the notification volume (owner 2026-11-03 on the Pixel: on the system-sounds
 * stream it was not heard, that volume is often down or off); on vibrate it is a short buzz instead,
 * as Android messengers do; on silent / Do Not Disturb, nothing.
 */
object ChatSounds {
    private const val PREFS = "fariin_push"
    const val SENT_KEY = "chat.sentSound"

    private var app: Context? = null
    private var pool: SoundPool? = null
    private var sentId = 0
    @Volatile private var lastSent = 0L

    fun init(c: Context) {
        if (pool != null) return
        app = c.applicationContext
        pool = SoundPool.Builder().setMaxStreams(1).setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        ).build().also { sentId = it.load(c.applicationContext, R.raw.sent, 1) }
    }

    /** One short tick (the vibrate-mode stand-in for the sound). */
    private fun buzz(c: Context) {
        val v = if (android.os.Build.VERSION.SDK_INT >= 31)
            c.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") c.getSystemService(android.os.Vibrator::class.java)
        if (v?.hasVibrator() != true) return
        val effect = if (android.os.Build.VERSION.SDK_INT >= 29) android.os.VibrationEffect.createPredefined(android.os.VibrationEffect.EFFECT_TICK)
            else android.os.VibrationEffect.createOneShot(20, android.os.VibrationEffect.DEFAULT_AMPLITUDE)
        v.vibrate(effect)
    }

    /** Ten photos landing together make one sound, not ten. */
    fun sent() {
        val c = app ?: return
        val now = System.currentTimeMillis()
        if (now - lastSent < 300) return
        if (!c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(SENT_KEY, true)) return
        val audio = c.getSystemService(AudioManager::class.java)
        when (audio?.ringerMode) {
            AudioManager.RINGER_MODE_NORMAL -> { lastSent = now; pool?.play(sentId, 1f, 1f, 1, 0, 1f) }
            AudioManager.RINGER_MODE_VIBRATE -> { lastSent = now; buzz(c) }
            else -> {}
        }
    }
}
