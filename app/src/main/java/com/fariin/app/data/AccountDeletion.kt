package com.fariin.app.data

import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.tasks.await

/**
 * Delete Account (owner 2026-11-07, same flow as iOS): the `scheduleAccountDeletion` callable hides
 * the account now and deletes it after [GRACE_DAYS]; signing in before then restores it.
 *
 * The request carries an optional reason as a short stable code, and free text only for "Other".
 * An older server ignores both fields and answers without `deleteAt`, so the date falls back to the
 * old `due` field, then to today plus [GRACE_DAYS].
 */
object AccountDeletion {
    /** Field names the callable reads and answers with: change them here only. */
    object Fields {
        const val CALLABLE = "scheduleAccountDeletion"
        const val REASON_CODE = "reasonCode"
        const val REASON_TEXT = "reasonText"
        const val DELETE_AT = "deleteAt"      // epoch ms, newer server
        const val LEGACY_DUE = "due"          // epoch ms, older server
    }

    /** Days between the request and the real delete (iOS ProfileStore.gracePeriodDays). */
    const val GRACE_DAYS = 30
    const val REASON_TEXT_MAX = 255

    /** Stable codes the server stores, with the words shown for each. Order is the order on screen. */
    val reasons = listOf(
        "other_account" to "I want to use another account",
        "abusive_users" to "I met abusive users",
        "app_problem" to "Something in the app didn't work for me",
        "not_using" to "I don't use Fariin anymore",
        "other" to "Other reason",
    )
    const val OTHER = "other"

    fun defaultDue(now: Long = System.currentTimeMillis()): Long = now + GRACE_DAYS * 86_400_000L

    /** Pure, tested: what is sent for a choice (nothing for no choice; text only for Other). */
    fun payload(code: String?, text: String): Map<String, Any> {
        if (code.isNullOrEmpty()) return emptyMap()
        val t = text.trim().take(REASON_TEXT_MAX)
        return if (code == OTHER && t.isNotEmpty()) mapOf(Fields.REASON_CODE to code, Fields.REASON_TEXT to t)
            else mapOf(Fields.REASON_CODE to code)
    }

    /** Pure, tested: the delete date in the server's answer, or [fallback]. */
    fun dueFrom(answer: Any?, fallback: Long): Long {
        val m = answer as? Map<*, *> ?: return fallback
        return (m[Fields.DELETE_AT] as? Number)?.toLong()?.takeIf { it > 0 }
            ?: (m[Fields.LEGACY_DUE] as? Number)?.toLong()?.takeIf { it > 0 }
            ?: fallback
    }

    /** Schedules the deletion; returns the delete date (epoch ms). Throws on failure. */
    suspend fun schedule(code: String?, text: String): Long {
        val fns = FirebaseFunctions.getInstance(AuthRepository.FUNCTIONS_REGION)
        // The confirmation email now comes from the server after scheduling (a trigger, 2026-11-07),
        // so the old notifyAccountDeleted call before it is gone; the answer comes back at once.
        val answer = fns.getHttpsCallable(Fields.CALLABLE).call(payload(code, text)).await().getData()
        return dueFrom(answer, defaultDue())
    }
}
