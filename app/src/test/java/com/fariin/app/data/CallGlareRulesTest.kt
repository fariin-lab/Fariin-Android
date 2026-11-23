package com.fariin.app.data

import com.fariin.app.call.CallGlareRules
import com.fariin.app.call.CallGlareRules.Verdict
import com.fariin.app.call.CallPhase
import org.junit.Assert.assertEquals
import org.junit.Test

class CallGlareRulesTest {
    private fun d(phase: CallPhase, caller: String = "b", peer: String = "b", me: String = "a", newer: Boolean = true,
                  created: Boolean = true, accepted: Boolean = false, group: Boolean = false, started: Boolean = created) =
        CallGlareRules.decide(phase, accepted, group, me, peer, caller, newer, created, started)

    @Test fun idleRings() = assertEquals(Verdict.RING, d(CallPhase.IDLE, peer = ""))
    @Test fun endedTailRings() = assertEquals(Verdict.RING, d(CallPhase.ENDED, peer = "x"))
    @Test fun groupCallIsBusy() = assertEquals(Verdict.BUSY, d(CallPhase.IDLE, peer = "", group = true))
    @Test fun otherPersonIsBusy() = assertEquals(Verdict.BUSY, d(CallPhase.ACTIVE, caller = "c"))
    @Test fun lowerUidWins() = assertEquals(Verdict.GLARE_WIN, d(CallPhase.OUTGOING, me = "a", caller = "b", peer = "b"))
    @Test fun higherUidYields() = assertEquals(Verdict.GLARE_YIELD, d(CallPhase.OUTGOING, me = "c", caller = "b", peer = "b"))
    @Test fun dialNotOutYields() = assertEquals(Verdict.GLARE_YIELD, d(CallPhase.OUTGOING, me = "a", created = false, started = false))
    @Test fun createInFlightHolds() = assertEquals(Verdict.GLARE_WIN_HOLD, d(CallPhase.OUTGOING, me = "a", created = false, started = true))

    /** Both dials still before their docs exist: exactly one phone stands down, so one call lives. */
    @Test fun bothEarlyOneSurvives() {
        val low = d(CallPhase.OUTGOING, me = "a", caller = "b", peer = "b", created = false, started = true)
        val high = d(CallPhase.OUTGOING, me = "b", caller = "a", peer = "a", created = false, started = true)
        assertEquals(Verdict.GLARE_WIN_HOLD, low)
        assertEquals(Verdict.GLARE_YIELD, high)
    }

    /** One side's create not out yet: it yields, the other's call is the one that rings. */
    @Test fun lowNotOutHighOutStillOneSurvives() {
        // The lower phone has not sent its create, so the higher one never sees it; the lower one yields to the higher's call.
        assertEquals(Verdict.GLARE_YIELD, d(CallPhase.OUTGOING, me = "a", caller = "b", peer = "b", created = false, started = false))
    }

    @Test fun higherYieldsEvenWhenCreated() = assertEquals(Verdict.GLARE_YIELD, d(CallPhase.OUTGOING, me = "b", caller = "a", peer = "a"))
    @Test fun recallWhileConnected() = assertEquals(Verdict.REPLACE, d(CallPhase.ACTIVE))
    @Test fun recallWhileReconnecting() = assertEquals(Verdict.REPLACE, d(CallPhase.RECONNECTING))
    @Test fun redialWhileRinging() = assertEquals(Verdict.REPLACE, d(CallPhase.INCOMING))
    @Test fun answeredRingIsNotReplaced() = assertEquals(Verdict.BUSY, d(CallPhase.INCOMING, accepted = true))
    @Test fun olderLeftoverEnds() = assertEquals(Verdict.END_OLD, d(CallPhase.ACTIVE, newer = false))
}
