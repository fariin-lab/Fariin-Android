package com.fariin.app.data

import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The quote cards, asked from the server (iOS QuoteStore, owner 2026-11-06). Rows ask [card] as they
 * are drawn; ids never asked, and answers older than two minutes, are gathered for a moment and
 * fetched together (up to 50 a call). [cards] changes when an answer does, so open chats redraw.
 */
object QuoteStore {
    private val fn get() = FirebaseFunctions.getInstance(AuthRepository.FUNCTIONS_REGION)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _cards = MutableStateFlow<Map<String, QuoteCard>>(emptyMap())
    val cards: StateFlow<Map<String, QuoteCard>> = _cards

    private val fetchedAt = HashMap<String, Long>()
    private val queued = LinkedHashSet<String>()
    private val inFlight = HashSet<String>()
    private var flushScheduled = false

    /**
     * THE CARDS ARE KEPT ON DISK (iOS QuoteStore; owner 2026-11-07: a channel's quotes read
     * "Loading…" and then grew on every open after a restart). The last answers draw on the first
     * frame; no `fetchedAt` is restored, so each is still asked again at once and access lost since
     * then hides it within a moment. Sealed in LocalCache, per account, wiped at sign-out.
     */
    private var loadedFor: String? = null
    private val lastUsed = HashMap<String, Long>()
    private const val KEEP = 500

    private fun uid(): String? = runCatching { com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid }.getOrNull()

    /** The saved cards into memory before a chat draws (ThreadRoute), so its first frame has them. */
    fun warm() = loadSaved()

    private fun loadSaved() {
        val uid = uid() ?: return
        if (loadedFor == uid) return
        loadedFor = uid
        val text = com.fariin.app.local.LocalCache.loadQuotes(uid) ?: return
        val saved = runCatching {
            val o = org.json.JSONObject(text)
            o.keys().asSequence().mapNotNull { k ->
                val d = o.optJSONObject(k) ?: return@mapNotNull null
                val m = d.keys().asSequence().associateWith { d.opt(it) }
                QuoteRules.parse(m)?.let { k to it }
            }.toMap()
        }.getOrNull() ?: return
        // A live answer already here wins over the saved one.
        if (saved.isNotEmpty()) _cards.value = saved + _cards.value
    }

    private fun save() {
        val uid = uid() ?: return
        val all = _cards.value
        val keep = all.keys.sortedByDescending { lastUsed[it] ?: 0L }.take(KEEP)
        val o = org.json.JSONObject()
        for (k in keep) {
            val c = all[k] ?: continue
            o.put(k, org.json.JSONObject().apply {
                put("state", when (c.state) {
                    QuoteCard.State.OK -> "ok"; QuoteCard.State.DELETED -> "deleted"
                    QuoteCard.State.HIDDEN -> "private"; QuoteCard.State.GONE -> "gone"
                })
                put("cid", c.cid); put("mid", c.mid); put("title", c.title); put("photoUrl", c.photoUrl)
                put("username", c.username); put("text", c.text); put("kind", c.kind); put("image", c.image)
            })
        }
        com.fariin.app.local.LocalCache.saveQuotes(uid, o.toString())
    }

    /** The card for [qid], or null while its first answer is on its way. Asks again when old. */
    fun card(qid: String): QuoteCard? {
        loadSaved()
        if (QuoteRules.isStale(fetchedAt[qid], System.currentTimeMillis())) request(qid)
        val c = _cards.value[qid]
        if (c != null) lastUsed[qid] = System.currentTimeMillis()
        return c
    }

    /** What createQuote gave the quoting phone: shown at once, no second request. */
    fun seed(qid: String, card: QuoteCard) {
        loadSaved()
        fetchedAt[qid] = System.currentTimeMillis()
        lastUsed[qid] = System.currentTimeMillis()
        _cards.value = _cards.value + (qid to card)
        save()
    }

    /** Sign-out: nothing of this account's cards stays in memory (the disk copy goes with LocalCache.wipe). */
    fun reset() {
        _cards.value = emptyMap(); fetchedAt.clear(); lastUsed.clear(); queued.clear(); loadedFor = null
    }

    private fun request(qid: String) {
        if (!QuoteRules.isQid(qid) || qid in inFlight) return
        queued += qid
        if (flushScheduled) return
        flushScheduled = true
        scope.launch {
            delay(120)   // gather a screenful of rows into one call
            flushScheduled = false
            flush()
        }
    }

