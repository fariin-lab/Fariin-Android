package com.fariin.app.ui.thread

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.crypto.EncMeta
import com.fariin.app.data.Message
import com.fariin.app.media.AutoDownload
import com.fariin.app.media.BlurHash
import com.fariin.app.media.LowEnd
import com.fariin.app.media.ChatMedia
import com.fariin.app.media.MediaRules
import com.fariin.app.media.VoicePlayer
import com.fariin.app.ui.theme.Fariin
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The chat's file source (download + open + cache). Null: the demo, bundled files only. */
val LocalChatMedia = staticCompositionLocalOf<ChatMedia?> { null }

@Composable
internal fun chatMedia(): ChatMedia {
    val ctx = LocalContext.current
    return LocalChatMedia.current ?: remember { ChatMedia(ctx.applicationContext, "demo", null) }
}

/** The plain file behind [url], fetched and opened in the background; null until then (or never). */
@Composable
internal fun rememberPlainFile(url: String?, enc: EncMeta?): File? {
    val media = chatMedia()
    val v by produceState<File?>(initialValue = url?.let { media.cached(it) }, url) {
        if (url != null && value == null) value = media.file(url, enc)
    }
    return v
}

/**
 * What a picture download is doing. WAITING = auto-download is off for this network, a tap starts
 * it; FAILED = the fetch gave up (slow or no network), a tap tries again. Never an endless ring.
 */
internal enum class Fetch { WAITING, LOADING, FAILED, DONE }

internal class GatedFile(val file: File?, val state: Fetch, val start: () -> Unit)

/**
 * [rememberPlainFile] with the Storage and Data rules (iOS AutoDownloadPrefs): a picture that is
 * not on the phone yet only comes down by itself when [kind] may download on this network. Until
 * then the bubble shows the inline thumbnail or BlurHash it already has (no bytes spent).
 */
@Composable
internal fun rememberGatedFile(url: String?, enc: EncMeta?, kind: AutoDownload.Kind?): GatedFile {
    val media = chatMedia()
    val ctx = LocalContext.current
    var file by remember(url) { mutableStateOf(url?.let { media.cached(it) }) }
    var state by remember(url) {
        val auto = url == null || ChatMedia.isLocal(url) || kind == null || AutoDownload.allowedNow(ctx, kind)
        mutableStateOf(when { url == null || file != null -> Fetch.DONE; auto -> Fetch.LOADING; else -> Fetch.WAITING })
    }
    LaunchedEffect(url, state) {
        if (state == Fetch.LOADING && url != null) {
            val f = media.file(url, enc)
            file = f
            state = if (f != null) Fetch.DONE else Fetch.FAILED
        }
    }
    return GatedFile(file, state) { if (state == Fetch.WAITING || state == Fetch.FAILED) state = Fetch.LOADING }
}

/** The disc on a picture that waits for a tap: an arrow to download, or a turn arrow to try again. */
@Composable
internal fun FetchDisc(state: Fetch, modifier: Modifier = Modifier) {
    Box(modifier.size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
        Icon(if (state == Fetch.FAILED) Icons.Filled.Refresh else Icons.Filled.Download,
            if (state == Fetch.FAILED) "Try again" else "Download", tint = Color.White, modifier = Modifier.size(24.dp))
    }
}

/**
 * Decoded chat pictures kept in memory, so a bubble scrolled back into view draws at once instead
 * of decoding again (less garbage, fewer dropped frames on a cheap phone). An eighth of the app's
 * heap, a sixteenth on a low-RAM phone.
 */
internal object DecodedPictures {
    private var cache: android.util.LruCache<String, ImageBitmap>? = null

    fun of(lowRam: Boolean): android.util.LruCache<String, ImageBitmap> = cache ?: synchronized(this) {
        cache ?: object : android.util.LruCache<String, ImageBitmap>(
            (Runtime.getRuntime().maxMemory() / if (lowRam) 16 else 8).toInt().coerceAtLeast(2 * 1024 * 1024)) {
            override fun sizeOf(key: String, value: ImageBitmap): Int = value.asAndroidBitmap().allocationByteCount
        }.also { cache = it }
    }

    fun clear() { cache?.evictAll() }
}

