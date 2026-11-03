package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The empty group's card against Kulan-iOS GroupWelcomeCard (startedLine, sizeLine, canAdd, canLink). */
class GroupWelcomeTest {
    private val doc = mapOf<String, Any?>("createdBy" to "o", "admins" to listOf("o", "a"), "users" to listOf("o", "a", "m"),
        "names" to mapOf("o" to "Bobby"))

    @Test fun lines() {
        assertEquals("You started this group", GroupWelcome.startedLine("o", doc))
        assertEquals("Bobby started this group", GroupWelcome.startedLine("m", doc))
        assertEquals("You were added to this group", GroupWelcome.startedLine("m", doc - "names"))
        assertEquals("3 members", GroupWelcome.sizeLine(doc))
        assertEquals("Just you so far", GroupWelcome.sizeLine(doc + ("users" to listOf("o"))))
    }

    @Test fun rights() {
        assertTrue(GroupWelcome.canAdd("a", doc)); assertTrue(GroupWelcome.canLink("a", doc))
        assertFalse(GroupWelcome.canAdd("m", doc)); assertFalse(GroupWelcome.canLink("m", doc))
        val open = doc + ("membersCanAdd" to true)
        assertTrue(GroupWelcome.canAdd("m", open)); assertFalse(GroupWelcome.canLink("m", open))
        val held = open + ("restrictedUntil" to mapOf("m" to 2_000.0)) + ("restrictedFlags" to mapOf("m" to listOf("addMembers")))
        assertFalse(GroupWelcome.canAdd("m", held, nowMs = 1_000.0))
        assertTrue(GroupWelcome.canAdd("m", held, nowMs = 3_000.0))
        // An admin granted rights without Add members.
        assertFalse(GroupWelcome.canAdd("a", doc + ("adminRights" to mapOf("a" to listOf("changeInfo")))))
    }
}
