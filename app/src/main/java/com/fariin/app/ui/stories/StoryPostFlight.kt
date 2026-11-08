package com.fariin.app.ui.stories

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind

/**
 * After Post Story (iOS StoryPostFlight in StoryCameraDoor.swift): the posted picture leaves the
 * editor's card and flies into My Story's card in the Stories list, which shows the same picture
 * with its ring turning while the upload runs. 0.4 s on the iOS curve (0.38, 0.70, 0.125, 1.0): the
 * centre travels, the box takes the slot's shape and the corners turn into the slot's. The slot is
 * hidden until the copy lands, so there is one picture on screen, never two, and no pop.
 * No slot on screen (another tab, scrolled away): it drops off the bottom, shrinking to 0.8 and
 * fading, as iOS does.
 */
object StoryPostFlight {
    /** The uploading My Story card's key in [StoryOrigins]. */
    const val UPLOAD_KEY = "story-upload"
    const val FLIGHT_MS = 400
    val EASING = CubicBezierEasing(0.38f, 0.70f, 0.125f, 1.0f)
}

/** One flight from [from] (this window's px) with [fromRadius] corners; [onDone] once it has landed. */
@Composable
fun PostFlightLayer(image: ImageBitmap, from: Rect, fromRadius: Float, onDone: () -> Unit) {
    val view = LocalView.current
    val done by rememberUpdatedState(onDone)
    var t by remember { mutableFloatStateOf(0f) }
    var dest by remember { mutableStateOf<Rect?>(null) }
    var destRadius by remember { mutableFloatStateOf(0f) }
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // The uploading card appears in the list in the frame after Post: give it a few to be laid out.
        val loc = IntArray(2)
        var found: Rect? = null
        repeat(4) {
            if (found == null) {
                withFrameMillis { }
                view.getLocationOnScreen(loc)
                val r = StoryOrigins.rects[StoryPostFlight.UPLOAD_KEY]?.translate(-loc[0].toFloat(), -loc[1].toFloat())
                val screen = Rect(0f, 0f, view.width.toFloat(), view.height.toFloat())
                if (r != null && r.width > 1f && r.overlaps(screen)) found = r
            }
        }
        dest = found
        if (found != null) {
            destRadius = StoryOrigins.radii[StoryPostFlight.UPLOAD_KEY] ?: 0f
            StoryOrigins.hiddenKey = StoryPostFlight.UPLOAD_KEY
        }
        started = true
        if (com.fariin.app.ui.components.Motion.on) {
            Animatable(0f).animateTo(1f, tween(StoryPostFlight.FLIGHT_MS,
                easing = if (found != null) StoryPostFlight.EASING else FastOutSlowInEasing)) { t = value }
        }
        if (StoryOrigins.hiddenKey == StoryPostFlight.UPLOAD_KEY) StoryOrigins.hiddenKey = null
        // The slot draws its picture in this frame; the copy goes the next.
        withFrameMillis { }
        done()
    }
    if (!started) {
        // Holding still on the card while the slot is found.
        FlightBox(image, from, fromRadius, 1f)
        return
    }
    // The slot as it stands this frame: the app grows back under the flight (StoryAppShrink), so a
    // fresh place for the card wins over the one found at the start.
    val d = dest?.let { found ->
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        StoryOrigins.rects[StoryPostFlight.UPLOAD_KEY]?.translate(-loc[0].toFloat(), -loc[1].toFloat())
            ?.takeIf { it.width > 1f } ?: found
    }
    if (d != null) {
        val r = Rect(
            Offset(lerp(from.left, d.left, t), lerp(from.top, d.top, t)),
            Size(lerp(from.width, d.width, t), lerp(from.height, d.height, t)),
        )
        FlightBox(image, r, lerp(fromRadius, destRadius, t), 1f)
    } else {
        val k = 1f - 0.2f * t
        val r = Rect(Offset(from.center.x - from.width * k / 2f, from.center.y - from.height * k / 2f + view.height * t),
            Size(from.width * k, from.height * k))
        FlightBox(image, r, fromRadius * k, 1f - t)
    }
}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

