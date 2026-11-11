package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The channel store (CHANNEL-SYNC-SPEC 5-8), the iOS ChannelHistoryStoreTests cases ported. */
class ChannelHistoryTest {
    private data class P(val text: String = "")
    private val now = 2_000_000_000L
    private fun at(s: Int): Long = (1_000_000L + s) * 1_000_000L
    private fun doc(id: String, s: Int, chg: Long?, deleted: Boolean = false, text: String = "") =
        ChannelHistory.Doc(id, at(s), deleted, chg, P(text))
    private val visible: (ChannelHistory.Doc<P>) -> Boolean = { !it.deleted }
    private val F = ChannelHistory.FUTURE

    private fun serverPage(server: List<ChannelHistory.Doc<P>>, upper: Long, limit: Int = 60) =
        server.filter { it.at <= upper }.sortedByDescending { it.at }.take(limit)

    private fun fill(h: ChannelHistory<P>, server: List<ChannelHistory.Doc<P>>, want: Int = 40): Int {
        var pages = 0
        while (h.needsFill(want, visible) && pages < 30) {
            val hole = h.topHole ?: break
            val page = serverPage(server, hole.upper)
            h.applyPage(page, hole.upper, full = page.size >= 60, fromServer = true, nowMs = now)
            pages++
        }
        return pages
    }

    @Test fun fillReadsPastDeletedPostsToTheLivePost() {
        val server = listOf(doc("live", 0, 1, text = "Hello")) + (1..95).map { doc("d$it", it, it + 1L, deleted = true) }
        val h = ChannelHistory<P>()
        assertEquals(2, fill(h, server))
        assertEquals(1, h.size)
        assertEquals("Hello", h.post("live")?.value?.text)
        assertTrue(h.reachedStart)
    }

    @Test fun fillStopsAtTheWantedCount() {
        val h = ChannelHistory<P>()
        fill(h, (0 until 500).map { doc("p$it", it, it + 1L) })
        assertEquals(60, h.size)
        assertNotNull(h.topHole)
    }

    @Test fun deletedNewestCannotComeBackFromAnOlderRead() {
        val h = ChannelHistory<P>()
        h.applyPage(listOf(doc("c", 3, 3), doc("b", 2, 2), doc("a", 1, 1)), F, full = false, fromServer = true, nowMs = now)
        h.lastChg = 3
        assertEquals(ChannelHistory.Outcome.REMOVED, h.applyChange(doc("c", 3, 4, deleted = true), now))
        h.applyPage(listOf(doc("c", 3, 3), doc("b", 2, 2)), at(3), full = false, fromServer = true, nowMs = now)
        assertNull(h.post("c"))
        assertEquals(ChannelHistory.Outcome.IGNORED, h.applyChange(doc("c", 3, null), now))
        assertEquals(2, h.size)
    }

    @Test fun offlineBurstAppliedInChangeOrder() {
        val h = ChannelHistory<P>()
        h.applyPage(listOf(doc("a", 1, 1, text = "a1"), doc("b", 2, 2), doc("c", 3, 3)), F, full = false, fromServer = true, nowMs = now)
        val burst = listOf(doc("a", 1, 4, text = "a2"), doc("b", 2, 5, deleted = true), doc("d", 4, 6, text = "d1"),
            doc("d", 4, 7, text = "d2"), doc("e", 5, 8), doc("e", 5, 9, deleted = true))
        val out = burst.map { h.applyChange(it, now) }
        assertEquals(listOf(ChannelHistory.Outcome.EDITED, ChannelHistory.Outcome.REMOVED, ChannelHistory.Outcome.ADDED,
            ChannelHistory.Outcome.EDITED, ChannelHistory.Outcome.ADDED, ChannelHistory.Outcome.REMOVED), out)
        assertEquals(listOf("a", "c", "d"), h.sorted().map { it.id })
        burst.forEach { h.applyChange(it, now) }
        assertEquals("d2", h.post("d")?.value?.text)
        assertEquals(ChannelHistory.Outcome.IGNORED, h.applyChange(doc("a", 1, 2, text = "stale"), now))
    }

    @Test fun tooLongKeepsOldRowsAndMarksAHole() {
        val h = ChannelHistory<P>()
        val old = (1..5).map { doc("o$it", it, it.toLong()) }
        h.applyPage(old.reversed(), F, full = false, fromServer = true, nowMs = now)
        val newest = (1..60).map { doc("n$it", 1000 + it, 300L + it) }
        h.resetForTooLong()
        h.applyPage(newest.reversed(), F, full = true, fromServer = true, nowMs = now)
        assertEquals(65, h.size)
        assertEquals(at(1001), h.topHole?.upper)
        assertEquals(60, h.visibleCount(visible))
        fill(h, newest + old.take(3), want = 100)
        assertTrue(h.reachedStart)
        assertNull(h.post("o4")); assertNull(h.post("o5")); assertNotNull(h.post("o1"))
        assertEquals(63, h.size)
    }

    @Test fun clearRemovesUpToBefore() {
        val h = ChannelHistory<P>()
        h.applyPage((1..5).map { doc("p$it", it, it.toLong()) }, F, full = false, fromServer = true, nowMs = now)
        assertEquals(setOf("p1", "p2", "p3"), h.clearBefore(at(3), now).map { it.id }.toSet())
        h.applyPage(listOf(doc("p2", 2, 2)), at(3), full = false, fromServer = true, nowMs = now)
        assertNull(h.post("p2"))
    }

    @Test fun changeOutsideReadRangesWaitsForTheHoleRead() {
        val h = ChannelHistory<P>()
        h.applyPage((100 until 160).map { doc("n$it", it, it.toLong()) }, F, full = true, fromServer = true, nowMs = now)
        assertEquals(ChannelHistory.Outcome.IGNORED, h.applyChange(doc("old", 10, 500, text = "edited"), now))
        assertEquals(ChannelHistory.Outcome.UNCHANGED, h.applyChange(doc("gone", 20, 501, deleted = true), now))
        assertTrue(h.isTomb("gone"))
        assertEquals(ChannelHistory.Outcome.ADDED, h.applyChange(doc("new", 200, 502), now))
        h.applyPage(listOf(doc("old", 10, 500, text = "edited"), doc("gone", 20, 400)), at(100), full = false, fromServer = true, nowMs = now)
        assertEquals("edited", h.post("old")?.value?.text)
        assertNull(h.post("gone"))
    }

    @Test fun adoptBeforeShortWindowDropsHardDeletedCachedPost() {
        val h = ChannelHistory<P>()
        h.adoptUnverified(listOf(doc("cachedGone", 1, null), doc("kept", 2, null)))
        h.applyPage(listOf(doc("new", 3, 3), doc("kept", 2, 2)), F, full = false, fromServer = true, nowMs = now)
        assertTrue(h.reachedStart)
        assertEquals(listOf("kept", "new"), h.sorted().map { it.id })
    }

    @Test fun rangesMergeAndBoundMakesAHole() {
        val h = ChannelHistory<P>()
        h.markKnown(at(100), F); h.markKnown(at(10), at(50))
        assertEquals(ChannelHistory.Hole(at(100), at(50)), h.topHole)
        h.markKnown(at(40), at(100))
        assertEquals(1, h.rangesCopy().size)
        val g = ChannelHistory<P>()
        g.applyPage((0 until 100).map { doc("p$it", it, it + 1L) }, F, full = false, fromServer = true, nowMs = now)
        g.bound(30, visible)
        assertEquals(30, g.size)
        assertEquals("p70", g.sorted().first().id)
        assertFalse(g.reachedStart)
        assertEquals(at(69), g.topHole?.upper)
    }
}
