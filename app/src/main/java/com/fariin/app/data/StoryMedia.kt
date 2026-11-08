package com.fariin.app.data

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import java.io.ByteArrayOutputStream

/** A picked photo or video made ready for a story, the way iOS StoryPhoto / the video path does. */
object StoryMedia {
    class Video(val file: Uri, val info: StoryRules.VideoInfo)

    /** [jpeg] is the photo itself, or the video's poster frame (thumb.jpg). [stickers]: the tap
     *  areas drawn into it (a text story's link card), posted as the story's `stickers`. */
    class Prepared(val jpeg: ByteArray, val blurThumb: String, val video: Video? = null, val width: Int = 0, val height: Int = 0,
                   val stickers: List<StoryTapTarget> = emptyList(),
                   /** A repost's credit (iOS repostOf), written on the story and its public copy. */
                   val repostOf: StoryRepost? = null)

    class TooBig : Exception("This video is too large for a story.")
    /** Past the 10-minute pick limit (iOS AddStorySheet "That video is too long"). */
    class TooLong : Exception(StoryLimits.TOO_LONG_TEXT)

    fun isVideo(context: Context, uri: Uri): Boolean =
        context.contentResolver.getType(uri)?.startsWith("video/") == true

    fun prepare(context: Context, uri: Uri): Prepared? =
        if (isVideo(context, uri)) prepareVideo(context, uri) else preparePhoto(context, uri)

    /** iOS StoryPhoto.encode: 1080 wide (then 900, 720, 540) at 0.85 until it fits 1.5 MB. */
    fun preparePhoto(context: Context, uri: Uri): Prepared? {
        // 2400 on the long side covers the 1080-wide story of a tall photo without holding all 12 MP.
        val src = com.fariin.app.media.BitmapDecode.fromUri(context, uri, 2400) ?: return null
        return encodePhoto(flatten(src))
    }

    /**
     * The edited photo (text and pen burned in, iOS flatten): drawn onto a copy of the prepared
     * picture by [draw], which gets the canvas and the pixels per edit-space unit, then encoded the
     * same way as a picked photo. Call off the main thread.
     */
    fun bake(prepared: Prepared, extraStickers: List<StoryTapTarget> = emptyList(), draw: (android.graphics.Canvas, Float) -> Unit): Prepared? {
        val opts = android.graphics.BitmapFactory.Options().apply { inMutable = true; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bmp = android.graphics.BitmapFactory.decodeByteArray(prepared.jpeg, 0, prepared.jpeg.size, opts) ?: return null
        draw(android.graphics.Canvas(bmp), bmp.width / StoryEditRules.SPACE)
        val out = encodePhoto(bmp)
        // The tap areas and a repost's credit go with the picture (the credit used to be dropped here).
        return Prepared(out.jpeg, out.blurThumb, out.video, out.width, out.height, prepared.stickers + extraStickers, prepared.repostOf)
    }

    /**
     * The photo cut to [aspect] (width / height) around its middle, as the 9:16 card shows a portrait
     * photo (iOS defaultZoom fills the card). Call off the main thread; null if it cannot be decoded.
     */
    fun cropToAspect(prepared: Prepared, aspect: Float): Prepared? {
        val src = android.graphics.BitmapFactory.decodeByteArray(prepared.jpeg, 0, prepared.jpeg.size) ?: return null
        val (x, y, w, h) = StoryCardRules.cropBox(src.width, src.height, aspect)
        if (w == src.width && h == src.height) { src.recycle(); return prepared }
        val cut = Bitmap.createBitmap(src, x, y, w, h)
        if (cut !== src) src.recycle()
        val out = encodePhoto(cut)
        return Prepared(out.jpeg, out.blurThumb, null, out.width, out.height, prepared.stickers, prepared.repostOf)
    }

    /** A picture drawn here (a text story, iOS renderTextStory): posted at its own size and [quality],
     *  like iOS; recycles [bmp]. */
    fun fromRendered(bmp: Bitmap, quality: Int, stickers: List<StoryTapTarget> = emptyList()): Prepared {
        val out = jpeg(bmp, quality)
        val blur = blurThumb(bmp)
        val w = bmp.width; val h = bmp.height
        bmp.recycle()
        return Prepared(out, blur, width = w, height = h, stickers = stickers)
    }

    /** The ladder; recycles [bmp]. */
    private fun encodePhoto(bmp: Bitmap): Prepared {
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
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            // Longer than one story's 90 s: posted as several 540p pieces (StoryUploads), so only
            // the 10-minute pick limit applies, not the file size of the original.
            if (StoryLimits.pickTooLong(ms / 1000.0)) throw TooLong()
            if (StoryLimits.segments(ms / 1000.0).isEmpty() && bytes > StoryRules.VIDEO_MAX_BYTES) throw TooBig()
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
            val frame = r.getFrameAtTime(0) ?: return null
            val poster = fitWidth(frame, 720)
            val jpeg = jpeg(poster, 80)
            val blur = blurThumb(poster)
            return Prepared(jpeg, blur, Video(uri, StoryRules.VideoInfo(ms / 1000.0, w, h, bytes)), w, h)
        } catch (e: TooLong) {
            throw e
        } catch (e: TooBig) {
            throw e
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

/** The editor card's rules (iOS StoryEditorView.defaultZoom / flyRest), kept pure for the tests. */
object StoryCardRules {
    data class Box(val x: Int, val y: Int, val w: Int, val h: Int)

    /** A portrait photo fills the card; a square or landscape one, a clip, or one carrying tap areas is fitted. */
    fun fillsCard(width: Int, height: Int, isVideo: Boolean, carriesStickers: Boolean): Boolean =
        !isVideo && !carriesStickers && width > 1 && height > width

    /** The middle part of a [w] x [h] picture with the shape [aspect] (width / height). */
    fun cropBox(w: Int, h: Int, aspect: Float): Box {
        if (w <= 0 || h <= 0 || aspect <= 0f) return Box(0, 0, w, h)
        val a = w.toFloat() / h
        return if (a > aspect) {
            val nw = (h * aspect).toInt().coerceIn(1, w)
            Box((w - nw) / 2, 0, nw, h)
        } else {
            val nh = (w / aspect).toInt().coerceIn(1, h)
            Box(0, (h - nh) / 2, w, nh)
        }
    }
}
