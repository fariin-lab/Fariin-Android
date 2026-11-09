package com.fariin.app.ui.thread

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.fariin.app.ui.theme.Fariin
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/*
 * The chat's scroll edges (iOS ChatHeaderBlurView): the messages that slide under the floating
 * header or the composer go soft and fade into the page colour, with no line where the effect ends.
 *
 * Numbers from iOS: the page colour at 85 % over a light blur, faded along an eased curve over the
 * last 80 dp of the strip, the strip reaching 16 dp past the controls and never under 100 dp tall
 * at the top. The curve is every sixth stop of iOS's 90 (location to strength, 0 = the solid end).
 *
 * Cost: the glass is drawn only when [glass] is on (PowerState.glass(): Android 12+, not a LOW phone,
 * not Battery Saver, blur allowed), from a third-size copy of the list. It stays attached and only
 * its alpha moves (220 ms) as messages arrive under it, so switching it never rebuilds anything,
 * and the alpha is read in the effect's own block, not in composition. Without glass the same curve
 * is drawn in the plain page colour, solid over the controls.
 */

private val edgeCurve = floatArrayOf(
    0.000f, 1.000f, 0.132f, 0.972f, 0.192f, 0.944f, 0.233f, 0.917f, 0.261f, 0.889f, 0.289f, 0.861f,
    0.314f, 0.829f, 0.355f, 0.769f, 0.394f, 0.704f, 0.432f, 0.625f, 0.470f, 0.546f, 0.505f, 0.472f,
    0.547f, 0.389f, 0.589f, 0.306f, 0.659f, 0.190f, 0.697f, 0.139f, 0.791f, 0.056f, 1.000f, 0.000f,
)

/**
 * The fade as a brush in [color], in fractions of the strip's own height (Haze reads a mask in
 * fractions: absolute startY/endY landed off and left a hard line, owner's photo 2026-11-05).
 * Solid down to [solidFrac], then iOS's eased curve to nothing at the bottom.
 */
private fun edgeBrush(color: Color, solidFrac: Float): Brush {
    val n = edgeCurve.size / 2
    val stops = Array(n + 1) { i ->
        if (i == 0) 0f to color
        else {
            val loc = edgeCurve[(i - 1) * 2]
            val a = edgeCurve[(i - 1) * 2 + 1]
            (solidFrac + (1f - solidFrac) * loc).coerceIn(0f, 1f) to color.copy(alpha = color.alpha * a)
        }
    }
    return Brush.verticalGradient(*stops)
}

/**
 * One edge strip. [barPx] is the floating controls' measured height (header cluster or composer);
 * [under] is true while messages sit under them. Place it under the controls and over the list.
 */
/**
 * The chat's top edge as the reference app draws it (owner 2026-11-05, his Pixel photo of it: "the
 * kind soft blur"): no blur on the messages. The page colour (or the wallpaper, see ThreadScreen) is
 * laid over them at 81 % from the screen's top down through the header, so they show faintly, and
 * over the header's last 48 dp it steps down 81 % -> 62 % -> 34 % -> 0, so the bubbles right under
 * the controls are sharp. Plain colour: free for the GPU and the battery, the same on every phone.
 */
internal val EDGE_FADE = 48.dp
internal val EDGE_STOPS = floatArrayOf(0.814f, 0.618f, 0.337f, 0f)

/** The edge as alpha stops over [heightPx]: [EDGE_STOPS] over its last [fadePx]. */
internal fun edgeAlphaBrush(color: Color, heightPx: Float, fadePx: Float): Brush {
    val f = fadePx.coerceAtMost(heightPx)
    val start = ((heightPx - f) / heightPx).coerceIn(0f, 1f)
    return Brush.verticalGradient(
        0f to color.copy(alpha = color.alpha * EDGE_STOPS[0]),
        start to color.copy(alpha = color.alpha * EDGE_STOPS[0]),
        (start + (1f - start) / 3f) to color.copy(alpha = color.alpha * EDGE_STOPS[1]),
        (start + (1f - start) * 2f / 3f) to color.copy(alpha = color.alpha * EDGE_STOPS[2]),
        1f to color.copy(alpha = 0f),
        startY = 0f, endY = heightPx)
}

@Composable
internal fun ThreadEdgeGlass(haze: HazeState?, top: Boolean, barPx: Int, under: Boolean, walled: Boolean = false, modifier: Modifier = Modifier) {
    if (!top) return   // no bottom edge (iOS)
    val c = Fariin.colors
    val density = LocalDensity.current
    val barDp = with(density) { barPx.toDp() }
    if (haze != null) {
        // iOS (owner's iPhone beside the Pixel, 2026-11-05: "you can still see where it ends; Apple
        // never"): a real blur whose radius eases down to nothing, with no colour laid over it, so
        // there is no band to see. Full strength behind the top of the header, gone 32 dp under it.
        // The wallpaper is inside the list's blur source (ThreadScreen), so it blurs along.
        val heightDp = barDp + 32.dp
        val solid = (barDp * 0.55f / heightDp).coerceIn(0f, 0.8f)
        val alpha by com.fariin.app.ui.components.rememberEdgeAlpha(under, startOn = true)
        // The blur slips for single frames in the first ~200 ms after the page appears (owner's
        // Pixel 2026-11-09: a bubble or a photo's card showed sharp under the status bar, "the header
        // blur makes movement"). Until the open has settled the plain edge (the page colour in soft
        // steps) lies over it, then fades into the glass.
        val cover = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(1f) }
        androidx.compose.runtime.LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(BLUR_WARM_MS)
            cover.animateTo(0f, androidx.compose.animation.core.tween(220))
        }
        Box(modifier.fillMaxWidth().height(heightDp).hazeEffect(haze) {
            backgroundColor = c.bg; blurRadius = 18.dp; noiseFactor = 0f
            tints = emptyList()
            // Full size (was a third): the scaled copy dropped out for single frames, drawing the
            // messages sharp under the header (owner's Pixel 2026-11-09, "the header blur makes movement").
            inputScale = HazeInputScale.None
            progressive = com.fariin.app.ui.components.edgeStages(solid)
            this.alpha = alpha
        })
        // Drawn over the blur, so a frame where the blur slips can never show the messages sharp.
        if (!walled) Box(modifier.fillMaxWidth().height(barDp + EDGE_FADE).drawBehind {
            val a2 = cover.value * alpha
            if (a2 > 0f) {
                // Solid behind the bar itself (the 81 % of the plain edge let a slip show faintly), then easing out.
                val bar = (size.height - EDGE_FADE.toPx()).coerceAtLeast(0f) / size.height
                drawRect(Brush.verticalGradient(0f to c.bg, bar to c.bg, (bar + (1f - bar) * 0.5f) to c.bg.copy(alpha = c.bg.alpha * 0.5f),
                    1f to c.bg.copy(alpha = 0f)), alpha = a2)
            }
        })
    } else if (!walled) {
        // No blur on this phone (Android < 12, a LOW phone, Battery Saver): the page colour over the
        // messages in four soft steps, the reference app's cheap edge.
        val heightDp = barDp + EDGE_FADE
        val alpha by com.fariin.app.ui.components.rememberEdgeAlpha(under, startOn = true)
        Box(modifier.fillMaxWidth().height(heightDp).drawBehind {
            if (alpha > 0f) drawRect(edgeAlphaBrush(c.bg, size.height, EDGE_FADE.toPx()), alpha = alpha)
        })
    }
}

/** How long after a chat appears its header blur is taken to be drawing (4 frames seen, with room). */
private const val BLUR_WARM_MS = 450L
