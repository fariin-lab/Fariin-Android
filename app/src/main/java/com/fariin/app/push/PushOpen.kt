package com.fariin.app.push

/**
 * A tapped chat notification's message, held from the tap until its chat opens (iOS passes the
 * push's `mid` with the chat the same way). The open chat lands at the newest and follows that
 * message in, instead of a reading place saved earlier or rows from the phone's copy that do not
 * have it yet.
 *
 * Only a fresh tap counts: one whose chat never opened must not steer a later, ordinary open.
 */
data class PushOpen(val cid: String, val mid: String, val atMs: Long) {
    /** The message for [chat] when the tap is still fresh at [nowMs], else null. */
    fun midFor(chat: String, nowMs: Long): String? =
        mid.takeIf { chat == cid && nowMs - atMs in 0..FRESH_MS }

    companion object {
        const val FRESH_MS = 30_000L
    }
}
