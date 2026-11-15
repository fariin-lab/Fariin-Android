package com.fariin.app.data

import com.fariin.app.data.ChatList.PreviewIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForwardMarkTest {
    private val m = ForwardMark.MARK

    @Test fun markIsArrowAndSpace() {
        assertEquals("↪ ", m)
        assertEquals("$m📷 Photo", ForwardMark.mark("📷 Photo"))
        assertEquals("$m📷 Photo", ForwardMark.mark("$m📷 Photo"))   // never twice
        assertEquals("hi", ForwardMark.markIf(false, "hi"))
        assertEquals("${m}hi", ForwardMark.markIf(true, "hi"))
    }

    @Test fun markAtStart() {
        assertEquals(ForwardMark.Split(true, "🎤 Voice message · 0:05"), ForwardMark.split("$m🎤 Voice message · 0:05"))
        assertEquals(ForwardMark.Split(true, "PLAY DIRTY"), ForwardMark.split("${m}PLAY DIRTY"))
    }

    @Test fun markAfterNamePrefix() {
        assertEquals(ForwardMark.Split(true, "Omar: Photo"), ForwardMark.split("Omar: ${m}Photo"))
        assertEquals(ForwardMark.Split(true, "Omar: "), ForwardMark.split("Omar: ↪"))
        // Only straight after the first ": ", and only the whole mark.
        assertFalse(ForwardMark.split("a: b: ${m}x").forwarded)
        assertFalse(ForwardMark.split("Omar: ↪x").forwarded)
    }

    @Test fun noMark() {
        assertEquals(ForwardMark.Split(false, "hello"), ForwardMark.split("hello"))
        assertEquals(ForwardMark.Split(false, ""), ForwardMark.split(""))
        assertEquals(ForwardMark.Split(false, "↪hello"), ForwardMark.split("↪hello"))
        assertEquals("enc1:abc", ForwardMark.strip("enc1:abc"))
    }

    @Test fun markOnly() {
        assertEquals(ForwardMark.Split(true, ""), ForwardMark.split(m))
        assertEquals(ForwardMark.Split(true, ""), ForwardMark.split("↪"))
    }

    // ---- the chat list row

    private val sealed = MessageDecryptor { c, _ -> c.lastMessageCipher.removePrefix("enc:") }
    private fun conv(last: String, group: Boolean = false, image: String? = null) =
        Conversation(id = "a_b", users = listOf("a", "b"), names = mapOf("b" to "Omar Ali"),
            convType = if (group) "group" else "", lastMessageCipher = last, lastSender = "b",
            lastImageUrl = image, updatedAtMillis = 1000.0)
    private fun row(c: Conversation) = ChatList.rowPreview(c, "a", sealed) { null }

    @Test fun clearMarkerForward() {
        val voice = row(conv("$m🎤 Voice message"))
        assertTrue(voice.forwarded); assertEquals(PreviewIcon.MIC, voice.icon); assertEquals("Voice message", voice.text)
        val photo = row(conv("$m📷 Photo", image = "https://x/p.jpg"))
        assertTrue(photo.forwarded); assertTrue(photo.photo); assertEquals("Photo", photo.text)
        val file = row(conv("$m📄 File", group = true))
        assertTrue(file.forwarded); assertEquals("Omar: ", file.prefix); assertEquals("File", file.text)
    }

    @Test fun sealedForward() {
        val r = row(conv("enc:${m}PLAY DIRTY"))
        assertTrue(r.forwarded); assertEquals("PLAY DIRTY", r.text); assertEquals("PLAY DIRTY", r.plain)
        val voice = row(conv("enc:$m🎤 Voice message"))
        assertTrue(voice.forwarded); assertEquals(PreviewIcon.MIC, voice.icon)
    }

    @Test fun notForward() {
        val r = row(conv("enc:hello"))
        assertFalse(r.forwarded); assertEquals("hello", r.text)
        assertFalse(row(conv("📞 Call")).forwarded)
    }
}
