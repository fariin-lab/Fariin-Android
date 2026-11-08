package com.fariin.app.data

import com.fariin.app.data.ChatList.PreviewIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RowPreviewTest {
    private val plain = MessageDecryptor { c, _ -> c.lastMessageCipher }
    private fun conv(last: String, sender: String = "b", group: Boolean = false, image: String? = null) =
        Conversation(id = "a_b", users = listOf("a", "b"), names = mapOf("b" to "Omar Ali"),
            convType = if (group) "group" else "", lastMessageCipher = last, lastSender = sender,
            lastImageUrl = image, updatedAtMillis = 1000.0)
    private fun row(c: Conversation) = ChatList.rowPreview(c, "a", plain) { null }

    @Test fun markersBecomeIcons() {
        assertEquals(PreviewIcon.MIC to "Voice message · 0:05", ChatList.badge("🎤 Voice message · 0:05"))
        assertEquals(PreviewIcon.VIEW_ONCE to "View-once photo", ChatList.badge("View-once photo"))
        assertEquals(PreviewIcon.GIF to "GIF", ChatList.badge("GIF"))
        assertEquals(PreviewIcon.FILE to "File", ChatList.badge("📄 File"))
        assertNull(ChatList.badge("hello"))
    }

    @Test fun callsByDirection() {
        val missed = row(conv("📞 Missed call", sender = "b"))
        assertEquals(PreviewIcon.CALL_IN, missed.icon); assertTrue(missed.missed); assertEquals("Missed voice call", missed.text)
        val mine = row(conv("📹 Missed video call", sender = "a"))
        assertEquals(PreviewIcon.VIDEO_CALL_OUT, mine.icon); assertFalse(mine.missed); assertEquals("Video call", mine.text)
    }

    @Test fun deletedByMe() {
        assertEquals("You deleted this message", row(conv("This message was deleted", sender = "a")).text)
        assertEquals("This message was deleted", row(conv("This message was deleted", sender = "b")).text)
    }

    @Test fun groupPrefixApart() {
        val r = row(conv("📄 File", group = true))
        assertEquals("Omar: ", r.prefix); assertEquals("File", r.text)
        // A notice nobody "sent" stays bare.
        assertEquals("", row(conv("Omar created the group", group = true)).prefix)
    }

    @Test fun helloNewerAndPhoto() {
        assertEquals(PreviewIcon.HELLO, row(conv("", sender = "")).icon)
        assertEquals("Message from a newer version", row(conv("fariin-hologram:xyz")).text)
        val p = row(conv("📷 3 Photos", image = "https://x/y"))
        assertTrue(p.photo); assertEquals("3 Photos", p.text)
    }

    @Test fun typingLines() {
        val c = conv("hi", group = true).copy(users = listOf("a", "b", "c"), names = mapOf("b" to "Omar Ali", "c" to "Carol"),
            typing = mapOf("b" to true, "c" to true))
        assertEquals("Omar, Carol are typing…", ChatList.typingLabel(c, "a"))
        assertEquals("typing…", ChatList.typingLabel(conv("hi").copy(typing = mapOf("b" to true)), "a"))
        assertEquals("recording…", ChatList.recordingLabel(conv("hi").copy(recording = mapOf("b" to true)), "a"))
    }
}
