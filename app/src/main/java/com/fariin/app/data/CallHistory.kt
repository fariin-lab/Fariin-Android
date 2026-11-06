package com.fariin.app.data

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await
import java.util.Calendar

/**
 * One row of call history, as iOS CallEntry (CallsRepository.swift). The record is a message
 * in `conversations/{cid}/messages` with `type == "call"`: callerUid, callOutcome
 * (answered | missed, legacy "declined" counts as missed), callVideo, callDuration, createdAt.
 */
data class CallEntry(
    val id: String,
    val cid: String,
    val name: String,
    val photoUrl: String?,
    val otherUid: String,
    val callerUid: String,
    val outcome: String,
    val video: Boolean,
    val durationSec: Int,
    val dateMs: Long,
    val groupCid: String? = null,
) {
    fun mine(me: String): Boolean = callerUid == me

    /** A row still "ringing" after 120 s is a call nobody finalised: missed (iOS audit 2026-09-24). */
    fun missed(nowMs: Long = System.currentTimeMillis()): Boolean =
        outcome == "missed" || outcome == "declined" ||
            (outcome == "ringing" && nowMs - dateMs >= 120_000)

    /** Red only when THEY called and I did not pick up; my own unanswered call is just "Outgoing". */
    fun missedIncoming(me: String, nowMs: Long = System.currentTimeMillis()): Boolean =
        missed(nowMs) && !mine(me)
}

/** Consecutive same-kind calls shown as one "name (3)" row, newest first. */
data class CallRun(val entries: List<CallEntry>) {
    val latest: CallEntry get() = entries[0]
    val id: String get() = latest.id
}

object CallHistory {
    /** The All / Missed switch: Missed keeps only calls I missed from someone else. */
    fun filter(calls: List<CallEntry>, me: String, missedOnly: Boolean, nowMs: Long = System.currentTimeMillis()) =
        if (missedOnly) calls.filter { it.missedIncoming(me, nowMs) } else calls

    /**
     * iOS CallsView.shownRuns: same person, same direction, same missed state, same voice or
     * video, same group, same calendar day, adjacent in the list.
     */
    fun runs(calls: List<CallEntry>, me: String, nowMs: Long = System.currentTimeMillis()): List<CallRun> {
        val out = mutableListOf<MutableList<CallEntry>>()
        for (e in calls) {
            val last = out.lastOrNull()?.first()
            if (last != null && last.otherUid == e.otherUid && last.mine(me) == e.mine(me) &&
                last.missed(nowMs) == e.missed(nowMs) && last.video == e.video &&
                last.groupCid == e.groupCid && sameDay(last.dateMs, e.dateMs)
            ) {
                out.last().add(e)
            } else {
                out.add(mutableListOf(e))
            }
        }
        return out.map { CallRun(it) }
    }

    /** "Outgoing", "Missed" or "Incoming", as the row's second line. */
    fun directionText(e: CallEntry, me: String, nowMs: Long = System.currentTimeMillis()): String =
        if (e.mine(me)) "Outgoing" else if (e.missed(nowMs)) "Missed" else "Incoming"

    /** The length in brackets, only for a single answered call that has one. */
    fun durationText(e: CallEntry, count: Int, nowMs: Long = System.currentTimeMillis()): String? {
        if (count != 1 || e.missed(nowMs) || e.durationSec <= 0) return null
        val secs = e.durationSec
        if (secs < 60) return "$secs sec"
        val mins = secs / 60
        if (mins < 60) return "$mins min"
        val hours = mins / 60
        val rest = mins % 60
        return if (rest == 0) "$hours hr" else "$hours hr $rest min"
    }

    private fun sameDay(a: Long, b: Long): Boolean {
        val ca = Calendar.getInstance().apply { timeInMillis = a }
        val cb = Calendar.getInstance().apply { timeInMillis = b }
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) &&
            ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
    }
}

/**
 * Reads call history the way iOS CallsRepository.load does: one query per chat for its call
 * rows, re-read only when the chat's updatedAt moved. Read only.
 *
 * TODO (iOS has these): ad-hoc group calls from `groupCalls` (members array-contains me), the
 * per-member outcome of a group chat's call (GroupCallOutcome: joinedUids / rungUids), locally
 * hidden calls, the live row of a call in progress, saved call links.
 */
class CallsRepository(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
) {
    private val cache = mutableMapOf<String, Pair<Double, List<CallEntry>>>()

    /** After [load]: the plain reason a chat's calls could not be read (nothing held for it), or null. */
    @Volatile var failed: String? = null
        private set

    suspend fun load(me: String, convs: List<Conversation>): List<CallEntry> = coroutineScope {
        var miss: Throwable? = null
        convs.filter { it.id.isNotEmpty() && !it.id.contains('/') && !it.isChannel }.map { c ->
            async {
                cache[c.id]?.takeIf { it.first == c.updatedAtMillis }?.second ?: run {
                    val read = runCatching {
                        db.collection("conversations").document(c.id).collection("messages")
                            .whereEqualTo("type", "call").get().await()
                    }
                    val snap = read.getOrNull() ?: run {
                        if (cache[c.id] == null) miss = read.exceptionOrNull()
                        return@async cache[c.id]?.second ?: emptyList()
                    }
                    val group = c.isGroup
                    val name = if (group) c.title.ifEmpty { "Group" } else c.name(me)
                    val photo = if (group) c.avatarUrl else c.photoUrl(me)
                    val other = if (group) "" else c.otherUid(me)
                    val list = snap.documents.map { entry(it, c.id, name, photo, other, group) }
                    cache[c.id] = c.updatedAtMillis to list
                    list
                }
            }
        }.awaitAll().flatten().sortedByDescending { it.dateMs }
            .also { failed = miss?.let { e -> LoadFailure.of(e) } }
    }

    private fun entry(d: DocumentSnapshot, cid: String, name: String, photo: String?, other: String, group: Boolean) =
        CallEntry(
            id = d.id, cid = cid, name = name, photoUrl = photo, otherUid = other,
            callerUid = d.getString("callerUid") ?: "",
            outcome = d.getString("callOutcome") ?: "answered",
            video = d.getBoolean("callVideo") ?: false,
            durationSec = (d.get("callDuration") as? Number)?.toInt() ?: 0,
            dateMs = (d.get("createdAt") as? Timestamp)?.toDate()?.time ?: 0L,
            groupCid = if (group) cid else null,
        )
}
