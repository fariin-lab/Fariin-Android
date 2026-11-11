package com.fariin.app.media

import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FaststartTest {
    private fun fixture(): File {
        val f = File.createTempFile("moov_end", ".mp4")
        f.writeBytes(javaClass.classLoader!!.getResourceAsStream("media/moov_end.mp4")!!.readBytes())
        f.deleteOnExit()
        return f
    }

    /** Every chunk offset (stco) of the file, found by a plain byte search for the box. */
    private fun chunkOffsets(b: ByteArray): List<Long> {
        val out = ArrayList<Long>()
        val bb = ByteBuffer.wrap(b)
        var i = 4
        while (i + 4 <= b.size) {
            if (b[i] == 's'.code.toByte() && b[i + 1] == 't'.code.toByte() && b[i + 2] == 'c'.code.toByte() && b[i + 3] == 'o'.code.toByte()) {
                val n = bb.getInt(i + 8)
                for (k in 0 until n) out += bb.getInt(i + 12 + k * 4).toLong() and 0xFFFFFFFFL
            }
            i++
        }
        return out
    }

    /** moov_end.mp4: 1 s of H.264 + AAC from ffmpeg, which writes the index after the samples. */
    @Test fun movesTheIndexInFrontAndKeepsEverySample() {
        val f = fixture()
        val before = f.readBytes()
        assertEquals(false, Faststart.moovFirst(f))
        assertTrue(Faststart.apply(f))
        val after = f.readBytes()
        assertEquals(true, Faststart.moovFirst(f))
        assertEquals(before.size, after.size)
        val old = chunkOffsets(before)
        val new = chunkOffsets(after)
        assertTrue(old.isNotEmpty())
        assertEquals(old.size, new.size)
        val shift = new[0] - old[0]
        assertTrue(shift > 0)
        for ((o, n) in old.zip(new)) {
            assertEquals(o + shift, n)
            assertArrayEquals(before.copyOfRange(o.toInt(), o.toInt() + 16), after.copyOfRange(n.toInt(), n.toInt() + 16))
        }
        // Kept for a decode check by hand: ffmpeg -v error -i build/faststart-out.mp4 -f null -
        runCatching { f.copyTo(File("build/faststart-out.mp4"), overwrite = true) }
    }

    @Test fun aFileAlreadyInOrderIsLeftAlone() {
        val f = fixture()
        assertTrue(Faststart.apply(f))
        val once = f.readBytes()
        assertFalse(Faststart.apply(f))
        assertArrayEquals(once, f.readBytes())
    }

    @Test fun notAnMp4IsLeftAlone() {
        val f = File.createTempFile("junk", ".mp4").apply { writeBytes(ByteArray(100) { it.toByte() }); deleteOnExit() }
        val before = f.readBytes()
        assertFalse(Faststart.apply(f))
        assertArrayEquals(before, f.readBytes())
    }
}
