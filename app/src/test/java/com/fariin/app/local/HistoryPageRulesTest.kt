package com.fariin.app.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryPageRulesTest {
    private val k = HistoryPageRules::Key

    /** Pages of [size] the way the pull reads them, from [fromMs] down. */
    private fun pull(rows: List<HistoryPageRules.Key>, size: Int, fromMs: Long): List<HistoryPageRules.Key> {
        val read = ArrayList<HistoryPageRules.Key>()
        var last: HistoryPageRules.Key? = null
        while (true) {
            val page = rows.filter { HistoryPageRules.inNextPage(it, last, fromMs) }
                .sortedWith(HistoryPageRules.newestFirst).take(size)
            read += page
            if (page.size < size) return read
            last = page.last()
        }
    }

    @Test fun equalMillisAtThePageEdgeAreAllRead() {
        val rows = listOf(k(10, "a"), k(9, "b"), k(9, "c"), k(9, "d"), k(8, "e"), k(7, "f"))
        val read = pull(rows, 2, 10)
        assertEquals(rows.toSet(), read.toSet())
        assertEquals(rows.size, read.size)
    }

    @Test fun aWholePageInOneMillisecondStillMovesOn() {
        val rows = (0 until 7).map { k(5, "m$it") } + k(4, "z")
        assertEquals(rows.toSet(), pull(rows, 3, 5).toSet())
    }

    @Test fun resumeFromTheNoteIsInclusive() {
        assertTrue(HistoryPageRules.inNextPage(k(9, "x"), null, 9))
        assertFalse(HistoryPageRules.inNextPage(k(10, "x"), null, 9))
    }

    @Test fun sameMillisecondSmallerIdComesAfter() {
        assertTrue(HistoryPageRules.inNextPage(k(9, "b"), k(9, "c"), 0))
        assertFalse(HistoryPageRules.inNextPage(k(9, "d"), k(9, "c"), 0))
    }
}
