package com.fariin.app.ui.stories

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The story viewer's motion numbers against Kulan-iOS StoriesViews.swift (hero) and StoryPager.swift (cube). */
class StoryMotionTest {
    @Test fun wallIsSolidAtRestAndHeldAtItsFloorUnderTheFinger() {
        assertEquals(1f, StoryMotion.dim(0f, 1f), 1e-6f)
        assertEquals(0.58f, StoryMotion.dim(0.3f, 1f), 1e-6f)
        assertEquals(0.36f, StoryMotion.dim(1f, 1f), 1e-6f)
        // A landing lets the floor go, so the slot end reaches a true zero.
        assertEquals(0f, StoryMotion.dim(1f, 0f), 1e-6f)
    }

    @Test fun dragNeverShrinksBelowTheFloor() {
        // A 66dp card on a 411dp screen: the cap keeps the story at 46% of its width.
        val ratio = 66f / 411f
        val cap = StoryMotion.dragCeiling(ratio)
        assertEquals(0.46f, 1f + (ratio - 1f) * cap, 1e-4f)
        assertEquals(1f, StoryMotion.dragCeiling(0.6f), 0f)
    }

    @Test fun rubberBandIsOneToOneThenBounded() {
        assertEquals(100f, StoryMotion.dragTravel(100f, 200f, 28f), 0f)
        assertTrue(StoryMotion.dragTravel(10_000f, 200f, 28f) < 228f)
    }

    @Test fun coverTimings() {
        assertEquals(1f, StoryMotion.coverIn(0.85f), 1e-6f)
        assertEquals(1f, StoryMotion.coverOut(0.2f), 1e-6f)
        assertEquals(0f, StoryMotion.coverOut(0.55f), 1e-6f)
        assertEquals(1f, StoryMotion.avatarCropIn(0.4f), 1e-6f)
        assertEquals(0f, StoryMotion.avatarCoverIn(0.1f), 1e-6f)
        assertEquals(1f, StoryMotion.avatarCoverIn(0.55f), 1e-6f)
        assertEquals(0f, StoryMotion.ringIn(0.75f), 1e-6f)
        assertEquals(1f, StoryMotion.ringOut(0f), 1e-6f)
    }

    @Test fun chromeLeavesAndArrivesWithTheCard() {
        assertEquals(1f, StoryMotion.chrome(0f), 1e-6f)
        assertEquals(0f, StoryMotion.chrome(0.3f), 1e-6f)
        assertEquals(0.5f, StoryMotion.chrome(0.15f), 1e-6f)
    }

    @Test fun cubeFaceIsFlatAtRestAndMirrored() {
        val rest = StoryMotion.face(0f, 411f)
        assertEquals(0f, rest.rotationDeg, 1e-4f)
        assertEquals(0f, rest.shiftPt, 1e-3f)
        assertEquals(1f, rest.scale, 1e-6f)
        val r = StoryMotion.face(0.5f, 411f)
        val l = StoryMotion.face(-0.5f, 411f)
        assertEquals(r.rotationDeg, -l.rotationDeg, 1e-4f)
        assertEquals(r.shiftPt, -l.shiftPt, 1e-3f)
        // A whole turn is about 60 degrees on a phone, not 90 (the 40pt side distance).
        val edge = StoryMotion.face(1f, 393f)
        assertTrue(abs(edge.rotationDeg) in 55f..65f)
    }
}
