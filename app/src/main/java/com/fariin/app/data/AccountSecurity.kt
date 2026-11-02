package com.fariin.app.data

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GetTokenResult
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Settings > Account's security pages and the two-step door, after Kulan-iOS
 * AccountSecurityViews.swift (AccountCall, ChangeEmailView, EmailCodeView),
 * TwoStepVerificationViews.swift (TwoStepGate and the pages) and RestoreAccountView.swift.
 * Server: functions-account (me-central1) and functions-loginalert purgeAccountNow.
 */
object TwoStepRules {
    const val MIN = 6

    /** The same three-way test as firestore.rules twoStepSatisfied (iOS TwoStepGate.sessionPassed). */
    fun sessionPassed(claims: Map<String, Any?>, authTimeSec: Long): Boolean {
        val c = claims["twoStep"] as? Map<*, *> ?: return true
        if (c["required"] != true) return true
        if (c["ok"] != true) return false
        val tMs = authTimeSec.toDouble() * 1000
        val legacyAt = (c["legacyAt"] as? Number)?.toDouble() ?: 0.0
        val list = c["authTimes"] as? List<*>
        if (list != null) return list.any { (it as? Number)?.toLong() == authTimeSec } || (legacyAt > 0 && tMs <= legacyAt)
        val at = (c["at"] as? Number)?.toDouble() ?: 0.0
        return at > 0 && tMs <= at
    }

    /** The account has two-step on (the claim says required): keeps the Account row reachable. */
    fun isRequired(claims: Map<String, Any?>): Boolean = (claims["twoStep"] as? Map<*, *>)?.get("required") == true

    /** SetAdditionalPasswordView.canSave. */
    fun canSave(isChange: Boolean, current: String, password: String, confirm: String, busy: Boolean) =
        !busy && password.length >= MIN && password == confirm && (!isChange || current.isNotEmpty())

    /** The code fields: digits only, six at most (a paste cannot slip past). */
    fun code(raw: String) = raw.filter { it.isDigit() }.take(6)

    fun recoveryFooter(maskedRecovery: String) = if (maskedRecovery.isEmpty())
        "No recovery email is set. If you forget this password there is no way to turn it off."
    else "If you forget this password, we can turn it off using your recovery email."
}

object EmailChangeRules {
    /** ChangeEmailView.canSend. */
    fun canSend(newEmail: String, current: String?, sending: Boolean) =
        !sending && newEmail.contains("@") && newEmail.contains(".") &&
            newEmail.trim().lowercase() != (current ?: "").lowercase()

    fun normalized(newEmail: String) = newEmail.trim().lowercase()

    fun codeFooter(address: String) = "We sent a code to $address. Enter it below to confirm."
}

object RestoreRules {
    private const val DAY = 86_400_000L

    /** Whole days from [now] to [due], never below zero (iOS Calendar day components). */
    fun daysLeft(now: Long, due: Long) = ((due - now) / DAY).toInt().coerceAtLeast(0)

    fun body(handle: String) = if (handle.isEmpty()) "Your chats, your photos and your name. Nothing has been deleted."
    else "@$handle, your chats and your photos. Nothing has been deleted."

    fun deadline(days: Int) = if (days == 0) "Today is the last day. Cancel now to keep your account."
    else "$days day${if (days == 1) "" else "s"} left. Cancel any time before then to keep your account as it is."

    fun deleteNowText(dueText: String) =
        "This deletes your account, profile, username and stories straight away instead of on $dueText. It cannot be undone."

    const val FOOT = "Restoring on a different phone brings back your account and your username, but older messages stay unreadable. The key that opens them is only on the phone you deleted from."
}

