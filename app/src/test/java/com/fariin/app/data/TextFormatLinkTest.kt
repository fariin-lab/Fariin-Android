package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Format > Link (iOS f09ef437c): the wire entries, the address rules and the composer's bookkeeping. */
class TextFormatLinkTest {
    @Test fun addressRules() {
        assertEquals("https://fariin.com", TextFormat.cleanUrl("fariin.com"))
        assertEquals("https://www.x.com/a", TextFormat.cleanUrl(" www.x.com/a "))
        assertEquals("http://a.b", TextFormat.cleanUrl("http://a.b"))
        assertEquals("mailto:a@b.co", TextFormat.cleanUrl("mailto:a@b.co"))
        assertEquals("tel:+25261", TextFormat.cleanUrl("tel:+25261"))
        assertEquals("fariin:chat/x", TextFormat.cleanUrl("fariin:chat/x"))
        assertNull(TextFormat.cleanUrl("javascript:alert(1)"))
        assertNull(TextFormat.cleanUrl("file:///etc/passwd"))
        assertNull(TextFormat.cleanUrl("https://localhost"))
        assertNull(TextFormat.cleanUrl("a b.com"))
        assertNull(TextFormat.cleanUrl("https://x.com/" + "a".repeat(2048)))
    }

    @Test fun readsTheIosWireWithBold() {
        val fmt = """[[0,5,"b"],[6,5,"l","https://fariin.com"],[13,3,"l","javascript:x"],[7,2,"l","https://later.com"]]"""
        assertEquals(listOf(TextFormat.Span(0, 5)), TextFormat.parse(fmt, 16))
        // The bad address goes; of two overlapping links the earlier stays.
        assertEquals(listOf(TextFormat.Link(6, 11, "https://fariin.com")), TextFormat.parseLinks(fmt, 16))
        // A link reaching past the words is dropped whole.
        assertTrue(TextFormat.parseLinks("""[[6,20,"l","https://a.com"]]""", 16).isEmpty())
    }

    @Test fun roundTrip() {
        val bold = listOf(TextFormat.Span(0, 2))
        val links = listOf(TextFormat.Link(3, 7, "https://a.com"))
        val json = TextFormat.serialize(bold, 10, links)!!
        assertEquals(bold, TextFormat.parse(json, 10))
        assertEquals(links, TextFormat.parseLinks(json, 10))
        // Links alone are still written.
        assertEquals(links, TextFormat.parseLinks(TextFormat.serialize(emptyList(), 10, links), 10))
        assertNull(TextFormat.serialize(emptyList(), 10, emptyList()))
    }

    @Test fun typingKeepsLinksInStep() {
        val l = listOf(TextFormat.Link(4, 8, "https://a.com"))   // "see here now" -> "here"
        // Inside grows it.
        assertEquals(listOf(TextFormat.Link(4, 9, "https://a.com")), TextFormat.adjustLinks(l, "see here now", "see heXre now"))
        // Right after does not.
        assertEquals(l, TextFormat.adjustLinks(l, "see here now", "see hereX now"))
        // Right before moves it.
        assertEquals(listOf(TextFormat.Link(5, 9, "https://a.com")), TextFormat.adjustLinks(l, "see here now", "see Xhere now"))
        // Its words deleted: gone.
        assertTrue(TextFormat.adjustLinks(l, "see here now", "see  now").isEmpty())
    }

    @Test fun setAndRemove() {
        val l = TextFormat.setLink(emptyList(), 2, 6, "https://a.com", 10)
        assertEquals(TextFormat.Link(2, 6, "https://a.com"), TextFormat.linkAt(l, 3, 5))
        assertTrue(TextFormat.setLink(l, 2, 6, null, 10).isEmpty())
        // A new link over part of an old one replaces it.
        assertEquals(listOf(TextFormat.Link(4, 8, "https://b.com")), TextFormat.setLink(l, 4, 8, "https://b.com", 10))
        assertEquals(listOf(TextFormat.Link(1, 5, "https://a.com")),
            TextFormat.linksForTrimmed(listOf(TextFormat.Link(3, 7, "https://a.com")), "  hello there "))
    }

    @Test fun wordsThatAreTheAddress() {
        assertTrue(TextFormat.wordsAreAddress("fariin.com", "https://fariin.com/"))
        assertTrue(TextFormat.wordsAreAddress("www.fariin.com", "https://fariin.com"))
        assertFalse(TextFormat.wordsAreAddress("our site", "https://fariin.com"))
    }
}
