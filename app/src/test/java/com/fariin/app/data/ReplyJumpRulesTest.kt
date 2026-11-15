package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyJumpRulesTest {
    @Test fun foundGoesEvenWhenBlockedFlagSet() {
        assertEquals(ReplyJumpRules.Outcome.GO, ReplyJumpRules.outcome(found = true, hiddenByBlock = true))
        assertNull(ReplyJumpRules.notice(ReplyJumpRules.Outcome.GO))
    }

    @Test fun blockedSaysNotAvailable() {
        val o = ReplyJumpRules.outcome(found = false, hiddenByBlock = true)
        assertEquals("Not available", ReplyJumpRules.notice(o))
    }

    @Test fun notReachedSaysNotLoadedNeverDeleted() {
        val o = ReplyJumpRules.outcome(found = false, hiddenByBlock = false)
        assertEquals("Original message isn't loaded", ReplyJumpRules.notice(o))
    }

    @Test fun wholeRowInBandDoesNotMove() {
        assertTrue(ReplyJumpRules.fullyVisible(0, 100, 1000))
        assertTrue(ReplyJumpRules.fullyVisible(900, 100, 1000))
        assertFalse(ReplyJumpRules.fullyVisible(-1, 100, 1000))
        assertFalse(ReplyJumpRules.fullyVisible(901, 100, 1000))
    }

    @Test fun centresInBand() {
        assertEquals(450, ReplyJumpRules.centredOffset(100, 1000))
        assertEquals(-100, ReplyJumpRules.centredOffset(1200, 1000))   // tall row: its middle centred
        // A row at the bottom edge lifts by 450 (negative scroll); one above the centre comes down.
        assertEquals(-450, ReplyJumpRules.scrollToCentre(0, 100, 1000))
        assertEquals(300, ReplyJumpRules.scrollToCentre(750, 100, 1000))
    }
}
