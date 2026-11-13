package com.fariin.app.ui.thread

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.view.TextureView
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.fariin.app.media.MediaRules
import com.fariin.app.media.VideoTrim
import com.fariin.app.media.VoicePlayer
import com.fariin.app.ui.components.floating
import com.fariin.app.ui.theme.FariinTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.fariin.app.R

/**
 * One clip's trim and play state in an editor (iOS VideoApprovalView / MediaApprovalView per item):
 * the kept range, the play head, the frame a drag is showing, play or paused (paused at first), and
 * the filmstrip. Times are in ms.
 */
@Stable
internal class TrimState(val uri: Uri) {
    var durationMs by mutableLongStateOf(0L)
    var start by mutableLongStateOf(0L)
    var end by mutableLongStateOf(0L)
    var playhead by mutableLongStateOf(0L)
    /** Not null while a handle or the play head is dragged: the player shows this frame. */
    var scrub by mutableStateOf<Long?>(null)
    var playing by mutableStateOf(false)
    var thumbs by mutableStateOf<List<ImageBitmap>>(emptyList())
    var poster by mutableStateOf<ImageBitmap?>(null)
    /** Width over height, rotation applied; 0 until read. */
    var ratio by mutableFloatStateOf(0f)
    private var loaded = false

    val trimmed: Boolean get() = durationMs > 0 && (start > 50 || end < durationMs - 50)

    /** Length, shape, first frame and [count] filmstrip frames (iOS loadVideo: 10 single, 8 pager). */
    suspend fun load(ctx: android.content.Context, count: Int) {
        if (loaded) return
        loaded = true
        val r = MediaMetadataRetriever()
        try {
            withContext(Dispatchers.IO) { r.setDataSource(ctx, uri) }
            val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
            if (w > 0 && h > 0) ratio = w.toFloat() / h
            poster = withContext(Dispatchers.IO) { frame(r, 0L, 1080) }?.asImageBitmap()
            if (ms <= 0) return
            durationMs = ms
            if (end == 0L) end = ms
            val out = ArrayList<ImageBitmap>(count)
            for (i in 0 until count) {
                val t = ms * i / (count - 1).coerceAtLeast(1)
                withContext(Dispatchers.IO) { frame(r, t, 160) }?.let { out += it.asImageBitmap() }
            }
            thumbs = out
        } catch (_: Exception) {
        } finally {
            runCatching { r.release() }
        }
    }

    private fun frame(r: MediaMetadataRetriever, ms: Long, side: Int): Bitmap? {
        val f = r.getFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
        val (w, h) = MediaRules.fit(f.width, f.height, side)
        if (w >= f.width) return f
        return Bitmap.createScaledBitmap(f, w, h, true).also { if (it !== f) f.recycle() }
    }

    fun pause() { playing = false }
}

/** 9:16 or taller: the video gets rounded corners of its own (iOS isTall). */
private fun isTall(ratio: Float) = ratio > 0f && ratio <= 9f / 16f + 0.01f

/**
 * The clip in an editor page (iOS TrimPlayerUIView): paused until Play is tapped, a tap plays or
 * pauses, it loops inside the kept range, and a drag on the strip shows the frame under the finger.
 * The player exists only while [active] (the page in front); otherwise the first frame stands in.
 * A TextureView, and its size is set once from the clip's shape, never in an animation.
 */
