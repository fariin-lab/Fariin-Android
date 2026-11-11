package com.fariin.app.ui.components

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import kotlinx.coroutines.launch
import androidx.compose.ui.layout.layout
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.composable
import com.fariin.app.perf.PowerSaving
import com.fariin.app.perf.rememberPowerState

/**
 * One place for the app's motion, so every animation obeys Settings > Power Saving > Animations
 * (and Battery Saver when the user follows it). Off = screens and rows change at once.
 */
object Motion {
    /** Material 3 "emphasized decelerate", the curve for things arriving on screen. */
    val Decelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    /** Material 3 "emphasized accelerate", for things leaving. */
    val Accelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** Read outside composition (NavHost transition lambdas are not composable). */
    val on: Boolean get() = PowerSaving.state.value.animations

    // PAGE MOTION, the iOS push (owner 2026-11-09, Pixel next to the iPhone: the old 150 ms fade-in
    // over 48 dp "taps right away", and the list showed through the half-faded chat). Now the new
    // page is solid the whole way and slides in from the right edge; the page under it drifts a
    // third of the way left (parallax), so the eye follows one sheet of paper moving. Back is the
    // same, reversed, a little quicker. Nothing is ever see-through.
    const val PAGE_MS = 320
    /** Back is a little quicker than opening. */
    const val PAGE_BACK_MS = 260
    /** How far the page underneath drifts, as a share of the width (iOS: about 0.3). */
    const val UNDER_SHIFT = 0.3f
    /** An iOS-like ease out: quick start, long soft landing (cubic-bezier 0.2, 0.9, 0.3, 1). */
    // Starts at twice the average speed, not four and a half (0.2, 0.9 made the first frame jump 20 %).
    val PageEase = CubicBezierEasing(0.3f, 0.6f, 0.2f, 1f)
    /** The back curve: starts at the finger's pace, lands softly. */
    val BackEase = CubicBezierEasing(0.25f, 0.6f, 0.3f, 1f)

    fun pageIn(): EnterTransition = slideInHorizontally(tween(PAGE_MS, easing = PageEase)) { it }
    /** Back: the top page slides off to the right, solid. */
    fun pageOut(): ExitTransition = slideOutHorizontally(tween(PAGE_BACK_MS, easing = BackEase)) { it }
    /** Opening: the page underneath drifts left. */
    fun pageUnder(): ExitTransition = slideOutHorizontally(tween(PAGE_MS, easing = PageEase)) { -(it * UNDER_SHIFT).toInt() }
    /** Back: the uncovered page drifts back in from the left. */
    fun pageBackUnder(): EnterTransition = slideInHorizontally(tween(PAGE_BACK_MS, easing = BackEase)) { -(it * UNDER_SHIFT).toInt() }

    /** The nav host's part: nothing visible (PageMotion moves the pages), only long enough that
     *  both pages stay composed while they move. A real change (1 px) so it is never skipped. */
    const val HOLD_MS = 1_200
    // A one-pixel slide, never a fade: the 0.99 alpha drew every page see-through for the whole
    // hold, the chat list showing faintly through a channel with its edge as a line down the middle
    // (owner's Pixel 2026-11-09, dark mode).
    fun holdIn(): EnterTransition = slideInHorizontally(tween(HOLD_MS)) { 1 }
    fun holdOut(): ExitTransition = slideOutHorizontally(tween(HOLD_MS)) { 1 }

