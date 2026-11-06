package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReactionPicksTest {
    @Test fun aTapTurnsOnAndOff() {
        val on = ReactionPicks.toggle(listOf("👍"), "🔥")
        assertEquals(listOf("👍", "🔥"), on.list); assertNull(on.note)
        val off = ReactionPicks.toggle(listOf("👍", "🔥"), "👍")
        assertEquals(listOf("🔥"), off.list); assertNull(off.note)
        // "❤" and "❤️" are the same one.
        assertEquals(listOf("👍"), ReactionPicks.toggle(listOf("👍", "❤️"), "❤").list)
        assertEquals(listOf("cu_abcdefghij"), ReactionPicks.toggle(emptyList(), "cu_abcdefghij").list)
    }

    @Test fun theLastOneStays() {
        val t = ReactionPicks.toggle(listOf("👍"), "👍")
        assertEquals(listOf("👍"), t.list)
        assertEquals("Keep at least one. To turn reactions off, choose No Reactions.", t.note)
    }

    @Test fun twelveAtMost() {
        val full = ChannelReactions.palette.take(12)
        val t = ReactionPicks.toggle(full, "🌭")
        assertEquals(full, t.list)
        assertEquals("Up to 12 reactions.", t.note)
        assertEquals(full.drop(1), ReactionPicks.toggle(full, full[0]).list)   // taking one off still works
    }

    @Test fun savesOnlyWhenChanged() {
        assertFalse(ReactionPicks.changed("some", listOf("👍"), "some", listOf("👍")))
        assertTrue(ReactionPicks.changed("some", listOf("👍", "🔥"), "some", listOf("👍")))
        assertTrue(ReactionPicks.changed("some", listOf("🔥", "👍"), "some", listOf("👍", "🔥")))
        assertTrue(ReactionPicks.changed("some", listOf("👍"), "all", listOf("👍")))
    }

    @Test fun words() {
        assertEquals("6 of 12", ReactionPicks.title(6))
        assertEquals("Tap to choose reactions, up to 12", ReactionPicks.footer(listOf("👍")))
        assertEquals("Pick at least one, or reactions will be off.", ReactionPicks.footer(emptyList()))
    }
}
