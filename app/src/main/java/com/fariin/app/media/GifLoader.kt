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
}
