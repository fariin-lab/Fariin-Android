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

    /** Starts reading [cid]'s newest saved rows for [me]. Main thread (the tap). */
    fun prefetch(me: String, cid: String) {
        synchronized(pending) {
            pending.remove(key(me, cid))?.rows?.cancel()
            pending[key(me, cid)] = Seed(android.os.SystemClock.elapsedRealtime(), scope.async {
                runCatching { com.fariin.app.local.LocalDb.newest(me, cid, com.fariin.app.local.LocalCache.THREAD_CAP) }
                    .getOrDefault(emptyList())
            })
        }
    }

    /** The rows read at the tap if they are ready now, else null (the caller reads them itself). */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun take(me: String, cid: String): List<Message>? {
        val s = synchronized(pending) { pending.remove(key(me, cid)) } ?: return null
        val d = s.rows
        val fresh = android.os.SystemClock.elapsedRealtime() - s.at <= FRESH_MS
        return if (fresh && d.isCompleted && !d.isCancelled) d.getCompleted() else { d.cancel(); null }
    }

    private fun key(me: String, cid: String) = "$me|$cid"
}
