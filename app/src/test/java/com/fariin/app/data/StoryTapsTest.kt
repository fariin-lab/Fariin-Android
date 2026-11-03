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

    @Test fun textStoryLinkEntryIsTheIosShape() {
        val cardW = TextStoryRules.RENDER_W * TextStoryRules.LINK_CARD_WIDTH
        val cardH = TextStoryRules.linkCardHeight(cardW)
        assertEquals(259.2f, cardH, 0.01f)
        // Short words: the card on top of a centred group, the words under it.
        val (words, centre) = TextStoryRules.placeLink(200f, cardH, 1080f, 2700f)
        assertEquals(200f, words, 0.01f)
        val top = (2700f - (cardH + 1080f * 0.04f + 200f)) / 2
        assertEquals((top + cardH / 2) / 2700f, centre, 1e-5f)
        val t = TextStoryRules.linkTap("https://example.com/a", centre, cardH)
        val m = StoryTaps.asMap(t)
        assertEquals(listOf("x", "y", "w", "h", "rotation", "url", "aspect"), m.keys.toList())
        assertEquals(0.5, m["x"] as Double, 1e-9)
        assertEquals(0.72, m["w"] as Double, 1e-6)
        assertEquals(cardH / 2700.0, m["h"] as Double, 1e-6)
        assertEquals(0.0, m["rotation"] as Double, 1e-9)
        assertEquals(0.4, m["aspect"] as Double, 1e-6)
        // What the viewers read back is what was written.
        assertEquals(t, StoryTaps.from(m))
        assertFalse("aspect" in StoryTaps.asMap(t.copy(aspect = null)))
    }

    @Test fun longWordsNeverPushTheCardOff() {
        val (words, _) = TextStoryRules.placeLink(5000f, 259.2f, 1080f, 2700f)
        assertEquals(2700f - 259.2f - 43.2f, words, 0.01f)
    }

    @Test fun firstWebLinkInTheWords() {
        assertEquals("https://www.example.com/x", TextStoryRules.firstLink("read www.example.com/x and https://b.org"))
        assertEquals("http://a.org", TextStoryRules.firstLink("mail me@a.com or see http://a.org"))
        assertNull(TextStoryRules.firstLink("no link here, call 555 1234"))
    }

    @Test fun storyPayloadCarriesTheStickers() {
        val t = TextStoryRules.linkTap("https://example.com", 0.4f, 259.2f)
        val p = StoryRules.payload("me", false, "S1", "", StoryAudienceChoice.MY_CHATS, "all", setOf("a"), "E", "N", "b",
            stickers = listOf(t))
        assertEquals(listOf(StoryTaps.asMap(t)), p["stickers"])
        assertTrue((StoryRules.payload("me", false, "S2", "", StoryAudienceChoice.MY_CHATS, "all", setOf("a"), "E", "N", "b")["stickers"] as List<*>).isEmpty())
    }
}