/** A picture decoded to about [targetPx] on its short side, and never over [maxLongPx] on its long side. */
@Composable
internal fun rememberBitmap(file: File?, targetPx: Int, maxLongPx: Int = 0): ImageBitmap? {
    val ctx = LocalContext.current
    val lowRam = remember { LowEnd.isLowRam(ctx) }
    val key = file?.let { "${it.absolutePath}|${it.length()}|$targetPx|$maxLongPx" }
    val v by produceState(initialValue = key?.let { DecodedPictures.of(lowRam).get(it) }, file, targetPx) {
        if (value != null || file == null || key == null) return@produceState
        value = withContext(Dispatchers.IO) {
            // Bubble-sized pictures only: the full-screen viewer (a view-once photo among them) is never kept.
            decodeSampled(file, targetPx, maxLongPx, lowRam)?.also { if (maxLongPx == 0) DecodedPictures.of(lowRam).put(key, it) }
        }
    }
    return v
}

internal fun decodeSampled(f: File, targetPx: Int, maxLongPx: Int = 0, lowRam: Boolean = false): ImageBitmap? = runCatching {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(f.absolutePath, o)
    val sample = MediaRules.sampleFor(o.outWidth, o.outHeight, targetPx, maxLongPx)
    BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply {
        inSampleSize = sample
        // Photos are JPEGs (no see-through parts): half the memory a pixel on a low-RAM phone.
        if (lowRam && o.outMimeType == "image/jpeg") inPreferredConfig = Bitmap.Config.RGB_565
    })?.asImageBitmap()
}.getOrNull()

/** The inline thumbnail first (a real tiny picture), else the BlurHash, else nothing. */
@Composable
internal fun rememberPlaceholder(thumb: String?, blurhash: String?): ImageBitmap? = remember(thumb, blurhash) {
    thumb?.let { t ->
        runCatching { Base64.decode(t, Base64.DEFAULT) }.getOrNull()
            ?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }?.asImageBitmap()
    } ?: blurhash?.let { h ->
        BlurHash.decode(h, 32, 32)?.let { px -> Bitmap.createBitmap(px, 32, 32, Bitmap.Config.ARGB_8888).asImageBitmap() }
    }
}

/** The time and ticks floating on a picture with no caption: a dark capsule (iOS metaCapsule). */
@Composable
internal fun MetaCapsule(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.45f))
        .padding(horizontal = 7.dp, vertical = 2.dp)) { content() }
}

/**
 * A photo (or a video's poster). Sized like iOS photoBox, the placeholder under the real picture,
 * a ring while my own copy is still going up.
 */
