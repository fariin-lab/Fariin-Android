package com.fariin.app.data

/**
 * "Groups in Common" on a person's page (iOS ContactInfoView sharedGroups / groupsInCommonCard).
 * Membership only: a group whose row I swiped away (cleared or hidden) still counts while we are
 * both in it, so the header and the list never disagree. Channels are kept out.
 */
object GroupsInCommon {
    /** Rows on the page before "See All" takes over (the group members card's rule). */
    const val SHOWN = 6

    /** Groups both [me] and [them] are in, A to Z by the name I see, ignoring case. */
    fun shared(chats: List<Conversation>, me: String, them: String): List<Conversation> =
        if (me.isEmpty() || them.isEmpty() || me == them) emptyList()
        else chats.filter { it.isGroup && !it.isChannel && me in it.users && them in it.users }
            .sortedBy { it.displayName(me).lowercase() }

    /** "No Groups in Common", "1 Group in Common", "3 Groups in Common". */
    fun header(count: Int): String =
        if (count == 0) "No Groups in Common" else "$count Group${if (count == 1) "" else "s"} in Common"

    /** The right-hand count on a group row: "1 member", "12 members". */
    fun memberLabel(g: Conversation): String {
        val n = g.users.size
        return "$n member${if (n == 1) "" else "s"}"
    }

    /** The rows on the page and whether "See All" follows: up to six; past six, the first five. */
    fun <T> shown(all: List<T>): Pair<List<T>, Boolean> =
        if (all.size > SHOWN) all.take(SHOWN - 1) to true else all to false
}
