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
    fun still(c: Context, url: String): coil3.request.ImageRequest =
        coil3.request.ImageRequest.Builder(c.applicationContext).data(url)
            .size(STILL_PX).memoryCacheKey("still:$url").build()

    /** The first frames of the GIF panel's last trending list, made ahead (a chat opened). */
    fun warmStills(c: Context) {
        val list = com.fariin.app.data.Giphy.cached(c, "") ?: return
        val l = get(c)
        for (g in list.take(WARM_COUNT)) l.enqueue(still(c, g.url))
    }

    private const val STILL_PX = 360
    private const val WARM_COUNT = 15
}