@Composable
internal fun TrimVideo(s: TrimState, active: Boolean, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var firstFrame by remember(s, active) { mutableStateOf(false) }
    Box(
        modifier.pointerInput(s) { detectTapGestures(onTap = { s.playing = !s.playing }) },
        contentAlignment = Alignment.Center,
    ) {
        val r = s.ratio
        val shape = if (isTall(r)) RoundedCornerShape(22.dp) else RoundedCornerShape(0.dp)
        val fit = if (r > 0f) Modifier.aspectRatio(r).clip(shape) else Modifier.fillMaxSize()
        if (active) {
            val player = remember(s.uri) {
                ExoPlayer.Builder(ctx).build().apply {
                    setMediaItem(MediaItem.fromUri(s.uri))
                    prepare()
                    playWhenReady = false
                }
            }
            DisposableEffect(player) {
                val listener = object : Player.Listener {
                    override fun onRenderedFirstFrame() { firstFrame = true }
                }
                player.addListener(listener)
                if (s.playhead > 0) player.seekTo(s.playhead)
                onDispose { player.removeListener(listener); player.release() }
            }
            // iOS periodic observer: every 0.05 s the play head follows the clip, and at the
            // out-point it goes back to the start.
            LaunchedEffect(player) {
                while (true) {
                    val t = player.currentPosition.coerceAtLeast(0L)
                    if (s.scrub == null) s.playhead = t
                    if (s.playing && s.scrub == null && (t >= s.end - 30 || t < s.start - 100)) player.seekTo(s.start)
                    delay(50)
                }
            }
            // While a finger drags: the nearest key frame, which shows at once, so the picture follows
            // the finger (exact seeks were each cancelled by the next and nothing moved; owner's Pixel
            // 2026-11-09). On release: the exact frame where it stopped.
            val scrubbing = s.scrub != null
            LaunchedEffect(player, scrubbing) {
                if (scrubbing) player.setSeekParameters(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC)
                else { player.setSeekParameters(androidx.media3.exoplayer.SeekParameters.EXACT); player.seekTo(s.playhead) }
            }
            LaunchedEffect(player, s.scrub) { s.scrub?.let { player.seekTo(it) } }
            val play = s.playing && s.scrub == null
            LaunchedEffect(player, play) {
                if (play) {
                    VoicePlayer.stop()   // one sound at a time
                    val t = player.currentPosition
                    if (t >= s.end - 30 || t < s.start - 100) player.seekTo(s.start)
                    player.play()
                } else player.pause()
            }
            AndroidView(factory = { TextureView(it).also { tv -> player.setVideoTextureView(tv) } }, modifier = fit)
        }
        // The still waits under the player until its first frame, so the page never shows black.
        if (!(active && firstFrame)) s.poster?.let { Image(it, null, contentScale = ContentScale.Fit, modifier = fit) }
        if (!s.playing && s.scrub == null) {
            Icon(ImageVector.vectorResource(R.drawable.ic_sf_play_circle_fill), null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(66.dp))
        }
    }
}

/**
 * THE trimmer (iOS VideoTrimStrip), one for the single video editor and the pager: the filmstrip
 * with the parts outside the kept range dimmed, a frame and two round handles (grey until something
 * is cut, then yellow), and a white play head inside the frame. A drag on a handle or the play head
 * pauses the clip and shows that frame; it stays paused after. The kept part is never shorter than
 * [minMs] (or the clip). The strip is kept out of the system back swipe.
 */
