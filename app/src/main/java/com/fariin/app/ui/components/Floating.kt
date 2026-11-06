package com.fariin.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.fariin.app.ui.theme.Fariin
import dev.chrisbanes.haze.hazeEffect
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

/**
 * The chat screen's floating controls (iOS glass: the back circle, the call capsule, the "+", the
 * message pill and the mic). Android has no system glass, so it is the card colour with the same
 * hairline rim and a soft shadow, which is what the glass reads as on a white chat.
 */
/**
 * The chat screen's haze source (its message list), when glass is allowed there. Inside it,
 * [floating] controls are real glass, as iOS's (owner's photos 2026-11-05: the call capsule turns
 * light blue over a blue bubble and yellow over the wallpaper); everywhere else they stay solid.
 */
val LocalGlassHaze = androidx.compose.runtime.staticCompositionLocalOf<dev.chrisbanes.haze.HazeState?> { null }

/**
 * Android 13+ in the chat: the message list as a backdrop for real liquid glass (the backdrop
 * library: lens refraction at the rim, colour vibrancy, specular edge), as the tab bar (owner
 * 2026-11-06). Null elsewhere and on older phones, where [LocalGlassHaze] or solid is used.
 */
val LocalLiquidBackdrop = androidx.compose.runtime.staticCompositionLocalOf<com.kyant.backdrop.backdrops.LayerBackdrop?> { null }

@Composable
fun Modifier.floating(shape: Shape = CircleShape, fill: Color? = null): Modifier {
    val c = Fariin.colors
    val haze = LocalGlassHaze.current
    val liquid = LocalLiquidBackdrop.current
    if (fill == null && liquid != null) {
        // Bends what passes under at the rim and lifts its colours, under a frosted surface; no blur
        // pass (it cost 5 ms a frame on the tab bar). The library draws the shine on the edge.
        // On a plain white chat (owner 2026-11-06: "without wallpaper, white on white is not
        // visible"): a faint grey in the surface, a hairline rim and a clearer shadow, as iOS glass
        // shows on white.
        val frost = if (c.dark) c.card else Color(0xFFF4F4F7)
        return this
            .shadow(12.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.08f), spotColor = Color.Black.copy(alpha = 0.18f))
            .drawBackdrop(
                backdrop = liquid,
                shape = { shape },
                effects = {
                    vibrancy()
                    lens(10.dp.toPx(), 20.dp.toPx())
                },
                shadow = null,
                // Round buttons (circle / capsule) stay clearer; the text field (a rounded rectangle)
                // is thicker so words passing under never mix with "Message".
                onDrawSurface = {
                    val field = shape != CircleShape
                    drawRect(frost.copy(alpha = if (field) 0.92f else if (c.dark) 0.76f else 0.82f))
                },
            )
            .border(0.5.dp, c.label.copy(alpha = if (c.dark) 0.14f else 0.12f), shape)
    }
    if (fill == null && haze != null) {
        // Glass (the reference app's recipe in our own terms): what is behind blurred hard, under
        // the page colour at 76 %, a hairline rim and a soft shadow. A bubble or the wallpaper
        // behind shows through as colour, never as shapes.
        return this
            .shadow(10.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.06f), spotColor = Color.Black.copy(alpha = 0.12f))
            .clip(shape)
            .hazeEffect(haze) {
                backgroundColor = c.bg; blurRadius = 24.dp; noiseFactor = 0f
                tints = listOf(dev.chrisbanes.haze.HazeTint(c.card.copy(alpha = 0.76f)))
                // A quarter-size copy: under a hard blur and 76 % colour it reads the same, at a fraction of
                // the GPU work per frame while the list scrolls behind (measured 2026-11-06).
                inputScale = dev.chrisbanes.haze.HazeInputScale.Fixed(0.25f)
            }
            .border(0.5.dp, c.label.copy(alpha = if (c.dark) 0.10f else 0.08f), shape)
    }
    return this
        .shadow(12.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.05f), spotColor = Color.Black.copy(alpha = 0.10f))
        .clip(shape)
        .background(fill ?: c.card)
        // The rim is barely there, as the glass edge is: the text colour at 7% (dark in light, light in dark).
        .border(0.5.dp, c.label.copy(alpha = 0.07f), shape)
}
