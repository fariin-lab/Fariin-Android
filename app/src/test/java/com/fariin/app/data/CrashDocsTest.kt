package com.fariin.app.data

import com.fariin.app.crypto.MiniJson
import com.fariin.app.media.MediaRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Crafted message docs must never crash a reader (audit 2026-11-23). */
class CrashDocsTest {
    @Test fun thousandDeepJsonIsRefusedNotCrashed() {
        val deep = "[".repeat(1000) + "]".repeat(1000)
        assertNull(MiniJson.parse(deep))
        val deepObj = "{\"a\":".repeat(1000) + "1" + "}".repeat(1000)
        assertNull(MiniJson.parse(deepObj))
    }

    @Test fun shallowJsonStillReads() {
        val v = MiniJson.parse("{\"n\":\"x\",\"w\":{\"u\":\"a.b\"}}") as Map<*, *>
        assertEquals("x", v["n"])
        assertEquals(mapOf("u" to "a.b"), v["w"])
    }

    @Test fun deepPollMarkerIsRefused() {
        val json = "{\"id\":\"p\",\"q\":\"Q\",\"opts\":[\"a\",\"b\"],\"x\":" + "[".repeat(1000) + "]".repeat(1000) + "}"
        val marker = PollRules.MARKER + java.util.Base64.getEncoder().encodeToString(json.toByteArray())
        assertNull(PollRules.parse(marker))
        assertEquals("Poll", ActivityItem.shown(marker))
    }

    private fun msg(d: Map<String, Any?>) = Message.fromFirestore("m", d, "g_x") { r, _ -> r }

    @Test fun infiniteSizeIsUnknown() {
        val inf = Double.POSITIVE_INFINITY
        val m = msg(mapOf("type" to "image", "imageUrl" to "u", "width" to inf, "height" to inf,
            "album" to listOf(mapOf("imageUrl" to "a", "width" to Double.NaN, "height" to inf))))
        assertNull(m.width); assertNull(m.height)
        assertNull(m.album.first().width); assertNull(m.album.first().height)
        val (w, h) = MediaRules.photoBox(inf, inf, 300f)
        assertTrue(w.isFinite() && h.isFinite())
        val (ww, wh) = MediaRules.wideMediaBox(Double.NaN, 1.0, 300f)
        assertTrue(ww.isFinite() && wh.isFinite())
        assertEquals(220f to 220f, StickerGifRules.gifBox(inf, 10.0))
    }

    @Test fun nanDurationIsUnknown() {
        val m = msg(mapOf("type" to "video", "videoUrl" to "v", "duration" to Double.NaN))
        assertNull(m.duration)
        assertEquals("0:00", MediaRules.durationLabel(Double.NaN))
        assertEquals("0:00", MediaRules.durationLabel(Double.POSITIVE_INFINITY))
        assertEquals("1:05", MediaRules.durationLabel(65.0))
    }

    @Test fun stringCreatedAtReadsAsNotSet() {
        val m = Message.fromFirestore("m", mapOf("text" to "hi", "createdAt" to "2026-11-23"), "g_x", nowMillis = 5.0) { r, _ -> r }
        assertEquals(5.0, m.createdAtMillis, 0.0)
        assertTrue(!m.hasServerTime)
        val inf = Message.fromFirestore("m", mapOf("text" to "hi", "createdAt" to Double.POSITIVE_INFINITY), "g_x", nowMillis = 5.0) { r, _ -> r }
        assertEquals(5.0, inf.createdAtMillis, 0.0)
    }
}
