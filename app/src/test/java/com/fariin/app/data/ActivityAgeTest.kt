package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ActivityAgeTest {
    private val now = 10_000_000_000L
    private fun ago(s: Long) = ActivityRules.age(now - s * 1000, now)

    @Test fun shortAges() {
        assertEquals("now", ago(59))
        assertEquals("5m", ago(5 * 60))
        assertEquals("3h", ago(3 * 3600 + 59))
        assertEquals("6d", ago(6 * 86_400 + 3600))
    }

    /** iOS ActivityTime.age keeps counting in weeks; it never switches to a date. */
    @Test fun weeksAfterAWeek() {
        assertEquals("1w", ago(7 * 86_400))
        assertEquals("6w", ago(45 * 86_400))
        assertEquals("now", ActivityRules.age(now + 5000, now))
    }
}
