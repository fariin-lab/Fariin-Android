package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryLimitsTest {
    @Test fun upTo90SecondsIsOneStory() {
        assertTrue(StoryLimits.segments(30.0).isEmpty())
        assertTrue(StoryLimits.segments(90.4).isEmpty())
    }

    @Test fun longerSplitsInto90SecondPieces() {
        assertEquals(listOf(0L..90_000L, 90_000L..150_000L), StoryLimits.segments(150.0))
        val ten = StoryLimits.segments(600.0)
        assertEquals(7, ten.size)
        assertEquals(540_000L..600_000L, ten.last())
        // Past ten minutes only the first ten are taken (the pick is refused before that anyway).
        assertEquals(7, StoryLimits.segments(700.0).size)
    }

    @Test fun pickLimitIsTenMinutesWithASecondOfSlack() {
        assertFalse(StoryLimits.pickTooLong(600.9))
        assertTrue(StoryLimits.pickTooLong(601.5))
    }

    @Test fun dailyWindow() {
        val now = 10L * 86_400_000
        assertFalse(StoryLimits.reached(null, 99, 50, now))                    // unknown: fails open
        assertTrue(StoryLimits.reached(now - 3_600_000, 50, 50, now))
        assertFalse(StoryLimits.reached(now - 3_600_000, 49, 50, now))
        assertFalse(StoryLimits.reached(now - 86_400_001, 50, 50, now))        // the window rolled
        assertEquals(23, StoryLimits.hoursLeft(now - 3_600_000, now))
        assertEquals(1, StoryLimits.hoursLeft(now - 86_399_000, now))
    }

    @Test fun verifiedNeverGetsLess() {
        assertEquals(50, AppLimits.tiered(emptyMap(), "stories_per_day", 50, 100, verified = false))
        assertEquals(100, AppLimits.tiered(emptyMap(), "stories_per_day", 50, 100, verified = true))
        assertEquals(200, AppLimits.tiered(mapOf("stories_per_day_default" to 200L), "stories_per_day", 50, 100, verified = true))
    }
}
