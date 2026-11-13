package com.fariin.app.ui.photo

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.R
import com.fariin.app.ui.components.FloatingIcon
import com.fariin.app.ui.theme.Fariin
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/*
 * The in-place profile photo viewer (iOS ContactInfoView.swift `ProfilePhotoViewer`), used by a
 * person's profile, a group's and a channel's info page, and my own photo in Settings.
 *
 * The photo grows OUT of the avatar on the page and closes back INTO it; it is never a new page.
 * It is drawn in the page's own window, over the page (iOS `.overlay`), so the page stays behind it
 * and a drag melts the solid backdrop away to show it. Profile photos are not end-to-end sealed
 * (FariinPhotoFetcher), so the avatar's bitmap is reused for the first frame and the full picture
 * is read from Coil's caches.
 *
 * Wiring on a page: `val photo = rememberProfilePhotoState()`, the hero avatar gets
 * `Modifier.profilePhotoSource(photo)` and `onResolved = photo::resolved`, a tap calls
 * `photo.show()`, and [ProfilePhotoViewer] is the page's last, full-screen child.
 */

/** How the photo lands (iOS `ProfilePhotoViewer` flags). */
data class ProfilePhotoStyle(
    /** Grow out of the round avatar and land on the whole photo, square corners (Settings, groups). */
    val landsSquare: Boolean = false,
    /** With [landsSquare]: land as a 1:1 square the screen's width, the photo cropped to fill it. */
    val squareCrop: Boolean = false,
    /** A channel's photo: starts and ends on the avatar's rounded square, never through a circle. */
    val squircle: Boolean = false,
)

/**
 * The bar the viewer puts where the page's own bar is (iOS: the X is a toolbar item in the back
 * button's slot, and the page's other bar items step aside while the viewer is up).
 */
data class ProfilePhotoBar(
    /** The page bar's height under the status bar: 64 for a pushed page's bar, 48 on Settings. */
    val height: Dp = 64.dp,
    /** Settings: "Profile photo", centred. A profile or group page shows no title. */
    val title: String? = null,
    /**
     * The person profile: the X leaves as soon as a close BEGINS, with the page's own chrome fading
     * back over the collapse (iOS `chromeHiddenForPhoto` / `onClosingBegan`). Settings and groups
     * keep the X until the photo has landed (their iOS X is gated on the viewer being up).
     */
    val leavesOnClose: Boolean = false,
)

/** One page's viewer: open or not, the avatar's rect and bitmap, and whether it showed a photo. */
@Stable
class ProfilePhotoState internal constructor() {
    /** The viewer is up (until the close has landed). The page's chrome steps aside while true. */
    var open by mutableStateOf(false)
        private set
    /** A close has begun: the page's chrome may come back over the collapse. */
    var closing by mutableStateOf(false)
        internal set
    /** The viewer stands in for the avatar: the avatar is hidden, so there is one picture, not two. */
    internal var handoff by mutableStateOf(false)
    /**
     * The avatar really drew a photo (iOS `onPhotoResolved`): a url whose file is gone, refused or
     * not loaded is a silhouette on screen, and a tap must not open an empty viewer.
     */
    var hasPhoto by mutableStateOf(false)
        private set
    internal var seed: ImageBitmap? = null
    /** The avatar's rect in its window, px. */
    internal var source: Rect? = null
    /** The open viewer's close (its collapse), for a host that gets Back itself (a popup). */
    var closeRequest: (() -> Unit)? = null
        internal set

    /** Avatar's answer: [has] = a photo is on screen, [bitmap] = what it drew (seeds the first frame). */
    fun resolved(has: Boolean, bitmap: ImageBitmap?) {
        hasPhoto = has
        seed = if (has) bitmap else null
    }

    /** Opens the viewer; false (nothing happens) when there is no photo on screen to open. */
    fun show(): Boolean {
        if (open || !hasPhoto || source == null) return false
        closing = false
        handoff = false
        open = true
        return true
    }

    internal fun finished() {
        handoff = false
        closing = false
        open = false
    }

    /** The page's bar items while the viewer is up: hidden, back the moment it has gone. */
    val chromeHidden: Boolean get() = open
}

@Composable
fun rememberProfilePhotoState(): ProfilePhotoState = remember { ProfilePhotoState() }

