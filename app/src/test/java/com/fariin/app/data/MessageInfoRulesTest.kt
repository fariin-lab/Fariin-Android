package com.fariin.app.data

import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageInfoRulesTest {
    private val names = mapOf("a" to "Zed", "b" to "Amy", "c" to "Bob", "me" to "Me")

    @Test fun splitsOnLastReadAtOrAfterTheMessageAndSortsByName() {
        val s = MessageInfoRules.split(listOf("me", "a", "b", "c"), "me",
            mapOf("a" to 500.0, "b" to 499.0, "me" to 900.0), 500.0) { names[it] ?: "" }
        assertEquals(listOf("a"), s.readBy)
        assertEquals(listOf("b", "c"), s.deliveredTo)   // c has no lastRead at all: delivered
    }

    @Test fun snippetUsesMediaNounsAndNeverTheCardMarker() {
        fun m(type: String?, text: String = "", image: String? = null, video: String? = null, audio: String? = null) =
            Message(id = "m", authorId = "me", text = text, type = type, createdAtMillis = 1.0,
                imageUrl = image, videoUrl = video, audioUrl = audio)
        assertEquals("📷 Photo", MessageInfoRules.snippet(m("image", image = "u")))
        assertEquals("🎥 Video", MessageInfoRules.snippet(m("video", video = "u")))
        assertEquals("🎤 Voice message", MessageInfoRules.snippet(m("audio", audio = "u")))
        assertEquals("Contact", MessageInfoRules.snippet(m("text", text = "fariin-contact:u1||Ann")))
        assertEquals("hello", MessageInfoRules.snippet(m("text", text = "hello")))
    }

    @Test fun dateLineFollowsTheClockSetting() {
        val utc = ZoneId.of("UTC")
        val t = 1_762_178_700_000.0   // 2025-11-03 14:05 UTC
        assertEquals("Nov 3, 2025 at 2:05 PM", MessageInfoRules.dateLine(t, false, utc, Locale.US))
        assertEquals("Nov 3, 2025 at 14:05", MessageInfoRules.dateLine(t, true, utc, Locale.US))
    }
}
