package com.fariin.app.ui.components

import android.annotation.SuppressLint
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.fariin.app.ui.theme.Fariin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * A full-height page presented the way iOS presents a sheet (owner, 2026-11-06): it slides up from
 * the bottom with rounded top corners, the screen behind stays dimmed at the top, and it closes by
 * its X, by Back, by a tap on the dimmed screen, or by a swipe down that follows the finger.
 *
 * First built and tuned frame by frame for the in-app browser; the browser, the PDF reader and the
 * location pages share it so they all move the same way.
 *
 * Two hosts: an Activity with Theme.Fariin.Sheet (translucent, no window animation) calls
 * [rememberSheetMotion] + [FariinSheet] in setContent and finishes in onClosed; a Compose page uses
 * [FariinSheetDialog]. Inside, a page can let the sheet move by [sheetDrag] (a strip or handle), by
 * [sheetDrag] with atTop (a list pulled down at its top), or by a View's own touch listener calling
 * [SheetMotion.drag] / [SheetMotion.release] (the browser's WebView).
 */
@Composable
fun rememberSheetMotion(onClosed: () -> Unit): SheetMotion {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val full = with(density) { (LocalConfiguration.current.screenHeightDp + 80).dp.toPx() }
    val closed by rememberUpdatedState(onClosed)
    // Animations off (Power Saving, Battery Saver, a low-end phone, or the system's Remove
    // animations): the sheet appears and leaves at once; a finger still drags it.
    val still = !(com.fariin.app.perf.rememberPowerState().animations && com.fariin.app.ui.thread.systemAnimationsOn())
    return remember {
        SheetMotion(scope, full, dismissAt = with(density) { 160.dp.toPx() }, flingClose = with(density) { 900.dp.toPx() },
            band = with(density) { 120.dp.toPx() }, onClosed = { closed() })
    }.also { it.still = still }
}

/**
 * The sheet frame: the dim behind, the page on top under the status bar with a 32 dp rounded top
 * (the page itself carries it, no strip above it, as iOS). [handle] draws the iOS grabber and makes
 * a top strip drag the sheet, for pages whose own content takes every drag (a map).
 */
@Composable
fun FariinSheet(motion: SheetMotion, handle: Boolean = false, fill: Color? = null, content: @Composable () -> Unit) {
    val c = Fariin.colors
    val bg = fill ?: c.bg
    LaunchedEffect(Unit) { motion.open() }
    val at = remember { arrayOfNulls<androidx.compose.ui.geometry.Rect>(1) }
    Box(Modifier.fillMaxSize()
        .drawBehind { drawRect(Color.Black.copy(alpha = 0.4f * motion.grow.value * (1f - motion.y / motion.full).coerceIn(0f, 1f))) }
        .clickable(interactionSource = null, indication = null) { motion.close() }) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(top = 10.dp)
            .onGloballyPositioned { at[0] = it.boundsInWindow() }
            .offset { IntOffset(0, motion.y.toInt()) }
            .graphicsLayer {
                // Grown from a button (SheetMotion.source): at 0 the sheet is a circle sitting on the
                // button, at 1 it is itself; the corners round off as it goes.
                val g = motion.grow.value
                val src = if (g < 1f) motion.source?.invoke() else null
                val box = at[0]
                if (src != null && box != null && size.width > 0f) {
                    val inv = 1f - g
                    val sx = src.width / size.width; val sy = src.height / size.height
                    scaleX = sx + (1f - sx) * g; scaleY = sy + (1f - sy) * g
                    val cx = box.left + size.width / 2f; val cy = box.top + motion.y + size.height / 2f
                    translationX = (src.center.x - cx) * inv
                    translationY = (src.center.y - cy) * inv
                    val r = SheetCorner.toPx()
                    shape = RoundedCornerShape(lerpPx(size.width / 2f, r, g), lerpPx(size.width / 2f, r, g),
                        lerpPx(size.width / 2f, 0f, g), lerpPx(size.width / 2f, 0f, g))
                    clip = true
                } else {
                    scaleX = 1f; scaleY = 1f; translationX = 0f; translationY = 0f
                    shape = RoundedCornerShape(topStart = SheetCorner, topEnd = SheetCorner); clip = true
                }
            }
            .drawBehind { drawRect(bg) }
            .drawWithContent {
                drawContent()
                // The button's "+" over the shrunk sheet, fading as it grows (iOS: the glyph rides in).
                val g = motion.grow.value
                if (g < 0.5f && motion.source != null) {
                    val a = (1f - g / 0.5f).coerceIn(0f, 1f)
                    val arm = minOf(size.width, size.height) * 0.12f
                    val cx = size.width / 2f; val cy = size.height / 2f
                    val col = c.label.copy(alpha = a)
                    val sw = minOf(size.width, size.height) * 0.025f
                    drawLine(col, Offset(cx - arm, cy), Offset(cx + arm, cy), sw)
                    drawLine(col, Offset(cx, cy - arm), Offset(cx, cy + arm), sw)
                }
            }
            .clickable(interactionSource = null, indication = null) {}) {
            Box(Modifier.weight(1f).graphicsLayer { alpha = ((motion.grow.value - 0.35f) / 0.45f).coerceIn(0f, 1f) }) {
                content()
                if (handle) Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(SheetHandleStrip).sheetDrag(motion)) {
                    // The iOS grabber: 36 x 5, 6 dp from the top, grey that reads on a map and on a page.
                    Box(Modifier.align(Alignment.TopCenter).padding(top = 6.dp).size(36.dp, 5.dp)
                        .clip(RoundedCornerShape(50)).background(Color(0x99888890)))
                }
            }
        }
    }
}

