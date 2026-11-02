package com.fariin.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import java.io.ByteArrayOutputStream

/** A picked photo or video made ready for a story, the way iOS StoryPhoto / the video path does. */
object StoryMedia {
    class Video(val file: Uri, val info: StoryRules.VideoInfo)

    /** [jpeg] is the photo itself, or the video's poster frame (thumb.jpg). */
    class Prepared(val jpeg: ByteArray, val blurThumb: String, val video: Video? = null, val width: Int = 0, val height: Int = 0)

    class TooBig : Exception("This video is too large for a story.")

    fun isVideo(context: Context, uri: Uri): Boolean =
        context.contentResolver.getType(uri)?.startsWith("video/") == true

    fun prepare(context: Context, uri: Uri): Prepared? =
        if (isVideo(context, uri)) prepareVideo(context, uri) else preparePhoto(context, uri)

    /** iOS StoryPhoto.encode: 1080 wide (then 900, 720, 540) at 0.85 until it fits 1.5 MB. */
    fun preparePhoto(context: Context, uri: Uri): Prepared? {
        val src = runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { d, _, _ ->
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }.getOrNull() ?: return null
        val bmp = flatten(src)
        var out = ByteArray(0)
        var w = bmp.width; var h = bmp.height
        for (width in StoryRules.PHOTO_WIDTHS) {
            val scaled = fitWidth(bmp, width)
            out = jpeg(scaled, StoryRules.PHOTO_QUALITY)
            w = scaled.width; h = scaled.height
            if (scaled !== bmp) scaled.recycle()
            if (out.size <= StoryRules.PHOTO_BUDGET) break
        }
        val blur = blurThumb(bmp)
        bmp.recycle()
        return Prepared(out, blur, width = w, height = h)
    }

    fun prepareVideo(context: Context, uri: Uri): Prepared? {
        val bytes = context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getLong(0) else 0L
        } ?: 0L
        if (bytes > StoryRules.VIDEO_MAX_BYTES) throw TooBig()
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
            val frame = r.getFrameAtTime(0) ?: return null
            val poster = fitWidth(frame, 720)
            val jpeg = jpeg(poster, 80)
            val blur = blurThumb(poster)
            return Prepared(jpeg, blur, Video(uri, StoryRules.VideoInfo(ms / 1000.0, w, h, bytes)), w, h)
        } catch (_: Exception) {
            return null
        } finally {
            r.release()
        }
    }

    /** iOS blurThumbBase64: at most 30 px, JPEG 0.4, plain base64. */
    fun blurThumb(b: Bitmap): String {
        val k = 30f / maxOf(b.width, b.height)
        val s = if (k < 1f) Bitmap.createScaledBitmap(b, maxOf(1, (b.width * k).toInt()), maxOf(1, (b.height * k).toInt()), true) else b
        return Base64.encodeToString(jpeg(s, 40), Base64.NO_WRAP)
    }

    private fun fitWidth(b: Bitmap, w: Int): Bitmap {
        if (b.width <= w) return b
        val h = (b.height.toLong() * w / b.width).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(b, w, h, true)
    }

    private fun flatten(b: Bitmap): Bitmap {
        if (!b.hasAlpha()) return b
        val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(out).apply { drawColor(android.graphics.Color.WHITE); drawBitmap(b, 0f, 0f, null) }
        b.recycle()
        return out
    }

    private fun jpeg(b: Bitmap, q: Int): ByteArray =
        ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, q, it) }.toByteArray()
}
