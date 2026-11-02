package com.fariin.app.channel

/**
 * Who may do what in a channel, to the iOS model (Models.swift Conversation.Right / adminCan,
 * GroupSettingsView shortLabel(channel:) / applies(channel:)) and the server's own test
 * (functions-groups lib/channels.js channelRightsOf / canAddChannelAdmins / canDismissChannelAdmin).
 *
 * A channel's `users` are its owner and admins only; its followers live in `followers`.
 */
object ChannelAdmins {

    /** The rights a channel admin can hold, in the iOS order (a channel has no calls). */
    enum class Right(val slug: String, val label: String) {
        CHANGE_INFO("changeInfo", "Change channel info"),
        DELETE_MESSAGES("deleteMessages", "Delete posts"),
        BAN_USERS("banUsers", "Remove followers"),
        INVITE_USERS("inviteUsers", "Invite followers"),
        PIN_MESSAGES("pinMessages", "Pin posts"),
        POST_STORIES("postStories", "Add story"),
        ADD_ADMINS("addAdmins", "Add admins");

        companion object {
            fun of(slug: String): Right? = entries.firstOrNull { it.slug == slug }
        }
    }

    /** The server's CHANNEL_ADMIN_RIGHTS: what a new or legacy admin holds (never Add admins). */
    val defaults: List<Right> = Right.entries.filter { it != Right.ADD_ADMINS }

    /** The admin half of a channel document. */
    data class Team(
        val owner: String,
        val admins: List<String>,
        /** uid -> granted slugs; no entry = a legacy admin with the defaults. */
        val rights: Map<String, List<String>>,
        /** uid -> who brought them in. */
        val adminBy: Map<String, String>,
        val names: Map<String, String> = emptyMap(),
        val photos: Map<String, String> = emptyMap(),
    ) {
        companion object {
            fun from(d: Map<String, Any?>): Team {
                fun strMap(k: String) = (d[k] as? Map<*, *>)?.entries?.mapNotNull { (a, b) ->
                    if (a is String && b is String) a to b else null
                }?.toMap() ?: emptyMap()
                val users = (d["users"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                val admins = (d["admins"] as? List<*>)?.filterIsInstance<String>() ?: users
                return Team(
                    owner = d["createdBy"] as? String ?: "",
                    admins = admins,
                    rights = (d["adminRights"] as? Map<*, *>)?.entries?.mapNotNull { (k, v) ->
                        if (k is String && v is List<*>) k to v.filterIsInstance<String>() else null
                    }?.toMap() ?: emptyMap(),
                    adminBy = strMap("adminBy"),
                    names = strMap("names"),
                    photos = strMap("photos"),
                )
            }
        }

        fun isOwner(uid: String) = owner.isNotEmpty() && owner == uid
        fun isAdmin(uid: String) = isOwner(uid) || admins.contains(uid)

        /** adminCan / channelCan: the owner always; a legacy admin the defaults; else what was granted. */
        fun can(uid: String, right: Right): Boolean {
            if (isOwner(uid)) return true
            if (!admins.contains(uid)) return false
            val granted = rights[uid] ?: return right != Right.ADD_ADMINS
            return granted.contains(right.slug)
        }

        fun rightsOf(uid: String): List<Right> = Right.entries.filter { can(uid, it) }

        /** What `me` may hand out on someone's rights page: only what I hold myself. */
        fun grantable(me: String): List<Right> = rightsOf(me)

        /** Owner first, then the others by name (ChannelFollowersView.admins / GroupAdminsView). */
        fun sorted(): List<String> {
            val all = (admins + listOfNotNull(owner.takeIf { it.isNotEmpty() })).distinct()
            return all.sortedWith(compareBy({ it != owner }, { (names[it] ?: "").lowercase() }))
        }

        /** canDismissChannelAdmin: the owner any admin, an Add admins admin those they brought in; never the owner or myself. */
        fun canDismiss(me: String, target: String): Boolean {
            if (me.isEmpty() || target.isEmpty() || me == target || isOwner(target) || !admins.contains(target)) return false
            if (isOwner(me)) return true
            return can(me, Right.ADD_ADMINS) && adminBy[target] == me
        }

        /**
         * May `me` change `target`'s rights (firestore.rules owner branch, or delegatedAdminEditOK:
         * an Add admins admin with an entry, for an admin they brought in). Never the owner, never myself.
         */
        fun canEditRights(me: String, target: String): Boolean {
            if (me == target || isOwner(target) || !admins.contains(target)) return false
            if (isOwner(me)) return true
            return rights[me]?.contains(Right.ADD_ADMINS.slug) == true && adminBy[target] == me
        }
    }

    /** The slugs to write for a rights page: in the model's order, only what `grantable` covers. */
    fun toWrite(on: Set<String>, grantable: List<Right>): List<String> =
        Right.entries.filter { it.slug in on && it in grantable }.map { it.slug }

    /** "All rights" is on when every shown right is on. */
    fun allOn(on: Set<String>, shown: List<Right>): Boolean = shown.isNotEmpty() && shown.all { it.slug in on }

    /** The Followers page's header count: followers as the page opened, plus or minus this page's changes, plus the admins. */
    fun shownCount(followersAtOpen: Int, removed: Int, admins: Int): Int = maxOf(0, followersAtOpen - removed) + admins

    // ---- Words (iOS) ---------------------------------------------------------------------------

    const val FOLLOWERS_FOOTER = "Only admins can see who follows this channel."
    const val NO_FOLLOWERS = "No followers yet. Share the channel to get your first ones."
    const val FOLLOWERS_FAILED = "Couldn't load followers. Pull to try again."
    const val ADMINS_FOOTER = "Admins post in the channel. The owner decides what else each admin can do. Followers never see who the admins are."
    const val PICK_EMPTY = "No followers yet. Only a follower of the channel can become an admin."
    const val PICK_NONE = "No follower by that name."
    const val PICK_FAILED = "Couldn't load followers. Check your connection and try again."
    const val MAKE_ADMIN_BODY = "They can post in the channel. You can change what else they can do from Administrators."

    fun removeTitle(ban: Boolean) = if (ban) "Remove and ban?" else "Remove follower?"
    fun removeBody(ban: Boolean) = if (ban) "They stop following and can't follow again."
    else "They stop following. They can follow again if the channel is public."

    fun rightsFooter(name: String) = "$name can only do what is switched on here. Nobody but the owner can remove or change the owner."
}
