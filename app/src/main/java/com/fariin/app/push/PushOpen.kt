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

/**
 * Where a chat tap from outside (a notification, its in-app banner, a shortcut) lands. The official
 * Fariin chat has no conversation document: it is its own page, as its row in the list opens it
 * (iOS opens the official chat for its pushes, a sign-in alert among them).
 */
object PushTapRoute {
    fun route(cid: String): String =
        if (cid == com.fariin.app.data.OfficialChannel.CID) "official" else "thread/$cid"

    /**
     * May a plain chat tap open it, after OpenGate (iOS didReceive: open or post gone only)? A
     * notification can outlive its account: signed out of A and in as B, A's banner must not
     * push B onto a chat B is not in, nor onto a chat that is gone.
     */
    fun opens(gate: com.fariin.app.data.OpenGate.Result): Boolean =
        gate == com.fariin.app.data.OpenGate.Result.OPEN || gate == com.fariin.app.data.OpenGate.Result.POST_GONE
}
