package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageLengthTest {
    @Test fun shortTextIsOneBubble() {
        assertEquals(listOf("hello"), MessageLength.splitForBubbles("  hello \n"))
        assertEquals(emptyList<String>(), MessageLength.splitForBubbles("   "))
    }

    @Test fun cutsAtLineBreakThenSpace() {
        val a = "a".repeat(60)
        val b = "b".repeat(50)
        // A line break past the middle wins over a later space.
        assertEquals(listOf(a, "$b c"), MessageLength.splitForBubbles("$a\n$b c", max = 100))
        // No line break: the last space past the middle.
        val words = (1..30).joinToString(" ") { "word" }   // 149 chars
        val parts = MessageLength.splitForBubbles(words, max = 100)
        assertTrue(parts.all { it.length <= 100 })
        assertTrue(parts.all { !it.startsWith(" ") && !it.endsWith(" ") })
        assertEquals(words, parts.joinToString(" "))
    }

    @Test fun noSpaceCutsAtTheEdgeNeverInsideAnEmoji() {
        val s = "x".repeat(99) + "😀" + "y".repeat(10)
        val parts = MessageLength.splitForBubbles(s, max = 100)
        assertEquals("x".repeat(99), parts[0])
        assertEquals("😀" + "y".repeat(10), parts[1])
    }

    @Test fun maxLengthTextIsThreeBubbles() {
        val words = buildString { while (length < MessageLength.MAX_CHARS - 6) append("words ") }.trim()
        val parts = MessageLength.splitForBubbles(words)
        assertEquals(3, parts.size)
        assertTrue(parts.all { it.length <= MessageLength.BUBBLE_CHARS })
    }

    @Test fun fieldStopsAtTheCap() {
        val full = "a".repeat(10)
        assertNull(MessageLength.fit(full, full + "b", 11, max = 10))
        assertEquals("abc" to 3, MessageLength.fit("ab", "abc", 3, max = 10))
        // A deletion always stands.
        assertEquals("a" to 1, MessageLength.fit(full, "a", 1, max = 10))
    }

    @Test fun pasteIsTrimmedToTheRoom() {
        // "12345" pasted after "abcdefgh" with room for 2.
        assertEquals("abcdefgh12" to 10, MessageLength.fit("abcdefgh", "abcdefgh12345", 13, max = 10))
        // Pasted in the middle: the tail of the paste goes, the words after the cursor stay.
        assertEquals("ab12cdefgh" to 4, MessageLength.fit("abcdefgh", "ab12345cdefgh", 7, max = 10))
        // Never half an emoji.
        assertNull(MessageLength.fit("abcdefghi", "abcdefghi😀", 11, max = 10))
    }
}
