package com.fariin.app.backup

import android.content.Context
import com.fariin.app.data.CryptoHub
import com.fariin.app.local.LocalDb
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Chat backup to the person's own Google Drive (device-storage plan phase 3, flows A-D in
 * Documents/fariin-plans/backup-flow-study.md; same rules as iOS ChatBackup).
 *
 * OFF ([ENABLED]) until the Google Drive setup on the Fariin Cloud project is done and tested
 * (Drive API on, consent screen in production with drive.appdata, Android OAuth clients).
 *
 * A backup is a SET of files (BackupArchive) tagged appProperties.set = its `made`: the new set goes
 * up whole (header.json last), then the older sets are deleted. A set without its header.json is
 * unfinished and ignored. Safety rules agreed with iOS:
 *  - never an automatic backup on a phone with no history or while a skipped restore still waits
 *    (an empty phone must never replace a full backup);
 *  - the restore offer is decided from META only, before inbox sync or the history pull file anything;
 *  - "restored" only when every counted message was read.
 */
object ChatBackup {
    /** ON in test builds (the owner's Pixel and testers), OFF in store releases until the owner's test passes. */
    @Volatile var ENABLED: Boolean = false   // set at start from R.bool.fariin_test_build (FariinApp)
    private val lock = Mutex()

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("fariin_backup", Context.MODE_PRIVATE)

    fun lastBackup(ctx: Context, uid: String): Long = prefs(ctx).getLong("last.$uid", 0L)

    /** How often: "daily" (default once on), "weekly" or "off". */
    fun frequency(ctx: Context, uid: String): String = prefs(ctx).getString("freq.$uid", "off") ?: "off"
    fun setFrequency(ctx: Context, uid: String, f: String) { prefs(ctx).edit().putString("freq.$uid", f).apply(); BackupWorker.schedule(ctx, uid) }
    fun mobileData(ctx: Context, uid: String): Boolean = prefs(ctx).getBoolean("cell.$uid", false)
    fun setMobileData(ctx: Context, uid: String, on: Boolean) { prefs(ctx).edit().putBoolean("cell.$uid", on).apply(); BackupWorker.schedule(ctx, uid) }

    /** The password / 64-digit key wrap kept on this phone (the Argon2 result and its salt), so
     *  daily backups need nothing typed. Never leaves the phone. */
    fun hasPassword(ctx: Context, uid: String): Boolean = prefs(ctx).contains("pw.$uid")
    private fun passwordWrap(ctx: Context, uid: String): Pair<ByteArray, ByteArray>? {
        val k = prefs(ctx).getString("pw.$uid", null)?.let { com.fariin.app.crypto.B64.decode(it) } ?: return null
        val s = prefs(ctx).getString("pwsalt.$uid", null)?.let { com.fariin.app.crypto.B64.decode(it) } ?: return null
        return k to s
    }
    suspend fun setPassword(ctx: Context, uid: String, password: String?) {
        val e = prefs(ctx).edit()
        if (password.isNullOrEmpty()) e.remove("pw.$uid").remove("pwsalt.$uid")
        else {
            val c = CryptoHub.get(ctx)
            val salt = c.randomBytes(16)
            val pk = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { BackupArchive.passwordKey(c, password, salt) } ?: return
            e.putString("pw.$uid", com.fariin.app.crypto.B64.encode(pk)).putString("pwsalt.$uid", com.fariin.app.crypto.B64.encode(salt))
        }
        e.apply()
    }
    fun lastSize(ctx: Context, uid: String): Long = prefs(ctx).getLong("size.$uid", 0L)

    sealed class Result {
        object Done : Result()
        class NeedsGoogle(val ask: Drive.Auth.Ask) : Result()
        class Failed(val message: String) : Result()
        object NothingToBackUp : Result()
    }

    /** "Drive is full", "can't reach" and the rest, in plain words. */
    private fun words(e: Throwable): String = when {
        e is Drive.DriveError && e.code == 403 && e.message?.contains("storageQuota", true) == true -> "Your Google Drive is full."
        e is Drive.DriveError && (e.code == 401 || e.code == 403) -> "Fariin can't reach your Google Drive."
        else -> "The backup didn't finish. It will try again."
    }

    /** Asks the sign-in gate to look again (the Restore circle). */
    val reopen = kotlinx.coroutines.flow.MutableStateFlow(0)

