package com.fariin.app.media

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One voice note plays at a time, app wide (Kulan-iOS VoiceNotePlayer.shared): starting another
 * stops the first, the speed belongs to the chat (1, 1.5, 2, 0.5), and a paused note keeps its
 * place. Plays both iOS formats: Opus in CAF (moved into Ogg first, see [CafOpus]) and AAC m4a.
 */
object VoicePlayer {
    data class State(
        val id: String? = null,
        val playing: Boolean = false,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        /** Kept per note, so a paused note shows where it stopped. */
        val paused: Map<String, Long> = emptyMap(),
        val rates: Map<String, Float> = emptyMap(),
        val failed: Set<String> = emptySet(),
        /** The chat the open note belongs to, and who sent it (the bar away from that chat). */
        val cid: String? = null,
        val author: String = "",
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var player: ExoPlayer? = null
    private var ticker: Job? = null
    private var cid: String = ""
    private val main = CoroutineScope(Dispatchers.Main)

    fun rate(cid: String) = _state.value.rates[cid] ?: 1f

    fun cycleRate(cid: String) {
        val next = MediaRules.nextRate(rate(cid))
        _state.value = _state.value.copy(rates = _state.value.rates + (cid to next))
        if (this.cid == cid) player?.playbackParameters = PlaybackParameters(next)
    }

    /** Tap on the disc: play, pause, or resume [id]. [file] holds the plain bytes. */
    fun toggle(context: Context, id: String, cid: String, file: File, author: String = "") {
        val s = _state.value
        val p = player
        if (s.id == id && p != null) {
            if (p.isPlaying) p.pause() else { if (p.playbackState == Player.STATE_ENDED) p.seekTo(0); p.play() }
            return
        }
        main.launch {
            val playable = withContext(Dispatchers.IO) { playableFile(context, file) }
            if (playable == null) {
                _state.value = _state.value.copy(failed = _state.value.failed + id)
                return@launch
            }
            start(context, id, cid, playable, author)
        }
    }

    fun seek(id: String, fraction: Float) {
        val p = player ?: return
        if (_state.value.id != id || p.duration <= 0) return
        p.seekTo((p.duration * fraction.coerceIn(0f, 1f)).toLong())
        tick()
    }

    fun stop() {
        save()
        player?.release(); player = null
        ticker?.cancel()
        _state.value = _state.value.copy(id = null, playing = false, positionMs = 0, cid = null)
    }

    /** The bar's control: pause the open note, or carry on from where it stopped. */
    fun playPause() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else { if (p.playbackState == Player.STATE_ENDED) p.seekTo(0); p.play() }
    }

    /** The run's next voice note (VoiceRun), fetched and played as a tap on it would; heard, so marked played. */
    private suspend fun playNext(context: Context, cid: String, after: String) {
        val n = VoiceRun.nextAfter(cid, after) ?: return
        val url = n.localPath ?: n.audioUrl ?: return
        val f = VoiceRun.media?.file(url, n.enc) ?: return
        if (_state.value.id != null) return   // something else started meanwhile
        com.fariin.app.data.PlayedVoice.markPlayed(context, cid, n.createdAtMillis)
        toggle(context, n.rowId, cid, f, n.authorId)
    }

    private fun start(context: Context, id: String, cid: String, file: File, author: String) {
        save()
        player?.release()
        this.cid = cid
        val p = ExoPlayer.Builder(context.applicationContext).build()
        p.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
        p.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        p.playbackParameters = PlaybackParameters(rate(cid))
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { tick() }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    // Finished: the note is closed, as iOS (VoicePlayer.barVisible is "started, not
                    // yet finished or closed"), so the bar away from the chat goes; the bubble reads
                    // its length again. Before, the finished note stayed open and its bar never left.
                    _state.value = _state.value.copy(id = null, cid = null, playing = false, positionMs = 0,
                        paused = _state.value.paused - id)
                    ticker?.cancel()
                    // Released just after this callback returns, not from inside it.
                    if (player === p) { player = null; main.launch { p.release() } }
                    // iOS followOn: the next note sent straight after this one plays by itself.
                    main.launch { playNext(context.applicationContext, cid, id) }
                } else tick()
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                _state.value = _state.value.copy(playing = false, failed = _state.value.failed + id)
            }
        })
        p.prepare()
        _state.value.paused[id]?.let { p.seekTo(it) }
        p.play()
        player = p
        _state.value = _state.value.copy(id = id, playing = true, failed = _state.value.failed - id, cid = cid, author = author)
        ticker?.cancel()
        ticker = main.launch { while (isActive) { tick(); delay(50) } }
    }

    private fun save() {
        val id = _state.value.id ?: return
        val pos = player?.currentPosition ?: return
        _state.value = _state.value.copy(paused = _state.value.paused + (id to pos))
    }

    private fun tick() {
        val p = player ?: return
        _state.value = _state.value.copy(
            playing = p.isPlaying,
            positionMs = p.currentPosition.coerceAtLeast(0),
            durationMs = p.duration.takeIf { it > 0 } ?: _state.value.durationMs,
        )
    }

    /** CAF Opus becomes an Ogg copy next to it; AAC m4a plays as it is. */
    fun playableFile(context: Context, file: File): File? {
        val head = runCatching { file.inputStream().use { s -> ByteArray(8).also { s.read(it) } } }.getOrNull() ?: return null
        if (!CafOpus.isCaf(head)) return file
        val ogg = File(context.cacheDir, "voice-play-" + file.name + ".ogg")
        if (ogg.exists() && ogg.length() > 0) return ogg
        // A malformed or crafted .caf (oversized chunk, a packet too big for one Ogg page) is
        // "can't play", never a crash (review 2026-11-05).
        val out = try { CafOpus.toOgg(file.readBytes()) } catch (_: Exception) { null } ?: return null
        ogg.writeBytes(out)
        return ogg
    }
}
