package com.fariin.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenAwakeRulesTest {
    @Test fun twoHoldersReleaseOneStaysOn() {
        val r = AwakeReasons()
        assertTrue(r.hold("voice-play"))
        assertFalse(r.hold("voice-record"))
        assertFalse(r.release("voice-play"))
        assertTrue(r.on)
        assertTrue(r.release("voice-record"))
        assertFalse(r.on)
    }

    @Test fun sameReasonTwiceIsOneHold() {
        val r = AwakeReasons()
        r.hold("voice-play")
        assertFalse(r.hold("voice-play"))
        assertTrue(r.release("voice-play"))
        assertFalse(r.on)
    }

    @Test fun releaseWithoutHoldDoesNothing() {
        val r = AwakeReasons()
        assertFalse(r.release("voice-record"))
        assertFalse(r.on)
    }
}
