package com.fariin.app.crypto

import android.content.Context
import com.google.android.gms.auth.blockstore.Blockstore
import com.google.android.gms.auth.blockstore.BlockstoreClient
import com.google.android.gms.auth.blockstore.RetrieveBytesRequest
import com.google.android.gms.auth.blockstore.StoreBytesData
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

/**
 * The identity key's automatic backup on Android (owner 2026-11-11: a reinstall lost his key and
 * every older private photo; iOS does the same with iCloud Keychain). Google's Block Store keeps a
 * few bytes per app; only this app (its signing key) can read them, and with cloud backup on they
 * move to a new phone restored from the same Google account, end-to-end encrypted with its screen
 * lock.
 *
 * ONE KEYLESS ENTRY on purpose: on the emulator's Play services a named entry was deleted with the
 * app ("Removed Blockstore data ... upon uninstallation"), while the keyless one is kept as the last
 * installation's data for the next install. So every account on this phone shares that one entry:
 * {"v":1,"keys":{uid:{"sk","pk","made"}}}. Each account's entry leaves with its Log Out (L-100).
 *
 * J-5: a read that failed is told apart from one that found nothing ([KeyBackupRules.Read]), and a
 * saved key is only replaced under [KeyBackupRules.writeStep].
 */
object KeyBlockStore {
    private val lock = Mutex()
    private const val KEY = BlockstoreClient.DEFAULT_BYTES_DATA_KEY

    /** What is kept: the keypair and when it was made (ms, 0 = unknown), so the date rule holds. */
    data class Saved(val sk: ByteArray, val pk: ByteArray, val made: Long)

    /** The whole entry, or the reason it could not be read (then nothing may be written). */
    private class All(val json: JSONObject?, val failed: KeyBackupRules.Read?)

    private suspend fun readAll(context: Context): All = try {
        val res = Blockstore.getClient(context).retrieveBytes(RetrieveBytesRequest.Builder().setKeys(listOf(KEY)).build()).await()
        val bytes = res.blockstoreDataMap[KEY]?.bytes
        // Bytes that are not our JSON hold nothing usable: they count as empty.
        All(if (bytes == null) JSONObject() else runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrDefault(JSONObject()), null)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        android.util.Log.w("FariinKeys", "key backup not readable now: ${e.javaClass.simpleName}")
        All(null, if (e is ApiException && KeyBackupRules.noService(e.statusCode)) KeyBackupRules.Read.NoService
            else KeyBackupRules.Read.Failed)
    }

    private fun entry(all: JSONObject, uid: String): KeyBackupRules.Read {
        val j = all.optJSONObject("keys")?.optJSONObject(uid) ?: return KeyBackupRules.Read.Empty
        val sk = B64.decode(j.optString("sk"))
        val pk = B64.decode(j.optString("pk"))
        val valid = sk != null && pk != null && sk.size == NaCl.SECRET_KEY_BYTES && pk.size == 32
        return KeyBackupRules.Read.Found(sk ?: ByteArray(0), pk ?: ByteArray(0), j.optLong("made", 0L), valid)
    }

    private suspend fun writeAll(context: Context, all: JSONObject) {
        Blockstore.getClient(context).storeBytes(StoreBytesData.Builder()
            .setBytes(all.toString().toByteArray(Charsets.UTF_8))
            .setShouldBackupToCloud(true)
            .build()).await()
    }

    /** Saves my key when [KeyBackupRules.writeStep] allows it. Throws when the entry could not be
     *  read, so the caller tries again on a later start. [adopted]: the person chose this key. */
    suspend fun save(context: Context, uid: String, sk: ByteArray, pk: ByteArray, made: Long, adopted: Boolean = false) = lock.withLock {
        val all = readAll(context)
        val json = all.json ?: throw CryptoException("key backup not readable now")
        when (KeyBackupRules.writeStep(entry(json, uid), pk, made, adopted)) {
            KeyBackupRules.Write.SKIP -> return@withLock
            KeyBackupRules.Write.KEEP_OTHER -> {
                android.util.Log.w("FariinKeys", "backup holds another key for this account: kept"); return@withLock
            }
            KeyBackupRules.Write.WRITE -> Unit
        }
        val keys = json.optJSONObject("keys") ?: JSONObject()
        keys.put(uid, JSONObject().put("sk", B64.encode(sk)).put("pk", B64.encode(pk)).put("made", made))
        writeAll(context, json.put("v", 1).put("keys", keys))
        android.util.Log.i("FariinKeys", "key backed up")
    }

    /** This account's entry, or why there is none ([KeyBackupRules.Read]). */
    suspend fun read(context: Context, uid: String): KeyBackupRules.Read = lock.withLock {
        val all = readAll(context)
        val r = all.json?.let { entry(it, uid) } ?: all.failed ?: KeyBackupRules.Read.Failed
        if (r is KeyBackupRules.Read.Found && r.valid) android.util.Log.i("FariinKeys", "key found in backup")
        r
    }

    /** This account's entry only; every other account's stays. Throws when it could not be read. */
    suspend fun delete(context: Context, uid: String) = lock.withLock {
        val all = readAll(context).json ?: throw CryptoException("key backup not readable now")
        val keys = all.optJSONObject("keys") ?: return@withLock
        if (!keys.has(uid)) return@withLock
        keys.remove(uid)
        writeAll(context, all.put("keys", keys))
    }
}
