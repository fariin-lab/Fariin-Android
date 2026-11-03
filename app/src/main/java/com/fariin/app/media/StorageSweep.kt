package com.fariin.app.media

import android.content.Context
import java.io.File

/**
 * Settings > Storage and Data > Manage storage (iOS ManageStoragePage): what the app keeps on this
 * phone, the Keep Media age limit and Clear Cache. Never touches `outgoing` (sends still waiting
 * for the server) or anything changed in the last hour (iOS: a file in use is left alone).
 */
object StorageSweep {
    /** Days to keep opened media, 0 = forever (iOS keepMediaDays). Prefs file `fariin_media`. */
    const val KEEP_KEY = "keepMediaDays"
    private const val MEDIA = "chat-media"
    private const val OUTGOING = "outgoing"
    private const val HOUR_MS = 60 * 60 * 1000L

    data class Sizes(val media: Long, val images: Long, val temp: Long)

    private fun size(f: File): Long = if (f.isFile) f.length() else (f.listFiles()?.sumOf { size(it) } ?: 0L)

    private fun imageDir(c: Context): File? =
        runCatching { coil3.SingletonImageLoader.get(c).diskCache?.directory?.toFile() }.getOrNull()

    fun sizes(c: Context): Sizes {
        val media = size(File(c.cacheDir, MEDIA))
        val imgDir = imageDir(c)
        val images = imgDir?.let { size(it) } ?: 0L
        val all = size(c.cacheDir) + (c.externalCacheDir?.let { size(it) } ?: 0L)
        val outgoing = size(File(c.cacheDir, OUTGOING))
        val inCache = if (imgDir != null && imgDir.startsWith(c.cacheDir)) images else 0L
        return Sizes(media, images, maxOf(0L, all - media - inCache - outgoing))
    }

    /** Keep Media: opened media older than the limit goes (it downloads again when opened). */
    fun sweep(c: Context, nowMs: Long = System.currentTimeMillis()) {
        val days = c.getSharedPreferences(AutoDownload.PREFS, Context.MODE_PRIVATE).getInt(KEEP_KEY, 0)
        if (days <= 0) return
        val cutoff = nowMs - days * 24L * HOUR_MS
        File(c.cacheDir, MEDIA).listFiles()?.forEach { if (it.isFile && it.lastModified() < cutoff) it.delete() }
    }

    /** Clear Cache: everything that downloads again, except waiting sends and files in use. */
    fun clear(c: Context, nowMs: Long = System.currentTimeMillis()) {
        runCatching { coil3.SingletonImageLoader.get(c).diskCache?.clear() }
        val recent = nowMs - HOUR_MS
        fun wipe(dir: File?) {
            dir?.listFiles()?.forEach { f ->
                if (f.name == OUTGOING) return@forEach
                if (f.isDirectory) { wipe(f); if (f.listFiles().isNullOrEmpty()) f.delete() }
                else if (f.lastModified() < recent || f.parentFile?.name == MEDIA) f.delete()
            }
        }
        wipe(c.cacheDir)
        wipe(c.externalCacheDir)
    }
}
