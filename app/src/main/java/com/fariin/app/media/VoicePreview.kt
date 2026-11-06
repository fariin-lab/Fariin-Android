package com.fariin.app.media

import android.media.AudioAttributes
import android.media.MediaPlayer
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
 * A private player for one file: the locked recording's listen-back and the one-time note's
 * page. Not [VoicePlayer], the shared engine that outlives screens: what plays here must stop
 * with its screen (iOS keeps the review and OneTimeVoicePage off the shared engine the same way).
 */
class VoicePreview {
    data class State(val ready: Boolean = false, val playing: Boolean = false,
                     val positionMs: Long = 0, val durationMs: Long = 0, val ended: Boolean = false) {
        val progress: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private var player: MediaPlayer? = null
    private var ticker: Job? = null
    private val main = CoroutineScope(Dispatchers.Main)

    /** Open [file]; false when it cannot be played. [rewindAtEnd]: park at the start when it ends (review). */
    fun load(file: File, rewindAtEnd: Boolean = true): Boolean {
        release()
        val p = MediaPlayer()
        return try {
            p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            p.setDataSource(file.absolutePath)
            p.prepare()
            p.setOnCompletionListener {
                ticker?.cancel()
                if (rewindAtEnd) { runCatching { it.seekTo(0) }; _state.value = _state.value.copy(playing = false, positionMs = 0, ended = true) }
                else _state.value = _state.value.copy(playing = false, positionMs = _state.value.durationMs, ended = true)
            }
            player = p
            _state.value = State(ready = true, durationMs = p.duration.toLong().coerceAtLeast(0))
            true
        } catch (_: Exception) {
            runCatching { p.release() }
            false
        }
    }

    fun play() {
        val p = player ?: return
        if (p.isPlaying) return
        runCatching { p.start() }.onFailure { return }
        _state.value = _state.value.copy(playing = true, ended = false)
        ticker?.cancel()
        ticker = main.launch {
            while (isActive) {
                val now = player ?: break
                if (now.isPlaying) _state.value = _state.value.copy(positionMs = runCatching { now.currentPosition.toLong() }.getOrDefault(0))
                delay(50)
            }
        }
    }

    fun pause() {
        val p = player ?: return
        runCatching { if (p.isPlaying) p.pause() }
        ticker?.cancel()
        _state.value = _state.value.copy(playing = false)
    }

    fun toggle() { if (_state.value.playing) pause() else play() }

    /** Move to [fraction] of the note, playing or paused. */
    fun seek(fraction: Float) {
        val p = player ?: return
        val ms = (_state.value.durationMs * fraction.coerceIn(0f, 1f)).toLong()
        runCatching { p.seekTo(ms.toInt()) }
        _state.value = _state.value.copy(positionMs = ms, ended = false)
    }

    fun release() {
        ticker?.cancel(); ticker = null
        player?.let { runCatching { it.stop() }; runCatching { it.release() } }
        player = null
        _state.value = State()
    }
}
