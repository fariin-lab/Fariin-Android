package com.fariin.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.ui.theme.Fariin

/*
 * THE ONE SPINNER (iOS BusyArc.swift / FariinSpinnerStyle). Every wait in the app uses these, never
 * Material's CircularProgressIndicator:
 *  - the ring: one arc of 78% of a circle (a 22% gap), round ends, one full turn every 1.1 s at an
 *    even speed, in the label colour (black on light, white on dark) unless the spot asks otherwise;
 *  - next to words: [LoadingLine], the small ring and grey words on one line ("Checking username…");
 *  - in a button: [ButtonSpinner] takes the label's place INSIDE the button, the button keeps its
 *    size and fill (iOS BusyArc.small in onFariinAction);
 *  - a page with nothing to show yet: [PageLoading], the ring alone in the middle;
 *  - a long job that must not be interrupted (Signing Out…): [BusyOverlay], the page fades to its
 *    own background with the ring and the words in the middle.
 * Progress rings that show a real fraction (media uploads) are not spinners and stay as they are.
 */

/** The turning ring. [size] is the whole box, the stroke sits inside it. */
@Composable
fun FariinSpinner(
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    stroke: Dp = maxOf(2.dp, size / 8),
    color: Color = Fariin.colors.label,
) {
    val turn by rememberInfiniteTransition(label = "spinner").animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "turn",
    )
    Canvas(modifier.size(size).semantics { contentDescription = "Loading" }) {
        val w = stroke.toPx()
        val d = this.size.minDimension - w
        rotate(turn) {
            drawArc(
                color = color, startAngle = -90f, sweepAngle = 0.78f * 360f, useCenter = false,
                topLeft = Offset((this.size.width - d) / 2f, (this.size.height - d) / 2f), size = Size(d, d),
                style = Stroke(width = w, cap = StrokeCap.Round),
            )
        }
    }
}

/** The ring that sits inside a busy button in place of its words (iOS BusyArc.small: 22, stroke 2.5). */
@Composable
fun ButtonSpinner(color: Color, modifier: Modifier = Modifier, size: Dp = 22.dp) =
    FariinSpinner(modifier, size = size, stroke = 2.5.dp, color = color)

/** The small ring and grey words on one line, under a field or in a row ("Checking username…"). */
@Composable
fun LoadingLine(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 13.sp,
    color: Color = Fariin.colors.secondary,
    spinnerColor: Color = Fariin.colors.label,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FariinSpinner(size = 18.dp, stroke = 2.5.dp, color = spinnerColor)
        Text(text, fontSize = fontSize, color = color)
    }
}

/**
 * A word button that is working: the ring sits in the middle of the button and the words stay
 * laid out but unseen, so the button does not change size while it waits.
 */
@Composable
fun BusyContent(busy: Boolean, spinnerColor: Color, size: Dp = 20.dp, content: @Composable () -> Unit) {
    Box(contentAlignment = Alignment.Center) {
        Box(Modifier.alpha(if (busy) 0f else 1f)) { content() }
        if (busy) ButtonSpinner(spinnerColor, size = size)
    }
}

/**
 * A page bar's action capsule while it works: the same floating capsule as [FloatingTextAction],
 * the ring inside it. With [text] ("Saving…") for a page that saves by itself and has no button,
 * so the bar never holds a lone ring.
 */
@Composable
fun BarBusyPill(text: String? = null, modifier: Modifier = Modifier) {
    val c = Fariin.colors
    Row(modifier.padding(end = 4.dp).height(44.dp).floating(CircleShape).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ButtonSpinner(c.label, size = 20.dp)
        if (text != null) Text(text, fontSize = 15.sp, color = c.secondary)
    }
}

/** A page with nothing to show yet: the ring alone in the middle, with optional words under it. */
@Composable
fun PageLoading(modifier: Modifier = Modifier.fillMaxSize(), text: String? = null, color: Color = Fariin.colors.label) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            FariinSpinner(size = 30.dp, stroke = 3.dp, color = color)
            if (text != null) Text(text, fontSize = 15.sp, color = Fariin.colors.secondary, modifier = Modifier.padding(top = 12.dp))
        }
    }
}

/** A row in a list that is still loading more (the bottom of a long list): the ring centred. */
@Composable
fun LoadingRow(modifier: Modifier = Modifier, color: Color = Fariin.colors.label) {
    Box(modifier.fillMaxWidth().padding(vertical = 18.dp), contentAlignment = Alignment.Center) {
        FariinSpinner(size = 22.dp, color = color)
    }
}

/**
 * The page-wide busy state (iOS BusyOverlay): the page turns to its own plain background with the
 * ring (44, stroke 5) and what is happening in words. Swallows touches while it shows.
 */
@Composable
fun BusyOverlay(text: String, modifier: Modifier = Modifier) {
    val c = Fariin.colors
    Box(
        modifier.fillMaxSize().background(c.bg)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            FariinSpinner(size = 44.dp, stroke = 5.dp, color = c.label)
            Text(text, fontSize = 17.sp, fontWeight = FontWeight.Normal, color = c.label, modifier = Modifier.padding(top = 16.dp))
        }
    }
}
