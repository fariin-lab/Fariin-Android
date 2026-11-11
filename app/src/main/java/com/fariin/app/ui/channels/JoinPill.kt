package com.fariin.app.ui.channels

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.ui.theme.Fariin

/** What the Explore list's pill says. */
enum class JoinPillState { JOIN, JOINING, JOINED, REQUESTED }

/**
 * The Join pill in Explore (owner 2026-11-11: "the tap is not rewarding, it just happened"). It dips
 * under the finger with a light tick; while the server works a small spinner sits inside; joined, the
 * fill turns grey, a check draws itself in before "Joined" and the pill gives one small bounce; a
 * refusal shakes it back to Join. [shake] goes up by one for each refusal.
 */
@Composable
fun JoinPill(state: JoinPillState, shake: Int, channel: Boolean = false, onClick: () -> Unit) {
    val words = if (channel) listOf("Follow", "Following") else listOf("Join", "Joined")
    val c = Fariin.colors
    val view = LocalView.current
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val dip by animateFloatAsState(if (pressed) 0.94f else 1f, spring(dampingRatio = 0.6f, stiffness = 900f), label = "joinDip")
    val bounce = remember { Animatable(1f) }
    val shakeX = remember { Animatable(0f) }
    val filled = state == JoinPillState.JOIN || state == JoinPillState.JOINING
    // Only a pill that was waiting on the server celebrates: an old member's "Joined" is drawn
    // plain, with no check and no bounce (iOS).
    var wasJoining by remember { mutableStateOf(false) }
    var celebrate by remember { mutableStateOf(false) }
    LaunchedEffect(pressed) {
        if (pressed) view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 27) HapticFeedbackConstants.KEYBOARD_PRESS else HapticFeedbackConstants.KEYBOARD_TAP)
    }
    val fill by animateColorAsState(if (filled) c.accent else c.fieldFill, tween(220), label = "joinFill")
    LaunchedEffect(state) {
        if (state == JoinPillState.JOINING) { wasJoining = true; return@LaunchedEffect }
        val done = state == JoinPillState.JOINED || state == JoinPillState.REQUESTED
        if (!done || !wasJoining) { if (state == JoinPillState.JOIN) { wasJoining = false; celebrate = false }; return@LaunchedEffect }
        wasJoining = false
        celebrate = state == JoinPillState.JOINED
        run {
            view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
            bounce.animateTo(1.06f, tween(110))
            bounce.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 600f))
        }
    }
    val px = with(LocalDensity.current) { 6.dp.toPx() }
    LaunchedEffect(shake) {
        if (shake == 0) return@LaunchedEffect
        if (Build.VERSION.SDK_INT >= 30) view.performHapticFeedback(HapticFeedbackConstants.REJECT)
        for (x in listOf(px, -px, px * 0.7f, -px * 0.7f, px * 0.35f, 0f)) shakeX.animateTo(x, tween(45))
    }
    Box(
        Modifier.graphicsLayer {
            val s = dip * bounce.value
            scaleX = s; scaleY = s; translationX = shakeX.value
        }.height(34.dp).widthIn(min = 84.dp).clip(CircleShape).background(fill)
            .clickable(interactionSource = press, indication = null, enabled = state == JoinPillState.JOIN, onClick = onClick)
            .semantics { contentDescription = when (state) {
                JoinPillState.JOIN -> words[0]; JoinPillState.JOINING -> if (channel) "Following" else "Joining"
                JoinPillState.JOINED -> words[1]; JoinPillState.REQUESTED -> "Requested"
            } }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(state, transitionSpec = {
            (fadeIn(tween(160)) + scaleIn(tween(200), initialScale = 0.85f)) togetherWith fadeOut(tween(120))
        }, label = "joinWord") { st ->
            when (st) {
                JoinPillState.JOIN -> Text(words[0], fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.onAccent)
                JoinPillState.JOINING -> com.fariin.app.ui.components.FariinSpinner(Modifier.size(16.dp), size = 16.dp, stroke = 2.dp, color = c.onAccent)
                JoinPillState.JOINED -> Row(verticalAlignment = Alignment.CenterVertically) {
                    if (celebrate) {
                        DrawnCheck(c.secondary)
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(words[1], fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.secondary)
                }
                JoinPillState.REQUESTED -> Text("Requested", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.secondary)
            }
        }
    }
}

/** A check that draws itself in, left stroke then right (~0.25 s). */
@Composable
private fun DrawnCheck(color: Color) {
    val t = remember { Animatable(0f) }
    LaunchedEffect(Unit) { t.animateTo(1f, tween(260)) }
    Canvas(Modifier.size(13.dp)) {
        val w = size.width; val h = size.height
        val full = Path().apply { moveTo(w * 0.1f, h * 0.55f); lineTo(w * 0.4f, h * 0.83f); lineTo(w * 0.92f, h * 0.2f) }
        val pm = PathMeasure().apply { setPath(full, false) }
        val part = Path()
        pm.getSegment(0f, pm.length * t.value, part, true)
        drawPath(part, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
