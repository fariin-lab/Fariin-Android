package com.fariin.app.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupStageTest {
    /** iOS GroupCallLayoutEngine.debugSelfCheck, the 390 x 700 portrait stage. */
    @Test fun portraitGridMatchesIos() {
        // (count, columns, rows, overflow, last shown tile w x h)
        val want = listOf(
            listOf(1, 1, 1, 0, 378f, 688f), listOf(2, 1, 2, 0, 378f, 341f), listOf(3, 2, 2, 0, 186f, 341f),
            listOf(4, 2, 2, 0, 186f, 341f), listOf(5, 2, 3, 0, 186f, 676f / 3), listOf(6, 2, 3, 0, 186f, 676f / 3),
            listOf(7, 2, 3, 1, 186f, 676f / 3), listOf(8, 2, 3, 2, 186f, 676f / 3),
        )
        assertEquals(6, GroupCallLayout.capacity(390f, 700f))
        val empty = GroupCallLayout.grid(emptyList(), 390f, 700f)
        assertTrue(empty.frames.isEmpty() && empty.overflow.isEmpty())
        for (w in want) {
            val n = w[0] as Int
            val ids = (0 until n).map { "p$it" }
            val l = GroupCallLayout.grid(ids, 390f, 700f)
            assertEquals("n=$n cols", w[1], l.columns)
            assertEquals("n=$n rows", w[2], l.rows)
            assertEquals("n=$n overflow", w[3], l.overflow.size)
            val last = l.frames.getValue(ids[n - (w[3] as Int) - 1])
            assertEquals("n=$n w", w[4] as Float, last.w, 0.5f)
            assertEquals("n=$n h", w[5] as Float, last.h, 0.5f)
            val first = l.frames.getValue("p0")
            assertEquals(6f, first.y, 0.01f)
        }
    }

    /** Every tile the same width; a short last row sits centred (owner 2026-10-28 on iOS). */
    @Test fun shortLastRowIsCentred() {
        val l = GroupCallLayout.grid(listOf("a", "b", "c"), 390f, 700f)
        val c = l.frames.getValue("c")
        assertEquals(186f, c.w, 0.5f)
        assertEquals((390f - 186f) / 2, c.x, 0.5f)
    }

    @Test fun landscapeUsesOrphanPenalty() {
        val four = GroupCallLayout.grid((0 until 4).map { "p$it" }, 852f, 230f)
        assertEquals(2, four.columns); assertEquals(2, four.rows)
        assertEquals(417f, four.frames.getValue("p3").w, 0.5f)
        val five = GroupCallLayout.grid((0 until 5).map { "p$it" }, 852f, 230f)
        assertEquals(3, five.columns); assertEquals(2, five.rows)
    }

    @Test fun speakerQualifiesHoldsAndReleases() {
        val t = SpeakerTracker()
        assertFalse(t.update(setOf("a"), 0))
        assertNull(t.activeSpeakerId)
        assertTrue(t.update(setOf("a"), 300))
        assertEquals("a", t.activeSpeakerId)
        // b talks over a: a keeps it while talking.
        t.update(setOf("a", "b"), 400); t.update(setOf("a", "b"), 800)
        assertEquals("a", t.activeSpeakerId)
        // A breath under 0.6 s is not the end of a's turn.
        t.update(setOf("b"), 1000)
        assertEquals("a", t.activeSpeakerId)
        // a silent 0.6 s: b, already qualified, takes over at once.
        t.update(setOf("b"), 1400)
        assertEquals("b", t.activeSpeakerId)
        // Everyone quiet: held 1.5 s from when b was last heard (1400), then nobody.
        t.update(emptySet(), 1500)
        assertEquals("b", t.activeSpeakerId)
        t.update(emptySet(), 2800)
        assertEquals("b", t.activeSpeakerId)
        t.update(emptySet(), 2900)
        assertNull(t.activeSpeakerId)

    }

    @Test fun speakerCoughNeverMoves() {
        val t = SpeakerTracker()
        t.update(setOf("a"), 0); t.update(emptySet(), 200); t.update(setOf("a"), 250)
        t.update(setOf("a"), 500)
        assertNull(t.activeSpeakerId)   // the clock restarted at 250
        t.update(setOf("a"), 550)
        assertEquals("a", t.activeSpeakerId)
    }

    @Test fun speakerTieGoesToEarliestThenId() {
        val t = SpeakerTracker()
        t.update(setOf("b", "a"), 0)
        t.update(setOf("b", "a"), 300)
        assertEquals("a", t.activeSpeakerId)
    }

    @Test fun words() {
        assertEquals("Ana", GroupCallWords.names(listOf("Ana")))
        assertEquals("Ana and Bilan", GroupCallWords.names(listOf("Ana", "Bilan")))
        assertEquals("Ana, Bilan and 1 other", GroupCallWords.names(listOf("Ana", "Bilan", "C")))
        assertEquals("Ana, Bilan and 3 others", GroupCallWords.names(listOf("Ana", "Bilan", "C", "D", "E")))
        assertEquals("No one else is here", GroupCallWords.whoIsHere(emptyList(), 0))
        assertEquals("1 person is here", GroupCallWords.whoIsHere(emptyList(), 1))
        assertEquals("4 people are here", GroupCallWords.whoIsHere(emptyList(), 4))
        assertEquals("Ana is in this call", GroupCallWords.whoIsHere(listOf("Ana"), 1))
        assertEquals("Ana and 1 other are in this call", GroupCallWords.whoIsHere(listOf("Ana"), 2))
        assertEquals("Ana and Bo are in this call", GroupCallWords.whoIsHere(listOf("Ana", "Bo"), 2))
        assertEquals("Ana, Bo and 3 others are in this call", GroupCallWords.whoIsHere(listOf("Ana", "Bo", "Cy"), 5))
        assertEquals("Ringing everyone…", GroupCallWords.subtitle(false, false, false, true, true, 1))
        assertEquals("No one else is here", GroupCallWords.subtitle(false, false, false, true, false, 1))
        assertEquals("3 in call", GroupCallWords.subtitle(false, false, false, true, false, 3))
        assertEquals("Waiting to be let in", GroupCallWords.subtitle(true, true, false, false, false, 0))
        assertEquals("Reconnecting…", GroupCallWords.subtitle(true, true, true, true, true, 3))
        assertEquals("Video Chat · 2 participants", GroupCallWords.rowSubtitle(true, 2))
        assertEquals("Voice Chat", GroupCallWords.rowSubtitle(false, 0))
        assertEquals("Live now", GroupCallWords.barSubtitle(0))
        assertEquals("2 waiting to join", GroupCallWords.waitingTitle(2))
        assertEquals("Host", CallRole.of("owner").badge)
        assertNull(CallRole.of(null).badge)
    }

    @Test fun faceSpots() {
        assertEquals(1, FaceCluster.spots(1).size)
        assertEquals(150f, FaceCluster.spots(1)[0].size)
        assertEquals(4, FaceCluster.spots(9).size)
        assertEquals(0, FaceCluster.spots(0).size)
    }
}

