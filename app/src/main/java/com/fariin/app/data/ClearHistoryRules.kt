package com.fariin.app.data

/**
 * Settings > Chats > Clear Chat History, one chat at a time, as iOS ChatsSettingsView.clearEverything
 * runs ChatService.deleteForMe on every chat in the list. The Fariin chat lives in its own store, not
 * in the list, so iOS leaves it alone; it is only cleared here if it ever shows up in the list.
 */
object ClearHistoryRules {
    enum class Step {
        /** My own `clearedAt` mark on the chat (server time), as Delete on one chat. */
        CLEAR_MARK,
        /** A channel I only follow: iOS unfollows it. A follower has no mark of its own to write. */
        UNFOLLOW,
        /** The Fariin chat: its own history clear. */
        CLEAR_OFFICIAL,
    }

    fun step(me: String, c: Conversation): Step = when {
        c.id == OfficialChannel.CID -> Step.CLEAR_OFFICIAL
        c.isFollower(me) -> Step.UNFOLLOW
        else -> Step.CLEAR_MARK
    }
}
