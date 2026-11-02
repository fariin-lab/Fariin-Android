package com.fariin.app.media

import com.fariin.app.crypto.EncMeta
import com.fariin.app.crypto.TestSodium
import com.fariin.app.crypto.Wire
import com.fariin.app.data.Message
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTest {
    private fun res(name: String) = javaClass.classLoader!!.getResourceAsStream("media/$name")!!.readBytes()

    // ---------------------------------------------------------------- CAF Opus (iOS voice notes)

    /** short.caf: 1 s of 48 kHz mono Opus at 30 kbit/s in 20 ms packets, laid out as Apple's
     *  AudioFile writes CAF (desc, chan, kuki, free, pakt with BER sizes, data with edit count),
     *  made from short.opus (ffmpeg libopus). */
    @Test fun cafParsesAndKeepsEveryPacket() {
        val caf = res("short.caf")
        val p = CafOpus.parse(caf)!!
        assertEquals(1, p.channels)
        assertEquals(48_000, p.sampleRate)
        assertEquals(312, p.priming)
        assertEquals(51, p.packets.size)
        assertTrue(p.packets.all { CafOpus.samplesInPacket(it) == 960 })
        // The packets are the very ones inside the Ogg file the CAF was made from.
        val ogg = oggPackets(res("short.opus")).drop(2)
        assertEquals(ogg.size, p.packets.size)
        ogg.zip(p.packets).forEach { (a, b) -> assertArrayEquals(a, b) }
    }

    @Test fun cafToOggIsAValidOpusStream() {
        val out = CafOpus.toOgg(res("short.caf"))!!
        val pages = pages(out)
        assertTrue(pages.size >= 3)
        // Every page's checksum is right (recomputed with the field zeroed).
        for ((start, len) in pages) {
            val page = out.copyOfRange(start, start + len)
            val stored = le32(page, 22)
            for (k in 22..25) page[k] = 0
            assertEquals(stored, CafOpus.crc(0, page))
        }
        val packets = oggPackets(out)
        assertEquals("OpusHead", String(packets[0], 0, 8))
        assertEquals(1, packets[0][9].toInt())                        // mono
        assertEquals(312, (packets[0][10].toInt() and 0xFF) or ((packets[0][11].toInt() and 0xFF) shl 8))
        assertEquals("OpusTags", String(packets[1], 0, 8))
        assertEquals(51, packets.size - 2)
        // First page begins the stream, last ends it, and the last granule is pre-skip + samples.
        assertEquals(0x02, out[pages.first().first + 5].toInt())
        assertEquals(0x04, out[pages.last().first + 5].toInt())
        val g = le64(out, pages.last().first + 6)
        assertEquals(312L + 51 * 960, g)
    }

    @Test fun oggChecksumMatchesFfmpegPages() {
        val ogg = res("short.opus")
        for ((start, len) in pages(ogg)) {
            val page = ogg.copyOfRange(start, start + len)
            val stored = le32(page, 22)
            for (k in 22..25) page[k] = 0
            assertEquals(stored, CafOpus.crc(0, page))
        }
    }

    @Test fun notCafIsLeftAlone() {
        val m4a = byteArrayOf(0, 0, 0, 0x20, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte())
        assertFalse(CafOpus.isCaf(m4a))
        assertNull(CafOpus.toOgg(m4a))
        assertNull(CafOpus.parse("caff".toByteArray() + ByteArray(20)))   // no desc, no data
    }

    @Test fun opusTocSamples() {
        assertEquals(960, CafOpus.samplesInPacket(byteArrayOf((1 shl 3).toByte())))       // SILK 20 ms
        assertEquals(1920, CafOpus.samplesInPacket(byteArrayOf(((31 shl 3) or 1).toByte()))) // CELT 20 ms x2
        assertEquals(480 * 3, CafOpus.samplesInPacket(byteArrayOf(((18 shl 3) or 3).toByte(), 3)))   // CELT 10 ms x3 (code 3)
    }

    // ---------------------------------------------------------------- EncMeta round trip on a real file

    @Test fun encryptedPhotoAndVoiceRoundTrip() {
        val wire = Wire(TestSodium.nacl)
        for (name in listOf("photo_portrait.jpg", "short.caf")) {
            val file = res(name)
            val (ct, meta) = wire.sealFile(file, TestSodium.bobPk, TestSodium.aliceSk)
            assertEquals(file.size + 16, ct.size)
            // Through Firestore and back: the map the message carries.
            val back = EncMeta.fromMap(meta.asMap().mapValues { (k, v) -> if (k == "v") (v as Int).toLong() else v })!!
            assertArrayEquals(file, wire.openFile(ct, back, listOf(TestSodium.alicePk), TestSodium.bobSk)!!.first)
            // A flipped byte in Storage is refused, never shown.
            val bad = ct.copyOf().also { it[ct.size / 2] = (it[ct.size / 2].toInt() xor 1).toByte() }
            assertNull(wire.openFile(bad, back, listOf(TestSodium.alicePk), TestSodium.bobSk))
        }
        // Group file, opened by another member.
        val file = res("photo_portrait.jpg")
        val (ct, meta) = wire.sealGroupFile(file, "alice", listOf("alice", "bob"),
            mapOf("alice" to TestSodium.alicePk, "bob" to TestSodium.bobPk), TestSodium.aliceSk)
        assertArrayEquals(file, wire.openGroupFile(ct, EncMeta.fromMap(meta.asMap())!!, "bob", listOf(TestSodium.alicePk), TestSodium.bobSk))
    }

    // ---------------------------------------------------------------- size and compression rules

    @Test fun photoQualityRules() {
        assertEquals(listOf(1280, 960, 720), MediaRules.sideLadder(MediaRules.PhotoQuality.STANDARD))
        assertEquals(listOf(2560, 1920, 1440, 1080, 810), MediaRules.sideLadder(MediaRules.PhotoQuality.HIGH))
        assertEquals(1_572_864, MediaRules.PhotoQuality.STANDARD.maxBytes)
        assertEquals(60, MediaRules.PhotoQuality.STANDARD.jpegQuality)
        assertEquals(1280 to 720, MediaRules.fit(4000, 2250, 1280))
        assertEquals(720 to 1280, MediaRules.fit(3024, 5376, 1280))
        assertEquals(300 to 200, MediaRules.fit(300, 200, 1280))      // never enlarged
        assertEquals(1 to 1, MediaRules.fit(0, 0, 1280))
        assertEquals(40 to 30, MediaRules.fit(4000, 3000, MediaRules.THUMB_SIDE))
        assertEquals(64L * 1024 * 1024, MediaRules.STORAGE_CHAT_LIMIT)
    }

    @Test fun photoBoxLikeIos() {
        // Square at the 272 cap: 272 x 272. A 9:16 grows taller (1.2 x cap) and narrower.
        assertEquals(272f to 272f, MediaRules.photoBox(1000.0, 1000.0, 272f))
        val (w, h) = MediaRules.photoBox(720.0, 1280.0, 272f)
        assertEquals(184f, w); assertEquals(326f, h)
        // Landscape: full width.
        assertEquals(272f to 181f, MediaRules.photoBox(1280.0, 853.0, 272f))
        // No size known: square.
        assertEquals(272f to 272f, MediaRules.photoBox(null, null, 272f))
        // A tiny original is not blown up past 150.
        assertEquals(150f to 150f, MediaRules.photoBox(40.0, 40.0, 272f))
    }

    @Test fun labelsAndWaveform() {
        assertEquals("0:53", MediaRules.durationLabel(52.6))
        assertEquals("1:05", MediaRules.durationLabel(65.0))
        assertEquals("🎤 Voice message · 0:07", MediaRules.voicePreview(7.2))
        assertEquals("📷 Photo", MediaRules.PHOTO_PREVIEW)
        assertEquals(listOf(1.5f, 2f, 0.5f, 1f), generateSequence(1f) { MediaRules.nextRate(it) }.drop(1).take(4).toList())
        // 80 readings into 40 RMS bars, 0...100.
        val bars = MediaRules.bars(List(80) { if (it % 2 == 0) 1f else 0f })
        assertEquals(40, bars.size)
        assertEquals(71, bars[0])                                      // sqrt(0.5) * 100
        assertEquals(emptyList<Int>(), MediaRules.bars(emptyList()))
        assertEquals(0f, MediaRules.level(0))
        assertEquals(1f, MediaRules.level(32767))
        assertEquals(0f, MediaRules.display(0))
        assertTrue(MediaRules.display(100) <= 1f && MediaRules.display(100) > 0.82f)
        // 40 bars in an 80 px wave with 4 px slots: pairs merged, the louder one kept.
        val merged = MediaRules.drawBars(List(40) { it }, 80f, 4f)
        assertEquals(20, merged.size); assertEquals(1, merged[0]); assertEquals(39, merged[19])
        assertEquals(listOf(1, 5, 9, 2), MediaRules.drawBars(listOf(1, 5, 9, 2), 8f, 4f))   // at least 8 bars kept
        assertEquals(listOf("chat/a_b/m1.enc", "chat/a_b/m1.m4a.enc"),
            listOf(MediaRules.imagePath("a_b", "m1"), MediaRules.audioPath("a_b", "m1")))
    }

    @Test fun blurHashRoundTrip() {
        val w = 32; val h = 24
        val px = IntArray(w * h) { i -> if (i % w < w / 2) 0xFFD04030.toInt() else 0xFF3050D0.toInt() }
        val hash = BlurHash.encode(px, w, h)!!
        assertEquals(28, hash.length)                                 // 4 x 3: 4 + 2 * 12
        val back = BlurHash.decode(hash, 32, 32)!!
        val left = back[16 * 32 + 2]; val right = back[16 * 32 + 29]
        assertTrue((left shr 16 and 0xFF) > (left and 0xFF))           // red side stays red
        assertTrue((right and 0xFF) > (right shr 16 and 0xFF))         // blue side stays blue
        assertNotNull(BlurHash.decode("LKO2?U%2Tw=w]~RBVZRi};RPxuwH"))
        assertNull(BlurHash.decode("short"))
        assertNull(BlurHash.decode("LKO2?U%2Tw=w]~RBVZRi};RPxuw"))   // wrong length
    }

    // ---------------------------------------------------------------- message fields (iOS writes)

    @Test fun imageAndAudioWritesMatchIos() {
        val meta = EncMeta(1, "n", "k", "kn")
        val img = MediaMessages.image("me", "https://x", meta, "enc1:c", 10.0, "C1", 720, 1280, "enc1:b", "enc1:t", "TS")
        assertEquals(setOf("type", "enc", "text", "imageUrl", "authorId", "createdAt", "clientTs", "clientId",
            "width", "height", "blurhash", "thumb"), img.keys)
        assertEquals("image", img["type"]); assertEquals(720.0, img["width"]); assertEquals(meta.asMap(), img["enc"])
        val img2 = MediaMessages.image("me", "u", meta, "", 1.0, null, null, null, null, "", "TS")
        assertFalse(img2.containsKey("width") || img2.containsKey("blurhash") || img2.containsKey("thumb") || img2.containsKey("clientId"))

        val audio = MediaMessages.audio("me", meta, 7.5, listOf(1, 2), 10.0, "C2", "TS")
        assertEquals(setOf("type", "duration", "waveform", "enc", "text", "authorId", "createdAt", "clientTs",
            "uploading", "clientId"), audio.keys)
        assertEquals("audio", audio["type"]); assertEquals(true, audio["uploading"]); assertEquals("", audio["text"])
        assertFalse(audio.containsKey("audioUrl"))
        assertEquals(mapOf("audioUrl" to "u", "uploading" to "DEL"), MediaMessages.attachAudio("u", "DEL"))

        val conv = MediaMessages.convUpdate("me", MediaRules.PHOTO_PREVIEW, listOf("me", "a", "b"), "TS", "+1", "u", meta)
        assertEquals(setOf("lastMessage", "lastSender", "updatedAt", "lastImageUrl", "lastImageEnc",
            "unreadCount.a", "unreadCount.b"), conv.keys)
        val c2 = MediaMessages.convUpdate("me", "x", listOf("a"), "TS", "+1")
        assertFalse(c2.containsKey("lastImageUrl"))
    }

    @Test fun mediaMessageReadsLikeIos() {
        val open: (String, String) -> String = { raw, _ -> when (raw) {
            "enc1:bh" -> "LKO2?U%2Tw=w]~RBVZRi};RPxuwH"; "enc1:th" -> "AAAA"; "enc1:bad" -> Wire.LOCKED; else -> raw } }
        val doc = mapOf<String, Any?>(
            "type" to "image", "authorId" to "a", "text" to "", "imageUrl" to "https://i",
            "enc" to mapOf("v" to 1L, "n" to "n", "k" to "k", "kn" to "kn"),
            "width" to 720.0, "height" to 1280L, "blurhash" to "enc1:bh", "thumb" to "enc1:bad",
        )
        val m = Message.fromFirestore("m1", doc, "a_b", open = open)
        assertEquals(Message.MediaKind.PHOTO, m.mediaKind)
        assertEquals(1, m.enc!!.v); assertEquals(1280.0, m.height)
        assertEquals("LKO2?U%2Tw=w]~RBVZRi};RPxuwH", m.blurhash)
        assertNull(m.thumb)                                            // the lock sentinel is no thumbnail
        val v = Message.fromFirestore("m2", mapOf("type" to "audio", "authorId" to "a", "text" to "", "duration" to 7L,
            "waveform" to listOf(1L, 50L), "uploading" to true), "a_b", open = open)
        assertEquals(Message.MediaKind.VOICE, v.mediaKind)
        assertEquals(7.0, v.duration); assertEquals(listOf(1, 50), v.waveform); assertTrue(v.uploading)
        val vid = Message.fromFirestore("m3", mapOf("type" to "video", "authorId" to "a", "thumbUrl" to "t",
            "thumbEnc" to mapOf("v" to 0L, "n" to "", "k" to "", "kn" to ""), "duration" to 3.0), "g1", open = open)
        assertEquals(Message.MediaKind.VIDEO, vid.mediaKind); assertTrue(vid.thumbEnc!!.isPlain)
        val album = Message.fromFirestore("m4", mapOf("type" to "album", "authorId" to "a",
            "album" to listOf(mapOf("imageUrl" to "u1", "width" to 1.0, "height" to 2.0))), "g1", open = open)
        assertEquals(Message.MediaKind.ALBUM, album.mediaKind); assertEquals("u1", album.album[0].imageUrl)
    }

    // ---------------------------------------------------------------- Ogg helpers

    private fun le32(b: ByteArray, o: Int) = (0 until 4).fold(0) { a, k -> a or ((b[o + k].toInt() and 0xFF) shl (8 * k)) }
    private fun le64(b: ByteArray, o: Int) = (0 until 8).fold(0L) { a, k -> a or ((b[o + k].toLong() and 0xFF) shl (8 * k)) }

    private fun pages(d: ByteArray): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var p = 0
        while (p < d.size) {
            assertEquals("OggS", String(d, p, 4))
            val n = d[p + 26].toInt() and 0xFF
            var len = 27 + n
            for (i in 0 until n) len += d[p + 27 + i].toInt() and 0xFF
            out.add(p to len); p += len
        }
        return out
    }

    private fun oggPackets(d: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var cur = java.io.ByteArrayOutputStream()
        for ((p, _) in pages(d)) {
            val n = d[p + 26].toInt() and 0xFF
            var q = p + 27 + n
            for (i in 0 until n) {
                val l = d[p + 27 + i].toInt() and 0xFF
                cur.write(d, q, l); q += l
                if (l < 255) { out.add(cur.toByteArray()); cur = java.io.ByteArrayOutputStream() }
            }
        }
        return out
    }
}
