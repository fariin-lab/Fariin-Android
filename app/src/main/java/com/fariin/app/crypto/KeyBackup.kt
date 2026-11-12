package com.fariin.app.crypto

import android.content.Context
import com.fariin.app.data.AccountCall
import com.fariin.app.data.AccountCallError
import com.fariin.app.data.CryptoHub
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The chat key backup with a PIN (2026-11-12). Without it a reinstall or a new phone makes a new
 * key and every older message stays closed. The key is locked here, on the phone, with a key made
 * from the PIN; the server (functions-account key-backup.js) keeps the locked box and a hash of a
 * second half it can check, never the PIN and never anything that opens the box.
 *
 * Shared format with iOS (Documents/fariin-plans/to-ios/2026-11-12-pin-key-backup.md):
 *   salt 16 random bytes; Argon2id13 ops 3, mem 64 MiB; 64 bytes out:
 *   [0,32) lock key (secretbox), [32,64) auth (sent to the server, which keeps sha256 of it).
 *   PIN bytes = the digits as UTF-8. Box = secretbox(JSON {"v":1,"sk","pk","made"}), base64 fields.
 */
object KeyBackup {
    const val MIN_PIN = 6
    private const val OPS = 3L
    private const val MEM = 64L * 1024 * 1024

    data class Status(val exists: Boolean, val salt: ByteArray?, val ops: Long, val mem: Long, val pk: String?,
                      val updatedAt: Long, val triesLeft: Int, val lockedUntil: Long)

    sealed class Restore {
        object Done : Restore()
        data class WrongPin(val triesLeft: Int, val lockedUntil: Long) : Restore()
        data class Locked(val until: Long) : Restore()
        object Wiped : Restore()
        object None : Restore()
        data class Failed(val message: String) : Restore()
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("fariin_key_backup", Context.MODE_PRIVATE)

    /** What this phone last heard (for the Privacy row without a server trip). */
    fun cachedOn(ctx: Context, uid: String): Boolean? =
        prefs(ctx).takeIf { it.contains("on.$uid") }?.getBoolean("on.$uid", false)

    private fun remember(ctx: Context, uid: String, on: Boolean) = prefs(ctx).edit().putBoolean("on.$uid", on).apply()

    suspend fun status(ctx: Context, uid: String): Status? = try {
        val r = AccountCall.run("keyBackupStatus")
        val exists = r["exists"] == true
        remember(ctx, uid, exists)
        Status(exists, (r["salt"] as? String)?.let { B64.decode(it) }, (r["ops"] as? Number)?.toLong() ?: OPS,
            (r["mem"] as? Number)?.toLong() ?: MEM, r["pk"] as? String, (r["updatedAt"] as? Number)?.toLong() ?: 0L,
            (r["triesLeft"] as? Number)?.toInt() ?: 0, (r["lockedUntil"] as? Number)?.toLong() ?: 0L)
    } catch (_: Exception) { null }

    private suspend fun derive(pin: String, salt: ByteArray, ops: Long, mem: Long, crypto: FariinCrypto): Pair<ByteArray, ByteArray>? =
        withContext(Dispatchers.Default) {
            crypto.pinHash(64, pin.toByteArray(Charsets.UTF_8), salt, ops, mem)?.let { it.copyOfRange(0, 32) to it.copyOfRange(32, 64) }
        }

    /** Set or change the PIN. Null on success, else the words to show. */
    suspend fun save(ctx: Context, uid: String, pin: String): String? {
        val crypto = CryptoHub.get(ctx)
        val (sk, pk, made) = crypto.identityForBackup() ?: return "Your chat key is not ready yet. Try again in a moment."
        val salt = crypto.randomBytes(16)
        val (lock, auth) = derive(pin, salt, OPS, MEM, crypto) ?: return "Could not make the backup on this phone."
        val plain = JSONObject().put("v", 1).put("sk", B64.encode(sk)).put("pk", B64.encode(pk)).put("made", made).toString()
        val (nonce, box) = crypto.sealWith(lock, plain.toByteArray(Charsets.UTF_8)) ?: return "Could not make the backup on this phone."
        // The server checks the public half against the published key: make sure it is out first.
        runCatching { crypto.publishPublicKey() }
        return try {
            AccountCall.run("keyBackupSave", mapOf("salt" to B64.encode(salt), "ops" to OPS, "mem" to MEM,
                "auth" to B64.encode(auth), "nonce" to B64.encode(nonce), "box" to B64.encode(box), "pk" to B64.encode(pk)))
            remember(ctx, uid, true); null
        } catch (e: AccountCallError) { e.message }
    }

