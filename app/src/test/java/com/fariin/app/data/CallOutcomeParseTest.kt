package com.fariin.app.data

import com.fariin.app.call.CallWire
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** H-127: the call row is written by the other person too, so its callOutcome may be anything. */
class CallOutcomeParseTest {
    @Test fun aNumberCallOutcomeDoesNotCrashOrSkip() {
        assertFalse(CallWire.recordSkipped("missed", 42L))
        assertFalse(CallWire.recordSkipped("missed", true))
        assertFalse(CallWire.recordSkipped("missed", null))
        assertTrue(CallWire.recordSkipped("missed", "answered"))
    }
}
