package com.fariin.app.ui.thread

import android.net.Uri
import android.view.TextureView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import com.fariin.app.data.ViewerRules
import com.fariin.app.media.VoicePlayer
import com.fariin.app.ui.components.floating
import com.fariin.app.ui.theme.Fariin
import kotlinx.coroutines.delay
import androidx.compose.ui.res.vectorResource
import com.fariin.app.R

/**
 * The playing clip, shared by the page (the picture) and the chrome (scrubber, play), after iOS
 * VideoPlayerScreen: a plain player under the viewer's own controls, not the platform's.
 */
@Stable
internal class VideoCtl {
    var player by mutableStateOf<ExoPlayer?>(null)
    var playing by mutableStateOf(false)
    var positionMs by mutableLongStateOf(0L)
    var durationMs by mutableLongStateOf(0L)
    var firstFrame by mutableStateOf(false)
    var ratio by mutableFloatStateOf(0f)
    /** While the finger is on the track: the clip is paused and the position is the finger's. */
    var scrubbing by mutableStateOf(false)
    private var resumeAfterScrub = false

    /** iOS togglePlay: at the end it starts again from the top. */
    fun togglePlay() {
        val p = player ?: return
        if (p.isPlaying) p.pause()
        else { if (p.playbackState == Player.STATE_ENDED) p.seekTo(0); p.play() }
    }

    fun skip(seconds: Int) {
        val p = player ?: return
        if (durationMs <= 0) return
        val t = (p.currentPosition + seconds * 1000L).coerceIn(0L, durationMs)
        positionMs = t
        p.seekTo(t)
    }

    fun scrubTo(fraction: Float) {
        val p = player ?: return
        if (!scrubbing) { scrubbing = true; resumeAfterScrub = p.isPlaying; p.pause() }
        val t = (fraction.coerceIn(0f, 1f) * durationMs).toLong()
        positionMs = t
        p.seekTo(t)
    }

    fun endScrub() {
        if (!scrubbing) return
        scrubbing = false
        if (resumeAfterScrub) player?.play()
    }
}

/**
 * One video page: the poster from the bubble until the first frame is drawn (iOS keeps its still
 * on screen so the open is never a black wait), then the clip. A TextureView, not a SurfaceView,
 * so the picture moves and shrinks with the drag-close.
 */
