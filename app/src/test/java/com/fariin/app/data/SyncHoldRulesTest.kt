package com.fariin.app.data

import com.fariin.app.crypto.Wire
import com.fariin.app.local.SyncHoldRules
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncHoldRulesTest {
    @Test fun lockHoldsOnlyInGroups() {
        assertFalse(SyncHoldRules.keysPending(Wire.LOCKED, "alice_bob"))
        assertTrue(SyncHoldRules.keysPending(Wire.NOT_READY, "alice_bob"))
        assertFalse(SyncHoldRules.keysPending("hello", "alice_bob"))
    }

    @Test fun restAndUnchanged() {
        val last = SyncHoldRules.Pulled(1_000_000L, 5.0)
        assertTrue(SyncHoldRules.mayPull(null, 5.0, 0L))
        assertFalse(SyncHoldRules.mayPull(last, 6.0, 1_030_000L))
        assertTrue(SyncHoldRules.mayPull(last, 6.0, 1_061_000L))
        assertFalse(SyncHoldRules.mayPull(last, 5.0, 1_300_000L))
        assertTrue(SyncHoldRules.mayPull(last, 5.0, 1_601_000L))
    }
}
