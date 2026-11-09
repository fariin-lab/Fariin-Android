package com.fariin.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fariin.app.ui.theme.Fariin

/**
 * THE GLASS ALERT (owner 2026-11-08): every centred alert in the app is one crisp glass card, the
 * way the system alerts look on the newest iPhones: a thick frosted surface (never a washed out,
 * see-through blur), a bright 1dp rim with a fine dark edge outside it, a soft light along the top
 * and a deep shadow. Buttons are grey glass pills; only their words carry a colour.
 *
 * A Dialog is its own window, so the page's backdrop cannot reach it; the crisp look comes from the
 * layered surface instead.
 */

/** How an alert action's words are drawn. */
enum class GlassAlertStyle { Normal, Red, Bold }

/** One button of a [GlassAlert] built from a list. */
class GlassAlertAction(val label: String, val style: GlassAlertStyle = GlassAlertStyle.Normal, val onClick: () -> Unit)

/** The colours of the glass card for a surface [base]. */
private class AlertGlass(val base: Color) {
    val dark = base.luminance() < 0.4f
    // Solid: a Dialog cannot blur the page, and a see-through card only shows the rows behind as a wash.
    val fill = base
    val rim = if (dark) Color.White.copy(alpha = 0.20f) else Color.White
    val edge = if (dark) Color.Black.copy(alpha = 0.55f) else Color.Black.copy(alpha = 0.10f)
    val sheen = if (dark) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.85f)
    val pill = if (dark) Color.White.copy(alpha = 0.13f) else Color(0xFF787880).copy(alpha = 0.13f)
    val pillRim = if (dark) Color.White.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.7f)
    val text = if (dark) Color.White else Color.Black
}

@Composable
private fun glassFor(containerColor: Color): AlertGlass {
    val c = Fariin.colors
    // A call site's own fill is kept only when it asks for something other than the theme card
    // (the call screens pass a fixed dark grey).
    val base = if (containerColor == Color.Unspecified || containerColor == c.card)
        (if (c.dark) Color(0xFF242426) else Color(0xFFF9F9FB)) else containerColor
    return AlertGlass(base)
}

/** The crisp glass surface: shadow, fill, top sheen, white rim and the fine dark edge. */
private fun Modifier.glassCard(g: AlertGlass, shape: Shape, corner: Dp, shadow: Dp): Modifier = this
    .shadow(shadow, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.35f), spotColor = Color.Black.copy(alpha = 0.35f))
    .clip(shape)
    .background(g.fill)
    .drawBehind {
        val r = corner.toPx()
        drawRoundRect(Brush.verticalGradient(0f to g.sheen, 0.35f to g.sheen.copy(alpha = 0f), startY = 0f, endY = size.height),
            cornerRadius = CornerRadius(r, r))
    }
    .border(0.5.dp, g.edge, shape)
    .padding(0.5.dp)
    .border(1.dp, g.rim, RoundedCornerShape(corner - 0.5.dp))

/**
 * Drop-in for material3 AlertDialog: the same parameters, drawn as the glass card. Title and text
 * sit on the left; the buttons become full width grey glass pills, side by side when there are two
 * short ones, else stacked with [confirmButton] on top and [dismissButton] under it. Each button
 * keeps its own words, colour and onClick.
 */
@Composable
fun GlassAlert(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    shape: Shape = RoundedCornerShape(28.dp),
    containerColor: Color = Color.Unspecified,
    iconContentColor: Color = Color.Unspecified,
    titleContentColor: Color = Color.Unspecified,
    textContentColor: Color = Color.Unspecified,
    @Suppress("UNUSED_PARAMETER") tonalElevation: Dp = 0.dp,
    properties: DialogProperties = DialogProperties(),
) {
    val g = glassFor(containerColor)
    val buttons = buildList<@Composable () -> Unit> {
        if (dismissButton != null) add(dismissButton)
        add(confirmButton)
    }
    GlassAlertFrame(onDismissRequest, modifier, shape, g, properties, icon,
        if (iconContentColor == Color.Unspecified) g.text else iconContentColor, title,
        if (titleContentColor == Color.Unspecified) g.text else titleContentColor, text,
        if (textContentColor == Color.Unspecified) g.text.copy(alpha = 0.85f) else textContentColor) {
        AlertButtons(buttons, g, pairs = true)
    }
}

/**
 * The list form: any number of actions, each a full width grey glass pill stacked in order.
 * Tapping one closes the alert ([onDismissRequest]) first, then runs
 * its onClick, unless [dismissOnAction] is false.
 */
