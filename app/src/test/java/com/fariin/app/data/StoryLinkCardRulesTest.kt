package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryLinkCardRulesTest {
    @Test fun channelAndGroupLinksSayJoin() {
        assertEquals(StoryLinkCardRules.Pill.JOIN, StoryLinkCardRules.pill("https://fariin.com/vee", "1,204 followers"))
        assertEquals(StoryLinkCardRules.Pill.JOIN, StoryLinkCardRules.pill("https://fariin.com/vee", "1 follower"))
        assertEquals(StoryLinkCardRules.Pill.JOIN, StoryLinkCardRules.pill("https://fariin.com/g/abcd1234", "12 members"))
        assertEquals(StoryLinkCardRules.Pill.JOIN, StoryLinkCardRules.pill("https://www.fariin.com/news", "2.5K members"))
        // A public channel opened by its @name: the chat card line our own cards carry.
        assertEquals(StoryLinkCardRules.Pill.JOIN, StoryLinkCardRules.pill("https://fariin.com/vee", "Channel invite · 9 followers"))
    }

    @Test fun personLinksSayChat() {
        assertEquals(StoryLinkCardRules.Pill.CHAT, StoryLinkCardRules.pill("https://fariin.com/u/ali", "@ali"))
        assertEquals(StoryLinkCardRules.Pill.CHAT, StoryLinkCardRules.pill("https://fariin.com/ali", "@ali"))
    }

    @Test fun otherLinksHaveNoPill() {
        assertNull(StoryLinkCardRules.pill("https://example.com/club", "12 members"))
        assertNull(StoryLinkCardRules.pill("https://example.com/u/ali", "@ali"))
        assertNull(StoryLinkCardRules.pill("https://fariin.com/help", "@help"))
        assertNull(StoryLinkCardRules.pill("https://fariin.com/vee", "A channel about cars"))
        assertNull(StoryLinkCardRules.pill("https://fariin.com/vee", ""))
    }

    @Test fun ourLinksOpenInTheApp() {
        assertTrue(StoryLinkCardRules.opensInApp("https://fariin.com/vee"))
        assertTrue(StoryLinkCardRules.opensInApp("https://fariin.com/u/ali"))
        assertTrue(StoryLinkCardRules.opensInApp("https://fariin.com/g/abcd1234"))
        assertFalse(StoryLinkCardRules.opensInApp("https://fariin.com/help"))
        assertFalse(StoryLinkCardRules.opensInApp("https://fariin.com/vee?x=1"))
        assertFalse(StoryLinkCardRules.opensInApp("https://example.com/vee"))
    }
}
