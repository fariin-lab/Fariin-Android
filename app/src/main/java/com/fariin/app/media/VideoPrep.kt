package com.fariin.app.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume

/**
 * A picked video made ready to send, like iOS VideoTranscoder: H.264 mp4 with the short side at
 * most 540 (iOS AVAssetExportPreset960x540, its default), plus the poster frame (a JPEG made the
 * same way as a photo, so it gets the same blurhash and inline thumb) and the length and size.
 * If the phone cannot transcode the clip, the original is sent when it is an mp4 under the limit.
 */
class VideoPrep(
    val file: File,
    val poster: PhotoPrep,
    val duration: Double,
    val width: Int,
    val height: Int,
) {
    companion object {
        /** Limits.videoMessageBytes: storage.rules `chat/` takes 64 MB. */
        const val MAX_BYTES = 64L * 1024 * 1024

        suspend fun fromUri(context: Context, uri: Uri, outDir: File, id: String): VideoPrep? {
            val out = File(outDir, "send-$id.mp4")
            val ok = transcode(context, uri, out)
            val file = if (ok && out.length() > 0) out else copyOriginal(context, uri, out) ?: return null
            if (file.length() > MAX_BYTES) { file.delete(); return null }
            return describe(file)
        }

        /** Length, size and poster of a clip already on disk (also used for a forwarded video). */
        suspend fun describe(file: File, posterJpeg: ByteArray? = null): VideoPrep? = withContext(Dispatchers.IO) {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(file.absolutePath)
                val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
                val poster = posterJpeg?.let { PhotoPrep.fromBytes(it) } ?: run {
                    val frame = r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return@withContext null
                    val jpeg = ByteArrayOutputStream().also { frame.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
                    PhotoPrep.fromBytes(jpeg)
                } ?: return@withContext null
                if (w <= 0 || h <= 0) { w = poster.width; h = poster.height }
                VideoPrep(file, poster, ms / 1000.0, w, h)
            } catch (_: Exception) {
                null
            } finally {
                runCatching { r.release() }
            }
        }

        private suspend fun transcode(context: Context, uri: Uri, out: File): Boolean = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val t = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (cont.isActive) cont.resume(true)
                        }
                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            if (cont.isActive) cont.resume(false)
                        }
                    })
                    .build()
                val item = EditedMediaItem.Builder(MediaItem.fromUri(uri))
                    .setEffects(Effects(emptyList(), listOf(Presentation.createForShortSide(540))))
                    .build()
                try {
                    t.start(item, out.absolutePath)
                } catch (_: Exception) {
                    if (cont.isActive) cont.resume(false)
                }
                cont.invokeOnCancellation { runCatching { t.cancel() } }
            }
        }

        private suspend fun copyOriginal(context: Context, uri: Uri, out: File): File? = withContext(Dispatchers.IO) {
            val type = context.contentResolver.getType(uri) ?: ""
            if (type.isNotEmpty() && type != "video/mp4") return@withContext null
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { i -> out.outputStream().use { i.copyTo(it) } }
                out.takeIf { it.length() > 0 }
            }.getOrNull()
        }
    }
}
