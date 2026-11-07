package com.fariin.app.ui.thread

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.media.MediaDownloads
import com.fariin.app.media.MediaRules
import com.fariin.app.media.UploadProgress
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Whether rings may turn and fill with motion: Power Saving's switch and the system setting. */
@Composable
internal fun ringMotionOn(): Boolean = com.fariin.app.perf.rememberPowerState().animations && systemAnimationsOn()

/** A turn a second, read in the draw step only (no recomposition per frame); 0 when motion is off. */
@Composable
private fun rememberTurn(on: Boolean): State<Float> {
    if (!on) return remember { mutableStateOf(0f) }
    return rememberInfiniteTransition(label = "ring").animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "turn",
    )
}

/**
 * The receive side's disc (iOS MediaDownloadBadge, 48 pt at 0.45): an arrow while it waits for a
 * tap (auto-download off for this network), a ring filled by bytes with an X while it comes down
 * (the tap cancels), a turn arrow after a failure (the tap tries again). The size pill in the top
 * corner names the cost: always on a video, on a photo only while it waits.
 */
@Composable
internal fun DownloadBadge(url: String?, state: Fetch, isVideo: Boolean, modifier: Modifier = Modifier, showPill: Boolean = true,
    /** False where the tap does not cancel (the viewer's wait): the ring alone. */
    showCross: Boolean = true) {
    if (url == null || state == Fetch.DONE) return
    val motion = ringMotionOn()
    val prog by produceState<MediaDownloads.Progress?>(null, url) {
        MediaDownloads.live.map { it[url] }.distinctUntilChanged().collect { value = it }
    }
    var bytes by remember(url) { mutableStateOf(MediaDownloads.knownSize(url)) }
    LaunchedEffect(url, state) {
        if (bytes == null && (state == Fetch.WAITING || isVideo && state != Fetch.FAILED)) bytes = MediaDownloads.fetchSize(url)
    }
    Box(modifier.fillMaxSize()) {
        val pill = when {
            !showPill -> null
            state == Fetch.WAITING -> MediaDownloads.pillLabel(null, bytes)
            isVideo && state == Fetch.LOADING -> MediaDownloads.pillLabel(prog?.received ?: 0L, prog?.total ?: bytes)
            else -> null
        }
        if (pill != null) {
            Box(Modifier.padding(8.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 7.dp, vertical = 2.dp)) {
                Text(pill, style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                    fontFeatureSettings = "tnum"))
            }
        }
        Box(Modifier.align(Alignment.Center).size(48.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
            contentAlignment = Alignment.Center) {
            if (state == Fetch.LOADING) {
                val f = prog?.fraction
                val turn = rememberTurn(motion && f == null)
                val fill = remember(url) { Animatable(0.03f) }
                LaunchedEffect(f, motion) {
                    if (f != null) { val to = maxOf(0.03f, f); if (motion) fill.animateTo(to, tween(200)) else fill.snapTo(to) }
                }
                Canvas(Modifier.fillMaxSize()) {
                    val w = 2.5.dp.toPx()
                    val r = size.minDimension / 2 - 4.dp.toPx()
                    val tl = Offset(center.x - r, center.y - r)
                    drawCircle(Color.White.copy(alpha = 0.25f), r, style = Stroke(w))
                    rotate(if (f == null) turn.value else 0f) {
                        drawArc(Color.White, -90f, 360f * (if (f == null) 0.25f else fill.value), useCenter = false,
                            topLeft = tl, size = Size(r * 2, r * 2), style = Stroke(w, cap = StrokeCap.Round))
                    }
                }
                if (showCross) Icon(Icons.Filled.Close, "Cancel download", tint = Color.White, modifier = Modifier.size(18.dp))
            } else {
                Icon(if (state == Fetch.FAILED) Icons.Filled.Refresh else Icons.Filled.ArrowDownward,
                    if (state == Fetch.FAILED) "Try again" else "Download", tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }
    }
}
