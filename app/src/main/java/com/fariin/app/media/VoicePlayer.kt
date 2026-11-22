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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
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
        /**
         * Paused by the person on its bubble, not by a call or unplugged headphones (iOS pausedByUser):
         * they were done with it, so leaving the chat shows no bar for it.
         */
        val pausedByUser: Boolean = false,
        /** A song or a playlist's song is open (iOS isMusic), not a voice note; null = a voice note. */
        val music: MusicNow? = null,
        /** The song whose file is coming down for a tap, shown as a spinner on its button. */
        val loading: String? = null,
    )

    /** What the bar and the lock screen say about the open song (iOS noteMusicLabels). */
    data class MusicNow(
        val trackId: String,
        val messageId: String,
        val title: String,
        val artist: String,
        /** The song's picture, else the message's cover, as JPEG bytes; null = none. */
        val art: ByteArray? = null,
        val hasNext: Boolean = false,
        val hasPrevious: Boolean = false,
    ) {
        override fun equals(other: Any?) = other is MusicNow && trackId == other.trackId && title == other.title &&
            artist == other.artist && art === other.art && hasNext == other.hasNext && hasPrevious == other.hasPrevious
        override fun hashCode() = trackId.hashCode()
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var player: ExoPlayer? = null
    private var ticker: Job? = null
    private var cid: String = ""
    private val main = CoroutineScope(Dispatchers.Main)

    fun rate(cid: String) = _state.value.rates[cid] ?: 1f

    /** A call (one to one or group) owns the audio: no voice note plays over it (iOS VoiceAudio.callActive). */
    private fun callActive(): Boolean =
        com.fariin.app.call.CallManager.ui.value.status.live || com.fariin.app.call.GroupCall.ui.value.joined

    /** Set only when a call paused the note, so the call ending carries it on (iOS resumeAfterInterruption). */
    private var resumeAfterCall = false
    private var callWatch: Job? = null

    /** A call is starting or ringing: the note pauses and keeps its place (iOS interruption began). */
    fun pauseForCall() {
        val p = player ?: return
        if (p.isPlaying) { p.pause(); save(); tick(); resumeAfterCall = true }
    }

    /**
     * Any call, one to one or group, ringing or joining, pauses the note (group calls never did), and
     * when the last one is over a note the call paused plays on from its place (iOS interruption
     * ended). Anything the person does with the note in between cancels that.
     */
    private fun watchCalls() {
        if (callWatch?.isActive == true) return
        callWatch = main.launch {
            kotlinx.coroutines.flow.combine(com.fariin.app.call.CallManager.ui, com.fariin.app.call.GroupCall.ui) { a, g ->
                a.status.live || g.joined || g.connecting
            }.distinctUntilChanged().collectLatest { up ->
                if (up) pauseForCall()
                else if (resumeAfterCall) {
                    delay(600)   // the call's audio lets go first
                    resumeAfterCall = false
                    val p = player ?: return@collectLatest
                    if (!p.isPlaying && !callActive()) p.play()
                }
            }
        }
    }

    fun cycleRate(cid: String) {
        if (_state.value.music != null) return   // a song plays at its own speed (iOS: speed is for voice notes)
        val next = MediaRules.nextRate(rate(cid))
        _state.value = _state.value.copy(rates = _state.value.rates + (cid to next))
        if (this.cid == cid) player?.playbackParameters = PlaybackParameters(next)
    }

    /**
     * Tap on the disc: play, pause, or resume [id]. [file] holds the plain bytes. [messageId] and
     * [createdAtMs] are for the played mark, written once the sound really starts.
     */
    fun toggle(context: Context, id: String, cid: String, file: File, author: String = "",
               messageId: String? = null, createdAtMs: Double = 0.0) {
        val s = _state.value
        val p = player
        resumeAfterCall = false   // the person chose: a call ending must not override it
        if (s.id == id && p != null) {
            if (p.isPlaying) { p.pause(); _state.value = _state.value.copy(pausedByUser = true) }
            else if (callActive()) return
            else { if (p.playbackState == Player.STATE_ENDED) p.seekTo(0); p.play(); _state.value = _state.value.copy(pausedByUser = false) }
            return
        }
        if (callActive()) return
        main.launch {
            val playable = withContext(Dispatchers.IO) { playableFile(context, file) }
            if (playable == null) {
                _state.value = _state.value.copy(failed = _state.value.failed + id)
                return@launch
            }
            start(context, id, cid, playable, author, messageId, createdAtMs)
        }
    }

    // ------------------------------------------------------------------ songs

    /** The playlist (or one song) the open song belongs to, and where in it. */
    private var musicQueue: List<com.fariin.app.data.MusicRules.Ref> = emptyList()
    private var musicIndex = 0
    private var musicCid = ""
    private var musicCover: ByteArray? = null
    private var musicFetch: (suspend (com.fariin.app.data.MusicRules.Ref) -> File?)? = null
    private var appContext: Context? = null

    /**
     * Tap on a song's button or a playlist's track (iOS toggleMusic): play, pause or resume
     * [queue]'s song at [index]. The songs after it play on by themselves, and next and previous
     * work from the bar and the lock screen. [cover] is the message's cover (its preview tile);
     * [fetch] brings a song's plain file (ChatMedia, the document path).
     */
    fun toggleMusic(context: Context, cid: String, queue: List<com.fariin.app.data.MusicRules.Ref>, index: Int,
                    cover: ByteArray?, fetch: suspend (com.fariin.app.data.MusicRules.Ref) -> File?) {
        val t = queue.getOrNull(index) ?: return
        val p = player
        resumeAfterCall = false
        if (_state.value.id == t.id && p != null) {
            if (p.isPlaying) { p.pause(); _state.value = _state.value.copy(pausedByUser = true) }
            else if (callActive()) return
            else { if (p.playbackState == Player.STATE_ENDED) p.seekTo(0); p.play(); _state.value = _state.value.copy(pausedByUser = false) }
            return
        }
        if (callActive()) return
        appContext = context.applicationContext
        musicQueue = queue; musicIndex = index; musicCid = cid; musicCover = cover; musicFetch = fetch
        main.launch { loadMusic(index) }
    }

    /** The playlist's next song, from the bar, the lock screen or a headset. */
    fun nextTrack() {
        if (_state.value.music?.hasNext != true) return
        resumeAfterCall = false
        main.launch { loadMusic(musicIndex + 1) }
    }

    /** Back to the start of the song, or the song before it when it has only just started. */
    fun previousTrack() {
        val p = player
        resumeAfterCall = false
        if (p != null && (p.currentPosition > 3000 || _state.value.music?.hasPrevious != true)) { p.seekTo(0); tick(); return }
        if (_state.value.music?.hasPrevious != true) return
        main.launch { loadMusic(musicIndex - 1) }
    }

    /** Brings song [index] of the queue and plays it; false when it cannot be had. */
    private suspend fun loadMusic(index: Int): Boolean {
        val ctx = appContext ?: return false
        val queue = musicQueue
        val t = queue.getOrNull(index) ?: return false
        if (callActive()) return false
        musicIndex = index
        _state.value = _state.value.copy(loading = t.id, failed = _state.value.failed - t.id)
        val raw = runCatching { musicFetch?.invoke(t) }.getOrNull()
        val f = raw?.let { withContext(Dispatchers.IO) { playableFile(ctx, it) } }
        val tags = f?.let { withContext(Dispatchers.IO) { MusicTags.ensure(t.id, it) } }
        if (_state.value.loading == t.id) _state.value = _state.value.copy(loading = null)
        if (musicQueue !== queue || musicIndex != index) return false   // another song was picked meanwhile
        if (f == null) { _state.value = _state.value.copy(failed = _state.value.failed + t.id); return false }
        val now = MusicNow(
            trackId = t.id, messageId = t.messageId,
            // The sender's own words first, then the tags, then the file name (iOS noteMusicLabels).
            title = t.title ?: tags?.title ?: t.plainTitle,
            artist = t.artist ?: tags?.artist ?: "",
            art = tags?.art ?: musicCover,
            hasNext = index < queue.size - 1, hasPrevious = index > 0,
        )
        start(ctx, t.id, musicCid, f, "", null, 0.0, now)
        return true
    }

    fun seek(id: String, fraction: Float) {
        val p = player ?: return
        if (_state.value.id != id || p.duration <= 0) return
        p.seekTo((p.duration * fraction.coerceIn(0f, 1f)).toLong())
        tick()
    }

    /**
     * A tap or drag on the waveform of a note that is not loaded (iOS seek before the first play): the
     * spot is kept, and play starts from it.
     */
    fun stash(id: String, positionMs: Long) {
        _state.value = _state.value.copy(paused = _state.value.paused + (id to positionMs.coerceAtLeast(0)))
    }

    fun stop() {
        resumeAfterCall = false
        save()
        VoiceEar.stop()
        VoiceSession.close()   // the lock screen lets go before its player does
        player?.release(); player = null
        ticker?.cancel()
        musicQueue = emptyList(); musicFetch = null; musicCover = null
        _state.value = _state.value.copy(id = null, playing = false, positionMs = 0, cid = null, pausedByUser = false, music = null)
    }

    /**
     * Its chat was opened (iOS chatOpened): a paused note there is closed, so the bar does not come
     * back for it on the way out. A note still playing carries on.
     */
    fun chatOpened(cid: String) {
        val s = _state.value
        if (s.id != null && s.cid == cid && !s.playing) stop()
    }

    /** The bar's control: pause the open note, or carry on from where it stopped. */
    fun playPause() {
        val p = player ?: return
        // A pause on the bar keeps the bar: it holds the only play button (iOS pause(byUser:) outside the chat).
        _state.value = _state.value.copy(pausedByUser = false)
        resumeAfterCall = false
        if (p.isPlaying) p.pause() else if (callActive()) return else { if (p.playbackState == Player.STATE_ENDED) p.seekTo(0); p.play() }
    }

    /**
     * Play or pause from the lock screen, the shade or a headset (iOS remote commands: resume() and
     * pause(), which is by the user). A call still blocks play, and the person's choice wins over a
     * call ending, as on the bubble.
     */
    internal fun remotePlay() {
        val p = player ?: return
        resumeAfterCall = false
        if (p.isPlaying || callActive()) return
        if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
        p.play()
        _state.value = _state.value.copy(pausedByUser = false)
    }

    internal fun remotePause() {
        val p = player ?: return
        resumeAfterCall = false
        if (!p.isPlaying) return
        p.pause()
        _state.value = _state.value.copy(pausedByUser = true)
    }

    /** The run is over and nothing else started: the lock screen controls go now, not after the wait. */
    private fun runOver() { if (_state.value.id == null) VoiceSession.close() }

    /** The run's next voice note (VoiceRun), fetched and played as a tap on it would; heard, so marked played. */
    private suspend fun playNext(context: Context, cid: String, after: String) {
        // The run is over (or the next note cannot be had): raise-to-ear lets go of the audio.
        val n = VoiceRun.nextAfter(cid, after) ?: run { if (_state.value.id == null) VoiceEar.stop(); runOver(); return }
        val url = n.localPath ?: n.audioUrl ?: run { VoiceEar.stop(); runOver(); return }
        val f = VoiceRun.media?.file(url, n.enc) ?: run { if (_state.value.id == null) VoiceEar.stop(); runOver(); return }
        if (_state.value.id != null) return   // something else started meanwhile
        toggle(context, n.rowId, cid, f, n.authorId, n.id, n.createdAtMillis)
    }

    private fun start(context: Context, id: String, cid: String, file: File, author: String,
                      messageId: String?, createdAtMs: Double, music: MusicNow? = null) {
        save()
        if (music == null) { musicQueue = emptyList(); musicFetch = null; musicCover = null }   // a voice note ends the playlist
        // The old note's player goes once the lock screen session has moved to the new one.
        val old = player
        player = null
        this.cid = cid
        val p = ExoPlayer.Builder(context.applicationContext).build()
        p.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
            .setContentType(if (music != null) C.AUDIO_CONTENT_TYPE_MUSIC else C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
        // iOS: headphones out must not put a private note on the speaker; it pauses instead.
        p.setHandleAudioBecomingNoisy(true)
        p.setMediaItem(MediaItem.Builder().setUri(Uri.fromFile(file))
            .setMediaMetadata(if (music != null) VoiceSession.musicMetadata(context, music)
                else VoiceSession.metadata(context, cid, author)).build())
        p.playbackParameters = PlaybackParameters(if (music != null) 1f else rate(cid))
        p.addListener(object : Player.Listener {
            var heard = false
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // Heard once sound really comes out (iOS: only after play() succeeded), and only
                // someone else's note (iOS: !isMine). A note that never plays tells nobody.
                if (isPlaying && !heard) {
                    heard = true
                    val me = com.fariin.app.Fb.auth.currentUser?.uid
                    if (createdAtMs > 0 && author.isNotEmpty() && author != me) {
                        com.fariin.app.data.PlayedVoice.markPlayed(context, cid, createdAtMs, messageId)
                    }
                }
                tick()
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    // Finished: the note is closed, as iOS (VoicePlayer.barVisible is "started, not
                    // yet finished or closed"), so the bar away from the chat goes; the bubble reads
                    // its length again. Before, the finished note stayed open and its bar never left.
                    _state.value = _state.value.copy(id = null, cid = null, playing = false, positionMs = 0, music = null,
                        paused = _state.value.paused - id)
                    ticker?.cancel()
                    // Released just after this callback returns, not from inside it.
                    // The lock screen session keeps it a moment, for the run's next note (VoiceSession.retire).
                    if (player === p) { player = null; main.launch { VoiceSession.retire(p) } }
                    // A playlist plays on to its next song (iOS musicNext); else iOS followOn: the
                    // next note sent straight after this one plays by itself.
                    if (music != null) main.launch {
                        if (!(music.hasNext && _state.value.id == null && loadMusic(musicIndex + 1))) runOver()
                    } else main.launch { playNext(context.applicationContext, cid, id) }
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
        // The song is known before the lock screen reads what next and previous may do.
        _state.value = _state.value.copy(music = music)
        VoiceSession.attach(context, p)
        old?.release()
        if (music == null) VoiceEar.watch(context) { player }   // raise to ear, as iOS (voice notes only)
        watchCalls()
        _state.value = _state.value.copy(id = id, playing = true, failed = _state.value.failed - id, cid = cid, author = author,
            pausedByUser = false, music = music)
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
