package com.fariin.app.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionEndUnsentTest {
    @Test fun nothingQueuedSaysNothing() {
        assertNull(SessionEnd.unsentLine(0, afterRevoke = false))
        assertNull(SessionEnd.unsentLine(0, afterRevoke = true))
    }

    @Test fun logOutWarnsWithTheCount() {
        val one = SessionEnd.unsentLine(1, afterRevoke = false)!!
        assertTrue(one.startsWith("1 message is not sent yet."))
        assertTrue("it will be lost" in one)
        val many = SessionEnd.unsentLine(3, afterRevoke = false)!!
        assertTrue(many.startsWith("3 messages are not sent yet."))
        assertTrue("they will be lost" in many)
    }

    @Test fun remoteSignOutSaysWhatWasDropped() {
        assertEquals("2 messages that were not sent yet could not be delivered.", SessionEnd.unsentLine(2, afterRevoke = true))
    }

    @Test fun noDashesInTheWords() {
        for (n in 1..3) for (r in listOf(true, false)) assertTrue('—' !in SessionEnd.unsentLine(n, r)!!)
    }
}
