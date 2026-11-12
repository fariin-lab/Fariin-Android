package com.fariin.app.crypto

import android.content.Context
import com.google.android.gms.auth.blockstore.Blockstore
import com.google.android.gms.auth.blockstore.BlockstoreClient
import com.google.android.gms.auth.blockstore.RetrieveBytesRequest
import com.google.android.gms.auth.blockstore.StoreBytesData
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
 * {"v":1,"keys":{uid:{"sk","pk","made"}}}.
 */
object KeyBlockStore {
    private val lock = Mutex()
    private const val KEY = BlockstoreClient.DEFAULT_BYTES_DATA_KEY

    /** What is kept: the keypair and when it was made (ms, 0 = unknown), so the date rule holds. */
    data class Saved(val sk: ByteArray, val pk: ByteArray, val made: Long)

    private suspend fun readAll(context: Context): JSONObject {
        val res = Blockstore.getClient(context).retrieveBytes(RetrieveBytesRequest.Builder().setKeys(listOf(KEY)).build()).await()
        val bytes = res.blockstoreDataMap[KEY]?.bytes ?: return JSONObject()
        return runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrDefault(JSONObject())
    }

    private suspend fun writeAll(context: Context, all: JSONObject) {
        Blockstore.getClient(context).storeBytes(StoreBytesData.Builder()
            .setBytes(all.toString().toByteArray(Charsets.UTF_8))
            .setShouldBackupToCloud(true)
            .build()).await()
    }

    suspend fun save(context: Context, uid: String, sk: ByteArray, pk: ByteArray, made: Long) = lock.withLock {
        val all = readAll(context)
        val keys = all.optJSONObject("keys") ?: JSONObject()
        val mine = JSONObject().put("sk", B64.encode(sk)).put("pk", B64.encode(pk)).put("made", made)
        if (keys.optJSONObject(uid)?.toString() == mine.toString()) return@withLock
        keys.put(uid, mine)
        writeAll(context, all.put("v", 1).put("keys", keys))
        android.util.Log.i("FariinKeys", "key backed up")
    }

    suspend fun load(context: Context, uid: String): Saved? = lock.withLock {
        val j = readAll(context).optJSONObject("keys")?.optJSONObject(uid) ?: return@withLock null
        val sk = B64.decode(j.optString("sk")) ?: return@withLock null
        val pk = B64.decode(j.optString("pk")) ?: return@withLock null
        android.util.Log.i("FariinKeys", "key found in backup")
        Saved(sk, pk, j.optLong("made", 0L))
    }

    suspend fun delete(context: Context, uid: String) = lock.withLock {
        val all = readAll(context)
        val keys = all.optJSONObject("keys") ?: return@withLock
        if (!keys.has(uid)) return@withLock
        keys.remove(uid)
        writeAll(context, all.put("keys", keys))
    }
}
