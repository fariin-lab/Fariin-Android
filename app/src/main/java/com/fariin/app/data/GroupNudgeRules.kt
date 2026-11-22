package com.fariin.app.data

/**
 * "Wants you back in the call" (server groupCallNudge, iOS LinkNudge with no key): someone in a
 * group chat's running call taps Notify for a member who left. Data, all strings:
 * `{ type: "groupnudge", cid, callerUid, callerName, title, video, startedAt }`.
 */
data class GroupNudge(
    val cid: String,
    val callerUid: String,
    val callerName: String,
    val title: String?,
    val video: Boolean,
    val startedAtMs: Long?,
) {
    /** The keys a tap carries back to MainActivity, the same ones the push has. */
    fun extras(): Map<String, String> = mapOf(
        "type" to GroupNudgeRules.TYPE, "cid" to cid, "callerUid" to callerUid, "callerName" to callerName,
        "title" to (title ?: ""), "video" to video.toString(), "startedAt" to (startedAtMs?.toString() ?: "0"),
    )
}

/** How a nudge that reached the app is shown. */
enum class NudgeShow {
    /** Not at all: I am in that call, it ended, the group is muted, the caller is blocked, pushes off. */
    NOTHING,
    /** The app is behind: our notification, a tap or Join opens the invitation. */
    NOTIFICATION,
    /** In front with In-App Preview off or the App Lock page up: in the list only, no drop-down. */
    QUIET_NOTIFICATION,
    /** In front: the in-app banner, "<name> wants to talk", with a Join pill (iOS showNudge). */
    BANNER,
}

object GroupNudgeRules {
    const val TYPE = "groupnudge"

    /** Nil unless it is a group nudge naming a safe group id (iOS LinkNudge.init). */
    fun parse(d: Map<String, String?>): GroupNudge? {
        if (d["type"] != TYPE) return null
        val cid = d["cid"].orEmpty()
        if (cid.isEmpty() || cid.contains("/") || cid.startsWith("adhoc_")) return null
        val start = d["startedAt"]?.toDoubleOrNull() ?: 0.0
        // Seconds or milliseconds, as iOS reads it.
        val startMs = if (start > 0) (if (start < 100_000_000_000.0) start * 1000 else start).toLong() else null
        return GroupNudge(
            cid = cid,
            callerUid = d["callerUid"].orEmpty(),
            callerName = d["callerName"]?.takeIf { it.isNotEmpty() } ?: "Someone",
            title = d["title"]?.takeIf { it.isNotEmpty() },
            video = d["video"] == "true",
            startedAtMs = startMs,
        )
    }

    /**
     * [myCallCid] is the group call I am in or joining (null when none); [callActive] is the call's
     * `active` (null when it could not be read: then it is shown, the invitation checks again).
     * [mutedUntilMs] is the group's `mutedBy[me]` (the server already skips a muted group; this is
     * for a mute set while the push was on its way).
     */
    fun route(
        n: GroupNudge?,
        me: String,
        pushOn: Boolean,
        blocked: Boolean,
        myCallCid: String?,
        callActive: Boolean?,
        mutedUntilMs: Long,
        nowMs: Long,
        inFront: Boolean,
        locked: Boolean,
        preview: Boolean,
    ): NudgeShow {
        if (n == null || me.isEmpty() || n.callerUid == me) return NudgeShow.NOTHING
        if (!pushOn || blocked) return NudgeShow.NOTHING
        if (myCallCid == n.cid || callActive == false) return NudgeShow.NOTHING
        if (mutedUntilMs > nowMs) return NudgeShow.NOTHING
        if (!inFront) return NudgeShow.NOTIFICATION
        if (locked || !preview) return NudgeShow.QUIET_NOTIFICATION
        return NudgeShow.BANNER
    }

    /** The notification's words when the push has none (the server's own, Message Preview on). */
    fun fallbackBody(n: GroupNudge): String = "👋 ${n.callerName} wants you back in the call"
}
