package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A long text split in bubbles keeps each part's own bold and links (iOS ThreadView.send). */
class TextFormatPartsTest {
    @Test fun eachPartGetsItsOwnRuns() {
        val whole = "aaaa bbbb\n\ncccc dddd"
        val fmt = TextFormat.serialize(listOf(TextFormat.Span(0, 4), TextFormat.Span(7, 18)), whole.length,
            listOf(TextFormat.Link(16, 20, "https://fariin.com")))
        val parts = listOf("aaaa bbbb", "cccc dddd")
        val out = TextFormat.forParts(fmt, whole, parts)
        assertEquals(listOf(TextFormat.Span(0, 4), TextFormat.Span(7, 9)), TextFormat.parse(out[0], 9))
        assertEquals(listOf(TextFormat.Span(0, 7)), TextFormat.parse(out[1], 9))
        assertEquals(listOf(TextFormat.Link(5, 9, "https://fariin.com")), TextFormat.parseLinks(out[1], 9))
    }

    @Test fun plainTextStaysPlain() {
        assertEquals(listOf(null, null), TextFormat.forParts(null, "a b", listOf("a", "b")))
        val fmt = TextFormat.serialize(listOf(TextFormat.Span(0, 1)), 3)
        val out = TextFormat.forParts(fmt, "a b", listOf("a", "b"))
        assertEquals(listOf(TextFormat.Span(0, 1)), TextFormat.parse(out[0], 1))
        assertNull(out[1])
    }
}