    /** Open the backup with [pin] and take its key. */
    suspend fun restore(ctx: Context, uid: String, pin: String): Restore {
        val crypto = CryptoHub.get(ctx)
        val st = status(ctx, uid) ?: return Restore.Failed(AccountCallError.LATER)
        if (!st.exists) return Restore.None
        if (st.lockedUntil > System.currentTimeMillis()) return Restore.Locked(st.lockedUntil)
        val salt = st.salt ?: return Restore.Failed("This backup cannot be read.")
        val (lock, auth) = derive(pin, salt, st.ops, st.mem, crypto) ?: return Restore.Failed("This backup cannot be read.")
        val r = try {
            com.google.firebase.functions.FirebaseFunctions.getInstance(com.fariin.app.data.AuthRepository.FUNCTIONS_REGION)
                .getHttpsCallable("keyBackupRestore").call(mapOf("auth" to B64.encode(auth))).await().getData() as? Map<*, *> ?: emptyMap<String, Any>()
        } catch (e: FirebaseFunctionsException) {
            val d = e.details as? Map<*, *>
            return when (e.code) {
                FirebaseFunctionsException.Code.PERMISSION_DENIED ->
                    if (d?.containsKey("triesLeft") == true) Restore.WrongPin((d["triesLeft"] as? Number)?.toInt() ?: 0, (d["lockedUntil"] as? Number)?.toLong() ?: 0L)
                    else Restore.Failed(e.message ?: AccountCallError.LATER)
                FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED -> Restore.Locked((d?.get("lockedUntil") as? Number)?.toLong() ?: 0L)
                FirebaseFunctionsException.Code.NOT_FOUND -> { remember(ctx, uid, false); if (d?.get("wiped") == true) Restore.Wiped else Restore.None }
                else -> Restore.Failed(AccountCallError.LATER)
            }
        } catch (_: Exception) { return Restore.Failed(AccountCallError.LATER) }
        val nonce = (r["nonce"] as? String)?.let { B64.decode(it) }
        val box = (r["box"] as? String)?.let { B64.decode(it) }
        val plain = (if (nonce != null && box != null) crypto.openWith(lock, nonce, box) else null)
            ?: return Restore.Failed("This backup cannot be opened.")
        val j = runCatching { JSONObject(plain.toString(Charsets.UTF_8)) }.getOrNull() ?: return Restore.Failed("This backup cannot be opened.")
        val sk = B64.decode(j.optString("sk")); val pk = B64.decode(j.optString("pk"))
        if (sk == null || pk == null || !crypto.adoptIdentity(sk, pk, j.optLong("made", 0L)))
            return Restore.Failed("This backup cannot be opened.")
        prefs(ctx).edit().remove("skip.$uid").apply()
        return Restore.Done
    }

    suspend fun delete(ctx: Context, uid: String): String? = try {
        AccountCall.run("keyBackupDelete"); remember(ctx, uid, false); null
    } catch (e: AccountCallError) { e.message }

    /**
     * After sign-in: a backup whose key is not the one on this phone (a reinstall, a new phone)
     * is offered once per backup; "Skip" is remembered for that backup only.
     */
    suspend fun shouldOffer(ctx: Context, uid: String): Status? {
        val mine = CryptoHub.get(ctx).myPublicKeyB64 ?: return null
        val st = status(ctx, uid) ?: return null
        if (!st.exists || st.pk == null || st.pk == mine) return null
        if (prefs(ctx).getLong("skip.$uid", -1L) == st.updatedAt) return null
        return st
    }

    fun skip(ctx: Context, uid: String, st: Status) = prefs(ctx).edit().putLong("skip.$uid", st.updatedAt).apply()
}
