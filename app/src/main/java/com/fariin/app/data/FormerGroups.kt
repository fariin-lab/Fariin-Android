package com.fariin.app.data

import com.fariin.app.crypto.Wire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Groups I was removed from (or left on another phone) this session, with me already out of
 * `users` (iOS ThreadView FormerGroups). The chat list drops such a group at once, because its
 * query is "users contains me"; an open chat keeps the group's last copy with me taken out, so it
 * draws "You're no longer a member of this group" where the composer was, the member count
 * without me, and every message it already had. Memory only: a fresh start begins empty.
 */
object FormerGroups {
    private val removed = java.util.concurrent.ConcurrentHashMap<String, Map<String, Any?>>()
    private val _back = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** A former group that is in my chat list again with me in it (added back): its cid. */
    val back: SharedFlow<String> = _back.asSharedFlow()

    fun get(cid: String): Map<String, Any?>? = removed[cid]
    /** The newest `updatedAt` I saw for each, so an older list copy still holding me is not taken for a return. */
    private val removedAt = java.util.concurrent.ConcurrentHashMap<String, Double>()

    private val _version = MutableStateFlow(0)
    /** Bumped when a group is noted or forgotten, so the chat list filters again (ChatsViewModel). */
    val version: StateFlow<Int> = _version.asStateFlow()

    fun note(cid: String, data: Map<String, Any?>, listedAt: Double = 0.0) {
        removed[cid] = data
        removedAt[cid] = maxOf(Conversation.millis(data["updatedAt"]), listedAt)
        _version.value++
    }
    fun forget(cid: String) { if (removed.remove(cid) != null) _version.value++; removedAt.remove(cid) }
    fun clear() { removed.clear(); removedAt.clear() }

    /**
     * A listed copy of a group I was removed from that is not newer than the removal: off the list.
     * The chat list's query keeps such a copy while the open chat's own listener holds the same
     * document, so it is taken off here, as iOS's list drops it.
     */
    fun hides(c: Conversation): Boolean {
        if (!removed.containsKey(c.id)) return false
        return c.updatedAtMillis <= (removedAt[c.id] ?: 0.0)
    }

    /** The chat list changed (ChatsViewModel): a former group listed again with me in it is mine again. */
    fun noteList(convs: List<Conversation>, me: String) {
        if (removed.isEmpty()) return
        for (c in convs) if (me in c.users && removed.containsKey(c.id) && !hides(c)) { forget(c.id); _back.tryEmit(c.id) }
    }

    /** The group as I last saw it, with me out of its members and admins. */
    fun strip(data: Map<String, Any?>, me: String): Map<String, Any?> {
        fun without(k: String) = (data[k] as? List<*>)?.filterIsInstance<String>()?.filter { it != me }
        val out = HashMap(data)
        without("users")?.let { out["users"] = it }
        without("admins")?.let { out["admins"] = it }
        out.remove("typing")   // nobody types to me any more
        return out
    }

    /** A group's live copy that no longer has me in `users` (iOS notAMember). 1:1 chats never are. */
    fun isFormer(cid: String, data: Map<String, Any?>, me: String): Boolean {
        if (!Wire.isGroupCid(cid) || data.isEmpty() || data["channel"] == true) return false
        val users = (data["users"] as? List<*>)?.filterIsInstance<String>() ?: return false
        return me !in users
    }

    /** iOS confirmRemovedFromGroup: a missing document, or `users` without me, means removed. */
    fun goneFromServer(exists: Boolean, users: List<String>?, me: String): Boolean = !exists || me !in (users ?: emptyList())
}

/**
 * Leave Group with Undo (iOS LeaveGroupUndo): the group leaves my list and screens at once and the
 * real leave runs after [WINDOW_MS] unless Undo comes first. A second leave while one waits runs
 * the first at once. Leaving the app (MainActivity.onStop) runs a waiting leave straight away, so
 * it is not lost to a kill.
 */
object LeaveUndo {
    const val WINDOW_MS = 5_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _hidden = MutableStateFlow<Set<String>>(emptySet())
    /** Groups hidden from my lists while their leave waits or runs. */
    val hidden: StateFlow<Set<String>> = _hidden.asStateFlow()
    private var pending: Pair<String, suspend () -> Unit>? = null
    private var title: String = ""
    private var timer: Job? = null
    /** A leave that failed when it ran, shown so it is never silent. */
    var onFailure: (String) -> Unit = {}

    fun start(cid: String, groupTitle: String, leave: suspend () -> Unit) {
        if (pending != null) commit()
        pending = cid to leave
        title = groupTitle
        _hidden.value = _hidden.value + cid
        timer?.cancel()
        timer = scope.launch { delay(WINDOW_MS); commit() }
    }

    /** Taken back: nothing went to the server, so the group simply comes back. */
    fun undo() {
        timer?.cancel(); timer = null
        pending?.let { _hidden.value = _hidden.value - it.first }
        pending = null
    }

    /** Run the waiting leave now. The group stays hidden through it; a failure gives it back. */
    fun commit() {
        timer?.cancel(); timer = null
        val p = pending ?: return
        pending = null
        val t = title
        scope.launch {
            try {
                p.second()
                // The list drops it when the server's copy arrives; hidden a moment longer (iOS).
                delay(3_000)
                _hidden.value = _hidden.value - p.first
            } catch (_: Exception) {
                _hidden.value = _hidden.value - p.first
                onFailure(leaveFailed(t))
            }
        }
    }

    fun isWaiting(cid: String) = pending?.first == cid

    fun leaveFailed(title: String) = "Couldn't leave \"$title\". Check your connection and try again."
}
