package com.fariin.app.ui.thread

import com.fariin.app.data.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrivalRulesTest {
    private fun m(id: String, by: String = "them") = Message(id = id, authorId = by, text = id, createdAtMillis = 0.0)
    private val old = listOf(m("a"), m("b", "me"), m("c"))
    private val oldIds = old.map { it.rowId }.toSet()

    private fun run(next: List<Message>, prevIds: Set<String>? = oldIds, prevLast: String? = "c", live: Boolean = true,
                    atBottom: Boolean = true, scrolling: Boolean = false) =
        ArrivalRules.arrivals(prevIds, prevLast, live, next, "me", atBottom, scrolling)

    @Test fun liveArrivalAtTheNewestGlides() = assertEquals(listOf("d"), run(old + m("d")))

    @Test fun severalInOneUpdateGlideTogether() = assertEquals(listOf("d", "e"), run(old + m("d") + m("e")))

    @Test fun myOwnRowsNeverGlideHere() = assertEquals(listOf("e"), run(old + m("d", "me") + m("e")))

    @Test fun notWhileTheChatIsOpening() {
        assertTrue(run(old + m("d"), prevIds = null, prevLast = null).isEmpty())   // first emission
        assertTrue(run(old + m("d"), live = false).isEmpty())                      // cache, then server window
    }

    @Test fun notUpInHistoryOrUnderAFinger() {
        assertTrue(run(old + m("d"), atBottom = false).isEmpty())
        assertTrue(run(old + m("d"), scrolling = true).isEmpty())
    }

    @Test fun onlyAPlainAppend() {
        // The window swapped: the old newest is not right above the new rows.
        assertTrue(run(listOf(m("x"), m("y"))).isEmpty())
        assertTrue(run(listOf(m("a"), m("b", "me"), m("d"))).isEmpty())
        // Older history paged in at the top, nothing new at the bottom.
        assertTrue(run(listOf(m("z")) + old).isEmpty())
        // Nothing changed.
        assertTrue(run(old).isEmpty())
    }
}