    // The nav host only holds both pages; PageMotion moves them (see there).
    fun enter(): AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        if (!on) EnterTransition.None else holdIn()
    }

    fun exit(): AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        if (!on) ExitTransition.None else holdOut()
    }

    fun popEnter(): AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        if (!on) EnterTransition.None else holdIn()
    }

    fun popExit(): AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        if (!on) ExitTransition.None else holdOut()
    }

    /** A soft spring with a little bounce: reaction chips, badges. */
    fun <T> pop(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium)

    /** FAB in and out (scroll, selection, search). */
    fun fabIn(): EnterTransition = if (!on) EnterTransition.None
        else scaleIn(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) + fadeIn(tween(120))
    fun fabOut(): ExitTransition = if (!on) ExitTransition.None
        else scaleOut(tween(120, easing = Accelerate)) + fadeOut(tween(120))

    /** A row placed or moved in a list (Modifier.animateItem), or none when animations are off. */
    fun <T> item(): FiniteAnimationSpec<T>? = if (on) spring(stiffness = Spring.StiffnessMediumLow) else null
    val itemOffset: FiniteAnimationSpec<IntOffset>? get() = if (on) spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset(1, 1)) else null
}

/** Whether animations are allowed right now; recomposes when the switch or Battery Saver changes. */
@Composable
fun animationsOn(): Boolean = rememberPowerState().animations




/**
 * Every page change of the app's nav host, as the iOS push (owner's Pixel 2026-11-09).
 *
 * Why not the nav host's own slide: a page's first frames are heavy (a chat ~180 ms over four
 * frames on a Pixel 5, a profile or a chat coming back under a closing page as well). A slide timed
 * on the clock jumped most of its way in one of those frames and looked like no slide, or showed a
 * blank page under one going back. This motion runs on its own time, which moves at most one 60 Hz
 * frame per frame drawn: a slow frame only delays it, never skips it, and it starts on the tap.
 *
 * Forward: the new page slides in from the right edge, the page under it drifts a third left.
 * Back: reversed, a little quicker. Pages are solid the whole way, nothing is see-through.
 */
object PageMotion {
    /** 0 to 1 through the current change (already eased); 1 = settled. */
    var e by androidx.compose.runtime.mutableFloatStateOf(1f)
        private set
    var forward by androidx.compose.runtime.mutableStateOf(true)
        private set
    /** The page arriving on top (forward) or being uncovered (back), and the one it replaces. */
    var top: String? by androidx.compose.runtime.mutableStateOf(null)
        private set
    var other: String? by androidx.compose.runtime.mutableStateOf(null)
        private set
    /** The "main" entry (the chat list shell drawn outside the nav host). */
    var mainId: String? by androidx.compose.runtime.mutableStateOf(null)

    private var run = 0

    /** A new top page: from the composition that first sees it, so its first frame is placed right. */
    fun begin(newTop: String, oldTop: String?, isForward: Boolean) {
        if (newTop == top) return
        run++
        other = oldTop; top = newTop; forward = isForward
        e = if (oldTop == null || !Motion.on) 1f else 0f
    }

    /**
     * Lands any change still under way, at once. Called when the app comes back to the front: a back
     * press queued during a pop left the app mid-change, and on return the list sat a third off to
     * the left, still "moving", so it took no touch and never loaded (overnight test 2026-11-10,
     * chat > profile > three quick backs > reopen).
     */
    fun settle() {
        if (e >= 1f) return
        run++
        e = 1f
    }

    /** Plays the change begun last; called from an effect keyed on the top page. */
    suspend fun play() {
        if (e >= 1f) return
        val mine = run
        val ms = if (forward) Motion.PAGE_MS else Motion.PAGE_BACK_MS
        val ease = if (forward) Motion.PageEase else Motion.BackEase
        try {
            var last = androidx.compose.runtime.withFrameNanos { it }
            var v = 0f
            while (v < ms && mine == run) {
                val t = androidx.compose.runtime.withFrameNanos { it }
                // A slow frame (the new page building) moves it only a little, so the page never
                // peeks in and then lurches half way in one step (owner's Pixel 2026-11-09, groups).
                val gap = (t - last) / 1_000_000f
                v += if (gap > SLOW_FRAME_MS) STALL_STEP_MS else gap.coerceAtMost(MAX_STEP_MS)
                last = t
                e = ease.transform((v / ms).coerceAtMost(1f))
            }
        } finally {
            if (mine == run) e = 1f
        }
    }

