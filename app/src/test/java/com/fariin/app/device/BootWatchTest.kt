package com.fariin.app.device

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BootWatchTest {
    @Test fun newestAttemptForTheSignedInAccountCounts() {
        val a = BootAttempts()
        val first = a.begin("A")
        assertTrue(a.isCurrent(first, "A", "A"))
    }

    @Test fun anOlderAttemptIsDropped() {
        val a = BootAttempts()
        val first = a.begin("A")
        val second = a.begin("A")
        assertFalse(a.isCurrent(first, "A", "A"))
        assertTrue(a.isCurrent(second, "A", "A"))
    }

    @Test fun anAnswerForTheOldAccountIsDropped() {
        val a = BootAttempts()
        val old = a.begin("A")
        // A logged out, B logged in: A's late answer must not move B's screen.
        assertFalse(a.isCurrent(old, "A", "B"))
        assertFalse(a.isCurrent(old, "A", null))
        val b = a.begin("B")
        assertFalse(a.isCurrent(old, "A", "B"))
        assertTrue(a.isCurrent(b, "B", "B"))
    }

    @Test fun cappedGivesTheAnswerInTime() = runBlocking {
        assertEquals(7, BootWatch.capped(1_000) { 7 })
    }

    @Test fun cappedGivesNullForAStalledRead() = runBlocking {
        assertNull(BootWatch.capped(50) { awaitCancellation() })
    }

    @Test fun cappedGivesNullForAnError() = runBlocking {
        assertNull(BootWatch.capped<Int>(1_000) { error("offline") })
    }

    @Test fun aRealCancelStillCancels() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var after = false
        val job = async {
            BootWatch.capped(10_000) { started.complete(Unit); delay(10_000) }
            after = true   // must never run: a cancelled boot attempt stops here
        }
        started.await()
        job.cancel()
        try { job.await() } catch (_: CancellationException) {}
        assertFalse(after)
    }
}
