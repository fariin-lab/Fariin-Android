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

    @Test fun aCuKeyOffTheListIsRetiredAndLeavesTheChips() {
        val list = listOf(UploadedEmoji.Item("aaaaaaaaaa", "https://x/a.tgs"))
        assertFalse(UploadedEmoji.retired("cu_aaaaaaaaaa", list))
        assertTrue(UploadedEmoji.retired("cu_bbbbbbbbbb", list))
        assertFalse(UploadedEmoji.retired("ce_999", list))
        assertFalse(UploadedEmoji.retired("👍", list))
        val chips = MessageRules.reactionChips(mapOf("a" to "cu_bbbbbbbbbb", "b" to "cu_aaaaaaaaaa", "me" to "cu_bbbbbbbbbb"), "me") {
            UploadedEmoji.retired(it, list)
        }
        assertEquals(listOf(MessageRules.Chip("cu_aaaaaaaaaa", 1, false, listOf("b"))), chips)
    }

    @Test fun fileMustBeGzipAndAtMost64Kb() {
        val gz = ByteArray(100).also { it[0] = 0x1f; it[1] = 0x8b.toByte() }
        assertTrue(UploadedEmoji.looksValid(gz))
        assertFalse(UploadedEmoji.looksValid(ByteArray(100)))
        assertFalse(UploadedEmoji.looksValid(ByteArray(64 * 1024 + 1).also { it[0] = 0x1f; it[1] = 0x8b.toByte() }))
    }

    @Test fun hiddenBuiltInLeaveThePicker() {
        assertEquals(setOf("ce_2", "ce_10"), UploadedEmoji.parseHidden(listOf("ce_2", "ce_10", "cu_abc", "ce_x", 5, null)))
        assertEquals(emptySet<String>(), UploadedEmoji.parseHidden(null))
        assertEquals(emptySet<String>(), UploadedEmoji.parseHidden("ce_1"))
        val built = listOf("ce_1", "ce_2", "ce_3")
        assertEquals(listOf("ce_1", "ce_3"), UploadedEmoji.offered(built, setOf("ce_2")))
        assertEquals(built, UploadedEmoji.offered(built, emptySet()))
    }
}