/** iOS AccountCall.Failure: the server wants a fresh sign-in, or a sentence for a person. */
class AccountCallError(val needsReauth: Boolean, message: String) : Exception(message) {
    companion object {
        const val REAUTH = "For your security, sign in again to continue."
        const val LATER = "Try again in a moment."

        fun from(e: Throwable): AccountCallError {
            if (e is AccountCallError) return e
            if (e is FirebaseFunctionsException) {
                if ((e.details as? Map<*, *>)?.get("reauth") == true) return AccountCallError(true, REAUTH)
                if (e.code == FirebaseFunctionsException.Code.UNAVAILABLE || e.code == FirebaseFunctionsException.Code.DEADLINE_EXCEEDED)
                    return AccountCallError(false, LATER)
                return AccountCallError(false, e.message?.takeIf { it.isNotBlank() } ?: LATER)
            }
            if (e is FirebaseNetworkException) return AccountCallError(false, LATER)
            return AccountCallError(false, e.message?.takeIf { it.isNotBlank() } ?: LATER)
        }
    }
}

object AccountCall {
    private val fn get() = FirebaseFunctions.getInstance(AuthRepository.FUNCTIONS_REGION)

    /** One call to functions-account; every failure comes back as an [AccountCallError]. */
    suspend fun run(name: String, payload: Map<String, Any?> = emptyMap()): Map<*, *> = try {
        fn.getHttpsCallable(name).call(payload).await().getData() as? Map<*, *> ?: emptyMap<String, Any>()
    } catch (e: Exception) { throw AccountCallError.from(e) }

    /** After a call that revokes the refresh tokens: sign in with the session the server minted after it. */
    suspend fun adoptSession(reply: Map<*, *>) {
        val auth = FirebaseAuth.getInstance()
        (reply["customToken"] as? String)?.let { runCatching { auth.signInWithCustomToken(it).await() } }
        runCatching { auth.currentUser?.getIdToken(true)?.await() }
    }
}

/** iOS TwoStepGate: the app's copy of the rules' per-sign-in test, and keeping a session through it. */
object TwoStepGate {
    private fun passed(r: GetTokenResult) = TwoStepRules.sessionPassed(r.claims, r.authTimestamp)

    /** true: this sign-in must enter the password; false: through; null: the token did not come in time. */
    suspend fun needsPassword(timeoutMs: Long = 2000): Boolean? {
        val user = FirebaseAuth.getInstance().currentUser ?: return false
        val r = withTimeoutOrNull(timeoutMs) { runCatching { user.getIdToken(false).await() }.getOrNull() } ?: return null
        return !passed(r)
    }

    /** The account has two-step on (claim required), read from the held token. */
    suspend fun accountHasTwoStep(): Boolean {
        val r = runCatching { FirebaseAuth.getInstance().currentUser?.getIdToken(false)?.await() }.getOrNull() ?: return false
        return TwoStepRules.isRequired(r.claims)
    }

    /** The token held just before a re-authentication (for [renewAfterReauth]). */
    suspend fun tokenBefore(): GetTokenResult? =
        runCatching { FirebaseAuth.getInstance().currentUser?.getIdToken(false)?.await() }.getOrNull()

    /**
     * Re-authenticating gives a new auth_time, which is not on the claim's list: without this the
     * phone would lock itself out. renewTwoStepSession adds it when the earlier token was through.
     * One try now, then 2 s, 5 s and 15 s (iOS; the on-disk retry marker is not ported yet).
     */
    suspend fun renewAfterReauth(previous: GetTokenResult?) {
        previous ?: return
        if (!TwoStepRules.isRequired(previous.claims) || !passed(previous)) return
        val token = previous.token ?: return
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        for (wait in listOf(0L, 2000L, 5000L, 15000L)) {
            if (wait > 0) delay(wait)
            if (FirebaseAuth.getInstance().currentUser?.uid != uid) return
            if (previous.expirationTimestamp * 1000 <= System.currentTimeMillis()) return
            val ok = runCatching {
                AccountCall.run("renewTwoStepSession", mapOf("idToken" to token))
                FirebaseAuth.getInstance().currentUser?.getIdToken(true)?.await()
            }.isSuccess
            if (ok) return
        }
    }
}
