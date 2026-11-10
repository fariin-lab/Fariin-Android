package com.fariin.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.fariin.app.device.DeviceRegistry
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Kulan-iOS Services/LastAccount.swift: the accounts this phone has used, newest first, at most
 * five, each with its name, handle, door, a small photo and (email door only) the address. It
 * survives sign-out on purpose (that is the feature) and goes when the account is removed from
 * its card or deleted. Kept in app prefs + files/LastAccount, which the backup rules leave out.
 */
object SavedAccounts {
    private const val PREFS = "fariin_accounts"
    private const val KEY = "lastAccounts.v1"
    private const val LAST_METHOD = "auth.lastSignInMethod"

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun dir(c: Context) = File(c.applicationContext.noBackupFilesDir, "LastAccount").apply { mkdirs() }
    private fun photoFile(c: Context, uid: String) = File(dir(c), uid.filter { it.isLetterOrDigit() } + ".jpg")

    fun all(c: Context): List<SavedAccount> = try {
        val arr = JSONArray(prefs(c).getString(KEY, "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            SavedAccount(
                uid = o.getString("uid"), name = o.optString("name"), handle = o.optString("handle"),
                method = o.optString("method"), email = o.optString("email").ifEmpty { null },
                verified = if (o.has("verified")) o.getBoolean("verified") else null,
                gold = if (o.has("gold")) o.getBoolean("gold") else null,
            )
        }
    } catch (_: Exception) { emptyList() }

    private fun save(c: Context, list: List<SavedAccount>) {
        val arr = JSONArray()
        for (a in list.take(SavedAccount.LIMIT)) arr.put(JSONObject().apply {
            put("uid", a.uid); put("name", a.name); put("handle", a.handle); put("method", a.method)
            a.email?.let { put("email", it) }; a.verified?.let { put("verified", it) }; a.gold?.let { put("gold", it) }
        })
        prefs(c).edit().putString(KEY, arr.toString()).apply()
    }

    fun photo(c: Context, uid: String): Bitmap? =
        photoFile(c, uid).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    /** AuthService.lastSignInMethod: the last door used on this phone, by any account. */
    fun lastMethod(c: Context): SignInMethod? = SignInMethod.of(prefs(c).getString(LAST_METHOD, null))
    fun rememberMethod(c: Context, m: SignInMethod) { prefs(c).edit().putString(LAST_METHOD, m.raw).apply() }

    /**
     * LastAccount.remember, called while signed in whenever the profile changes. Cards pushed off
     * the end take their photo and their one-tap key with them. [photoJpeg] null keeps the copy,
     * an empty array (no photo on the profile) removes it.
     */
    fun remember(c: Context, profile: MyProfile, verified: Boolean = false, gold: Boolean = false, photoJpeg: ByteArray? = null) {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        if (user.uid != profile.uid || profile.handle.isEmpty()) return
        val providers = user.providerData.map { it.providerId }.toSet()
        val door = SignInMethod.doorOf(providers, lastMethod(c)) ?: return
        val info = SavedAccount(user.uid, profile.name, profile.handle, door.raw,
            email = if (door == SignInMethod.Email) user.email else null, verified = verified, gold = gold)
        val (next, dropped) = SavedAccount.upsert(all(c), info)
        if (dropped.isNotEmpty() || next != all(c)) {
            for (d in dropped) { photoFile(c, d.uid).delete(); DeviceSessionKeys.forget(c, d.uid) }
            save(c, next)
        }
        when {
            photoJpeg == null -> {}
            photoJpeg.isEmpty() -> photoFile(c, user.uid).delete()
            else -> try { photoFile(c, user.uid).writeBytes(photoJpeg) } catch (_: Exception) {}
        }
    }

    /** One account off this phone (its card's menu, or the account was deleted). */
    fun forget(c: Context, uid: String) {
        DeviceSessionKeys.forget(c, uid)
        save(c, all(c).filter { it.uid != uid })
        photoFile(c, uid).delete()
    }

