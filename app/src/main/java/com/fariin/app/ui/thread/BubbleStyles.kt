package com.fariin.app.ui.thread

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.fariin.app.data.BubbleStyles
import com.fariin.app.data.CardRules
import com.fariin.app.data.Message
import com.fariin.app.ui.theme.Fariin

/** A text bubble drawn in the sender's bubble style: its fill and its own text colour. */
internal class StyledBubble(val fill: Brush, val ink: Color, val glass: Boolean)

/**
 * The style a text bubble draws in, or null for the normal bubble: an unknown id, a deleted
 * message, or a bubble that is not plain words (media, poll, contact or location card).
 */
@Composable
internal fun styledBubble(m: Message): StyledBubble? {
    if (!BubbleStyles.SHOWN) return null
    val style = BubbleStyles.find(m.bubbleStyle) ?: return null
    if (m.deleted || m.mediaKind != null || m.poll != null) return null
    if (CardRules.location(m.text) != null || CardRules.contact(m.text) != null) return null
    val look = style.look(Fariin.colors.dark)
    val colors = look.fill.map { Color(0xFF000000 or it) }
    // Soft Glass is a live blur on iOS; a blur in the list would cost every scrolled frame, so here
    // it is its colour with the chat showing through a little, and a hairline edge. With no
    // wallpaper behind there is nothing to see through: its soft colour, solid, still with the edge.
    val brush = if (look.glass) androidx.compose.ui.graphics.SolidColor(
            if (LocalOnWallpaper.current) colors.first().copy(alpha = 0.72f) else colors.first())
        else if (colors.size > 1) Brush.verticalGradient(colors) else androidx.compose.ui.graphics.SolidColor(colors.first())
    return StyledBubble(brush, Color(0xFF000000 or look.text), look.glass)
}

/** The styled fill, with a hairline edge for Soft Glass. */
internal fun Modifier.styledFill(s: StyledBubble, shape: Shape, dark: Boolean): Modifier =
    background(s.fill).then(
        if (s.glass) Modifier.border(0.5.dp, if (dark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.10f), shape)
        else Modifier,
    )

/** Inside a styled bubble: the style's text colour, so the time and ticks follow it (iOS: 80%). */
internal val LocalStyledInk = staticCompositionLocalOf<Color?> { null }

/**
 * The corner decorations one bubble draws (BubbleStyles.placed): only when the sender chose them,
 * on a styled text bubble that ends its run, not on a failed send, and never over a group avatar.
 */
internal fun bubbleDecorFor(m: Message, styled: StyledBubble?, isMe: Boolean, dark: Boolean, first: Boolean,
                            last: Boolean, failed: Boolean, besideAvatar: Boolean): List<BubbleStyles.Placed> =
    if (styled == null) emptyList()
    else BubbleStyles.placed(m.bubbleStyle, m.bubbleDecor, isMe, dark, first, last, failed, besideAvatar)

/**
 * Draws the decorations at the bubble's corners, hanging a little outside it. Put it BEFORE the
 * bubble's clip so the art is not cut off. Each piece casts a faint shadow so it reads on any
 * wallpaper (iOS: a soft blurred shadow; here a plain offset copy, which costs nothing to draw).
 * Drawn at once, never animated in.
 */
internal fun Modifier.bubbleDecor(list: List<BubbleStyles.Placed>): Modifier =
    if (list.isEmpty()) this else drawWithCache {
        val rtl = layoutDirection == LayoutDirection.Rtl
        val wDp = size.width / density
        val hDp = size.height / density
        val pieces = list.map { p ->
            val art = decorArt(p.glyph, p.size * density, Color(0xFF000000 or p.color))
            var (cx, cy) = BubbleStyles.centre(p, wDp, hDp)
            if (rtl) cx = wDp - cx
            Triple(art, Offset(cx * density, cy * density), if (rtl) -p.rotation else p.rotation)
        }
        val shadow = Color.Black.copy(alpha = 0.16f)
        val drop = 1f * density
        onDrawWithContent {
            drawContent()
            for ((art, centre, rot) in pieces) {
                withTransform({
                    rotate(Math.toDegrees(rot.toDouble()).toFloat(), centre)
                    translate(centre.x - art.width / 2f, centre.y - art.height / 2f)
                }) {
                    translate(0f, drop) { for ((path, _) in art.parts) drawPath(path, shadow) }
                    for ((path, color) in art.parts) drawPath(path, color)
                }
            }
        }
    }

