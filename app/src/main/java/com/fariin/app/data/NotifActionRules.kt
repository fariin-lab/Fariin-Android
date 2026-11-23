package com.fariin.app.data

/**
 * What Reply and Mark as read from the shade may do (system/NotifActionWorker), with no Android
 * types so the unit tests run it. The chat screen refuses these sends; the shade must too.
 */
object NotifActionRules {
    const val CHANNEL = "Open the channel to post. A reply from here would go to every follower."
    const val BLOCKED = "You blocked this person. Unblock them in the chat to send."
    const val REQUEST = "Open the chat to answer this message request."
    const val ACCOUNT = "Open Fariin to finish with your account first."

    /**
     * Why a shade reply must not go, or null when it may.
     * K-42 a channel (an admin's reply would post to every follower), K-84 a person I blocked,
     * K-100 a message request nobody answered (a reply accepts it), K-86 an account waiting for
     * deletion or banned (the Restore page is up in the app).
     */
    fun replyRefusal(
        isGroup: Boolean,
        isChannel: Boolean,
        blockedByMe: Boolean,
        startedBy: String,
        accepted: Boolean,
        /** The chat began while requests were off for me (`reqOff`): an ordinary chat. */
        reqOff: Boolean,
        lastSender: String,
        me: String,
        deletionAtMs: Long,
        banned: Boolean,
        nowMs: Long,
    ): String? = when {
        banned || deletionAtMs > nowMs -> ACCOUNT
        isChannel -> CHANNEL
        !isGroup && blockedByMe -> BLOCKED
        !isGroup && !reqOff && startedBy.isNotEmpty() && startedBy != me && !accepted && lastSender != me -> REQUEST
        else -> null
    }

    /**
     * H-95: the read mark a shade action writes is the time of the newest line the notification
     * showed, never "now", so a message that came after it stays unread for the sender.
     * Null = no write (nothing shown, or my mark is already at or past it).
     */
    fun readMark(shownMs: Long, myLastReadMs: Long?): Long? =
        if (shownMs <= 0 || (myLastReadMs != null && myLastReadMs >= shownMs)) null else shownMs

    /** K-74: a follower's read count after Mark as read on a post: every post, only ever forward. */
    fun followerRead(current: Int?, postCount: Int): Int? =
        if (current != null && current >= postCount) null else postCount
}