/**
 * THE APP SHRINKS BACK FROM THE CAMERA ON POST (iOS StoryCameraDoor.closeAfterPost, the root
 * transform): at the tap the app behind the camera is held as a card, (width - 32) / width wide, its
 * top at the status bar, 38 corners, black around it, and it grows back to full screen in 0.3 s
 * ease-in-out while the sheet drops and the picture flies. The real app is moved, not a picture of it.
 * [play] starts it; [storyAppShrink] on the app's root draws it.
 */
object StoryAppShrink {
    internal val tick = androidx.compose.runtime.mutableIntStateOf(0)
    fun play() { tick.intValue = tick.intValue + 1 }
    /** For the app's root, without an import there. */
    fun modifier(): Modifier = Modifier.storyAppShrink()
    const val MS = 300
    val EASE_IN_OUT = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
}

/** On the app's root: black behind, the content scaled and rounded while [StoryAppShrink] runs. */
fun Modifier.storyAppShrink(): Modifier = composed {
    val tick = StoryAppShrink.tick.intValue
    val p = remember { Animatable(0f) }
    LaunchedEffect(tick) {
        if (tick == 0 || !com.fariin.app.ui.components.Motion.on) return@LaunchedEffect
        p.snapTo(1f)
        p.animateTo(0f, tween(StoryAppShrink.MS, easing = StoryAppShrink.EASE_IN_OUT))
    }
    val density = LocalDensity.current
    val top = WindowInsets.statusBars.getTop(density).toFloat()
    val gutter = with(density) { 32.dp.toPx() }
    val corner = with(density) { 38.dp.toPx() }
    this.drawBehind { if (p.value > 0f) drawRect(androidx.compose.ui.graphics.Color.Black) }
        .graphicsLayer {
            val t = p.value
            if (t <= 0f || size.width <= gutter) return@graphicsLayer
            val w = size.width
            val h = size.height
            val held = (w - gutter) / w
            val s = 1f + (held - 1f) * t
            scaleX = s
            scaleY = s
            // iOS: translate(0, statusBar - h(1 - s)/2), so the held card's top sits at the status bar.
            translationY = (top - h * (1f - held) / 2f) * t
            clip = true
            // Drawn at 38 when held, down to square at full size; the layer's own size is unscaled.
            shape = RoundedCornerShape(corner * t / s)
        }
}

@Composable
private fun FlightBox(image: ImageBitmap, r: Rect, radiusPx: Float, alpha: Float) {
    val density = LocalDensity.current
    with(density) {
        Box(Modifier.offset { IntOffset(r.left.toInt(), r.top.toInt()) }.size(r.width.toDp(), r.height.toDp())
            .graphicsLayer { this.alpha = alpha }
            .clip(RoundedCornerShape(radiusPx.toDp()))) {
            Image(image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

/**
 * The turning ring round my face on the uploading My Story card (iOS StoryListRow faceRing while
 * isUploading): 72% of a circle in the unseen gradient, a turn every 0.9 s (UploadCancelRing.turn).
 */
@Composable
fun UploadSpinRing(modifier: Modifier, brush: Brush, width: Dp) {
    var turn by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withInfiniteAnimationFrameMillis { it }
        while (true) withInfiniteAnimationFrameMillis { ms -> turn = ((ms - start) % 900L) / 900f * 360f }
    }
    Canvas(modifier) {
        val w = width.toPx()
        drawArc(brush, startAngle = turn - 90f, sweepAngle = 0.72f * 360f, useCenter = false,
            topLeft = Offset(w / 2f, w / 2f), size = Size(size.width - w, size.height - w),
            style = Stroke(w, cap = StrokeCap.Round))
    }
}
