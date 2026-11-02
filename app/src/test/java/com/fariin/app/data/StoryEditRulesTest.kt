package com.fariin.app.data

import com.fariin.app.data.StoryEditRules.Bg
import com.fariin.app.data.StoryEditRules.Font
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryEditRulesTest {
    @Test fun fontAndBadgeCycleInIosOrder() {
        assertEquals(Font.CLASSIC, Font.ROUNDED.next())
        assertEquals(Font.ROUNDED, Font.MONO.next())
        assertEquals(Bg.SEMI, Bg.PLAIN.next())
        assertEquals(Bg.SOLID, Bg.SEMI.next())
        assertEquals(Bg.PLAIN, Bg.SOLID.next())
    }

    @Test fun textColourBarEndsAreBlackAndWhite() {
        assertEquals(0xff000000.toInt(), StoryEditRules.textColor(0f))
        assertEquals(0xffffffff.toInt(), StoryEditRules.textColor(1f))
        assertEquals(0xffffffff.toInt(), StoryEditRules.textColor(2f))
        // Halfway along the second stop (0xff5500) to the third (0xffff00).
        assertEquals(0xffffaa00.toInt(), StoryEditRules.textColor(1.5f / 8f))
    }

    @Test fun badgeInkFollowsLuminance() {
        assertEquals(0xffffffff.toInt(), StoryEditRules.badgeInk(0xff0000ff.toInt()))
        assertEquals(0xff000000.toInt(), StoryEditRules.badgeInk(0xff00ff00.toInt()))
        assertEquals(0xff000000.toInt(), StoryEditRules.badgeInk(0xffffffff.toInt()))
        assertEquals(0xffffffff.toInt(), StoryEditRules.badgeInk(0xff000000.toInt()))
    }

    @Test fun penColourAndWidth() {
        assertEquals(0xffffffff.toInt(), StoryEditRules.penColor(0f))
        assertEquals(0xffff0000.toInt(), StoryEditRules.penColor(1f))
        assertEquals(0xff00ff00.toInt(), StoryEditRules.penColor(1f / 3f))
        assertEquals(10f, StoryEditRules.nextPenWidth(4f))
        assertEquals(16f, StoryEditRules.nextPenWidth(10f))
        assertEquals(4f, StoryEditRules.nextPenWidth(16f))
        assertEquals(12f, StoryEditRules.nextPenWidth(StoryEditRules.PEN_START_WIDTH))
        assertTrue(StoryEditRules.penStroke(6f, true) > StoryEditRules.penStroke(6f, false))
        assertEquals(13, StoryEditRules.PEN_TRACK_HUES.size)
    }

    @Test fun binNeedsTheFingerOnIt() {
        assertTrue(StoryEditRules.isOverTrash(100f, 100f, 100f, 130f, 52f, 12f))
        assertFalse(StoryEditRules.isOverTrash(100f, 50f, 100f, 130f, 52f, 12f))
    }

    @Test fun screenToSpaceScalesAndClamps() {
        val (x, y) = StoryEditRules.toSpace(10f + 195f, 100f + 50f, 10f, 100f, 390f, 600f)
        assertEquals(StoryEditRules.SPACE / 2, x, 0.01f)
        assertEquals(150f, y, 0.01f)
        val (cx, cy) = StoryEditRules.toSpace(-50f, 2000f, 10f, 100f, 390f, 600f)
        assertEquals(0f, cx, 0.01f)
        assertEquals(1800f, cy, 0.01f)
    }

    @Test fun hitTestFollowsScaleAndTurn() {
        // A 200 x 40 box at (500, 500).
        assertTrue(StoryEditRules.hitsBox(590f, 500f, 500f, 500f, 200f, 40f, 1f, 0f))
        assertFalse(StoryEditRules.hitsBox(500f, 590f, 500f, 500f, 200f, 40f, 1f, 0f))
        // Turned a quarter, the long side now runs up and down.
        assertTrue(StoryEditRules.hitsBox(500f, 590f, 500f, 500f, 200f, 40f, 1f, 90f))
        assertFalse(StoryEditRules.hitsBox(590f, 500f, 500f, 500f, 200f, 40f, 1f, 90f))
        // Twice the size reaches twice as far.
        assertTrue(StoryEditRules.hitsBox(690f, 500f, 500f, 500f, 200f, 40f, 2f, 0f))
    }

    @Test fun bakeOnlyWithSomethingPlaced() {
        assertFalse(StoryEditRules.needsBake(0, 0))
        assertTrue(StoryEditRules.needsBake(1, 0))
        assertTrue(StoryEditRules.needsBake(0, 2))
        assertEquals(StoryEditRules.MAX_SCALE, StoryEditRules.clampScale(100f))
    }
}
