package com.fariin.app.ui.people

import android.net.Uri
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.fariin.app.data.AvatarVideo
import com.fariin.app.ui.components.cachedVector
import com.fariin.app.ui.thread.TrimState
import com.fariin.app.ui.thread.VideoTrimStrip

/**
 * Fariin Plus animatedAvatar: CHOOSE THE PART OF THE VIDEO THAT BECOMES THE PICTURE (iOS
 * AvatarVideoEditor, owner 2026-11-20: "it just sets it with a centre crop, I want to choose the
 * area"). The picked video inside a circle, dimmed outside; drag to move it, pinch to zoom, and the
 * picture always covers the circle (zoom 1 = its short side just fills it). The strip picks the time,
 * at most 10 seconds (the other handle follows), and the preview loops that part, muted.
 *
 * Done hands back the window (ms) and the circle's square as fractions of the picture as shown
 * (rotation applied, y down); AvatarVideoMaker cuts exactly that. The still is the clip's first frame.
 */
@Composable
internal fun AvatarVideoEditor(uri: Uri, onDone: (startMs: Long, endMs: Long, crop: android.graphics.RectF) -> Unit, onCancel: () -> Unit) {
    com.fariin.app.ui.components.AppOrientation.KeepUpright()
    val ctx = LocalContext.current
    val density = LocalDensity.current
    BackHandler(onBack = onCancel)
    val s = remember(uri) { TrimState(uri) }
    LaunchedEffect(uri) {
        s.load(ctx, 8)
        if (s.durationMs > 0) { s.start = 0L; s.end = minOf(s.durationMs, AvatarVideo.MAX_MS) }
    }
    // The window is never longer than the limit: moving one handle past it brings the other along.
    var lastStart by remember { mutableStateOf(0L) }
    LaunchedEffect(s) {
        snapshotFlow { s.start to s.end }.collect { (a, b) ->
            if (b - a > AvatarVideo.MAX_MS) {
                if (a != lastStart) s.end = minOf(s.durationMs, a + AvatarVideo.MAX_MS) else s.start = maxOf(0L, b - AvatarVideo.MAX_MS)
            }
            lastStart = s.start
        }
    }
    val player = remember(uri) {
        ExoPlayer.Builder(ctx).build().apply { volume = 0f; setMediaItem(MediaItem.fromUri(uri)); prepare(); playWhenReady = true }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    // Loops the chosen part; a dragged handle shows its frame instead.
    LaunchedEffect(player) {
        while (true) {
            kotlinx.coroutines.delay(60)
            val scrub = s.scrub
            if (scrub != null) { if (player.isPlaying) player.pause(); player.seekTo(scrub); continue }
            if (!player.isPlaying) player.play()
            val p = player.currentPosition
            s.playhead = p
            if (p >= s.end || p < s.start - 300) player.seekTo(s.start)
        }
    }
    // Framing: zoom over the size that just fills the circle; [cx],[cy] = the picture's point (0..1)
    // under the circle's middle.
    var zoom by remember { mutableFloatStateOf(1f) }
    var cx by remember { mutableFloatStateOf(0.5f) }
    var cy by remember { mutableFloatStateOf(0.5f) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                com.fariin.app.ui.components.FloatingIcon(androidx.compose.ui.graphics.vector.ImageVector.cachedVector(com.fariin.app.R.drawable.ic_sf_xmark), "Cancel",
                    onClick = onCancel, circle = true)
                Spacer(Modifier.weight(1f))
                val ready = s.durationMs > 0 && s.ratio > 0f && s.end - s.start >= 500
                Box(Modifier.clip(CircleShape).background(if (ready) Color(0xFF0590F7) else Color(0xFF0590F7).copy(alpha = 0.4f))
                    .then(if (ready) Modifier.clickable { onDone(s.start, s.end, cropRect(s.ratio, zoom, cx, cy)) } else Modifier)
                    .padding(horizontal = 18.dp, vertical = 9.dp)) {
                    Text("Done", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val w = constraints.maxWidth.toFloat(); val h = constraints.maxHeight.toFloat()
                val d = minOf(w, h) * 0.86f
                val ratio = s.ratio
                if (ratio > 0f) {
                    // The picture at zoom 1: its short side = the circle.
                    val baseW = if (ratio >= 1f) d * ratio else d
                    val baseH = if (ratio >= 1f) d else d / ratio
                    Box(Modifier.fillMaxSize().pointerInput(ratio) {
                        detectTransformGestures { _, pan, gz, _ ->
                            zoom = (zoom * gz).coerceIn(1f, 4f)
                            val pw = baseW * zoom; val ph = baseH * zoom
                            cx = clampCenter(cx - pan.x / pw, d / 2f / pw)
                            cy = clampCenter(cy - pan.y / ph, d / 2f / ph)
                        }
                    }) {
                        val pw = baseW * zoom; val ph = baseH * zoom
                        // The picture placed so its point (cx, cy) sits on the circle's middle.
                        val left = w / 2f - cx * pw; val top = h / 2f - cy * ph
                        AndroidView(factory = { k -> TextureView(k).also { player.setVideoTextureView(it) } },
                            // Pinned at the box's corner even when wider than the box (an oversized child
                            // is otherwise centred, and the picture drew off from the circle's math).
                            modifier = Modifier.align(Alignment.TopStart)
                                .wrapContentSize(Alignment.TopStart, unbounded = true)
                                .requiredSize(with(density) { baseW.toDp() }, with(density) { baseH.toDp() })
                                .graphicsLayer {
                                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                                    scaleX = zoom; scaleY = zoom
                                    translationX = left; translationY = top
                                })
                        Canvas(Modifier.fillMaxSize()) {
                            val hole = Rect(Offset(size.width / 2f, size.height / 2f), d / 2f)
                            val dim = Path().apply {
                                fillType = PathFillType.EvenOdd
                                addRect(Rect(Offset.Zero, Size(size.width, size.height)))
                                addOval(hole)
                            }
                            drawPath(dim, Color.Black.copy(alpha = 0.6f))
                        }
                    }
                } else com.fariin.app.ui.components.FariinSpinner(Modifier.align(Alignment.Center), size = 30.dp, stroke = 3.dp, color = Color.White)
            }
            if (s.durationMs > 0) VideoTrimStrip(s, Modifier.padding(horizontal = 16.dp, vertical = 14.dp), minMs = 500)
        }
    }
}

/** Keeps the circle on the picture: the centre stays [half] away from each edge (fractions). */
private fun clampCenter(c: Float, half: Float): Float = if (half >= 0.5f) 0.5f else c.coerceIn(half, 1f - half)

/** The circle's square as fractions of the picture (y down), for the cut. */
private fun cropRect(ratio: Float, zoom: Float, cx: Float, cy: Float): android.graphics.RectF {
    // At zoom 1 the short side spans the circle exactly: the square is that side's full length.
    val hw = (if (ratio >= 1f) 1f / ratio else 1f) / 2f / zoom
    val hh = (if (ratio >= 1f) 1f else ratio) / 2f / zoom
    val x = clampCenter(cx, hw); val y = clampCenter(cy, hh)
    return android.graphics.RectF(x - hw, y - hh, x + hw, y + hh)
}