@Composable
internal fun VideoTrimStrip(s: TrimState, modifier: Modifier = Modifier, stripHeight: Int = 40, handleW: Int = 12, minMs: Long = 1000) {
    val density = LocalDensity.current
    val frameColor by animateColorAsState(if (s.trimmed) TrimYellow else TrimGrey, tween(200), label = "trimFrame")
    BoxWithConstraints(modifier.fillMaxWidth().height(stripHeight.dp).systemGestureExclusion()) {
        val wPx = constraints.maxWidth.toFloat()
        val hw = with(density) { handleW.dp.toPx() }
        val dur = s.durationMs.coerceAtLeast(1L).toFloat()
        val startX = s.start / dur * wPx
        val endX = s.end / dur * wPx
        val leftHandle = (startX - hw / 2).coerceAtLeast(0f)
        val rightHandle = (endX - hw / 2).coerceAtMost(wPx - hw)
        val gapPx = with(density) { 3.5.dp.toPx() }
        val phX = (s.playhead.coerceIn(s.start, s.end.coerceAtLeast(s.start))) / dur * wPx
        val leftInner = leftHandle + hw + gapPx
        val rightInner = rightHandle - gapPx
        val headX = phX.coerceIn(leftInner, maxOf(leftInner, rightInner))

        Box(
            Modifier.fillMaxSize().pointerInput(wPx, hw, s) {
                val handleZone = 22.dp.toPx()
                val headZone = 16.dp.toPx()
                fun at(x: Float) = (x.coerceIn(0f, wPx) / wPx * s.durationMs).toLong()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (s.durationMs <= 0) return@awaitEachGesture
                    val d = s.durationMs.toFloat()
                    val sx = s.start / d * wPx
                    val ex = s.end / d * wPx
                    val lc = (sx - hw / 2).coerceAtLeast(0f) + hw / 2
                    val rc = (ex - hw / 2).coerceAtMost(wPx - hw) + hw / 2
                    val li = lc + hw / 2 + gapPx
                    val headX = (s.playhead.coerceIn(s.start, s.end.coerceAtLeast(s.start)) / d * wPx)
                        .coerceIn(li, maxOf(li, rc - hw / 2 - gapPx))
                    val x0 = down.position.x
                    // The nearest of the three under the finger wins. The handles used to win any shared
                    // touch, so once the play head sat against a handle it could never be picked again
                    // (owner's Pixel 2026-11-09: "the small line glues to the big one").
                    val dl = abs(x0 - lc).takeIf { it <= handleZone }
                    val dr = abs(x0 - rc).takeIf { it <= handleZone }
                    val dh = abs(x0 - headX).takeIf { it <= headZone }
                    val best = listOfNotNull(dl?.let { 0 to it }, dr?.let { 1 to it }, dh?.let { 2 to it }).minByOrNull { it.second }
                        ?: return@awaitEachGesture
                    val target = best.first
                    fun move(x: Float) {
                        s.playing = false
                        val t = at(x)
                        // The gap can never be wider than the clip, and neither end can leave it.
                        val gap = minOf(minMs, s.durationMs)
                        when (target) {
                            0 -> {
                                s.start = t.coerceAtMost(s.end - gap).coerceAtLeast(0L); s.scrub = s.start
                                if (s.playhead < s.start) s.playhead = s.start
                            }
                            1 -> {
                                s.end = t.coerceAtLeast(s.start + gap).coerceAtMost(s.durationMs); s.scrub = s.end
                                if (s.playhead > s.end) s.playhead = s.end
                            }
                            else -> { s.playhead = t.coerceIn(s.start, s.end); s.scrub = s.playhead }
                        }
                    }
                    down.consume()
                    move(x0)
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        ch.consume()
                        move(ch.position.x)
                    }
                    s.scrub = null   // stays paused, no auto-play
                }
            },
        ) {
            // Filmstrip and dimmed ends under one rounded clip.
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp))) {
                Row(Modifier.fillMaxSize()) {
                    for (t in s.thumbs) Image(t, null, contentScale = ContentScale.Crop, modifier = Modifier.weight(1f).fillMaxHeight())
                }
                Box(Modifier.width(with(density) { startX.toDp() }).fillMaxHeight().background(Color.Black.copy(alpha = 0.55f)))
                Box(Modifier.offset { IntOffset(endX.roundToInt(), 0) }.width(with(density) { (wPx - endX).coerceAtLeast(0f).toDp() })
                    .fillMaxHeight().background(Color.Black.copy(alpha = 0.55f)))
            }
            Box(Modifier.offset { IntOffset(startX.roundToInt(), 0) }.width(with(density) { (endX - startX).coerceAtLeast(0f).toDp() })
                .fillMaxHeight().border(3.dp, frameColor, RoundedCornerShape(8.dp)))
            if (s.end > s.start) {
                Box(Modifier.offset { IntOffset((headX - with(density) { 1.5.dp.toPx() }).roundToInt(), with(density) { (-4).dp.roundToPx() }) }
                    .width(3.dp).height((stripHeight + 8).dp).shadow(1.5.dp, CircleShape).background(Color.White, CircleShape))
            }
            for (x in listOf(leftHandle, rightHandle)) {
                Box(Modifier.offset { IntOffset(x.roundToInt(), 0) }.width(handleW.dp).fillMaxHeight()
                    .background(frameColor, RoundedCornerShape((handleW / 2f).dp)), contentAlignment = Alignment.Center) {
                    Box(Modifier.width(2.5.dp).height(18.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape))
                }
            }
        }
    }
}

private val TrimYellow = Color(0xFFFFCC00)   // iOS .yellow
private val TrimGrey = Color(0xFF8E8E93)     // iOS systemGray

internal fun clock(ms: Long): String { val t = ((ms + 500) / 1000).toInt(); return "%d:%02d".format(t / 60, t % 60) }

/**
 * The editor for one chosen video (iOS VideoApprovalView): the X, the kept length at the top right
 * once something is cut, the clip between the bars, the trimmer, and the caption with Send. The
 * strip hides while the keyboard is up. Send hands the kept range to the send ([VideoTrim]).
 * Not ported: the HD button (video quality on Android is fixed at the iOS default).
 */
