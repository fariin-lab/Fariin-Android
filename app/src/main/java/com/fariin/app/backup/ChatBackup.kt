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
    private const val MEDIA_SET = "md"
    @Volatile var ENABLED: Boolean = false   // set at start from R.bool.fariin_test_build (FariinApp)
    private val lock = Mutex()

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("fariin_backup", Context.MODE_PRIVATE)

    fun lastBackup(ctx: Context, uid: String): Long = prefs(ctx).getLong("last.$uid", 0L)

    /** How often: "daily" (default once on), "weekly" or "off". */
    fun frequency(ctx: Context, uid: String): String = prefs(ctx).getString("freq.$uid", "off") ?: "off"
    fun setFrequency(ctx: Context, uid: String, f: String) { prefs(ctx).edit().putString("freq.$uid", f).apply(); BackupWorker.schedule(ctx, uid) }
    fun mobileData(ctx: Context, uid: String): Boolean = prefs(ctx).getBoolean("cell.$uid", false)
    /** Include Videos (off by default, both apps): videos are the big part of a backup. */
    fun includeVideos(ctx: Context, uid: String): Boolean = prefs(ctx).getBoolean("videos.$uid", false)
    fun setIncludeVideos(ctx: Context, uid: String, on: Boolean) = prefs(ctx).edit().putBoolean("videos.$uid", on).apply()
    fun setMobileData(ctx: Context, uid: String, on: Boolean) { prefs(ctx).edit().putBoolean("cell.$uid", on).apply(); BackupWorker.schedule(ctx, uid) }

    /** The password / 64-digit key wrap kept on this phone (the Argon2 result and its salt), so
     *  daily backups need nothing typed. Never leaves the phone. */
    fun hasPassword(ctx: Context, uid: String): Boolean = prefs(ctx).contains("pw.$uid")
    private fun passwordWrap(ctx: Context, uid: String): Pair<ByteArray, ByteArray>? {
        // Sealed with this phone's Keystore key (local/AtRest), never readable as stored.
        val k = prefs(ctx).getString("pw.$uid", null)?.let { com.fariin.app.crypto.B64.decode(it) }
            ?.let { com.fariin.app.local.AtRest.open(it) } ?: return null
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
            val sealed = com.fariin.app.local.AtRest.seal(pk) ?: return
            e.putString("pw.$uid", com.fariin.app.crypto.B64.encode(sealed)).putString("pwsalt.$uid", com.fariin.app.crypto.B64.encode(salt))
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

    /** What a running backup is doing, for the page: a step line and 0..1. */
    data class Progress(val step: String, val fraction: Float)
    val progress = kotlinx.coroutines.flow.MutableStateFlow<Progress?>(null)

    /** What the last backup held: messages, photos, voice notes, videos. */
    fun contents(ctx: Context, uid: String): IntArray = prefs(ctx).getString("what.$uid", null)
        ?.split(",")?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 4 }?.toIntArray() ?: intArrayOf(0, 0, 0, 0)

    /** What Include Videos would add: the videos already saved on this phone, in bytes. */
    suspend fun videoBytes(ctx: Context, uid: String): Long = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val c = CryptoHub.get(ctx)
            val key = BackupStoreKey.mediaKey(ctx, uid) { c.randomBytes(it) }
            BackupMedia.collect(ctx, c, uid, key, true).filter { it.entry["slot"] == "video" }.sumOf { it.file.length() }
        }.getOrDefault(0L)
    }

    /** The Google account the backup is in (asked from Drive once, then kept). */
    fun account(ctx: Context, uid: String): String? = prefs(ctx).getString("acct.$uid", null)

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

    /**
     * The sign-in question "restore your backup?" is still open (safety review item 8, iOS
     * SignInRestoreGate): the history pull waits, so marks made before the answer never hide the offer.
     */
    fun restoreQuestionOpen(uid: String): Boolean = ENABLED && LocalDb.meta(uid, "restore.checked") == null &&
        LocalDb.meta(uid, "restored").isNullOrEmpty() && !LocalDb.hasSyncedMarks(uid)

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
        // Google's access pass lasts about an hour: a long backup asks for a fresh one (silent once
        // allowed) when Drive says it ran out, and carries on.
        var live = tok
        suspend fun <T> drive(op: suspend (String) -> T): T = try { op(live) } catch (e: Drive.DriveError) {
            if (e.code != 401) throw e
            live = (Drive.authorize(ctx) as? Drive.Auth.Token)?.value ?: throw e
            op(live)
        }
        return try {
            progress.value = Progress("Getting ready…", 0f)
            runCatching { drive { t -> Drive.accountEmail(t) } }.getOrNull()?.let { prefs(ctx).edit().putString("acct.$uid", it).apply() }
            val storeKey = BackupStoreKey.getOrMake(ctx, uid) { c.randomBytes(it) }
            // Only this Fariin account's files: other accounts on the same Google Drive keep theirs
            // (owner 2026-11-12, three accounts on one phone). Untagged files are from before the tag.
            val before = drive { t -> Drive.list(t) }.filter { it.uid == uid || it.uid == null }
            // Media first (iOS 7687219ef): md- files belong to no set; one already there is only listed.
            val mediaKey = BackupStoreKey.mediaKey(ctx, uid) { c.randomBytes(it) }
            val items = BackupMedia.collect(ctx, c, uid, mediaKey, includeVideos(ctx, uid))
            val have = before.filter { it.name.startsWith("md-") }.associateBy { it.name }
            var size = 0L
            val listed = ArrayList<Map<String, Any?>>()
            val freshItems = items.filter { it.name !in have }
            val total = freshItems.sumOf { it.file.length() }.coerceAtLeast(1L)
            var doneBytes = 0L
            fun mb(b: Long) = "%.1f MB".format(b / 1_000_000.0)
            for (it in items) {
                if (it.name !in have) {
                    doneBytes += it.file.length()
                    val f = doneBytes.toFloat() / total
                    // Bytes, as the reference app shows them (owner 2026-11-12): "Uploading 74.6 MB of 88.0 MB".
                    progress.value = Progress("Uploading ${mb(doneBytes)} of ${mb(total)}", 0.1f + 0.7f * f)
                    val bytes = BackupMedia.seal(c, it) ?: continue
                    drive { t -> Drive.upload(t, it.name, MEDIA_SET, bytes, uid) }; size += bytes.size
                } else size += have[it.name]!!.size
                listed.add(it.entry)
            }
            progress.value = Progress("Saving messages…", 0.85f)
            val built = BackupArchive.build(c, uid, storeKey, passwordWrap(ctx, uid),
                mapOf("mediaKey" to com.fariin.app.crypto.B64.encode(mediaKey), "media" to listed))
            val set = built.made.toString()
            for ((name, bytes) in built.files) { drive { t -> Drive.upload(t, name, set, bytes, uid) }; size += bytes.size }
            // The new set is whole (its header went last): every older set goes, and media no longer listed.
            val keep = listed.mapNotNull { it["n"] as? String }.toSet()
            for (f in before) when {
                f.name.startsWith("md-") -> if (f.name !in keep) drive { t -> Drive.delete(t, f.id) }
                f.set != set -> drive { t -> Drive.delete(t, f.id) }
            }
            val slots = listed.groupingBy { it["slot"] as? String }.eachCount()
            prefs(ctx).edit().putLong("last.$uid", built.made).putLong("size.$uid", size)
                .putString("what.$uid", "${built.count},${slots["image"] ?: 0},${slots["audio"] ?: 0},${slots["video"] ?: 0}").apply()
            Result.Done
        } catch (e: Exception) { Result.Failed(words(e)) }
        finally { progress.value = null }
    }

    class Found(val made: Long, val size: Long, val needsPassword: Boolean, internal val files: Map<String, String>,
                internal val header: BackupArchive.Header, internal val media: Map<String, String> = emptyMap())

    /** The newest finished backup in the person's Drive, or null. */
    suspend fun find(ctx: Context, uid: String, token: String): Found? {
        if (!ENABLED) return null
        val all = Drive.list(token).filter { it.uid == uid || it.uid == null }
        val bySet = all.filter { it.set != null && it.set != MEDIA_SET }.groupBy { it.set!! }
        for ((_, files) in bySet.entries.sortedByDescending { it.key.toLongOrNull() ?: 0L }) {
            val head = files.firstOrNull { it.name == "header.json" } ?: continue   // unfinished set
            val header = BackupArchive.readHeader(Drive.download(token, head.id)) ?: continue
            val storeKey = BackupStoreKey.find(ctx, uid)
            val canStore = storeKey != null && header.store != null
            return Found(header.made, files.sumOf { it.size }, !canStore && header.hasPassword,
                files.associate { it.name to it.id }, header,
                all.filter { it.name.startsWith("md-") }.associate { it.name to it.id })
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
        // Only a whole restore closes the offer: a dropped connection must leave Restore there to try again.
        if (!whole) return "Some chats couldn't be restored. Check your connection and tap Restore again."
        LocalDb.setMeta(uid, "restore.checked", found.made.toString())
        LocalDb.setMeta(uid, "restored", found.made.toString())
        LocalDb.setMeta(uid, "restore.waiting", "")
        // Media after messages and chats (both apps): the backup's media key becomes ours, then
        // each file goes where this app keeps opened media. A missing or bad one is skipped.
        BackupArchive.lastManifest?.let { mo ->
            val mk = mo.optString("mediaKey").takeIf { it.isNotEmpty() }?.let { com.fariin.app.crypto.B64.decode(it) } ?: return null
            BackupStoreKey.adoptMediaKey(ctx, uid, mk)
            val list = mo.optJSONArray("media") ?: return null
            @Suppress("UNCHECKED_CAST")
            val entries = (0 until list.length()).mapNotNull { Canonical.decode(list.getJSONObject(it).toString()) as? Map<String, Any?> }
            BackupMedia.place(ctx, c, uid, mk, entries) { n -> found.media[n]?.let { id -> runCatching { Drive.download(token, id) }.getOrNull() } }
        }
        return null
    }
}
