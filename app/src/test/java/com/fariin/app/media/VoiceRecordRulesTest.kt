package com.fariin.app.media

import com.fariin.app.crypto.EncMeta
import com.fariin.app.media.VoiceRecordRules.Release
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceRecordRulesTest {
    private val slop = 10f

    @Test fun quickStillTapLocksHandsFree() {
        assertEquals(Release.LOCK, VoiceRecordRules.onRelease(heldMs = 120, movedPx = 2f, slopPx = slop, elapsed = 0.0))
        // A tap a little over the old 0.3 s is still a tap (iOS widened it to 0.5 s).
        assertEquals(Release.LOCK, VoiceRecordRules.onRelease(heldMs = 420, movedPx = 0f, slopPx = slop, elapsed = 0.4))
    }

    @Test fun tapThatTravelsIsNotATap() {
        assertEquals(Release.TOO_SHORT, VoiceRecordRules.onRelease(heldMs = 200, movedPx = 30f, slopPx = slop, elapsed = 0.2))
    }

    @Test fun shortHoldIsBinnedLongHoldSends() {
        assertEquals(Release.TOO_SHORT, VoiceRecordRules.onRelease(heldMs = 800, movedPx = 0f, slopPx = slop, elapsed = 0.8))
        assertEquals(Release.SEND, VoiceRecordRules.onRelease(heldMs = 1500, movedPx = 0f, slopPx = slop, elapsed = 1.4))
        assertEquals(Release.SEND, VoiceRecordRules.onRelease(heldMs = 1500, movedPx = 40f, slopPx = slop, elapsed = 1.4))
    }

    @Test fun lockAndCancelLines() {
        assertTrue(VoiceRecordRules.locks(-90f, 86f))
        assertFalse(VoiceRecordRules.locks(-80f, 86f))
        assertFalse(VoiceRecordRules.locks(40f, 86f))
        assertTrue(VoiceRecordRules.cancels(-121f, 120f))
        assertFalse(VoiceRecordRules.cancels(-100f, 120f))
        assertEquals(0f, VoiceRecordRules.lockProgress(10f, 86f), 0f)
        assertEquals(0.5f, VoiceRecordRules.lockProgress(-43f, 86f), 0.001f)
        assertEquals(1f, VoiceRecordRules.lockProgress(-200f, 86f), 0f)
    }

    @Test fun lockedSendNeedsASecond() {
        assertFalse(VoiceRecordRules.canSendLocked(0.6))
        assertTrue(VoiceRecordRules.canSendLocked(1.0))
    }

    @Test fun stretchesStayInsideTheLimit() {
        assertEquals(MediaRules.VOICE_NOTE_SECONDS, VoiceRecordRules.remainingSeconds(0.0))
        assertEquals(MediaRules.VOICE_NOTE_SECONDS - 90, VoiceRecordRules.remainingSeconds(90.5))
        assertEquals(1, VoiceRecordRules.remainingSeconds(MediaRules.VOICE_NOTE_SECONDS + 5.0))
    }

    @Test fun stitchedStretchStartsOneFrameAfterTheLast() {
        assertEquals(21_333L, VoiceRecordRules.AAC_FRAME_US)
        assertEquals(2_000_000L + 21_333L, VoiceRecordRules.nextOffsetUs(2_000_000L, wroteAny = true, previousOffsetUs = 0))
        // An empty stretch moves nothing on.
        assertEquals(500L, VoiceRecordRules.nextOffsetUs(0, wroteAny = false, previousOffsetUs = 500L))
    }

    @Test fun oneTimeListenOnlySeeksForward() {
        assertTrue(VoiceRecordRules.onceSeekAllowed(5_000, 2_000))
        assertFalse(VoiceRecordRules.onceSeekAllowed(1_000, 2_000))
        assertFalse(VoiceRecordRules.onceSeekAllowed(2_000, 2_000))
    }

    @Test fun oneTimePillLabels() {
        assertEquals("Voice message", VoiceRecordRules.onceLabel(isMe = true, spent = false, durationSeconds = 12.0))
        assertEquals("Played", VoiceRecordRules.onceLabel(isMe = false, spent = true, durationSeconds = 12.0))
        assertEquals("Voice message · 0:12", VoiceRecordRules.onceLabel(isMe = false, spent = false, durationSeconds = 12.0))
    }

    @Test fun chatListMarkerMatchesIos() {
        assertEquals("🎤 One-time voice message", VoiceRecordRules.preview(53.0, once = true))
        assertEquals(MediaRules.voicePreview(53.0), VoiceRecordRules.preview(53.0, once = false))
    }

    @Test fun oneTimeNoteOnTheWireAsIosWritesIt() {
        val meta = EncMeta(1, "n", "k", "kn")
        val once = MediaMessages.audio("me", meta, 4.0, listOf(10, 20, 30), 1.0, "C1", "TS", viewOnce = true)
        assertEquals(true, once["viewOnce"])
        assertEquals(emptyList<Int>(), once["waveform"])
        assertEquals("audio", once["type"])
        val plain = MediaMessages.audio("me", meta, 4.0, listOf(10, 20, 30), 1.0, "C1", "TS")
        assertNull(plain["viewOnce"])
        assertEquals(listOf(10, 20, 30), plain["waveform"])
    }

    @Test fun resultCarriesTheOnceFlag() {
        val r = VoiceRecorder.Result(java.io.File("x.m4a"), 3.0, listOf(1, 2))
        assertFalse(r.viewOnce)
        val o = r.once(true)
        assertTrue(o.viewOnce)
        assertEquals(3.0, o.duration, 0.0)
        assertEquals(listOf(1, 2), o.waveform)
    }
}
