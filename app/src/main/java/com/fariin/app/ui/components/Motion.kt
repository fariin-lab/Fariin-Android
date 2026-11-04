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

    // Measured against the big Android messenger on the Pixel 5 (owner 2026-11-04: ours felt
    // slow and the tap's grey never showed): a quicker page, and a crossfade in which the page
    // being left stays on screen while the new one arrives, so the tapped row's grey is seen.
    const val ENTER_MS = 220
    const val EXIT_MS = 200
    /** How long the page being covered takes to fade (it used to vanish in 90 ms). */
    const val COVER_FADE_MS = 180
    /** How far a page slides in the Material shared X axis: a short push, not a full-width slide. */
    private fun shift(full: Int) = full / 10

    // Shared X axis (Material motion): the new page slides in a little from the right and fades
    // up while the old one slides a little left and fades out; back reverses it. With predictive
    // back the pop pair follows the finger.
    fun enter(): AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        if (!on) EnterTransition.None
        else slideInHorizontally(tween(ENTER_MS, easing = Decelerate)) { shift(it) } +
            fadeIn(tween(ENTER_MS - 40, easing = Decelerate))
    }

    fun exit(): AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        if (!on) ExitTransition.None
        else slideOutHorizontally(tween(ENTER_MS, easing = Decelerate)) { -shift(it) } +
            fadeOut(tween(COVER_FADE_MS, easing = Accelerate))
    }

    fun popEnter(): AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        if (!on) EnterTransition.None
        else slideInHorizontally(tween(EXIT_MS, easing = Decelerate)) { -shift(it) } +
            fadeIn(tween(EXIT_MS, easing = Decelerate))
    }

    fun popExit(): AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        if (!on) ExitTransition.None
        else slideOutHorizontally(tween(EXIT_MS, easing = Accelerate)) { shift(it) } +
            fadeOut(tween(EXIT_MS - 50, easing = Accelerate))
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