private fun lerpPx(a: Float, b: Float, t: Float) = a + (b - a) * t

/** The sheet's top corner radius (the browser's, tuned on the phone). */
val SheetCorner = 32.dp

/** How tall the drag strip at a handled sheet's top is; a page's top controls sit below it. */
val SheetHandleStrip = 24.dp

/**
 * The sheet hosted in a full-screen Compose Dialog. Back and a tap on the dim close it through the
 * same motion; [onDismiss] runs once it is gone. The window has no animation and no dim of its own.
 */
@Composable
fun FariinSheetDialog(onDismiss: () -> Unit, handle: Boolean = false, content: @Composable (SheetMotion) -> Unit) =
    FariinSheetDialog(rememberSheetMotion(onDismiss), handle, content = content)

/**
 * The same dialog around a [motion] the caller holds (to give it a [SheetMotion.mid] stop or to close
 * it from inside). [fill] is the page colour; [typing] lets the keyboard's height reach the page
 * (imePadding) for a page with a text field at its bottom.
 */
@Composable
fun FariinSheetDialog(motion: SheetMotion, handle: Boolean = false, fill: Color? = null, typing: Boolean = false,
    content: @Composable (SheetMotion) -> Unit) {
    Dialog(onDismissRequest = { motion.close() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false)) {
        SheetDialogWindow(Fariin.colors.dark, typing)
        // A page swapped inside a sheet must never slide the page under the sheet (InnerPages).
        FariinSheet(motion, handle, fill) { androidx.compose.runtime.CompositionLocalProvider(LocalInnerPages provides null) { content(motion) } }
    }
}

/**
 * The dialog's window for a sheet: edge to edge, no dim, no window animation (the sheet moves itself),
 * bars clear with icons that follow the theme; the keyboard lays over the page instead of squeezing it.
 */
