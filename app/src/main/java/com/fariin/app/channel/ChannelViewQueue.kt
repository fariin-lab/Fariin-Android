package com.fariin.app.channel

import com.fariin.app.data.AuthRepository
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Channel post views (iOS ChannelViewQueue): the posts that came ON SCREEN go into a set per channel,
 * sent 1.5 s after the first one, at most 100 per call (`channelViews`). A post sent once is not sent
 * again this session; a refusal (not a follower, banned, closed) stays refused, and only a failure
 * that may pass (offline, busy) lets those posts go again on the next look.
 */
object ChannelViewQueue {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = HashMap<String, LinkedHashSet<String>>()   // cid -> post ids waiting
    private val sent = HashSet<String>()                              // "cid/mid" already sent
    private var flushScheduled = false
    private var generation = 0

    /** Sign-out: the next account's views are its own, not skipped as already sent. */
    @Synchronized
    fun reset() {
        pending.clear(); sent.clear(); flushScheduled = false; generation++
    }

    fun note(cid: String, ids: Collection<String>) {
        if (ids.isEmpty()) return
        val schedule = synchronized(this) {
            val waiting = pending.getOrPut(cid) { LinkedHashSet() }
            for (id in ids) if (ChannelRules.safeId(id) && "$cid/$id" !in sent) waiting.add(id)
            val s = !flushScheduled && waiting.isNotEmpty()
            if (s) flushScheduled = true
            s
        }
        if (!schedule) return
        scope.launch { delay(1_500); flush() }
    }

    private suspend fun flush() {
        val (batch, gen) = synchronized(this) {
            val b = pending.mapValues { it.value.toList() }
            pending.clear(); flushScheduled = false
            for ((cid, ids) in b) for (id in ids) sent.add("$cid/$id")
            b to generation
        }
        val fn = FirebaseFunctions.getInstance(AuthRepository.FUNCTIONS_REGION).getHttpsCallable("channelViews")
        for ((cid, ids) in batch) for (part in ids.chunked(100)) {
            try {
                fn.call(mapOf("cid" to cid, "mids" to part)).await()
            } catch (e: Exception) {
                val code = (e as? FirebaseFunctionsException)?.code
                val refused = code == FirebaseFunctionsException.Code.PERMISSION_DENIED ||
                    code == FirebaseFunctionsException.Code.FAILED_PRECONDITION ||
                    code == FirebaseFunctionsException.Code.NOT_FOUND ||
                    code == FirebaseFunctionsException.Code.INVALID_ARGUMENT ||
                    code == FirebaseFunctionsException.Code.UNAUTHENTICATED
                if (!refused) synchronized(this) { if (gen == generation) for (id in part) sent.remove("$cid/$id") }
            }
        }
    }
}
