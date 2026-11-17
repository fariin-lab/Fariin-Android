package com.fariin.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * A chat's saved rows, read from the phone's database the moment its row is tapped, on a background
 * thread, so the chat's first frame finds them in memory instead of reading the database itself on
 * the main thread (Pixel trace 2026-11-15: that read sat in the opening frame). Taken once; a chat
 * that finds nothing ready reads as before.
 */
object ThreadRowsPrefetch {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private class Seed(val at: Long, val rows: Deferred<List<Message>>)
    private val pending = HashMap<String, Seed>()
    /** A read older than this (a tap that never opened the chat) is not used. */
    private const val FRESH_MS = 2_000L

    /** Starts reading [cid]'s newest saved rows for [me]. Main thread: as the finger lands on the row,
     *  and again at the tap, which keeps a read already under way (that head start is the point). */
    fun prefetch(me: String, cid: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        synchronized(pending) {
            pending[key(me, cid)]?.let { if (now - it.at <= FRESH_MS && !it.rows.isCancelled) return }
            pending.remove(key(me, cid))?.rows?.cancel()
            pending[key(me, cid)] = Seed(now, scope.async {
                runCatching { com.fariin.app.local.LocalDb.newest(me, cid, com.fariin.app.local.LocalCache.THREAD_CAP) }
                    .getOrDefault(emptyList())
            })
        }
    }

    /**
     * The rows read since the finger landed. A read still under way is waited for (at most
     * [WAIT_MS]): it is already part done, so waiting is always shorter than reading again here.
     * Null when there is no fresh read (the caller reads them itself).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun take(me: String, cid: String): List<Message>? {
        val s = synchronized(pending) { pending.remove(key(me, cid)) } ?: return null
        val d = s.rows
        if (android.os.SystemClock.elapsedRealtime() - s.at > FRESH_MS || d.isCancelled) { d.cancel(); return null }
        if (!d.isCompleted) kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeoutOrNull(WAIT_MS) { d.join() } }
        return if (d.isCompleted && !d.isCancelled) d.getCompleted() else { d.cancel(); null }
    }
    private const val WAIT_MS = 300L

    private fun key(me: String, cid: String) = "$me|$cid"
}
