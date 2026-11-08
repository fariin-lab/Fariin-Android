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

    /**
     * The story card as the editor shows it (iOS flatten): the picture's two edge colours top to
     * bottom, the photo at [photo] (output pixels, where the pinch and pan put it; whatever falls
     * outside the card is cut off), then the edits by [draw] (canvas, pixels per edit unit). [outW] x
     * [outH] has the card's shape, so what is posted is what was framed. Call off the main thread.
     */
    fun renderCard(prepared: Prepared, outW: Int, outH: Int, photo: android.graphics.RectF, top: Int, bottom: Int,
                   extraStickers: List<StoryTapTarget> = emptyList(), draw: (android.graphics.Canvas, Float) -> Unit): Prepared? {
        if (outW <= 0 || outH <= 0) return null
        val src = android.graphics.BitmapFactory.decodeByteArray(prepared.jpeg, 0, prepared.jpeg.size) ?: return null
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(out)
        paintCanvas(c, outW, outH, top, bottom)
        c.drawBitmap(src, null, photo, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG))
        src.recycle()
        draw(c, outW / StoryEditRules.SPACE)
        val enc = encodePhoto(out)
        return Prepared(enc.jpeg, enc.blurThumb, null, enc.width, enc.height, prepared.stickers + extraStickers, prepared.repostOf)
    }

    /** The story canvas: [top] fading to [bottom] (iOS StoryCanvas, RepostCard.edgeColours). */
    fun paintCanvas(c: android.graphics.Canvas, w: Int, h: Int, top: Int, bottom: Int) {
        val p = android.graphics.Paint()
        p.shader = android.graphics.LinearGradient(0f, 0f, 0f, h.toFloat(), top, bottom, android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
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

    /** iOS PinchPanGestureView: a pinch may shrink the picture to 0.4 or grow it to 4 times its fit. */
    const val MIN_ZOOM = 0.4f
    const val MAX_ZOOM = 4f
    /** A clip only zooms in: the zoom is its crop, and a crop cannot make it smaller (see StoryVideoCut). */
    const val VIDEO_MIN_ZOOM = 1f
    /** The rubber band past either end while the fingers are still on (iOS maxScale * 1.15, minScale * 0.9). */
    fun liveZoom(z: Float, min: Float, max: Float) = z.coerceIn(min * 0.9f, max * 1.15f)

    /** The editor can pinch and pan this one: not a text story's card, not a repost or a picture with tap areas drawn in. */
    fun zoomable(textStory: Boolean, carriesStickers: Boolean) = !textStory && !carriesStickers

    /**
     * iOS defaultZoom: a portrait picture opens filling the card (the ratio on the axis fitting left
     * open); square and landscape open fitted. [cardAspect] = width / height.
     */
    fun defaultZoom(w: Int, h: Int, cardAspect: Float): Float {
        if (w <= 1 || h <= 1 || h <= w || cardAspect <= 0f) return 1f
        val a = w.toFloat() / h
        return maxOf(1f, if (a > cardAspect) a / cardAspect else cardAspect / a)
    }

    /** iOS clampOffset: the picture's edge may reach the card's edge but never come inside it. */
    fun clampPan(panX: Float, panY: Float, fitW: Float, fitH: Float, zoom: Float, frameW: Float, frameH: Float): Pair<Float, Float> {
        val mx = maxOf(0f, (fitW * zoom - frameW) / 2)
        val my = maxOf(0f, (fitH * zoom - frameH) / 2)
        return panX.coerceIn(-mx, mx) to panY.coerceIn(-my, my)
    }

    /**
     * The pen's transform in edit units (a [spaceW] x [spaceH] card): the picture's zoom about the
     * card's middle, then its pan, as `[a, b, tx, c, d, ty]` for an android Matrix (pure for the tests).
     */
    fun strokeTransform(spaceW: Float, spaceH: Float, zoom: Float, panX: Float, panY: Float): FloatArray {
        val cx = spaceW / 2; val cy = spaceH / 2
        return floatArrayOf(zoom, 0f, cx + panX - cx * zoom, 0f, zoom, cy + panY - cy * zoom)
    }

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