@Composable
internal fun VideoPage(p: ViewerRules.Page, current: Boolean, ctl: VideoCtl, chromeHidden: Boolean, onTap: () -> Unit) {
    val ctx = LocalContext.current
    val media = chatMedia()
    val saveCid = LocalThreadCid.current
    // A plain group or channel clip plays while it comes down (VideoStream); a sealed one, one
    // already on the phone, or one whose stream stalled, is fetched whole first as before.
    var fellBack by remember(p.url) { mutableStateOf(false) }
    val streaming = !fellBack && remember(p.url, saveCid) {
        com.fariin.app.media.VideoStream.streamable(saveCid, p.url, p.enc) && media.cached(p.url) == null
    }
    val file = rememberPlainFile(if (streaming) null else p.url, p.enc)
    // Settings > Chats > Save to Photos: a received video goes to the gallery once it has come down
    // here (Android fetches a video when it is played). Never mine, never view-once. A streamed clip
    // is saved once all of it is in the stream cache (copied out, not fetched again).
    val mine = p.authorId == null || p.authorId == com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
    val autoSave = p.once == null && !mine && saveCid.isNotEmpty() && p.messageId != null
    LaunchedEffect(file) {
        val f = file ?: return@LaunchedEffect
        if (autoSave) com.fariin.app.media.AutoSaveMedia.save(ctx, "$saveCid/${p.messageId}/v/${p.key}", f, isVideo = true)
    }
    if (streaming && autoSave && current) LaunchedEffect(p.url) {
        if (!com.fariin.app.media.AutoSaveMedia.savesMedia(ctx, saveCid)) return@LaunchedEffect
        while (!kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.fariin.app.media.VideoStream.fullyCached(ctx, p.url) }) delay(1_000)
        media.file(p.url, p.enc, video = true)?.let {
            com.fariin.app.media.AutoSaveMedia.save(ctx, "$saveCid/${p.messageId}/v/${p.key}", it, isVideo = true)
        }
    }
    val poster = rememberBitmap(rememberPlainFile(p.posterUrl, p.posterEnc), 720)
    val tap by rememberUpdatedState(onTap)
    // From the page showing (the tap, near enough) to the clip's first frame, for VSTREAM# lines.
    val shownAt = remember(p.url) { android.os.SystemClock.elapsedRealtime() }
    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { tap() }) }, contentAlignment = Alignment.Center) {
        val player = if ((file != null || streaming) && current) remember(file, streaming) {
            VoicePlayer.stop()   // one sound at a time
            if (streaming) {
                // The player takes over from a preload still running (one writer per clip).
                com.fariin.app.media.VideoPreload.cancel(p.url)
                com.fariin.app.media.VideoStream.log("open stream preloaded=${com.fariin.app.media.VideoStream.cachedPrefix(ctx, p.url)}")
                ExoPlayer.Builder(ctx).setMediaSourceFactory(com.fariin.app.media.VideoStream.mediaSourceFactory(ctx)).build().apply {
                    setMediaItem(MediaItem.fromUri(p.url))
                    prepare()
                    playWhenReady = true
                }
            } else ExoPlayer.Builder(ctx).build().apply {
                setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                prepare()
                playWhenReady = true
            }
        } else null
        if (player != null) {
            var drawn by remember(player) { mutableStateOf(false) }
            val fallBack: (String) -> Unit = { why ->
                if (streaming && !fellBack) {
                    com.fariin.app.media.VideoStream.log("fallback reason=$why after=${android.os.SystemClock.elapsedRealtime() - shownAt}ms")
                    com.fariin.app.media.VideoStream.markFailed(p.url)
                    fellBack = true
                }
            }
            DisposableEffect(player) {
                val listener = object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) { ctl.playing = isPlaying }
                    override fun onRenderedFirstFrame() {
                        if (!drawn) com.fariin.app.media.VideoStream.log(
                            "first frame mode=${if (streaming) "stream" else "file"} ms=${android.os.SystemClock.elapsedRealtime() - shownAt}")
                        drawn = true
                        ctl.firstFrame = true
                    }
                    override fun onVideoSizeChanged(size: VideoSize) {
                        if (size.width > 0 && size.height > 0) ctl.ratio = size.width * size.pixelWidthHeightRatio / size.height
                    }
                    override fun onPlaybackStateChanged(state: Int) {
                        if (player.duration > 0) ctl.durationMs = player.duration
                    }
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        // A stream that breaks before or while playing: the whole file instead.
                        fallBack("error ${error.errorCodeName}")
                    }
                }
                player.addListener(listener)
                ctl.player = player
                onDispose {
                    player.removeListener(listener)
                    if (ctl.player === player) { ctl.player = null; ctl.playing = false; ctl.firstFrame = false }
                    player.release()
                }
            }
            // Watchdog: no first frame in time and the stream gives way to the whole download.
            if (streaming) LaunchedEffect(player) {
                delay(com.fariin.app.media.VideoStream.WATCHDOG_MS)
                if (!drawn) fallBack("no first frame")
            }
            // The track moves smoothly: the position is read every frame or so while the clip plays.
            LaunchedEffect(player) {
                while (true) {
                    if (!ctl.scrubbing) ctl.positionMs = player.currentPosition.coerceAtLeast(0L)
                    if (player.duration > 0) ctl.durationMs = player.duration
                    delay(33)
                }
            }
            val r = ctl.ratio
            AndroidView(
                factory = { TextureView(it).also { tv -> player.setVideoTextureView(tv) } },
                modifier = if (r > 0f) Modifier.aspectRatio(r) else Modifier.fillMaxSize(),
            )
        }
        if (!(current && ctl.firstFrame)) {
            if (poster != null) Image(poster, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            // The clip's bytes fill the ring as they come (iOS), not a bare spinner. A stream has no
            // byte count to show: the ring turns until the first frame.
            if (streaming) DownloadBadge("vstream:" + p.url, Fetch.LOADING, isVideo = false, showPill = false, showCross = false)
            else if (file == null) DownloadBadge(p.url, Fetch.LOADING, isVideo = true, showPill = false, showCross = false)
        }
    }
}

