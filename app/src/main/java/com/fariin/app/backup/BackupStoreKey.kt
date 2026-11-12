package com.fariin.app.backup

import android.content.Context
import com.fariin.app.crypto.B64
import com.google.android.gms.auth.blockstore.Blockstore
import com.google.android.gms.auth.blockstore.RetrieveBytesRequest
import com.google.android.gms.auth.blockstore.StoreBytesData
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

/**
 * The platform-store key of the chat backup (archive v1 `wrapped.store`): 32 random bytes per
 * account, in Google Block Store with cloud backup on, so a new phone restored from the person's
 * Google backup can open the Drive archive with nothing typed. Block Store is wiped when the app is
 * uninstalled; a plain reinstall then needs the backup password (the app says so when backup is
 * turned on). Kept in the app's own preferences too, so this phone never depends on Block Store.
 */
object BackupStoreKey {
    private const val KEY = "fariin_backup_store_v1"
    private val lock = Mutex()

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("fariin_backup", Context.MODE_PRIVATE)

    private suspend fun readAll(ctx: Context): JSONObject = runCatching {
        val r = Blockstore.getClient(ctx).retrieveBytes(RetrieveBytesRequest.Builder().setKeys(listOf(KEY)).build()).await()
        r.blockstoreDataMap[KEY]?.bytes?.let { JSONObject(it.toString(Charsets.UTF_8)) }
    }.getOrNull() ?: JSONObject()

    /** This account's key: from this phone, else Block Store (a new phone), else null. */
    suspend fun find(ctx: Context, uid: String): ByteArray? = lock.withLock {
        prefs(ctx).getString("store.$uid", null)?.let { B64.decode(it) }?.let { return it }
        readAll(ctx).optString(uid).takeIf { it.isNotEmpty() }?.let { B64.decode(it) }?.also {
            prefs(ctx).edit().putString("store.$uid", B64.encode(it)).apply()
        }
    }

    /** The media key (backup media, iOS 7687219ef): one per account, kept like the store key under "m:<uid>". */
    suspend fun mediaKey(ctx: Context, uid: String, random: (Int) -> ByteArray): ByteArray = getOrMake(ctx, "m:$uid", random)

    /** A restored backup's media key becomes this account's (so the next backup reuses its files). */
    suspend fun adoptMediaKey(ctx: Context, uid: String, key: ByteArray) = lock.withLock {
        prefs(ctx).edit().putString("store.m:$uid", B64.encode(key)).apply()
        runCatching {
            val all = readAll(ctx).put("m:$uid", B64.encode(key))
            Blockstore.getClient(ctx).storeBytes(StoreBytesData.Builder().setKey(KEY)
                .setBytes(all.toString().toByteArray(Charsets.UTF_8)).setShouldBackupToCloud(true).build()).await()
        }
    }

    /** The key to back up with: the existing one, or a new one written to both places. */
    suspend fun getOrMake(ctx: Context, uid: String, random: (Int) -> ByteArray): ByteArray {
        find(ctx, uid)?.let { return it }
        return lock.withLock {
            val k = random(32)
            prefs(ctx).edit().putString("store.$uid", B64.encode(k)).apply()
            runCatching {
                val all = readAll(ctx).put(uid, B64.encode(k))
                Blockstore.getClient(ctx).storeBytes(StoreBytesData.Builder().setKey(KEY)
                    .setBytes(all.toString().toByteArray(Charsets.UTF_8)).setShouldBackupToCloud(true).build()).await()
            }
            k
        }
    }
}
