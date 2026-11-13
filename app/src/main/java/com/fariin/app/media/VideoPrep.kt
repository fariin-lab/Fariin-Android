package com.fariin.app.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.VideoEncoderSettings
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
        /** Limits.videoMessageBytes: the send cap, 100 MB (owner 2026-11-13). */
        const val MAX_BYTES = MediaRules.VIDEO_MESSAGE_BYTES

        /** [onProgress] gets the compression's 0..1 (a long clip takes minutes; the ring shows it). */
        suspend fun fromUri(context: Context, uri: Uri, outDir: File, id: String, onProgress: ((Float) -> Unit)? = null): VideoPrep? {
            val out = File(outDir, "send-$id.mp4")
            val cut = VideoTrim.take(uri)
            // A trimmed clip must go trimmed: if the full re-encode fails (some phones and the
            // emulator have no encoder for it), the cut alone is tried before the uncut original.
            val shortSide = if (SentQuality.videoHd(context, uri)) 1080 else 540
            // A long clip aims at the export target (62 MB, as iOS): at the fixed 2 Mbit/s a
            // 10-minute video came out near 150 MB. Short clips keep the usual bitrate. One encode
            // only (owner 2026-11-13: no second, lower-quality pass); an overshoot up to the
            // 100 MB cap still sends.
            val seconds = (cut?.let { it.last - it.first } ?: lengthMs(context, uri)) / 1000.0
            val first = minOf(bitrateFor(shortSide), fitBitrate(seconds))
            val ok = transcode(context, uri, out, cut, shortSide = shortSide, bitrate = first, onProgress = onProgress) ||
                (cut != null && transcode(context, uri, out, cut, resize = false, onProgress = onProgress))
            val file = if (ok && out.length() > 0) out else copyOriginal(context, uri, out) ?: return null
            if (file.length() > MAX_BYTES) { file.delete(); return null }
            return describe(file)
        }

        /** Length, size and poster of a clip already on disk (also used for a forwarded video). */
        suspend fun describe(file: File, posterJpeg: ByteArray? = null): VideoPrep? = withContext(Dispatchers.IO) {
            // The index goes in front of the samples, so the receiver's player can start while the
            // rest comes down (iOS shouldOptimizeForNetworkUse). Transcoded, original or forwarded.
            runCatching { Faststart.apply(file) }
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

        /** [cut] is the editor's kept range in ms; when the cut cannot be made the whole original goes (iOS exportTrimmed nil). */
        private suspend fun transcode(context: Context, uri: Uri, out: File, cut: LongRange?, resize: Boolean = true, shortSide: Int = 540,
                                      bitrate: Int = bitrateFor(shortSide), onProgress: ((Float) -> Unit)? = null): Boolean = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val t = Transformer.Builder(context)
                    .setMuxerFactory(NoLocationMuxerFactory())   // never the place it was filmed
                    .apply { if (resize) setEncoderFactory(encoderFor(context, bitrate)) }
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
                val media = MediaItem.Builder().setUri(uri).apply {
                    if (cut != null) setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(cut.first).setEndPositionMs(cut.last).build())
                }.build()
                val item = EditedMediaItem.Builder(media)
                    .apply { if (resize) setEffects(Effects(emptyList(), listOf(Presentation.createForShortSide(shortSide)))) }
                    .build()
                try {
                    t.start(item, out.absolutePath)
                } catch (_: Exception) {
                    if (cont.isActive) cont.resume(false)
                }
                // The export's own progress, read every 250 ms on the Transformer's thread (main).
                val tick = android.os.Handler(android.os.Looper.getMainLooper())
                val holder = androidx.media3.transformer.ProgressHolder()
                val poll = object : Runnable {
                    override fun run() {
                        if (!cont.isActive) return
                        if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress?.invoke(holder.progress / 100f)
                        tick.postDelayed(this, 250)
                    }
                }
                if (onProgress != null) tick.post(poll)
                cont.invokeOnCancellation { tick.removeCallbacks(poll); runCatching { t.cancel() } }
            }
        }

        /** A steady bitrate for a resized clip: about 2 Mbit/s at 540 (iOS's 960x540 export), 5 at
         *  1080. Small enough to stream on a mobile network, the encoder's own choice otherwise. */
        fun bitrateFor(shortSide: Int): Int = if (shortSide >= 1080) 5_000_000 else 2_000_000

        private fun encoderFor(context: Context, bitrate: Int) = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
            .build()

        /** The video bitrate that puts [seconds] of clip at the export target, leaving room for the
         *  sound (up to 192 kbit/s); never below 250 kbit/s. Unknown length: no cap. */
        fun fitBitrate(seconds: Double): Int {
            if (seconds <= 0) return Int.MAX_VALUE
            val total = MediaRules.VIDEO_EXPORT_TARGET_BYTES * 8.0 / seconds
            return (total - 192_000).toInt().coerceAtLeast(250_000)
        }

        private fun lengthMs(context: Context, uri: Uri): Long {
            val r = MediaMetadataRetriever()
            return try {
                r.setDataSource(context, uri)
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } catch (_: Exception) { 0L } finally { runCatching { r.release() } }
        }

        private suspend fun copyOriginal(context: Context, uri: Uri, out: File): File? = withContext(Dispatchers.IO) {
            val type = context.contentResolver.getType(uri) ?: ""
            if (type.isNotEmpty() && type != "video/mp4") return@withContext null
            runCatching {
                val raw = File(out.parentFile, out.name + ".raw")
                context.contentResolver.openInputStream(uri)?.use { i -> raw.outputStream().use { i.copyTo(it) } }
                try {
                    if (raw.length() == 0L) return@runCatching null
                    // The original as it is only when it holds no place; otherwise its samples are
                    // copied into a new file without the location, or it is not sent at all.
                    if (!VideoLocation.hasLocation(raw)) { raw.copyTo(out, overwrite = true); out }
                    else VideoLocation.stripped(raw, out)?.takeIf { !VideoLocation.hasLocation(it) }
                } finally { raw.delete() }
            }.getOrNull()
        }
    }
}

/**
 * The editor's trim for a clip it hands to the send (iOS trimStart/trimEnd), read once by
 * [VideoPrep.fromUri] and then forgotten, as [SentQuality.choose] is for a photo's HD.
 */
object VideoTrim {
    private val cuts = java.util.concurrent.ConcurrentHashMap<String, LongRange>()
    fun choose(uri: Uri, startMs: Long, endMs: Long) { cuts[uri.toString()] = startMs..endMs }
    fun take(uri: Uri): LongRange? = cuts.remove(uri.toString())
}
