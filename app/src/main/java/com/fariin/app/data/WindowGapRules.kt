package com.fariin.app.data

/**
 * The open chat's live window is the newest 60 messages (audit H-27). When a new full answer
 * shares no message with the window it replaces (a reconnect, a listener made again, 60 or more
 * new messages at once), there may be messages between the two that nothing has read. The rows
 * held from before are then cut off: they leave the screen, and older pages start again from the
 * new window, so scrolling up reads the messages in between instead of jumping over them.
 */
object WindowGapRules {
    /** [held]: ids of the window on screen; [now]: ids of the new answer; [full]: it holds 60. */
    fun cutOff(held: Set<String>, now: Set<String>, full: Boolean): Boolean =
        full && held.isNotEmpty() && now.isNotEmpty() && now.none { it in held }
}