/** The avatar the viewer flies out of and back into: its rect, and hidden while the viewer is it. */
fun Modifier.profilePhotoSource(state: ProfilePhotoState): Modifier =
    onGloballyPositioned { lc ->
        val p = lc.positionInWindow()
        state.source = Rect(p, Size(lc.size.width.toFloat(), lc.size.height.toFloat()))
    }.graphicsLayer { alpha = if (state.handoff) 0f else 1f }

// iOS springs, as Compose springs: damping = 1 - bounce, stiffness = (2π / duration)².
private const val TWO_PI = 6.2831855f
private fun iosSpring(duration: Float, bounce: Float): AnimationSpec<Float> =
    spring(dampingRatio = 1f - bounce, stiffness = (TWO_PI / duration) * (TWO_PI / duration))
private val OpenSpring = iosSpring(0.38f, 0.15f)
private val CloseSpring = iosSpring(0.34f, 0.12f)
private val HomeSpring = iosSpring(0.3f, 0f)
/** iOS: the overlay is removed 0.36 s after the close starts, when the photo has landed. */
private const val CLOSE_LANDS_MS = 360L
/** The drag distance over which the backdrop melts away entirely (iOS 260 pt). */
private val MeltDistance = 260.dp
/** iOS: the round photo is the view's width minus 24 pt each side, no upper cap. */
private val RoundGutter = 48.dp
/** Android's channel avatar is a rounded square of 0.3 of its side (Avatar `squircle`). */
private const val SQUIRCLE_CORNER = 0.3f

/**
 * The viewer. Place it as the LAST child of a full-screen Box on the page (or in TopOverlay), so it
 * covers the page; it draws nothing while [state] is closed.
 */