    /** Where a page sits, as a share of the width (0 = in place, 1 = off to the right). */
    fun offset(id: String?): Float {
        if (id == null) return 0f
        val p = e
        return when (id) {
            top -> if (forward) 1f - p else -Motion.UNDER_SHIFT * (1f - p)
            other -> if (forward) -Motion.UNDER_SHIFT * p else p
            // Any other page still composed (one closing a moment ago, held by the nav host while
            // a new change starts): never drawn in place. Owner's Pixel 2026-11-09: a chat closed
            // and another opened within a second flashed the closed chat full screen for 4 frames.
            else -> if (top == null) 0f else 1f
        }
    }

    /** A page change is playing: nothing takes a touch or a back press until it lands (iOS does the
     *  same). Owner's Pixel 2026-11-09: taps while a chat was still sliding away landed on that chat,
     *  the next back press found no chat to close and left the app ("lost the track"). */
    val moving: Boolean get() = e < 1f

    /** Whether a page is on screen at all: the top one, or the one it is replacing while that moves. */
    fun shows(id: String?): Boolean = id != null && (id == top || (id == other && e < 1f))
}

/** The most [PageMotion]'s own time moves in one frame: a 60 Hz frame. */
private const val MAX_STEP_MS = 17f
/** A frame slower than this is a stall: it moves the slide by [STALL_STEP_MS] only. */
private const val SLOW_FRAME_MS = 25f
private const val STALL_STEP_MS = 6f

/** A nav host page placed by [PageMotion]. */
@Composable
fun PageFrame(id: String, content: @Composable () -> Unit) {
    val inner = androidx.compose.runtime.remember { InnerPages() }
    val innerOld = androidx.compose.ui.graphics.rememberGraphicsLayer()
    val innerScope = androidx.compose.runtime.rememberCoroutineScope()
    // Moved in placement, not in a draw layer: the header's blur (Haze) finds where the page is
    // from placement, and a layer-only move left it a frame behind, so it drew the messages sharp
    // for single frames while the page slid (owner's Pixel 2026-11-09).
    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()
        .layout { m, c ->
            val p = m.measure(c)
            layout(p.width, p.height) { p.place((PageMotion.offset(id) * p.width).toInt(), 0) }
        }
        // While the page moves, its touches go nowhere (PageMotion.moving). After the move, so it
        // covers the page where it is: on the frame, a page that had slid away still caught every
        // tap on the list for the nav host's whole hold (owner's Pixel 2026-11-09).
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val ev = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                    if (PageMotion.moving || inner.moving) ev.changes.forEach { it.consume() }
                }
            }
        }
        .drawWithContentCompat(inner, innerOld, innerScope)) {
        androidx.compose.runtime.CompositionLocalProvider(LocalInnerPages provides inner) { content() }
    }
}

/**
 * The pages a screen swaps in place of itself (a profile's Sounds & Notifications, a group's Storage
 * or Group Settings, a channel's pages): owner 2026-11-10, "they open raw". The screen says how deep
 * it is with [InnerPageDepth]; when that changes, its page frame slides like a nav push: the new page
 * in from the right over a picture of the one it covers (drifting a third left), and back the other
 * way. Same clock as [PageMotion]. A screen that never says a depth is drawn as before, untouched.
 */
class InnerPages {
    internal var depth by androidx.compose.runtime.mutableIntStateOf(0)
    internal var used = false
    internal var shown = 0
    internal var forward = true
    internal var p by androidx.compose.runtime.mutableFloatStateOf(1f)
    internal var run = 0
    val moving: Boolean get() = p < 1f
}

val LocalInnerPages = androidx.compose.runtime.staticCompositionLocalOf<InnerPages?> { null }

/** This screen is showing its base (0) or a page pushed in place over it (1, 2...). Call it on the
 *  path that draws, right before what it draws; the last call in a composition wins. */
