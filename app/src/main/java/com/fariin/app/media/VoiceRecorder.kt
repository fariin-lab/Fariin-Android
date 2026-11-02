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
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

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

    // MediaRecorder prepare/start/stop run on this one thread, in the order they were asked for:
    // the first prepare can take seconds while the phone opens its audio input, and on the main
    // thread inside the hold gesture that froze the app once (QA 2026-11-02). UI state stays on main.
    private val audio = Executors.newSingleThreadExecutor { r -> Thread(r, "voice-recorder") }.asCoroutineDispatcher()
    @Volatile private var recorder: MediaRecorder? = null
    private var file: File? = null
    @Volatile private var startedAt = 0L
    private val levels = java.util.Collections.synchronizedList(ArrayList<Float>())
    private var meter: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)
    @Volatile private var wanted = false

    private val _elapsed = MutableStateFlow(0.0)
    val elapsed: StateFlow<Double> = _elapsed
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level

    val isRecording: Boolean get() = wanted

    /**
     * Asks for recording to start and returns at once. False only when a recording is already
     * wanted; a microphone that cannot be opened shows as a [finish] that hands back null.
     */
    fun start(): Boolean {
        cancel()
        wanted = true
        _elapsed.value = 0.0; _level.value = 0f
        scope.launch(audio) { openRecorder() }
        return true
    }

    private fun openRecorder() {
        val f = File(context.cacheDir, "voice-${System.currentTimeMillis()}.m4a")
        val r = if (android.os.Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        try {
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
        } catch (_: Exception) {
            runCatching { r.release() }
            f.delete()
            return
        }
        file = f
        startedAt = SystemClock.elapsedRealtime()
        levels.clear()
        recorder = r
        scope.launch {
            meter?.cancel()
            meter = scope.launch {
                while (isActive && wanted && recorder === r) {
                    val amp = runCatching { r.maxAmplitude }.getOrDefault(0)
                    val l = MediaRules.level(amp)
                    levels.add(l); _level.value = l
                    _elapsed.value = (SystemClock.elapsedRealtime() - startedAt) / 1000.0
                    delay(50)
                }
            }
        }
    }

    /** Stop; [onDone] gets the note on the main thread, or null when it is under a second or failed. */
    fun finish(onDone: (Result?) -> Unit) {
        if (!wanted) { onDone(null); return }
        wanted = false
        val endAt = SystemClock.elapsedRealtime()
        meter?.cancel()
        _elapsed.value = 0.0; _level.value = 0f
        scope.launch {
            val result = withContext(audio) {
                val r = recorder ?: return@withContext null
                val f = file
                val duration = (endAt - startedAt) / 1000.0
                recorder = null; file = null
                val ok = runCatching { r.stop() }.isSuccess
                runCatching { r.release() }
                if (!ok || f == null || duration < MediaRules.VOICE_MIN_SECONDS || f.length() == 0L) {
                    f?.delete(); null
                } else Result(f, duration, MediaRules.bars(levels.toList()))
            }
            onDone(result)
        }
    }

    fun cancel() {
        if (!wanted && recorder == null) return
        wanted = false
        meter?.cancel()
        _elapsed.value = 0.0; _level.value = 0f
        scope.launch(audio) {
            val r = recorder ?: return@launch
            recorder = null
            runCatching { r.stop() }
            runCatching { r.release() }
            file?.delete(); file = null
        }
    }
}
