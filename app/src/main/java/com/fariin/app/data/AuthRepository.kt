package com.fariin.app.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/**
 * Sign-in state, a thin layer over FirebaseAuth. Mirrors the email part of iOS AuthService.
 *
 * TODO (iOS has these): Sign in with Apple, Google, passkeys, login by emailed code
 * (`requestLoginCode` / `signInWithLoginCode`), email sign-up with a verification code
 * (`createEmailAccount`), the new-login security message (`notifyNewLogin`), saved accounts.
 */
class AuthRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
) {
    val currentUser: FirebaseUser? get() = auth.currentUser

    /** The signed-in uid, or null, live. */
    val uid: Flow<String?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser?.uid) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    suspend fun signInEmail(email: String, password: String) {
        auth.signInWithEmailAndPassword(email.trim(), password).await()
    }

    /**
     * Our own reset email, as on iOS: the `requestPasswordReset` callable (region me-central1)
     * mails the link from fariin.com. It says nothing about whether the account exists.
     */
    suspend fun resetPassword(email: String) {
        FirebaseFunctions.getInstance(FUNCTIONS_REGION)
            .getHttpsCallable("requestPasswordReset")
            .call(mapOf("email" to email.trim()))
            .await()
    }

    fun signOut() = auth.signOut()

    companion object {
        const val FUNCTIONS_REGION = "me-central1"

        /** A short, plain message for a failed sign-in. */
        fun messageFor(e: Throwable): String = when (e) {
            is FirebaseAuthInvalidUserException,
            is FirebaseAuthInvalidCredentialsException -> "Wrong email or password."
            is FirebaseAuthException -> when (e.errorCode) {
                "ERROR_TOO_MANY_REQUESTS" -> "Too many tries. Wait a moment and try again."
                "ERROR_USER_DISABLED" -> "This account is turned off."
                else -> "Could not log in. Try again."
            }
            is com.google.firebase.FirebaseNetworkException -> "No connection. Check your internet."
            else -> "Could not log in. Try again."
        }
    }
}
