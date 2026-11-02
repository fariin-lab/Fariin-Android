package com.fariin.app.data

/**
 * Who may do what in a group, and what members may send, to the iOS model (Models.swift
 * Conversation.Right / adminCan / canManageAdmin / SendKind / membersMaySend / slowsMember,
 * GroupSettingsView shortLabel / permissionCount / slowSteps) and firestore.rules
 * (delegatedAdminEditOK, memberSendOk). Pure: unit-tested in GroupAdminsTest.
 */
object GroupAdmins {

    /** The admin rights, in the iOS order, with the admin pages' short names. */
    enum class Right(val slug: String, val label: String) {
        CHANGE_INFO("changeInfo", "Change group info"),
        DELETE_MESSAGES("deleteMessages", "Delete messages"),
        BAN_USERS("banUsers", "Remove members"),
        INVITE_USERS("inviteUsers", "Add members"),
        PIN_MESSAGES("pinMessages", "Pin messages"),
        MANAGE_CALLS("manageCalls", "Video chats"),
        POST_STORIES("postStories", "Add story"),
        ADD_ADMINS("addAdmins", "Add admins");

        companion object { fun of(slug: String): Right? = entries.firstOrNull { it.slug == slug } }
    }

    /** What a new admin gets, and what a legacy admin (no entry) holds: everything but Add admins. */
    val defaults: List<Right> = Right.entries.filter { it != Right.ADD_ADMINS }

