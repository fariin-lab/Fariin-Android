package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WallpaperFitTest {
    @Test fun aLibraryPhotoShrinksToCoverAPhoneScreen() {
        // The low-end emulator's case: 2560 x 1920 on 720 x 1600.
        val p = WallpaperFit.plan(2560, 1920, 720, 1600, cropSides = false)!!
        assertEquals(1, p.sample)
        assertEquals(1600, p.h)
        assertEquals(2133, p.w)
        assertEquals(p.w, p.cropW)
    }

    @Test fun aLowPhoneAlsoDropsTheSidesTheFillCutsOff() {
        val p = WallpaperFit.plan(2560, 1920, 720, 1600, cropSides = true)!!
        assertEquals(720, p.cropW)
        assertEquals(1600, p.h)
        assertTrue(p.cropW.toLong() * p.h * 4 < 5_000_000)
    }

    @Test fun aTallPictureIsNeverCutTopToBottom() {
        val p = WallpaperFit.plan(1440, 4000, 720, 1600, cropSides = true)!!
        assertEquals(720, p.cropW)
        assertTrue(p.h >= 1600)
        assertEquals(Math.round(4000 * 0.5f), p.h)
    }

    @Test fun theDecodeStepKeepsThePictureCoveringTheScreen() {
        assertEquals(2, WallpaperFit.sample(4000, 3000, 720, 1400))
        assertEquals(1, WallpaperFit.sample(2560, 1920, 720, 1600))
        assertEquals(4, WallpaperFit.sample(4000, 8000, 720, 1600))
        val p = WallpaperFit.plan(4000, 8000, 720, 1600, cropSides = false)!!
        assertTrue(p.w >= 720 && p.h >= 1600)
    }

    @Test fun aSmallPictureIsNotBlownUp() {
        val p = WallpaperFit.plan(500, 800, 720, 1600, cropSides = true)!!
        assertEquals(1, p.sample)
        assertEquals(500, p.w)
        assertEquals(800, p.h)
        assertEquals(500, p.cropW)
    }

    @Test fun noPlanForAnUnreadablePicture() {
        assertNull(WallpaperFit.plan(0, 0, 720, 1600, cropSides = false))
        assertNull(WallpaperFit.plan(100, 100, 0, 1600, cropSides = false))
    }
}
