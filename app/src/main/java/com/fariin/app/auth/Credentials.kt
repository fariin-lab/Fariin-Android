package com.fariin.app.auth

import android.app.Activity
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.fariin.app.data.AuthFlowError
import com.fariin.app.data.AuthRepository
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject

/** The person closed the system sheet: say nothing, as iOS does for a cancelled sheet. */
class SheetCancelled : Exception("cancelled")

/**
 * Sign in with Google through the system Credential Manager ("Sign in with Google" button flow).
 * The ID token is for the project's web client (default_web_client_id from google-services.json),
 * which Firebase accepts for provider google.com; the Google account id inside it is the one the
 * iPhone's sign-in carries, so both open the same Fariin account.
 */
object GoogleSignIn {
    suspend fun idToken(activity: Activity): String {
        val option = GetSignInWithGoogleOption.Builder(AuthRepository.googleServerClientId(activity)).build()
        val req = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val cred = try {
            CredentialManager.create(activity).getCredential(activity, req).credential
        } catch (_: GetCredentialCancellationException) { throw SheetCancelled() }
        if (cred is CustomCredential && cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            return GoogleIdTokenCredential.createFrom(cred.data).idToken
        }
        throw AuthFlowError(AuthFlowError.Kind.GoogleFailed)
    }
}

/**
 * Kulan-iOS Views/PasskeysView.swift `Passkeys`, on Android's Credential Manager. The same server
 * ceremony (functions-account passkeys.js, relying party "fariin.com"): options from
 * passkeyRegistrationOptions / passkeyAuthOptions, the WebAuthn JSON back to *Verify, and a
 * custom token for sign-in. Android needs https://fariin.com/.well-known/assetlinks.json naming
 * this app, and the server must accept the app's `android:apk-key-hash:` origin (PROGRESS.md).
 */
object Passkeys {
    const val RELYING_PARTY = "fariin.com"
    private val fn get() = FirebaseFunctions.getInstance(AuthRepository.FUNCTIONS_REGION)

    private suspend fun call(name: String, data: Map<String, Any?> = emptyMap()): Map<*, *> =
        fn.getHttpsCallable(name).call(data).await().getData() as? Map<*, *> ?: emptyMap<String, Any>()

    /** One passkey for the signed-in account, saved in the phone's password manager. */
    suspend fun register(activity: Activity, auth: AuthRepository, label: String = "Passkey") {
        val start = call("passkeyRegistrationOptions")
        val options = start["options"] as? Map<*, *>
        val challengeId = start["challengeId"] as? String
        if (options == null || challengeId == null) throw IllegalStateException("Could not start adding a passkey.")
        val res = try {
            CredentialManager.create(activity).createCredential(activity, CreatePublicKeyCredentialRequest(toJson(options).toString()))
        } catch (_: CreateCredentialCancellationException) { throw SheetCancelled() }
        val json = (res as? CreatePublicKeyCredentialResponse)?.registrationResponseJson
            ?: throw IllegalStateException("That passkey could not be added.")
        call("passkeyRegistrationVerify", mapOf("challengeId" to challengeId, "response" to fromJson(JSONObject(json)), "label" to label) + auth.deviceFields())
    }

    /**
     * Sign in with a passkey this phone holds. [immediateOnly]: the quiet offer on the front door,
     * which shows nothing at all when there is none (Credential Manager's
     * preferImmediatelyAvailableCredentials, the iOS immediateOnly).
     */
    suspend fun signIn(activity: Activity, auth: AuthRepository, immediateOnly: Boolean = false) {
        val start = call("passkeyAuthOptions")
        val options = start["options"] as? Map<*, *>
        val challengeId = start["challengeId"] as? String
        if (options == null || challengeId == null) throw IllegalStateException("Could not start signing in.")
        val req = GetCredentialRequest(listOf(GetPublicKeyCredentialOption(toJson(options).toString())),
            preferImmediatelyAvailableCredentials = immediateOnly)
        val cred = try {
            CredentialManager.create(activity).getCredential(activity, req).credential
        } catch (_: GetCredentialCancellationException) { throw SheetCancelled() }
        catch (_: NoCredentialException) { throw SheetCancelled() }
        val json = (cred as? PublicKeyCredential)?.authenticationResponseJson ?: throw AuthFlowError(AuthFlowError.Kind.PasskeyFailed)
        val out = call("passkeyAuthVerify", mapOf("challengeId" to challengeId, "response" to fromJson(JSONObject(json))))
        val token = out["token"] as? String ?: throw AuthFlowError(AuthFlowError.Kind.PasskeyFailed)
        auth.signInWithToken(token)
    }

    data class Row(val id: String, val name: String, val createdMs: Double?, val lastUsedMs: Double?)

    suspend fun list(): List<Row> = (call("listPasskeys")["passkeys"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }.map {
        val provider = it["provider"] as? String ?: ""
        Row(it["id"] as? String ?: "", provider.ifEmpty { it["label"] as? String ?: "Passkey" },
            (it["createdAt"] as? Number)?.toDouble(), (it["lastUsedAt"] as? Number)?.toDouble())
    }

    suspend fun delete(id: String) { call("deletePasskey", mapOf("id" to id)) }

    private fun toJson(v: Any?): Any? = when (v) {
        is Map<*, *> -> JSONObject().apply { v.forEach { (k, x) -> put(k.toString(), toJson(x) ?: JSONObject.NULL) } }
        is List<*> -> JSONArray().apply { v.forEach { put(toJson(it) ?: JSONObject.NULL) } }
        else -> v
    }

    private fun fromJson(v: Any?): Any? = when (v) {
        is JSONObject -> v.keys().asSequence().associateWith { fromJson(v.get(it)) }
        is JSONArray -> (0 until v.length()).map { fromJson(v.get(it)) }
        JSONObject.NULL -> null
        else -> v
    }
}
