package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The editor card against Kulan-iOS StoryEditorView defaultZoom / flyRest. */
class StoryCardRulesTest {
    @Test fun onlyAPortraitPhotoFillsTheCard() {
        assertTrue(StoryCardRules.fillsCard(1080, 1440, isVideo = false, carriesStickers = false))
        assertFalse(StoryCardRules.fillsCard(1440, 1080, isVideo = false, carriesStickers = false))
        assertFalse(StoryCardRules.fillsCard(1080, 1080, isVideo = false, carriesStickers = false))
        assertFalse(StoryCardRules.fillsCard(1080, 1920, isVideo = true, carriesStickers = false))
        assertFalse(StoryCardRules.fillsCard(1080, 1920, isVideo = false, carriesStickers = true))
    }

    @Test fun aWidePortraitLosesItsSidesATallOneItsEnds() {
        val wide = StoryCardRules.cropBox(1080, 1440, 9f / 16f)
        assertEquals(810, wide.w); assertEquals(1440, wide.h); assertEquals(135, wide.x); assertEquals(0, wide.y)
        val tall = StoryCardRules.cropBox(900, 2000, 9f / 16f)
        assertEquals(900, tall.w); assertEquals(1600, tall.h); assertEquals(0, tall.x); assertEquals(200, tall.y)
    }

    @Test fun aPortraitOpensFillingTheCardTheSameCutAsCropBox() {
        // 3:4 in a 9:16 card: fitted by height, so it grows by (3/4) / (9/16) to fill the width.
        assertEquals(4f / 3f, StoryCardRules.defaultZoom(1080, 1440, 9f / 16f), 0.0001f)
        assertEquals(1f, StoryCardRules.defaultZoom(1440, 1080, 9f / 16f), 0f)
        assertEquals(1f, StoryCardRules.defaultZoom(1080, 1080, 9f / 16f), 0f)
    }

    @Test fun thePanStopsAtTheCardsEdge() {
        // 400 x 300 fitted, zoom 2 = 800 x 600 in a 400 x 700 frame: 200 each way across, none down.
        val (x, y) = StoryCardRules.clampPan(500f, -50f, 400f, 300f, 2f, 400f, 700f)
        assertEquals(200f, x, 0f); assertEquals(0f, y, 0f)
    }

    @Test fun thePenTransformKeepsTheMiddleWhereItIs() {
        val m = StoryCardRules.strokeTransform(1170f, 2080f, 2f, 0f, 0f)
        assertEquals(585f, 585f * m[0] + m[2], 0.001f)
        assertEquals(1040f, 1040f * m[4] + m[5], 0.001f)
    }
}
