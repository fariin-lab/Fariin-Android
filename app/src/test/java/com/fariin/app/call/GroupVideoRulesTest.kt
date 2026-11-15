package com.fariin.app.call

import com.fariin.app.perf.PerfClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupVideoRulesTest {
    private val six = listOf("a", "b", "c", "d", "e", "f")

    @Test fun underTheCapEveryoneIsLive() {
        assertEquals(setOf("a", "b", "c"), GroupVideoRules.liveVideo(listOf("a", "b", "c"), null, null, emptyMap(), 4))
    }

    @Test fun nobodySpokeYetTakesTheFirstToCome() {
        assertEquals(setOf("a", "b", "c", "d"), GroupVideoRules.liveVideo(six, null, null, emptyMap(), 4))
    }

    @Test fun speakerThenLatestSpeakers() {
        val spoke = mapOf("f" to 100L, "e" to 300L, "d" to 200L, "c" to 50L, "b" to 10L)
        // Speaker a, then e (300), d (200), f (100); c and b fall out.
        assertEquals(setOf("a", "e", "d", "f"), GroupVideoRules.liveVideo(six, null, "a", spoke, 4))
    }

    @Test fun pinnedAlwaysLive() {
        val spoke = mapOf("a" to 4L, "b" to 3L, "c" to 2L, "d" to 1L)
        val live = GroupVideoRules.liveVideo(six, "f", "a", spoke, 4)
        assertTrue("f" in live && "a" in live)
        assertEquals(4, live.size)
        assertFalse("d" in live)
    }

    @Test fun speakerWithCameraOffIsSkipped() {
        assertEquals(setOf("a", "b", "c", "d"), GroupVideoRules.liveVideo(six, "x", "y", emptyMap(), 4))
    }

    @Test fun capSize() {
        assertEquals(320 to 180, GroupVideoRules.capSize(1280, 720, 320))
        assertEquals(180 to 320, GroupVideoRules.capSize(540, 960, 320))
        assertEquals(200 to 100, GroupVideoRules.capSize(200, 100, 320))
        assertEquals(0 to 0, GroupVideoRules.capSize(0, 0, 320))
    }

    @Test fun cameraByClass() {
        val low = GroupVideoRules.camera(PerfClass.LOW)!!
        assertEquals(480, low.height); assertEquals(15, low.fps); assertTrue(low.twoLayers)
        val avg = GroupVideoRules.camera(PerfClass.AVERAGE)!!
        assertEquals(540, avg.height); assertEquals(24, avg.fps); assertFalse(avg.twoLayers)
        assertNull(GroupVideoRules.camera(PerfClass.HIGH))
    }

    @Test fun poorForTenSecondsPauses() {
        val g = ReceiveVideoGate()
        assertFalse(g.update(0, poor = true, hot = false))
        assertFalse(g.update(9_000, poor = true, hot = false))
        assertTrue(g.update(10_000, poor = true, hot = false))
        assertTrue(g.paused)
    }

    @Test fun shortPoorSpellsDoNotPause() {
        val g = ReceiveVideoGate()
        g.update(0, true, false); g.update(8_000, true, false)
        g.update(9_000, false, false)
        g.update(10_000, true, false)
        assertFalse(g.update(19_000, true, false))
        assertFalse(g.paused)
    }

    @Test fun hotPausesAtOnce() {
        val g = ReceiveVideoGate()
        assertTrue(g.update(0, poor = false, hot = true))
    }

    @Test fun turnBackOnHoldsUntilTheCauseClears() {
        val g = ReceiveVideoGate()
        g.update(0, false, true)
        g.resume()
        assertFalse(g.paused)
        // Still hot: stays on, as asked.
        assertFalse(g.update(5_000, false, true))
        // Cooled down, then hot again: pauses again.
        g.update(6_000, false, false)
        assertTrue(g.update(7_000, false, true))
    }

    @Test fun liftsByItselfAfterThirtyClearSeconds() {
        val g = ReceiveVideoGate()
        g.update(0, false, true)
        g.update(1_000, false, false)
        assertFalse(g.update(30_000, false, false))
        assertTrue(g.update(31_000, false, false))
        assertFalse(g.paused)
    }

    @Test fun resetClears() {
        val g = ReceiveVideoGate()
        g.update(0, false, true); g.reset()
        assertFalse(g.paused)
    }
}