    /** The Back Up circle: Not Now on the backup card was 7+ days ago, backup is still off, not hidden. */
    fun backUpCircle(ctx: Context, uid: String): Boolean {
        if (!ENABLED || frequency(ctx, uid) != "off") return false
        val asked = LocalDb.meta(uid, "backup.asked") ?: return false
        val at = asked.removePrefix("later:").toLongOrNull() ?: return false
        return asked.startsWith("later:") && System.currentTimeMillis() - at >= 7L * 24 * 60 * 60 * 1000
    }
    fun hideBackUpCircle(uid: String) = LocalDb.setMeta(uid, "backup.asked", "dismissed")

    /** True when a restore was offered and skipped and that backup still waits (no automatic backup then). */
    fun restoreWaiting(ctx: Context, uid: String): Boolean = !LocalDb.meta(uid, "restore.waiting").isNullOrEmpty()

    /**
     * Makes a backup now. [automatic] runs the safety rules (no history, a waiting restore); a
     * manual Back Up Now asks the person first in that case (the screen's job).
     */
    suspend fun backUp(ctx: Context, uid: String, automatic: Boolean, token: String? = null): Result = lock.withLock {
        if (!ENABLED) return Result.Failed("Chat backup is not on yet.")
        if (automatic && (!LocalDb.hasSyncedMarks(uid) || restoreWaiting(ctx, uid))) return Result.NothingToBackUp
        val tok = token ?: when (val a = Drive.authorize(ctx)) {
            is Drive.Auth.Token -> a.value
            is Drive.Auth.Ask -> return Result.NeedsGoogle(a)
            Drive.Auth.Failed -> return Result.Failed("Fariin can't reach your Google Drive.")
        }
        val c = CryptoHub.get(ctx)
        return try {
            val storeKey = BackupStoreKey.getOrMake(ctx, uid) { c.randomBytes(it) }
            val built = BackupArchive.build(c, uid, storeKey, passwordWrap(ctx, uid))
            val set = built.made.toString()
            val before = Drive.list(tok)
            var size = 0L
            for ((name, bytes) in built.files) { Drive.upload(tok, name, set, bytes); size += bytes.size }
            // The new set is whole (its header went last): every older file goes.
            for (f in before) if (f.set != set) Drive.delete(tok, f.id)
            prefs(ctx).edit().putLong("last.$uid", built.made).putLong("size.$uid", size).apply()
            Result.Done
        } catch (e: Exception) { Result.Failed(words(e)) }
    }

    class Found(val made: Long, val size: Long, val needsPassword: Boolean, internal val files: Map<String, String>, internal val header: BackupArchive.Header)

    /** The newest finished backup in the person's Drive, or null. */
    suspend fun find(ctx: Context, uid: String, token: String): Found? {
        if (!ENABLED) return null
        val all = Drive.list(token)
        val bySet = all.filter { it.set != null }.groupBy { it.set!! }
        for ((_, files) in bySet.entries.sortedByDescending { it.key.toLongOrNull() ?: 0L }) {
            val head = files.firstOrNull { it.name == "header.json" } ?: continue   // unfinished set
            val header = BackupArchive.readHeader(Drive.download(token, head.id)) ?: continue
            val storeKey = BackupStoreKey.find(ctx, uid)
            val canStore = storeKey != null && header.store != null
            return Found(header.made, files.sumOf { it.size }, !canStore && header.hasPassword,
                files.associate { it.name to it.id }, header)
        }
        return null
    }

    /** Opens [found] into the phone's history. Null on success, else the words to show. */
    suspend fun restore(ctx: Context, uid: String, token: String, found: Found, password: String?): String? {
        val c = CryptoHub.get(ctx)
        val key = BackupArchive.unlock(c, found.header, BackupStoreKey.find(ctx, uid), password)
            ?: return if (password != null) "That password doesn't open this backup." else "Enter your backup password."
        val cache = HashMap<String, ByteArray>()
        for ((name, id) in found.files) runCatching { cache[name] = Drive.download(token, id) }
        val whole = BackupArchive.restore(c, uid, key) { cache[it] }
        LocalDb.setMeta(uid, "restore.checked", found.made.toString())
        if (!whole) return "Some chats couldn't be restored. Try again on Wi-Fi."
        LocalDb.setMeta(uid, "restored", found.made.toString())
        LocalDb.setMeta(uid, "restore.waiting", "")
        return null
    }
}