    private suspend fun flush() {
        while (queued.isNotEmpty()) {
            val batch = queued.take(QuoteRules.BATCH)
            queued.removeAll(batch.toSet())
            inFlight += batch
            try {
                val r = fn.getHttpsCallable("getQuotes").withTimeout(15, TimeUnit.SECONDS)
                    .call(mapOf("qids" to batch)).await().getData() as? Map<*, *>
                val got = r?.get("quotes") as? Map<*, *> ?: emptyMap<String, Any>()
                val now = System.currentTimeMillis()
                var next = _cards.value
                for (q in batch) {
                    fetchedAt[q] = now
                    val c = QuoteRules.parse(got[q] as? Map<*, *>) ?: continue
                    if (next[q] != c) next = next + (q to c)
                }
                if (next !== _cards.value) { _cards.value = next; save() }
            } catch (_: Exception) {
                // Asked again on a later draw after a short pause, not in a loop.
                val stamp = QuoteRules.retryStamp(System.currentTimeMillis())
                for (q in batch) if ((fetchedAt[q] ?: 0L) < stamp) fetchedAt[q] = stamp
                inFlight -= batch.toSet()
                return
            }
            inFlight -= batch.toSet()
        }
    }

    sealed interface Made {
        data class Ok(val qid: String, val card: QuoteCard) : Made
        data class Refused(val text: String) : Made
    }

    /**
     * Quote a channel post: the server checks I may see it and gives a single-use quote id (spent
     * by the send, gone after 24 hours if never sent); my card is seeded. [picture] goes only for an
     * older sealed post (QuoteRules.sendsPicture): the server keeps one shared copy per post, and a
     * plain post's quote points at the post's own file.
     */
    suspend fun create(cid: String, mid: String, picture: ByteArray? = null): Made = try {
        val args = HashMap<String, Any>()
        args["cid"] = cid
        args["mid"] = mid
        if (picture != null) args["image"] = android.util.Base64.encodeToString(picture, android.util.Base64.NO_WRAP)
        val d = fn.getHttpsCallable("createQuote").withTimeout(20, TimeUnit.SECONDS)
            .call(args).await().getData() as? Map<*, *>
        val qid = d?.get("qid") as? String
        val card = QuoteRules.parse(d, forceOk = true)?.copy(cid = cid, mid = mid)
        if (!QuoteRules.isQid(qid) || card == null) Made.Refused("Couldn't quote this post. Try again.")
        else {
            if (picture != null) synchronized(pictures) { pictures[qid!!] = picture }
            seed(qid!!, card); Made.Ok(qid, card)
        }
    } catch (e: FirebaseFunctionsException) {
        Made.Refused(if (e.code == FirebaseFunctionsException.Code.PERMISSION_DENIED || e.code == FirebaseFunctionsException.Code.NOT_FOUND)
            "You can't quote this post." else "Couldn't quote this post. Try again.")
    } catch (_: Exception) {
        Made.Refused("Couldn't quote this post. Try again.")
    }

    /**
     * The JPEG this phone made for each quote it created, kept in memory for this run: the composer
     * bar's thumbnail and my own sent bubble show it at once, before the server's copy loads.
     */
    private val pictures = HashMap<String, ByteArray>()
    fun picture(qid: String): ByteArray? = synchronized(pictures) { pictures[qid] }

    /**
     * THE CHAT OPENS BEFORE THE SERVER ANSWERS (owner 2026-11-06: the picked chat took seconds to
     * open). [start] makes the quote in the background and hands back a local token at once; the
     * composer asks [follow] (its picture, then the quote) and a send asks [whenMade]. One request
     * per quote, however many times it is asked.
     */
    private class Making(val picture: Deferred<ByteArray?>, val made: Deferred<Made>)
    private val making = HashMap<String, Making>()

    fun start(cid: String, mid: String, picture: suspend () -> ByteArray?): String {
        val token = QuoteRules.newToken(UUID.randomUUID().toString())
        val pic = scope.async {
            runCatching { picture() }.getOrNull()?.also { b -> synchronized(pictures) { pictures[token] = b } }
        }
        making[token] = Making(pic, scope.async { create(cid, mid, pic.await()) })
        return token
    }

    /** The quote under [token]: [onPicture] once the phone's picture of the post is kept (under the token), then the server's answer. */
    suspend fun follow(token: String, onPicture: () -> Unit = {}): Made {
        val m = making[token] ?: return Made.Refused(QuoteRules.REFUSED)
        if (m.picture.await() != null) onPicture()
        return m.made.await()
    }

    /** The server's answer for [token], handed to [done] on the main thread even if the chat is closed meanwhile. */
    fun whenMade(token: String, done: (Made) -> Unit) {
        scope.launch { done(making[token]?.made?.await() ?: Made.Refused(QuoteRules.REFUSED)) }
    }

    /**
     * A quote waiting above a chat's composer. [qid] is "" until the server has made it; [token]
     * is the local one it was started under ("" for none); [pictured] turns true once the phone's
     * own picture of the post is ready (so the bar redraws with it).
     */
    data class Draft(val cid: String, val qid: String, val card: QuoteCard, val token: String = "", val pictured: Boolean = false)
    val pendingDraft = MutableStateFlow<Draft?>(null)

    /** A quote card's tap: the channel to open and the post to scroll to once it is open. */
    data class Jump(val cid: String, val mid: String)
    val pendingJump = MutableStateFlow<Jump?>(null)
}