class GroupStagePlanTest {
    @Test fun meFirstOnlyWithTwoOrMoreOthers() {
        assertEquals(listOf("a"), GroupStagePlan.gridIds("me", listOf("a"), null, 6))
        assertEquals(listOf("me", "a", "b"), GroupStagePlan.gridIds("me", listOf("a", "b"), null, 6))
    }

    @Test fun speakerOffTheGridTakesTheLastCell() {
        val r = (1..8).map { "p$it" }
        // 6 cells: me + p1..p5 on the grid, p6..p8 in the strip; p7 speaks.
        val ids = GroupStagePlan.gridIds("me", r, "p7", 6)
        assertEquals(listOf("me", "p1", "p2", "p3", "p4", "p7"), ids.take(6))
        assertEquals(listOf("p5", "p6", "p8"), ids.drop(6))
        // A speaker already on the grid changes nothing.
        assertEquals(listOf("me") + r, GroupStagePlan.gridIds("me", r, "p2", 6))
    }

    @Test fun selfCardSizes() {
        assertEquals(90f to 160f, GroupStagePlan.selfSize(false, 390f, 700f))
        val (w, h) = GroupStagePlan.selfSize(true, 390f, 700f)
        assertEquals(170f, w, 0.01f); assertEquals(300f, h, 0.01f)
        assertEquals(0.45f * 300f, GroupStagePlan.selfSize(true, 300f, 700f).first, 0.01f)
    }

    @Test fun avatarSizes() {
        assertEquals(112f, GroupStagePlan.avatarSize(378f, 688f))
        assertEquals(96f, GroupStagePlan.avatarSize(150f, 200f))
        assertEquals(60f, GroupStagePlan.avatarSize(96f, 96f))
        assertEquals(56f, GroupStagePlan.avatarSize(417f, 100f), 0.01f)
    }
}
