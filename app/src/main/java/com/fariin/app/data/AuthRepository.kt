package com.fariin.app.data

import android.app.Activity
import android.content.Context
import android.os.Build
import com.fariin.app.R
import com.fariin.app.device.DeviceRegistry
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.AuthResult
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.OAuthProvider
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.DateFormat
import java.util.Date

/**
 * Sign-in and sign-up, a port of Kulan-iOS Services/AuthService.swift on the same Firebase project
 * and the same callables (all in me-central1), so one account opens from either platform:
 *  - email + password: Firebase's own sign-in; sign-up goes through the server
 *    (requestSignupCode -> verifySignupCode gives a ticket -> completeSignup gives a sign-in token),
 *  - Google: provider "google.com" from a Google ID token (Credential Manager here, the OAuth
 *    web flow on iOS; Google's account id is the same for both, so Firebase finds one account),
 *  - Apple: provider "apple.com" through Firebase's web flow (Apple's account id is the same for
 *    every app of one developer team, so it is the same Firebase account as on the iPhone),
 *  - a 6-digit emailed code (requestLoginCode / verifyLoginCode) for "Forgot password?" and the
 *    one-time "Confirm your email",
 *  - passkeys (auth/Passkeys.kt) and saved-account one-tap keys (DeviceSessionKeys).
 * Every success reports the login (notifyNewLogin, the new-login email) and remembers the door.
 */
class AuthRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
) {
    val currentUser: FirebaseUser? get() = auth.currentUser
    private val app: Context get() = FirebaseApp.getInstance().applicationContext
    private val fn: FirebaseFunctions get() = FirebaseFunctions.getInstance(FUNCTIONS_REGION)

    /** The signed-in uid, or null, live. */
    val uid: Flow<String?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser?.uid) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    private suspend fun call(name: String, data: Map<String, Any?> = emptyMap()): Map<*, *>? =
        fn.getHttpsCallable(name).call(data).await().getData() as? Map<*, *>

    // ---- email + password ---------------------------------------------------------------

    suspend fun signInEmail(email: String, password: String) {
        auth.signInWithEmailAndPassword(email.trim(), password).await()
        SavedAccounts.rememberMethod(app, SignInMethod.Email)
        reportLogin()
    }

    /**
     * AuthService.emailNeedsProof: an email-only account that never proved its address gets the
     * one-time code check after its password. Read fresh from the server, never from cache.
     */
    suspend fun emailNeedsProof(): Boolean {
        val user = auth.currentUser ?: return false
        if (user.providerData.map { it.providerId }.filter { it != "firebase" }.toSet() != setOf("password")) return false
        try { user.reload().await() } catch (_: Exception) {}
        return auth.currentUser?.isEmailVerified == false
    }

    /** The same check without the network, for the launch route (AuthService.unprovenEmailCached). */
    val unprovenEmailCached: String? get() {
        val user = auth.currentUser ?: return null
        if (user.isEmailVerified) return null
        val address = user.email?.takeIf { it.isNotEmpty() } ?: return null
        if (user.providerData.map { it.providerId }.filter { it != "firebase" }.toSet() != setOf("password")) return null
        return address
    }

    /** The server's own reset mail (requestPasswordReset). It says nothing about whether the account exists. */
    suspend fun resetPassword(email: String) { call("requestPasswordReset", mapOf("email" to email.trim())) }

    // ---- emailed codes -------------------------------------------------------------------

    /** requestLoginCode; `purpose: "confirm"` for the confirm-your-email check. */
    suspend fun requestLoginCode(email: String, confirm: Boolean = false) {
        val body = HashMap<String, Any>(mapOf("email" to email.trim()))
        if (confirm) body["purpose"] = "confirm"
        call("requestLoginCode", body)
    }

    /** verifyLoginCode answers a sign-in token; the code proves the address too. */
    suspend fun signInWithLoginCode(email: String, code: String): String {
        val d = call("verifyLoginCode", mapOf("email" to email.trim(), "code" to code))
        val token = d?.get("token") as? String ?: throw IllegalStateException("Could not sign you in. Try again.")
        val res = auth.signInWithCustomToken(token).await()
        try { res.user?.reload()?.await() } catch (_: Exception) {}
        reportLogin()
        return res.user?.uid ?: ""
    }

    suspend fun requestSignupCode(email: String) { call("requestSignupCode", mapOf("email" to email.trim())) }

    suspend fun verifySignupCode(email: String, code: String): String {
        val d = call("verifySignupCode", mapOf("email" to email.trim(), "code" to code))
        return d?.get("ticket") as? String ?: throw IllegalStateException("Could not check that code. Try again.")
    }

    /** completeSignup: the ticket (the proven address) plus the password make the account. */
    suspend fun completeSignup(ticket: String, password: String) {
        val d = try {
            call("completeSignup", mapOf("ticket" to ticket, "password" to password))
        } catch (e: FirebaseFunctionsException) {
            when (e.code) {
                FirebaseFunctionsException.Code.ALREADY_EXISTS -> throw AuthFlowError(AuthFlowError.Kind.EmailTaken)
                FirebaseFunctionsException.Code.PERMISSION_DENIED -> throw AuthFlowError(AuthFlowError.Kind.SignupExpired)
                else -> throw e
            }
        }
        val token = d?.get("token") as? String ?: throw IllegalStateException("Could not make your account. Try again.")
        auth.signInWithCustomToken(token).await()
        SavedAccounts.rememberMethod(app, SignInMethod.Email)
        reportLogin()
    }

    // ---- Google and Apple ----------------------------------------------------------------

    /** Apple's first sign-in carries the person's name once; onboarding fills it in. */
    var pendingDisplayName: String? = null

    /** What the sign-up door says when the Google/Apple identity already had an account. */
    var welcomeBack: String? = null

    /** The Google ID token from auth/GoogleSignIn, then the shared rule below. */
    suspend fun signInWithGoogleToken(idToken: String, requireExisting: Boolean, requireNew: Boolean) =
        authenticate({ auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await() },
            SignInMethod.Google, requireExisting, requireNew)

    /**
     * Apple through Firebase's web flow (a Custom Tab on appleid.apple.com). Firebase makes and
     * checks the nonce itself on this path. A flow already running (the app was killed while the
     * tab was up) is picked up rather than started twice.
     */
    suspend fun signInWithApple(activity: Activity, requireExisting: Boolean, requireNew: Boolean) =
        authenticate({
            val pending = auth.pendingAuthResult
            pending?.await() ?: auth.startActivityForSignInWithProvider(activity,
                OAuthProvider.newBuilder("apple.com").setScopes(listOf("email", "name")).build()).await()
        }, SignInMethod.Apple, requireExisting, requireNew)

    /**
     * AuthService.authenticate. The log-in door with an identity that never signed up: Firebase
     * has already made an empty account, so it is deleted again and the person is told. The
     * sign-up door with an identity that already has an account just lets them in ("Welcome back").
     */
    private suspend fun authenticate(signIn: suspend () -> AuthResult, door: SignInMethod, requireExisting: Boolean, requireNew: Boolean) {
        val result = signIn()
        val isNew = result.additionalUserInfo?.isNewUser == true
        if (requireExisting && isNew) {
            try { result.user?.delete()?.await() } catch (_: Exception) {}
            auth.signOut()
            throw AuthFlowError(AuthFlowError.Kind.NoAccount)
        }
        if (door == SignInMethod.Apple) result.user?.displayName?.takeIf { it.isNotBlank() }?.let { pendingDisplayName = it }
        if (requireNew && !isNew) {
            val first = result.user?.displayName?.split(" ")?.firstOrNull()?.takeIf { it.isNotBlank() }
            welcomeBack = first?.let { "Welcome back, $it" } ?: "Welcome back"
        }
        SavedAccounts.rememberMethod(app, door)
        reportLogin()
    }

    /** A custom token from a passkey or a saved account's key. */
    suspend fun signInWithToken(token: String) {
        auth.signInWithCustomToken(token).await()
        reportLogin()
    }

    // ---- password (Settings > Account > Password, and after a code sign-in) -----------------

    /** AuthService.passwordAddress: the address a password belongs to, a typable one first. */
    val passwordAddress: String? get() {
        val user = auth.currentUser ?: return null
        val primary = user.email?.takeIf { user.isEmailVerified && it.isNotEmpty() }
        val all = listOfNotNull(primary) + user.providerData.mapNotNull { it.email?.takeIf { e -> e.isNotEmpty() } }
        return all.firstOrNull { !EmailRules.isRelayAddress(it) } ?: all.firstOrNull()
    }

    val hasTypableAddress: Boolean get() = passwordAddress?.let { it.isNotEmpty() && !EmailRules.isRelayAddress(it) } == true
    val hasPassword: Boolean get() = auth.currentUser?.providerData?.any { it.providerId == "password" } == true

    /** AuthService.setPassword: the first one links an email credential, later ones replace it. */
    suspend fun setPassword(newPassword: String, isFirst: Boolean) {
        val user = auth.currentUser ?: throw AuthFlowError(AuthFlowError.Kind.NotSignedIn)
        if (isFirst && !hasPassword) {
            val address = passwordAddress?.takeIf { it.isNotEmpty() } ?: throw AuthFlowError(AuthFlowError.Kind.NotSignedIn)
            user.linkWithCredential(EmailAuthProvider.getCredential(address, newPassword)).await()
        } else {
            user.updatePassword(newPassword).await()
        }
        try { user.reload().await() } catch (_: Exception) {}
        val stamp = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date())
        try { call("notifyPasswordChanged", mapOf("when" to stamp, "first" to isFirst) + deviceFields()) } catch (_: Exception) {}
    }

    /** reauthEmail: prove the current password before changing it. */
    suspend fun reauthEmail(password: String) {
        val user = auth.currentUser ?: throw AuthFlowError(AuthFlowError.Kind.NotSignedIn)
        val address = user.providerData.firstOrNull { it.providerId == "password" }?.email ?: passwordAddress
            ?: throw AuthFlowError(AuthFlowError.Kind.NotSignedIn)
        user.reauthenticate(EmailAuthProvider.getCredential(address, password)).await()
    }

    /** startPasswordReset (signed in): mails a code, answers the masked address. */
    suspend fun startPasswordReset(): String? = call("startPasswordReset")?.get("maskedEmail") as? String
    suspend fun checkPasswordResetCode(code: String) { call("checkPasswordResetCode", mapOf("code" to code)) }
    suspend fun confirmPasswordReset(code: String, newPassword: String) {
        call("confirmPasswordReset", mapOf("code" to code, "newPassword" to newPassword) + deviceFields())
    }

    // ---- login report -----------------------------------------------------------------------

    /** AuthService.deviceFields, the words the new-login and password emails use for this phone. */
    fun deviceFields(): Map<String, String> = mapOf(
        "deviceId" to DeviceRegistry.thisDeviceId(app),
        "app" to "Fariin ${appVersion()}",
        "device" to Build.MODEL,
        "os" to "Android ${Build.VERSION.RELEASE}",
    )

    /** AuthService.reportLogin: notifyNewLogin mails the account "a new login" with these words. Best effort. */
    fun reportLogin() {
        if (auth.currentUser == null) return
        val fields = deviceFields()
        bg.launch { try { call("notifyNewLogin", fields) } catch (_: Exception) {} }
    }

    private fun appVersion(): String = try {
        val p = app.packageManager.getPackageInfo(app.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else @Suppress("DEPRECATION") p.versionCode.toLong()
        "${p.versionName} ($code)"
    } catch (_: Exception) { "1.0" }

    fun signOut() = auth.signOut()

    companion object {
        const val FUNCTIONS_REGION = "me-central1"
        private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun googleServerClientId(c: Context): String = c.getString(R.string.default_web_client_id)

        /** A short, plain message for a failed email sign-in (iOS plainMessage). */
        fun messageFor(e: Throwable, credentialHint: String = "Wrong email or password."): String = when (e) {
            is AuthFlowError -> e.kind.message
            is FirebaseNetworkException -> AuthMessages.OFFLINE_SHORT
            is FirebaseAuthException -> AuthMessages.forAuthCode(e.errorCode, credentialHint) ?: (e.message ?: "Could not log in. Try again.")
            is FirebaseFunctionsException -> AuthMessages.forServer(e.message)
            else -> e.message?.takeIf { it.isNotBlank() } ?: "Could not log in. Try again."
        }

        /** The doors page's words (Apple, Google, a saved card). */
        fun doorMessageFor(e: Throwable): String = when (e) {
            is AuthFlowError -> e.kind.message
            is FirebaseNetworkException -> AuthMessages.OFFLINE_SHORT
            is FirebaseAuthException -> AuthMessages.forDoorCode(e.errorCode)
            else -> "Couldn't sign in. Please try again."
        }

        /** The code page's words: the server's sentence, or a plain fallback. */
        fun codeMessageFor(e: Throwable): String = when (e) {
            is FirebaseNetworkException -> AuthMessages.OFFLINE
            is FirebaseFunctionsException ->
                if (e.code == FirebaseFunctionsException.Code.UNAVAILABLE || e.code == FirebaseFunctionsException.Code.DEADLINE_EXCEEDED)
                    AuthMessages.OFFLINE else AuthMessages.forServer(e.message)
            is FirebaseAuthException -> AuthMessages.forAuthCode(e.errorCode) ?: AuthMessages.SOMETHING_WRONG
            else -> AuthMessages.forServer(e.message)
        }

        /** The Firebase web flow's "the person closed the tab". */
        fun isCancelled(e: Throwable): Boolean =
            (e is FirebaseAuthException && e.errorCode == "ERROR_WEB_CONTEXT_CANCELED") ||
                e.javaClass.simpleName.contains("Cancellation")
    }
}
