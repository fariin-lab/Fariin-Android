package com.fariin.app.ui.calls

import com.fariin.app.call.CallPhase
import com.fariin.app.data.CallEntry
import com.fariin.app.data.CallRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallsLiveTest {
    private val me = "me"
    private fun call(phase: CallPhase, caller: Boolean = true, video: Boolean = false) =
        CallsLive.Call(phase, caller, video, "bob", "Bob", null)
    private fun stored(id: String, outcome: String, other: String = "bob", group: String? = null) =
        CallEntry(id, "bob_me", "Bob", null, other, me, outcome, false, 0, 1_000L, group)

    @Test fun onlyAnUpCallHasALiveRow() {
        assertNotNull(CallsLive.entry(call(CallPhase.OUTGOING), me, 5L))
        assertNotNull(CallsLive.entry(call(CallPhase.ACTIVE, caller = false), me, 5L))
        assertNotNull(CallsLive.entry(call(CallPhase.RECONNECTING), me, 5L))
        // Still ringing here, ended, idle: no row (iOS liveEntry).
        assertNull(CallsLive.entry(call(CallPhase.INCOMING, caller = false), me, 5L))
        assertNull(CallsLive.entry(call(CallPhase.ENDED), me, 5L))
        assertNull(CallsLive.entry(call(CallPhase.IDLE), me, 5L))
        assertNull(CallsLive.entry(null, me, 5L))
        assertNull(CallsLive.entry(CallsLive.Call(CallPhase.ACTIVE, true, false, "", "", null), me, 5L))
    }

    @Test fun liveRowReadsAsTheCall() {
        val out = CallsLive.entry(call(CallPhase.OUTGOING, video = true), me, 5L)!!
        assertEquals(CallsLive.ID, out.id)
        assertEquals("bob_me", out.cid)
        assertTrue(out.mine(me))
        assertTrue(out.video)
        assertEquals("ringing", out.outcome)
        val inc = CallsLive.entry(call(CallPhase.ACTIVE, caller = false), me, 5L)!!
        assertFalse(inc.mine(me))
        assertEquals("ongoing", inc.outcome)
    }

    @Test fun theStoredCopyStepsAsideOnlyForTheLivePeer() {
        val live = CallsLive.entry(call(CallPhase.ACTIVE), me, 5L)
        val list = listOf(stored("call_1", "ongoing"), stored("m2", "answered"), stored("call_3", "ringing", other = "ann"),
            stored("g", "ongoing", other = "", group = "grp"))
        assertEquals(listOf("m2", "call_3", "g"), CallsLive.withoutLive(list, live).map { it.id })
        assertEquals(list, CallsLive.withoutLive(list, null))
    }

    @Test fun returnIsOnTheLiveRunAloneAndFirst() {
        val live = CallsLive.entry(call(CallPhase.ACTIVE), me, 5L)
        val older = listOf(CallRun(listOf(stored("m2", "answered"))))
        val runs = CallsLive.runs(older, live, missedOnly = false)
        assertEquals(2, runs.size)
        assertTrue(CallsLive.showsReturn(runs[0]))
        assertFalse(CallsLive.showsReturn(runs[1]))
        // Never under Missed.
        assertEquals(older, CallsLive.runs(older, live, missedOnly = true))
        assertEquals(older, CallsLive.runs(older, null, missedOnly = false))
    }
}
