package com.fariin.app.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.SystemClock
import java.io.File
import java.nio.ByteBuffer
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
 * Voice notes: hold-to-record, and the locked recording that can pause, be listened back and
 * carry on (iOS AudioRecorder pauseForReview / resume).
 *
 * ⚠️ FORMAT: AAC-LC in an MPEG-4 (.m4a) file, 48 kHz mono, 32 kbit/s. iOS now sends Opus in CAF,
 * but every iPhone build plays AAC m4a (that is what its older notes are, and its player opens
 * whatever the bytes are, whatever the name). Android cannot write CAF, and the Ogg Opus Android
 * can write is a container iOS does not open. So AAC is the one format both phones play.
 *
 * A pause closes the current stretch (its own m4a). Keep recording opens a new one, and the note
 * that is sent is the stretches joined into ONE m4a, the same AAC track copied sample by sample
 * (no re-encode), so it is the format above whatever the number of pauses. A note never paused
 * is the one file the recorder wrote, exactly as before.
 *
 * The level meter samples the recorder's peak 20 times a second (iOS meters at about the same
 * rate); [MediaRules.bars] turns the readings into the 40 bars iOS stores.
 */
class VoiceRecorder(private val context: Context) {
    class Result(val file: File, val duration: Double, val waveform: List<Int>, val viewOnce: Boolean = false) {
        /** The same note, sent as a one-time listen or not (the "1" on the locked bar). */
        fun once(on: Boolean) = Result(file, duration, waveform, on)
    }

    /** A paused note, ready to be listened back. */
    class Review(val file: File, val duration: Double, val waveform: List<Int>)

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
    @Volatile private var paused = false

    // Finished stretches (audio thread only), the seconds they hold, and the joined copy for a review.
    private val parts = ArrayList<File>()
    @Volatile private var banked = 0.0
    private var joined: File? = null

    private val _elapsed = MutableStateFlow(0.0)
    val elapsed: StateFlow<Double> = _elapsed
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level

    val isRecording: Boolean get() = wanted
    val isPaused: Boolean get() = paused

    /**
     * Asks for recording to start and returns at once. False only when a recording is already
     * wanted; a microphone that cannot be opened shows as a [finish] that hands back null.
     */
    fun start(): Boolean {
        cancel()
        wanted = true; paused = false
        levels.clear()
        _elapsed.value = 0.0; _level.value = 0f
        scope.launch(audio) {
            banked = 0.0
            openRecorder()
        }
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
            r.setMaxDuration(VoiceRecordRules.remainingSeconds(banked) * 1000)
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
        recorder = r
        scope.launch {
            meter?.cancel()
            meter = scope.launch {
                while (isActive && wanted && !paused && recorder === r) {
                    val amp = runCatching { r.maxAmplitude }.getOrDefault(0)
                    val l = MediaRules.level(amp)
                    levels.add(l); _level.value = l
                    _elapsed.value = banked + (SystemClock.elapsedRealtime() - startedAt) / 1000.0
                    delay(50)
                }
            }
        }
    }

    /** Audio thread: stop the open stretch and keep it when it holds any sound. */
    private fun closeStretch(endAt: Long) {
        val r = recorder ?: return
        val f = file
        recorder = null; file = null
        val seconds = (endAt - startedAt) / 1000.0
        val ok = runCatching { r.stop() }.isSuccess
        runCatching { r.release() }
        if (ok && f != null && f.length() > 0L && seconds > 0) { parts.add(f); banked += seconds } else f?.delete()
    }

    /** Audio thread: the stretches as one file (the only one itself when there is one). */
    private fun joinParts(): File? = when (parts.size) {
        0 -> null
        1 -> parts[0]
        else -> File(context.cacheDir, "voice-${System.currentTimeMillis()}-joined.m4a").takeIf { stitch(parts, it) }
    }

    /** Audio thread: drop the joined review copy unless it is one of the stretches itself. */
    private fun dropJoined() {
        joined?.let { if (it !in parts) it.delete() }
        joined = null
    }

