package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class SameRowsTest {
    private data class Row(val id: String, val text: String)

    @Test fun unchangedAnswerGivesTheOldList() {
        val prev = listOf(Row("a", "1"), Row("b", "2"))
        val next = listOf(Row("a", "1"), Row("b", "2"))
        assertSame(prev, SameRows.keep(prev, next) { it.id })
    }

    @Test fun onlyTheChangedRowIsNew() {
        val prev = listOf(Row("a", "1"), Row("b", "2"), Row("c", "3"))
        val next = listOf(Row("a", "1"), Row("b", "changed"), Row("c", "3"))
        val out = SameRows.keep(prev, next) { it.id }
        assertEquals(next, out)
        assertSame(prev[0], out[0])
        assertSame(next[1], out[1])
        assertSame(prev[2], out[2])
    }

    @Test fun movedRowsKeepTheirObjectsInTheNewOrder() {
        val prev = listOf(Row("a", "1"), Row("b", "2"))
        val next = listOf(Row("b", "2"), Row("a", "1"))
        val out = SameRows.keep(prev, next) { it.id }
        assertNotSame(prev, out)
        assertEquals(next, out)
        assertSame(prev[1], out[0])
        assertSame(prev[0], out[1])
    }

    @Test fun addedAndRemovedRows() {
        val prev = listOf(Row("a", "1"), Row("b", "2"))
        val next = listOf(Row("c", "3"), Row("a", "1"))
        val out = SameRows.keep(prev, next) { it.id }
        assertEquals(next, out)
        assertSame(next[0], out[0])
        assertSame(prev[0], out[1])
    }

    @Test fun nothingBeforeGivesTheNewList() {
        val next = listOf(Row("a", "1"))
        assertSame(next, SameRows.keep(null, next) { it.id })
        assertSame(next, SameRows.keep(emptyList(), next) { it.id })
        val empty = emptyList<Row>()
        assertSame(empty, SameRows.keep(listOf(Row("a", "1")), empty) { it.id })
    }
}
