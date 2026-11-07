package com.fariin.app.device

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Time limits for the start of a session (iOS 2026-11-07: after log out and log in to another
 * account the app sat on the logo for good, because the first screen waited on a Firestore read
 * with no limit and the client had stalled after the two account changes).
 */
object BootWatch {
    const val TAG = "FariinBoot"
    /** The loading page never stays longer than this: the app opens (signed in) on whatever it has. */
    const val WATCHDOG_MS = 8_000L
    const val SERVER_READ_MS = 5_000L
    const val CACHE_READ_MS = 1_500L
    /** Each server step of Log Out (offline, a Firestore write never answers). */
    const val SIGN_OUT_STEP_MS = 4_000L

    /** [read] with a limit: null for no answer in time or an error. A real cancel still cancels. */
    suspend fun <T> capped(ms: Long, read: suspend () -> T): T? = withTimeoutOrNull(ms) {
        try { read() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }
}

/**
 * Numbered boot attempts: an answer may change the screen only while its attempt is the newest
 * one and its account is still the signed-in one, so a late answer from before a log out (or from
 * a check that was started again) is dropped. Pure, tested.
 */
class BootAttempts {
    private var n = 0
    private var uid: String? = null

    @Synchronized fun begin(forUid: String): Int { n++; uid = forUid; return n }

    @Synchronized fun isCurrent(attempt: Int, forUid: String, signedInUid: String?): Boolean =
        attempt == n && forUid == uid && signedInUid == forUid
}
