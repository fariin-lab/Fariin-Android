package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The chat list card's rules (iOS ChatListCards): parse, reach, pick. */
class ChatCardsTest {
    private val day = 86_400_000.0
    private val now = 1_800_000_000_000.0

    private fun pick(admin: List<ChatCard>, closed: Set<String> = emptySet(), joined: Double? = now - 2 * day,
                     photo: String? = "p", handle: String? = "h", notif: Boolean? = true) =
        ChatCard.pick(admin, closed, "u1", 100, "SO", now, joined, photo, handle, notif)

    @Test fun parseSkipsBadEntries() {
        val cards = ChatCard.parse(listOf(
            mapOf("id" to "a", "title" to " Hi ", "link" to "javascript:x", "image" to "http://x.png"),
            mapOf("id" to "builtin_photo", "title" to "shadow"),
            mapOf("id" to "Bad ID", "title" to "x"),
            mapOf("id" to "b", "title" to ""),
            mapOf("id" to "a", "title" to "dup"),
            mapOf("id" to "c", "title" to "C", "link" to "fariin://chat/x", "ppm" to 5_000_000),
        ))
        assertEquals(listOf("a", "c"), cards.map { it.id })
        assertEquals("Hi", cards[0].title)
        assertNull(cards[0].link); assertNull(cards[0].image)
        assertEquals("fariin://chat/x", cards[1].link)
        assertEquals(1_000_000, cards[1].ppm)
    }

    @Test fun reachRules() {
        val c = ChatCard("x", "X")
        assertTrue(c.reaches("u", 1, "SO", now))
        assertFalse(c.copy(platforms = listOf("ios")).reaches("u", 1, "SO", now))
        assertTrue(c.copy(platforms = listOf("ios", "android")).reaches("u", 1, "SO", now))
        assertFalse(c.copy(endsAt = now).reaches("u", 1, "SO", now))
        assertFalse(c.copy(startsAt = now + 1).reaches("u", 1, "SO", now))
        assertFalse(c.copy(countries = listOf("KE")).reaches("u", 1, "SO", now))
        // iPhone build limits do not apply to Android: such a card is not shown, unless Android-only.
        assertFalse(c.copy(minBuild = 5).reaches("u", 10, "SO", now))
        assertTrue(c.copy(minBuild = 5, platforms = listOf("android")).reaches("u", 10, "SO", now))
        assertFalse(c.copy(ppm = 0).reaches("u", 1, "SO", now))
    }

    @Test fun pickOrder() {
        val a = ChatCard("a", "A", priority = 1)
        val b = ChatCard("b", "B", priority = 5)
        assertEquals("b", pick(listOf(a, b))?.id)
        assertEquals("a", pick(listOf(a, b), closed = setOf("b"))?.id)
        // Built-ins after the admin cards, in order, not on the first day.
        assertEquals(ChatCard.PHOTO, pick(emptyList(), photo = null, handle = ""))
        assertEquals(ChatCard.USERNAME, pick(emptyList(), closed = setOf("builtin_photo"), photo = null, handle = ""))
        assertEquals(ChatCard.NOTIFICATIONS, pick(emptyList(), notif = false))
        assertNull(pick(emptyList(), photo = null, joined = now - 1000))
        assertNull(pick(emptyList(), handle = null))      // profile not read yet
        assertNull(pick(emptyList(), notif = null))       // permission not known yet
    }
}
