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
 * The ring on my own photo, video or album tile while it goes up (iOS UploadRingView, 52 pt, disc
 * at 0.35): it spins while no byte has moved (waiting its turn is not zero), fills by bytes with a
 * short ease once they move, turns as a nearly closed "finishing" arc once every byte is up until
 * the server has the message, and is gone once the item is done, X'd or the send failed.
 *
 * [key] is a photo's or video's clientId, an album tile's "clientId#index"; null (somebody else's
 * upload) only spins. The progress is read in the draw step and the phase in this small composable,
 * so a progress tick never recomposes the row.
 */
@Composable
internal fun UploadRing(key: String?, modifier: Modifier = Modifier, onCancel: (() -> Unit)? = null,
    side: Dp = 52.dp, failed: Boolean = false) {
    // The first frame already draws what the upload is at (a row scrolled back mid-upload).
    val now = key?.let { UploadProgress.map.value[it] }
    val first = if (key == null) MediaRules.RingPhase.SPIN else MediaRules.ringPhase(now, key in UploadProgress.settled.value)
    val phase by produceState(first, key) {
        if (key == null) return@produceState
        kotlinx.coroutines.flow.combine(UploadProgress.map, UploadProgress.settled) { m, s ->
            MediaRules.ringPhase(m[key], key in s)
        }.distinctUntilChanged().collect { value = it }
    }
    if (failed || phase == MediaRules.RingPhase.GONE) return
    val motion = ringMotionOn()
    val fill = remember(key) { Animatable(maxOf(0.03f, now?.takeIf { it < 0.999f } ?: 0f)) }
    LaunchedEffect(key, motion) {
        if (key == null) return@LaunchedEffect
        UploadProgress.map.map { it[key] }.distinctUntilChanged().collect { f ->
            if (f == null || f <= 0f || f >= 0.999f) return@collect
            val to = maxOf(0.03f, f)
            if (motion) fill.animateTo(to, tween(200, easing = FastOutSlowInEasing)) else fill.snapTo(to)
        }
    }
    val turning = phase != MediaRules.RingPhase.FILL
    val turn = rememberTurn(motion && turning)
    // The spinner's stroke opens from nothing to half a circle over its first second (iOS "open").
    val open = remember(key, phase) { Animatable(if (motion && phase == MediaRules.RingPhase.SPIN) 0f else 0.5f) }
    LaunchedEffect(key, phase, motion) {
        if (motion && phase == MediaRules.RingPhase.SPIN) open.animateTo(0.5f, tween(1000, easing = androidx.compose.animation.core.EaseIn))
    }
    Box(modifier.size(side).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f))
        .then(if (onCancel != null) Modifier.clickable(onClickLabel = "Cancel upload", onClick = onCancel) else Modifier),
        contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val s = size.minDimension
            val w = maxOf(2.dp.toPx(), s * 0.058f)
            val r = s * 0.4225f
            val sweep = 360f * when (phase) {
                MediaRules.RingPhase.SPIN -> if (motion) open.value else 0.25f
                MediaRules.RingPhase.FINISH -> 0.9f
                else -> fill.value
            }
            rotate(if (turning) turn.value else 0f) {
                drawArc(Color.White, -90f, sweep, useCenter = false,
                    topLeft = Offset(center.x - r, center.y - r), size = Size(r * 2, r * 2),
                    style = Stroke(width = w, cap = StrokeCap.Round))
            }
        }
        if (onCancel != null) Icon(Icons.Filled.Close, "Cancel upload", tint = Color.White, modifier = Modifier.size(side * 0.34f))
    }
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
