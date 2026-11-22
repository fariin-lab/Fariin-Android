package com.fariin.app.data

import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Now as the server says it (iOS ServerClock). A mark that is compared with server stamps, such as
 * Delete for Me's `clearedAt`, must not use a phone clock that runs fast: it would also hide the
 * next message that arrives, on every device of the account. The phone's error is learned once per
 * run from a fresh sign-in token's issue time; under 2 s is the round trip and is ignored.
 */
object ServerClock {
    @Volatile private var offsetMs = 0L
    @Volatile private var synced = false

    fun nowMs(): Double = (System.currentTimeMillis() + offsetMs).toDouble()

    /** [nowMs], after learning the offset once (3 s at most; offline keeps the phone's clock). */
    suspend fun nowMsSynced(): Double {
        if (!synced) sync()
        return nowMs()
    }

    suspend fun sync() {
        val user = com.fariin.app.Fb.auth.currentUser ?: return
        val issuedSec = withTimeoutOrNull(3_000) {
            runCatching { user.getIdToken(true).await().issuedAtTimestamp }.getOrNull()
        } ?: return
        val delta = issuedSec * 1000 - System.currentTimeMillis()
        if (kotlin.math.abs(delta) > 2_000) offsetMs = delta
        synced = true
    }
}
