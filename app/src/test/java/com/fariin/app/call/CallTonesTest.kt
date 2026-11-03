package com.fariin.app.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallTonesTest {
    private val dialling = CallStatus(phase = CallPhase.OUTGOING, isCaller = true)

    @Test fun ringbackFromDialToPickUp() {
        assertTrue(CallToneRules.ringback(dialling))
        assertTrue(CallToneRules.ringback(dialling.copy(calleeRinging = true)))
        assertFalse(CallToneRules.ringback(dialling.copy(accepted = true)))
        assertFalse(CallToneRules.ringback(CallMachine.reduce(dialling, CallEvent.RemoteAccepted)))
        assertFalse(CallToneRules.ringback(CallStatus(phase = CallPhase.INCOMING)))
        assertFalse(CallToneRules.ringback(dialling.copy(phase = CallPhase.ENDED)))
    }

    @Test fun endToneOnlyWhenNotMyTapAndHeard() {
        val busy = dialling.copy(phase = CallPhase.ENDED, endReason = EndReason.BUSY)
        assertEquals(CallTone.BUSY, CallToneRules.endTone(busy, byUser = false))
        val hungUp = CallStatus(phase = CallPhase.ENDED, isCaller = false, accepted = true, connectedAtMs = 1, endReason = EndReason.HANGUP)
        assertEquals(CallTone.ENDED, CallToneRules.endTone(hungUp, byUser = false))
        assertEquals(CallTone.ENDED, CallToneRules.endTone(dialling.copy(phase = CallPhase.ENDED, endReason = EndReason.DECLINED), false))
        assertNull(CallToneRules.endTone(hungUp, byUser = true))
        // A ring I never answered that the caller cancelled: nothing heard, no tone.
        assertNull(CallToneRules.endTone(CallStatus(phase = CallPhase.ENDED, endReason = EndReason.MISSED), false))
        assertEquals(2000L, CallToneRules.endToneMs(CallTone.BUSY))
        assertEquals(600L, CallToneRules.endToneMs(CallTone.ENDED))
    }

    @Test fun videoChimeWhenTheirCameraComesOnMidCall() {
        val live = CallStatus(phase = CallPhase.ACTIVE, accepted = true, connectedAtMs = 0)
        assertTrue(CallToneRules.videoChime(false, true, live, held = false, nowMs = 5000))
        assertFalse(CallToneRules.videoChime(false, true, live, held = false, nowMs = 1000))
        assertFalse(CallToneRules.videoChime(true, true, live, held = false, nowMs = 5000))
        assertFalse(CallToneRules.videoChime(false, true, live, held = true, nowMs = 5000))
        assertFalse(CallToneRules.videoChime(false, true, live.copy(connectedAtMs = null), held = false, nowMs = 5000))
    }

    @Test fun samplesMatchTheCadence() {
        val ring = CallToneSamples.samples(CallTone.RINGBACK)
        assertEquals(6 * 8000, ring.size)
        assertTrue(ring.take(16000).any { it > 15000 })   // the ring is loud
        assertTrue(ring.drop(16100).all { it == 0.toShort() })   // 4 s quiet
        assertEquals(8000, CallToneSamples.samples(CallTone.BUSY).size)
        assertEquals((8000 * (0.16 * 2 + 0.05)).toInt(), CallToneSamples.samples(CallTone.ENDED).size)
        assertEquals((16000 * (0.12 * 2 + 0.04)).toInt(), CallToneSamples.samples(CallTone.VIDEO_ON).size)
    }
}
