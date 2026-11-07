package com.fariin.app.media

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
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

    suspend fun cut(context: Context, uri: Uri, out: File, range: LongRange): Boolean = withContext(Dispatchers.Main) {
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
            val media = MediaItem.Builder().setUri(uri).setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(range.first).setEndPositionMs(range.last).build(),
            ).build()
            val item = EditedMediaItem.Builder(media)
                .setEffects(Effects(emptyList(), listOf(Presentation.createForShortSide(SHORT_SIDE))))
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