    /**
     * Pause a locked recording: the stretch so far is closed and handed back as one file to
     * listen to. Null (on the main thread) when nothing was recorded.
     */
    fun pause(onReady: (Review?) -> Unit) {
        if (!wanted || paused) { onReady(null); return }
        paused = true
        val endAt = SystemClock.elapsedRealtime()
        meter?.cancel()
        _level.value = 0f
        scope.launch {
            val review = withContext(audio) {
                closeStretch(endAt)
                dropJoined()
                joined = joinParts()
                joined?.let { Review(it, banked, MediaRules.bars(levels.toList())) }
            }
            if (review != null && wanted) _elapsed.value = review.duration
            onReady(if (wanted) review else null)
        }
    }

    /** Carry on after a pause: a new stretch, joined to the others when the note is sent. */
    fun resume() {
        if (!wanted || !paused) return
        paused = false
        scope.launch(audio) {
            dropJoined()
            openRecorder()
        }
    }

    /** Stop; [onDone] gets the note on the main thread, or null when it is under a second or failed. */
    fun finish(onDone: (Result?) -> Unit) {
        if (!wanted) { onDone(null); return }
        wanted = false
        val wasPaused = paused
        paused = false
        val endAt = SystemClock.elapsedRealtime()
        meter?.cancel()
        _elapsed.value = 0.0; _level.value = 0f
        scope.launch {
            val result = withContext(audio) {
                if (!wasPaused) closeStretch(endAt)
                val out = (if (wasPaused) joined else null) ?: run { dropJoined(); joinParts() }
                joined = null
                val duration = banked
                parts.forEach { if (it != out) it.delete() }
                parts.clear()
                banked = 0.0
                if (out == null || duration < MediaRules.VOICE_MIN_SECONDS || out.length() == 0L) {
                    out?.delete(); null
                } else Result(out, duration, MediaRules.bars(levels.toList()))
            }
            onDone(result)
        }
    }

    fun cancel() {
        if (!wanted && recorder == null) return
        wanted = false; paused = false
        meter?.cancel()
        _elapsed.value = 0.0; _level.value = 0f
        scope.launch(audio) {
            recorder?.let { r ->
                recorder = null
                runCatching { r.stop() }
                runCatching { r.release() }
            }
            file?.delete(); file = null
            dropJoined()
            parts.forEach { it.delete() }; parts.clear()
            banked = 0.0
        }
    }

    /**
     * Join AAC m4a stretches into one m4a by copying their samples, each stretch moved on to start
     * one frame after the last. All stretches come from the same recorder settings, so the first
     * one's track format describes them all.
     */
    private fun stitch(inputs: List<File>, out: File): Boolean {
        val muxer = runCatching { MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4) }.getOrNull()
            ?: return false
        var track = -1
        var offset = 0L
        val buf = ByteBuffer.allocate(256 * 1024)
        val info = MediaCodec.BufferInfo()
        val ok = runCatching {
            for (p in inputs) {
                val ex = MediaExtractor()
                try {
                    ex.setDataSource(p.absolutePath)
                    val ti = (0 until ex.trackCount).firstOrNull {
                        ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                    } ?: continue
                    ex.selectTrack(ti)
                    if (track < 0) { track = muxer.addTrack(ex.getTrackFormat(ti)); muxer.start() }
                    var last = 0L
                    var wrote = false
                    while (true) {
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) break
                        info.set(0, n, ex.sampleTime + offset,
                            if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                        muxer.writeSampleData(track, buf, info)
                        last = info.presentationTimeUs; wrote = true
                        ex.advance()
                    }
                    offset = VoiceRecordRules.nextOffsetUs(last, wrote, offset)
                } finally { ex.release() }
            }
            if (track >= 0) muxer.stop()
            track >= 0
        }.getOrDefault(false)
        runCatching { muxer.release() }
        if (!ok) out.delete()
        return ok
    }
}
