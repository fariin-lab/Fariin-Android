package com.fariin.app.ui.thread

import android.content.Context
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import java.io.File

/**
 * GIFs played the way big messengers play them (owner 2026-11-10: GIFs lagged, a friend's cheap
 * phone too): not the .gif file, whose every frame is unpacked on the main processor, but the small
 * MP4 Giphy keeps beside it, played by the phone's video chip. A few shared players (four on a cheap
 * phone, six otherwise) serve the GIFs on screen; one that cannot have a player, or has no MP4,
 * shows its still. MP4s are kept on disk (64 MB), so a GIF seen before is not fetched again.
 */
internal object GifVideo {
    /** The MP4 Giphy serves beside a GIF (same path, .mp4), or null for any other address. */
    fun mp4Of(gifUrl: String?): String? {
        val u = gifUrl ?: return null
        if (!u.contains("giphy.com/media", ignoreCase = true)) return null
        val path = u.substringBefore('?')
        if (!path.endsWith(".gif", ignoreCase = true)) return null
        return path.dropLast(4) + ".mp4"
    }

    @Volatile private var cache: SimpleCache? = null

    @OptIn(UnstableApi::class)
    private fun cache(c: Context): SimpleCache = cache ?: synchronized(this) {
        cache ?: SimpleCache(File(c.cacheDir, "gif-mp4"), LeastRecentlyUsedCacheEvictor(64L * 1024 * 1024),
            StandaloneDatabaseProvider(c)).also { cache = it }
    }

    private val free = ArrayDeque<ExoPlayer>()
    private var made = 0
    private var busy = 0
    // How many GIFs play at once (owner 2026-11-11: a tester's mid-range phone lagged after a burst of
    // GIFs and stickers; its small graphics chip ran six videos while the chat scrolled). The newest
    // GIFs on screen ask first, so they are the ones that play; the rest show their stills.
    private fun limit(): Int = when (com.fariin.app.perf.PowerSaving.state.value.deviceClass) {
        com.fariin.app.perf.PerfClass.LOW -> 1
        com.fariin.app.perf.PerfClass.AVERAGE -> 2
        else -> 4
    }
    /** Bumped when a player comes free, so a GIF that was turned away asks again. */
    var freed by androidx.compose.runtime.mutableIntStateOf(0)
        private set

    /** A player for one GIF on screen, or null when all are busy (that GIF shows its still). */
    @OptIn(UnstableApi::class)
    fun take(c: Context): ExoPlayer? {
        if (busy >= limit()) return null
        free.removeFirstOrNull()?.let { busy++; return it }
        made++; busy++
        val app = c.applicationContext
        val source = CacheDataSource.Factory().setCache(cache(app))
            .setUpstreamDataSourceFactory(DefaultDataSource.Factory(app))
        return ExoPlayer.Builder(app).setMediaSourceFactory(ProgressiveMediaSource.Factory(source)).build().apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ALL
        }
    }

    fun give(p: ExoPlayer) {
        p.stop(); p.clearMediaItems(); p.clearVideoSurface()
        free.addLast(p)
        busy = (busy - 1).coerceAtLeast(0)
        freed++
    }
}

/**
 * One GIF as its MP4, fitted to [modifier]'s box (cropped like the picture was). [still] is drawn
 * under it until the first frame is on screen, and stays when no player is free or there is no MP4.
 */
@Composable
internal fun GifMp4(gifUrl: String?, play: Boolean, modifier: Modifier, still: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val mp4 = remember(gifUrl) { GifVideo.mp4Of(gifUrl) }
    var shown by remember(mp4) { mutableStateOf(false) }
    Box(modifier) {
        still()
        if (mp4 != null && play) {
            var player by remember(mp4) { mutableStateOf<ExoPlayer?>(null) }
            var video by remember(mp4) { mutableStateOf<Pair<Int, Int>?>(null) }
            // Turned away (all players busy): ask again each time one comes free. Once it has one,
            // the key stays put, so it is never taken back by its own success.
            var heldAt by remember(mp4) { androidx.compose.runtime.mutableIntStateOf(-1) }
            val key = if (heldAt >= 0) heldAt else GifVideo.freed
            DisposableEffect(mp4, key) {
                val p = GifVideo.take(ctx)
                player = p
                if (p != null) heldAt = key
                val l = object : Player.Listener {
                    override fun onRenderedFirstFrame() { shown = true }
                    override fun onVideoSizeChanged(s: androidx.media3.common.VideoSize) {
                        if (s.width > 0 && s.height > 0) video = s.width to s.height
                    }
                }
                p?.addListener(l)
                p?.setMediaItem(MediaItem.fromUri(mp4)); p?.prepare(); p?.playWhenReady = true
                onDispose { p?.removeListener(l); p?.let { GifVideo.give(it) }; player = null; shown = false; if (p != null) heldAt = -1 }
            }
            // Only while the app is on screen (owner 2026-11-13: minimized, our GIFs kept playing,
            // the reference app's stop): paused when the app goes to the background, on again on return.
            val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
            val alive = owner.lifecycle.currentStateFlow.collectAsState().value
                .isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
            androidx.compose.runtime.LaunchedEffect(player, alive) { player?.playWhenReady = alive }
            val p = player
            val vs = video
            if (p != null) AndroidView({ c -> TextureView(c).apply {
                // Cropped to the box, as the picture was (ContentScale.Crop), whenever it is laid out again.
                addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> (v.tag as? Pair<*, *>)?.let { crop(v as TextureView, it) } }
            } }, Modifier.fillMaxSize().alpha(if (shown) 1f else 0f),
                update = { tv -> p.setVideoTextureView(tv); tv.tag = vs; if (vs != null) crop(tv, vs) })
        }
    }
}

/** The video scaled to cover [tv] and centred, the extra cut off (a TextureView otherwise stretches it). */
private fun crop(tv: TextureView, size: Pair<*, *>) {
    val vw = (size.first as? Int) ?: return; val vh = (size.second as? Int) ?: return
    val w = tv.width.toFloat(); val h = tv.height.toFloat()
    if (w <= 0f || h <= 0f) return
    val s = maxOf(w / vw, h / vh)
    tv.setTransform(android.graphics.Matrix().apply { setScale(s * vw / w, s * vh / h, w / 2f, h / 2f) })
}
