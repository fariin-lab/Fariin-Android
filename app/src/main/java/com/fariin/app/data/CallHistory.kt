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
    /** Set only on a multi-person (ad-hoc) call: `id` and `cid` are then the room id (iOS CallEntry.adhoc). */
    val adhoc: AdhocCall? = null,
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

/**
 * The call a person's profile was opened from on the Calls page (iOS ContactInfoView source .calls:
 * the profile then shows that call's card). Set just before the push, taken once by the profile.
 */
object CallInfoLink {
    @Volatile private var pending: Pair<String, CallEntry>? = null
    fun set(uid: String, call: CallEntry) { pending = uid to call }
    fun take(uid: String): CallEntry? = pending?.takeIf { it.first == uid }?.second.also { pending = null }

    /** iOS callLogCard: "Missed video call" for one I missed, else Outgoing / Incoming and the kind. */
    fun line(call: CallEntry, me: String, nowMs: Long = System.currentTimeMillis()): String {
        val kind = if (call.video) "video call" else "voice call"
        return when {
            call.missedIncoming(me, nowMs) -> "Missed $kind"
            call.mine(me) -> "Outgoing $kind"
            else -> "Incoming $kind"
        }
    }
}

object CallHistory {
    /** The All / Missed switch: Missed keeps only calls I missed from someone else. */
    fun filter(calls: List<CallEntry>, me: String, missedOnly: Boolean, nowMs: Long = System.currentTimeMillis()) =
        if (missedOnly) calls.filter { it.missedIncoming(me, nowMs) } else calls

