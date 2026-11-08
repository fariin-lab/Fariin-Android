package com.fariin.app.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.ui.theme.Fariin

/**
 * The round action on an info page (iOS PosterActionIcon): a 60 dp glass circle with the action's
 * name 8 dp under it, used by the channel, group and contact pages so they share one look.
 *
 * iOS numbers: the circle is 60; its own drawn icons are 0.42 of it and a symbol is drawn at 0.36
 * of it in medium weight (a stock outline icon sits in a padded box, so it gets 0.45 to come out the
 * same size); glyph and name in the text colour; the name at 13 regular, one line. Each circle takes
 * an equal share of the row (pass `Modifier.weight(1f)`), so three, four or five stay balanced.
 *
 * The press is the glass's own: the circle swells a little on a spring and lightens, no ripple.
 */
@Composable
fun GlassActionCircle(
    label: String,
    modifier: Modifier = Modifier,
    diameter: Dp = 60.dp,
    dimmed: Boolean = false,
    enabled: Boolean = true,
    tint: Color? = null,
    onClick: () -> Unit,
    glyph: @Composable (color: Color) -> Unit,
) {
    val c = Fariin.colors
    val color = tint ?: c.label
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 1.1f else 1f,
        spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow), label = "glassPress")
    Column(
        modifier
            .alpha(if (dimmed) 0.45f else 1f)
            .clickable(interactionSource = press, indication = null, enabled = enabled, role = Role.Button,
                onClickLabel = label, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.size(diameter)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .floating(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            // The glass lightening under the finger.
            if (pressed) Box(Modifier.fillMaxSize().background(if (c.dark) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.35f), CircleShape))
            glyph(color)
        }
        Text(label, fontSize = 13.sp, color = color, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    }
}

/** One of Fariin's own drawn icons (the iOS "ic_*" set), at 0.42 of the circle. */
@Composable
fun GlassActionCircle(label: String, painter: Painter, modifier: Modifier = Modifier, diameter: Dp = 60.dp,
                      dimmed: Boolean = false, enabled: Boolean = true, tint: Color? = null, onClick: () -> Unit) =
    GlassActionCircle(label, modifier, diameter, dimmed, enabled, tint, onClick) { col ->
        Icon(painter, contentDescription = null, tint = col, modifier = Modifier.size(diameter * 0.42f))
    }

/** A stock outline icon standing in for the iOS symbol, sized to the symbol's drawn size. */
@Composable
fun GlassActionCircle(label: String, icon: ImageVector, modifier: Modifier = Modifier, diameter: Dp = 60.dp,
                      dimmed: Boolean = false, enabled: Boolean = true, tint: Color? = null, onClick: () -> Unit) =
    GlassActionCircle(label, modifier, diameter, dimmed, enabled, tint, onClick) { col ->
        Icon(icon, contentDescription = null, tint = col, modifier = Modifier.size(diameter * 0.45f))
    }
