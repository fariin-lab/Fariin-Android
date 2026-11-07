package com.fariin.app.call

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** The call-progress sounds iOS RingbackTone makes. */
enum class CallTone { RINGBACK, BUSY, ENDED, VIDEO_ON, GROUP_JOINED, GROUP_LEFT }

/**
 * iOS RingbackTone: the same notes, cadence and levels, made in memory (no sound files, as iOS).
 * Pure, so the samples and the rules for when each one plays are unit tested.
 */
object CallToneSamples {
    /** iOS `level`: about -3 dBFS, heard at arm's length without the speaker. */
    private const val LEVEL = 23000.0

    private fun tone(t: Double, a: Double, b: Double) = (sin(2 * PI * a * t) + sin(2 * PI * b * t)) / 2

    /** 0..1 fade at both ends of a sound `length` seconds long, `t` seconds in. */
    private fun fade(t: Double, length: Double, edge: Double = 0.02) = max(0.0, min(1.0, min(t / edge, (length - t) / edge)))

    private fun clip(v: Double) = max(-1.0, min(1.0, v))

    fun sampleRate(t: CallTone) = when (t) {
        CallTone.RINGBACK, CallTone.BUSY, CallTone.ENDED -> 8000
        else -> 16000
    }

    fun samples(t: CallTone): ShortArray = when (t) {
        CallTone.RINGBACK -> ringback()
        CallTone.BUSY -> busy()
        CallTone.ENDED -> ended()
        CallTone.VIDEO_ON -> twoNotes(660.0, 880.0, 0.12, 0.04, 5200.0)
        CallTone.GROUP_JOINED -> twoNotes(523.0, 784.0, 0.11, 0.03, 5600.0)
        CallTone.GROUP_LEFT -> twoNotes(784.0, 523.0, 0.11, 0.03, 5600.0)
    }

    /** 440+480 Hz, 2 s on / 4 s quiet: one 6 s cycle, looped by the player. */
    private fun ringback(): ShortArray {
        val sr = 8000; val cycle = 6.0
        return ShortArray((sr * cycle).toInt()) { i ->
            val time = i.toDouble() / sr
            val phase = time % cycle
            if (phase < 2.0) (clip(tone(time, 440.0, 480.0)) * LEVEL * fade(phase, 2.0)).toInt().toShort() else 0
        }
    }

    /** 480+620 Hz, 0.5 s on / 0.5 s off: one 1 s cycle; the player plays it twice. */
    private fun busy(): ShortArray {
        val sr = 8000
        return ShortArray(sr) { i ->
            val time = i.toDouble() / sr
            val phase = time % 1.0
            if (phase < 0.5) (clip(tone(time, 480.0, 620.0)) * LEVEL * fade(phase, 0.5)).toInt().toShort() else 0
        }
    }

    /** A short falling two-beep (520 then 410 Hz) once a call ends. */
    private fun ended(): ShortArray {
        val sr = 8000; val beep = 0.16; val gap = 0.05
        return ShortArray((sr * (beep * 2 + gap)).toInt()) { i ->
            val time = i.toDouble() / sr
            val v = when {
                time < beep -> sin(2 * PI * 520 * time)
                time > beep + gap -> sin(2 * PI * 410 * time)
                else -> 0.0
            }
            val env = 1.0 - min(1.0, abs((time % (beep + gap)) - beep / 2) / beep)
            (clip(v) * 9000 * max(0.2, env)).toInt().toShort()
        }
    }

    /** Two soft notes, each faded in and out (video on, group joined / left). */
    private fun twoNotes(first: Double, second: Double, note: Double, gap: Double, level: Double): ShortArray {
        val sr = 16000
        return ShortArray((sr * (note * 2 + gap)).toInt()) { i ->
            val time = i.toDouble() / sr
            var v = 0.0; var local = 0.0
            if (time < note) { v = sin(2 * PI * first * time); local = time }
            else if (time > note + gap) { v = sin(2 * PI * second * time); local = time - note - gap }
            val env = sin(PI * min(1.0, max(0.0, local / note)))
            (v * level * env).toInt().toShort()
        }
    }
}

/** When each sound plays, as iOS CallService and GroupCallService decide it. */
object CallToneRules {
    /**
     * iOS startRingbackIfDue: the caller hears one calling sound from the dial to the pick-up,
     * also once the other phone rings.
     */
    fun ringback(s: CallStatus) = s.isCaller && s.phase == CallPhase.OUTGOING && !s.accepted

    /**
     * iOS playEndTone, only when the end was not my own tap and I heard the call (caller, or
     * accepted, or connected): busy for busy, the short ended tone for everything else.
     */
    fun endTone(s: CallStatus, byUser: Boolean): CallTone? {
        val heard = s.isCaller || s.accepted || s.connectedAtMs != null
        if (byUser || !heard) return null
        return when (s.endReason) {
            EndReason.BUSY -> CallTone.BUSY
            EndReason.NONE -> null
            else -> CallTone.ENDED
        }
    }

