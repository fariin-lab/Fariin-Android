package com.fariin.app.ui.stories

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The trim page against Kulan-iOS StoryEditorView DraftItem.isTrimmed / trimRange. */
class StoryTrimRulesTest {
    @Test fun aHairOffEitherEndIsUntouched() {
        assertFalse(StoryTrimRules.isTrimmed(0.02f, 0f, 30f))
        assertFalse(StoryTrimRules.isTrimmed(0f, 29.97f, 30f))
        assertTrue(StoryTrimRules.isTrimmed(1f, 0f, 30f))
        assertTrue(StoryTrimRules.isTrimmed(0f, 20f, 30f))
    }

    @Test fun theRangeIsInMillisecondsAndAnOpenEndRunsToTheClipsEnd() {
        assertNull(StoryTrimRules.rangeMs(0f, 0f, 30f))
        assertEquals(2000L..30000L, StoryTrimRules.rangeMs(2f, 0f, 30f))
        assertEquals(0L..12500L, StoryTrimRules.rangeMs(0f, 12.5f, 30f))
    }

    @Test fun theClockReadsMinutesAndSeconds() {
        assertEquals("0:07", StoryTrimRules.clock(7.9f))
        assertEquals("1:05", StoryTrimRules.clock(65f))
    }
}
