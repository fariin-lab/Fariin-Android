package com.fariin.app.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LowPhoneMediaTest {
    @Test fun onlyALowPhoneLimitsPlayingStickers() {
        assertEquals(LowPhoneMedia.LOW_STICKER_SLOTS, LowPhoneMedia.stickerSlots(PerfClass.LOW))
        assertNull(LowPhoneMedia.stickerSlots(PerfClass.AVERAGE))
        assertNull(LowPhoneMedia.stickerSlots(PerfClass.HIGH))
        assertNull(LowPhoneMedia.stickerSlots(null))
    }

    @Test fun aLowPhoneHoldsWhileTheChatSlidesOrScrolls() {
        assertTrue(LowPhoneMedia.held(PerfClass.LOW, pageMoving = true, scrolling = false))
        assertTrue(LowPhoneMedia.held(PerfClass.LOW, pageMoving = false, scrolling = true))
        assertFalse(LowPhoneMedia.held(PerfClass.LOW, pageMoving = false, scrolling = false))
    }

    @Test fun aGifStillIsOneFrameOnALowPhoneOrWithAutoplayOff() {
        assertTrue(LowPhoneMedia.gifStillOnly(PerfClass.LOW, autoplayGifs = true))
        assertTrue(LowPhoneMedia.gifStillOnly(PerfClass.LOW, autoplayGifs = false))
        assertTrue(LowPhoneMedia.gifStillOnly(PerfClass.HIGH, autoplayGifs = false))
        assertFalse(LowPhoneMedia.gifStillOnly(PerfClass.HIGH, autoplayGifs = true))
        assertFalse(LowPhoneMedia.gifStillOnly(PerfClass.AVERAGE, autoplayGifs = true))
    }

    @Test fun otherPhonesNeverHold() {
        for (c in listOf(PerfClass.AVERAGE, PerfClass.HIGH, null)) {
            assertFalse(LowPhoneMedia.held(c, pageMoving = true, scrolling = true))
        }
    }
}