@Composable
internal fun PictureBox(
    m: Message, url: String?, enc: EncMeta?, maxW: Dp, shape: Shape, captionWidth: Float,
    busy: Boolean, onOpen: () -> Unit, overlay: @Composable () -> Unit = {},
) {
    val (wDp, hDp) = MediaRules.photoBox(m.width, m.height, maxW.value, captionWidth)
    val px = with(LocalDensity.current) { minOf(wDp, hDp).dp.roundToPx() }
    val placeholder = rememberPlaceholder(m.thumb, m.blurhash)
    // A video's poster is small and always comes down; the photo itself follows auto-download.
    val kind = if (m.mediaKind == Message.MediaKind.PHOTO) AutoDownload.Kind.PHOTOS else null
    val got = rememberGatedFile(url, enc, kind)
    val bmp = rememberBitmap(got.file, px)
    val waits = got.state == Fetch.WAITING || got.state == Fetch.FAILED
    Box(
        Modifier.size(wDp.dp, hDp.dp).clip(shape).background(Fariin.colors.secondary.copy(alpha = 0.18f))
            .clickable(enabled = bmp != null || waits, onClick = { if (bmp != null) onOpen() else got.start() }),
    ) {
        if (bmp != null) {
            Image(bmp, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else if (placeholder != null) {
            Image(placeholder, null, contentScale = ContentScale.Crop, filterQuality = FilterQuality.High, modifier = Modifier.fillMaxSize())
        }
        if (waits && !busy) {
            FetchDisc(got.state, Modifier.align(Alignment.Center))
        } else if (busy || (bmp == null && url != null)) {
            // My own upload shows its progress (iOS ring); a download waits on a plain spinner.
            UploadRing(if (busy) m.clientId else null, Modifier.align(Alignment.Center))
        }
        overlay()
    }
}

/** A video bubble: the poster, a play disc, the length in a pill (iOS durationPill). */
@Composable
internal fun VideoOverlay(m: Message, ready: Boolean) {
    Box(Modifier.fillMaxSize()) {
        m.duration?.let { d ->
            Box(Modifier.padding(8.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 7.dp, vertical = 2.dp)) {
                Text(MediaRules.durationLabel(d), fontSize = 12.sp, color = Color.White)
            }
        }
        if (ready) {
            Box(Modifier.align(Alignment.Center).size(48.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.PlayArrow, "Play video", tint = Color.White, modifier = Modifier.size(30.dp))
            }
        }
    }
}

/**
 * A voice note, after iOS VoiceMessageView: a 44 disc, the waveform (20 high) with the speed pill
 * beside it while playing, the length (or the time played) under it, the time and ticks at the
 * bottom right. Tap the wave to jump there.
 */
@Composable
internal fun VoiceBody(m: Message, cid: String, isMe: Boolean, width: Dp, meta: @Composable () -> Unit) {
    val c = Fariin.colors
    val ctx = LocalContext.current
    val tint = if (isMe) Color.White else c.blue
    val glyph = if (isMe) c.blue else if (c.dark) Color(0xFF26262B) else Color(0xFFF2F2F2)
    val url = m.localPath ?: m.audioUrl
    val st by VoicePlayer.state.collectAsState()
    val mine = st.id == m.rowId
    val playing = mine && st.playing
    val media = chatMedia()
    val scope = rememberCoroutineScope()
    var loading by remember(m.rowId) { mutableStateOf(false) }
    val failed = m.rowId in st.failed
    val total = (m.duration ?: 0.0)
    val pos = if (mine) st.positionMs else st.paused[m.rowId] ?: 0
    val durMs = if (mine && st.durationMs > 0) st.durationMs else (total * 1000).toLong()
    val progress = if (durMs > 0) (pos.toFloat() / durMs).coerceIn(0f, 1f) else 0f
    val rate = VoicePlayer.state.collectAsState().value.rates[cid] ?: 1f

    Column(Modifier.width(width)) {
        Row(Modifier.fillMaxWidth().height(55.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(tint.copy(alpha = if (loading || url == null) 0.22f else 1f))
                    .clickable(enabled = url != null && !loading) {
                        scope.launch {
                            loading = true
                            val f = media.file(url!!, m.enc)
                            loading = false
                            if (f != null) VoicePlayer.toggle(ctx, m.rowId, cid, f)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (loading || url == null || m.uploading && m.audioUrl == null && m.localPath == null) {
                    CircularProgressIndicator(color = tint, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                } else {
                    Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        if (playing) "Pause" else "Play", tint = glyph, modifier = Modifier.size(28.dp))
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Waveform(m.waveform, progress, tint, Modifier.weight(1f).height(20.dp)) { f ->
                        if (mine) VoicePlayer.seek(m.rowId, f)
                    }
                    if (mine) {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier.width(40.dp).height(22.dp).clip(RoundedCornerShape(50)).background(tint.copy(alpha = 0.2f))
                                .clickable { VoicePlayer.cycleRate(cid) },
                            contentAlignment = Alignment.Center,
                        ) { Text(MediaRules.rateLabel(rate), fontSize = 12.sp, color = tint) }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                    val label = when {
                        failed -> "Can't play"
                        mine || pos > 0 -> MediaRules.durationLabel(pos / 1000.0)
                        else -> MediaRules.durationLabel(total)
                    }
                    Text(label, style = TextStyle(fontSize = 11.sp, fontFeatureSettings = "tnum",
                        color = (if (isMe) Color.White else c.label).copy(alpha = 0.8f)))
                    meta()
                }
            }
        }
    }
}

@Composable
private fun Waveform(bars: List<Int>, progress: Float, tint: Color, modifier: Modifier, onSeek: (Float) -> Unit) {
    val list = bars.ifEmpty { List(40) { 12 } }
    var w by remember { mutableStateOf(1f) }
    Canvas(modifier.pointerInput(Unit) {
        detectTapGestures { o -> onSeek((o.x / size.width).coerceIn(0f, 1f)) }
    }) {
        w = size.width
        val drawn = MediaRules.drawBars(list, size.width, 4.dp.toPx())
        val count = drawn.size.coerceAtLeast(1)
        val slot = size.width / count
        val barW = maxOf(2.dp.toPx(), slot * 0.5f)
        val playedTo = (count * progress).toInt()
        drawn.forEachIndexed { i, v ->
            val h = maxOf(2.dp.toPx(), MediaRules.display(v) * size.height)
            val x = i * slot + (slot - barW) / 2
            drawRoundRect(
                color = if (progress > 0 && i <= playedTo) tint else tint.copy(alpha = 0.35f),
                topLeft = Offset(x, (size.height - h) / 2), size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2, barW / 2),
            )
        }
    }
}
