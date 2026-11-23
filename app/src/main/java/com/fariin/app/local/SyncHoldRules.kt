package com.fariin.app.local

import com.fariin.app.crypto.Wire

/** J-90: when the phone's history sync holds on a message, and how often a chat is read again. */
object SyncHoldRules {
    /** A row whose key may still come: "…" anywhere, the lock only in a group. In a 1:1 the lock
     *  is final (FariinCrypto: a key this phone never had), so it is filed, as iOS InboxSync. */
    fun keysPending(text: String, cid: String): Boolean =
        text == Wire.NOT_READY || (Wire.isGroupCid(cid) && text == Wire.LOCKED)

    data class Pulled(val atMs: Long, val updatedAt: Double)

    const val REST_MS = 60_000L
    /** An unchanged chat is still read again after this long (a file that came in the meantime). */
    const val UNCHANGED_MS = 10 * 60_000L

    fun mayPull(last: Pulled?, updatedAt: Double, now: Long): Boolean {
        last ?: return true
        if (now - last.atMs < REST_MS) return false
        if (last.updatedAt == updatedAt && now - last.atMs < UNCHANGED_MS) return false
        return true
    }
}
