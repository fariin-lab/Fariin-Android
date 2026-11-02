package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTest {

    @Test fun plainTagIsFnv1a64() {
        assertEquals("cbf29ce484222325", Conversation.plainTag(""))
        assertEquals("af63dc4c8601ec8c", Conversation.plainTag("a"))
    }

    @Test fun clearedOneToOneHidesUntilNewerMessage() {
        val c = Conversation(id = "a_b", users = listOf("a", "b"), updatedAtMillis = 100.0,
            clearedAt = mapOf("a" to 100.0), lastMessageCipher = "x")
        assertTrue(c.isCleared("a"))
        assertFalse(c.copy(updatedAtMillis = 101.0).isCleared("a"))
    }

    @Test fun groupIAmInIsNeverCleared() {
        val g = Conversation(id = "g", users = listOf("a", "b"), convType = "group",
            updatedAtMillis = 100.0, clearedAt = mapOf("a" to 200.0))
        assertFalse(g.isCleared("a"))
        // Once I have left, the clear applies again.
        assertTrue(g.copy(users = listOf("b")).isCleared("a"))
    }

    @Test fun pinnedFirstThenNewest() {
        val old = Conversation(id = "old", users = listOf("me", "x"), lastMessageCipher = "m", updatedAtMillis = 1.0,
            pinnedBy = mapOf("me" to true))
        val mid = Conversation(id = "mid", users = listOf("me", "y"), lastMessageCipher = "m", updatedAtMillis = 5.0)
        val new = Conversation(id = "new", users = listOf("me", "z"), lastMessageCipher = "m", updatedAtMillis = 9.0)
        val empty = Conversation(id = "empty", users = listOf("me", "w"), updatedAtMillis = 10.0)
        assertEquals(listOf("old", "new", "mid"), ChatList.visible(listOf(mid, empty, new, old), "me").map { it.id })
    }

    @Test fun absentStartedByMeansAccepted() {
        val c = Conversation.fromFirestore("a_b", mapOf("users" to listOf("a", "b")))
        assertTrue(c.accepted)
        val r = Conversation.fromFirestore("a_b", mapOf("users" to listOf("a", "b"), "startedBy" to "b",
            "accepted" to false, "lastSender" to "b", "lastMessage" to "x"))
        assertTrue(ChatList.isIncomingRequest(r, "a"))
        assertFalse(ChatList.isIncomingRequest(r, "b"))
    }
}
