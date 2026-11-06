package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupLinkCardsTest {
    @Test fun invitesAndNamesAreGroupLinks() {
        assertEquals(GroupLinkCards.Target.Invite("AbC123"), GroupLinkCards.target("https://fariin.com/g/AbC123"))
        assertEquals(GroupLinkCards.Target.Name("vee"), GroupLinkCards.target("https://fariin.com/vee"))
        assertNull(GroupLinkCards.target("https://fariin.com/u/dave"))
        assertNull(GroupLinkCards.target("https://example.com/g/AbC123"))
        assertNull(GroupLinkCards.target("https://fariin.com/vee/s/abcd1234"))
    }

    @Test fun aNameIsAGroupCardOnlyWhenTheSenderFoundAGroup() {
        assertTrue(GroupLinkCards.cardTarget("https://fariin.com/g/AbC123", "") != null)
        assertTrue(GroupLinkCards.cardTarget("https://fariin.com/vee", "12 members") != null)
        assertTrue(GroupLinkCards.cardTarget("https://fariin.com/vee", "1 follower") != null)
        assertTrue(GroupLinkCards.cardTarget("https://fariin.com/vee", "1.2K followers") != null)
        assertNull(GroupLinkCards.cardTarget("https://fariin.com/vee", "Somali films every night"))
        assertNull(GroupLinkCards.cardTarget("https://fariin.com/vee", ""))
    }

    @Test fun countLineAndDeadMark() {
        assertEquals("1 member", GroupLinkCards.countLine(1, false))
        assertEquals("3 followers", GroupLinkCards.countLine(3, true))
        assertEquals("Channel invite · 3 followers", GroupLinkCards.countLine(3, true, byName = true))
        assertEquals("3 members", GroupLinkCards.countLine(3, false, byName = true))
        assertTrue(GroupLinkCards.cardTarget("https://fariin.com/vee", "Channel invite · 3 followers") != null)
    }

    @Test fun actionWordsOnlyOnANameCard() {
        assertNull(GroupLinkCards.actionLine(byName = false, channel = true, member = true))
        assertEquals("Open Channel", GroupLinkCards.actionLine(byName = true, channel = true, member = true))
        assertEquals("View Channel", GroupLinkCards.actionLine(byName = true, channel = true, member = false))
        assertEquals("Open Group", GroupLinkCards.actionLine(byName = true, channel = false, member = true))
        assertTrue(GroupLinkCards.snapshotSaysGone("Unavailable", ""))
        assertFalse(GroupLinkCards.snapshotSaysGone("Unavailable", "4 members"))
    }
}
