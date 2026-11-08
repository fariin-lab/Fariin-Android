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
 * the short side at 540 (iOS stories export at 540p) and a capped bitrate, so a 90 second piece is
 * about 15 MB and always fits the story file limit. Never the place it was filmed.
 */
object StoryVideoCut {
    private const val SHORT_SIDE = 540

    /**
     * A clip as the story editor left it (iOS VideoTranscoder.prepare with burnIn): [range] of it (ms,
     * null = all), its sound taken off for [mute], [crop] (the part the pinch kept, 0 to 1 of the
     * frame, y down) and, when [canvasW] is set, the frame laid on the story card's shape with
     * [overlay] (the card's canvas round the clip, then the text, pen and stickers, exactly
     * canvasW x canvasH) burned in over it. Without a canvas it is 540 on the short side like [cut].
     */
    class Edit(
        val range: LongRange?,
        val mute: Boolean,
        val crop: android.graphics.RectF?,
        val canvasW: Int,
        val canvasH: Int,
        val overlay: android.graphics.Bitmap?,
    )

    suspend fun cut(context: Context, uri: Uri, out: File, range: LongRange): Boolean =
        edit(context, uri, out, Edit(range, mute = false, crop = null, canvasW = 0, canvasH = 0, overlay = null))

    suspend fun edit(context: Context, uri: Uri, out: File, spec: Edit): Boolean = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val bitrate = com.fariin.app.data.StoryCameraRules.videoBitrate(SHORT_SIDE * 16 / 9,
                seconds = com.fariin.app.data.StoryLimits.VIDEO_SECONDS)
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
            // Crop works in the frame's own -1..1 space, y up.
            spec.crop?.let { c -> video += Crop(2f * c.left - 1f, 2f * c.right - 1f, 1f - 2f * c.bottom, 1f - 2f * c.top) }
            video += if (spec.canvasW > 0 && spec.canvasH > 0)
                Presentation.createForWidthAndHeight(spec.canvasW, spec.canvasH, Presentation.LAYOUT_SCALE_TO_FIT)
            else Presentation.createForShortSide(SHORT_SIDE)
            // Drawn at its own pixel size over the frame, which is why it is made canvasW x canvasH.
            spec.overlay?.let { bmp -> video += OverlayEffect(listOf<TextureOverlay>(BitmapOverlay.createStaticBitmapOverlay(bmp))) }
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
}
