package com.fariin.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fariin.app.ui.theme.Fariin

/*
 * Loading placeholders, as iOS DesignSystem/Skeleton.swift: grey blocks in the shape of the rows
 * that are coming, with a light band sweeping across every 1.25 s. With animations off the band
 * stays still (plain grey blocks).
 */

/** One sweep position for the whole screen, so every block shines in step. */
@Composable
private fun shimmerPhase(): androidx.compose.runtime.State<Float> {
    // A State read only while drawing (battery/lag audit 2026-11-05): the band moves every frame,
    // but the rows are no longer rebuilt every frame, only redrawn.
    if (!animationsOn()) return androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(-2f) }
    val t = rememberInfiniteTransition(label = "skeleton")
    return t.animateFloat(-1f, 1f, infiniteRepeatable(tween(1250, easing = LinearEasing), RepeatMode.Restart), label = "sweep")
}

private fun Modifier.skeleton(shape: Shape, phaseState: androidx.compose.runtime.State<Float>, base: Color, highlight: Color): Modifier =
    clip(shape).background(base).drawWithCache {
        val band = size.width * 0.55f
        onDrawBehind {
            val phase = phaseState.value
            if (phase > -2f) {
                val x = phase * size.width * 1.7f
                drawRect(Brush.horizontalGradient(listOf(Color.Transparent, highlight, Color.Transparent),
                    startX = x, endX = x + band), topLeft = Offset.Zero, size = size)
            }
        }
    }

@Composable
private fun colors(): Pair<Color, Color> {
    val dark = Fariin.colors.dark
    // systemGray4 / systemGray5 and the iOS highlight strengths.
    return if (dark) Color(0xFF2C2C2E) to Color.White.copy(alpha = 0.10f)
    else Color(0xFFE5E5EA) to Color.White.copy(alpha = 0.55f)
}

@Composable
fun SkeletonBlock(width: Dp, height: Dp = 12.dp, radius: Dp = 6.dp, phase: androidx.compose.runtime.State<Float> = shimmerPhase(), modifier: Modifier = Modifier) {
    val (base, hi) = colors()
    Box(modifier.size(width, height).skeleton(RoundedCornerShape(radius), phase, base, hi))
}

@Composable
fun SkeletonCircle(size: Dp, phase: androidx.compose.runtime.State<Float> = shimmerPhase()) {
    val (base, hi) = colors()
    Box(Modifier.size(size).skeleton(CircleShape, phase, base, hi))
}

/** The chat list while the first page loads: rows the size of real ones (56 dp photo). */
@Composable
fun ChatListSkeleton(modifier: Modifier = Modifier) {
    val phase = shimmerPhase()
    val widths = listOf(220, 150, 240, 120, 200, 170, 230, 140, 190, 210)
    Column(modifier.fillMaxSize().clearAndSetSemantics { contentDescription = "Loading chats" }) {
        widths.forEach { w ->
            Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                SkeletonCircle(56.dp, phase)
                Spacer(Modifier.width(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SkeletonBlock(140.dp, 14.dp, phase = phase)
                    SkeletonBlock(w.dp, 12.dp, phase = phase)
                }
            }
        }
    }
}

/** The calls list while it loads. */
@Composable
fun CallListSkeleton(modifier: Modifier = Modifier) {
    val phase = shimmerPhase()
    Column(modifier.fillMaxSize().clearAndSetSemantics { contentDescription = "Loading calls" }) {
        repeat(12) {
            Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                SkeletonCircle(48.dp, phase)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SkeletonBlock(130.dp, 14.dp, phase = phase)
                    SkeletonBlock(80.dp, 10.dp, phase = phase)
                }
                SkeletonBlock(44.dp, 12.dp, phase = phase)
                Spacer(Modifier.width(16.dp))
                SkeletonCircle(24.dp, phase)
            }
        }
    }
}

/** A chat thread before its messages arrive: bubble shapes on both sides, newest at the bottom. */
@Composable
fun ThreadSkeleton(modifier: Modifier = Modifier) {
    val phase = shimmerPhase()
    val rows = listOf(false to 180, false to 120, true to 150, false to 210, true to 90,
        false to 160, true to 200, false to 130, true to 110, false to 175)
    BoxWithConstraints(modifier.fillMaxSize().clearAndSetSemantics { contentDescription = "Loading messages" }) {
        val count = maxOf(rows.size, (maxHeight.value / 44).toInt() + 1)
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom)) {
            repeat(count) { i ->
                val (mine, w) = rows[(count - 1 - i) % rows.size]
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
                    SkeletonBlock(w.dp, 34.dp, radius = 18.dp, phase = phase)
                }
            }
        }
    }
}

/**
 * A channel or group opened from outside before its first page (iOS spec 2026-11-10 item 6): never
 * an empty white page. Post bubbles down the left, one with a 150 picture block, and a 50 capsule
 * where Follow sits.
 */
@Composable
fun ChannelSkeleton(modifier: Modifier = Modifier) {
    val phase = shimmerPhase()
    Column(modifier.fillMaxSize().background(com.fariin.app.ui.theme.Fariin.colors.bg)
        .clearAndSetSemantics { contentDescription = "Loading posts" }
        .navigationBarsPadding().padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom)) {
        SkeletonBlock(230.dp, 64.dp, radius = 18.dp, phase = phase)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SkeletonBlock(260.dp, 150.dp, radius = 18.dp, phase = phase)
            SkeletonBlock(200.dp, 34.dp, radius = 18.dp, phase = phase)
        }
        SkeletonBlock(180.dp, 48.dp, radius = 18.dp, phase = phase)
        SkeletonBlock(240.dp, 80.dp, radius = 18.dp, phase = phase)
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            SkeletonBlock(10000.dp, 50.dp, radius = 25.dp, phase = phase, modifier = Modifier.fillMaxWidth())
        }
    }
}