    /** The group document's admin half. */
    data class Team(
        val owner: String,
        val users: List<String>,
        val admins: List<String>,
        val rights: Map<String, List<String>>,
        val adminBy: Map<String, String>,
        val names: Map<String, String>,
        val photos: Map<String, String>,
        val closed: Boolean,
    ) {
        companion object {
            fun from(d: Map<String, Any?>): Team {
                fun strMap(k: String) = (d[k] as? Map<*, *>)?.entries?.mapNotNull { (a, b) ->
                    if (a is String && b is String) a to b else null
                }?.toMap() ?: emptyMap()
                fun list(k: String) = (d[k] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                val state = (d["mod"] as? Map<*, *>)?.get("state") as? String ?: "ok"
                return Team(
                    owner = d["createdBy"] as? String ?: "",
                    users = list("users"),
                    admins = list("admins"),
                    rights = (d["adminRights"] as? Map<*, *>)?.entries?.mapNotNull { (k, v) ->
                        if (k is String && v is List<*>) k to v.filterIsInstance<String>() else null
                    }?.toMap() ?: emptyMap(),
                    adminBy = strMap("adminBy"),
                    names = strMap("names"),
                    photos = strMap("photos"),
                    closed = state == "ended" || state == "disabled" || d["fariinLocked"] == true,
                )
            }
        }

        fun isOwner(uid: String) = owner.isNotEmpty() && owner == uid
        /** Conversation.isAdmin: in `admins` (the owner normally is). */
        fun isAdmin(uid: String) = admins.contains(uid)
        /** The owner, or listed as an admin. */
        fun isStaff(uid: String) = isOwner(uid) || isAdmin(uid)

        /** adminCan: the owner always; a legacy admin the defaults; else what was granted. */
        fun can(uid: String, right: Right): Boolean {
            if (isOwner(uid)) return true
            if (!admins.contains(uid)) return false
            val granted = rights[uid] ?: return right != Right.ADD_ADMINS
            return granted.contains(right.slug)
        }

        /** GroupSettingsView.can: a closed group offers no change at all. */
        fun may(uid: String, right: Right) = !closed && can(uid, right)

        fun rightsOf(uid: String): List<Right> = Right.entries.filter { can(uid, it) }

        /** The rights I may hand out: only those I hold myself (all, for the owner). */
        fun grantable(me: String): List<Right> = rightsOf(me)

        /**
         * canManageAdmin (= firestore.rules delegatedAdminEditOK): may `me` promote, demote or edit
         * `target`? The owner anyone but themselves; an Add admins admin with an entry a member, or an
         * admin they promoted. Never the owner, never myself.
         */
        fun canManage(me: String, target: String): Boolean {
            if (closed || target == me || isOwner(target) || !users.contains(target)) return false
            if (isOwner(me)) return true
            if (!can(me, Right.ADD_ADMINS) || rights[me] == null) return false
            return !admins.contains(target) || adminBy[target] == me
        }

        /** The member card's Ban from Group: Remove members; an admin only by the owner. */
        fun canRemove(me: String, target: String): Boolean =
            !closed && target != me && !isOwner(target) && users.contains(target) &&
                can(me, Right.BAN_USERS) && (!admins.contains(target) || isOwner(me))

        /** GroupAdminsView.split: the admins, owner first, then by name. */
        fun sortedAdmins(): List<String> {
            val all = (admins + listOfNotNull(owner.takeIf { it.isNotEmpty() && users.contains(it) })).distinct().filter { users.contains(it) }
            return all.sortedWith(compareBy({ it != owner }, { (names[it] ?: "").lowercase() }))
        }

        /** Members who are not admins, by name: the Choose Member list. */
        fun others(): List<String> = users.filter { !admins.contains(it) && !isOwner(it) }
            .sortedBy { (names[it] ?: "").lowercase() }
    }

    /** Slugs to write for a rights page: model order, only what `grantable` covers. */
    fun toWrite(on: Set<String>, grantable: List<Right>): List<String> =
        Right.entries.filter { it.slug in on && it in grantable }.map { it.slug }

    /** A new admin's switches: the defaults I may give. */
    fun startRights(grantable: List<Right>): Set<String> = defaults.filter { it in grantable }.map { it.slug }.toSet()

    fun allOn(on: Set<String>, shown: List<Right>): Boolean = shown.isNotEmpty() && shown.all { it.slug in on }

    // ---- What members may send (Conversation.SendKind) --------------------------------------------

    enum class SendKind(val slug: String, val label: String, val words: String) {
        PHOTOS("photos", "Photos", "photos"),
        VIDEOS("videos", "Videos", "videos"),
        VOICE("voice", "Voice messages", "voice messages"),
        FILES("files", "Files", "files"),
        STICKERS("stickers", "Stickers", "stickers"),
        GIFS("gifs", "GIFs", "GIFs"),
        LINKS("links", "Links", "links"),
        POLLS("polls", "Polls", "polls"),
        LOCATION("location", "Location", "location sharing");
    }

    private fun memberSend(d: Map<String, Any?>): Map<String, Any?> =
        (d["memberSend"] as? Map<*, *>)?.entries?.mapNotNull { (k, v) -> (k as? String)?.let { it to v } }?.toMap() ?: emptyMap()

    /** membersMaySend: the group's own switch for a kind. The old `media` key still covers photos and videos. */
    fun membersMaySend(d: Map<String, Any?>, kind: SendKind): Boolean {
        val ms = memberSend(d)
        if ((kind == SendKind.PHOTOS || kind == SendKind.VIDEOS) && ms[kind.slug] == null) return ms["media"] != false
        return ms[kind.slug] != false
    }

    private fun isGroup(d: Map<String, Any?>) = (d["type"] as? String ?: "") == "group"

    /** maySend: admins and the owner always; a member as the group's switch says. */
    fun maySend(d: Map<String, Any?>, me: String, kind: SendKind): Boolean {
        if (!isGroup(d)) return true
        val t = Team.from(d)
        if (t.isStaff(me)) return true
        return membersMaySend(d, kind)
    }

    /** heldBackText: "Admins turned off photos in this group.", or null when it may go. */
    fun heldBack(d: Map<String, Any?>, me: String, kind: SendKind): String? =
        if (maySend(d, me, kind)) null else "Admins turned off ${kind.words} in this group."

    /** attachMediaOffText: photos AND videos both off closes the photo grid. */
    fun mediaOff(d: Map<String, Any?>, me: String): String? =
        if (!maySend(d, me, SendKind.PHOTOS) && !maySend(d, me, SendKind.VIDEOS)) "Photos and videos are off in this group." else null

    private val LINK = Regex("(?si).*(https?://|www[.]).*")

    /** firestore.rules memberSendOk's link test on a plain text. */
    fun hasLink(text: String): Boolean = LINK.matches(text)

    /** Announcement mode: a member may not send at all. iOS words. */
    fun announcementBlock(d: Map<String, Any?>, me: String): String? {
        if (!isGroup(d) || d["onlyAdminsSend"] != true) return null
        return if (Team.from(d).isStaff(me)) null else "Only admins can send messages"
    }

    /** The Permissions row's "3/4": send messages, add members, change info, start calls. */
    fun permissionCount(d: Map<String, Any?>, groupCalls: Boolean = true): Pair<Int, Int> {
        val flags = mutableListOf(d["onlyAdminsSend"] != true, d["membersCanAdd"] == true, d["membersCanEditInfo"] == true)
        if (groupCalls) flags += d["onlyAdminsCall"] != true
        return flags.count { it } to flags.size
    }

    fun mediaOnCount(d: Map<String, Any?>): Int = SendKind.entries.count { membersMaySend(d, it) }

    // ---- Slow mode ----------------------------------------------------------------------------

    val slowSteps: List<Pair<Int, String>> = listOf(0 to "Off", 5 to "5s", 10 to "10s", 30 to "30s", 60 to "1m",
        300 to "5m", 900 to "15m", 3600 to "1h")

    /** The nearest step, so a value no step names never reads as Off. */
    fun slowIndex(seconds: Int): Int = slowSteps.indices.minByOrNull { kotlin.math.abs(slowSteps[it].first - seconds) } ?: 0

    /** The line setGroupSlowMode writes after "<name> ". */
    fun slowNotice(seconds: Int): String = when {
        seconds == 0 -> "turned off slow mode"
        seconds < 60 -> "set slow mode to $seconds seconds"
        seconds < 3600 -> "set slow mode to ${seconds / 60} minute${if (seconds == 60) "" else "s"}"
        else -> "set slow mode to 1 hour"
    }

    fun slowSeconds(d: Map<String, Any?>): Int = (d["slowMode"] as? Number)?.toInt() ?: 0

    /** slowsMember: a member (never owner or admins) of a group with slow mode on. */
    fun slows(d: Map<String, Any?>, me: String): Boolean =
        isGroup(d) && slowSeconds(d) > 0 && !Team.from(d).isStaff(me)

    /** slowModeWaitText: null when I may send now, else iOS words with m:ss over a minute. */
    fun slowWait(d: Map<String, Any?>, me: String, lastMineMs: Double?, nowMs: Double): String? {
        if (!slows(d, me) || lastMineMs == null) return null
        val left = lastMineMs + slowSeconds(d) * 1000.0 - nowMs
        if (left <= 0) return null
        val n = kotlin.math.ceil(left / 1000.0).toInt()
        val wait = if (n >= 60) "%d:%02d".format(n / 60, n % 60) else "$n second${if (n == 1) "" else "s"}"
        return "Slow mode is on. You can send again in $wait."
    }

    // ---- Words (iOS) ---------------------------------------------------------------------------

    const val ADMINS_FOOTER = "The owner decides what each admin can do. Tap an admin to see what they can do, including removing members."
    const val MAKE_ADMIN_BODY = "They will be able to manage the group with the usual admin rights. You can change or remove them any time."
    const val BAN_BODY = "They leave the group and can't rejoin, find it in search or open it until an admin unbans them."
    const val NO_ADD_ADMINS = "They won't be able to make other members admins."
    const val OWNER_NOTE = "You are the owner of this group, so you can do everything in it, including adding admins and transferring ownership."
    const val NOT_YOURS = "Only the owner, or the admin who made you an admin, can change these."
    fun rightsFooter(name: String) = "$name can only do what is switched on here. Nobody but the owner can remove or change the owner."
}

/**
 * One invite link as the server's link callables send it (functions-groups lib/grouplinks.js
 * clientLink; iOS GroupLink + GroupLinkLook).
 */
data class GroupLink(
    val code: String,
    val name: String = "",
    val isPrimary: Boolean = false,
    val createdBy: String = "",
    val createdAt: Double = 0.0,
    val expireAt: Double = 0.0,
    val usageLimit: Int = 0,
    val usedCount: Int = 0,
    val requestApproval: Boolean = false,
    val revoked: Boolean = false,
) {
    enum class State { ACTIVE, EXPIRED, USED_UP, REVOKED }

    /** KulanApp.groupLink while the short host is not live: https://fariin.com/g/<code>. */
    val url: String get() = "https://fariin.com/g/$code"
    val shortUrl: String get() = url.removePrefix("https://")
    val title: String get() = name.ifEmpty { shortUrl }

    fun state(now: Double): State = when {
        revoked -> State.REVOKED
        expireAt > 0 && expireAt <= now -> State.EXPIRED
        usageLimit > 0 && usedCount >= usageLimit -> State.USED_UP
        else -> State.ACTIVE
    }

    /** GroupLinkLook.statusLine. */
    fun statusLine(now: Double): String {
        val joined = if (usageLimit > 0 && !requestApproval) "$usedCount of $usageLimit joined"
        else if (usedCount == 0) "No one joined" else "$usedCount joined"
        return when (state(now)) {
            State.REVOKED -> "$joined · Revoked"
            State.EXPIRED -> "$joined · Expired"
            State.USED_UP -> "$joined · Used up"
            State.ACTIVE -> buildList {
                add(joined)
                if (requestApproval) add("Needs approval")
                if (expireAt > 0) add(timeLeft(expireAt - now) + " left")
            }.joinToString(" · ")
        }
    }

    companion object {
        private val SAFE = Regex("^[A-Za-z0-9_-]{1,64}$")

        fun from(d: Map<*, *>?): GroupLink? {
            val code = d?.get("code") as? String ?: return null
            if (!SAFE.matches(code)) return null
            fun n(k: String) = (d[k] as? Number)?.toDouble() ?: 0.0
            return GroupLink(code, d["name"] as? String ?: "", d["isPrimary"] == true, d["createdBy"] as? String ?: "",
                n("createdAt"), n("expireAt"), n("usageLimit").toInt(), n("usedCount").toInt(),
                d["requestApproval"] == true, d["revoked"] == true)
        }

        fun timeLeft(ms: Double): String {
            val minutes = (maxOf(0.0, ms) / 60_000).toInt()
            if (minutes < 60) return if (minutes <= 1) "1 minute" else "$minutes minutes"
            val hours = minutes / 60
            if (hours < 24) return if (hours == 1) "1 hour" else "$hours hours"
            val days = hours / 24
            return if (days == 1) "1 day" else "$days days"
        }

        /** The edit page's Usage Limit footer. */
        fun usesFooter(n: Int): String = if (n <= 0) "Any number of people can join with this link."
        else "The link stops working once $n ${if (n == 1) "person has" else "people have"} joined with it."
    }
}

/** listGroupLinks: the primary link, the others this admin may see, the revoked ones. */
data class GroupLinkList(val primary: GroupLink?, val links: List<GroupLink>, val revoked: List<GroupLink>, val isOwner: Boolean) {
    companion object {
        fun from(d: Map<*, *>?): GroupLinkList = GroupLinkList(
            GroupLink.from(d?.get("primary") as? Map<*, *>),
            (d?.get("links") as? List<*>)?.mapNotNull { GroupLink.from(it as? Map<*, *>) } ?: emptyList(),
            (d?.get("revoked") as? List<*>)?.mapNotNull { GroupLink.from(it as? Map<*, *>) } ?: emptyList(),
            d?.get("isOwner") == true,
        )
    }
}
