package com.fariin.app.lowend

import com.fariin.app.MemoryTrim
import com.fariin.app.local.LoadOnce
import com.fariin.app.media.LowEnd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Start-up off the main thread and memory given back: the pure rules behind them. */
class StartMemoryTest {
    @Test fun loadOnceRunsOnceAcrossThreads() {
        val runs = AtomicInteger()
        val gate = CountDownLatch(1)
        val l = LoadOnce { gate.await(2, TimeUnit.SECONDS); runs.incrementAndGet() }
        val threads = List(8) { Thread { l.ensure() }.apply { start() } }
        gate.countDown()
        threads.forEach { it.join(3_000) }
        assertEquals(1, runs.get())
        assertTrue(l.isDone)
    }

    @Test fun readerWaitsForTheBackgroundRead() {
        // The start-up thread is still reading when a reader comes: the reader sees the full copy,
        // never the empty one.
        var copy = emptyMap<String, String>()
        val started = CountDownLatch(1)
        val l = LoadOnce { started.countDown(); Thread.sleep(150); copy = mapOf("c1" to "draft") }
        l.start("test-load")
        started.await(2, TimeUnit.SECONDS)
        l.ensure()
        assertEquals("draft", copy["c1"])
    }

    @Test fun writeAfterEnsureIsNeverOverwrittenByTheRead() {
        var copy = mapOf("c1" to "old")
        val l = LoadOnce { copy = mapOf("c1" to "from disk") }
        // A write path: ensure first, then change.
        l.ensure(); copy = copy + ("c2" to "new")
        l.ensure()   // a second ensure reads nothing again
        assertEquals("new", copy["c2"])
        assertEquals("from disk", copy["c1"])
    }

    @Test fun failedReadCountsAsDone() {
        val runs = AtomicInteger()
        val l = LoadOnce { runs.incrementAndGet(); throw IllegalStateException("bad file") }
        l.ensure(); l.ensure()
        assertEquals(1, runs.get())
        assertTrue(l.isDone)
    }

    @Test fun pictureCacheFollowsTheNormalHeap() {
        val mb = 1024L * 1024
        assertEquals((128 * mb * 0.15).toLong(), LowEnd.memoryCacheBytes(128, lowRam = true))
        assertEquals(64 * mb, LowEnd.memoryCacheBytes(256, lowRam = false))
        // Never from a nonsense memory class.
        assertTrue(LowEnd.memoryCacheBytes(0, lowRam = true) > 0)
    }

    @Test fun fixedCachesScaleWithTheHeap() {
        val base = 24 * 1024 * 1024
        assertEquals(base, MemoryTrim.scaledBytes(base, 256))
        assertEquals(base, MemoryTrim.scaledBytes(base, 512))      // never above the base
        assertEquals(base / 2, MemoryTrim.scaledBytes(base, 128))
        assertEquals(base / 4, MemoryTrim.scaledBytes(base, 32))   // never under a quarter
    }

    @Test fun trimClearsFromUiHiddenUp() {
        assertFalse(MemoryTrim.shouldClear(5))    // RUNNING_MODERATE: on screen
        assertFalse(MemoryTrim.shouldClear(15))   // RUNNING_CRITICAL: still on screen
        assertTrue(MemoryTrim.shouldClear(20))    // UI_HIDDEN
        assertTrue(MemoryTrim.shouldClear(40))    // BACKGROUND
        assertTrue(MemoryTrim.shouldClear(80))    // COMPLETE
    }

    @Test fun registerReplacesByNameAndOneFailureStopsNothing() {
        val hits = AtomicInteger()
        MemoryTrim.register("t-a") { hits.addAndGet(1) }
        MemoryTrim.register("t-a") { hits.addAndGet(10) }   // replaces the first
        MemoryTrim.register("t-bad") { throw IllegalStateException() }
        MemoryTrim.register("t-b") { hits.addAndGet(100) }
        MemoryTrim.trim(10)   // below UI_HIDDEN: nothing
        assertEquals(0, hits.get())
        MemoryTrim.trim(20)
        assertEquals(110, hits.get())
        assertEquals(1, MemoryTrim.names().count { it == "t-a" })
        listOf("t-a", "t-bad", "t-b").forEach(MemoryTrim::unregister)
        assertFalse("t-a" in MemoryTrim.names())
    }
}