@Composable
private fun SheetDialogWindow(dark: Boolean, typing: Boolean = false) {
    val view = LocalView.current
    SideEffect {
        (view.parent as? DialogWindowProvider)?.window?.let { w ->
            w.setWindowAnimations(0)
            w.setDimAmount(0f)
            w.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
            // With a field at the bottom the keyboard's insets must reach the page (ADJUST_NOTHING hides
            // them on older Android); edge to edge, the window itself still does not shrink.
            @Suppress("DEPRECATION") w.setSoftInputMode(if (typing) android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                else android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
            WindowCompat.setDecorFitsSystemWindows(w, false)
            // A dialog window does not draw its own bar backgrounds by default, so the clear colour
            // below was ignored and the status bar stayed black (window dump on the Pixel, 2026-11-06).
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            @Suppress("DEPRECATION") run {
                w.statusBarColor = android.graphics.Color.TRANSPARENT
                w.navigationBarColor = android.graphics.Color.TRANSPARENT
            }
            if (android.os.Build.VERSION.SDK_INT >= 29) { w.isStatusBarContrastEnforced = false; w.isNavigationBarContrastEnforced = false }
            // Into the camera-cutout strip too: without it the status bar stayed a black band above the
            // sheet on the Pixel 5 (its punch hole sits in that strip), where the dimmed chat should show.
            if (android.os.Build.VERSION.SDK_INT >= 28) w.attributes = w.attributes.apply {
                layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            WindowCompat.getInsetsController(w, view).apply {
                // The status bar sits over the dimmed screen behind the sheet: white icons, as iOS
                // turns them while a sheet is up (dark ones vanished on the dim, Pixel 2026-11-06).
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
}

/**
 * The sheet's movement, done the way iOS sheets feel: it tracks the finger 1:1; pulled above its top
 * it gives with a rubber band; let go, the finger's speed decides: a flick or a long pull closes it,
 * otherwise a spring carries it back from that same speed. Every animation starts from where the
 * sheet is and how fast it moves, so a grab mid-animation never jumps.
 */
class SheetMotion internal constructor(
    private val scope: CoroutineScope,
    internal val full: Float,
    private val dismissAt: Float,
    private val flingClose: Float,
    private val band: Float,
    private val onClosed: () -> Unit,
) {
    var y by mutableFloatStateOf(full)
        private set
    /** No slide: open and close jump (animations off). */
    internal var still = false
    /**
     * A middle stop (iOS medium detent), as an offset from the top; 0 = none. The sheet opens there,
     * a drag or a scroll up carries it to full, and a release settles on whichever stop the finger's
     * speed points at (full, middle, or closed).
     */
    var mid = 0f
    /**
     * The middle stop is the top (iOS one fitted detent, `.height(contentHeight)`): a pull up only
     * stretches and springs back, it never carries the sheet to full height. [expand] still can (the
     * keyboard for a text field), and from there the full height is the top again.
     */
    var midIsTop = false
    private var expanded = false
    private val top: Float get() = if (midIsTop && !expanded && mid > 0f) mid else 0f
    /** Resting fully open (no drag or animation holding it lower). */
    val atFull: Boolean get() = y <= 0.5f

    /**
     * Set: the sheet grows out of this button (its rect in window pixels, the composer's "+") and a
     * button or Back close shrinks it back into it (iOS AttachSheetController, owner 2026-11-07:
     * "in iOS it animates open and close, Android doesn't"). A swipe down still slides it away.
     */
    var source: (() -> androidx.compose.ui.geometry.Rect?)? = null
    /** 0 = the button, 1 = the sheet; stays 1 for a sheet with no [source]. */
    val grow = androidx.compose.animation.core.Animatable(1f)
    private fun sourceRect() = source?.invoke()?.takeIf { it.width > 0f && !still }
    private var raw = 0f            // the finger's own travel; y is raw with the rubber band applied
    private var job: Job? = null
    private var closing = false

    private fun run(target: Float, velocity: Float, spec: AnimationSpec<Float>, end: () -> Unit = {}) {
        job?.cancel()
        job = scope.launch {
            animate(y, target, velocity, spec) { v, _ -> y = v }
            raw = target.coerceAtLeast(0f)   // the next drag starts from where it rests
            end()
        }
    }

    // Open and close by button follow the iOS sheet curve (the one the open-source "Vaul" drawer uses
    // to match it): a quick start, a soft stop, no crawl at the end. The old critically damped spring
    // spent its last ~0.25 s creeping the final pixels (frame by frame on the Pixel, 2026-11-06).
    fun open() {
        if (sourceRect() != null && !closing) {
            // From the button: the sheet sits at its stop at once and grows there.
            job?.cancel(); y = mid; raw = mid
            scope.launch {
                grow.snapTo(0f)
                grow.animateTo(1f, spring(dampingRatio = 0.86f, stiffness = 300f, visibilityThreshold = 0.002f))
            }
            return
        }
        run(mid, 0f, if (still) androidx.compose.animation.core.snap() else tween(500, easing = SheetEase))
    }

    /**
     * Moves the middle stop to where the content, now measured, really ends (a sheet as tall as what
     * it holds, no empty band under the last button). A sheet opening to, or resting at, the old stop
     * carries on to the new one.
     */
    fun fitMid(newMid: Float) {
        if (kotlin.math.abs(newMid - mid) < 1f) return
        val old = mid
        mid = newMid
        if (closing || expanded) return
        // A sheet resting at full height only because the last page filled it (old stop 0) follows
        // too: Report step 1 (13 rows) to a 5-row step left a big empty band (owner 2026-11-09).
        if (job?.isActive == true || kotlin.math.abs(y - old) < 1f || (old <= 0f && y < 1f)) open()
    }

    /** To full height on the same curve (a list opened, the keyboard coming up). */
    fun expand() {
        if (closing || (atFull && job?.isActive != true)) return
        expanded = true
        run(0f, 0f, if (still) androidx.compose.animation.core.snap() else tween(500, easing = SheetEase))
    }

    fun close(velocity: Float = 0f) {
        if (closing) return
        closing = true
        // A button, Back or a tap on the dim: back into the button it came from.
        if (velocity <= 0f && sourceRect() != null) {
            job?.cancel()
            scope.launch {
                grow.animateTo(0f, tween(300, easing = SheetEase))
                onClosed()
            }
            return
        }
        // A flick keeps its own speed (a spring started from it); a button or Back uses the curve.
        val spec: AnimationSpec<Float> =
            if (still) androidx.compose.animation.core.snap()
            else if (velocity > 0f) spring(dampingRatio = 1f, stiffness = 700f, visibilityThreshold = 4f)
            else tween(380, easing = SheetEase)
        run(full, velocity.coerceAtLeast(0f), spec) { onClosed() }
    }

    private companion object {
        val SheetEase = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)
    }

    fun drag(dy: Float) {
        if (closing) return
        val t = top
        if (job?.isActive == true) { job?.cancel(); raw = if (y >= t) y else t }
        raw += dy
        // Above the top it pulls back harder the further it goes (the rubber band iOS uses).
        y = if (raw >= t) raw else t - (1f - 1f / ((t - raw) * 0.55f / band + 1f)) * band
    }

    fun release(velocity: Float) {
        if (closing) return
        if (mid > 0f) {
            val projected = y + velocity * 0.2f
            when {
                // From the middle stop or below, a flick down or a pull well past it closes.
                (velocity > flingClose && y >= mid - 1f) || projected > mid + (full - mid) * 0.3f -> close(velocity)
                // Only a sheet that may grow goes to full height on a pull up (not a fitted one, [midIsTop]).
                top == 0f && projected < mid / 2f -> run(0f, velocity, spring(dampingRatio = 0.86f, stiffness = 520f, visibilityThreshold = 0.5f))
                else -> run(mid, velocity, spring(dampingRatio = 0.86f, stiffness = 520f, visibilityThreshold = 0.5f))
            }
            return
        }
        val projected = y + velocity * 0.2f      // where the flick would carry it
        if (y > dismissAt || velocity > flingClose || projected > full * 0.45f) close(velocity)
        else { raw = 0f; run(0f, velocity, spring(dampingRatio = 0.86f, stiffness = 520f, visibilityThreshold = 0.5f)) }
    }
}

/**
 * Lets a touch drag the sheet: it follows the finger 1:1 and lets go with the finger's speed. The
 * finger is read in window coordinates, because whatever holds this moves with the sheet (read
 * locally, each move would come out short by the sheet's own last step).
 *
 * With no [atTop] it is a strip or handle: any vertical drag on it moves the sheet. With [atTop] it
 * wraps a scrolling page: one finger pulling down while [atTop] says the page cannot scroll further
 * up takes the touch from the page and moves the sheet (the browser's rule, iOS); every other touch
 * stays the page's own. It looks at the touch before the page does, so the page never scrolls or
 * taps on a pull.
 */
@SuppressLint("ModifierFactoryUnreferencedReceiver")
fun Modifier.sheetDrag(motion: SheetMotion, atTop: (() -> Boolean)? = null, yieldToChildren: Boolean = false): Modifier = composed {
    val at = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val top by rememberUpdatedState(atTop)
    val pass = if (atTop == null) PointerEventPass.Main else PointerEventPass.Initial
    onGloballyPositioned { at[0] = it }.pointerInput(motion, pass) {
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = pass)
            fun rootY(p: Offset): Float = at[0]?.takeIf { it.isAttached }?.localToRoot(p)?.y ?: p.y
            val tracker = VelocityTracker()
            val start = rootY(down.position)
            var last = start
            var pulling = false
            tracker.addPosition(down.uptimeMillis, Offset(0f, start))
            while (true) {
                val event = awaitPointerEvent(pass)
                val ch = event.changes.firstOrNull { it.id == down.id } ?: break
                val y = rootY(ch.position)
                tracker.addPosition(ch.uptimeMillis, Offset(0f, y))
                if (!ch.pressed) {
                    if (pulling) { ch.consume(); motion.release(tracker.calculateVelocity().y) }
                    break
                }
                if (!pulling) {
                    // Behind a scrolling page: a drag the page already took is the page's.
                    if (yieldToChildren && ch.isConsumed) break
                    val gate = top
                    val starts = if (gate == null) abs(y - start) > slop
                        else event.changes.size == 1 && y - start > slop && gate()
                    if (gate != null && event.changes.size > 1) break   // a pinch is the page's
                    if (starts) { pulling = true; last = y }
                }
                if (pulling) { motion.drag(y - last); last = y; ch.consume() }
            }
        }
    }
}

/**
 * For a sheet with a [SheetMotion.mid] stop whose page scrolls (a grid of photos): a push up first
 * carries the sheet to full height, then scrolls the page; a pull down with the page at its top moves
 * the sheet, and the lift of the finger settles it (or closes it) by its speed. A fling the page
 * throws to its own top never moves the sheet.
 */
@Composable
fun rememberSheetScroll(motion: SheetMotion): NestedScrollConnection = remember(motion) {
    object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source != NestedScrollSource.UserInput) return Offset.Zero
            if (available.y < 0f && motion.y > 0f) {
                val dy = available.y.coerceAtLeast(-motion.y)
                motion.drag(dy)
                return Offset(0f, dy)
            }
            return Offset.Zero
        }
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
            motion.drag(available.y)
            return Offset(0f, available.y)
        }
        override suspend fun onPreFling(available: Velocity): Velocity {
            // Off a stop: the sheet was moving under the finger, so the fling is the sheet's.
            val y = motion.y
            if (abs(y) > 0.5f && abs(y - motion.mid) > 0.5f) { motion.release(available.y); return available }
            return Velocity.Zero
        }
    }
}