@Composable
fun GlassAlert(
    onDismissRequest: () -> Unit,
    title: String,
    actions: List<GlassAlertAction>,
    message: String? = null,
    dismissOnAction: Boolean = true,
    containerColor: Color = Color.Unspecified,
    properties: DialogProperties = DialogProperties(),
) {
    val g = glassFor(containerColor)
    val c = Fariin.colors
    val buttons = actions.map { a ->
        @Composable {
            TextButton({ if (dismissOnAction) onDismissRequest(); a.onClick() }) {
                Text(a.label, color = if (a.style == GlassAlertStyle.Red) c.red else g.text,
                    fontWeight = if (a.style == GlassAlertStyle.Bold) FontWeight.SemiBold else FontWeight.Medium)
            }
        }
    }
    GlassAlertFrame(onDismissRequest, Modifier, RoundedCornerShape(28.dp), g, properties, null, g.text,
        { Text(title) }, g.text, message?.let { m -> { Text(m) } }, g.text.copy(alpha = 0.85f)) {
        AlertButtons(buttons, g, pairs = false)
    }
}

@Composable
private fun GlassAlertFrame(
    onDismissRequest: () -> Unit, modifier: Modifier, shape: Shape, g: AlertGlass, properties: DialogProperties,
    icon: (@Composable () -> Unit)?, iconColor: Color,
    title: (@Composable () -> Unit)?, titleColor: Color,
    text: (@Composable () -> Unit)?, textColor: Color,
    buttons: @Composable () -> Unit,
) {
    Dialog(onDismissRequest, DialogProperties(dismissOnBackPress = properties.dismissOnBackPress,
        dismissOnClickOutside = properties.dismissOnClickOutside, securePolicy = properties.securePolicy,
        usePlatformDefaultWidth = false, decorFitsSystemWindows = properties.decorFitsSystemWindows)) {
        Box(Modifier.padding(horizontal = 36.dp, vertical = 24.dp), contentAlignment = Alignment.Center) {
            Column(modifier.widthIn(max = 330.dp).fillMaxWidth().glassCard(g, shape, 28.dp, 28.dp)
                .padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 16.dp)) {
                if (icon != null) {
                    CompositionLocalProvider(LocalContentColor provides iconColor) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) { icon() }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                if (title != null) {
                    CompositionLocalProvider(LocalContentColor provides titleColor) {
                        ProvideTextStyle(TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp, color = titleColor)) { title() }
                    }
                }
                if (text != null) {
                    if (title != null) Spacer(Modifier.height(8.dp))
                    CompositionLocalProvider(LocalContentColor provides textColor) {
                        ProvideTextStyle(TextStyle(fontSize = 15.sp, lineHeight = 20.sp, color = textColor)) {
                            Box(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) { text() }
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                buttons()
            }
        }
    }
}

/** The buttons as grey glass pills. [pairs] = dismiss + confirm: side by side when both are short, else stacked confirm first. */
@Composable
private fun AlertButtons(buttons: List<@Composable () -> Unit>, g: AlertGlass, pairs: Boolean) {
    val gap = 10.dp
    // The button words in the pill size, keeping each call site's own colour and weight.
    val type = MaterialTheme.typography
    MaterialTheme(typography = type.copy(labelLarge = type.labelLarge.copy(fontSize = 17.sp, fontWeight = FontWeight.Medium,
        letterSpacing = 0.sp))) {
        Layout(content = {
            buttons.forEach { b ->
                // propagateMinConstraints: the call site's TextButton fills the whole pill, so all of it is tappable.
                Box(Modifier.clip(CircleShape).background(g.pill).border(0.5.dp, g.pillRim, CircleShape),
                    propagateMinConstraints = true, contentAlignment = Alignment.Center) { b() }
            }
        }) { ms, cs ->
            val w = cs.maxWidth
            val gapPx = gap.roundToPx()
            val h = 50.dp.roundToPx()
            val half = (w - gapPx) / 2
            val sideBySide = pairs && ms.size == 2 && ms.all { it.maxIntrinsicWidth(h) <= half }
            if (sideBySide) {
                val ps = ms.map { it.measure(Constraints.fixed(half, h)) }
                layout(w, h) { ps[0].place(0, 0); ps[1].place(w - half, 0) }
            } else {
                // Stacked: the main action on top, as the system alerts (the list form keeps its order).
                val ordered = if (pairs && ms.size == 2) listOf(ms[1], ms[0]) else ms
                val ps = ordered.map { it.measure(Constraints.fixed(w, h)) }
                val total = ps.size * h + (ps.size - 1).coerceAtLeast(0) * gapPx
                layout(w, total) { ps.forEachIndexed { i, p -> p.place(0, i * (h + gapPx)) } }
            }
        }
    }
}
