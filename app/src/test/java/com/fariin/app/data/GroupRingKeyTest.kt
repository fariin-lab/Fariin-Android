package com.fariin.app.data

import com.fariin.app.call.GroupRingRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class GroupRingKeyTest {
    @Test fun sameCallSameKey() {
        assertEquals(GroupRingRules.ringKey("g1", 1000L), GroupRingRules.ringKey("g1", 1000L))
    }

    @Test fun nextCallInTheSameGroupRingsAgain() {
        assertNotEquals(GroupRingRules.ringKey("g1", 1000L), GroupRingRules.ringKey("g1", 2000L))
    }

    @Test fun otherGroupNotTouched() {
        assertNotEquals(GroupRingRules.ringKey("g1", 1000L), GroupRingRules.ringKey("g2", 1000L))
    }

    @Test fun unknownStartIsZero() {
        assertEquals("g1@0", GroupRingRules.ringKey("g1", null))
    }

    @Test fun linkRingCountsFromSentAt() {
        // A ring sent 50 s after the room started still gets most of its minute.
        assertEquals(50_000L, GroupRingRules.ringLeftMs(100_000L, 110_000L))
    }
}
