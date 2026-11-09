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

    // Shared with iOS 2026-11-07: the top 20 of the list.
    private const val TOP_CHATS = 20

    /** How long an opening chat waits for its listener before drawing saved rows that are behind.
     *  About the page slide's length (Motion.PAGE_MS): the wait hides under the slide, and the page
     *  is never seen empty (owner's Pixel 2026-11-09: 1.5 s showed a blank chat). Rows newer than
     *  the saved ones then glide in at the bottom. */
    const val OPEN_WAIT_MS = 350L

    /** Behind (shared with iOS): the list's last activity is newer than the newest saved row, 2 s slack. */
    fun behind(listTime: Double, cachedNewest: Double?): Boolean =
        listTime > 0 && (cachedNewest == null || listTime > cachedNewest + 2_000)

    // The last list seen, for a retry: a quiet list sends no new snapshot, so a refresh dropped
    // while a chat was open or during the launch hold would otherwise never come back (iOS finding).
    private var lastList: List<Conversation> = emptyList()
    private var lastMe = ""
    private var holdRetry = false

    /** Runs the last refresh again: a chat closed, the launch hold ended, a slot freed, back in front. */
    fun retry() {
        // A moment later: a chat that just closed is still on the open list for a frame.
        scope.launch {
            kotlinx.coroutines.delay(300)
            val (l, m) = synchronized(this@ChatHistoryPreloader) { lastList to lastMe }
            if (l.isNotEmpty()) refresh(l, m, null)
        }
    }

    @Synchronized
    fun refresh(conversations: List<Conversation>, me: String, openChat: String?) {
        if (me.isEmpty()) return
        lastList = conversations; lastMe = me
        if (openChat != null || ThreadRepository.anyOpen()) return
        warmSaved(conversations, me)
        val held = LAUNCH_HOLD_MS - (SystemClock.elapsedRealtime() - launchedAt)
        if (held > 0) {
            if (!holdRetry) { holdRetry = true; scope.launch { kotlinx.coroutines.delay(held + 50); retry() } }
            return
        }
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

    private val savedWarm = HashSet<String>()
    /**
     * The phone's own saved copy of the top chats, read into memory in the background (owner
     * 2026-11-09: opening a chat lagged). A chat's first open read its file, unsealed it and parsed
     * it on the main thread; now that is already done when the row is tapped, as the reference app
     * keeps its chats' rows ready. Local only (no network), so it runs on every phone, cheap ones too.
     */
    private fun warmSaved(conversations: List<Conversation>, me: String) {
        val ids = conversations.sortedByDescending { it.displayUpdatedAt(me) }.take(TOP_CHATS).map { it.id }.filter { savedWarm.add("$me/$it") }
        if (ids.isEmpty()) return
        // Oldest first, so the newest (the likeliest taps) are the freshest in the memory cache.
        scope.launch { for (id in ids.asReversed()) runCatching { com.fariin.app.ui.thread.ReactionArt.warm(com.fariin.app.local.LocalCache.loadThread(me, id)) } }
    }

    /** A finger is on a chat row: read its saved copy now, before the tap lands. */
    fun touch(me: String, cid: String) {
        if (me.isEmpty() || !savedWarm.add("$me/$cid")) return
        scope.launch { runCatching { com.fariin.app.ui.thread.ReactionArt.warm(com.fariin.app.local.LocalCache.loadThread(me, cid)) } }
    }

    @Synchronized
    fun stopAll() { warming.clear(); warmedAt.clear(); lastList = emptyList(); lastMe = "" }

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
            // A slot is free: the next chat behind goes (only those not yet warmed to their time).
            if (ok && com.fariin.app.AppLifecycle.inFront) retry()   // not after a failure: offline it would loop
        }
    }
}
