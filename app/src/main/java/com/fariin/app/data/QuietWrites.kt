package com.fariin.app.data

/**
 * Writes to a chat's doc that change nothing are left out. Every write to the doc comes back to
 * this phone twice (the local copy, then the server's answer), and each time the chat list and the
 * open chat are built again. Opening and closing a chat with nothing new wrote four of them
 * (Pixel 5 trace 2026-11-15). The other side sees exactly the same doc either way.
 */
object QuietWrites {
    /**
     * `typing.<me>`: an "on" always goes. An "off" goes only after an "on" from this visit, or when
     * the doc still shows me typing or recording ([docValue], what the listener has for me).
     */
    fun typingWrite(on: Boolean, sentOnThisVisit: Boolean, docValue: Any?): Boolean =
        on || sentOnThisVisit || MessageRules.isTyping(docValue) || MessageRules.isRecording(docValue)

    /**
     * The open chat's read mark (badge to 0, then lastRead): needed while my badge is not 0, I
     * marked the chat unread, or their newest message is newer than my last read (or either time is
     * unknown, as with a lastRead the server has not stamped yet).
     */
    fun readMarkNeeded(unread: Int, markedUnread: Boolean, myLastReadMs: Long?, newestTheirsMs: Long?): Boolean =
        unread > 0 || markedUnread || (newestTheirsMs != null && (myLastReadMs == null || newestTheirsMs > myLastReadMs))
}