/** One decoration's art: filled paths with their colours, in a box [width] by [height] px. */
private class DecorArt(val width: Float, val height: Float, val parts: List<Pair<Path, Color>>)

/**
 * The art, drawn in code like iOS. There it is system symbols plus our own bow and strawberry; the
 * symbols here are our own simple shapes of the same things (heart, sparkle, sparkles, blossom,
 * leaf, cloud, star). The bow and strawberry use the iOS curves.
 */
private fun decorArt(g: BubbleStyles.Glyph, s: Float, color: Color): DecorArt = when (g) {
    BubbleStyles.Glyph.HEART -> DecorArt(s, s, listOf(heart(s) to color))
    BubbleStyles.Glyph.SPARKLE -> DecorArt(s, s, listOf(Path().apply { sparkle(s * 0.5f, s * 0.5f, s * 0.5f) } to color))
    BubbleStyles.Glyph.SPARKLES -> DecorArt(s, s, listOf(Path().apply {
        sparkle(s * 0.6f, s * 0.6f, s * 0.4f)
        sparkle(s * 0.2f, s * 0.22f, s * 0.18f)
        sparkle(s * 0.86f, s * 0.13f, s * 0.12f)
    } to color))
    BubbleStyles.Glyph.STAR -> DecorArt(s, s, listOf(star(s) to color))
    BubbleStyles.Glyph.CLOUD -> DecorArt(s, s * 0.8f, listOf(Path().apply {
        addOval(Rect(Offset(s * 0.32f, s * 0.42f), s * 0.2f))
        addOval(Rect(Offset(s * 0.56f, s * 0.32f), s * 0.25f))
        addOval(Rect(Offset(s * 0.77f, s * 0.46f), s * 0.18f))
        addRoundRect(RoundRect(s * 0.12f, s * 0.42f, s * 0.95f, s * 0.64f, CornerRadius(s * 0.11f)))
    } to color))
    BubbleStyles.Glyph.LEAF -> DecorArt(s, s, listOf(
        Path().apply {
            moveTo(s * 0.16f, s * 0.84f)
            quadraticTo(s * 0.08f, s * 0.08f, s * 0.9f, s * 0.1f)
            quadraticTo(s * 0.92f, s * 0.92f, s * 0.16f, s * 0.84f)
            close()
            // A short stem out of the bottom corner.
            moveTo(s * 0.12f, s * 0.8f); lineTo(s * 0.2f, s * 0.88f); lineTo(s * 0.06f, s * 1.0f); lineTo(s * 0.0f, s * 0.94f); close()
        } to color,
        // The middle vein, a thin lighter line.
        Path().apply {
            moveTo(s * 0.2f, s * 0.8f); lineTo(s * 0.78f, s * 0.2f); lineTo(s * 0.8f, s * 0.24f); lineTo(s * 0.24f, s * 0.82f); close()
        } to Color.White.copy(alpha = 0.35f),
    ))
    BubbleStyles.Glyph.BLOSSOM -> DecorArt(s, s, listOf(
        Path().apply {
            for (k in 0 until 5) {
                val a = Math.toRadians(-90.0 + 72.0 * k)
                addOval(Rect(Offset(s * (0.5f + 0.27f * Math.cos(a).toFloat()), s * (0.5f + 0.27f * Math.sin(a).toFloat())), s * 0.21f))
            }
        } to color,
        Path().apply { addOval(Rect(Offset(s * 0.5f, s * 0.5f), s * 0.13f)) } to Color(0xFFFFF3C4),
    ))
    BubbleStyles.Glyph.BOW -> bow(s, color)
    BubbleStyles.Glyph.STRAWBERRY -> strawberry(s, color)
}

private fun heart(s: Float) = Path().apply {
    moveTo(s * 0.5f, s * 0.92f)
    cubicTo(s * 0.2f, s * 0.72f, s * 0.02f, s * 0.58f, s * 0.02f, s * 0.36f)
    cubicTo(s * 0.02f, s * 0.12f, s * 0.36f, s * 0.02f, s * 0.5f, s * 0.24f)
    cubicTo(s * 0.64f, s * 0.02f, s * 0.98f, s * 0.12f, s * 0.98f, s * 0.36f)
    cubicTo(s * 0.98f, s * 0.58f, s * 0.8f, s * 0.72f, s * 0.5f, s * 0.92f)
    close()
}

