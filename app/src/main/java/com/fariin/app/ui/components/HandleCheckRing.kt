package com.fariin.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.ui.theme.Fariin

enum class CheckPhase { Checking, Free, Taken }

private val CheckGreen = Color(0xFF34C759)

/**
 * The username and link check as ONE ring (iOS HandleCheckRing; owner 2026-11-09: "it looks super
 * cool, does Android have it"): a thin track with an arc running round it while the server is
 * asked, then the arc closes into a full green circle with a tick drawing itself in, or a red one
 * with a cross. 20 dp, the size of the field's other marks.
 */
@Composable
fun HandleCheckRing(phase: CheckPhase, modifier: Modifier = Modifier) {
    val c = Fariin.colors
    val tint = when (phase) { CheckPhase.Checking -> c.label; CheckPhase.Free -> CheckGreen; CheckPhase.Taken -> c.red }
    val track = c.label.copy(alpha = 0.14f)
    val close = remember { Animatable(0f) }
    val mark = remember { Animatable(0f) }
    LaunchedEffect(phase) {
        if (phase == CheckPhase.Checking) { close.snapTo(0f); mark.snapTo(0f); return@LaunchedEffect }
        close.snapTo(0f); mark.snapTo(0f)
        close.animateTo(1f, tween(220, easing = FastOutSlowInEasing))
        mark.animateTo(1f, tween(250, easing = FastOutSlowInEasing))
    }
    // The spin only runs while checking (it is composed only then).
    val angle = if (phase == CheckPhase.Checking) spinAngle() else 0f
    Canvas(modifier.size(20.dp).semantics {
        contentDescription = when (phase) { CheckPhase.Checking -> "Checking"; CheckPhase.Free -> "Available"; CheckPhase.Taken -> "Taken" }
    }) {
        val sw = 2.2.dp.toPx()
        val tl = Offset(sw / 2, sw / 2)
        val box = Size(size.width - sw, size.height - sw)
        drawArc(track, 0f, 360f, false, tl, box, style = Stroke(sw))
        if (phase == CheckPhase.Checking) {
            drawArc(tint, angle - 90f, 0.28f * 360f, false, tl, box, style = Stroke(sw, cap = StrokeCap.Round))
            return@Canvas
        }
        drawArc(tint, -90f, 360f * (0.28f + 0.72f * close.value), false, tl, box, style = Stroke(sw, cap = StrokeCap.Round))
        val w = size.width; val h = size.height
        if (phase == CheckPhase.Free) {
            val full = Path().apply { moveTo(0.30f * w, 0.52f * h); lineTo(0.45f * w, 0.66f * h); lineTo(0.71f * w, 0.37f * h) }
            val pm = PathMeasure().apply { setPath(full, false) }
            val part = Path()
            pm.getSegment(0f, pm.length * mark.value, part, true)
            drawPath(part, tint, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
        } else {
            val a = tint.copy(alpha = tint.alpha * mark.value)
            drawLine(a, Offset(0.37f * w, 0.37f * h), Offset(0.63f * w, 0.63f * h), sw, StrokeCap.Round)
            drawLine(a, Offset(0.63f * w, 0.37f * h), Offset(0.37f * w, 0.63f * h), sw, StrokeCap.Round)
        }
    }
}

@Composable
private fun spinAngle(): Float {
    val t = rememberInfiniteTransition(label = "checkRing")
    val a by t.animateFloat(0f, 360f, infiniteRepeatable(tween(800, easing = LinearEasing)), label = "angle")
    return a
}

/** The ring and its words on one line under a field: grey while checking, green free, red taken. */
@Composable
fun CheckLine(phase: CheckPhase, text: String, modifier: Modifier = Modifier) {
    val c = Fariin.colors
    val color = when (phase) { CheckPhase.Checking -> c.secondary; CheckPhase.Free -> CheckGreen; CheckPhase.Taken -> c.red }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        HandleCheckRing(phase)
        Text(text, fontSize = 13.sp, color = color)
    }
}
