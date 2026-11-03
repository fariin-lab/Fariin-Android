package com.fariin.app.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The call state machine and the words the screen shows, after iOS CallStageWords / CallView. */
class CallMachineTest {
    private fun run(vararg e: CallEvent) = e.fold(CallStatus()) { s, ev -> CallMachine.reduce(s, ev) }

    @Test fun callerHappyPath() {
        var s = run(CallEvent.Dial(video = false))
        assertEquals(CallPhase.OUTGOING, s.phase); assertEquals("Calling…", CallMachine.label(s, 0))
        s = CallMachine.reduce(s, CallEvent.DocCreated)
        s = CallMachine.reduce(s, CallEvent.RemoteRinging)
        assertEquals("Ringing…", CallMachine.label(s, 0))
        s = CallMachine.reduce(s, CallEvent.RemoteAccepted)
        assertEquals(CallPhase.ACTIVE, s.phase); assertEquals("Connecting…", CallMachine.label(s, 0))
        s = CallMachine.reduce(s, CallEvent.MediaUp(1_000))
        assertEquals("1:23", CallMachine.label(s, 84_000))
        s = CallMachine.reduce(s, CallEvent.MediaLost)
        assertEquals("Reconnecting…", CallMachine.label(s, 0))
        s = CallMachine.reduce(s, CallEvent.MediaBack)
        assertEquals(CallPhase.ACTIVE, s.phase); assertEquals(1_000L, s.connectedAtMs)
        s = CallMachine.reduce(s, CallEvent.LocalEnd(EndReason.NONE, byUser = true))
        assertEquals(CallPhase.ENDED, s.phase); assertEquals(EndReason.HANGUP, s.endReason); assertEquals("Call ended", CallMachine.label(s, 0))
    }

    @Test fun mediaBeforeAcceptDoesNotStartTheClock() {
        val s = run(CallEvent.Dial(false), CallEvent.DocCreated, CallEvent.MediaUp(5))
        assertEquals(CallPhase.OUTGOING, s.phase); assertEquals(null, s.connectedAtMs)
    }

    @Test fun calleeDeclineAndAccept() {
        val ring = run(CallEvent.Ring(video = true))
        assertEquals(CallPhase.INCOMING, ring.phase); assertFalse(ring.isCaller); assertTrue(ring.callDocCreated)
        assertEquals("Fariin video call", CallMachine.label(ring, 0))
        val declined = CallMachine.reduce(ring, CallEvent.LocalEnd(EndReason.DECLINED, byUser = true))
        assertEquals(EndReason.DECLINED, declined.endReason)
        val accepted = CallMachine.reduce(ring, CallEvent.Accept)
        assertEquals(CallPhase.ACTIVE, accepted.phase); assertTrue(accepted.accepted)
        assertEquals("Connecting…", CallMachine.label(accepted, 0))
    }

    @Test fun callerEndedWords() {
        val rang = run(CallEvent.Dial(false), CallEvent.DocCreated, CallEvent.RemoteRinging, CallEvent.RemoteEnded(EndReason.DECLINED))
        assertEquals("No answer", CallMachine.label(rang, 0))   // a decline is never named (iOS)
        val never = run(CallEvent.Dial(false), CallEvent.DocCreated, CallEvent.RemoteEnded(EndReason.MISSED))
        assertEquals("Couldn't reach them", CallMachine.label(never, 0))
        val busy = run(CallEvent.Dial(false), CallEvent.DocCreated, CallEvent.RemoteEnded(EndReason.BUSY))
        assertEquals("Busy", CallMachine.label(busy, 0))
        val failed = run(CallEvent.Dial(false), CallEvent.LocalEnd(EndReason.FAILED, byUser = false))
        assertEquals("Couldn't connect", CallMachine.label(failed, 0))
        val dropped = run(CallEvent.Dial(false), CallEvent.DocCreated, CallEvent.RemoteAccepted, CallEvent.MediaUp(1), CallEvent.LocalEnd(EndReason.FAILED, false))
        assertEquals("Call failed", CallMachine.label(dropped, 0))
        val mic = run(CallEvent.Dial(false), CallEvent.MicDenied, CallEvent.LocalEnd(EndReason.FAILED, false))
        assertEquals("Microphone access is off", CallMachine.label(mic, 0))
    }

    @Test fun oneCallAtATimeAndEndOnce() {
        val live = run(CallEvent.Dial(false))
        assertEquals(live, CallMachine.reduce(live, CallEvent.Ring(true)))
        assertEquals(live, CallMachine.reduce(live, CallEvent.Dial(true)))
        val ended = CallMachine.reduce(live, CallEvent.LocalEnd(EndReason.NONE, true))
        assertEquals(ended, CallMachine.reduce(ended, CallEvent.RemoteEnded(EndReason.BUSY)))
        assertEquals(CallPhase.OUTGOING, CallMachine.reduce(ended, CallEvent.Dial(false)).phase)
        assertEquals(CallStatus(), CallMachine.reduce(ended, CallEvent.Reset))
        // A remote end on an unaccepted ring here is the caller giving up: no "declined" from me.
        val ringEnd = run(CallEvent.Ring(false), CallEvent.RemoteEnded(EndReason.MISSED))
        assertEquals(EndReason.MISSED, ringEnd.endReason)
    }

    @Test fun clock() {
        assertEquals("0:00", CallMachine.clock(0)); assertEquals("0:59", CallMachine.clock(59))
        assertEquals("10:05", CallMachine.clock(605)); assertEquals("1:00:01", CallMachine.clock(3601))
    }

    @Test fun noAnswerPageForMyUnansweredOrUnconnectedCall() {
        val ended = CallStatus(phase = CallPhase.ENDED, isCaller = true, callDocCreated = true, calleeRinging = true)
        assertTrue(CallMachine.noAnswerPage(ended.copy(endReason = EndReason.MISSED), byUser = false, inFront = true))
        assertTrue(CallMachine.noAnswerPage(ended.copy(endReason = EndReason.DECLINED), byUser = false, inFront = true))
        assertTrue(CallMachine.noAnswerPage(ended.copy(endReason = EndReason.FAILED), byUser = false, inFront = true))
        assertFalse(CallMachine.noAnswerPage(ended.copy(endReason = EndReason.FAILED, connectedAtMs = 1), false, true))
        assertFalse(CallMachine.noAnswerPage(ended.copy(endReason = EndReason.BUSY), false, true))
        assertFalse(CallMachine.noAnswerPage(ended.copy(endReason = EndReason.MISSED), byUser = true, inFront = true))
        assertFalse(CallMachine.noAnswerPage(ended.copy(endReason = EndReason.MISSED), byUser = false, inFront = false))
        assertFalse(CallMachine.noAnswerPage(ended.copy(endReason = EndReason.MISSED, isCaller = false), false, true))
        assertFalse(CallMachine.noAnswerPage(ended.copy(endReason = EndReason.FAILED, micDenied = true), false, true))
    }

    @Test fun endedWordsStayAsLongAsIos() {
        val ended = CallStatus(phase = CallPhase.ENDED, isCaller = true, endReason = EndReason.HANGUP)
        assertEquals(0L, CallMachine.endedHoldMs(ended, byUser = true))
        assertEquals(1000L, CallMachine.endedHoldMs(ended, byUser = false))
        assertEquals(2000L, CallMachine.endedHoldMs(ended.copy(endReason = EndReason.BUSY), byUser = false))
        assertEquals(2000L, CallMachine.endedHoldMs(ended.copy(micDenied = true), byUser = false))
    }
}
