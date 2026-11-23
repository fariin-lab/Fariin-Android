package com.fariin.app.data

/**
 * "<name> is inviting you to a voice call" (server ringAdhocRecipients): someone put me on a
 * multi-person call that has no group chat behind it (groupCalls/adhoc_...). The data is only
 * `{ type: "adhoccall", roomId }`; the words come in data `t` / `b` (data-only, androidDataPush) or
 * the notification part (title = the call's title, body = the invite line).
 */
data class AdhocCallPush(val roomId: String) {
    /** The keys a tap carries back to MainActivity, the same ones the push has. */
    fun extras(): Map<String, String> = mapOf("type" to AdhocInviteRules.TYPE, "roomId" to roomId)
}

/** Someone else on the call, for the invitation's faces. */
data class AdhocMember(val uid: String, val name: String, val photo: String?)

/** The invitation read from the call's doc (iOS makeInvite): the others, the starter first. */
data class AdhocInviteInfo(val roomId: String, val title: String, val video: Boolean, val startedBy: String, val others: List<AdhocMember>)

/** Where a join of someone else's multi-person call stopped (GroupCall.joinAdhoc). */
enum class AdhocJoinStep {
    /** The call's doc could not be read (offline): not the same as "the call ended". */
    READ_FAILED,
    /** The doc says the call is off, or it is gone. */
    ENDED,
    /** groupCallToken refused (full, removed, not on the list...). */
    PASS_REFUSED,
    /** groupCallToken answered without a pass. */
    NO_PASS,
    /** The room itself would not connect. */
    CONNECT_FAILED,
}

/**
 * How a failed join ends: [notice] is the alert's words; [reset] means the call state is cleared,
 * a partly opened room is disconnected and the invitation is closed (always, so no half-joined
 * screen is left). Me on `joined` is written only after a good connect, so a failure never has it.
 */
data class AdhocJoinEnd(val notice: String, val reset: Boolean)

/** How an invite push that reached the app is shown. */
enum class AdhocShow {
    /** Not at all: signed out, pushes off, or I am already in that call. */
    NOTHING,
    /** In front while in another call: nothing shown, the server hears "busy" (iOS reportBusy). */
    BUSY,
    /** The app is behind while I am in a call: our notification, a tap or Join opens the invitation. */
    NOTIFICATION,
    /**
     * The app is behind or closed (data-only push, androidDataPush): our own full-screen ring, as a
     * group's call rings (the server no longer sends a plain notice for it).
     */
    RING,
    /** In front with the App Lock page up: in the list only, no drop-down. */
    QUIET_NOTIFICATION,
    /** In front: the full-screen invitation at once, no banner on top (iOS willPresent shows none). */
    INVITE,
}

object AdhocInviteRules {
    const val TYPE = "adhoccall"
    private const val PREFIX = "adhoc_"

    /** The words when the push has none (the server always sends them; this is a safety net). */
    const val FALLBACK_TITLE = "Group call"
    const val FALLBACK_BODY = "Someone is inviting you to a call"

    /** Nil unless it is an invite naming a safe multi-person room (iOS showInvite's guard). */
    fun parse(d: Map<String, String?>): AdhocCallPush? {
        if (d["type"] != TYPE) return null
        val id = d["roomId"].orEmpty()
        if (!id.startsWith(PREFIX) || id.length == PREFIX.length || id.contains("/")) return null
        return AdhocCallPush(id)
    }

    /**
     * [myCallId] is the call I am in or joining (null when none); [otherCallLive] is a 1:1 call up.
     * The doc checks (still on, me on its list, starter not blocked) come after, in [invite].
     */
    fun route(
        p: AdhocCallPush?,
        me: String,
        pushOn: Boolean,
        myCallId: String?,
        otherCallLive: Boolean,
        inFront: Boolean,
        locked: Boolean,
    ): AdhocShow {
        if (p == null || me.isEmpty() || !pushOn) return AdhocShow.NOTHING
        if (myCallId == p.roomId) return AdhocShow.NOTHING
        if (inFront && (myCallId != null || otherCallLive)) return AdhocShow.BUSY
        if (!inFront) return if (myCallId != null || otherCallLive) AdhocShow.NOTIFICATION else AdhocShow.RING
        if (locked) return AdhocShow.QUIET_NOTIFICATION
        return AdhocShow.INVITE
    }

    /**
     * iOS showInvite + makeInvite: nil when the call is off, I am not on its `members`, or the
     * starter is someone I blocked. No `joined` check: a tap is me asking to go back in.
     */
    fun invite(roomId: String, doc: Map<String, Any?>?, me: String, blocked: (String) -> Boolean): AdhocInviteInfo? {
        doc ?: return null
        if (me.isEmpty() || doc["active"] != true) return null
        val members = (doc["members"] as? List<*>).orEmpty().filterIsInstance<String>()
        if (me !in members) return null
        val by = doc["startedBy"] as? String ?: ""
        if (by.isNotEmpty() && blocked(by)) return null
        val names = (doc["names"] as? Map<*, *>).orEmpty()
        val photos = (doc["photos"] as? Map<*, *>).orEmpty()
        val uids = members.filter { it != me }.distinct()
        val ordered = uids.filter { it == by } + uids.filter { it != by }
        val others = ordered.map { u ->
            AdhocMember(u, (names[u] as? String)?.takeIf { it.isNotBlank() } ?: "Member", (photos[u] as? String)?.takeIf { it.isNotEmpty() })
        }
        return AdhocInviteInfo(roomId, title(others.map { it.name }), doc["video"] == true, by, others)
    }

    /** iOS GroupCallService.title(for:): "A", "A & B", "A, B & 1 other", "A, B & 3 others". */
    fun title(names: List<String>): String {
        val n = names.map { it.trim() }.filter { it.isNotEmpty() }
        return when (n.size) {
            0 -> FALLBACK_TITLE
            1 -> n[0]
            2 -> "${n[0]} & ${n[1]}"
            else -> { val rest = n.size - 2; "${n[0]}, ${n[1]} & $rest ${if (rest == 1) "other" else "others"}" }
        }
    }

    /**
     * iOS joinAdhoc / connect: "Couldn't join" for a failed read, "Call ended" for a call that is
     * off, the server's refusal words for a refused pass (joinNotice), else "Call failed".
     */
    fun joinFailure(step: AdhocJoinStep, serverMessage: String? = null): AdhocJoinEnd = AdhocJoinEnd(
        notice = when (step) {
            AdhocJoinStep.READ_FAILED -> "Couldn't join"
            AdhocJoinStep.ENDED -> "Call ended"
            AdhocJoinStep.PASS_REFUSED -> com.fariin.app.call.GroupCallWire.joinNotice(serverMessage)
            AdhocJoinStep.NO_PASS, AdhocJoinStep.CONNECT_FAILED -> "Call failed"
        },
        reset = true,
    )

    /** The push says voice or video only in its words ("... to a video call"). */
    fun video(body: String?): Boolean = body?.contains("video call") == true
}
