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
    val PageEase = CubicBezierEasing(0.2f, 0.9f, 0.3f, 1f)
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
     *  both pages stay composed while they move. A real change (0.99) so it is never skipped. */
    const val HOLD_MS = 1_200
    fun holdIn(): EnterTransition = fadeIn(tween(HOLD_MS), initialAlpha = 0.99f)
    fun holdOut(): ExitTransition = fadeOut(tween(HOLD_MS), targetAlpha = 0.99f)

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
                v += ((t - last) / 1_000_000f).coerceAtMost(MAX_STEP_MS)
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

    /** Whether a page is on screen at all: the top one, or the one it is replacing while that moves. */
    fun shows(id: String?): Boolean = id != null && (id == top || (id == other && e < 1f))
}

/** The most [PageMotion]'s own time moves in one frame: a 60 Hz frame. */
private const val MAX_STEP_MS = 17f

/** A nav host page placed by [PageMotion]. */
@Composable
fun PageFrame(id: String, content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()
        .graphicsLayer { translationX = PageMotion.offset(id) * size.width }) { content() }
}

/** [composable] with the page placed by [PageMotion]. */
fun androidx.navigation.NavGraphBuilder.page(
    route: String,
    arguments: List<androidx.navigation.NamedNavArgument> = emptyList(),
    deepLinks: List<androidx.navigation.NavDeepLink> = emptyList(),
    content: @Composable androidx.compose.animation.AnimatedContentScope.(NavBackStackEntry) -> Unit,
) = composable(route, arguments, deepLinks) { entry: NavBackStackEntry -> PageFrame(entry.id) { content(entry) } }
