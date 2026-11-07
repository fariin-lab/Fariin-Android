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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
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
fun FariinSheet(motion: SheetMotion, handle: Boolean = false, content: @Composable () -> Unit) {
    val c = Fariin.colors
    LaunchedEffect(Unit) { motion.open() }
    Box(Modifier.fillMaxSize()
        .drawBehind { drawRect(Color.Black.copy(alpha = 0.4f * (1f - motion.y / motion.full).coerceIn(0f, 1f))) }
        .clickable(interactionSource = null, indication = null) { motion.close() }) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(top = 10.dp)
            .offset { IntOffset(0, motion.y.toInt()) }
            .clip(RoundedCornerShape(topStart = SheetCorner, topEnd = SheetCorner))
            .drawBehind { drawRect(c.bg) }
            .clickable(interactionSource = null, indication = null) {}) {
            Box(Modifier.weight(1f)) {
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

/** The sheet's top corner radius (the browser's, tuned on the phone). */
val SheetCorner = 32.dp

/** How tall the drag strip at a handled sheet's top is; a page's top controls sit below it. */
val SheetHandleStrip = 24.dp

/**
 * The sheet hosted in a full-screen Compose Dialog. Back and a tap on the dim close it through the
 * same motion; [onDismiss] runs once it is gone. The window has no animation and no dim of its own.
 */
@Composable
fun FariinSheetDialog(onDismiss: () -> Unit, handle: Boolean = false, content: @Composable (SheetMotion) -> Unit) {
    val motion = rememberSheetMotion(onDismiss)
    Dialog(onDismissRequest = { motion.close() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false)) {
        SheetDialogWindow(Fariin.colors.dark)
        FariinSheet(motion, handle) { content(motion) }
    }
}

/**
 * The dialog's window for a sheet: edge to edge, no dim, no window animation (the sheet moves itself),
 * bars clear with icons that follow the theme; the keyboard lays over the page instead of squeezing it.
 */
@Composable
private fun SheetDialogWindow(dark: Boolean) {
    val view = LocalView.current
    SideEffect {
        (view.parent as? DialogWindowProvider)?.window?.let { w ->
            w.setWindowAnimations(0)
            w.setDimAmount(0f)
            w.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
            @Suppress("DEPRECATION") w.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
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
    private var raw = 0f            // the finger's own travel; y is raw with the rubber band applied
    private var job: Job? = null
    private var closing = false

    private fun run(target: Float, velocity: Float, spec: AnimationSpec<Float>, end: () -> Unit = {}) {
        job?.cancel()
        job = scope.launch {
            animate(y, target, velocity, spec) { v, _ -> y = v }
            end()
        }
    }

    // Open and close by button follow the iOS sheet curve (the one the open-source "Vaul" drawer uses
    // to match it): a quick start, a soft stop, no crawl at the end. The old critically damped spring
    // spent its last ~0.25 s creeping the final pixels (frame by frame on the Pixel, 2026-11-06).
    fun open() = run(0f, 0f, if (still) androidx.compose.animation.core.snap() else tween(500, easing = SheetEase))

    fun close(velocity: Float = 0f) {
        if (closing) return
        closing = true
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
        if (job?.isActive == true) { job?.cancel(); raw = if (y >= 0f) y else 0f }
        raw += dy
        // Above the top it pulls back harder the further it goes (the rubber band iOS uses).
        y = if (raw >= 0f) raw else -(1f - 1f / (-raw * 0.55f / band + 1f)) * band
    }

    fun release(velocity: Float) {
        if (closing) return
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
fun Modifier.sheetDrag(motion: SheetMotion, atTop: (() -> Boolean)? = null): Modifier = composed {
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
