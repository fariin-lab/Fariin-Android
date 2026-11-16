package com.fariin.app.perf

import com.fariin.app.channel.ChannelFollows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenRulesTest {
    // ---- Throttle (channel document answers gathered before one publish)

    @Test fun firstChangePublishesAtOnce() = assertEquals(0L, Throttle(250).offer(1_000))

    @Test fun changesInsideTheWindowWaitForItsEnd() {
        val t = Throttle(250)
        assertEquals(0L, t.offer(1_000)); t.ran(1_000)
        assertEquals(150L, t.offer(1_100))
        // Already on its way: carried by that publish, nothing new scheduled.
        assertEquals(-1L, t.offer(1_150))
        assertEquals(-1L, t.offer(1_240))
        t.ran(1_250)
        assertEquals(250L, t.offer(1_250))
    }

    @Test fun aQuietSpellPublishesAtOnceAgain() {
        val t = Throttle(250)
        t.offer(0); t.ran(0)
        assertEquals(0L, t.offer(5_000))
    }

    @Test fun burstOf300AnswersGivesFewPublishes() {
        val t = Throttle(250)
        var publishes = 0
        var due = -1L
        for (ms in 0L until 1_000L step 3) {   // 334 answers in one second
            if (due in 0..ms) { t.ran(ms); publishes++; due = -1 }
            val w = t.offer(ms)
            if (w == 0L) { t.ran(ms); publishes++ } else if (w > 0) due = ms + w
        }
        assertTrue("publishes=$publishes", publishes in 4..5)
    }

    @Test fun resetForgetsTheWindow() {
        val t = Throttle(250)
        t.offer(0); t.ran(0); t.offer(10)
        t.reset()
        assertEquals(0L, t.offer(20))
    }

    // ---- Live channel documents

    private fun e(at: Double, pinned: Boolean = false) = ChannelFollows.Entry(read = 0, muted = false, pinned = pinned, at = at)

    @Test fun capIs50OnLowAnd300Elsewhere() {
        assertEquals(50, ChannelFollows.liveCap(PerfClass.LOW))
        assertEquals(300, ChannelFollows.liveCap(PerfClass.AVERAGE))
        assertEquals(300, ChannelFollows.liveCap(PerfClass.HIGH))
    }

    @Test fun keepsPinnedThenMostRecentlyActive() {
        val entries = mapOf("old" to e(1.0), "pin" to e(0.0, pinned = true), "followedLate" to e(50.0), "busy" to e(2.0))
        val active = mapOf("busy" to 100.0, "old" to 3.0)
        assertEquals(setOf("pin", "busy"), ChannelFollows.pickLive(entries, active, 2))
        assertEquals(setOf("pin", "busy", "followedLate"), ChannelFollows.pickLive(entries, active, 3))
    }

    @Test fun underTheCapEveryChannelIsLive() {
        val entries = (1..40).associate { "c$it" to e(it.toDouble()) }
        assertEquals(entries.keys, ChannelFollows.pickLive(entries, emptyMap(), ChannelFollows.liveCap(PerfClass.LOW)))
    }

    @Test fun lowPhoneKeepsThe50Newest() {
        val entries = (1..120).associate { "c$it" to e(it.toDouble()) }
        val live = ChannelFollows.pickLive(entries, emptyMap(), ChannelFollows.liveCap(PerfClass.LOW))
        assertEquals(50, live.size)
        assertTrue("c120" in live && "c71" in live)
        assertFalse("c70" in live)
    }

    // ---- Motion on a LOW phone

    @Test fun onlyLowGetsReducedMotion() {
        assertTrue(PowerRules.reducedMotion(PerfClass.LOW))
        assertFalse(PowerRules.reducedMotion(PerfClass.AVERAGE))
        assertFalse(PowerRules.reducedMotion(PerfClass.HIGH))
    }

    @Test fun tweensShorterOnLowOnly() {
        assertEquals(320, PowerRules.motionMs(320, PerfClass.AVERAGE))
        assertEquals(320, PowerRules.motionMs(320, PerfClass.HIGH))
        assertEquals(192, PowerRules.motionMs(320, PerfClass.LOW))
        // Never cut below 90 ms; a short one stays as it is.
        assertEquals(90, PowerRules.motionMs(120, PerfClass.LOW))
        assertEquals(60, PowerRules.motionMs(60, PerfClass.LOW))
    }
}
