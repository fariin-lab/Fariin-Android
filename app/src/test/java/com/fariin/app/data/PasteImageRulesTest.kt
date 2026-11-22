package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PasteImageRulesTest {
    @Test fun picturesAccepted() {
        for (m in listOf("image/png", "image/jpeg", "image/webp", "image/heic", "image/heif", "image/gif", "IMAGE/PNG", " image/jpeg ")) {
            assertTrue(m, PasteImageRules.accepts(m))
        }
    }

    @Test fun otherKindsRefused() {
        for (m in listOf(null, "", "image/svg+xml", "image/*", "video/mp4", "text/plain", "application/pdf")) {
            assertFalse(m.toString(), PasteImageRules.accepts(m))
        }
    }

    /** iOS pastedImages: no words or only an address, the picture goes; real words, the words go. */
    @Test fun pictureOrWords() {
        assertTrue(PasteImageRules.pictureWins(null))
        assertTrue(PasteImageRules.pictureWins(""))
        assertTrue(PasteImageRules.pictureWins("  \n "))
        assertTrue(PasteImageRules.pictureWins("https://example.com/cat.png"))
        assertTrue(PasteImageRules.pictureWins(" http://a.b/c?d=e \n"))
        assertFalse(PasteImageRules.pictureWins("look at this"))
        assertFalse(PasteImageRules.pictureWins("https://a.b and more"))
        assertFalse(PasteImageRules.pictureWins("line one\nline two"))
        assertFalse(PasteImageRules.pictureWins("example.com"))
    }

    @Test fun takeKeepsToThePickLimit() {
        assertEquals(1, PasteImageRules.take(1, 10))
        assertEquals(10, PasteImageRules.take(14, 10))
        assertEquals(0, PasteImageRules.take(-2, 10))
        assertEquals(1, PasteImageRules.take(3, 0))
    }
}
