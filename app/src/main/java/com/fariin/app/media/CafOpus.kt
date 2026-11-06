package com.fariin.app.media

import java.io.ByteArrayOutputStream

/**
 * iOS voice notes are Opus inside a CAF file (Kulan-iOS OpusVoiceEncoder.swift: 48 kHz, mono,
 * ~30 kbit/s, 20 ms packets, written through AudioFile as kAudioFileCAFType, `free` chunk
 * stripped). Android's media stack has an Opus decoder but no CAF reader, so the packets are
 * moved, untouched, into an Ogg Opus stream (RFC 7845), which Media3 plays.
 *
 * Older iOS notes are AAC in an m4a file; those play as they are ([isCaf] is false for them).
 *
 * Nothing is decoded or re-encoded here: the bytes of every Opus packet are copied as they are.
 */
object CafOpus {
    private const val OPUS = 0x6F707573   // 'opus'

    fun isCaf(b: ByteArray) = b.size >= 8 && b[0] == 'c'.code.toByte() && b[1] == 'a'.code.toByte() &&
        b[2] == 'f'.code.toByte() && b[3] == 'f'.code.toByte()

    class Parsed(val channels: Int, val sampleRate: Int, val priming: Int, val remainder: Int, val packets: List<ByteArray>)

    /** The Opus packets of a CAF file, or null when it is not CAF Opus or is damaged. */
    fun parse(b: ByteArray): Parsed? {
        if (!isCaf(b)) return null
        var p = 8
        var formatId = 0
        var channels = 1
        var sampleRate = 48_000.0
        var bytesPerPacket = 0
        var sizes: List<Int>? = null
        var priming = 0
        var remainder = 0
        var dataStart = -1
        var dataEnd = -1
        while (p + 12 <= b.size) {
            val type = String(b, p, 4, Charsets.US_ASCII)
            val size = be64(b, p + 4)
            val body = p + 12
            // -1 is "to the end of the file" (a data chunk that was never closed).
            // A size past the file (or one that would overflow) ends the parse: a crafted note must
            // not crash the player (review 2026-11-05).
            if (size >= 0 && size > (b.size - body).toLong() && type != "data") return null
            val end = if (size < 0 || size > (b.size - body).toLong()) b.size.toLong() else body + size
            if (end > b.size) {
                if (type != "data") return null
            }
            val stop = minOf(end, b.size.toLong()).toInt()
            when (type) {
                "desc" -> {
                    if (stop - body < 32) return null
                    sampleRate = java.lang.Double.longBitsToDouble(be64(b, body))
                    formatId = be32(b, body + 8)
                    bytesPerPacket = be32(b, body + 16)
                    channels = be32(b, body + 24)
                }
                "pakt" -> {
                    if (stop - body < 24) return null
                    val count = be64(b, body)
                    priming = be32(b, body + 16)
                    remainder = be32(b, body + 20)
                    var q = body + 24
                    val list = ArrayList<Int>(count.coerceIn(0, 1_000_000).toInt())
                    for (i in 0 until count) {
                        var v = 0L
                        while (true) {
                            if (q >= stop) return null
                            val c = b[q++].toInt() and 0xFF
                            v = (v shl 7) or (c and 0x7F).toLong()
                            if (v > Int.MAX_VALUE) return null
                            if (c and 0x80 == 0) break
                        }
                        list.add(v.toInt())
                        // A table with frame counts too (variable frames per packet) is not what
                        // iOS writes for Opus; it would show up as nonsense sizes and fail below.
                    }
                    sizes = list
                }
                "data" -> { dataStart = body + 4; dataEnd = stop }   // 4 bytes of edit count first
            }
            p = stop
            if (size < 0) break
        }
        if (formatId != OPUS || dataStart < 0 || dataStart > dataEnd) return null
        val packets = ArrayList<ByteArray>()
        var q = dataStart
        val table = sizes ?: if (bytesPerPacket > 0) List((dataEnd - dataStart) / bytesPerPacket) { bytesPerPacket } else return null
        for (s in table) {
            if (s <= 0 || q + s > dataEnd) return null
            packets.add(b.copyOfRange(q, q + s))
            q += s
        }
        if (packets.isEmpty()) return null
        return Parsed(channels.coerceIn(1, 2), sampleRate.toInt(), priming, remainder, packets)
    }