/**
 * iOS bottomPanel: a 44 capsule with the elapsed time, a knobless track and the time left; 24 under
 * it a row of 44 round buttons, Share and play (with 10 s back and forward beside play, in one
 * capsule, on a clip of 20 s or more). Forward is not here on Android yet; its place is kept.
 */
@Composable
internal fun VideoPanel(ctl: VideoCtl, canShare: Boolean, onShare: () -> Unit) {
    val c = Fariin.colors
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Scrubber(ctl)
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            BarButton(painterResource(com.fariin.app.R.drawable.ic_link_share), "Share", size = 44.dp,
                enabled = canShare && ctl.player != null, onClick = onShare)
            Spacer(Modifier.weight(1f))
            if (ctl.durationMs >= 20_000) {
                Row(Modifier.height(44.dp).floating(CircleShape).padding(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    PanelGlyph(ImageVector.vectorResource(R.drawable.ic_sf_gobackward_10), "Back 10 seconds", 36) { ctl.skip(-10) }
                    PlayGlyph(ctl)
                    PanelGlyph(ImageVector.vectorResource(R.drawable.ic_sf_goforward_10), "Forward 10 seconds", 36) { ctl.skip(10) }
                }
            } else Box(Modifier.floating(CircleShape)) { PlayGlyph(ctl) }
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.size(44.dp))
        }
    }
}

@Composable
private fun PlayGlyph(ctl: VideoCtl) =
    PanelGlyph(if (ctl.playing) ImageVector.vectorResource(R.drawable.ic_sf_pause_fill) else ImageVector.vectorResource(R.drawable.ic_sf_play_fill), if (ctl.playing) "Pause" else "Play", 44) { ctl.togglePlay() }

@Composable
private fun PanelGlyph(icon: ImageVector, label: String, width: Int, onClick: () -> Unit) {
    Box(Modifier.size(width.dp, 44.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = Fariin.colors.label, modifier = Modifier.size(26.dp))
    }
}

/** The scrubber capsule: "00:00", the track (a drag or a tap seeks live), "-00:07". */
@Composable
private fun Scrubber(ctl: VideoCtl) {
    val c = Fariin.colors
    val dur = ctl.durationMs.coerceAtLeast(0L)
    val pos = ctl.positionMs.coerceIn(0L, dur.coerceAtLeast(0L))
    val fraction = if (dur > 0) pos.toFloat() / dur else 0f
    val digits = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = c.label, fontFeatureSettings = "tnum")
    Row(Modifier.fillMaxWidth().height(44.dp).floating(CircleShape).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(ViewerRules.clock(pos / 1000.0), style = digits)
        Spacer(Modifier.width(12.dp))
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()
            .pointerInput(dur) {
                detectTapGestures(onPress = { o ->
                    ctl.scrubTo(o.x / size.width)
                    tryAwaitRelease()
                    ctl.endScrub()
                })
            }
            .pointerInput(dur) {
                detectHorizontalDragGestures(
                    onDragEnd = { ctl.endScrub() }, onDragCancel = { ctl.endScrub() },
                ) { change, _ -> change.consume(); ctl.scrubTo(change.position.x / size.width) }
            },
            contentAlignment = Alignment.CenterStart) {
            Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(c.label.copy(alpha = 0.3f)))
            Box(Modifier.width(maxWidth * fraction).height(3.dp).clip(CircleShape).background(c.label))
        }
        Spacer(Modifier.width(12.dp))
        Text("-" + ViewerRules.clock((dur - pos) / 1000.0), style = digits)
    }
}
