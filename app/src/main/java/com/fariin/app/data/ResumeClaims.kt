package com.fariin.app.data

/**
 * Who is resuming an outbox entry right now, by clientId. The start-up drain's quiet chat and an
 * opened chat can both reach the same entry: only one gets the claim. The other one waits on it
 * ([onRelease]) so it can redraw its bubble when that send ends (failed: red with Resend; sent:
 * its pending copy goes). Pure, so it is unit tested.
 */
class ResumeClaims {
    private val lock = Any()
    private val held = HashMap<String, MutableList<() -> Unit>>()

    /** True when the caller now holds the claim; false when someone else does. */
    fun claim(clientId: String): Boolean = synchronized(lock) {
        if (held.containsKey(clientId)) false else { held[clientId] = mutableListOf(); true }
    }

    fun isHeld(clientId: String): Boolean = synchronized(lock) { held.containsKey(clientId) }

    /**
     * Runs [listener] once, when the claim on [clientId] goes. Not held (it already went): it runs
     * now. Returns true when it was queued.
     */
    fun onRelease(clientId: String, listener: () -> Unit): Boolean {
        val queued = synchronized(lock) { held[clientId]?.add(listener) != null }
        if (!queued) listener()
        return queued
    }

    /** The send ended (sent, failed or cancelled): the claim goes and every waiter runs, once. */
    fun release(clientId: String) {
        val waiters = synchronized(lock) { held.remove(clientId) } ?: return
        for (w in waiters) runCatching { w() }
    }
}
