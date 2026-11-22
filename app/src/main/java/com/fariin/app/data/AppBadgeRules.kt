package com.fariin.app.data

/**
 * The number on the app icon: TOTAL unread messages, as iOS NotificationCleaner.badgeTotal counts
 * them. Muted chats count. The official chat counts. A chat counts unless it is cleared, archived,
 * blocked by me, a group while groups are off, or a message request waiting for me. A chat I only
 * marked unread adds nothing (unread() is never negative and ignores markedUnread).
 */
object AppBadgeRules {
    fun total(
        chats: List<Conversation>,
        me: String,
        official: Conversation? = null,
        /** iOS excluding: the chat just read, already 0 here. */
        excluding: String? = null,
        groupsOn: Boolean = true,
        incoming: (Conversation) -> Boolean = { Requests.stance(it, me) == Requests.Stance.INCOMING },
    ): Int {
        if (me.isEmpty()) return 0
        val rows = if (official != null && chats.none { it.id == official.id }) chats + official else chats
        val sum = rows.filter {
            it.id != excluding && !it.isCleared(me) && !it.isArchived(me) && !it.isBlockedByMe(me) &&
                (groupsOn || !it.isGroup) && !incoming(it)
        }.sumOf { it.unread(me) }
        return maxOf(0, sum)
    }

    /**
     * A push that arrived with the app in the background: the push carries no count on Android, so
     * the last total this phone knew plus this one message. Not for a chat that is open now.
     */
    fun afterPush(lastKnown: Int, countsHere: Boolean): Int = maxOf(0, lastKnown) + if (countsHere) 1 else 0
}
