package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CallsSeenTest {
    private fun call(id: String, caller: String, outcome: String, at: Long) =
        CallEntry(id, "c", "N", null, "o", caller, outcome, false, 0, at)

    private val me = "me"
    private val now = 1_000_000L

    @Test fun countsOnlyNewMissedIncoming() {
        val calls = listOf(
            call("a", "them", "missed", 900_000),    // new missed from them: counts
            call("b", "them", "missed", 100_000),    // before the mark: no
            call("c", me, "missed", 950_000),        // mine: no
            call("d", "them", "answered", 960_000),  // answered: no
        )
        assertEquals(1, CallsSeen.count(calls, me, CallsSeen.Mark(500_000, emptyList()), now))
    }

    @Test fun seenByIdNeverCountsAgainEvenIfItsDateMoves() {
        val m = CallsSeen.seen(listOf(call("a", "them", "missed", 900_000)), me, CallsSeen.Mark(500_000, emptyList()), now)
        assertEquals(900_000, m.seenAtMs)
        // The same call now dated later still does not count; a new one does.
        val later = listOf(call("a", "them", "missed", 990_000), call("e", "them", "missed", 995_000))
        assertEquals(1, CallsSeen.count(later, me, m, now))
    }
}
