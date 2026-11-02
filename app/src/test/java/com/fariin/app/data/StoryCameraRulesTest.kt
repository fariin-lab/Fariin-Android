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

    @Test fun slideToLockThreshold() {
        assertEquals(0f, StoryCameraRules.lockProgress(-20f))
        assertEquals(0.5f, StoryCameraRules.lockProgress(30f))
        assertEquals(1f, StoryCameraRules.lockProgress(200f))
        assertFalse(StoryCameraRules.reachesLock(60f))
        assertTrue(StoryCameraRules.reachesLock(61f))
    }

    @Test fun releaseStopsUnlessThisTouchLocked() {
        assertTrue(StoryCameraRules.stopsOnRelease(locked = false, lockedByThisTouch = false))
        assertFalse(StoryCameraRules.stopsOnRelease(locked = true, lockedByThisTouch = true))   // slid onto the lock
        assertTrue(StoryCameraRules.stopsOnRelease(locked = true, lockedByThisTouch = false))   // pressed stop
    }

    @Test fun dragUpZoomMapping() {
        assertEquals(null, StoryCameraRules.dragZoom(1f, upDp = 10f, dxDp = 0f, lensMaxRatio = 10f))
        assertEquals(null, StoryCameraRules.dragZoom(1f, upDp = 200f, dxDp = 40f, lensMaxRatio = 10f))  // going for the lock
        assertEquals(2f, StoryCameraRules.dragZoom(1f, upDp = 70f, dxDp = 5f, lensMaxRatio = 10f)!!, 0.001f)
        assertEquals(4f, StoryCameraRules.dragZoom(3f, upDp = 70f, dxDp = -5f, lensMaxRatio = 10f)!!, 0.001f)
        assertEquals(8f, StoryCameraRules.dragZoom(1f, upDp = 2000f, dxDp = 0f, lensMaxRatio = 10f)!!, 0.001f)  // capped at 8
        assertEquals(2.5f, StoryCameraRules.dragZoom(1f, upDp = 2000f, dxDp = 0f, lensMaxRatio = 2.5f)!!, 0.001f)
    }

    @Test fun zoomPillStops() {
        assertEquals(listOf(0.5f, 1f, 3f), StoryCameraRules.zoomStops(0.5f, 10f))
        assertEquals(listOf(1f, 3f), StoryCameraRules.zoomStops(1f, 10f))
        assertEquals(emptyList<Float>(), StoryCameraRules.zoomStops(1f, 2f))   // only "1": no pill
        assertEquals("1×", StoryCameraRules.zoomLabel(1f, true))
        assertEquals(".5", StoryCameraRules.zoomLabel(0.5f, false))
        assertEquals("3", StoryCameraRules.zoomLabel(3f, false))
    }

    @Test fun screenFlashOnlyOnTheFrontLensWithoutAFlash() {
        assertTrue(StoryCameraRules.needsScreenFlash(flashOn = true, front = true, lensHasFlash = false))
        assertFalse(StoryCameraRules.needsScreenFlash(flashOn = false, front = true, lensHasFlash = false))
        assertFalse(StoryCameraRules.needsScreenFlash(flashOn = true, front = false, lensHasFlash = false))
        assertFalse(StoryCameraRules.needsScreenFlash(flashOn = true, front = true, lensHasFlash = true))
        assertTrue(StoryCameraRules.showsFlashButton(front = true, lensHasFlash = false))
        assertFalse(StoryCameraRules.showsFlashButton(front = false, lensHasFlash = false))
    }
}
