package com.fariin.app.ui.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.fariin.app.R
import com.fariin.app.ui.theme.Fariin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The Devices page art (owner 2026-11-09): a woman looking at her phone and a padlock that clicks
 * shut once per open. Five white masks on one 1289x1676 canvas, tinted here so the art flips with
 * dark mode: woman, shackle, lock fill (the PAGE colour, so the shackle's legs vanish into the
 * body), lock, check (verified blue). GPU layer transforms only.
 */
@Composable
internal fun SecurityArt(modifier: Modifier = Modifier) {
    val c = Fariin.colors
    val haptics = LocalHapticFeedback.current
    val moving = com.fariin.app.ui.thread.systemAnimationsOn()
    // 0 = open (as drawn), 1 = closed (down by 3.7% of the image's height).
    val shut = remember { Animatable(if (moving) 0f else 1f) }
    val pulse = remember { Animatable(1f) }
    val check = remember { Animatable(if (moving) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (!moving) return@LaunchedEffect
        delay(350)
        shut.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 600f, visibilityThreshold = 0.002f))
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        launch {
            pulse.animateTo(1.04f, tween(100))
            pulse.animateTo(1f, tween(100))
        }
        delay(120)
        launch { check.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)) }
    }
    val label = ColorFilter.tint(c.label)
    val lockOrigin = TransformOrigin(0.2382f, 0.3923f)   // the lock body's centre, measured from the mask
    Box(modifier.height(220.dp).aspectRatio(1289f / 1676f)) {
        Image(painterResource(R.drawable.security_woman), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, colorFilter = label)
        Box(Modifier.fillMaxSize().graphicsLayer {
            scaleX = pulse.value; scaleY = pulse.value; transformOrigin = lockOrigin
        }) {
            Image(painterResource(R.drawable.security_shackle), null,
                Modifier.fillMaxSize().graphicsLayer { translationY = shut.value * 0.037f * size.height },
                contentScale = ContentScale.Fit, colorFilter = label)
            Image(painterResource(R.drawable.security_lockfill), null, Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit, colorFilter = ColorFilter.tint(c.bgSecondary))
            Image(painterResource(R.drawable.security_lock), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, colorFilter = label)
            Image(painterResource(R.drawable.security_check), null,
                Modifier.fillMaxSize().graphicsLayer {
                    val k = check.value
                    alpha = k.coerceIn(0f, 1f); scaleX = 0.6f + 0.4f * k; scaleY = 0.6f + 0.4f * k
                    transformOrigin = TransformOrigin(0.4081f, 0.4934f)   // the check's own centre
                },
                contentScale = ContentScale.Fit, colorFilter = ColorFilter.tint(Color(0xFF2392FE)))
        }
    }
}
