package com.fariin.app.data

import com.fariin.app.call.CallWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    /** H-4: a contact wrote video:"x" and a number for startedBy on a multi-person call doc. */
    @Test fun aStringVideoFlagReadsAsFalse() {
        val c = AdhocCall.fromMap("r1", mapOf("kind" to "adhoc", "video" to "x", "active" to 1L, "startedBy" to 7L,
            "members" to listOf("a", 3L, "b"), "names" to mapOf("a" to 5L), "startedAt" to "soon", "endedAt" to Double.NaN))
        assertNotNull(c)
        assertFalse(c!!.video)
        assertFalse(c.active)
        assertEquals("", c.startedBy)
        assertEquals(listOf("a", "b"), c.members)
        assertEquals(mapOf("a" to ""), c.names)
        assertEquals(0L, c.startedAtMs)
        assertNull(c.endedAtMs)
        assertNull(AdhocCall.fromMap("r2", mapOf("kind" to 9L)))
    }
}
