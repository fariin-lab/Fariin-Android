package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class InsightsTest {
    private val today = LocalDate.of(2026, 11, 3)

    @Test fun weekComparesWithTheWeekBeforeOnceTheHistoryReachesIt() {
        val days = listOf(
            InsightsDay("2026-10-21", storyViews = 4, glowersGained = 1),
            InsightsDay("2026-10-30", storyViews = 6, glowersGained = 2, glowersLost = 1, viewHours = mapOf(9 to 12)),
            InsightsDay("2026-11-03", storyViews = 4),
        )
        val r = InsightsReport.build(InsightsPeriod.WEEK, today, days, emptyList(), "2026-10-21", glowersNow = 10)
        assertEquals(LocalDate.of(2026, 10, 28), r.start)
        assertEquals(10.0, r.views.current, 0.0)
        // 21 Oct falls in the week before (21 to 27 Oct).
        assertEquals(4.0, r.views.previous!!, 0.0)
        assertEquals(1.5, r.views.percent!!, 1e-9)
        assertEquals(2, r.glowersGained)
        assertEquals(1, r.glowersLost)
        assertEquals(9, r.bestHourUtc)
        // Counted back from today's 10: the day before 30 Oct had 9.
        assertEquals(9, r.glowersByDay.first { it.date == LocalDate.of(2026, 10, 29) }.value)
        assertTrue(r.coversPeriod)
    }

    @Test fun aShortHistoryIsNotCompared() {
        val r = InsightsReport.build(InsightsPeriod.MONTH, today, listOf(InsightsDay("2026-11-01", storyViews = 3)), emptyList(), "2026-11-01", 0)
        assertNull(r.views.previous)
        assertFalse(r.coversPeriod)
        assertEquals(3, r.recordedDays.size)
        assertNull(r.bestHourUtc)
    }

    @Test fun unlockedAtAHundredGlowersOrForTheAdmin() {
        assertFalse(Insights.isUnlocked(99, "dave"))
        assertTrue(Insights.isUnlocked(100, "dave"))
        assertTrue(Insights.isUnlocked(0, "V"))
    }
}
