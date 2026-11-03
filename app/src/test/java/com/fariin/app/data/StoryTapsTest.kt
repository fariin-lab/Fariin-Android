package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryTapsTest {
    private val raw = mapOf("x" to 0.5, "y" to 0.6, "w" to 0.72, "h" to 0.1, "rotation" to 0.0,
        "url" to "https://fariin.com/u/bobby", "aspect" to 0.4)

    @Test fun readsAWebLink() {
        val t = StoryTaps.from(raw)!!
        assertEquals("https://fariin.com/u/bobby", t.url)
        assertEquals(0.4, t.aspect!!, 1e-9)
    }

    @Test fun dropsOtherSchemesAndEmptySizes() {
        assertNull(StoryTaps.from(raw + ("url" to "tel:123")))
        assertNull(StoryTaps.from(raw + ("url" to "intent://x")))
        assertNull(StoryTaps.from(raw + ("w" to 0.0)))
        assertNull(StoryTaps.from("nope"))
        assertEquals(1, StoryTaps.list(listOf(raw, mapOf("url" to "x"))).size)
    }

    @Test fun noAspectIsTheCard() {
        val t = StoryTaps.from(raw - "aspect")!!
        val r = StoryTaps.rect(t, 1000f, 2000f, 24f)
        assertEquals(500f, r.center.x, 0.01f)
        assertEquals(1200f, r.center.y, 0.01f)
        assertEquals(720f, r.width, 0.01f)
    }

    @Test fun aspectLaysOverTheFittedPicture() {
        // A 1:2.5 file fitted in a 1000 x 2000 card: 800 x 2000, 100 in from each side.
        val r = StoryTaps.rect(StoryTaps.from(raw)!!, 1000f, 2000f, 24f)
        assertEquals(500f, r.center.x, 0.01f)
        assertEquals(576f, r.width, 0.01f)
        assertEquals(200f, r.height, 0.01f)
    }

    @Test fun tinyAreasKeepAFloorAndTheBubbleFlipsNearTheBottom() {
        val t = StoryTaps.from(raw + ("w" to 0.001) + ("h" to 0.001) + ("y" to 0.97) - "aspect")!!
        val r = StoryTaps.rect(t, 1000f, 2000f, 24f)
        assertEquals(24f, r.width, 0.01f)
        assertTrue(StoryTaps.bubbleAbove(r, 2000f, 59f, 8f, 60f))
        assertFalse(StoryTaps.bubbleAbove(StoryTaps.rect(StoryTaps.from(raw)!!, 1000f, 2000f, 24f), 2000f, 59f, 8f, 60f))
    }
}
