package com.fariin.app.data

import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
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

    /** The card for [qid], or null while its first answer is on its way. Asks again when old. */
    fun card(qid: String): QuoteCard? {
        if (QuoteRules.isStale(fetchedAt[qid], System.currentTimeMillis())) request(qid)
        return _cards.value[qid]
    }

    /** What createQuote gave the quoting phone: shown at once, no second request. */
    fun seed(qid: String, card: QuoteCard) {
        fetchedAt[qid] = System.currentTimeMillis()
        _cards.value = _cards.value + (qid to card)
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
                if (next !== _cards.value) _cards.value = next
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
     * Quote a channel post: the server checks I may see it and keeps a snapshot; my card is seeded.
     * [picture] is the post's picture as this phone has it open (a small JPEG, see QuotePicture):
     * channel media is sealed, so the server cannot make one itself.
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

    /** A quote waiting for the chat it was sent to, shown above that chat's composer once it opens. */
    data class Draft(val cid: String, val qid: String, val card: QuoteCard)
    val pendingDraft = MutableStateFlow<Draft?>(null)

    /** A quote card's tap: the channel to open and the post to scroll to once it is open. */
    data class Jump(val cid: String, val mid: String)
    val pendingJump = MutableStateFlow<Jump?>(null)
}
