package com.fariin.app.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactNamesTest {
    @After fun tearDown() = ContactNames.clear()

    @Test fun cardRules() {
        assertEquals("Omar Ali", ContactCard(" Omar ", "Ali").displayName)
        assertEquals("Ali", ContactCard("", "Ali").displayName)
        assertNull(ContactCard(" ", "").displayName)
        assertTrue(ContactCard(note = "  ").isEmpty)
        assertFalse(ContactCard(note = "met at work").isEmpty)
    }

    @Test fun savedNameWinsInAOneToOneOnly() {
        val direct = Conversation(id = "a_b", users = listOf("a", "b"), names = mapOf("b" to "Bobby"))
        assertEquals("Bobby", direct.name("a"))
        ContactNames.setCard("b", ContactCard("Bob", "Uncle"))
        assertEquals("Bob Uncle", direct.name("a"))
        assertEquals("Bob Uncle", direct.displayName("a"))
        // A note alone keeps the profile name; an empty card is removed.
        ContactNames.setCard("b", ContactCard(note = "only a note"))
        assertEquals("Bobby", direct.name("a"))
        assertEquals("only a note", ContactNames.note("b"))
        ContactNames.setCard("b", ContactCard())
        assertNull(ContactNames.note("b"))
        // Limits as iOS: 40 per name, 100 for the note.
        ContactNames.setCard("b", ContactCard("x".repeat(50), note = "n".repeat(150)))
        assertEquals(40, ContactNames.card("b").first.length)
        assertEquals(100, ContactNames.card("b").note.length)
    }
}
