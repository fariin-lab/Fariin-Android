package com.fariin.app.data

import android.os.SystemClock
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Warms the chats you are about to open (iOS ChatHistoryPreloader), so a message that arrived
 * while the chat was closed is already on this phone when you tap, instead of coming down the
 * listener a beat after the chat is on screen.
 *
 * The thread opens from Firestore's own local cache. This reads the same newest page the thread
 * reads, from the server, which leaves it in that cache. Selection as iOS (after the reference
 * messenger's preload manager): only chats whose last activity moved since they were last warmed,
 * unmuted before muted, unread before read, newest first; at most three at a time; not while a chat
 * is open; not in the first seconds after launch, when every chat looks stale.
 */
object ChatHistoryPreloader {
    private const val MAX_CONCURRENT = 3
    private const val GIVE_UP_MS = 8_000L
    private const val LAUNCH_HOLD_MS = 3_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val launchedAt = SystemClock.elapsedRealtime()
    private val warming = HashSet<String>()
    private val warmedAt = HashMap<String, Double>()

    private const val TOP_CHATS = 12

    @Synchronized
    fun refresh(conversations: List<Conversation>, me: String, openChat: String?) {
        if (me.isEmpty() || openChat != null) return
        if (SystemClock.elapsedRealtime() - launchedAt < LAUNCH_HOLD_MS) return
        // Battery audit 2026-11-05: on a cheap phone or in Battery Saver nothing is warmed ahead, and
        // elsewhere only the chats near the top (the ones about to be opened), not up to 300 of them.
        val power = com.fariin.app.perf.PowerSaving.state.value
        if (power.deviceClass == com.fariin.app.perf.PerfClass.LOW || power.batterySaverOn) return
        val now = System.currentTimeMillis().toDouble()
        val top = conversations.sortedByDescending { it.displayUpdatedAt(me) }.take(TOP_CHATS).map { it.id }.toSet()
        val ranked = conversations.asSequence()
            .filter { it.id in top }
            .filter { !it.localPending && it.id !in warming && it.displayUpdatedAt(me) > (warmedAt[it.id] ?: 0.0) }
            .sortedWith(compareBy<Conversation> { it.isMuted(me, now) }
                .thenBy { !it.hasUnreadMark(me) }
                .thenByDescending { it.displayUpdatedAt(me) })
            .toList()
        for (c in ranked) {
            if (warming.size >= MAX_CONCURRENT) break
            warm(c, me)
        }
    }

    @Synchronized
    fun stopAll() { warming.clear(); warmedAt.clear() }

    private fun warm(c: Conversation, me: String) {
        val cid = c.id
        val stamp = c.displayUpdatedAt(me)
        warming += cid
        scope.launch {
            val ok = withTimeoutOrNull(GIVE_UP_MS) {
                runCatching {
                    FirebaseFirestore.getInstance().collection("conversations").document(cid).collection("messages")
                        .orderBy("createdAt", Query.Direction.DESCENDING)
                        .limit(ThreadRepository.PAGE_SIZE)
                        .get(Source.SERVER).await()
                }.isSuccess
            } == true
            synchronized(this@ChatHistoryPreloader) {
                warming -= cid
                if (ok) warmedAt[cid] = stamp
            }
        }
    }
}