/** A four-pointed sparkle with curved-in sides, centred on [cx], [cy], tip to centre [r]. */
private fun Path.sparkle(cx: Float, cy: Float, r: Float) {
    val k = r * 0.12f
    moveTo(cx, cy - r)
    quadraticTo(cx + k, cy - k, cx + r, cy)
    quadraticTo(cx + k, cy + k, cx, cy + r)
    quadraticTo(cx - k, cy + k, cx - r, cy)
    quadraticTo(cx - k, cy - k, cx, cy - r)
    close()
}

private fun star(s: Float) = Path().apply {
    val c = s * 0.5f
    for (i in 0 until 10) {
        val r = if (i % 2 == 0) s * 0.5f else s * 0.21f
        val a = Math.toRadians(-90.0 + 36.0 * i)
        val x = c + r * Math.cos(a).toFloat()
        val y = c + 0.04f * s + r * Math.sin(a).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

/** Two rounded loops, a knot and two short tails (iOS BubbleDecorArt.bow, same curves). */
private fun bow(s: Float, color: Color): DecorArt {
    val w = s
    val h = s * 0.75f
    val loops = Path().apply {
        moveTo(w * 0.5f, h * 0.42f)
        cubicTo(w * 0.3f, h * 0.02f, w * 0.06f, h * 0.0f, w * 0.04f, h * 0.14f)
        cubicTo(w * 0.0f, h * 0.62f, w * 0.3f, h * 0.7f, w * 0.5f, h * 0.52f)
        close()
        moveTo(w * 0.5f, h * 0.42f)
        cubicTo(w * 0.7f, h * 0.02f, w * 0.94f, h * 0.0f, w * 0.96f, h * 0.14f)
        cubicTo(w * 1.0f, h * 0.62f, w * 0.7f, h * 0.7f, w * 0.5f, h * 0.52f)
        close()
        moveTo(w * 0.46f, h * 0.5f); lineTo(w * 0.3f, h * 0.98f); lineTo(w * 0.42f, h * 0.9f); close()
        moveTo(w * 0.54f, h * 0.5f); lineTo(w * 0.7f, h * 0.98f); lineTo(w * 0.58f, h * 0.9f); close()
    }
    val ring = Path().apply { addOval(Rect(w * 0.42f, h * 0.34f, w * 0.58f, h * 0.56f)) }
    val knot = Path().apply { addOval(Rect(w * 0.44f, h * 0.36f, w * 0.56f, h * 0.54f)) }
    return DecorArt(w, h, listOf(loops to color, ring to Color.White.copy(alpha = 0.35f), knot to color))
}

/** A rounded berry with a green cap and pale seeds (iOS BubbleDecorArt.strawberry, same curves). */
private fun strawberry(s: Float, color: Color): DecorArt {
    val body = Path().apply {
        moveTo(s * 0.5f, s * 0.98f)
        cubicTo(s * 0.22f, s * 0.86f, s * 0.02f, s * 0.62f, s * 0.08f, s * 0.38f)
        cubicTo(s * 0.2f, s * 0.18f, s * 0.8f, s * 0.18f, s * 0.92f, s * 0.38f)
        cubicTo(s * 0.98f, s * 0.62f, s * 0.78f, s * 0.86f, s * 0.5f, s * 0.98f)
        close()
    }
    val seeds = Path().apply {
        for ((x, y) in listOf(0.32f to 0.5f, 0.5f to 0.46f, 0.68f to 0.5f, 0.4f to 0.66f, 0.6f to 0.66f, 0.5f to 0.8f)) {
            addOval(Rect(s * x - s * 0.03f, s * y - s * 0.04f, s * x + s * 0.03f, s * y + s * 0.04f))
        }
    }
    val cap = Path()
    for ((x, r) in listOf(0.3f to -0.5f, 0.5f to 0f, 0.7f to 0.5f)) {
        // A leaf oval standing on the berry's top, turned outwards.
        val leaf = Path().apply { addOval(Rect(-s * 0.08f, -s * 0.16f, s * 0.08f, s * 0.14f)) }
        leaf.transform(Matrix().apply { rotateZ(Math.toDegrees(r.toDouble()).toFloat()) })
        leaf.translate(Offset(s * x, s * 0.24f))
        cap.addPath(leaf)
    }
    return DecorArt(s, s, listOf(body to color, seeds to Color(0xF2FFF2BF), cap to Color(0xFF4DAD52)))
}
