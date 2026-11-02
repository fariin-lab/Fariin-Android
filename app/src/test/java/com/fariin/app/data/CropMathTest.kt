package com.fariin.app.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CropMathTest {
    @Test fun minScaleCoversBothEdges() {
        assertEquals(0.5f, CropMath.minScale(2000, 1000, 500f), 1e-4f)
        assertEquals(0.5f, CropMath.minScale(1000, 3000, 500f), 1e-4f)
    }

    @Test fun windowIsAsBigAsTheScreenAllows() {
        assertEquals(1032f, CropMath.windowSide(1080f, 2400f, 24f, 200f), 1e-3f)
        assertEquals(880f, CropMath.windowSide(2400f, 1080f, 24f, 200f), 1e-3f)
    }

    @Test fun zoomStaysBetweenFitAndFourTimes() {
        val (s1, _, _) = CropMath.step(1000, 1000, 500f, 0.5f, 0f, 0f, 0.2f, 0f, 0f, 0f, 0f)
        assertEquals(0.5f, s1, 1e-4f)
        val (s2, _, _) = CropMath.step(1000, 1000, 500f, 0.5f, 0f, 0f, 100f, 0f, 0f, 0f, 0f)
        assertEquals(2f, s2, 1e-4f)
    }

    @Test fun dragNeverShowsAnEdge() {
        // 2000x1000 at 0.5 is 1000x500 on screen in a 500 window: 250 px each way across, none up/down.
        val (_, x, y) = CropMath.step(2000, 1000, 500f, 0.5f, 0f, 0f, 1f, 0f, 0f, 900f, 40f)
        assertEquals(250f, x, 1e-4f)
        assertEquals(0f, y, 1e-4f)
    }

    @Test fun centredCropIsTheMiddleSquare() {
        assertArrayEquals(intArrayOf(500, 0, 1000), CropMath.cropRect(2000, 1000, 500f, 0.5f, 0f, 0f))
    }

    @Test fun offCentreCropFollowsThePhoto() {
        // Photo moved right by 250 screen px = the window now shows its left part.
        assertArrayEquals(intArrayOf(0, 0, 1000), CropMath.cropRect(2000, 1000, 500f, 0.5f, 250f, 0f))
        // Zoomed 2x (scale 1) and moved up-left: a 500 square from the bottom-right quarter.
        assertArrayEquals(intArrayOf(1250, 500, 500), CropMath.cropRect(2000, 1000, 500f, 1f, -500f, -250f))
    }

    @Test fun zoomAboutTheFingersKeepsThatPointStill() {
        // Fingers 100 px right of centre; the image point there stays put after a 2x zoom.
        val (s, x, _) = CropMath.step(4000, 4000, 500f, 0.125f, 0f, 0f, 2f, 100f, 0f, 0f, 0f)
        assertEquals(0.25f, s, 1e-4f)
        assertEquals(-100f, x, 1e-3f)
    }

    @Test fun outputIsCapped() {
        assertEquals(1280, CropMath.outputSide(3000))
        assertEquals(700, CropMath.outputSide(700))
    }

    @Test fun descriptionIsOneParagraphWithinTheLimit() {
        assertEquals("one two three", GroupRules.oneParagraph("one\n\ntwo   three"))
        assertEquals(200, GroupRules.oneParagraph("a".repeat(250)).length)
        assertEquals("hi", GroupRules.cleanDesc("  hi \n"))
        assertEquals("", GroupRules.description(mapOf("title" to "x")))
        assertEquals("About us", GroupRules.description(mapOf("desc" to " About us ")))
    }
}
