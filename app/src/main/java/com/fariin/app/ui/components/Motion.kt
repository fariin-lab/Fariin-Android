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
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NavBackStackEntry
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

    // THE REFERENCE APP'S PAGE MOTION (its ActionBarLayout.startLayoutAnimation, studied 2026-11-04
    // after the owner felt a flicker): only the page on top moves. Opening, the new page fades in
    // while sliding 48 dp in from the right; back, the top page fades out while sliding 48 dp right.
    // The page underneath stands still, no slide and no fade. 150 ms on DecelerateInterpolator(1.5).
    const val PAGE_MS = 150
    /** DecelerateInterpolator(1.5): 1 - (1 - t)^3. */
    val PageEase = androidx.compose.animation.core.Easing { t -> 1f - (1f - t) * (1f - t) * (1f - t) }
    /** 48 dp in pixels (NavHost transition lambdas are not composable, so the screen's own density). */
    val pageShiftPx: Int get() = (48f * android.content.res.Resources.getSystem().displayMetrics.density).toInt()

    fun pageIn(): EnterTransition =
        slideInHorizontally(tween(PAGE_MS, easing = PageEase)) { pageShiftPx } + fadeIn(tween(PAGE_MS, easing = PageEase))
    fun pageOut(): ExitTransition =
        slideOutHorizontally(tween(PAGE_MS, easing = PageEase)) { pageShiftPx } + fadeOut(tween(PAGE_MS, easing = PageEase))
    /** The page underneath: shown as it is, the whole time the top page moves. */
    fun pageStill(): ExitTransition =
        fadeOut(tween(PAGE_MS, easing = androidx.compose.animation.core.Easing { if (it < 1f) 0f else 1f }))

    fun enter(): AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        if (!on) EnterTransition.None else pageIn()
    }

    fun exit(): AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        if (!on) ExitTransition.None else pageStill()
    }

    fun popEnter(): AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        EnterTransition.None
    }

    fun popExit(): AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        if (!on) ExitTransition.None else pageOut()
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
