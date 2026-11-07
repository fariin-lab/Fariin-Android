package com.fariin.app.data

import com.fariin.app.crypto.Wire
import com.fariin.app.data.TextFormat.Span
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextFormatTest {
    @Test fun serializesSortedMergedAndClamped() {
        val json = TextFormat.serialize(listOf(Span(6, 9), Span(0, 2), Span(2, 4), Span(8, 40)), 10)
        assertEquals("""[[0,4,"b"],[6,4,"b"]]""", json)
    }

    @Test fun nothingBoldWritesNothing() {
        assertNull(TextFormat.serialize(emptyList(), 5))
        assertNull(TextFormat.serialize(listOf(Span(3, 3), Span(7, 9)), 5))
    }

    @Test fun parsesTheWireShape() {
        assertEquals(listOf(Span(0, 4), Span(6, 10)), TextFormat.parse("""[[0,4,"b"],[6,4,"b"]]""", 10))
    }

    @Test fun parseDropsRunsOutsideTheTextAndUnknownKinds() {
        val p = TextFormat.parse("""[[0,2,"b"],[3,9,"b"],[1,1,"i"],[-1,2,"b"],[4,0,"b"],["x",1,"b"]]""", 5)
        assertEquals(listOf(Span(0, 2)), p)
    }

    @Test fun parseOfJunkIsNoBold() {
        assertTrue(TextFormat.parse("not json", 5).isEmpty())
        assertTrue(TextFormat.parse(null, 5).isEmpty())
        assertTrue(TextFormat.parse("""[[0,2,"b"]]""", 0).isEmpty())
        assertTrue(TextFormat.parse(Wire.NOT_READY, 5).isEmpty())
    }

    @Test fun parseMergesOverlaps() {
        assertEquals(listOf(Span(0, 5)), TextFormat.parse("""[[2,3,"b"],[0,3,"b"]]""", 5))
    }

    @Test fun roundTripCountsUtf16Units() {
        val text = "hi 😀 there"   // the emoji is two code units
        val spans = listOf(Span(3, 5))
        val json = TextFormat.serialize(spans, text.length)
        assertEquals("""[[3,2,"b"]]""", json)
        assertEquals(spans, TextFormat.parse(json, text.length))
    }

    @Test fun toggleBoldsAMixedSelection() {
        assertEquals(listOf(Span(0, 6)), TextFormat.toggle(listOf(Span(0, 2)), 1, 6, 10))
    }

    @Test fun toggleOfAllBoldMakesItRegular() {
        assertEquals(listOf(Span(0, 2), Span(5, 8)), TextFormat.toggle(listOf(Span(0, 8)), 2, 5, 10))
        assertTrue(TextFormat.toggle(listOf(Span(2, 5)), 5, 2, 10).isEmpty())   // reversed selection
    }

    @Test fun toggleOfEmptySelectionChangesNothing() {
        assertEquals(listOf(Span(1, 3)), TextFormat.toggle(listOf(Span(1, 3)), 4, 4, 10))
    }

    @Test fun isAllBold() {
        assertTrue(TextFormat.isAllBold(listOf(Span(0, 5)), 1, 4))
        assertFalse(TextFormat.isAllBold(listOf(Span(0, 2)), 1, 4))
        assertFalse(TextFormat.isAllBold(listOf(Span(0, 5)), 3, 3))
    }

    @Test fun typingAfterABoldRunStartsRegular() {
        assertEquals(listOf(Span(0, 5)), TextFormat.adjust(listOf(Span(0, 5)), "hello", "hello!"))
    }

    @Test fun typingInsideABoldRunSplitsIt() {
        assertEquals(listOf(Span(0, 2), Span(3, 6)), TextFormat.adjust(listOf(Span(0, 5)), "hello", "heXllo"))
    }

    @Test fun typingBeforeShiftsTheRun() {
        assertEquals(listOf(Span(3, 8)), TextFormat.adjust(listOf(Span(1, 6)), " hello", "XX hello"))
    }

    @Test fun deletingShrinksAndRejoins() {
        // "ab|cd|ef" with ab and ef bold; deleting "cd" leaves one run "abef".
        assertEquals(listOf(Span(0, 4)), TextFormat.adjust(listOf(Span(0, 2), Span(4, 6)), "abcdef", "abef"))
        // deleting inside a run
        assertEquals(listOf(Span(0, 3)), TextFormat.adjust(listOf(Span(0, 5)), "hello", "heo"))
        // clearing the field
        assertTrue(TextFormat.adjust(listOf(Span(0, 5)), "hello", "").isEmpty())
    }

    @Test fun replacingASelectionDropsItsBold() {
        assertEquals(listOf(Span(0, 1), Span(4, 5)), TextFormat.adjust(listOf(Span(0, 5)), "hello", "hXYZo"))
    }

    @Test fun trimmedTextMovesTheRuns() {
        assertEquals(listOf(Span(0, 3)), TextFormat.forTrimmed(listOf(Span(1, 6)), "  hey  "))
        assertEquals(listOf(Span(0, 3)), TextFormat.forTrimmed(listOf(Span(0, 5)), "  hey"))
    }

    @Test fun rangesAreInclusive() {
        assertEquals(listOf(0..3), TextFormat.ranges(listOf(Span(0, 4))))
    }
}
