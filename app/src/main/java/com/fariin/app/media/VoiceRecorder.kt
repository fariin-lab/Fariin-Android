package com.fariin.app.media

import android.content.Context
import android.media.MediaRecorder
import android.os.SystemClock
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Hold-to-record voice notes.
 *
 * ⚠️ FORMAT: AAC-LC in an MPEG-4 (.m4a) file, 48 kHz mono, 32 kbit/s. iOS now sends Opus in CAF,
 * but every iPhone build plays AAC m4a (that is what its older notes are, and its player opens
 * whatever the bytes are, whatever the name). Android cannot write CAF, and the Ogg Opus Android
 * can write is a container iOS does not open. So AAC is the one format both phones play.
 *
 * The level meter samples the recorder's peak 20 times a second (iOS meters at about the same
 * rate); [MediaRules.bars] turns the readings into the 40 bars iOS stores.
 */
class VoiceRecorder(private val context: Context) {
    class Result(val file: File, val duration: Double, val waveform: List<Int>)

    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L
    private val levels = ArrayList<Float>()
    private var meter: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    private val _elapsed = MutableStateFlow(0.0)
    val elapsed: StateFlow<Double> = _elapsed
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level

    val isRecording: Boolean get() = recorder != null

    /** False when the microphone could not be opened. */
    fun start(): Boolean {
        cancel()
        val f = File(context.cacheDir, "voice-${System.currentTimeMillis()}.m4a")
        val r = if (android.os.Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(48_000)
            r.setAudioEncodingBitRate(32_000)
            r.setMaxDuration(MediaRules.VOICE_NOTE_SECONDS * 1000)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
            recorder = r; file = f
            startedAt = SystemClock.elapsedRealtime()
            levels.clear()
            meter = scope.launch {
                while (isActive) {
                    val amp = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
                    val l = MediaRules.level(amp)
                    levels.add(l); _level.value = l
                    _elapsed.value = (SystemClock.elapsedRealtime() - startedAt) / 1000.0
                    delay(50)
                }
            }
            true
        } catch (_: Exception) {
            runCatching { r.release() }
            f.delete()
            recorder = null; file = null
            false
        }
    }

    /** Stop and hand back the note, or null when it is under a second or failed. */
    fun finish(): Result? {
        val r = recorder ?: return null
        val f = file
        meter?.cancel()
        val duration = (SystemClock.elapsedRealtime() - startedAt) / 1000.0
        val ok = runCatching { r.stop() }.isSuccess
        runCatching { r.release() }
        recorder = null; file = null
        _elapsed.value = 0.0; _level.value = 0f
        if (!ok || f == null || duration < MediaRules.VOICE_MIN_SECONDS || f.length() == 0L) {
            f?.delete(); return null
        }
        return Result(f, duration, MediaRules.bars(levels.toList()))
    }

    fun cancel() {
        val r = recorder ?: return
        meter?.cancel()
        runCatching { r.stop() }
        runCatching { r.release() }
        file?.delete()
        recorder = null; file = null
        _elapsed.value = 0.0; _level.value = 0f
    }
}
