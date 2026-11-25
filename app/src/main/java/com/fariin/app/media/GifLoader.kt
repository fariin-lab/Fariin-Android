package com.fariin.app.media

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import okio.Path.Companion.toOkioPath

/**
 * iOS GifBytesCache (owner 2026-11-09, "like iOS"): GIFs in their OWN store on the phone, so a GIF
 * downloads once and plays from the phone after that. Kept apart from faces and photos, which used
 * to push GIFs out of the shared 250 MB store (and the other way round).
 */
object GifLoader {
    @Volatile private var loader: ImageLoader? = null

    fun get(c: Context): ImageLoader = loader ?: synchronized(this) {
        loader ?: ImageLoader.Builder(c.applicationContext)
            .diskCache {
                DiskCache.Builder()
                    .directory(java.io.File(c.applicationContext.cacheDir, "gif_cache").toOkioPath())
                    .maxSizeBytes(300L * 1024 * 1024)
                    .build()
            }
            .build().also { loader = it }
    }

    /** A GIF's first frame as a still picture, a third of the screen wide: memory-cached, unlike
     *  the animated decode, so a grid shown again draws at once. */
    fun still(c: Context, url: String, shownFirst: String? = null): coil3.request.ImageRequest =
        coil3.request.ImageRequest.Builder(c.applicationContext).data(url)
            .size(STILL_PX).memoryCacheKey("still:$url").apply {
                // The same GIF's still already in memory under another address (the panel's whole-GIF
                // still), drawn while this one loads: a GIF just sent shows at once.
                if (shownFirst != null && shownFirst != url) placeholderMemoryCacheKey("still:$shownFirst")
                // coil-gif adds its animated decoders to every loader by itself, so this "still" moved
                // too: on the low-end emulator (GIF autoplay off) four GIF bubbles each decoded a frame
                // on the main thread and sent it to the GPU every frame (2026-11-15). See
                // LowPhoneMedia.gifStillOnly; other phones with autoplay on keep what they had.
                val power = com.fariin.app.perf.PowerSaving.state.value
                if (com.fariin.app.perf.LowPhoneMedia.gifStillOnly(power.deviceClass, power.autoplayGifs))
                    decoderFactory(coil3.decode.BitmapFactoryDecoder.Factory())
            }.build()

    /** Giphy's one-frame copy of a GIF (same name + "_s"): about a sixteenth of the whole file, which
     *  a chat bubble's still used to wait for (owner 2026-11-24: a GIF showed late on opening a chat).
     *  Null for any other address. */
    /** Small copies Giphy did not have: asked for once per run, then the whole GIF straight away. */
    private val noSmall = java.util.Collections.synchronizedSet(HashSet<String>())
    fun smallStillMissing(small: String) { noSmall.add(small) }

    fun smallStillOf(url: String?): String? {
        val u = url ?: return null
        if (!u.contains("giphy.com/media", ignoreCase = true)) return null
        val path = u.substringBefore('?')
        if (!path.endsWith(".gif", ignoreCase = true) || path.endsWith("_s.gif", ignoreCase = true)) return null
        return (path.dropLast(4) + "_s.gif").takeIf { it !in noSmall }
    }

    /** The first frames of the GIF panel's last trending list, made ahead (a chat opened). */
    fun warmStills(c: Context) {
        val list = com.fariin.app.data.Giphy.cached(c, "") ?: return
        val l = get(c)
        for (g in list.take(WARM_COUNT)) l.enqueue(still(c, g.url))
    }

    private const val STILL_PX = 360
    private const val WARM_COUNT = 15
}