@Composable
fun ProfilePhotoViewer(
    state: ProfilePhotoState,
    photoUrl: String?,
    style: ProfilePhotoStyle = ProfilePhotoStyle(),
    bar: ProfilePhotoBar = ProfilePhotoBar(),
    /** Told FIRST when a close begins, so a page's own fade runs beside the collapse. */
    onClosingBegan: () -> Unit = {},
) {
    if (!state.open) return
    val url = photoUrl.orEmpty()
    val density = LocalDensity.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = Fariin.colors
    val closingBegan by rememberUpdatedState(onClosingBegan)

    // Seeded with the avatar's own bitmap, so the flight leaves holding the photo, not a grey disc.
    var image by remember { mutableStateOf(state.seed) }
    var progress by remember { mutableFloatStateOf(0f) }   // 0 = on the avatar, 1 = open
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var closing by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }      // the open has begun: drags may move it
    var barAlpha by remember { mutableFloatStateOf(1f) }
    // The container: its size, and where it sits in the window (the source rect is window px).
    var box by remember { mutableStateOf(Size.Zero) }
    var boxAt by remember { mutableStateOf<Offset?>(null) }
    val jobs = remember { arrayOfNulls<Job>(5) }   // progress, drag x, zoom, bar, drag y

    fun anim(slot: Int, from: Float, to: Float, spec: AnimationSpec<Float>, set: (Float) -> Unit) {
        jobs[slot]?.cancel()
        jobs[slot] = scope.launch { animate(from, to, animationSpec = spec) { v, _ -> set(v) } }
    }
    fun home() {
        anim(1, dragX, 0f, HomeSpring) { dragX = it }
        anim(4, dragY, 0f, HomeSpring) { dragY = it }
        anim(2, zoom, 1f, HomeSpring) { zoom = it }
    }
    // Close = the reverse flight: progress, drag and zoom go home together, so the photo flies from
    // wherever the finger left it straight back into the avatar, shrinking as it goes.
    fun close() {
        if (closing) return
        closing = true
        state.closing = true
        closingBegan()
        anim(0, progress, 0f, CloseSpring) { progress = it }
        anim(1, dragX, 0f, CloseSpring) { dragX = it }
        anim(4, dragY, 0f, CloseSpring) { dragY = it }
        anim(2, zoom, 1f, CloseSpring) { zoom = it }
        if (bar.leavesOnClose) anim(3, barAlpha, 0f, tween(280)) { barAlpha = it }
        scope.launch {
            delay(CLOSE_LANDS_MS)
            // The avatar shows again FIRST; the flying copy goes two frames later, so there is never
            // a frame with neither (owner 2026-11-13: a blank flicker as it landed, over a sheet).
            state.handoff = false
            androidx.compose.runtime.withFrameNanos { }
            androidx.compose.runtime.withFrameNanos { }
            state.finished()
        }
    }

    BackHandler { close() }
    // A host whose own window takes Back (a popup over a sheet) closes it through this.
    state.closeRequest = { close() }
    // If the page goes away with the viewer up, the avatar must not stay hidden.
    DisposableEffect(Unit) { onDispose { state.handoff = false } }

    // ONE FRAME ON THE AVATAR, THEN FLY (iOS: the change goes to the next runloop so the avatar-sized
    // picture is really on screen first and the spring has somewhere to travel from). The avatar
    // is hidden only once the copy over it has been drawn, so there is never a frame with neither.
    LaunchedEffect(boxAt != null) {
        if (boxAt == null) return@LaunchedEffect
        withFrameNanos { }
        state.handoff = true
        started = true
        anim(0, 0f, 1f, OpenSpring) { progress = it }
    }
    // The seed may be the avatar's small bitmap, so the full picture is still read and swapped in:
    // the same photo, sharper. Decoded off the main thread by Coil, held to the screen's size.
    LaunchedEffect(url) {
        if (url.isEmpty()) return@LaunchedEffect
        val side = max(ctx.resources.displayMetrics.widthPixels, ctx.resources.displayMetrics.heightPixels)
        val r = runCatching {
            coil3.SingletonImageLoader.get(ctx).execute(
                coil3.request.ImageRequest.Builder(ctx).data(url).size(side)
                    .precision(coil3.size.Precision.INEXACT).build())
        }.getOrNull()
        val bmp = ((r as? coil3.request.SuccessResult)?.image as? coil3.BitmapImage)?.bitmap
        if (bmp != null) image = bmp.asImageBitmap()
    }

    val meltPx = with(density) { MeltDistance.toPx() }
    val gutterPx = with(density) { RoundGutter.toPx() }

    /** The picture's own shape; 1 until a bitmap is here. */
    fun aspect(): Float = image?.let { if (it.height > 0) it.width.toFloat() / it.height else 1f } ?: 1f

    /** Where the picture is this frame, in the container's px (before the pinch zoom). */
    fun frame(): Rect? {
        val at = boxAt ?: return null
        val s = state.source ?: return null
        if (box.width <= 0f) return null
        val src = s.translate(-at.x, -at.y)
        val p = progress
        val whole = style.landsSquare
        val square = style.landsSquare && style.squareCrop
        val a = aspect()
        val tw = if (square) box.width else if (whole) min(box.width, box.height * a) else box.width - gutterPx
        val th = if (square) tw else if (whole) tw / a else tw
        val w = max(1f, src.width + (tw - src.width) * p)
        val h = max(1f, src.height + (th - src.height) * p)
        val cx = src.center.x + (box.width / 2f - src.center.x) * p + dragX
        val cy = src.center.y + (box.height / 2f - src.center.y) * p + dragY
        return Rect(Offset(cx - w / 2f, cy - h / 2f), Size(w, h))
    }

    /** Round the whole way as an avatar; round (or a channel's rounded square) to square across the flight. */
    fun corner(w: Float, h: Float): Float {
        val p = progress.coerceIn(0f, 1f)
        val r = when {
            style.squircle -> w * SQUIRCLE_CORNER * (if (style.landsSquare) 1f - p else 1f)
            style.landsSquare -> (w / 2f) * (1f - p)
            else -> w / 2f
        }
        return r.coerceIn(0f, min(w, h) / 2f)
    }

    // Solid page when open and resting; the DRAG melts it away (fully, not partially).
    fun backdrop(): Float {
        val dist = sqrt(dragX * dragX + dragY * dragY)
        return progress.coerceIn(0f, 1f) * max(0f, 1f - dist / meltPx)
    }

    Box(
        Modifier.fillMaxSize()
            .onGloballyPositioned { lc ->
                box = Size(lc.size.width.toFloat(), lc.size.height.toFloat())
                boxAt = lc.positionInWindow()
            }
            .pointerInput(Unit) {
                // One gesture layer that never moves, so a finger's travel is measured on the screen,
                // not inside the photo it is moving. A touch that starts on the photo pinches or drags
                // it; one that starts on the backdrop is a tap that closes.
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    // Unconsumed only: a tap on the bar's X is the X's.
                    val down = awaitFirstDown(requireUnconsumed = true)
                    down.consume()
                    val onPhoto = frame()?.contains(down.position) == true
                    var moved = false
                    var dragActive = false
                    var pinching = false
                    var wasPinch = false
                    var startSpan = 0f
                    var last = Offset.Zero
                    while (true) {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.filter { it.pressed }
                        ev.changes.forEach { it.consume() }
                        if (pressed.isEmpty()) break
                        if (!onPhoto) {
                            if ((pressed[0].position - down.position).getDistance() > slop || pressed.size > 1) moved = true
                            continue
                        }
                        if (pressed.size >= 2) {
                            // PINCH: a drag that met a pinch never moves or closes the viewer, and the
                            // photo goes home when the pinch ends.
                            val span = (pressed[0].position - pressed[1].position).getDistance()
                            if (!pinching) {
                                pinching = true
                                wasPinch = true
                                startSpan = span
                            } else if (!closing && startSpan > 0f) {
                                jobs[1]?.cancel(); jobs[2]?.cancel(); jobs[4]?.cancel()
                                dragX = 0f; dragY = 0f
                                zoom = max(1f, span / startSpan)
                            }
                        } else {
                            if (pinching) {
                                pinching = false
                                if (!closing) home()
                            }
                            val t = pressed[0].position - down.position
                            if (!dragActive && t.getDistance() > slop) dragActive = true
                            if (dragActive) {
                                last = t
                                // The drag follows the finger, only at rest size and once open.
                                if (!wasPinch && zoom == 1f && !closing && started) {
                                    jobs[1]?.cancel(); jobs[4]?.cancel()
                                    dragX = t.x; dragY = t.y
                                }
                            }
                        }
                    }
                    if (!onPhoto) {
                        if (!moved) close()   // a tap on the backdrop
                        return@awaitEachGesture
                    }
                    if (pinching && !closing) home()
                    if (closing || !(dragActive || wasPinch)) return@awaitEachGesture
                    // Only a mostly vertical drag closes; then ANY real movement does (the reference
                    // app's rule: no distance to reach before it lets go).
                    if (wasPinch || zoom != 1f || abs(last.y) <= abs(last.x)) { home(); return@awaitEachGesture }
                    if (last.getDistance() > 0f) close() else home()
                }
            }
    ) {
        // The page's own background, melting with the drag.
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = backdrop() }.background(c.bg))
        // The picture: one clipped shape from the first frame to the last.
        val img = image
        Box(
            Modifier
                .layout { measurable, constraints ->
                    val f = frame()
                    val w = f?.width?.toInt() ?: 0
                    val h = f?.height?.toInt() ?: 0
                    // Fixed to the flight's size and placed where it is this frame; the node itself
                    // keeps the container's size, so a spring's overshoot never exceeds its bounds.
                    val placeable = measurable.measure(Constraints.fixed(w, h))
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        placeable.place(f?.left?.toInt() ?: 0, f?.top?.toInt() ?: 0)
                    }
                }
                .graphicsLayer {
                    val f = frame()
                    alpha = if (f == null) 0f else 1f
                    scaleX = zoom
                    scaleY = zoom
                    shape = RoundedCornerShape(if (f == null) 0f else corner(f.width, f.height))
                    clip = true
                }
                .background(c.placeholderFill)
        ) {
            if (img != null) Image(img, contentDescription = "Profile photo", contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize())
        }
        // The bar, where the page's bar is: the X in the back button's place, and the title on Settings.
        Box(Modifier.fillMaxWidth().statusBarsPadding().height(bar.height).graphicsLayer { alpha = barAlpha }) {
            if (bar.title != null) Text(bar.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = c.label,
                modifier = Modifier.align(Alignment.Center))
            FloatingIcon(painterResource(R.drawable.ic_sf_xmark), contentDescription = "Close",
                onClick = { close() }, modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp))
        }
    }
}
