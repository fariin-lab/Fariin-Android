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
import com.kyant.backdrop.effects.blur
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

/**
 * The window the glass sources live in (the chat's own view). Sheets, menus and dialogs open in
 * windows of their own; a glass button there would sample a layer from another window (bubbles
 * showing through a solid sheet, and a render fault risk), so there it draws solid. Review 2026-11-06.
 */
val LocalGlassWindow = androidx.compose.runtime.staticCompositionLocalOf<android.view.View?> { null }

/**
 * The glass surface's colour where the page under it is not white. The chat's faint grey frost
 * reads on a white chat, but on the grey Settings page it was the page's own colour; there the
 * page host ([GlassBarPage]) asks for the card white instead, as the solid circle was. Null keeps
 * the chat's frost.
 */
val LocalGlassFrost = androidx.compose.runtime.staticCompositionLocalOf<Color?> { null }

/** On a frosted bar (GlassBarPage frosted): the buttons' glass blurs too, so nothing readable shows
 *  through them (owner 2026-11-08: the rows read sharp through Edit and All/Missed on Calls). */
val LocalGlassBlur = androidx.compose.runtime.staticCompositionLocalOf { false }

@Composable
fun Modifier.floating(shape: Shape = CircleShape, fill: Color? = null): Modifier {
    val c = Fariin.colors
    val sameWindow = LocalGlassWindow.current?.let { it.rootView === androidx.compose.ui.platform.LocalView.current.rootView } ?: true
    val haze = LocalGlassHaze.current?.takeIf { sameWindow }
    val liquid = LocalLiquidBackdrop.current?.takeIf { sameWindow }
    if (fill == null && liquid != null) {
        // Bends what passes under at the rim and lifts its colours, under a frosted surface; no blur
        // pass (it cost 5 ms a frame on the tab bar). The library draws the shine on the edge.
        // On a plain white chat (owner 2026-11-06: "without wallpaper, white on white is not
        // visible"): a faint grey in the surface, a hairline rim and a clearer shadow, as iOS glass
        // shows on white.
        val frost = LocalGlassFrost.current ?: if (c.dark) c.card else Color(0xFFF4F4F7)
        val blurred = LocalGlassBlur.current
        return this
            .shadow(12.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.08f), spotColor = Color.Black.copy(alpha = 0.18f))
            .drawBackdrop(
                backdrop = liquid,
                shape = { shape },
                effects = {
                    vibrancy()
                    if (blurred) blur(8.dp.toPx())
                    lens(10.dp.toPx(), 20.dp.toPx())
                },
                shadow = null,
                // No library rim: with our hairline it drew a second, offset outline (owner's photo
                // 2026-11-06, "it has something"); on white its shine is invisible anyway.
                highlight = null,
                // Round buttons (circle / capsule) stay clearer; the text field (a rounded rectangle)
                // is thicker so words passing under never mix with "Message".
                onDrawSurface = {
                    val field = shape != CircleShape
                    drawRect(frost.copy(alpha = if (field) 0.92f else if (c.dark) 0.76f else 0.82f))
                },
            )
            // A soft white rim, as the iPhone's buttons (owner 2026-11-08: ours had a dark outline).
            .border(if (c.dark) 0.5.dp else 1.dp, if (c.dark) Color.White.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.95f), shape)
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
    if (com.fariin.app.perf.rememberPowerState().cheapDrawing) {
        // A LOW phone: no soft shadow (it is worked out again on every frame while the list moves
        // under the button); a firmer rim keeps a white circle visible on a white page instead.
        return this
            .background(fill ?: c.card, shape)
            .border(1.dp, c.label.copy(alpha = if (fill == null) 0.12f else 0.07f), shape)
            .clip(shape)
    }
    return this
        .shadow(12.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.05f), spotColor = Color.Black.copy(alpha = 0.10f))
        .clip(shape)
        // Off the glass, the same look as the glass buttons (owner 2026-11-08: the Stories "..." was a faint
        // white circle on a white page): their soft grey frost and the white rim.
        .background(fill ?: if (c.dark) c.card else Color(0xFFF4F4F7))
        .border(if (c.dark || fill != null) 0.5.dp else 1.dp,
            if (fill != null) c.label.copy(alpha = 0.07f) else if (c.dark) Color.White.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.95f), shape)
}
