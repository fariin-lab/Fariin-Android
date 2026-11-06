package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NewBadgesTest {
    @Test fun parseKeepsKnownNumericKeysOnly() {
        val raw = mapOf("chats" to 2000L, "help" to 3000.0, "privacy" to 5, "bogus" to 9000L,
            "account" to "soon", "devices" to true, 7 to 1L)
        assertEquals(mapOf("chats" to 2000L, "help" to 3000L, "privacy" to 5L), NewBadges.parse(raw))
    }

    @Test fun parseNonMapIsEmpty() {
        assertEquals(emptyMap<String, Long>(), NewBadges.parse(null))
        assertEquals(emptyMap<String, Long>(), NewBadges.parse("x"))
    }

    @Test fun activeDropsPastEnds() {
        val ends = mapOf("chats" to 1000L, "help" to 3000L)
        assertEquals(setOf("help"), NewBadges.active(ends, 1000L))
        assertEquals(setOf("chats", "help"), NewBadges.active(ends, 999L))
    }

    @Test fun nextEndIsSoonestAhead() {
        val ends = mapOf("chats" to 1000L, "help" to 3000L, "safety" to 2000L)
        assertEquals(2000L, NewBadges.nextEnd(ends, 1500L))
        assertNull(NewBadges.nextEnd(ends, 3000L))
    }
}