    /** A small square copy (160 px, JPEG 80) of a photo for the card. */
    fun cardJpeg(bytes: ByteArray): ByteArray? = try {
        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: null
        src?.let {
            val side = minOf(it.width, it.height)
            val sq = Bitmap.createBitmap(it, (it.width - side) / 2, (it.height - side) / 2, side, side)
            val small = Bitmap.createScaledBitmap(sq, 160, 160, true)
            java.io.ByteArrayOutputStream().also { o -> small.compress(Bitmap.CompressFormat.JPEG, 80, o) }.toByteArray()
        }
    } catch (_: Exception) { null }
}

/**
 * Kulan-iOS Services/DeviceSessionKeys.swift: one-tap sign-in for a saved account. The server
 * (functions-account issueDeviceSession / resumeDeviceSession) gives this phone a secret per
 * account; a tap on the card sends it back for a sign-in token. The secret is sealed with an
 * Android Keystore AES key (this device only, never in a backup), standing in for the iOS
 * Keychain item "AfterFirstUnlockThisDeviceOnly".
 */
object DeviceSessionKeys {
    private const val PREFS = "fariin_device_sessions"
    private const val ALIAS = "fariin.deviceSession"
    private val fn get() = FirebaseFunctions.getInstance(AuthRepository.FUNCTIONS_REGION)

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun aesKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return gen.generateKey()
    }

    fun key(c: Context, uid: String): String? = try {
        prefs(c).getString(uid, null)?.let { stored ->
            val raw = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, aesKey(), GCMParameterSpec(128, raw, 0, 12))
            String(cipher.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
        }
    } catch (_: Exception) { null }

    fun store(c: Context, uid: String, secret: String) {
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, aesKey())
            val out = cipher.iv + cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
            prefs(c).edit().putString(uid, Base64.encodeToString(out, Base64.NO_WRAP)).apply()
        } catch (_: Exception) {}
    }

    fun forget(c: Context, uid: String) { prefs(c).edit().remove(uid).apply() }

    /**
     * Signed in: end other phones' one-tap keys (iOS DeviceSessionKeys.revoke / revokeAllOthers), so a
     * phone signed out from the Devices page asks for the password next time instead of signing
     * straight back in. Best effort.
     */
    suspend fun revoke(deviceIds: List<String>) {
        try { fn.getHttpsCallable("revokeDeviceSessions").call(mapOf("deviceIds" to deviceIds)).await() } catch (_: Exception) {}
    }

    suspend fun revokeAllOthers(c: Context) {
        try { fn.getHttpsCallable("revokeDeviceSessions").call(mapOf("allExcept" to DeviceRegistry.thisDeviceId(c))).await() } catch (_: Exception) {}
    }

    /** Signed in: make sure this phone holds a key for this account. Cheap when it already does. */
    suspend fun ensureIssued(c: Context) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        if (key(c, uid) != null) return
        val deviceId = DeviceRegistry.thisDeviceId(c)
        val secret = try {
            (fn.getHttpsCallable("issueDeviceSession").call(mapOf("deviceId" to deviceId)).await().getData() as? Map<*, *>)?.get("secret") as? String
        } catch (_: Exception) { null } ?: return
        store(c, uid, secret)
    }

    /**
     * Signed out: sign in to [uid] with its key. False when there is no key or the server refused
     * it (expired, password changed, signed out remotely); only a real refusal forgets the key.
     */
    suspend fun resume(c: Context, uid: String): Boolean {
        val secret = key(c, uid) ?: return false
        val deviceId = DeviceRegistry.thisDeviceId(c)
        return try {
            val d = fn.getHttpsCallable("resumeDeviceSession")
                .call(mapOf("uid" to uid, "deviceId" to deviceId, "secret" to secret)).await().getData() as? Map<*, *>
            val token = d?.get("token") as? String ?: return false
            (d["secret"] as? String)?.let { store(c, uid, it) }
            FirebaseAuth.getInstance().signInWithCustomToken(token).await()
            FirebaseAuth.getInstance().currentUser?.uid == uid
        } catch (e: Exception) {
            if (e is FirebaseFunctionsException && e.code == FirebaseFunctionsException.Code.FAILED_PRECONDITION) forget(c, uid)
            false
        }
    }
}
