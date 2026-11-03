package com.fariin.app.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import com.fariin.app.R

/**
 * The short sound when a message I sent reaches the server (iOS ChatSounds): played once the server
 * has it, never at the tap, so a message that never goes through makes no sound and a photo makes it
 * when its upload is done. Silent and vibrate modes silence it. Settings > Notifications > Sound When Sent.
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
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        ).build().also { sentId = it.load(c.applicationContext, R.raw.sent, 1) }
    }

    /** Ten photos landing together make one sound, not ten. */
    fun sent() {
        val c = app ?: return
        val now = System.currentTimeMillis()
        if (now - lastSent < 300) return
        if (!c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(SENT_KEY, true)) return
        val audio = c.getSystemService(AudioManager::class.java)
        if (audio?.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        lastSent = now
        pool?.play(sentId, 1f, 1f, 1, 0, 1f)
    }
}