@Composable
internal fun VideoEditor(uri: Uri, caption: String, onCaption: (String) -> Unit, onClose: () -> Unit, onSend: (String) -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        EditorWindow()
        FariinTheme(dark = true) { VideoEditorBody(uri, caption, onCaption, onClose, onSend) }
    }
}

@Composable
private fun VideoEditorBody(uri: Uri, caption: String, onCaption: (String) -> Unit, onClose: () -> Unit, onSend: (String) -> Unit) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val s = remember(uri) { TrimState(uri) }
    LaunchedEffect(uri) { s.load(ctx, 10) }
    var sent by remember { mutableStateOf(false) }
    var hd by remember { mutableStateOf(com.fariin.app.media.SentQuality.high(ctx)) }
    var bottomPx by remember { mutableIntStateOf(0) }
    val ime = WindowInsets.ime
    val imeUp = ime.getBottom(density) > 0
    val imeNow = { ime.getBottom(density) > 0 }
    LaunchedEffect(imeUp) { if (imeUp) s.pause() }
    val topDp = with(density) { WindowInsets.statusBars.getTop(this).toDp() } + (44 + 16).dp
    val bottomDp = with(density) { bottomPx.toDp() } + 12.dp

    fun send() {
        // One send per screen (iOS `sent`).
        if (sent) return
        sent = true
        s.pause()
        if (s.trimmed) VideoTrim.choose(uri, s.start, s.end)
        com.fariin.app.media.SentQuality.choose(uri, hd)
        onSend(caption.trim())
    }

    // The video keeps its resting size and place while the caption keyboard is up (owner 2026-11-13,
    // iOS 826c00a62: it jumped down): its box is the height measured with the keyboard down,
    // top-anchored, so the keyboard simply covers its lower part.
    var restH by remember { mutableIntStateOf(0) }
    Box(Modifier.fillMaxSize().background(Color.Black).onSizeChanged { if (!imeNow()) restH = it.height }) {
        val videoBox = if (restH > 0) Modifier.fillMaxWidth().height(with(density) { restH.toDp() }) else Modifier.fillMaxSize()
        TrimVideo(s, active = true, modifier = videoBox.padding(top = topDp, bottom = bottomDp))

        Box(
            Modifier.statusBarsPadding().padding(start = 12.dp, top = 6.dp).size(44.dp).floating()
                .clickable { onClose() },
            contentAlignment = Alignment.Center,
        ) { Icon(ImageVector.vectorResource(R.drawable.ic_sf_xmark), contentDescription = "Close", tint = Color.White) }
        if (s.trimmed) {
            Box(
                Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(end = 12.dp, top = 12.dp).height(32.dp)
                    .floating(RoundedCornerShape(16.dp)).padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) { Text(clock(s.end - s.start), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().imePadding()
                .onSizeChanged { if (!imeNow()) bottomPx = it.height }.navigationBarsPadding()
                .padding(top = 8.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!imeUp) {
                // HD, as on the photo editor: 1080p instead of 540p (iOS VideoTranscoder `hd`).
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(40.dp).floating().clickable { hd = !hd }, contentAlignment = Alignment.Center) {
                        Text("HD", color = if (hd) EditorBlue else Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    QualityHint(hd)
                }
                // The strip's place is kept while the filmstrip loads, so the clip does not refit.
                if (s.thumbs.isNotEmpty() && s.durationMs > 0) VideoTrimStrip(s, Modifier.padding(horizontal = 16.dp))
                else Spacer(Modifier.height(40.dp))
            }
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.Bottom) {
                Box(
                    Modifier.weight(1f).heightIn(min = 40.dp).floating(RoundedCornerShape(20.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (caption.isEmpty()) Text("Add a caption…", color = Color(0xFF8E8E93), fontSize = 17.sp)
                    BasicTextField(caption, { onCaption(it.take(1000)) }, textStyle = TextStyle(color = Color.White, fontSize = 17.sp),
                        cursorBrush = SolidColor(Color.White), maxLines = if (imeUp) 7 else 1, modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.width(10.dp))
                Box(Modifier.size(40.dp).clip(CircleShape).background(EditorBlue).clickable { send() }, contentAlignment = Alignment.Center) {
                    Icon(ImageVector.vectorResource(R.drawable.ic_sf_arrow_up), contentDescription = "Send", tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}
