package com.fariin.app.media

import android.content.Context
import java.io.File

/**
 * Settings > Storage and Data > Manage storage (iOS ManageStoragePage): what the app keeps on this
 * phone, the Keep Media age limit and Clear Cache. Clear Cache never touches `outgoing` (sends still
 * waiting for the server) or anything changed in the last hour (iOS: a file in use is left alone);
 * the start-up sweep drops only `outgoing` copies no outbox names any more.
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
        // The saved faces for notifications count as pictures too (K-127).
        val faces = com.fariin.app.push.NotifStore.avatarBytes(c)
        val images = (imgDir?.let { size(it) } ?: 0L) + faces
        val all = size(c.cacheDir) + (c.externalCacheDir?.let { size(it) } ?: 0L)
        val outgoing = size(File(c.cacheDir, OUTGOING))
        val inCache = if (imgDir != null && imgDir.startsWith(c.cacheDir)) images - faces else 0L
        // Copies of sends (outside the cache since K-45) count as temporary files too (H-106).
        val sending = size(File(c.noBackupFilesDir, OUTGOING))
        return Sizes(media, images, maxOf(0L, all - media - inCache - outgoing) + outgoing + sending)
    }

    /**
     * Keep Media: opened pictures older than the limit go (they download again when opened). Videos,
     * voice notes and documents are kept, as iOS (its sweep only walks the picture cache): the server
     * may have deleted a delivered file, so this phone's copy can be the last one.
     */
    fun sweep(c: Context, nowMs: Long = System.currentTimeMillis()) {
        runCatching { sweepOutgoing(c, nowMs) }
        val days = c.getSharedPreferences(AutoDownload.PREFS, Context.MODE_PRIVATE).getInt(KEEP_KEY, 0)
        if (days <= 0) return
        val cutoff = nowMs - days * 24L * HOUR_MS
        File(c.cacheDir, MEDIA).listFiles()?.forEach { if (it.isFile && it.lastModified() < cutoff && isPicture(it)) it.delete() }
    }

    /**
     * H-106: a send's copies left in `outgoing` (both the old cache one and the new one) once no
     * outbox of any account names them and they are a day old. Read from the stores' raw JSON, so
     * a saved account that is not signed in right now keeps its waiting sends.
     */
    private fun sweepOutgoing(c: Context, nowMs: Long) {
        val raws = listOf("photo_outbox", "file_outbox").flatMap { name ->
            c.getSharedPreferences(name, Context.MODE_PRIVATE).all.values.map { it as? String }
        }
        val held = com.fariin.app.data.OutgoingSweepRules.heldText(raws)
        for (dir in listOf(File(c.cacheDir, OUTGOING), File(c.noBackupFilesDir, OUTGOING))) {
            dir.listFiles()?.forEach { f ->
                if (f.isFile && com.fariin.app.data.OutgoingSweepRules.deletable(f.name, f.lastModified(), nowMs, held)) f.delete()
            }
        }
    }

    /** A picture (JPEG, PNG, GIF, WebP, HEIC, AVIF), by its first bytes: the folder names files by a hash. */
    private fun isPicture(f: File): Boolean = runCatching {
        val b = ByteArray(12)
        val n = f.inputStream().use { it.read(b) }
        if (n < 12) return false
        fun at(i: Int, s: String) = s.indices.all { b[i + it] == s[it].code.toByte() }
        (b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte()) ||
            (b[0] == 0x89.toByte() && at(1, "PNG")) || at(0, "GIF8") || (at(0, "RIFF") && at(8, "WEBP")) ||
            (at(4, "ftyp") && listOf("heic", "heix", "hevc", "mif1", "msf1", "avif").any { at(8, it) })
    }.getOrDefault(false)

    /** Clear Cache: everything that downloads again, except waiting sends and files in use. */
    fun clear(c: Context, nowMs: Long = System.currentTimeMillis()) {
        runCatching { coil3.SingletonImageLoader.get(c).diskCache?.clear() }
        runCatching { com.fariin.app.push.NotifStore.clearAvatars(c) }   // K-127: they download again
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
