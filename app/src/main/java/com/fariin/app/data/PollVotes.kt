package com.fariin.app.data

import android.os.Handler
import android.os.Looper
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.tasks.await

/**
 * iOS Polls.swift PollService + PollVotesHub: a poll's votes at
 * conversations/{cid}/messages/{mid}/votes/{uid}, one listener per poll shared by every bubble
 * showing it and kept for a minute after the last one goes, so scrolling past and back does not
 * read a big poll again. In a channel only my own vote is read (the rules let a follower read no
 * other); the counts come from the post (PollRules.channelVotes). Main thread only.
 */
object PollVotes {
    /** uid to chosen option indices; [mineAt] = when my vote reached the server (ms, 0 = not yet). */
    data class Votes(val byUid: Map<String, List<Int>> = emptyMap(), val mineAt: Double = 0.0)

    private class Entry(val flow: MutableStateFlow<Votes>) {
        var reg: ListenerRegistration? = null
        var subs = 0
        var idle = 0
    }

    private val db get() = FirebaseFirestore.getInstance()
    private val entries = HashMap<String, Entry>()
    private val main = Handler(Looper.getMainLooper())

    private fun votesRef(cid: String, mid: String) =
        db.collection("conversations").document(cid).collection("messages").document(mid).collection("votes")

    private fun key(cid: String, mid: String, channel: Boolean) = "$cid|$mid" + if (channel) "|ch" else ""

    /** Starts (or joins) the listener; call [release] with the same arguments when the bubble goes. */
    fun subscribe(cid: String, mid: String, me: String, channel: Boolean): StateFlow<Votes> {
        val k = key(cid, mid, channel)
        val e = entries.getOrPut(k) { Entry(MutableStateFlow(Votes())) }
        e.subs++
        e.idle++
        if (e.reg == null && mid.isNotEmpty()) {
            e.reg = if (channel) {
                if (me.isEmpty()) null else votesRef(cid, mid).document(me).addSnapshotListener { snap, _ ->
                    val mine = PollRules.readOptions(snap?.get("options"))
                    val at = snap?.getTimestamp("at")?.toDate()?.time?.toDouble() ?: 0.0
                    e.flow.value = Votes(if (mine.isEmpty()) emptyMap() else mapOf(me to mine), at)
                }
            } else votesRef(cid, mid).addSnapshotListener { snap, _ ->
                if (snap == null) return@addSnapshotListener
                val out = HashMap<String, List<Int>>()
                for (d in snap.documents) {
                    val o = PollRules.readOptions(d.get("options"))
                    if (o.isNotEmpty()) out[d.id] = o
                }
                e.flow.value = Votes(out, e.flow.value.mineAt)
            }
        }
        return e.flow
    }

    fun release(cid: String, mid: String, channel: Boolean) {
        val k = key(cid, mid, channel)
        val e = entries[k] ?: return
        e.subs--
        if (e.subs > 0) return
        val mark = ++e.idle
        main.postDelayed({
            val now = entries[k]
            if (now === e && e.subs <= 0 && e.idle == mark) { e.reg?.remove(); entries.remove(k) }
        }, 60_000)
    }

    /**
     * My vote, shown at once (two quick taps on a multiple poll must build on the first, iOS audit
     * 2026-10-20), then written; an empty choice deletes the doc. A refusal puts back what was there.
     */
    suspend fun setVote(cid: String, mid: String, me: String, channel: Boolean, options: List<Int>) {
        if (me.isEmpty() || mid.isEmpty()) return
        val e = entries[key(cid, mid, channel)]
        val before = e?.flow?.value
        if (e != null && before != null) {
            val m = before.byUid.toMutableMap()
            if (options.isEmpty()) m.remove(me) else m[me] = options.sorted()
            e.flow.value = before.copy(byUid = m)
        }
        val ref = votesRef(cid, mid).document(me)
        try {
            if (options.isEmpty()) ref.delete().await()
            else ref.set(PollRules.voteDoc(options, FieldValue.serverTimestamp())).await()
        } catch (x: Exception) {
            if (e != null && before != null) e.flow.value = before
            throw x
        }
    }

    /** Stop Poll (iOS PollService.stop): the creator closes it for good. */
    suspend fun stop(cid: String, mid: String) {
        db.collection("conversations").document(cid).collection("messages").document(mid)
            .update("pollClosed", true).await()
    }
}
