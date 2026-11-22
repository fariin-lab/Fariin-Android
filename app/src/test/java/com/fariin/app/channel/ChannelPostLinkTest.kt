package com.fariin.app.channel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelPostLinkTest {
    @Test fun linkPrefersTheNumber() {
        assertEquals("https://fariin.com/fariintv/42", ChannelPostLink.link("fariintv", "cid1", 42, "abcDEF123"))
        assertEquals("https://fariin.com/c/cid1/42", ChannelPostLink.link("", "cid1", 42, "abcDEF123"))
        // No number yet: the post id, never an id that looks like a number.
        assertEquals("https://fariin.com/fariintv/abcDEF123", ChannelPostLink.link("fariintv", "cid1", null, "abcDEF123"))
        assertNull(ChannelPostLink.link("fariintv", "cid1", null, "12345"))
    }

    @Test fun parseReadsBothShapes() {
        assertEquals(ChannelPostLink.Target("fariintv", null, "42"), ChannelPostLink.parse("https://fariin.com/fariintv/42"))
        assertEquals(ChannelPostLink.Target(null, "cid1", "42"), ChannelPostLink.parse("fariin.com/c/cid1/42/"))
        assertEquals(ChannelPostLink.Target("FariinTV", null, "abc"), ChannelPostLink.parse("https://www.fariin.com/FariinTV/abc?x=1"))
    }

    @Test fun parseLeavesTheSitesOwnPathsAlone() {
        assertNull(ChannelPostLink.parse("https://fariin.com/u/someone"))
        assertNull(ChannelPostLink.parse("https://fariin.com/g/code1"))
        assertNull(ChannelPostLink.parse("https://fariin.com/help/article"))
        assertNull(ChannelPostLink.parse("https://fariin.com/name/s/story1"))
        assertNull(ChannelPostLink.parse("https://fariin.com/fariintv"))
        assertNull(ChannelPostLink.parse("https://example.com/news/42"))
    }
}
