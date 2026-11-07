package com.fariin.app.ui.thread.bubbles

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import com.fariin.app.data.BubbleArt
import com.fariin.app.perf.DeviceClass
import com.fariin.app.perf.PerfClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext

/**
 * The decoded bubble pictures (SPEC 7). Each is decoded once, off the main thread, already shrunk to
 * screen size (about 0.7 of the source on a 3x phone, 0.75 of that again on a low-end phone), and
 * kept in a small LRU: 6 styles (about 5 MB at most), 3 on a low-end phone. A 1:1 chat needs 2 at
 * most. A bubble on screen holds its own reference, so an eviction never makes a row decode again
 * while it is still showing (no ping-pong in a group with many styles); nothing decodes per frame.
 */
object BubbleArtCache {
    @Volatile private var chat: LruCache<String, ImageBitmap>? = null
    /** The picker's small tiles, by bytes (about 4 MB), kept apart so they never push a chat's styles out. */
    private val thumbs = object : LruCache<String, ImageBitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
    /** One decode at a time: two rows asking for the same style get the first one's result. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val io = Dispatchers.IO.limitedParallelism(1)

    private fun chatCache(lowEnd: Boolean): LruCache<String, ImageBitmap> =
        chat ?: synchronized(this) { chat ?: LruCache<String, ImageBitmap>(if (lowEnd) 3 else 6).also { chat = it } }

    fun lowEnd(context: Context) = DeviceClass.get(context) == PerfClass.LOW

    /** Already decoded for the chat, or null (then [load]). */
    fun peek(id: String): ImageBitmap? = chat?.get(id)

    /** The chat-size picture, decoded once. Null when the asset is missing or cannot be read. */
    suspend fun load(context: Context, spec: BubbleArt.Spec, density: Float): ImageBitmap? {
        val low = lowEnd(context)
        val cache = chatCache(low)
        cache.get(spec.id)?.let { return it }
        return withContext(io) {
            cache.get(spec.id) ?: decode(context, spec, BubbleArt.decodeScale(spec, density, low))?.also { cache.put(spec.id, it) }
        }
    }

    /** A picker tile: the whole picture [widthPx] wide. */
    suspend fun thumb(context: Context, spec: BubbleArt.Spec, widthPx: Int): ImageBitmap? {
        thumbs.get(spec.id)?.let { return it }
        return withContext(io) {
            thumbs.get(spec.id) ?: decode(context, spec, (widthPx.toFloat() / spec.width).coerceIn(0.05f, 1f))?.also { thumbs.put(spec.id, it) }
        }
    }

    /** Drop the tiles when the picker closes; the chat cache stays. */
    fun dropThumbs() = thumbs.evictAll()

    private fun decode(context: Context, spec: BubbleArt.Spec, scale: Float): ImageBitmap? = runCatching {
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            // A coarse power-of-two cut first when the picture shrinks a lot, so the full-size
            // picture is never held in memory for a small tile.
            var sample = 1
            while (scale * sample * 2 <= 1f) sample *= 2
            inSampleSize = sample
        }
        val raw = context.assets.open(BubbleArt.asset(spec.id)).use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val w = Math.round(spec.width * scale).coerceAtLeast(1)
        val h = Math.round(spec.height * scale).coerceAtLeast(1)
        val out = if (raw.width == w && raw.height == h) raw
            else Bitmap.createScaledBitmap(raw, w, h, true).also { if (it !== raw) raw.recycle() }
        out.prepareToDraw()
        out.asImageBitmap()
    }.getOrNull()
}

/** A bubble's picture: at once when already decoded, else null on this frame and the picture when ready. */
@Composable
internal fun rememberBubbleArt(spec: BubbleArt.Spec): ImageBitmap? {
    val ctx = LocalContext.current
    val density = LocalDensity.current.density
    val held = remember(spec.id) { mutableStateOf(BubbleArtCache.peek(spec.id)) }
    if (held.value == null) LaunchedEffect(spec.id) { held.value = BubbleArtCache.load(ctx, spec, density) }
    return held.value
}

/** A picker tile's picture, [widthPx] wide. */
@Composable
internal fun rememberBubbleThumb(spec: BubbleArt.Spec, widthPx: Int): ImageBitmap? {
    val ctx = LocalContext.current
    val held = remember(spec.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(spec.id, widthPx) { if (held.value == null) held.value = BubbleArtCache.thumb(ctx, spec, widthPx) }
    return held.value
}
