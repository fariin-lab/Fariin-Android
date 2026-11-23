package com.fariin.app.data

/**
 * What a call or group push that is not a message posts (config/features.androidDataPush: the
 * server sends these DATA-ONLY to Android, so with the app closed nothing is drawn unless we draw
 * it). The words come in data `t` (title) and `b` (body); a push that still has a notification
 * part gives those first. `req` "1" = a message request (no Reply, no bubble, no shortcut);
 * `silent` "1" = no sound and no buzz.
 *
 * Every one of these types ends here: none goes on to the message path, which would wipe the
 * group's notification and count it on the widget. A push that cannot be read is [DataPushPost.IGNORE].
 */
enum class DataPushPost {
    /** "adhoccall" ring (it names the caller): our own full-screen ring (GroupRing.onAdhocInvite). */
    ADHOC_RING,
    /**
     * "adhoccall" fallback (only roomId, t, b; sent even with the flag off): a plain banner whose tap
     * opens the call's invitation by roomId. Never a ring.
     */
    ADHOC_NOTICE,
    /** "groupnudge": our nudge notification (or the in-app banner when in front). */
    GROUP_NUDGE,
    /** "joinrequest": a notification whose tap opens that group's join requests. */
    JOIN_REQUEST,
    /** "groupcall" ring (kind "groupring", or it names the caller or room): the group's ring (GroupRing.onPush). */
    GROUP_RING,
    /** "groupcall" of a group too big to ring (only cid, t, b): a normal banner on the Missed Calls channel. */
    GROUP_CALL_BANNER,
    /** One of these types, but nothing to show (bad id, pushes off). Never the message path. */
    IGNORE,
}

data class DataPushRoute(
    val post: DataPushPost,
    /** The group (or the multi-person room for [DataPushPost.ADHOC_RING]). */
    val id: String,
    val title: String,
    val body: String,
    val silent: Boolean,
    val request: Boolean,
    /** A ring's data `sentAt` (ms): a Ring Again rings for its own minute, not the call's first. */
    val sentAtMs: Long? = null,
)

object DataPushRules {
    const val GROUP_CALL = "groupcall"
    const val JOIN_REQUEST = "joinrequest"
    val TYPES = setOf(AdhocInviteRules.TYPE, GroupNudgeRules.TYPE, JOIN_REQUEST, GROUP_CALL)

    /** True for every type this object routes: the caller returns after it, whatever [route] says. */
    fun owns(type: String?): Boolean = type in TYPES

    /**
     * A ring push, told from a plain notice by what it carries: kind "groupring", a caller or a
     * room id. Only checked once [owns] matched the `type`: on a message push (no type) `kind` is the
     * message's type ("text"), never a ring.
     */
    fun isRing(d: Map<String, String?>): Boolean =
        d["kind"] == "groupring" || !d["callerUid"].isNullOrEmpty() || !d["roomId"].isNullOrEmpty()

    fun silent(d: Map<String, String?>): Boolean = d["silent"] == "1"
    fun request(d: Map<String, String?>): Boolean = d["req"] == "1"

    /** The notification part first, then data t / b, then [fallback]. */
    fun words(notif: String?, data: String?, fallback: String): String =
        notif?.takeIf { it.isNotEmpty() } ?: data?.takeIf { it.isNotEmpty() } ?: fallback

    /**
     * Null only when the type is not one of [TYPES] (then the push goes on as before).
     * [pushOn] is this phone's notification switch; the adhoc and nudge paths check it again in
     * their own rules, with the call state they need.
     */
    fun route(d: Map<String, String?>, notifTitle: String?, notifBody: String?, pushOn: Boolean): DataPushRoute? {
        val type = d["type"]
        if (!owns(type)) return null
        val silent = silent(d)
        val request = request(d)
        fun ignore() = DataPushRoute(DataPushPost.IGNORE, "", "", "", silent, request)
        return when (type) {
            AdhocInviteRules.TYPE -> {
                val p = AdhocInviteRules.parse(d) ?: return ignore()
                val ring = d["kind"] == "groupring" || !d["callerUid"].isNullOrEmpty()
                DataPushRoute(if (ring) DataPushPost.ADHOC_RING else DataPushPost.ADHOC_NOTICE, p.roomId,
                    words(notifTitle, d["t"], AdhocInviteRules.FALLBACK_TITLE),
                    words(notifBody, d["b"], AdhocInviteRules.FALLBACK_BODY), silent, request)
            }
            GroupNudgeRules.TYPE -> {
                val n = GroupNudgeRules.parse(d) ?: return ignore()
                DataPushRoute(DataPushPost.GROUP_NUDGE, n.cid,
                    words(notifTitle, d["t"], n.title ?: "Group call"),
                    words(notifBody, d["b"], GroupNudgeRules.fallbackBody(n)), silent, request)
            }
            JOIN_REQUEST -> {
                val cid = safeCid(d["cid"]) ?: return ignore()
                if (!pushOn) return ignore()
                DataPushRoute(DataPushPost.JOIN_REQUEST, cid,
                    words(notifTitle, d["t"], "Request to join"),
                    words(notifBody, d["b"], "Someone asked to join"), silent, request)
            }
            else -> {
                if (isRing(d)) {
                    // A Ring Again of a multi-person call (roomKind "adhoc") rings as that call does.
                    if (d["roomKind"] == "adhoc") {
                        val p = AdhocInviteRules.parse(mapOf("type" to AdhocInviteRules.TYPE, "roomId" to (d["roomId"] ?: d["cid"])))
                            ?: return ignore()
                        return DataPushRoute(DataPushPost.ADHOC_RING, p.roomId,
                            words(notifTitle, d["t"], AdhocInviteRules.FALLBACK_TITLE),
                            words(notifBody, d["b"], AdhocInviteRules.FALLBACK_BODY), silent, request)
                    }
                    // A call link's room is not rung from this push (its ring is "linkring", with the key).
                    if (d["roomKind"] == "link") return ignore()
                    val cid = safeCid(d["cid"]) ?: return ignore()
                    return DataPushRoute(DataPushPost.GROUP_RING, cid, "", "", silent, request, d["sentAt"]?.toLongOrNull()?.takeIf { it > 0 })
                }
                // Only the ring itself rings full screen (F-36); cid, t, b alone is the plain notice.
                val cid = safeCid(d["cid"]) ?: return ignore()
                if (!pushOn) return ignore()
                DataPushRoute(DataPushPost.GROUP_CALL_BANNER, cid,
                    words(notifTitle, d["t"], "Group call"),
                    words(notifBody, d["b"], "A call started"), silent, request)
            }
        }
    }

    private fun safeCid(cid: String?): String? =
        cid?.takeIf { it.isNotEmpty() && !it.contains("/") && !it.startsWith("adhoc_") }
}
