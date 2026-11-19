package com.fariin.app.ui.thread

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * A Fariin Plus send effect: about 2.4 s of particles over the chat, drawn on one canvas and never
 * catching a touch (it has no pointer input). Hearts rise, confetti falls, balloons float up,
 * sparkles twinkle. [key] restarts it; [onDone] when it has played.
 */
@Composable
internal fun SendEffectOverlay(effect: String, key: Any, onDone: () -> Unit) {
    val t = remember(key) { Animatable(0f) }
    LaunchedEffect(key) {
        t.snapTo(0f)
        t.animateTo(1f, tween(2400, easing = LinearEasing))
        onDone()
    }
    val parts = remember(key, effect) { particles(effect) }
    Canvas(Modifier.fillMaxSize()) {
        val p = t.value
        for (q in parts) drawParticle(effect, q, p)
    }
}

private class Particle(val x: Float, val delay: Float, val speed: Float, val size: Float, val color: Color, val spin: Float, val sway: Float)

private val heartColors = listOf(Color(0xFFFF2D55), Color(0xFFFF6482), Color(0xFFFF375F))
private val confettiColors = listOf(Color(0xFFFF3B30), Color(0xFFFF9500), Color(0xFFFFCC00), Color(0xFF34C759), Color(0xFF0590F7), Color(0xFFAF52DE))
private val balloonColors = listOf(Color(0xFFFF3B30), Color(0xFF0590F7), Color(0xFFFFCC00), Color(0xFF34C759), Color(0xFFAF52DE))
private val sparkleColors = listOf(Color(0xFFFFD60A), Color(0xFFFFFFFF), Color(0xFFFFE066))

private fun particles(effect: String): List<Particle> {
    val r = Random(System.nanoTime())
    val (n, colors) = when (effect) {
        "hearts" -> 26 to heartColors
        "confetti" -> 70 to confettiColors
        "balloons" -> 12 to balloonColors
        else -> 40 to sparkleColors
    }
    return List(n) {
        Particle(x = r.nextFloat(), delay = r.nextFloat() * 0.35f, speed = 0.8f + r.nextFloat() * 0.5f,
            size = 0.6f + r.nextFloat() * 0.8f, color = colors[r.nextInt(colors.size)],
            spin = (r.nextFloat() - 0.5f) * 720f, sway = r.nextFloat() * 2f * PI.toFloat())
    }
}

private fun DrawScope.drawParticle(effect: String, q: Particle, p: Float) {
    val local = ((p - q.delay) / (1f - q.delay)).coerceIn(0f, 1f)
    if (local <= 0f) return
    val w = size.width; val h = size.height
    val alpha = when { local < 0.1f -> local / 0.1f; local > 0.8f -> (1f - local) / 0.2f; else -> 1f }.coerceIn(0f, 1f)
    val unit = minOf(w, h) / 22f * q.size
    when (effect) {
        "hearts" -> {
            val y = h * (1.05f - local * q.speed * 0.95f)
            val x = w * q.x + sin(local * 6f + q.sway) * unit
            translate(x, y) { drawPath(heartPath(unit), q.color.copy(alpha = alpha)) }
        }
        "confetti" -> {
            val y = h * (-0.05f + local * q.speed * 1.05f)
            val x = w * q.x + sin(local * 8f + q.sway) * unit * 0.8f
            translate(x, y) {
                rotate(q.spin * local, Offset.Zero) {
                    drawRect(q.color.copy(alpha = alpha), topLeft = Offset(-unit * 0.25f, -unit * 0.12f), size = Size(unit * 0.5f, unit * 0.24f))
                }
            }
        }
        "balloons" -> {
            val y = h * (1.15f - local * q.speed * 1.2f)
            val x = w * q.x + sin(local * 4f + q.sway) * unit * 0.6f
            val bw = unit * 1.6f; val bh = unit * 2f
            translate(x, y) {
                drawLine(Color.Gray.copy(alpha = alpha * 0.7f), Offset(0f, bh / 2), Offset(sin(local * 10f) * unit * 0.2f, bh * 1.4f), strokeWidth = 2f)
                drawOval(q.color.copy(alpha = alpha), topLeft = Offset(-bw / 2, -bh / 2), size = Size(bw, bh))
                drawOval(Color.White.copy(alpha = alpha * 0.35f), topLeft = Offset(-bw * 0.28f, -bh * 0.36f), size = Size(bw * 0.22f, bh * 0.3f))
            }
        }
        else -> {
            val x = w * q.x
            val y = h * ((q.sway / (2f * PI.toFloat())) * 0.9f + 0.05f)
            val tw = (sin(local * 10f + q.sway) * 0.5f + 0.5f)
            translate(x, y) { drawPath(starPath(unit * (0.4f + tw * 0.6f)), q.color.copy(alpha = alpha * (0.4f + tw * 0.6f))) }
        }
    }
}

private fun heartPath(s: Float) = Path().apply {
    moveTo(0f, s * 0.35f)
    cubicTo(-s * 0.9f, -s * 0.25f, -s * 0.45f, -s * 0.85f, 0f, -s * 0.4f)
    cubicTo(s * 0.45f, -s * 0.85f, s * 0.9f, -s * 0.25f, 0f, s * 0.35f)
    close()
}

private fun starPath(s: Float) = Path().apply {
    moveTo(0f, -s); quadraticTo(0f, 0f, s, 0f); quadraticTo(0f, 0f, 0f, s); quadraticTo(0f, 0f, -s, 0f); quadraticTo(0f, 0f, 0f, -s); close()
}
