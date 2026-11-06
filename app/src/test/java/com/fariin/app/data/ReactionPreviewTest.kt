package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReactionPreviewTest {
    /** Opens "x:<plain>" as <plain>, anything else as unreadable. */
    private val open = object : MessageDecryptor {
        override fun previewText(conv: Conversation, me: String) = "last words"
        override fun open(conv: Conversation, sealed: String, author: String) = sealed.removePrefix("x:").takeIf { sealed.startsWith("x:") }
    }
    private fun conv(by: String, emoji: String = "x:😂", on: String? = null, toAuthor: String = "", at: Double = 2000.0, group: Boolean = false) =
        Conversation(id = "a_b", users = listOf("a", "b"), names = mapOf("b" to "Bobby Test", "c" to "Carol Test"),
            convType = if (group) "group" else "", updatedAtMillis = 1000.0, lastMessageCipher = "enc1:x",
            lastReactionBy = by, lastReactionEnc = emoji, lastReactionAtMillis = at, lastReactionToEnc = on, lastReactionToAuthor = toAuthor)

    private fun line(c: Conversation) = ChatList.previewLine(c, "a", open) { null }

    @Test fun wordsAsIos() {
        assertEquals("Reacted 😂 to \"Photo\"", line(conv("b", on = "x:Photo")))
        assertEquals("You reacted 😂 to \"Photo\"", line(conv("a", on = "x:Photo")))
        assertEquals("Reacted 😂 to your message", line(conv("b", toAuthor = "a")))
        assertEquals("Reacted 😂", line(conv("b")))
        assertEquals("Carol reacted 😂 to \"hi\"", line(conv("c", on = "x:hi", group = true)))
    }

    @Test fun onlyWhenNewerAndReadable() {
        // Older than the last message: the message's own line.
        assertEquals("last words", line(conv("b", at = 500.0)))
        // An emoji that cannot be opened yet: the message's own line.
        assertEquals("last words", line(conv("b", emoji = "sealed")))
        // Words that cannot be opened are left off.
        assertEquals("Reacted 😂", line(conv("b", on = "sealed")))
        assertNull(ChatList.reactionPreview(conv("b", at = 500.0), "a", open) { null })
    }

    @Test fun cachedLineOnlyWhenNothingToOpen() {
        // An opener with no crypto work: the cached line is the full line.
        assertEquals("Reacted 😂", ChatList.cachedPreviewLine(conv("b"), "a", open) { null })
        // A line not opened yet: null, so the row opens it off the main thread.
        val cold = object : MessageDecryptor {
            override fun previewText(conv: Conversation, me: String) = "last words"
            override fun peekPreview(conv: Conversation, me: String) = ChatList.PEEK_MISS
        }
        assertNull(ChatList.cachedPreviewLine(conv("b", at = 500.0), "a", cold) { null })
        assertEquals("last words", ChatList.previewLine(conv("b", at = 500.0), "a", cold) { null })
    }
}
