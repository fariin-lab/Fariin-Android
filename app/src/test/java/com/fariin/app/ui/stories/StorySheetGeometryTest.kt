package com.fariin.app.ui.stories

import com.fariin.app.data.StoryViewer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** My story's card above the viewers sheet, against Kulan-iOS StoriesViews cardSlot + StoryRowGeometry. */
class StorySheetGeometryTest {
    // A 393x852 pt phone with a 59 pt status area, at 1 px per pt.
    private fun geo(f: Float) = StorySheetGeometry(393f, 852f, 59f, 1f, f)

    @Test fun slotIsTheCardBlockLeftAboveTheSheet() {
        val g = geo(1f)
        assertEquals(699f, g.contentH, 1e-3f)              // the 9:16 story on 393 wide
        assertEquals(852f * 0.60f, g.sheetH, 1e-3f)
        val avail = 852f - 852f * 0.60f - 59f
        assertEquals((avail - 40f) * 0.94f * 0.88f, g.slotH, 1e-3f)
        // Same shape as the story, so nothing is cropped off the picture.
        assertEquals(393f / 699f, g.slotW / g.slotH, 1e-4f)
        assertEquals(59f + (avail - 40f - g.slotH) / 2f, g.slotTop, 1e-3f)
    }

    @Test fun liveCardGoesFromFullScreenToTheSlot() {
        val closed = geo(0f).placement(0f)
        assertEquals(393f, closed.w, 1e-3f)
        assertEquals(699f, closed.h, 1e-3f)
        assertEquals(59f + 699f / 2f, closed.cy, 1e-3f)   // under the status bar, not centred
        assertEquals(0f, closed.corner, 1e-6f)
        val g = geo(1f)
        val open = g.placement(0f)
        assertEquals(g.slotW, open.w, 1e-3f)
        assertEquals(g.slotH, open.h, 1e-3f)
        assertEquals(g.centerY, open.cy, 1e-3f)
        assertEquals(196.5f, open.cx, 1e-3f)
        assertEquals(24f, open.corner, 1e-6f)
        assertEquals(0f, open.dim, 1e-6f)
        // Half way is half way in size and place: the finger moves one number.
        val half = geo(0.5f).placement(0f)
        assertEquals((393f + g.slotW) / 2f, half.w, 1e-3f)
        assertEquals((59f + 699f / 2f + g.centerY) / 2f, half.cy, 1e-3f)
    }

    @Test fun neighboursAre54NarrowerAnd12Apart() {
        val g = geo(1f)
        val side = g.placement(1f)
        assertEquals(g.slotW - 54f, side.w, 1e-3f)
        assertEquals(g.slotW / 2f + 12f + (g.slotW - 54f) / 2f, side.cx - 196.5f, 1e-3f)
        assertEquals(24f * (g.slotW - 54f) / g.slotW, side.corner, 1e-3f)
        // The next one out is one side card further.
        val next = g.placement(2f)
        assertEquals(side.cx + (g.slotW - 54f) + 12f, next.cx, 1e-3f)
        val left = g.placement(-1f)
        assertEquals(196.5f - (side.cx - 196.5f), left.cx, 1e-3f)
        // Dim and count fall over three cards.
        assertEquals(1f / 3f, side.dim, 1e-4f)
        assertEquals(2f / 3f, g.countAlpha(1f), 1e-4f)
        assertEquals(0f, g.countAlpha(3f), 1e-6f)
    }

    @Test fun neighboursStartOffScreenWhileTheSheetIsDown() {
        val g = geo(0f)
        assertTrue(g.placement(1f).cx - g.placement(1f).w / 2f >= 393f)
        assertTrue(g.placement(-1f).cx + g.placement(-1f).w / 2f <= 0f)
    }

    @Test fun releaseRuleIsThirtyPercentOrAFlick() {
        assertTrue(StorySheetGeometry.shouldClose(852f * 0.30f, 0f, 852f, 1f))
        assertFalse(StorySheetGeometry.shouldClose(852f * 0.29f, 149f, 852f, 1f))
        assertTrue(StorySheetGeometry.shouldClose(852f * 0.05f, 150f, 852f, 1f))
        assertFalse(StorySheetGeometry.shouldClose(852f * 0.04f, 900f, 852f, 1f))
        // Density scales the flick speed, not the distances.
        assertFalse(StorySheetGeometry.shouldClose(852f * 0.05f, 150f, 852f, 2.625f))
    }

    @Test fun rowLandsOnTheNearestCardToItsProjectedStop() {
        assertEquals(1, StorySheetGeometry.snapIndex(0.6f, 0f, 3))
        assertEquals(0, StorySheetGeometry.snapIndex(0.4f, 0f, 3))
        // A flick carries about half a second of its speed.
        assertEquals(1, StorySheetGeometry.snapIndex(0.1f, 1.5f, 3))
        assertEquals(2, StorySheetGeometry.snapIndex(1.9f, 10f, 3))
        assertEquals(0, StorySheetGeometry.snapIndex(0f, -10f, 3))
    }

    @Test fun listIsReactionsFirstThenNewestAndFilters() {
        val a = StoryViewer("a", "Adnan", null, 300)
        val b = StoryViewer("b", "kream", null, 200, reaction = "❤️")
        val c = StoryViewer("c", "Abdi", null, 400)
        assertEquals(listOf("b", "c", "a"), StorySheetRows.order(listOf(a, b, c), "", null).map { it.uid })
        assertEquals(listOf("c"), StorySheetRows.order(listOf(a, b, c), " ab ", null).map { it.uid })
        assertEquals(listOf("a"), StorySheetRows.order(listOf(a, b, c), "", setOf("a")).map { it.uid })
    }

    @Test fun viewedTimeReadsAgo() {
        val now = 10_000_000_000L
        assertEquals("just now", viewedAgo(now - 59_000, now))
        assertEquals("6m ago", viewedAgo(now - 6 * 60_000, now))
        assertEquals("11h ago", viewedAgo(now - 11 * 3_600_000L, now))
        assertEquals("2d ago", viewedAgo(now - 2 * 86_400_000L, now))
    }
}
