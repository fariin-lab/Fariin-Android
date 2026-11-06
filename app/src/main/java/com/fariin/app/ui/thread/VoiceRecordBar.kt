package com.fariin.app.ui.thread

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.media.MediaRules
import com.fariin.app.media.VoicePreview
import com.fariin.app.ui.components.floating
import com.fariin.app.ui.theme.Fariin
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.floor

/**
 * The lock target over the mic while the hold runs (iOS ChatComposerView lockTarget): a small
 * pill with the padlock and an up chevron. [progress] is how far the finger has lifted toward
 * it (0 to 1): the pill comes down to meet the finger and the padlock closes at the top.
 */
@Composable
internal fun LockTarget(progress: Float, modifier: Modifier = Modifier) {
    val c = Fariin.colors
    Column(
        modifier.width(40.dp).height(66.dp).floating(RoundedCornerShape(20.dp)).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Icon(if (progress >= 1f) Icons.Filled.Lock else Icons.Filled.LockOpen, contentDescription = "Slide up to lock",
            tint = c.label, modifier = Modifier.size(20.dp))
        Icon(Icons.Filled.KeyboardArrowUp, null, tint = c.secondary.copy(alpha = 1f - progress * 0.7f),
            modifier = Modifier.size(20.dp))
    }
}

/**
 * The locked recording, in the field's place (iOS locked strip). Recording: the X, the red dot
 * with the time, the live wave, the "1", and pause. Paused: the X, play, the note's wave (tap or
 * drag to move), its length, the "1", and the mic to carry on. Send is the round button beside
 * the bar, drawn by the composer.
 */
@Composable
internal fun LockedVoiceBar(
    modifier: Modifier,
    elapsed: Double,
    levels: StateFlow<Float>,
    reviewing: Boolean,
    preview: VoicePreview.State,
    bars: List<Int>,
    total: Double,
    once: Boolean,
    onCancel: () -> Unit,
    onPauseOrResume: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeek: (Float) -> Unit,
    onToggleOnce: () -> Unit,
) {
    val c = Fariin.colors
    Row(modifier.height(48.dp).floating(RoundedCornerShape(24.dp)).padding(start = 6.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        // The X takes the bar's left end, its touch reaching the edge (iOS: "the touch area is too small").
        Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onCancel), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Close, contentDescription = "Delete recording", tint = c.label, modifier = Modifier.size(22.dp))
        }
        if (reviewing) {
            Box(Modifier.size(36.dp).clip(CircleShape).clickable(enabled = preview.ready, onClick = onTogglePlay),
                contentAlignment = Alignment.Center) {
                Icon(if (preview.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (preview.playing) "Pause preview" else "Play preview",
                    tint = c.label, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(4.dp))
            Waveform(bars, preview.progress, c.label, Modifier.weight(1f).height(22.dp), onSeek)
            Spacer(Modifier.width(8.dp))
            val shown = if (preview.playing || preview.positionMs > 0) preview.positionMs / 1000.0 else total
            Text(MediaRules.durationLabel(floor(shown)), style = TextStyle(fontSize = 15.sp, color = c.secondary,
                fontFeatureSettings = "tnum"))
        } else {
            Spacer(Modifier.width(4.dp))
            val pulse by rememberInfiniteTransition(label = "rec").animateFloat(1f, 0.25f,
                infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "dot")
            Box(Modifier.size(9.dp).alpha(pulse).clip(CircleShape).background(c.red))
            Spacer(Modifier.width(6.dp))
            Text(MediaRules.durationLabel(floor(elapsed)), style = TextStyle(fontSize = 15.sp, color = c.label,
                fontFeatureSettings = "tnum"))
            Spacer(Modifier.width(10.dp))
            LiveWave(levels, c.secondary, Modifier.weight(1f).height(22.dp))
        }
        Spacer(Modifier.width(8.dp))
        OnceToggle(once, onToggleOnce)
        Spacer(Modifier.width(6.dp))
        // Pause while recording, the mic to keep recording while paused (iOS stripControl).
        Box(Modifier.size(34.dp).clip(CircleShape).background(if (reviewing) c.red else c.label.copy(alpha = 0.08f))
            .clickable(onClick = onPauseOrResume), contentAlignment = Alignment.Center) {
            Icon(if (reviewing) Icons.Filled.Mic else Icons.Filled.Pause,
                contentDescription = if (reviewing) "Keep recording" else "Pause and listen back",
                tint = if (reviewing) Color.White else c.label, modifier = Modifier.size(20.dp))
        }
    }
}

/** The "1": send this note as a one-time listen (iOS onceButton, 1.circle / 1.circle.fill). */
@Composable
private fun OnceToggle(on: Boolean, onToggle: () -> Unit) {
    val c = Fariin.colors
    Box(Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onToggle)
        .semantics { contentDescription = if (on) "One-time listen on" else "One-time listen off" },
        contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(24.dp).clip(CircleShape)
                .then(if (on) Modifier.background(c.blue) else Modifier.border(1.6.dp, c.label, CircleShape)),
            contentAlignment = Alignment.Center,
        ) {
            Text("1", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (on) Color.White else c.label)
        }
    }
}

/** The last few seconds of level, newest on the right, while a locked recording runs. */
@Composable
private fun LiveWave(levels: StateFlow<Float>, tint: Color, modifier: Modifier) {
    val live = remember { mutableStateListOf<Float>() }
    LaunchedEffect(levels) {
        levels.collect { l ->
            live.add(l)
            while (live.size > 48) live.removeAt(0)
        }
    }
    Canvas(modifier.fillMaxHeight()) {
        val barW = 2.5.dp.toPx()
        val gap = 2.dp.toPx()
        val fit = ((size.width + gap) / (barW + gap)).toInt().coerceAtLeast(1)
        val shown = live.takeLast(fit)
        val startX = size.width - shown.size * (barW + gap) + gap
        shown.forEachIndexed { i, l ->
            val h = maxOf(2.dp.toPx(), l.coerceIn(0f, 1f) * size.height)
            drawRoundRect(tint, topLeft = Offset(startX + i * (barW + gap), (size.height - h) / 2),
                size = Size(barW, h), cornerRadius = CornerRadius(barW / 2, barW / 2))
        }
    }
}
