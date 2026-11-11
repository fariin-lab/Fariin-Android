package com.fariin.app.media

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Puts an MP4's index (`moov`) in front of its samples (`mdat`), so a player that reads the file
 * from the network can start after the first few hundred KB instead of fetching the tail first
 * (iOS VideoTranscoder sets shouldOptimizeForNetworkUse for the same reason). No re-encode: the
 * top-level boxes are reordered and every chunk offset in `stco` / `co64` is moved by the size of
 * the index. A file that is already in order, fragmented, or not understood is left as it is.
 */
object Faststart {
    private class Box(val type: String, val offset: Long, val size: Long)

    /** True when the file was rewritten with the index first. */
    fun apply(file: File): Boolean {
        val boxes = topLevel(file) ?: return false
        if (boxes.any { it.type == "moof" }) return false
        val moov = boxes.singleOrNull { it.type == "moov" } ?: return false
        val mdat = boxes.firstOrNull { it.type == "mdat" } ?: return false
        if (moov.offset < mdat.offset) return false
        if (moov.size > 64L * 1024 * 1024) return false
        val index = ByteArray(moov.size.toInt())
        RandomAccessFile(file, "r").use { it.seek(moov.offset); it.readFully(index) }
        // Data between the first mdat and the old index moves down by the index's size; data after
        // the old index stays where it was (the index left from before it and came back in front).
        val shift = moov.size
        val from = mdat.offset
        val until = moov.offset
        if (!shiftOffsets(ByteBuffer.wrap(index), 8, index.size, shift, from, until)) return false
        val tmp = File(file.parentFile, file.name + ".fs" + System.nanoTime())
        try {
            RandomAccessFile(file, "r").use { src ->
                tmp.outputStream().use { out ->
                    val ch = out.channel
                    val inCh = src.channel
                    for (b in boxes) {
                        if (b === moov) continue
                        if (b === mdat) out.write(index)
                        var pos = b.offset
                        val end = b.offset + b.size
                        while (pos < end) pos += inCh.transferTo(pos, end - pos, ch)
                    }
                }
            }
            if (tmp.length() != file.length()) { tmp.delete(); return false }
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) { tmp.delete(); return false }
            }
            return true
        } catch (e: Exception) {
            tmp.delete()
            return false
        }
    }

    /** True when the index comes before the samples; null when the file is not a readable MP4. */
    fun moovFirst(file: File): Boolean? {
        val boxes = topLevel(file) ?: return null
        val moov = boxes.firstOrNull { it.type == "moov" } ?: return null
        val mdat = boxes.firstOrNull { it.type == "mdat" } ?: return null
        return moov.offset < mdat.offset
    }

    private fun topLevel(file: File): List<Box>? = runCatching {
        RandomAccessFile(file, "r").use { f ->
            val len = f.length()
            val out = ArrayList<Box>()
            var pos = 0L
            val head = ByteArray(16)
            while (pos < len) {
                if (len - pos < 8) return null
                f.seek(pos); f.readFully(head, 0, 8)
                val bb = ByteBuffer.wrap(head)
                var size = bb.getInt(0).toLong() and 0xFFFFFFFFL
                val type = String(head, 4, 4, Charsets.ISO_8859_1)
                when (size) {
                    1L -> { if (len - pos < 16) return null; f.readFully(head, 8, 8); size = bb.getLong(8) }
                    0L -> size = len - pos
                }
                if (size < 8 || pos + size > len) return null
                out += Box(type, pos, size)
                pos += size
            }
            out
        }
    }.getOrNull()

    private val CONTAINERS = setOf("moov", "trak", "mdia", "minf", "stbl", "edts", "dinf", "udta", "mvex")

    /** Walks the boxes in buf[start, end) and moves every chunk offset in [from, until) by [shift]. */
    private fun shiftOffsets(buf: ByteBuffer, start: Int, end: Int, shift: Long, from: Long, until: Long): Boolean {
        var p = start
        while (p + 8 <= end) {
            var size = buf.getInt(p).toLong() and 0xFFFFFFFFL
            val type = String(ByteArray(4) { buf.get(p + 4 + it) }, Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L) { if (p + 16 > end) return false; size = buf.getLong(p + 8); header = 16 }
            else if (size == 0L) size = (end - p).toLong()
            if (size < header || p + size > end) return false
            val body = p + header
            val boxEnd = (p + size).toInt()
            when (type) {
                "cmov" -> return false
                "stco" -> {
                    val n = buf.getInt(body + 4).toLong() and 0xFFFFFFFFL
                    if (body + 8 + n * 4 > boxEnd) return false
                    for (i in 0 until n.toInt()) {
                        val at = body + 8 + i * 4
                        val v = buf.getInt(at).toLong() and 0xFFFFFFFFL
                        if (v in from until until) {
                            val moved = v + shift
                            if (moved > 0xFFFFFFFFL) return false
                            buf.putInt(at, moved.toInt())
                        }
                    }
                }
                "co64" -> {
                    val n = buf.getInt(body + 4).toLong() and 0xFFFFFFFFL
                    if (body + 8 + n * 8 > boxEnd) return false
                    for (i in 0 until n.toInt()) {
                        val at = body + 8 + i * 8
                        val v = buf.getLong(at)
                        if (v in from until until) buf.putLong(at, v + shift)
                    }
                }
                in CONTAINERS -> if (!shiftOffsets(buf, body, boxEnd, shift, from, until)) return false
            }
            p = boxEnd
        }
        return true
    }
}
