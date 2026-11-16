package com.fariin.app.ui.thread

/** The pure part of where an opening chat lands (ThreadScreen's open/scroll section). */
internal object ThreadOpenRules {
    /**
     * Whether a reopened chat goes back to the reading place saved earlier this run. Not with
     * unread messages (the first-unread landing wins, and the count is known at once, before the
     * server answers and the divider is placed), not for a tapped notification (the chat opens at
     * the newest and follows that message in), and not once the reader has dragged the list.
     */
    fun restoresPlace(unreadAtOpen: Int, hasDivider: Boolean, pushedMid: String?, userDragged: Boolean): Boolean =
        unreadAtOpen <= 0 && !hasDivider && pushedMid == null && !userDragged

    /** How long after the server's first answer rows start to glide and fade (the open has settled). */
    const val SETTLE_MS = 700L

    /** Whether the open has settled: [syncedAtMs] is when the server first answered (0 = not yet). */
    fun placeSettled(syncedAtMs: Long, nowMs: Long): Boolean = syncedAtMs > 0L && nowMs - syncedAtMs >= SETTLE_MS
}
