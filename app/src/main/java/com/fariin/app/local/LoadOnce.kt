package com.fariin.app.local

/**
 * A saved copy read from disk once, on whichever thread asks first: a start-up thread reads it
 * ahead ([start]) so the main thread never pays for the file, and a reader that arrives before
 * that read is done waits for it ([ensure]) instead of seeing an empty copy.
 *
 * Every read AND every write of the copy calls [ensure] first, so a write can never land before
 * the disk read and be overwritten by it. A failed read counts as done (the copy stays empty),
 * exactly as the old synchronous read behaved.
 */
class LoadOnce(private val load: () -> Unit) {
    @Volatile private var done = false

    val isDone: Boolean get() = done

    /** Reads the copy now if nobody has yet; returns once it is in memory. */
    fun ensure() {
        if (done) return
        synchronized(this) {
            if (done) return
            try { load() } catch (_: Exception) {} finally { done = true }
        }
    }

    /** Reads the copy on its own low-cost thread, so the first reader usually finds it ready. */
    fun start(name: String) {
        if (done) return
        Thread({ ensure() }, name).apply { isDaemon = true }.start()
    }
}
