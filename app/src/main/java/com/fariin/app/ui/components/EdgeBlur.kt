package com.fariin.app.ui.components

import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.Easing
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.hazeEffect
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp

/**
 * The scroll-edge blur in stages, as iOS (owner's iPhone photo 2026-11-05: right under the bar the
 * rows are frosted, lower down only softened, then sharp). A real variable blur, not one blur faded
 * in and out: full strength over the top [solid] part of the strip, then the radius eases down to
 * nothing at its lower edge. Used by the Chats bar, Settings and the chat header.
 */
fun edgeStages(solid: Float, gentle: Boolean = false): HazeProgressive.LinearGradient =
    HazeProgressive.verticalGradient(
        // [gentle]: a quintic ease (zero slope at both ends), so neither where the fade starts nor where it ends can be seen.
        easing = Easing { t -> if (t <= solid) 0f else ((t - solid) / (1f - solid)).coerceIn(0f, 1f).let { x -> if (gentle) x * x * x * (x * (6f * x - 15f) + 10f) else EaseInOutCubic.transform(x) } },
        startIntensity = 1f,
        endIntensity = 0f,
        preferPerformance = false,
    )

/**
 * The edge glass's strength. Owner 2026-11-05: "when you open a chat it must be there", as iOS. On
 * the screen's first moments (opening, its first layout) it follows at once, so a chat or tab opens
 * with its blur already on; after that it fades in and out over 220 ms as rows go under the bar.
 */
@androidx.compose.runtime.Composable
fun rememberEdgeAlpha(under: Boolean, startOn: Boolean = false): androidx.compose.runtime.State<Float> {
    // [startOn]: a chat opens at its newest message with the older ones under the header, but on the
    // very first frame its list is not measured yet and says "nothing under" (one sharp frame, seen
    // in a recording). With nothing really under it, glass over the plain page shows nothing anyway.
    val a = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(if (under || startOn) 1f else 0f) }
    val born = androidx.compose.runtime.remember { android.os.SystemClock.uptimeMillis() }
    androidx.compose.runtime.LaunchedEffect(under) {
        val target = if (under) 1f else 0f
        if (android.os.SystemClock.uptimeMillis() - born < 700) a.snapTo(target)
        else a.animateTo(target, androidx.compose.animation.core.tween(220))
    }
    return a.asState()
}

/**
 * THE TAB PAGES' TOP BAR (owner 2026-11-08: Settings, Calls and Stories the same; "never notice where
 * the bar ends"). Frosted over [haze] in the [page] colour, and it runs on past the bar's own height
 * by [tail]: over that tail the blur and the tint ease away together on a smooth curve, so there is
 * no edge to see. The tail is drawn but not measured, so what sits under the bar keeps its place.
 * Power Saving without glass: the page colour fading to clear over the same tail.
 */
fun androidx.compose.ui.Modifier.frostedTopBar(
    haze: dev.chrisbanes.haze.HazeState, page: androidx.compose.ui.graphics.Color, glass: Boolean,
    tail: androidx.compose.ui.unit.Dp = 34.dp,   // iOS measured (owner screenshot 2026-11-08): ends 34 under the bar
): androidx.compose.ui.Modifier = this
    .layout { m, k ->
        val t = tail.roundToPx()
        val p = m.measure(k.copy(maxHeight = androidx.compose.ui.unit.Constraints.Infinity))
        layout(p.width, (p.height - t).coerceAtLeast(0)) { p.place(0, 0) }
    }
    .then(if (glass) androidx.compose.ui.Modifier.hazeEffect(haze) {
        backgroundColor = page; blurRadius = 22.dp; noiseFactor = 0f
        tints = listOf(dev.chrisbanes.haze.HazeTint(page.copy(alpha = 0.70f)))   // 70%: the owner liked Calls at this
        inputScale = dev.chrisbanes.haze.HazeInputScale.Fixed(1f / 3)
        // Solid down the bar, then a long, soft ease to nothing over the tail.
        // iOS: full strength to the bar's middle, then a ~58 pt fade (status 28 + bar 48 + tail 34: middle at 0.47).
        progressive = edgeStages(0.47f, gentle = true)
    } else androidx.compose.ui.Modifier.background(androidx.compose.ui.graphics.Brush.verticalGradient(
        0f to page, 0.40f to page, 0.55f to page.copy(alpha = 0.78f), 0.7f to page.copy(alpha = 0.45f),
        0.84f to page.copy(alpha = 0.18f), 0.94f to page.copy(alpha = 0.05f), 1f to page.copy(alpha = 0f))))
    .padding(bottom = tail)
