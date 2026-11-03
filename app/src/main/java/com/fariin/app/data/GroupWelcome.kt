package com.fariin.app.data

/**
 * iOS GroupWelcomeCard's words and rights: the card a group shows while its chat is empty (the
 * group's photo, name and size, who started it, and Add Members / Group Link / Description).
 */
object GroupWelcome {
    fun startedLine(me: String, doc: Map<String, Any?>): String {
        val owner = doc["createdBy"] as? String ?: ""
        if (owner == me) return "You started this group"
        val who = ((doc["names"] as? Map<*, *>)?.get(owner) as? String).orEmpty()
        return if (who.isEmpty()) "You were added to this group" else "$who started this group"
    }

    fun sizeLine(doc: Map<String, Any?>): String {
        val n = (doc["users"] as? List<*>)?.size ?: 0
        return if (n == 1) "Just you so far" else "$n members"
    }

    private fun restricted(me: String, doc: Map<String, Any?>, flag: String, nowMs: Double): Boolean {
        val until = ((doc["restrictedUntil"] as? Map<*, *>)?.get(me) as? Number)?.toDouble() ?: 0.0
        val flags = (doc["restrictedFlags"] as? Map<*, *>)?.get(me) as? List<*> ?: emptyList<Any>()
        return until > nowMs && flag in flags
    }

    /** adminCan(.inviteUsers), or members by the group's switch unless restricted from adding. */
    fun canAdd(me: String, doc: Map<String, Any?>, nowMs: Double = System.currentTimeMillis().toDouble()): Boolean =
        GroupAdmins.Team.from(doc).can(me, GroupAdmins.Right.INVITE_USERS) ||
            (doc["membersCanAdd"] == true && !restricted(me, doc, "addMembers", nowMs))

    /** The link is the admin's own right. */
    fun canLink(me: String, doc: Map<String, Any?>): Boolean = GroupAdmins.Team.from(doc).can(me, GroupAdmins.Right.INVITE_USERS)
}
