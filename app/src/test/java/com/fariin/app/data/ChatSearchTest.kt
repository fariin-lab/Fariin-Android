package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

class ChatSearchTest {
    private val zone = ZoneId.of("UTC")
    private fun item(id: String, text: String, at: Double) = ChatSearch.Item(id, text, "a", at)

    @Test fun foldsCaseAccentsAndWidth() {
        assertTrue(ChatSearch.matches("Café au lait", "cafe"))
        assertTrue(ChatSearch.matches("CAFE", "café"))
        assertTrue(ChatSearch.matches("ＡＢＣ fullwidth", "abc"))
        assertEquals("hello  world ", ChatSearch.normalize("Hello, World!"))
    }

    @Test fun everyTermMustStartAWord() {
        assertTrue(ChatSearch.matches("Hello World", "hel wor"))
        assertTrue(ChatSearch.matches("Hello World", "wor hel"))
        assertFalse(ChatSearch.matches("Hello World", "ello"))
        assertFalse(ChatSearch.matches("Hello World", "hel xyz"))
    }

    @Test fun digitsSplitOutLikeIos() {
        assertEquals(setOf("abc", "123"), ChatSearch.queryTerms("abc123").toSet())
        assertEquals(listOf("555123"), ChatSearch.queryTerms("555 123"))
        assertEquals(setOf("room", "floor", "5", "2"), ChatSearch.queryTerms("room 5 floor 2").toSet())
        assertTrue(ChatSearch.matches("order abc123 shipped", "123"))
        assertTrue(ChatSearch.tokens("abc123").containsAll(listOf("abc123", "abc", "123")))
        assertTrue(ChatSearch.queryTerms("!!").isEmpty())
    }

    @Test fun twoLettersAtLeastAndTheNewest500() {
        val corpus = (1..600).map { item("m$it", "hello $it", it.toDouble()) }
        assertTrue(ChatSearch.run("h", corpus, emptyList()).isEmpty())
        assertTrue(ChatSearch.run(" h ", corpus, emptyList()).isEmpty())
        val r = ChatSearch.run("he", corpus, emptyList())
        assertEquals(500, r.size)
        assertEquals("m101", r.first().id)
        assertEquals("m600", r.last().id)
    }

    @Test fun corpusAndWindowMergeById() {
        val r = ChatSearch.run("hi", listOf(item("a", "hi there", 1.0)), listOf(item("a", "hi there", 1.0), item("b", "hi again", 2.0)))
        assertEquals(listOf("a", "b"), r.map { it.id })
    }

    @Test fun focusIsKeptFromTheNewestEnd() {
        val old = (1..10).map { item("m$it", "x", it.toDouble()) }
        val new = (1..4).map { item("m$it", "x", it.toDouble()) }
        // Two from the newest stays two from the newest.
        assertEquals(1, ChatSearch.keepIndex(old, 7, new, sameMessage = false))
        // Further back than the new list holds: the oldest.
        assertEquals(0, ChatSearch.keepIndex(old, 0, new, sameMessage = false))
        // A silent refresh keeps the same message.
        assertEquals(2, ChatSearch.keepIndex(old, 2, new, sameMessage = true))
        assertEquals(3, ChatSearch.keepIndex(emptyList(), 0, new, sameMessage = false))
    }

    @Test fun countWords() {
        assertEquals("No results", ChatSearch.countText(0, 0, false))
        assertEquals("1 of 12", ChatSearch.countText(12, 11, false))
        assertEquals("12 of 12", ChatSearch.countText(12, 0, false))
        assertEquals("12 messages", ChatSearch.countText(12, 3, true))
        assertEquals("1 message", ChatSearch.countText(1, 0, true))
    }

    @Test fun highlightsTermsAtWordStartsOnly() {
        val t = "Hello world, othello"
        assertEquals(listOf(0 until 3), ChatSearch.highlightRanges(t, "hel"))
        assertEquals(listOf(0 until 3, 6 until 9), ChatSearch.highlightRanges(t, "hel wor"))
        assertEquals(listOf(0 until 4), ChatSearch.highlightRanges("Café", "cafe"))
        assertTrue(ChatSearch.highlightRanges(t, "h").isEmpty())
    }

    @Test fun snippetStartsNearTheFirstMatch() {
        val short = ChatSearch.snippet("see you tomorrow", listOf("tom"))
        assertEquals("see you tomorrow", short.text)
        assertEquals(listOf(8 until 11), short.marks)
        val long = ChatSearch.snippet("one two three four five six seven eight nine\nten", listOf("nine"))
        assertTrue(long.text.startsWith("…"))
        assertTrue(long.text.endsWith("nine ten"))
        val at = long.text.indexOf("nine")
        assertEquals(listOf(at until at + 4), long.marks)
        // Backs up 12 characters then to the start of that word.
        assertEquals("…seven eight nine ten", long.text)
    }

    @Test fun safeTextNeverTheRawMarker() {
        fun m(text: String, viewOnce: Boolean = false, deleted: Boolean = false) =
            Message(id = "x", authorId = "a", text = text, createdAtMillis = 1.0, viewOnce = viewOnce, deleted = deleted)
        assertEquals("hi", ChatSearch.searchText(m("hi")))
        assertEquals(null, ChatSearch.searchText(m("hi", viewOnce = true)))
        assertEquals(null, ChatSearch.searchText(m("hi", deleted = true)))
        assertEquals(null, ChatSearch.searchText(m("")))
        assertEquals("📌 Pinned a message", ChatSearch.searchText(m(PinRules.MARKER + "abc|\"hi\"")))
    }

    @Test fun dateRuleOfTheChatList() {
        val now = LocalDateTime.of(2026, 11, 3, 15, 0).atZone(zone).toInstant().toEpochMilli()
        fun at(d: Int, h: Int) = LocalDateTime.of(2026, 11, d, h, 5).atZone(zone).toInstant().toEpochMilli().toDouble()
        assertEquals("9:05 AM", ChatSearch.dateText(at(3, 9), false, now, zone, Locale.US))
        assertEquals("09:05", ChatSearch.dateText(at(3, 9), true, now, zone, Locale.US))
        assertEquals("Mon", ChatSearch.dateText(at(2, 9), false, now, zone, Locale.US))
        fun oct(d: Int) = LocalDateTime.of(2026, 10, d, 9, 5).atZone(zone).toInstant().toEpochMilli().toDouble()
        assertEquals("Wed", ChatSearch.dateText(oct(28), false, now, zone, Locale.US))   // six days back
        assertEquals("10/27/26", ChatSearch.dateText(oct(27), false, now, zone, Locale.US))   // a week back
    }
}
