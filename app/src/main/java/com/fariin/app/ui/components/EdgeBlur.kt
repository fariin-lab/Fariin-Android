package com.fariin.app.ui.components

import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.Easing
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.hazeEffect
import androidx.compose.ui.draw.drawWithContent
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
    tail: androidx.compose.ui.unit.Dp = 24.dp,   // a little room for the cross-fade to finish under the bar
): androidx.compose.ui.Modifier = this
    .layout { m, k ->
        val t = tail.roundToPx()
        val p = m.measure(k.copy(maxHeight = androidx.compose.ui.unit.Constraints.Infinity))
        layout(p.width, (p.height - t).coerceAtLeast(0)) { p.place(0, 0) }
    }
    .then(if (glass) androidx.compose.ui.Modifier.hazeEffect(haze) {
        // Mostly blur, a light tint: a colour band is what shows where a bar ends (reference: ~0.4).
        backgroundColor = page; blurRadius = 14.dp; noiseFactor = 0f   // softer: the reference keeps what is under readable (dark-mode compare)
        tints = listOf(dev.chrisbanes.haze.HazeTint(page.copy(alpha = 0.42f)))
        inputScale = dev.chrisbanes.haze.HazeInputScale.Fixed(1f / 3)
        // Solid down the bar, then a long, soft ease to nothing over the tail.
        // A CROSS-FADE, NOT A FADING RADIUS (owner's video 2026-11-08, frame by frame: a letter under
        // the edge came out half blurred, half sharp, cut by a line where the radius changed). The blur
        // stays the same strength; the blurred layer's opacity eases to nothing on a smooth curve, so
        // the sharp page shows through gradually, as the reference app does.
        mask = androidx.compose.ui.graphics.Brush.verticalGradient(
            0f to androidx.compose.ui.graphics.Color.Black, 0.50f to androidx.compose.ui.graphics.Color.Black,
            0.60f to androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.90f), 0.70f to androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.66f),
            0.80f to androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.36f), 0.90f to androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.12f),
            0.96f to androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.03f), 1f to androidx.compose.ui.graphics.Color.Transparent)
    } else androidx.compose.ui.Modifier.background(androidx.compose.ui.graphics.Brush.verticalGradient(
        0f to page, 0.40f to page, 0.55f to page.copy(alpha = 0.78f), 0.7f to page.copy(alpha = 0.45f),
        0.84f to page.copy(alpha = 0.18f), 0.94f to page.copy(alpha = 0.05f), 1f to page.copy(alpha = 0f))))
    // The blur fades at its own top edge, so the screen's first pixels let the page show through sharp
    // (owner's photo of the physical screen 2026-11-08; a screenshot does not catch it). A band of the
    // page colour, solid at the very top and gone 10 dp down, covers that edge.
    .drawWithContent {
        drawContent()
        val band = 10.dp.toPx()
        drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(0f to page, 0.35f to page.copy(alpha = 0.8f),
            1f to page.copy(alpha = 0f), startY = 0f, endY = band), size = androidx.compose.ui.geometry.Size(size.width, band))
    }
    .padding(bottom = tail)