    /**
     * iOS noteRoomPeople: someone came into or went out of a running group call. The joined tone
     * for an arrival, the left tone for a departure, one sound for several in one pass. Not in the
     * first 1.5 s after I got in (that is the room I walked into), not past 20 people.
     */
    fun presenceTone(before: Set<String>, now: Set<String>, msSinceJoin: Long): CallTone? {
        if (msSinceJoin <= PRESENCE_QUIET_MS || now.size + 1 > PRESENCE_MAX_PEOPLE) return null
        return when {
            (now - before).isNotEmpty() -> CallTone.GROUP_JOINED
            (before - now).isNotEmpty() -> CallTone.GROUP_LEFT
            else -> null
        }
    }
    const val PRESENCE_QUIET_MS = 1500L
    const val PRESENCE_MAX_PEOPLE = 20

    /** iOS finishCall's tone window: the busy tone's two cycles, or the short end tone. */
    fun endToneMs(t: CallTone) = if (t == CallTone.BUSY) 2000L else 600L

    /**
     * iOS chimeForTheirCameraIfDue: their camera came on in a live call, more than 2 s after it
     * connected (not the camera a video call starts with), and not while held.
     */
    fun videoChime(wasOn: Boolean, on: Boolean, s: CallStatus, held: Boolean, nowMs: Long): Boolean {
        val c = s.connectedAtMs ?: return false
        return !wasOn && on && s.phase == CallPhase.ACTIVE && !held && nowMs - c > 2000
    }
}

/**
 * Plays the call sounds through the call's own audio (voice communication), so they follow the
 * earpiece, the speaker or Bluetooth like the voice, and the call volume sets how loud they are.
 */
object CallTonePlayer {
    private const val TAG = "CallTones"
    private var ringback: AudioTrack? = null
    private var oneShot: AudioTrack? = null

    private fun build(t: CallTone, loops: Int): AudioTrack? = try {
        val pcm = CallToneSamples.samples(t)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(CallToneSamples.sampleRate(t)).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        track.write(pcm, 0, pcm.size)
        if (loops != 0) track.setLoopPoints(0, pcm.size, loops)
        track
    } catch (x: Exception) { Log.w(TAG, "tone $t failed", x); null }

    val ringbackPlaying get() = ringback != null

    fun startRingback() {
        ringback?.let { t ->
            // Started before the call took the phone's audio: that switch can stop the player
            // (iOS updateRingback plays it again the same way).
            if (t.playState != AudioTrack.PLAYSTATE_PLAYING) try { t.play() } catch (_: Exception) {}
            return
        }
        stopTone()
        ringback = build(CallTone.RINGBACK, -1)?.also { try { it.play() } catch (_: Exception) {} }
    }

    fun stopRingback() {
        ringback?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        ringback = null
    }

    /** One sound, once (the busy tone twice); a new one cuts the last. */
    fun play(t: CallTone) {
        stopTone()
        oneShot = build(t, if (t == CallTone.BUSY) 1 else 0)?.also { try { it.play() } catch (_: Exception) {} }
    }

    fun stopTone() {
        oneShot?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        oneShot = null
    }
}

/**
 * The buzz under an incoming ring (iOS's system call screen vibrates by the phone's settings): on
 * while the phone rings or is on vibrate, never on silent or under Do Not Disturb's "no
 * interruptions" / "alarms only". The ring's own sound already follows the ringer and Do Not Disturb.
 */
object RingVibration {
    /** About a second on, a second off, as a phone call's ring. */
    private val PATTERN = longArrayOf(0, 1000, 1000)
    private var on: android.os.Vibrator? = null

    fun wanted(ringerMode: Int, filter: Int): Boolean =
        ringerMode != android.media.AudioManager.RINGER_MODE_SILENT &&
            filter != android.app.NotificationManager.INTERRUPTION_FILTER_NONE &&
            filter != android.app.NotificationManager.INTERRUPTION_FILTER_ALARMS

    fun start(c: android.content.Context) {
        if (on != null) return
        try {
            val audio = c.getSystemService(android.media.AudioManager::class.java) ?: return
            val nm = c.getSystemService(android.app.NotificationManager::class.java)
            if (!wanted(audio.ringerMode, nm?.currentInterruptionFilter ?: android.app.NotificationManager.INTERRUPTION_FILTER_ALL)) return
            val v = if (android.os.Build.VERSION.SDK_INT >= 31)
                c.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
            else @Suppress("DEPRECATION") c.getSystemService(android.os.Vibrator::class.java)
            if (v?.hasVibrator() != true) return
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            @Suppress("DEPRECATION") v.vibrate(android.os.VibrationEffect.createWaveform(PATTERN, 1), attrs)
            on = v
        } catch (x: Exception) { Log.w("CallTones", "ring vibration failed", x) }
    }

    fun stop() {
        try { on?.cancel() } catch (_: Exception) {}
        on = null
    }
}
