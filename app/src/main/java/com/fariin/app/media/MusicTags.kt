package com.fariin.app.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A song's own tags, read once its plain file is on the phone (iOS MusicMetaStore): title, artist,
 * album, length, and its picture shrunk to a small JPEG. Kept in memory by track id; [version]
 * moves when one is added, so bubbles redraw with the real names.
 */
object MusicTags {
    data class Tags(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val durationMs: Long = 0,
        /** The song's picture as a JPEG about 300 px on its long side; null = none. */
        val art: ByteArray? = null,
    )

    private val tags = ConcurrentHashMap<String, Tags>()
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    fun of(id: String): Tags? = tags[id]

    /** Reads [file]'s tags for [id] (call off the main thread); a second call for the same id is free. */
    fun ensure(id: String, file: File): Tags {
        tags[id]?.let { return it }
        val t = read(file)
        tags[id] = t
        _version.value = _version.value + 1
        return t
    }

    fun read(file: File): Tags {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.absolutePath)
            fun clean(k: Int) = r.extractMetadata(k)?.trim()?.ifEmpty { null }
            Tags(
                title = clean(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = clean(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?: clean(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                album = clean(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                durationMs = clean(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0,
                art = r.embeddedPicture?.let { artJpeg(it, 300) },
            )
        } catch (_: Exception) {
            Tags()
        } finally {
            runCatching { r.release() }
        }
    }

    /** A picture shrunk to [maxPx] on its long side as a JPEG (iOS artworkJPEG); null when it is not a picture. */
    fun artJpeg(bytes: ByteArray, maxPx: Int): ByteArray? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val scale = maxPx.toFloat() / maxOf(bmp.width, bmp.height)
        val out = if (scale < 1f) Bitmap.createScaledBitmap(bmp, maxOf(1, (bmp.width * scale).toInt()),
            maxOf(1, (bmp.height * scale).toInt()), true) else bmp
        ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
    }.getOrNull()
}
