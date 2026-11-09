package com.fariin.app.media

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer

/**
 * Raise to ear for voice notes (iOS VoiceNotePlayer, proximity): while a note plays, holding the phone
 * to the ear moves it to the earpiece and turns the screen off; taking it away puts it back on the
 * loudspeaker. Never while a call is up (the call owns the audio route then).
 */
internal object VoiceEar {
    private var sensors: SensorManager? = null
    private var listener: SensorEventListener? = null
    private var lock: PowerManager.WakeLock? = null
    private var atEar = false
    private var player: (() -> ExoPlayer?)? = null
    private var app: Context? = null

    private val media: AudioAttributes = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build()
    private val ear: AudioAttributes = AudioAttributes.Builder().setUsage(C.USAGE_VOICE_COMMUNICATION)
        .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build()

    private fun callUp(): Boolean =
        com.fariin.app.call.CallManager.ui.value.status.live ||
            com.fariin.app.call.GroupCall.ui.value.joined || com.fariin.app.call.GroupCall.ui.value.connecting

    /** Start watching the sensor for the open note; [current] is the player at the time of each change. */
    fun watch(context: Context, current: () -> ExoPlayer?) {
        player = current
        // The next note of a run, started while the phone is still at the ear: straight to the earpiece.
        if (atEar) current()?.setAudioAttributes(ear, false)
        if (listener != null) return
        val ctx = context.applicationContext
        app = ctx
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val s = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY) ?: return
        val near = minOf(5f, s.maximumRange)
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val now = e.values.firstOrNull()?.let { it < near } ?: false
                if (now != atEar) set(ctx, now)
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sensors = sm
        listener = l
        sm.registerListener(l, s, SensorManager.SENSOR_DELAY_NORMAL)
    }

    /** The note closed: stop watching and put the sound back where it was. */
    fun stop() {
        listener?.let { sensors?.unregisterListener(it) }
        listener = null
        app?.let { if (atEar) set(it, false) }
        player = null
    }

    private fun set(ctx: Context, near: Boolean) {
        if (near && callUp()) return
        val p = player?.invoke()
        if (near && p?.isPlaying != true) return
        atEar = near
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (near) {
            am?.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am?.availableCommunicationDevices?.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                    ?.let { am.setCommunicationDevice(it) }
            } else {
                @Suppress("DEPRECATION")
                am?.isSpeakerphoneOn = false
            }
            p?.setAudioAttributes(ear, false)
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm != null && pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) && lock == null) {
                lock = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "fariin:voice-ear").apply {
                    setReferenceCounted(false); acquire(10 * 60 * 1000L)
                }
            }
        } else {
            lock?.let { runCatching { if (it.isHeld) it.release() } }
            lock = null
            if (!callUp()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am?.clearCommunicationDevice()
                am?.mode = AudioManager.MODE_NORMAL
            }
            p?.setAudioAttributes(media, true)
        }
    }
}
