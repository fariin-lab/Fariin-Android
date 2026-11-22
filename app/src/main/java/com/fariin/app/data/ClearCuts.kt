package com.fariin.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

/**
 * CLEAR MESSAGES HAS AN UNDO (iOS 1ecd08bc8 + 0c5c3192d, owner 2026-11-21: "there should be the undo
 * bar counting 5 seconds, working exactly like when I leave a channel"). Confirming Clear Messages
 * empties the chat at once and the server's clear runs 5 s later ([LeaveUndo]). Until then nothing is
 * dropped: every sent message up to the cut is only hidden, so Undo shows it again at once from what
 * the chat already holds (no network). The cut stays after the clear has run, so a listener answer
 * still holding the old messages never draws them again.
 */
object ClearCuts {
    private val _cuts = MutableStateFlow<Map<String, Double>>(emptyMap())
    /** Chat id to its cut (ms, the messages' own server time). */
    val cuts: StateFlow<Map<String, Double>> = _cuts.asStateFlow()

    /** The newest sent message each open chat holds, so the cut covers exactly what was on screen. */
    private val newest = java.util.concurrent.ConcurrentHashMap<String, Double>()

    /** The open chat tells its newest sent message's time as its list changes. */
    fun noteNewest(cid: String, ms: Double) { if (ms > 0) newest[cid] = ms }

    /**
     * Hide everything in [cid] up to now and give back the cut. It is the newest message the chat
     * held (its server time), so a message sent by someone else during the Undo window is not
     * hidden and the server keeps it; with no message held it is this phone's clock.
     */
    fun hide(cid: String): Double {
        val cut = newest[cid] ?: System.currentTimeMillis().toDouble()
        _cuts.value = _cuts.value + (cid to maxOf(_cuts.value[cid] ?: 0.0, cut))
        return cut
    }

    /** Undo, or a clear the server refused: the hidden messages come straight back. */
    fun show(cid: String) { _cuts.value = _cuts.value - cid }

    /** Sign-out: the cuts are keyed by chat id, so the next account in the same chat keeps its messages. */
    fun reset() { _cuts.value = emptyMap(); newest.clear() }

    /** Hidden by a waiting or finished Clear Messages: a sent message at or before the cut. */
    fun hides(cut: Double?, m: Message): Boolean = cut != null && m.sendState == null && m.createdAtMillis <= cut

    /**
     * The server's clear (groups:clearGroupMessages), given the cut as `before` so a message sent
     * during the Undo window stays (iOS ChatService.clearGroupMessages). A big chat takes more than
     * one call: the server answers `more` with its own cut-off, which the next call carries. Throws
     * on a refusal.
     */
    suspend fun clearOnServer(cid: String, cut: Double) {
        val fn = com.google.firebase.functions.FirebaseFunctions.getInstance(AuthRepository.FUNCTIONS_REGION)
            .getHttpsCallable("clearGroupMessages").withTimeout(540, java.util.concurrent.TimeUnit.SECONDS)
        val data = mutableMapOf<String, Any>("cid" to cid)
        if (cut > 0) data["before"] = cut.toLong()
        for (round in 0 until 12) {
            val d = fn.call(data).await().getData() as? Map<*, *> ?: break
            if (d["more"] != true) break
            d["before"]?.let { data["before"] = it }
        }
    }
}
