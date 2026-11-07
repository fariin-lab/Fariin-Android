package com.fariin.app.ui.thread

/**
 * The view-once bubble's state, the same for a photo and a voice note (owner redesign 2026-11-07,
 * shared with iOS). Unopened: a bigger bubble, the 40dp dashed ring with a 1, "Photo"/"Voice" and
 * a hint line. Opened (either side): one line, the empty dashed ring and "Opened" in italic. My own one reads as unopened until the server says
 * the other side opened it (`consumed`), and I can never open it myself.
 */
internal object OnceRules {
    enum class Kind { PHOTO, VOICE }

    /**
     * [consumed]: the server burned it (the receiver opened it). [seenHere]: this phone opened it
     * (ViewedOnce). My own copy goes by the server only: a mark left on this phone by the old
     * "open my own once" rule must not read as the other side having opened it.
     */
    fun opened(isMe: Boolean, consumed: Boolean, seenHere: Boolean): Boolean =
        consumed || (!isMe && seenHere)

    fun label(kind: Kind, opened: Boolean): String = when {
        opened -> "Opened"
        kind == Kind.PHOTO -> "Photo"
        else -> "Voice"
    }

    /** The second line of the big unopened bubble: what a tap does, or for my own that it waits. */
    fun hint(kind: Kind, isMe: Boolean): String = when {
        isMe -> "Not opened yet"
        kind == Kind.PHOTO -> "Tap to view"
        else -> "Tap to play"
    }

    /** Only the receiver, only once, only when the media is there and the message has landed. */
    fun canOpen(isMe: Boolean, opened: Boolean, hasMedia: Boolean, sending: Boolean): Boolean =
        !isMe && !opened && hasMedia && !sending

    /** The 1 sits in the ring until it is opened. */
    fun ringShowsOne(opened: Boolean): Boolean = !opened

    /** The ring and its 1 are accent green only on an incoming one still waiting to be opened. */
    fun ringIsAccent(isMe: Boolean, opened: Boolean): Boolean = !isMe && !opened
}
