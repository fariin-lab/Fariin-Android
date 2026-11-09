package com.fariin.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.PI
import kotlin.math.sin

/**
 * The link field's "not yet" shake (owner 2026-11-09, iOS LinkShake): two quick swings side to
 * side each time [nudges] goes up.
 */
@Composable
fun Modifier.linkShake(nudges: Int): Modifier {
    val p = remember { Animatable(0f) }
    LaunchedEffect(nudges) {
        if (nudges == 0) return@LaunchedEffect
        p.snapTo(0f)
        p.animateTo(1f, tween(400, easing = LinearEasing))
    }
    return this.graphicsLayer { translationX = 7f * density * sin(p.value * PI.toFloat() * 4f) }
}
