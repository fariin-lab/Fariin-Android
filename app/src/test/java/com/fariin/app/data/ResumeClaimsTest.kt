package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeClaimsTest {
    @Test fun onlyOneClaimAtATime() {
        val c = ResumeClaims()
        assertTrue(c.claim("a"))
        assertFalse(c.claim("a"))
        assertTrue(c.claim("b"))
        c.release("a")
        assertTrue(c.claim("a"))
    }

    @Test fun waiterRunsOnceWhenTheClaimGoes() {
        val c = ResumeClaims()
        c.claim("a")
        var runs = 0
        assertTrue(c.onRelease("a") { runs++ })
        assertEquals(0, runs)
        c.release("a")
        assertEquals(1, runs)
        c.release("a")
        assertEquals(1, runs)
        assertFalse(c.isHeld("a"))
    }

    @Test fun waiterOnAFreeIdRunsNow() {
        val c = ResumeClaims()
        var runs = 0
        assertFalse(c.onRelease("a") { runs++ })
        assertEquals(1, runs)
    }

    @Test fun everyWaiterRunsEvenIfOneThrows() {
        val c = ResumeClaims()
        c.claim("a")
        var runs = 0
        c.onRelease("a") { error("boom") }
        c.onRelease("a") { runs++ }
        c.release("a")
        assertEquals(1, runs)
    }

    @Test fun aNewClaimDoesNotKeepOldWaiters() {
        val c = ResumeClaims()
        c.claim("a")
        var runs = 0
        c.onRelease("a") { runs++ }
        c.release("a")
        c.claim("a")
        c.release("a")
        assertEquals(1, runs)
    }
}