@Composable
fun InnerPageDepth(depth: Int) {
    val h = LocalInnerPages.current ?: return
    h.used = true
    // A page pushed in place takes the keyboard down, as a nav push does (owner 2026-11-10).
    val fm = androidx.compose.ui.platform.LocalFocusManager.current
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.LaunchedEffect(depth) {
        if (depth > 0) {
            fm.clearFocus(force = true)
            androidx.core.view.ViewCompat.getWindowInsetsController(view)?.hide(androidx.core.view.WindowInsetsCompat.Type.ime())
        }
    }
    // Written in composition and read only in this frame's draw, so the first frame of a new page
    // is already placed off to the right, never drawn in place first.
    if (h.depth != depth) h.depth = depth
}

private fun androidx.compose.ui.Modifier.drawWithContentCompat(
    inner: InnerPages, old: androidx.compose.ui.graphics.layer.GraphicsLayer, scope: kotlinx.coroutines.CoroutineScope,
) = this.then(androidx.compose.ui.Modifier.drawWithContent {
    if (!inner.used) { drawContent(); return@drawWithContent }
    val d = inner.depth
    if (d != inner.shown) {
        inner.forward = d > inner.shown
        inner.shown = d
        if (Motion.on && size.width > 0f) {
            val mine = ++inner.run
            inner.p = 0f
            scope.launch {
                val ms = if (inner.forward) Motion.PAGE_MS else Motion.PAGE_BACK_MS
                val ease = if (inner.forward) Motion.PageEase else Motion.BackEase
                try {
                    var last = androidx.compose.runtime.withFrameNanos { it }
                    var v = 0f
                    while (v < ms && mine == inner.run) {
                        val t = androidx.compose.runtime.withFrameNanos { it }
                        val gap = (t - last) / 1_000_000f
                        v += if (gap > SLOW_FRAME_MS) STALL_STEP_MS else gap.coerceAtMost(MAX_STEP_MS)
                        last = t
                        inner.p = ease.transform((v / ms).coerceAtMost(1f))
                    }
                } finally { if (mine == inner.run) inner.p = 1f }
            }
        }
    }
    val p = inner.p
    if (p >= 1f) {
        // Settled: the frame is kept, so the next change has the page it leaves to slide from.
        old.record { this@drawWithContent.drawContent() }
        drawLayer(old)
        return@drawWithContent
    }
    val w = size.width
    if (inner.forward) {
        translate(left = -Motion.UNDER_SHIFT * p * w) { drawLayer(old) }
        translate(left = (1f - p) * w) { this@drawWithContent.drawContent() }
    } else {
        translate(left = -Motion.UNDER_SHIFT * (1f - p) * w) { this@drawWithContent.drawContent() }
        translate(left = p * w) { drawLayer(old) }
    }
})

/** [composable] with the page placed by [PageMotion]. */
fun androidx.navigation.NavGraphBuilder.page(
    route: String,
    arguments: List<androidx.navigation.NamedNavArgument> = emptyList(),
    deepLinks: List<androidx.navigation.NavDeepLink> = emptyList(),
    content: @Composable androidx.compose.animation.AnimatedContentScope.(NavBackStackEntry) -> Unit,
) = composable(route, arguments, deepLinks) { entry: NavBackStackEntry -> PageFrame(entry.id) { content(entry) } }


/** A back press while a page change plays is kept and done the moment it lands (PageMotion.moving):
 *  dropped, a chat stayed open after the press; let through, a second quick back found no chat to
 *  close and left the app. One press is kept at most. Placed after the nav host, so it is asked first. */
@Composable
fun PageMotionBackGuard() {
    val moving by androidx.compose.runtime.remember { androidx.compose.runtime.derivedStateOf { PageMotion.moving } }
    var pending by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val owner = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current
    androidx.activity.compose.BackHandler(enabled = moving) { pending = true }
    androidx.compose.runtime.LaunchedEffect(moving, pending) {
        if (!moving && pending) { pending = false; owner?.onBackPressedDispatcher?.onBackPressed() }
    }
}
