package com.fariin.app.media

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.container.Mp4LocationData
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.Muxer
import androidx.media3.transformer.DefaultMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * A sent video carries NO place (owner 2026-11-05: "does a photo take my location?"). Photos are
 * rebuilt from pixels (PhotoPrep), but the video transcoder copies the camera's GPS (Mp4LocationData)
 * into the file it writes, and the fallback sent the original as it was. This muxer drops every
 * location entry, from the metadata calls and from each track's format.
 */
class NoLocationMuxerFactory(private val inner: Muxer.Factory = DefaultMuxer.Factory()) : Muxer.Factory {
    override fun create(path: String): Muxer {
        val m = inner.create(path)
        return object : Muxer {
            override fun addTrack(format: Format): Int = m.addTrack(withoutLocation(format))
            override fun writeSampleData(trackId: Int, data: ByteBuffer, info: BufferInfo) = m.writeSampleData(trackId, data, info)
            override fun addMetadataEntry(entry: Metadata.Entry) { if (entry !is Mp4LocationData) m.addMetadataEntry(entry) }
            override fun close() = m.close()
        }
    }

    override fun getSupportedSampleMimeTypes(trackType: Int) = inner.getSupportedSampleMimeTypes(trackType)

    companion object {
        fun withoutLocation(f: Format): Format {
            val md = f.metadata ?: return f
            val kept = (0 until md.length()).map { md.get(it) }.filter { it !is Mp4LocationData }
            if (kept.size == md.length()) return f
            return f.buildUpon().setMetadata(if (kept.isEmpty()) null else Metadata(*kept.toTypedArray())).build()
        }
    }
}

object VideoLocation {
    /** The file says where it was filmed (an "+lat+lon/" location tag). */
    fun hasLocation(file: File): Boolean = runCatching {
        val r = MediaMetadataRetriever()
        try { r.setDataSource(file.absolutePath); !r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION).isNullOrBlank() }
        finally { r.release() }
    }.getOrDefault(true)

    /**
     * The same audio and video samples, copied into a new MP4 without the location (no re-encode).
     * Null when it can not be done; the caller then does not send the original.
     */
    fun stripped(src: File, out: File): File? = runCatching {
        val ex = MediaExtractor()
        val mux = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            ex.setDataSource(src.absolutePath)
            val map = HashMap<Int, Int>()
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(android.media.MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
                map[i] = mux.addTrack(f)
            }
            if (map.isEmpty()) return@runCatching null
            val rot = runCatching {
                val r = MediaMetadataRetriever()
                try { r.setDataSource(src.absolutePath); r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0 }
                finally { r.release() }
            }.getOrDefault(0)
            mux.setOrientationHint(rot)
            mux.start()
            val buf = ByteBuffer.allocate(4 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            for ((t, outTrack) in map) {
                ex.selectTrack(t)
                while (true) {
                    val n = ex.readSampleData(buf, 0)
                    if (n < 0) break
                    info.set(0, n, ex.sampleTime, ex.sampleFlags)
                    mux.writeSampleData(outTrack, buf, info)
                    ex.advance()
                }
                ex.unselectTrack(t)
            }
            mux.stop()
            out.takeIf { it.length() > 0 }
        } finally {
            runCatching { mux.release() }
            ex.release()
        }
    }.getOrNull()
}
