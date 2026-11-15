package com.fariin.app.data

import com.fariin.app.perf.PerfClass
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BusyChatRulesTest {
    @Test fun intervalByPhoneClass() {
        assertEquals(250L, BusyChatRules.publishIntervalMs(PerfClass.LOW))
        assertEquals(150L, BusyChatRules.publishIntervalMs(PerfClass.AVERAGE))
        assertEquals(150L, BusyChatRules.publishIntervalMs(PerfClass.HIGH))
        assertEquals(150L, BusyChatRules.publishIntervalMs(null))
    }

    @Test fun gateRunsFirstAtOnceThenFolds() {
        val g = PublishGate(150)
        assertEquals(0L, g.ask(1000))
        g.ran(1000)
        // Inside the interval: one scheduled run for the rest of it, later asks fold into it.
        assertEquals(100L, g.ask(1050))
        assertNull(g.ask(1060))
        assertNull(g.ask(1140))
        g.ran(1150)
        // Past the interval: at once again.
        assertEquals(0L, g.ask(1400))
    }

    @Test fun gateUrgentAlwaysAtOnce() {
        val g = PublishGate(150)
        g.ask(1000); g.ran(1000)
        assertEquals(0L, g.ask(1010, urgent = true))
        g.ran(1010)
        assertEquals(150L, g.ask(1010))
    }

    @Test fun gateAtMostOnePublishPerInterval() {
        // 20 answers a second for two seconds: no more than one run per 150 ms.
        val g = PublishGate(150)
        var due: Long? = null
        val runs = ArrayList<Long>()
        var t = 0L
        while (t < 2000) {
            if (due != null && t >= due!!) { g.ran(t); runs.add(t); due = null }
            val w = g.ask(t)
            if (w == 0L) { g.ran(t); runs.add(t) } else if (w != null) due = t + w
            t += 50
        }
        for (i in 1 until runs.size) assertTrue(runs[i] - runs[i - 1] >= 150)
        assertTrue(runs.size in 12..14)
    }

    @Test fun readMarkOncePerIntervalWithTrailingWrite() {
        val r = ReadThrottle(2500)
        assertEquals(0L, r.ask(10_000))           // the open: at once
        assertEquals(2000L, r.ask(10_500))        // a new message: trailing write later
        assertNull(r.ask(11_000))                 // folded
        assertNull(r.ask(12_000))
        assertTrue(r.takeOwed(12_500))            // the timer fires: one write
        assertFalse(r.takeOwed(12_600))
        assertEquals(2500L, r.ask(12_500))
        assertTrue(r.takeOwed(13_000))            // left the chat early: the owed one goes now
        assertEquals(0L, r.ask(16_000))
    }

    @Test fun readMarkWritesPerMinuteInABusyChat() {
        // 10 messages a second for a minute: at most 25 writes (one per 2.5 s), never 600.
        val r = ReadThrottle(2500)
        var writes = 0
        var due: Long? = null
        var t = 0L
        while (t < 60_000) {
            if (due != null && t >= due!!) { if (r.takeOwed(t)) writes++; due = null }
            val w = r.ask(t)
            if (w == 0L) writes++ else if (w != null) due = t + w
            t += 100
        }
        if (r.takeOwed(t)) writes++   // leaving
        assertTrue("writes=$writes", writes in 24..26)
    }

    @Test fun nothingOwedNothingFlushed() {
        val r = ReadThrottle(2500)
        r.ask(0)
        assertFalse(r.takeOwed(100))
    }

    @Test fun burstOnlyAboveThreeRowsInASecond() {
        val b = ArrivalBurst(1000, 3)
        assertFalse(b.note(0, 1))
        assertFalse(b.note(200, 1))
        assertFalse(b.note(400, 1))
        assertTrue(b.note(600, 1))     // 4 in a second
        assertTrue(b.note(900, 0))
        assertFalse(b.note(1700, 1))   // the old ones left the window
        assertTrue(ArrivalBurst(1000, 3).note(0, 5))
    }

    @Test fun newAtEndCountsRowsAfterPreviousNewest() {
        val l = (1..5).map { msg("m$it", "a", it * 1000.0) }
        assertEquals(2, ArrivalBurst.newAtEnd(l, "m3"))
        assertEquals(0, ArrivalBurst.newAtEnd(l, "m5"))
        assertEquals(0, ArrivalBurst.newAtEnd(l, null))
        assertEquals(0, ArrivalBurst.newAtEnd(l, "gone"))
    }

    @Test fun incrementalGeometryMatchesFull() {
        val zone = ZoneId.of("UTC")
        val day = 86_400_000.0
        val base = ArrayList<Message>()
        val authors = listOf("a", "a", "b", "b", "b", "a", "c")
        for (i in authors.indices) base.add(msg("m$i", authors[i], 10 * day + i * 60_000.0))
        val g = IncrementalGeometry()
        assertEquals(ThreadLogic.geometry(base, zone), g.update(base, zone))
        // Rows added at the bottom, same author then another.
        var list: List<Message> = base + msg("n1", "c", 10 * day + 8 * 60_000.0) + msg("n2", "a", 10 * day + 9 * 60_000.0)
        assertEquals(ThreadLogic.geometry(list, zone), g.update(list, zone))
        // A row in the middle changed (a reaction makes a new object).
        list = list.toMutableList().also { it[3] = it[3].copy(text = "edited") }
        assertEquals(ThreadLogic.geometry(list, zone), g.update(list, zone))
        // Next day, a long gap, older rows loaded at the top, and a row removed.
        list = listOf(msg("o1", "b", 9 * day)) + list + msg("d2", "c", 11 * day)
        assertEquals(ThreadLogic.geometry(list, zone), g.update(list, zone))
        list = list.filterIndexed { i, _ -> i != 5 }
        assertEquals(ThreadLogic.geometry(list, zone), g.update(list, zone))
        // The same list again: the same answer.
        assertEquals(ThreadLogic.geometry(list, zone), g.update(list, zone))
        assertEquals(emptyList<ThreadLogic.RowGeometry>(), g.update(emptyList(), zone))
    }

    private fun msg(id: String, author: String, at: Double) =
        Message(id = id, authorId = author, text = "hi", createdAtMillis = at, hasServerTime = true)
}
