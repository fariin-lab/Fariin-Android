package com.fariin.app.data

import com.fariin.app.data.WeakLinkPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeakLinkPolicyTest {
    private val t0 = 1_000_000L
    private fun s(sec: Int) = t0 + sec * 1000L

    @Test fun pausesOnlyAfterFiveSecondsBelowFloor() {
        val p = WeakLinkPolicy()
        assertEquals(Decision.NONE, p.evaluate(30_000.0, paused = false, nowMs = s(0)))
        assertEquals(Decision.NONE, p.evaluate(30_000.0, paused = false, nowMs = s(2)))
        assertEquals(Decision.NONE, p.evaluate(30_000.0, paused = false, nowMs = s(4)))
        assertEquals(Decision.PAUSE, p.evaluate(30_000.0, paused = false, nowMs = s(5)))
    }

    @Test fun floorIsFiftyKbps() {
        val p = WeakLinkPolicy()
        p.evaluate(50_000.0, paused = false, nowMs = s(0))
        assertEquals(Decision.NONE, p.evaluate(50_000.0, paused = false, nowMs = s(20)))
        p.evaluate(49_999.0, paused = false, nowMs = s(21))
        assertEquals(Decision.PAUSE, p.evaluate(49_999.0, paused = false, nowMs = s(26)))
    }

    @Test fun oneGoodSampleRestartsThePoorWindow() {
        val p = WeakLinkPolicy()
        p.evaluate(20_000.0, paused = false, nowMs = s(0))
        p.evaluate(20_000.0, paused = false, nowMs = s(4))
        p.evaluate(200_000.0, paused = false, nowMs = s(5))
        assertEquals(Decision.NONE, p.evaluate(20_000.0, paused = false, nowMs = s(6)))
        assertEquals(Decision.NONE, p.evaluate(20_000.0, paused = false, nowMs = s(10)))
        assertEquals(Decision.PAUSE, p.evaluate(20_000.0, paused = false, nowMs = s(11)))
    }

    @Test fun resumesOnlyAfterTenSecondsGood() {
        val p = WeakLinkPolicy()
        assertEquals(Decision.NONE, p.evaluate(300_000.0, paused = true, nowMs = s(0)))
        assertEquals(Decision.NONE, p.evaluate(300_000.0, paused = true, nowMs = s(9)))
        assertEquals(Decision.RESUME, p.evaluate(300_000.0, paused = true, nowMs = s(10)))
    }

    @Test fun oneBadSampleRestartsTheGoodWindow() {
        val p = WeakLinkPolicy()
        p.evaluate(300_000.0, paused = true, nowMs = s(0))
        p.evaluate(300_000.0, paused = true, nowMs = s(8))
        p.evaluate(10_000.0, paused = true, nowMs = s(9))
        assertEquals(Decision.NONE, p.evaluate(300_000.0, paused = true, nowMs = s(10)))
        assertEquals(Decision.NONE, p.evaluate(300_000.0, paused = true, nowMs = s(19)))
        assertEquals(Decision.RESUME, p.evaluate(300_000.0, paused = true, nowMs = s(20)))
    }

    @Test fun neverAsksForWhatIsDone() {
        val p = WeakLinkPolicy()
        p.evaluate(10_000.0, paused = true, nowMs = s(0))
        assertEquals(Decision.NONE, p.evaluate(10_000.0, paused = true, nowMs = s(30)))
        val q = WeakLinkPolicy()
        q.evaluate(300_000.0, paused = false, nowMs = s(0))
        assertEquals(Decision.NONE, q.evaluate(300_000.0, paused = false, nowMs = s(30)))
    }

    @Test fun noEstimateDecidesNothingAndClearsWindows() {
        val p = WeakLinkPolicy()
        p.evaluate(10_000.0, paused = false, nowMs = s(0))
        assertEquals(Decision.NONE, p.evaluate(null, paused = false, nowMs = s(4)))
        assertEquals(Decision.NONE, p.evaluate(0.0, paused = false, nowMs = s(5)))
        assertEquals(null, p.poorSinceMs)
        assertEquals(Decision.NONE, p.evaluate(10_000.0, paused = false, nowMs = s(6)))
        assertEquals(Decision.PAUSE, p.evaluate(10_000.0, paused = false, nowMs = s(11)))
    }

    @Test fun resetStartsTheClockAgain() {
        val p = WeakLinkPolicy()
        p.evaluate(10_000.0, paused = false, nowMs = s(0))
        p.reset()
        assertEquals(Decision.NONE, p.evaluate(10_000.0, paused = false, nowMs = s(5)))
        assertEquals(Decision.PAUSE, p.evaluate(10_000.0, paused = false, nowMs = s(10)))
    }

    @Test fun movingWindows() {
        assertTrue(WeakLinkPolicy.moving(true, null, null, s(0)))
        assertTrue(WeakLinkPolicy.moving(false, s(0), null, s(9)))
        assertFalse(WeakLinkPolicy.moving(false, s(0), null, s(10)))
        assertTrue(WeakLinkPolicy.moving(false, null, s(0), s(7)))
        assertFalse(WeakLinkPolicy.moving(false, null, s(0), s(8)))
        assertFalse(WeakLinkPolicy.moving(false, null, null, s(0)))
    }

    @Test fun staleOnlyAfterARealMove() {
        assertTrue(WeakLinkPolicy.staleAfterMove(s(0), s(3)))
        assertFalse(WeakLinkPolicy.staleAfterMove(s(3), s(0)))
        assertFalse(WeakLinkPolicy.staleAfterMove(null, s(3)))
        assertFalse(WeakLinkPolicy.staleAfterMove(s(0), null))
    }
}
