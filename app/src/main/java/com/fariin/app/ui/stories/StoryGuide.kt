package com.fariin.app.ui.stories

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The first-time story guide (iOS StoryGuide): over the viewer until Got it on this phone; the X
 * closes the viewer without marking it, so it comes back next time. Owed only to accounts made from
 * the day it arrived (2026-10-20), so people who used stories before never see it.
 */
object StoryGuide {
    private const val PREFS = "fariin_story_guide"
    private const val KEY = "seen.v1"
    private const val NEW_SINCE_MS = 1_792_454_400_000L   // 2026-10-20 00:00 UTC

    fun isOwed(c: Context): Boolean {
        if (c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)) return false
        val made = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.metadata?.creationTimestamp ?: return false
        return made >= NEW_SINCE_MS
    }

    fun markSeen(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, true).apply()
}

private enum class GuideMotion { TAP, SWIPE_LEFT, SWIPE_DOWN }
private const val MOTION_MS = 1500L

/** The guide itself: dark wash, X, three gestures playing in turn, Got it. Every other touch stops here. */
@Composable
internal fun StoryGuideOverlay(onGotIt: () -> Unit, onExit: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val fade = remember { Animatable(0f) }
    var playing by remember { mutableIntStateOf(-1) }
    var round by remember { mutableIntStateOf(0) }
    var leaving by remember { mutableStateOf(false) }
    val rows = listOf(GuideMotion.TAP to "Tap to advance", GuideMotion.SWIPE_LEFT to "Swipe left to skip", GuideMotion.SWIPE_DOWN to "Swipe down to exit")
    LaunchedEffect(Unit) {
        fade.animateTo(1f, tween(350))
        delay(300)
        while (true) {
            for (i in rows.indices) { playing = i; round++; delay(MOTION_MS) }
            playing = -1
            delay(300)
        }
    }
    Box(
        Modifier.fillMaxSize().alpha(fade.value).background(Color.Black.copy(alpha = 0.78f))
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown().consume(); do { val e = awaitPointerEvent(); e.changes.forEach { it.consume() } } while (e.changes.any { it.pressed }) } },
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().padding(top = 12.dp, end = 16.dp), contentAlignment = Alignment.CenterEnd) {
                Box(Modifier.size(48.dp).clip(CircleShape).background(Color(0xFF1C1C1C)).clickable(onClick = onExit),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Close, "Close", tint = Color.White, modifier = Modifier.size(22.dp))
                }
            }
            Spacer(Modifier.weight(1f))
            Column(verticalArrangement = Arrangement.spacedBy(42.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                rows.forEachIndexed { i, (motion, label) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        GuideAnimation(motion, if (playing == i) round else -1)
                        Spacer(Modifier.height(12.dp))
                        Text(label, fontSize = 17.sp, color = Color.White)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.padding(bottom = 32.dp).width(190.dp).height(52.dp).clip(CircleShape).background(Color(0xFF1C1C1C))
                .clickable {
                    if (leaving) return@clickable
                    leaving = true
                    StoryGuide.markSeen(ctx)
                    scope.launch { fade.animateTo(0f, tween(200)); onGotIt() }
                }, contentAlignment = Alignment.Center) {
                Text("Got it", fontSize = 17.sp, color = Color.White, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            }
        }
    }
}

/** One gesture drawn by hand: a tap with its ring, a hand sweeping across a phone, or down it. */
@Composable
private fun GuideAnimation(motion: GuideMotion, token: Int) {
    val t = remember { Animatable(0f) }
    val press = remember { Animatable(0f) }
    LaunchedEffect(token) {
        if (token < 0) return@LaunchedEffect
        t.snapTo(0f); press.snapTo(0f)
        when (motion) {
            GuideMotion.TAP -> repeat(2) {
                delay(100); press.animateTo(1f, tween(150)); delay(50); press.animateTo(0f, tween(250)); delay(150)
            }
            else -> { delay(100); t.animateTo(1f, tween(600)); delay(250); t.animateTo(0f, tween(400)) }
        }
    }
    Box(Modifier.size(54.dp), contentAlignment = Alignment.Center) {
        when (motion) {
            GuideMotion.TAP -> {
                Canvas(Modifier.size(54.dp)) {
                    val r = (3f + 10f * press.value) * density
                    drawCircle(Color.White.copy(alpha = 0.7f * press.value), r, Offset(size.width / 2f - 2f * density, size.height / 2f - 16f * density),
                        style = Stroke(1.5f * density))
                }
                HandIcon(Modifier.scale(1f - 0.1f * press.value).offset(y = (2 * press.value).dp))
            }
            GuideMotion.SWIPE_LEFT -> {
                PhoneOutline(0.dp)
                HandIcon(Modifier.scale(0.78f).offset(x = (10 - 18 * t.value).dp, y = 6.dp))
            }
            GuideMotion.SWIPE_DOWN -> {
                PhoneOutline(8.dp)
                Icon(Icons.Filled.ArrowDownward, null, tint = Color.White.copy(alpha = 0.4f + 0.6f * t.value),
                    modifier = Modifier.size(14.dp).offset(x = 8.dp, y = (-6 + 10 * t.value).dp))
                HandIcon(Modifier.scale(0.78f).offset(x = (-12).dp, y = (-6 + 14 * t.value).dp))
            }
        }
    }
}

@Composable
private fun HandIcon(modifier: Modifier) =
    Icon(Icons.Outlined.TouchApp, null, tint = Color.White, modifier = modifier.size(32.dp))

@Composable
private fun PhoneOutline(x: androidx.compose.ui.unit.Dp) {
    Canvas(Modifier.offset(x = x).size(26.dp, 42.dp)) {
        drawRoundRect(Color.White, Offset.Zero, Size(size.width, size.height), CornerRadius(6.dp.toPx()), style = Stroke(1.6.dp.toPx()))
    }
}
