package com.fariin.app.media

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.Crop
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * One piece of a long story video (iOS postVideoSegment's export): [range] of the clip, H.264 with
 * the short side at 1080 for a full-HD clip and 720 otherwise (iOS story-hd-1114; it was 540, which
 * the owner saw as soft), never scaled up, and a bitrate capped so a piece always fits the story
 * file limit. Never the place it was filmed.
 */
object StoryVideoCut {
    /** Only when the clip's size cannot be read. */
    private const val SHORT_SIDE = 720

    /**
     * A clip as the story editor left it (iOS VideoTranscoder.prepare with burnIn): [range] of it (ms,
     * null = all), its sound taken off for [mute], [crop] (the part the pinch kept, 0 to 1 of the
     * frame, y down) and, when [canvasW] is set, the frame laid on the story card's shape with
     * [overlay] (the card's canvas round the clip, then the text, pen and stickers, exactly
     * canvasW x canvasH) burned in over it. Without a canvas it is sized like [cut].
     */
    class Edit(
        val range: LongRange?,
        val mute: Boolean,
        val crop: android.graphics.RectF?,
        val canvasW: Int,
        val canvasH: Int,
        val overlay: android.graphics.Bitmap?,
        /** When set (with a canvas): the clip is cut to this box's shape and laid in it (canvas
         *  pixels), under [overlay], which leaves the box open. Repost to Story's card. */
        val place: android.graphics.RectF? = null,
    )

    suspend fun cut(context: Context, uri: Uri, out: File, range: LongRange): Boolean =
        edit(context, uri, out, Edit(range, mute = false, crop = null, canvasW = 0, canvasH = 0, overlay = null))

    /**
     * H-105: whether the picked clip is already an MP4-family file (an `ftyp` box at byte 4: MP4,
     * MOV, 3GP), which an iPhone plays as posted. WebM, MKV and the rest are not, and must go through
     * [toMp4] before they are posted as a story's mp4.
     */
    fun isMp4Family(context: Context, uri: Uri): Boolean = runCatching {
        val b = ByteArray(8)
        val n = context.contentResolver.openInputStream(uri)?.use { s ->
            var at = 0
            while (at < 8) { val r = s.read(b, at, 8 - at); if (r < 0) break; at += r }
            at
        } ?: 0
        n == 8 && b[4] == 'f'.code.toByte() && b[5] == 't'.code.toByte() && b[6] == 'y'.code.toByte() && b[7] == 'p'.code.toByte()
    }.getOrDefault(false)

    /** H-105: the whole clip made again as H.264 MP4 (sized like [cut]); false when it cannot be. */
    suspend fun toMp4(context: Context, uri: Uri, out: File): Boolean =
        edit(context, uri, out, Edit(null, mute = false, crop = null, canvasW = 0, canvasH = 0, overlay = null))

    /** The clip's upright width and height (rotation applied); 0 x 0 when it cannot be read. */
    private fun sourceSize(context: Context, uri: Uri): Pair<Int, Int> {
        val r = android.media.MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            val w = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) h to w else w to h
        } catch (_: Exception) { 0 to 0 } finally { runCatching { r.release() } }
    }

    suspend fun edit(context: Context, uri: Uri, out: File, spec: Edit): Boolean {
        val (srcW, srcH) = withContext(Dispatchers.IO) { sourceSize(context, uri) }
        return withContext(Dispatchers.Main) { export(context, uri, out, spec, srcW, srcH) }
    }

    private suspend fun export(context: Context, uri: Uri, out: File, spec: Edit, srcW: Int, srcH: Int): Boolean =
        suspendCancellableCoroutine { cont ->
            val rules = com.fariin.app.data.StoryCameraRules
            val onCanvas = spec.canvasW > 0 && spec.canvasH > 0
            // The part of the clip that is kept, in pixels (all of it when there is no crop or no size).
            val keptW = if (srcW > 0) (srcW * (spec.crop?.width() ?: 1f)).toInt() else 0
            val keptH = if (srcH > 0) (srcH * (spec.crop?.height() ?: 1f)).toInt() else 0
            val keptShort = minOf(keptW, keptH)
            // The clip's short side once it is in the file: fitted on the card, or the plain cut's size.
            val outShort = when {
                keptShort <= 0 -> if (onCanvas) minOf(spec.canvasW, spec.canvasH) else SHORT_SIDE
                onCanvas -> (keptShort * minOf(spec.canvasW.toFloat() / keptW, spec.canvasH.toFloat() / keptH)).toInt()
                else -> rules.storyCutShortSide(minOf(srcW, srcH))
            }
            val outLong = if (onCanvas) maxOf(spec.canvasW, spec.canvasH)
                else if (keptShort > 0) outShort * maxOf(keptW, keptH) / keptShort else SHORT_SIDE * 16 / 9
            val bitrate = rules.videoBitrate(outLong, seconds = com.fariin.app.data.StoryLimits.VIDEO_SECONDS)
            val encoders = DefaultEncoderFactory.Builder(context)
                .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
                .build()
            val t = Transformer.Builder(context)
                .setEncoderFactory(encoders)
                .setMuxerFactory(NoLocationMuxerFactory())
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (cont.isActive) cont.resume(out.length() > 0)
                    }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        if (cont.isActive) cont.resume(false)
                    }
                })
                .build()
            val builder = MediaItem.Builder().setUri(uri)
            spec.range?.let { range ->
                builder.setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder().setStartPositionMs(range.first).setEndPositionMs(range.last).build(),
                )
            }
            val video = ArrayList<androidx.media3.common.Effect>()
            val box = spec.place
            if (box != null && onCanvas && srcW > 0 && srcH > 0) {
                // Cut the clip to the box's shape round its middle (-1..1, y up)...
                val a = srcW.toFloat() / srcH; val t = box.width() / box.height()
                video += if (a < t) { val k = a / t; Crop(-1f, 1f, -k, k) } else { val k = t / a; Crop(-k, k, -1f, 1f) }
                // ...fit it across the canvas, then shrink and move it onto the box.
                video += Presentation.createForWidthAndHeight(spec.canvasW, spec.canvasH, Presentation.LAYOUT_SCALE_TO_FIT)
                val fitW = minOf(spec.canvasW.toFloat(), spec.canvasH * t)
                val k = box.width() / fitW
                val tx = 2f * box.centerX() / spec.canvasW - 1f
                val ty = 1f - 2f * box.centerY() / spec.canvasH
                video += androidx.media3.effect.MatrixTransformation { _ ->
                    android.graphics.Matrix().apply { postScale(k, k); postTranslate(tx, ty) }
                }
                spec.overlay?.let { bmp -> video += OverlayEffect(listOf<TextureOverlay>(BitmapOverlay.createStaticBitmapOverlay(bmp))) }
            } else {
            // Crop works in the frame's own -1..1 space, y up.
            spec.crop?.let { c -> video += Crop(2f * c.left - 1f, 2f * c.right - 1f, 1f - 2f * c.bottom, 1f - 2f * c.top) }
            // A big shrink in halves, so detail survives the bilinear GPU scale (iOS uses Lanczos).
            for (s in rules.halvingSteps(keptShort, outShort)) video += Presentation.createForShortSide(s)
            video += if (onCanvas)
                Presentation.createForWidthAndHeight(spec.canvasW, spec.canvasH, Presentation.LAYOUT_SCALE_TO_FIT)
            else Presentation.createForShortSide(outShort.coerceAtLeast(2) / 2 * 2)
            // Drawn at its own pixel size over the frame, which is why it is made canvasW x canvasH.
            spec.overlay?.let { bmp -> video += OverlayEffect(listOf<TextureOverlay>(BitmapOverlay.createStaticBitmapOverlay(bmp))) }
            }
            val item = EditedMediaItem.Builder(builder.build())
                .setRemoveAudio(spec.mute)
                .setEffects(Effects(emptyList(), video))
                .build()
            try {
                t.start(item, out.absolutePath)
            } catch (_: Exception) {
                if (cont.isActive) cont.resume(false)
            }
            cont.invokeOnCancellation { runCatching { t.cancel() } }
        }
}
