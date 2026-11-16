package com.fariin.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietWritesTest {
    @Test fun typingOnAlwaysGoes() {
        assertTrue(QuietWrites.typingWrite(on = true, sentOnThisVisit = false, docValue = null))
        assertTrue(QuietWrites.typingWrite(on = true, sentOnThisVisit = true, docValue = "text-100"))
    }

    @Test fun anOffWithNothingOnIsLeftOut() {
        // The composer opening and closing with an empty field.
        assertFalse(QuietWrites.typingWrite(on = false, sentOnThisVisit = false, docValue = null))
        assertFalse(QuietWrites.typingWrite(on = false, sentOnThisVisit = false, docValue = false))
    }

    @Test fun anOffAfterMyOnGoes() =
        assertTrue(QuietWrites.typingWrite(on = false, sentOnThisVisit = true, docValue = false))

    @Test fun anOffClearsAStaleOnInTheDoc() {
        // Left on by an earlier visit that never said off (the app was killed).
        assertTrue(QuietWrites.typingWrite(on = false, sentOnThisVisit = false, docValue = "text-100"))
        assertTrue(QuietWrites.typingWrite(on = false, sentOnThisVisit = false, docValue = "audio-100"))
        assertTrue(QuietWrites.typingWrite(on = false, sentOnThisVisit = false, docValue = true))
    }

    @Test fun nothingUnreadNeedsNoReadMark() {
        assertFalse(QuietWrites.readMarkNeeded(0, false, myLastReadMs = 2_000, newestTheirsMs = 1_000))
        assertFalse(QuietWrites.readMarkNeeded(0, false, myLastReadMs = 2_000, newestTheirsMs = 2_000))
        assertFalse(QuietWrites.readMarkNeeded(0, false, myLastReadMs = 2_000, newestTheirsMs = null))  // only mine
    }

    @Test fun aBadgeOrAnUnreadMarkNeedsIt() {
        assertTrue(QuietWrites.readMarkNeeded(1, false, myLastReadMs = 2_000, newestTheirsMs = 1_000))
        assertTrue(QuietWrites.readMarkNeeded(0, true, myLastReadMs = 2_000, newestTheirsMs = 1_000))
    }

    @Test fun aNewerMessageOfTheirsNeedsIt() {
        // The message came before the badge did (two listeners): still marked.
        assertTrue(QuietWrites.readMarkNeeded(0, false, myLastReadMs = 1_000, newestTheirsMs = 1_001))
    }

    @Test fun anUnknownLastReadNeedsIt() =
        assertTrue(QuietWrites.readMarkNeeded(0, false, myLastReadMs = null, newestTheirsMs = 1_000))
}
