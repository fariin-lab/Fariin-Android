package com.fariin.app.call

import com.fariin.app.data.GroupAdmins

/**
 * What the server's `callAdmin` can be asked to do (iOS CallAdminAction). `block` is for call links
 * only: out of the call and refused by that link for good. `muteAll` mutes everyone I may mute,
 * never me, in one request. `unmute` is "Allow to Speak": it lifts an admin's mute.
 */
enum class CallAdminAction(val wire: String) {
    MUTE("mute"), REMOVE("remove"), BLOCK("block"), END("end"), MUTE_ALL("muteAll"), UNMUTE("unmute")
}

/** Someone the Add People picker lists. */
data class CallPerson(val uid: String, val name: String, val photo: String?)

/**
 * The host controls of a group call, as iOS GroupCallParticipantsSheet / GroupCallService.admin
 * and the server's `callAdmin` decide them. Pure: unit-tested in GroupCallAdminTest. The phone only
 * uses these to choose which buttons to show; the server checks every action again itself.
 */
object GroupCallAdmin {
    /** The server's limit on one ring-again request (groupCallRingAgain). */
    const val RING_PART = 16
    /** The server's own wait: one ring again per person per minute. */
    const val RING_AGAIN_WAIT_MS = 60_000L

    /**
     * The access table: the owner may act on anyone but another owner, a moderator only on
     * participants, a participant on nobody. Never oneself; [mayModerate] checks that by uid.
     */
    fun canModerate(mine: CallRole, target: CallRole): Boolean = when (mine) {
        CallRole.OWNER -> target != CallRole.OWNER
        CallRole.MODERATOR -> target == CallRole.PARTICIPANT
        CallRole.PARTICIPANT -> false
    }

    fun mayModerate(me: String?, mine: CallRole, m: GroupMember): Boolean =
        !m.local && m.id.isNotEmpty() && m.id != me && canModerate(mine, m.role)

    /**
     * A group chat call's role from the group as it is now (iOS groupRole, the server's roleIn): the
     * creator is the owner; a member admin with "Manage video chats" a moderator; anyone else a
     * participant. Null while the group has not loaded: the caller keeps the server's attribute.
     */
    fun groupRole(team: GroupAdmins.Team?, uid: String): CallRole? {
        if (team == null || uid.isEmpty()) return null
        if (team.isOwner(uid)) return CallRole.OWNER
        if (uid in team.users && team.can(uid, GroupAdmins.Right.MANAGE_CALLS)) return CallRole.MODERATOR
        return CallRole.PARTICIPANT
    }

    /** `room` of a callAdmin request: a call link's room, else the group chat's id. */
    fun room(cid: String?, linkRoom: String?): Map<String, String>? = when {
        linkRoom != null -> mapOf("kind" to "link", "id" to linkRoom)
        cid != null && cid.startsWith("adhoc_") -> mapOf("kind" to "adhoc", "id" to cid)
        cid != null && !cid.startsWith("link:") -> mapOf("kind" to "group", "id" to cid)
        else -> null
    }

    /** The callAdmin request, as iOS builds it: {room, action, targetUid?}. */
    fun request(room: Map<String, String>, action: CallAdminAction, target: String?): Map<String, Any> {
        val p = mutableMapOf<String, Any>("room" to room, "action" to action.wire)
        if (target != null) p["targetUid"] = target
        return p
    }

    /** Muted by an admin (their `micBlocked` attribute), not by themselves. */
    fun adminMuted(m: GroupMember): Boolean = !m.micOn && m.micBlocked

    /** The people I may mute who are not muted yet. "Mute all" shows once there are two of them. */
    fun mutable(me: String?, mine: CallRole, members: List<GroupMember>): List<GroupMember> =
        members.filter { mayModerate(me, mine, it) && it.micOn }

    fun showMuteAll(me: String?, mine: CallRole, members: List<GroupMember>): Boolean = mutable(me, mine, members).size > 1

    /** End Call for Everyone: the owner of a live call only. */
    fun showEndForAll(mine: CallRole, joined: Boolean): Boolean = mine == CallRole.OWNER && joined

    /** The link's owner (by role, or as its maker) may Remove and Block on a link call. */
    fun ownsLink(isLink: Boolean, mine: CallRole, linkCreator: Boolean): Boolean = isLink && (mine == CallRole.OWNER || linkCreator)

    /** On a link call Remove only puts them out, and they may ask again. */
    fun removeMessage(isLink: Boolean): String = if (isLink) "They can ask to join again." else "They won't be able to rejoin it."

    /**
     * One short line for an admin action the server refused (iOS errorText). [code] is the Functions
     * error code's name (FirebaseFunctionsException.Code.name), null for no answer at all.
     */
    fun errorText(code: String?): String = when (code) {
        null -> "Couldn't do that. Check your connection and try again."
        "PERMISSION_DENIED" -> "Only the host can do that"
        "NOT_FOUND" -> "They're no longer in the call"
        "RESOURCE_EXHAUSTED" -> TOO_MANY
        "UNAUTHENTICATED" -> "Sign in again, then try again."
        else -> "Couldn't do that. Try again."
    }
    const val TOO_MANY = "Too many tries. Wait a moment and try again."

    /** The foot line for a person the server did not ring (groupCallRingAgain's `skipped` reasons). */
    fun ringRefusal(reason: String?, name: String): String = when (reason) {
        "declined" -> "$name declined this call"
        "inCall" -> "$name is already in the call"
        "joined" -> "$name left this call. Notify them instead."
        "ringing" -> "$name is still ringing"
        "tooSoon" -> "Wait a minute before ringing $name again"
        "unreachable" -> "Couldn't reach $name's phone"
        "notMember" -> "$name isn't in this group"
        "limit" -> "$name was rung 3 times. Try a notification later."
        "full" -> "Too many people rung in this call"
        else -> "Can't ring $name now"
    }

    /** A skipped ring that still holds the minute's wait (it went out, or is still up). */
    fun holdsWait(reason: String?): Boolean = reason == "tooSoon" || reason == "unreachable" || reason == "ringing"

    /** The line after Add People on a group call: how many rang, else the last refusal. */
    fun ringToast(picked: List<String>, rung: Int, lastRefusal: String?): String? = when {
        rung > 0 -> if (picked.size == 1) "Ringing ${picked[0]}" else "Ringing $rung people"
        else -> lastRefusal
    }

    /** The line after Add People on a link call (linkCallInvite's `skipped` reasons). */
    fun inviteToast(picked: Int, rung: Int, reasons: List<String>): String? = when {
        "full" in reasons -> "The call is full"
        "unavailable" in reasons -> if (picked == 1) "They can't be added" else "Some people can't be added"
        "unreachable" in reasons -> "Couldn't reach their phone. Try again later."
        rung == 0 && reasons.isNotEmpty() -> if (picked == 1) "Already invited" else "They were already invited"
        else -> null
    }

    /**
     * Add People's second line on a group chat's call: here now, left, or still ringing. Someone
     * with a note is shown but cannot be picked.
     */
    fun groupAddNotes(here: Set<String>, joined: Set<String>, removed: Set<String>, me: String,
                      rangAt: Map<String, Long>, now: Long): Map<String, String> {
        val notes = LinkedHashMap<String, String>()
        for (u in here) notes[u] = "In this call"
        for (u in joined - here - removed - me - "") notes.putIfAbsent(u, "Left this call")
        for ((u, at) in rangAt) if (now - at < RING_AGAIN_WAIT_MS) notes.putIfAbsent(u, "Ringing…")
        return notes
    }
}
