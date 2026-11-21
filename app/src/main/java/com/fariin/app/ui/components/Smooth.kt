package com.fariin.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.launch

/**
 * THE SHARED "NO POP" MOTION (iOS 120-pops contract, 2026-11-21): anything that appears, goes or
 * changes size on a real change fades, slides or resizes instead of cutting in one frame.
 *  - Fades 220 ms ease-out ([FADE_MS]); moves and size changes a soft spring with no bounce.
 *  - Off (Settings > Power Saving > Animations, Battery Saver, the system's "Remove animations"):
 *    everything here is instant, so nothing is ever slower for it.
 *  - Never on first paint: AnimatedVisibility/AnimatedContent only move on a later change, and
 *    callers keep these out of rows that are being recycled by a scroll.
 */
object Smooth {
    const val FADE_MS = 220

    /** Off when the app's animations are off. */
    val on: Boolean get() = Motion.on

    /**
     * The system's "Remove animations" (animator scale 0): moves and size changes become a plain
     * fade. Read from the public setting, at most once every 5 s.
     */
    val plain: Boolean get() {
        val now = System.currentTimeMillis()
        if (now - plainAt > 5_000) {
            plainAt = now
            plainNow = runCatching {
                val c = com.google.firebase.FirebaseApp.getInstance().applicationContext
                android.provider.Settings.Global.getFloat(c.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
            }.getOrDefault(false)
        }
        return plainNow
    }
    @Volatile private var plainAt = 0L
    @Volatile private var plainNow = false

    fun <T> fadeSpec(): FiniteAnimationSpec<T> = tween(if (on) Motion.ms(FADE_MS) else 0, easing = Motion.Decelerate)
    /** The size/move spring: soft, no bounce. */
    fun <T> soft(): FiniteAnimationSpec<T> = if (on && !plain) spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
        else tween(0)

    fun fadeIn(): EnterTransition = if (on) fadeIn(fadeSpec()) else EnterTransition.None
    fun fadeOut(): ExitTransition = if (on) fadeOut(fadeSpec()) else ExitTransition.None

    /** In and out with the space it takes opening and closing (bars, cards, error lines). */
    fun expandIn(): EnterTransition = if (on && plain) fadeIn(fadeSpec()) else if (on) fadeIn(fadeSpec()) + expandVertically(soft(), expandFrom = Alignment.Top) else EnterTransition.None
    fun shrinkOut(): ExitTransition = if (on && plain) fadeOut(fadeSpec()) else if (on) fadeOut(fadeSpec()) + shrinkVertically(soft(), shrinkTowards = Alignment.Top) else ExitTransition.None
    /** For something anchored at the bottom (a bar over the composer). */
    fun expandUp(): EnterTransition = if (on && plain) fadeIn(fadeSpec()) else if (on) fadeIn(fadeSpec()) + expandVertically(soft(), expandFrom = Alignment.Bottom) else EnterTransition.None
    fun shrinkDown(): ExitTransition = if (on && plain) fadeOut(fadeSpec()) else if (on) fadeOut(fadeSpec()) + shrinkVertically(soft(), shrinkTowards = Alignment.Bottom) else ExitTransition.None

    /** A cross-fade between two contents, the size following on the spring. */
    fun <S> crossfade(): AnimatedContentTransitionScope<S>.() -> ContentTransform = {
        if (!on) ContentTransform(EnterTransition.None, ExitTransition.None, sizeTransform = null)
        else (fadeIn(fadeSpec()) togetherWith fadeOut(fadeSpec())).using(SizeTransform(clip = false) { _, _ -> soft<IntSize>() })
    }

    /** A number that rolls: up when it grows, down when it shrinks. */
    fun rollNumber(): AnimatedContentTransitionScope<Int>.() -> ContentTransform = {
        if (!on) ContentTransform(EnterTransition.None, ExitTransition.None, sizeTransform = null)
        else if (plain) fadeIn(fadeSpec()) togetherWith fadeOut(fadeSpec())
        else {
            val up = targetState > initialState
            (slideInVertically(soft()) { h -> if (up) h else -h } + fadeIn(fadeSpec()) togetherWith
                slideOutVertically(soft()) { h -> if (up) -h else h } + fadeOut(fadeSpec()))
                .using(SizeTransform(clip = true) { _, _ -> soft<IntSize>() })
        }
    }
}

/** Size changes on the soft spring (nothing when animations are off). */
fun Modifier.smoothSize(): Modifier = if (Smooth.on && !Smooth.plain) animateContentSize(Smooth.soft()) else this

/**
 * Shows or hides [content] with a fade (and its space, when [resize]). Until [visible] first changes
 * it is a plain `if` (no transition machinery in every list row); from the first change on, it
 * animates from where it was.
 */
@Composable
fun Appear(visible: Boolean, modifier: Modifier = Modifier, resize: Boolean = true, content: @Composable () -> Unit) {
    val state = androidx.compose.runtime.remember { androidx.compose.animation.core.MutableTransitionState(visible) }
    val changed = androidx.compose.runtime.remember { booleanArrayOf(false) }
    if (!changed[0] && visible == state.currentState) {
        if (visible) androidx.compose.foundation.layout.Box(modifier) { content() }
        return
    }
    changed[0] = true
    state.targetState = visible
    AnimatedVisibility(state, modifier,
        enter = if (resize) Smooth.expandIn() else Smooth.fadeIn(),
        exit = if (resize) Smooth.shrinkOut() else Smooth.fadeOut()) { content() }
}

/** [content] for [state], cross-faded when it changes (never on first paint). */
@Composable
fun <S> Swap(state: S, modifier: Modifier = Modifier, label: String = "swap", content: @Composable (S) -> Unit) {
    AnimatedContent(state, modifier, transitionSpec = Smooth.crossfade(), label = label) { content(it) }
}

/** A count that rolls instead of jumping. */
@Composable
fun RollingNumber(value: Int, modifier: Modifier = Modifier, content: @Composable (Int) -> Unit) {
    AnimatedContent(value, modifier, transitionSpec = Smooth.rollNumber(), label = "roll") { content(it) }
}

/**
 * Fades this in again whenever [value] changes after its first draw: the cheap way for a list row
 * (no extra layout, no coroutine until something changes). A row given to another chat by a scroll
 * starts over (its state is new), so nothing replays.
 */
@Composable
fun Modifier.fadeOnChange(value: Any?): Modifier {
    if (!Smooth.on) return this
    val alpha = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(1f) }
    val last = androidx.compose.runtime.remember { arrayOf(value) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // Hidden in the very frame the new value is drawn (draw reads it after this runs), then faded up.
    val hide = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.SideEffect {
        if (last[0] != value) {
            last[0] = value
            hide.value = true
            scope.launch {
                alpha.snapTo(0f)
                hide.value = false
                alpha.animateTo(1f, Smooth.fadeSpec())
            }
        }
    }
    // A layer only while a change is showing (a row at rest gets none): hidden in the composition
    // that brings the new value, then faded up.
    val changing = last[0] != value
    if (!changing && !hide.value && !alpha.isRunning && alpha.value >= 1f) return this
    return this.then(androidx.compose.ui.Modifier.graphicsLayer { this.alpha = if (changing || hide.value) 0f else alpha.value })
}

/** True from the second frame this screen has drawn: what appears after that is a real change. */
@Composable
fun rememberSettled(): androidx.compose.runtime.State<Boolean> {
    val settled = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        androidx.compose.runtime.withFrameNanos { }
        androidx.compose.runtime.withFrameNanos { }
        settled.value = true
    }
    return settled
}

/** Fades in when it first appears, if [play] (the screen had already drawn); else just there. */
@Composable
fun Modifier.enterFade(play: Boolean): Modifier {
    if (!Smooth.on) return this
    val alpha = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(if (play) 0f else 1f) }
    if (play) androidx.compose.runtime.LaunchedEffect(Unit) { alpha.animateTo(1f, Smooth.fadeSpec()) }
    return this.then(androidx.compose.ui.Modifier.graphicsLayer { this.alpha = alpha.value })
}

/**
 * [target], moved there on the soft spring when it changes after the first draw; at once on the
 * first draw. For list rows: no coroutine until the value really changes (animateFloatAsState starts
 * one per row as it composes).
 */
@Composable
fun smoothFloat(target: Float): androidx.compose.runtime.State<Float> {
    val anim = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(target) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    androidx.compose.runtime.SideEffect {
        if (anim.targetValue != target) {
            if (!Smooth.on) scope.launch { anim.snapTo(target) }
            else scope.launch { anim.animateTo(target, Smooth.soft()) }
        }
    }
    return anim.asState()
}

/** [smoothSize] as a value, for chains built outside a @Composable receiver. */
val SmoothSizeModifier: Modifier get() = Modifier.smoothSize()
