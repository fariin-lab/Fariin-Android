package com.fariin.app.ui.thread

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
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
    val style = BubbleStyles.find(m.bubbleStyle) ?: return null
    if (m.deleted || m.mediaKind != null || m.poll != null) return null
    if (CardRules.location(m.text) != null || CardRules.contact(m.text) != null) return null
    val look = style.look(Fariin.colors.dark)
    val colors = look.fill.map { Color(0xFF000000 or it) }
    // Soft Glass is a live blur on iOS; a blur in the list would cost every scrolled frame, so here
    // it is its colour with the chat showing through a little, and a hairline edge.
    val brush = if (look.glass) androidx.compose.ui.graphics.SolidColor(colors.first().copy(alpha = 0.72f))
        else if (colors.size > 1) Brush.verticalGradient(colors) else androidx.compose.ui.graphics.SolidColor(colors.first())
    return StyledBubble(brush, Color(0xFF000000 or look.text), look.glass)
}

/** The styled fill, with a hairline edge for Soft Glass. */
internal fun Modifier.styledFill(s: StyledBubble, shape: Shape, dark: Boolean): Modifier =
    background(s.fill).then(
        if (s.glass) Modifier.border(0.5.dp, if (dark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.10f), shape)
        else Modifier,
    )

/** Inside a styled bubble: the style's text colour, so the time and ticks follow it (iOS: 62%). */
internal val LocalStyledInk = staticCompositionLocalOf<Color?> { null }
