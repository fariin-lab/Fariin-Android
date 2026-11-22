package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ReplyQuoteRulesTest {
    private fun m(type: String = "text", text: String = "", viewOnce: Boolean = false, fileName: String? = null,
                  callVideo: Boolean = false) =
        Message(id = "m", authorId = "a", text = text, type = type, createdAtMillis = 0.0, viewOnce = viewOnce,
            fileName = fileName, callVideo = callVideo)

    @Test fun kindsAsIos() {
        assertEquals("📷 Photo", ReplyQuoteRules.quoteText(m("image", text = "my caption")))
        assertEquals("View-once photo", ReplyQuoteRules.quoteText(m("image", text = "secret", viewOnce = true)))
        assertEquals("📷 Photos", ReplyQuoteRules.quoteText(m("album")))
        assertEquals("🎥 Video", ReplyQuoteRules.quoteText(m("video")))
        assertEquals("🎤 Voice message", ReplyQuoteRules.quoteText(m("audio")))
        assertEquals("📄 a.pdf", ReplyQuoteRules.quoteText(m("file", fileName = "a.pdf")))
        assertEquals("📄 Document", ReplyQuoteRules.quoteText(m("file")))
        assertEquals("GIF", ReplyQuoteRules.quoteText(m("gif")))
        assertEquals("📹 Video call", ReplyQuoteRules.quoteText(m("call", callVideo = true)))
        assertEquals("📞 Voice call", ReplyQuoteRules.quoteText(m("call")))
        assertEquals("hello", ReplyQuoteRules.quoteText(m(text = "hello")))
    }

    @Test fun glyphDropped() {
        assertEquals("Voice message", ReplyQuoteRules.withoutGlyph("🎤 Voice message"))
        assertEquals("hello", ReplyQuoteRules.withoutGlyph("hello"))
    }
}
