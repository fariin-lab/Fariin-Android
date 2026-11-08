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
}
