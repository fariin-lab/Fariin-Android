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

    /**
     * How far a page sliding in by [EnterSlide] covers the list, 0 to 1; -1 when no such slide is
     * running (the list then follows the nav host's own transition).
     */
    var cover by androidx.compose.runtime.mutableFloatStateOf(-1f)

    /** The nav host's part while [EnterSlide] moves the page: nothing visible, just long enough
     *  that the list under it stays composed until the slide has landed. */
    const val HOLD_MS = 900
    fun holdIn(): EnterTransition = fadeIn(tween(HOLD_MS), initialAlpha = 1f)
    fun holdOut(): ExitTransition = fadeOut(tween(HOLD_MS), targetAlpha = 1f)

    fun enter(): AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        if (!on) EnterTransition.None else pageIn()
    }

    fun exit(): AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        if (!on) ExitTransition.None else pageUnder()
    }

    fun popEnter(): AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        if (!on) EnterTransition.None else pageBackUnder()
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


/**
 * A page that slides in from the tap without skipping (owner's Pixel 2026-11-09). A chat's first
 * frames are heavy (~180 ms over four frames on a Pixel 5); a normal slide timed on the clock
 * jumped to 90% in its second frame and looked like no slide at all. This one runs on its own
 * time, which moves at most one frame's worth per frame drawn: a slow frame only delays it, it
 * never skips ahead, so the whole slide is seen. Coming back to the page from one above it, it
 * does not slide again (the nav host's back motion plays).
 */
@Composable
fun EnterSlide(content: @Composable () -> Unit) {
    var done by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(!Motion.on) }
    val x = androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(if (done) 0f else 1f) }
    // Set while composing, not in an effect: the list follows from this same frame.
    androidx.compose.runtime.remember { if (!done) Motion.cover = 0f; 0 }
    if (!done) androidx.compose.runtime.LaunchedEffect(Unit) {
        try {
            var last = androidx.compose.runtime.withFrameNanos { it }
            var v = 0f
            while (v < Motion.PAGE_MS) {
                val t = androidx.compose.runtime.withFrameNanos { it }
                v += ((t - last) / 1_000_000f).coerceAtMost(MAX_STEP_MS)
                last = t
                val e = Motion.PageEase.transform((v / Motion.PAGE_MS).coerceAtMost(1f))
                x.floatValue = 1f - e
                Motion.cover = e
            }
        } finally {
            x.floatValue = 0f; done = true; Motion.cover = -1f
        }
    }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { if (Motion.cover >= 0f) Motion.cover = -1f } }
    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()
        .graphicsLayer { translationX = x.floatValue * size.width }) { content() }
}

/** The most [EnterSlide]'s own time moves in one frame: a 60 Hz frame. */
private const val MAX_STEP_MS = 17f
