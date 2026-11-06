package com.fariin.app.ui.thread

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
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

/** The fade as a brush in [color]: solid at the controls' side, nothing at the open end. */
private fun edgeBrush(color: Color, heightPx: Float, edgePx: Float, top: Boolean): Brush {
    val n = edgeCurve.size / 2
    val stops = Array(n) { i ->
        val loc = edgeCurve[i * 2]
        val a = edgeCurve[i * 2 + 1]
        // Top strip: solid above, fading downward. Bottom strip: the mirror, fading upward.
        if (top) loc to color.copy(alpha = color.alpha * a)
        else (1f - edgeCurve[(n - 1 - i) * 2]) to color.copy(alpha = color.alpha * edgeCurve[(n - 1 - i) * 2 + 1])
    }
    return if (top) Brush.verticalGradient(*stops, startY = heightPx - edgePx, endY = heightPx)
    else Brush.verticalGradient(*stops, startY = 0f, endY = edgePx)
}

/**
 * One edge strip. [barPx] is the floating controls' measured height (header cluster or composer);
 * [under] is true while messages sit under them. Place it under the controls and over the list.
 */
@Composable
internal fun ThreadEdgeGlass(haze: HazeState?, top: Boolean, barPx: Int, under: Boolean, modifier: Modifier = Modifier) {
    val c = Fariin.colors
    val density = LocalDensity.current
    val barDp = with(density) { barPx.toDp() }
    val heightDp = if (top) maxOf(100.dp, barDp + 16.dp) else barDp + 16.dp
    val heightPx = with(density) { heightDp.toPx() }
    val edgePx = minOf(with(density) { 80.dp.toPx() }, heightPx)
    if (haze != null) {
        val alpha = animateFloatAsState(if (under) 1f else 0f, tween(220), label = "edgeGlass")
        val mask = remember(heightPx, edgePx, top) { edgeBrush(Color.Black, heightPx, edgePx, top) }
        Box(modifier.fillMaxWidth().height(heightDp).hazeEffect(haze) {
            backgroundColor = c.bg; blurRadius = 8.dp; noiseFactor = 0f
            tints = listOf(HazeTint(c.bg.copy(alpha = 0.85f)))
            inputScale = HazeInputScale.Fixed(1f / 3)
            this.mask = mask
            this.alpha = alpha.value
        })
    } else {
        // No blur to hide a bubble behind the name or the field, so the page colour stays solid over
        // the controls and only the 24 dp past them fades (the owner's 2026-11-04 ask).
        val plainDp = barDp + 24.dp
        val plainPx = with(density) { plainDp.toPx() }
        val fade = remember(plainPx, top, c.bg) { edgeBrush(c.bg, plainPx, with(density) { 24.dp.toPx() }, top) }
        Box(modifier.fillMaxWidth().height(plainDp).drawBehind { drawRect(fade) })
    }
}
