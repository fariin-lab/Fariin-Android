package com.fariin.app.data

import com.fariin.app.ui.thread.CustomReaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Admin-panel custom emoji: the key shape, the list parse and the file check. */
class UploadedEmojiTest {

    @Test fun keyIsCuAndTenLowercaseLettersOrDigits() {
        assertTrue(UploadedEmoji.isKey("cu_abc123xyz0"))
        assertFalse(UploadedEmoji.isKey("cu_abc123xyz"))
        assertFalse(UploadedEmoji.isKey("cu_ABC123XYZ0"))
        assertFalse(UploadedEmoji.isKey("ce_abc123xyz0"))
        assertFalse(UploadedEmoji.isKey(null))
        assertTrue(CustomReaction.isCustom("cu_abc123xyz0"))
        assertTrue(CustomReaction.isCustom("ce_181"))
        assertFalse(CustomReaction.isCustom("ce_182"))
        assertTrue(CustomReaction.looksCustom("ce_182"))
        assertEquals("Custom emoji", CustomReaction.spoken("cu_zz"))
        assertEquals("❤️", CustomReaction.spoken("❤️"))
    }

    @Test fun parseKeepsGoodHttpsEntriesInOrderWithoutRepeats() {
        val raw = listOf(
            mapOf("id" to "aaaaaaaaaa", "url" to "https://x/a.tgs", "name" to "A", "at" to 1),
            mapOf("id" to "bbbbbbbbbb", "url" to "http://x/b.tgs"),
            mapOf("id" to "short", "url" to "https://x/c.tgs"),
            mapOf("id" to "aaaaaaaaaa", "url" to "https://x/again.tgs"),
            "junk",
            mapOf("id" to "dddddddddd", "url" to "https://x/d.tgs"),
        )
        assertEquals(listOf(UploadedEmoji.Item("aaaaaaaaaa", "https://x/a.tgs"), UploadedEmoji.Item("dddddddddd", "https://x/d.tgs")),
            UploadedEmoji.parse(raw))
        assertEquals(emptyList<UploadedEmoji.Item>(), UploadedEmoji.parse(null))
    }

    @Test fun fileMustBeGzipAndAtMost64Kb() {
        val gz = ByteArray(100).also { it[0] = 0x1f; it[1] = 0x8b.toByte() }
        assertTrue(UploadedEmoji.looksValid(gz))
        assertFalse(UploadedEmoji.looksValid(ByteArray(100)))
        assertFalse(UploadedEmoji.looksValid(ByteArray(64 * 1024 + 1).also { it[0] = 0x1f; it[1] = 0x8b.toByte() }))
    }
}