    /** Samples (at 48 kHz) in one Opus packet, from its TOC byte (RFC 6716 section 3.1). */
    fun samplesInPacket(p: ByteArray): Int {
        if (p.isEmpty()) return 0
        val toc = p[0].toInt() and 0xFF
        val config = toc shr 3
        val frame = when {
            config < 12 -> intArrayOf(480, 960, 1920, 2880)[config and 3]
            config < 16 -> intArrayOf(480, 960)[config and 1]
            else -> intArrayOf(120, 240, 480, 960)[config and 3]
        }
        val frames = when (toc and 3) {
            0 -> 1
            1, 2 -> 2
            else -> if (p.size < 2) 0 else p[1].toInt() and 0x3F
        }
        return frame * frames
    }

    /** CAF Opus to Ogg Opus, or null when [caf] is not CAF Opus. */
    fun toOgg(caf: ByteArray, serial: Int = 0x46415249): ByteArray? {
        val c = parse(caf) ?: return null
        val preSkip = c.priming.coerceIn(0, 65_535)
        val head = ByteArray(19).also {
            "OpusHead".toByteArray(Charsets.US_ASCII).copyInto(it)
            it[8] = 1
            it[9] = c.channels.toByte()
            le16(it, 10, preSkip)
            le32(it, 12, if (c.sampleRate > 0) c.sampleRate else 48_000)
            // output gain 0, mapping family 0
        }
        val vendor = "fariin".toByteArray()
        val tags = ByteArray(8 + 4 + vendor.size + 4).also {
            "OpusTags".toByteArray(Charsets.US_ASCII).copyInto(it)
            le32(it, 8, vendor.size)
            vendor.copyInto(it, 12)
        }
        val out = ByteArrayOutputStream(caf.size + caf.size / 8 + 512)
        var seq = 0
        page(out, listOf(head), granule = 0, serial = serial, seq = seq++, flags = 0x02)
        page(out, listOf(tags), granule = 0, serial = serial, seq = seq++, flags = 0)
        val total = c.packets.sumOf { samplesInPacket(it).toLong() }
        val last = (total - c.remainder.coerceAtLeast(0)).coerceAtLeast(0)
        var granule = 0L
        var i = 0
        while (i < c.packets.size) {
            val batch = ArrayList<ByteArray>()
            var segs = 0
            while (i < c.packets.size) {
                val need = c.packets[i].size / 255 + 1
                if (batch.isNotEmpty() && (segs + need > 255 || batch.size >= 50)) break
                batch.add(c.packets[i]); segs += need
                granule += samplesInPacket(c.packets[i])
                i++
            }
            val end = i == c.packets.size
            val g = preSkip + (if (end) minOf(granule, last) else granule)
            page(out, batch, granule = g, serial = serial, seq = seq++, flags = if (end) 0x04 else 0)
        }
        return out.toByteArray()
    }

    private fun page(out: ByteArrayOutputStream, packets: List<ByteArray>, granule: Long, serial: Int, seq: Int, flags: Int) {
        val lacing = ByteArrayOutputStream()
        for (p in packets) {
            repeat(p.size / 255) { lacing.write(255) }
            lacing.write(p.size % 255)
        }
        val lace = lacing.toByteArray()
        require(lace.size <= 255)
        val h = ByteArray(27 + lace.size)
        "OggS".toByteArray(Charsets.US_ASCII).copyInto(h)
        h[4] = 0
        h[5] = flags.toByte()
        for (k in 0 until 8) h[6 + k] = (granule ushr (8 * k)).toByte()
        le32(h, 14, serial)
        le32(h, 18, seq)
        h[26] = lace.size.toByte()
        lace.copyInto(h, 27)
        val body = ByteArrayOutputStream().also { b -> packets.forEach { b.write(it) } }.toByteArray()
        var crc = crc(0, h)
        crc = crc(crc, body)
        le32(h, 22, crc)
        out.write(h); out.write(body)
    }

    private val table = IntArray(256) { n ->
        var r = n shl 24
        repeat(8) { r = if (r and 0x80000000.toInt() != 0) (r shl 1) xor 0x04C11DB7 else r shl 1 }
        r
    }

    /** The Ogg checksum: CRC-32, polynomial 0x04C11DB7, not reflected, initial 0, no final xor. */
    fun crc(start: Int, b: ByteArray): Int {
        var c = start
        for (x in b) c = (c shl 8) xor table[((c ushr 24) xor (x.toInt() and 0xFF)) and 0xFF]
        return c
    }

    private fun be32(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    private fun be64(b: ByteArray, o: Int): Long = (be32(b, o).toLong() shl 32) or (be32(b, o + 4).toLong() and 0xFFFFFFFFL)

    private fun le16(b: ByteArray, o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte() }
    private fun le32(b: ByteArray, o: Int, v: Int) { for (k in 0 until 4) b[o + k] = (v ushr (8 * k)).toByte() }
}
