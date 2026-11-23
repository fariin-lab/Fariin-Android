package com.fariin.app.data

import com.fariin.app.call.CallGlareRules
import com.fariin.app.call.CallGlareRules.Verdict
import com.fariin.app.call.CallPhase
import org.junit.Assert.assertEquals
import org.junit.Test

class CallGlareRulesTest {
    private fun d(phase: CallPhase, caller: String = "b", peer: String = "b", me: String = "a", newer: Boolean = true,
                  created: Boolean = true, accepted: Boolean = false, group: Boolean = false) =
        CallGlareRules.decide(phase, accepted, group, me, peer, caller, newer, created)

    @Test fun idleRings() = assertEquals(Verdict.RING, d(CallPhase.IDLE, peer = ""))
    @Test fun endedTailRings() = assertEquals(Verdict.RING, d(CallPhase.ENDED, peer = "x"))
    @Test fun groupCallIsBusy() = assertEquals(Verdict.BUSY, d(CallPhase.IDLE, peer = "", group = true))
    @Test fun otherPersonIsBusy() = assertEquals(Verdict.BUSY, d(CallPhase.ACTIVE, caller = "c"))
    @Test fun lowerUidWins() = assertEquals(Verdict.GLARE_WIN, d(CallPhase.OUTGOING, me = "a", caller = "b", peer = "b"))
    @Test fun higherUidYields() = assertEquals(Verdict.GLARE_YIELD, d(CallPhase.OUTGOING, me = "c", caller = "b", peer = "b"))
    @Test fun dialNotOutYields() = assertEquals(Verdict.GLARE_YIELD, d(CallPhase.OUTGOING, me = "a", created = false))
    @Test fun recallWhileConnected() = assertEquals(Verdict.REPLACE, d(CallPhase.ACTIVE))
    @Test fun recallWhileReconnecting() = assertEquals(Verdict.REPLACE, d(CallPhase.RECONNECTING))
    @Test fun redialWhileRinging() = assertEquals(Verdict.REPLACE, d(CallPhase.INCOMING))
    @Test fun answeredRingIsNotReplaced() = assertEquals(Verdict.BUSY, d(CallPhase.INCOMING, accepted = true))
    @Test fun olderLeftoverEnds() = assertEquals(Verdict.END_OLD, d(CallPhase.ACTIVE, newer = false))
}
