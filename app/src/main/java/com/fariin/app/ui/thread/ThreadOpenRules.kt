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
}