    /**
     * iOS CallsView.shownRuns: same person, same direction, same missed state, same voice or
     * video, same group, same calendar day, adjacent in the list. Two multi-person calls never
     * merge, even with the same people: each room is its own row (iOS roomId check).
     */
    fun runs(calls: List<CallEntry>, me: String, nowMs: Long = System.currentTimeMillis()): List<CallRun> {
        val out = mutableListOf<MutableList<CallEntry>>()
        for (e in calls) {
            val last = out.lastOrNull()?.first()
            if (last != null && last.otherUid == e.otherUid && last.mine(me) == e.mine(me) &&
                last.missed(nowMs) == e.missed(nowMs) && last.video == e.video &&
                last.groupCid == e.groupCid && last.adhoc?.roomId == e.adhoc?.roomId &&
                sameDay(last.dateMs, e.dateMs)
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

    /**
     * iOS callGroupAgain: why a group row's tap may not call the group, said before any call screen,
     * or null to go ahead. A closed group says its notice; only admins may start one (joining a
     * running call is open to every member); a group of one says so. An unknown group goes ahead.
     */
    fun groupCallRefusal(conv: Conversation?, me: String, live: Boolean): String? {
        if (conv == null) return null
        if (conv.ended) return "This group has ended."
        if (conv.fariinLocked()) return GroupMod.noticeText(mapOf("type" to "group",
            "mod" to mapOf("state" to conv.modState, "until" to conv.modUntilMillis)))
        if (!live && conv.onlyAdminsCall && me !in conv.admins) return "Only admins can start a call in this group."
        if (conv.users.size <= 1) return "You're the only one in this group."
        return null
    }

    private fun sameDay(a: Long, b: Long): Boolean {
        val ca = Calendar.getInstance().apply { timeInMillis = a }
        val cb = Calendar.getInstance().apply { timeInMillis = b }
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) &&
            ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
    }
}

/** The people on a multi-person call, from groupCalls/{roomId} with kind "adhoc" (iOS AdhocCallInfo). */
data class AdhocCall(
    val roomId: String,
    val members: List<String>,
    val names: Map<String, String>,
    val photos: Map<String, String>,
    val joined: List<String>,
    val startedBy: String,
    val video: Boolean,
    val active: Boolean,
    val startedAtMs: Long,
    val endedAtMs: Long?,
) {
    fun name(uid: String) = names[uid] ?: "Unknown"
    fun photo(uid: String) = photos[uid]?.ifEmpty { null }
    fun others(me: String) = members.filter { it != me }
    fun joinedOthers(me: String) = joined.filter { it != me && members.contains(it) }
    /** Who the row is named after: the people who joined, or everyone invited when nobody did. */
    fun titleUids(me: String) = joinedOthers(me).ifEmpty { others(me) }

    /** As I saw it: mine and nobody came = my unanswered call; I joined = answered; else missed. A live room reads "ringing". */
    fun entry(me: String): CallEntry {
        val live = active && endedAtMs == null
        val outcome = when {
            startedBy == me -> if (joinedOthers(me).isNotEmpty()) "answered" else if (live) "ringing" else "missed"
            joined.contains(me) -> "answered"
            else -> if (live) "ringing" else "missed"
        }
        val duration = if (outcome == "answered" && endedAtMs != null) ((endedAtMs - startedAtMs) / 1000).toInt().coerceAtLeast(0) else 0
        val first = titleUids(me).firstOrNull() ?: ""
        return CallEntry(roomId, roomId, com.fariin.app.call.GroupCallWire.adhocTitle(titleUids(me).map { name(it) }),
            photo(first), first, startedBy, outcome, video, duration, startedAtMs, adhoc = this)
    }

    companion object {
        fun from(d: DocumentSnapshot): AdhocCall? =
            fromMap(d.id, d.getData(DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) ?: return null)

        /** Any member writes this doc: safe casts only, so a bad field never crashes the Calls list. */
        fun fromMap(id: String, d: Map<String, Any?>): AdhocCall? {
            if (d["kind"] as? String != "adhoc") return null
            fun strings(k: String) = (d[k] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
            fun map(k: String) = (d[k] as? Map<*, *>)?.mapNotNull { (a, b) -> (a as? String)?.let { it to (b as? String ?: "") } }?.toMap() ?: emptyMap()
            fun time(k: String) = (d[k] as? com.google.firebase.Timestamp)?.toDate()?.time
            return AdhocCall(id, strings("members"), map("names"), map("photos"), strings("joined"),
                d["startedBy"] as? String ?: "", d["video"] as? Boolean ?: false, d["active"] as? Boolean ?: false,
                time("startedAt") ?: 0L, time("endedAt"))
        }
    }
}

/**
 * Reads call history the way iOS CallsRepository.load does: one query per chat for its call
 * rows, re-read only when the chat's updatedAt moved, plus the multi-person calls I was on
 * (`groupCalls`, members array-contains me; kind filtered here, so no composite index). Read only.
 *
 * A group chat's call reads as THIS viewer saw it (iOS GroupCallOutcome, from joinedUids / rungUids).
 */
class CallsRepository(
    private val db: FirebaseFirestore = com.fariin.app.Fb.db,
) {
    // Read from a background thread now (MainShell), its chats in parallel.
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Double, List<CallEntry>>>()

    companion object {
        /** Chats whose calls changed without the chat moving (a group call's start and end write the call
         *  row, not the chat's updatedAt), and a counter the Calls list reloads on. */
        private val dirty = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val version = androidx.compose.runtime.mutableIntStateOf(0)
        fun touch(cid: String) { dirty.add(cid); version.intValue++ }
    }

    /** After [load]: the plain reason a chat's calls could not be read (nothing held for it), or null. */
    @Volatile var failed: String? = null
        private set

    /** The last multi-person calls read; kept when a read fails, so a weak network never empties them. */
    @Volatile private var adhocHeld: List<CallEntry> = emptyList()

    suspend fun load(me: String, convs: List<Conversation>): List<CallEntry> = coroutineScope {
        val miss = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val adhoc = async {
            runCatching { db.collection("groupCalls").whereArrayContains("members", me).get().await() }.getOrNull()
                ?.documents?.mapNotNull { runCatching { AdhocCall.from(it)?.entry(me) }.getOrNull() }?.also { adhocHeld = it } ?: adhocHeld
        }
        val chats = convs.filter { it.id.isNotEmpty() && !it.id.contains('/') && !it.isChannel }.map { c ->
            async {
                cache[c.id]?.takeIf { it.first == c.updatedAtMillis && !dirty.remove(c.id) }?.second ?: run {
                    val read = runCatching {
                        db.collection("conversations").document(c.id).collection("messages")
                            .whereEqualTo("type", "call").get().await()
                    }
                    val snap = read.getOrNull() ?: run {
                        if (cache[c.id] == null) miss.set(read.exceptionOrNull())
                        return@async cache[c.id]?.second ?: emptyList()
                    }
                    val group = c.isGroup
                    val name = if (group) c.title.ifEmpty { "Group" } else c.name(me)
                    val photo = if (group) c.avatarUrl else c.photoUrl(me)
                    val other = if (group) "" else c.otherUid(me)
                    val list = snap.documents.mapNotNull { runCatching { entry(it, c.id, name, photo, other, group, me) }.getOrNull() }
                    cache[c.id] = c.updatedAtMillis to list
                    list
                }
            }
        }.awaitAll().flatten()
        (chats + adhoc.await()).sortedByDescending { it.dateMs }
            .also { failed = miss.get()?.let { e -> LoadFailure.of(e) } }
    }

    private fun entry(d: DocumentSnapshot, cid: String, name: String, photo: String?, other: String, group: Boolean, me: String): CallEntry {
        // One map for the row (each getString parsed its field path again).
        // A row just written has no server time yet: the phone's estimate, not 0 (which sorted a call made
        // a moment ago to the very bottom of the list).
        val data = d.getData(DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) ?: emptyMap()
        val caller = data["callerUid"] as? String ?: ""
        val stored = data["callOutcome"] as? String ?: "answered"
        // A group chat's call as I saw it (iOS GroupCallOutcome): joined, missed, or never rung and
        // never in it (no length). A 1:1 record never carries joinedUids and keeps its outcome.
        val outcome = if (!group) stored else StickerGifRules.outcomeMine(stored, caller,
            (data["joinedUids"] as? List<*>)?.filterIsInstance<String>(),
            (data["rungUids"] as? List<*>)?.filterIsInstance<String>(), me) ?: stored
        return CallEntry(
            id = d.id, cid = cid, name = name, photoUrl = photo, otherUid = other,
            callerUid = caller,
            outcome = outcome,
            video = data["callVideo"] as? Boolean ?: false,
            durationSec = if (outcome == StickerGifRules.NOT_JOINED) 0 else (data["callDuration"] as? Number)?.toInt() ?: 0,
            dateMs = (data["createdAt"] as? com.google.firebase.Timestamp)?.toDate()?.time ?: 0L,
            groupCid = if (group) cid else null,
        )
    }
}
