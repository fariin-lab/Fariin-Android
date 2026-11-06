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
@Composable
internal fun ThreadEdgeGlass(haze: HazeState?, top: Boolean, barPx: Int, under: Boolean, walled: Boolean = false, modifier: Modifier = Modifier) {
    if (!top) return   // no bottom strip (iOS: the owner had the composer's blur removed)
    val c = Fariin.colors
    val density = LocalDensity.current
    val barDp = with(density) { barPx.toDp() }
    // The owner's iPhone photos 2026-11-05: under the header the bubbles are blurred but keep their
    // colour, the blur ends about one bubble below the controls, and only the status bar band is
    // washed with the page colour.
    val heightDp = barDp + 48.dp
    val solidFrac = (barDp / heightDp).coerceIn(0f, 0.9f) * 0.8f
    if (haze != null) {
        val alpha by com.fariin.app.ui.components.rememberEdgeAlpha(under, startOn = top)
        // The status bar band: the page colour from almost solid at the screen's edge to clear 48 dp
        // down, in four steps (the curve the reference app draws over its list). Not over a wallpaper,
        // which is itself in the blur.
        val bandPx = with(density) { 48.dp.toPx() }
        val wash = remember(c.bg, bandPx) {
            Brush.verticalGradient(0f to c.bg.copy(alpha = 0.91f), 0.33f to c.bg.copy(alpha = 0.69f),
                0.66f to c.bg.copy(alpha = 0.38f), 1f to c.bg.copy(alpha = 0f), startY = 0f, endY = bandPx)
        }
        val under0 = remember(solidFrac, c.bg) { edgeBrush(c.bg, solidFrac) }
        Box(modifier.fillMaxWidth().height(heightDp).drawWithContent {
            // Under the glass for its first frame only (it needs one frame to capture the list).
            if (alpha > 0f && !walled) drawRect(under0, alpha = alpha)
            drawContent()
            if (alpha > 0f && !walled) drawRect(wash, alpha = alpha)
        }.hazeEffect(haze) {
            backgroundColor = c.bg; blurRadius = 24.dp; noiseFactor = 0f
            tints = listOf(HazeTint(c.bg.copy(alpha = if (walled) 0.12f else 0.22f)))
            inputScale = HazeInputScale.Fixed(1f / 3)
            progressive = com.fariin.app.ui.components.edgeStages(solidFrac)
            this.alpha = alpha
        })
    } else {
        // No blur: the page colour, solid over the controls, fading out under them.
        val fade = remember(solidFrac, c.bg) { edgeBrush(c.bg, solidFrac) }
        Box(modifier.fillMaxWidth().height(heightDp).drawBehind { drawRect(fade) })
    }
}
