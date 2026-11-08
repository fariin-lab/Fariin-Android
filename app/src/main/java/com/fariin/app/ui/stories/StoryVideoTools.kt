package com.fariin.app.ui.stories

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The trim page's rules (iOS StoryEditorView trimRange / isTrimmed), pure for the tests. */
object StoryTrimRules {
    /** The shortest piece the handles may leave. */
    const val MIN_SECONDS = 1f

    /** iOS isTrimmed: a hair off either end counts as untouched. */
    fun isTrimmed(start: Float, end: Float, duration: Float): Boolean =
        start > 0.05f || (end > 0f && end < duration - 0.05f)

    /** The piece to export in ms, or null for the whole clip. */
    fun rangeMs(start: Float, end: Float, duration: Float): LongRange? {
        if (!isTrimmed(start, end, duration)) return null
        val e = if (end > 0f) end else duration
        if (e <= start) return null
        return (start * 1000).toLong()..(e * 1000).toLong()
    }

    /** "0:07" for the time labels. */
    fun clock(seconds: Float): String {
        val s = seconds.coerceAtLeast(0f).toInt()
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }
}

/**
 * The trim page (iOS StoryEditorView trim overlay): the clip as a strip of frames with two handles
 * over it, the piece's start, length and end, X that puts the handles back and the tick that keeps
 * them. Dragging a handle shows that frame ([onScrub] with ms; null when the finger lifts).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun StoryTrimBar(
    uri: Uri, duration: Float, start: Float, end: Float,
    onScrub: (Long?) -> Unit, onCancel: () -> Unit, onDone: (Float, Float) -> Unit, modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val total = duration.coerceAtLeast(StoryTrimRules.MIN_SECONDS)
    var range by remember { mutableStateOf(start.coerceIn(0f, total)..(if (end > 0f) end else total).coerceIn(0f, total)) }
    // Eight frames across the clip, read off the main thread.
    val frames by produceState<List<ImageBitmap>>(emptyList(), uri) {
        value = withContext(Dispatchers.IO) {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(context, uri)
                (0 until 8).mapNotNull { i ->
                    val us = ((i + 0.5f) / 8f * total * 1_000_000f).toLong()
                    r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { b ->
                        val h = 96
                        val w = (b.width.toFloat() * h / b.height.coerceAtLeast(1)).toInt().coerceAtLeast(1)
                        val s = android.graphics.Bitmap.createScaledBitmap(b, w, h, true)
                        if (s !== b) b.recycle()
                        s.asImageBitmap()
                    }
                }
            } catch (_: Exception) {
                emptyList()
            } finally {
                runCatching { r.release() }
            }
        }
    }
    Column(modifier.fillMaxWidth().background(Color.Black).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(StoryTrimRules.clock(range.start), color = Color.White, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Text(StoryTrimRules.clock(range.endInclusive - range.start), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(StoryTrimRules.clock(range.endInclusive), color = Color.White, fontSize = 13.sp)
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(52.dp), contentAlignment = Alignment.Center) {
            Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 10.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF2C2C2E))) {
                frames.forEach { f ->
                    Image(f, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.weight(1f).height(44.dp))
                }
            }
            RangeSlider(
                value = range,
                onValueChange = { v ->
                    // The handle that moved shows its frame; the piece never gets shorter than a second.
                    val movedStart = kotlin.math.abs(v.start - range.start) > kotlin.math.abs(v.endInclusive - range.endInclusive)
                    val next = if (v.endInclusive - v.start < StoryTrimRules.MIN_SECONDS) {
                        if (movedStart) (v.endInclusive - StoryTrimRules.MIN_SECONDS).coerceAtLeast(0f)..v.endInclusive
                        else v.start..(v.start + StoryTrimRules.MIN_SECONDS).coerceAtMost(total)
                    } else v
                    range = next
                    onScrub(((if (movedStart) next.start else next.endInclusive) * 1000).toLong())
                },
                valueRange = 0f..total,
                onValueChangeFinished = { onScrub(null) },
                colors = SliderDefaults.colors(
                    thumbColor = Color.White, activeTrackColor = Color.White.copy(alpha = 0.35f),
                    inactiveTrackColor = Color.Black.copy(alpha = 0.55f),
                ),
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f)).clickable(onClick = onCancel),
                contentAlignment = Alignment.Center) { Icon(Icons.Filled.Close, contentDescription = "Cancel", tint = Color.White) }
            Spacer(Modifier.weight(1f))
            Text("Trim", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(44.dp).clip(CircleShape).background(Color.White).clickable { onDone(range.start, range.endInclusive) },
                contentAlignment = Alignment.Center) { Icon(Icons.Filled.Check, contentDescription = "Done", tint = Color.Black) }
        }
    }
}
