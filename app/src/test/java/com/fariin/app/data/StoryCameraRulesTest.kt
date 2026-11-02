package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryCameraRulesTest {
    @Test fun bitrateByResolution() {
        assertEquals(1_200_000, StoryCameraRules.videoBitrate(720))      // 480p
        assertEquals(1_200_000, StoryCameraRules.videoBitrate(640))
        assertEquals(2_500_000, StoryCameraRules.videoBitrate(1280))     // 720p
        assertEquals(4_000_000, StoryCameraRules.videoBitrate(1920))     // 1080p
        assertEquals(1_500_000, StoryCameraRules.videoBitrate(1280, lowEnd = true))
        assertEquals(1_500_000, StoryCameraRules.videoBitrate(1920, lowEnd = true))
        assertEquals(1_000_000, StoryCameraRules.videoBitrate(720, lowEnd = true))
    }

    @Test fun fullLengthClipFitsTheStoryLimit() {
        listOf(640, 1280, 1920, 3840).forEach { side ->
            val bits = StoryCameraRules.videoBitrate(side).toLong() + StoryCameraRules.AUDIO_BITS
            val bytes = bits * StoryCameraRules.MAX_VIDEO_SECONDS / 8
            assertTrue("$side: $bytes", bytes < StoryRules.VIDEO_MAX_BYTES)
        }
        // A very long allowance is squeezed down to the budget, never under the floor.
        assertEquals(600_000, StoryCameraRules.videoBitrate(1920, seconds = 100_000))
    }

    @Test fun clockText() {
        assertEquals("00:00", StoryCameraRules.clock(0))
        assertEquals("00:05", StoryCameraRules.clock(5))
        assertEquals("01:00", StoryCameraRules.clock(60))
        assertEquals("00:00", StoryCameraRules.clock(-3))
    }

    @Test fun tapIsPhotoHoldIsVideo() {
        assertEquals(StoryCameraRules.Press.PHOTO, StoryCameraRules.pressKind(0))
        assertEquals(StoryCameraRules.Press.PHOTO, StoryCameraRules.pressKind(219))
        assertEquals(StoryCameraRules.Press.VIDEO, StoryCameraRules.pressKind(220))
        assertFalse(StoryCameraRules.keepClip(300))
        assertTrue(StoryCameraRules.keepClip(500))
    }

    @Test fun cheapPhonesAskForLess() {
        assertTrue(StoryCameraRules.previewLongSide(true) < StoryCameraRules.previewLongSide(false))
        assertTrue(StoryCameraRules.photoLongSide(true) < StoryCameraRules.photoLongSide(false))
        assertTrue(StoryCameraRules.photoLongSide(true) >= 1080)
    }
}
