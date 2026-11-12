package com.fariin.app.backup

import android.content.Context
import android.util.Base64
import com.fariin.app.data.CryptoHub
import com.fariin.app.local.LocalDb
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.tasks.await

/**
 * Phone-to-phone transfer (device-storage plan phase 4, same as iOS 308e1edde). The OLD phone builds
 * an archive v1 whose store wrap uses a one-time transfer key, uploads it to
 * transfer/{uid}/{tid}/ (manifest, chunks, chats.bin, header.json LAST) and shows the QR
 *   fariin-transfer:v1:<tid>:<key>:<files>
 * tid = 16 random bytes, key = 32 random bytes, both base64url without padding (22 and 43 chars);
 * files = every file in the folder including header.json (3..99999). The key never touches the
 * server. The NEW phone (same account) scans it, downloads, opens, files the history, then deletes
 * the folder in the same order, header.json last; the server sweeps anything older than an hour.
 */
object ChatTransfer {
    /** ON in test builds (the owner's Pixel and testers), OFF in store releases until the owner's test passes. */
    @Volatile var ENABLED: Boolean = false   // set at start from R.bool.fariin_test_build (FariinApp)

    class Code(val tid: String, val key: ByteArray, val files: Int)

    private fun b64url(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    private fun unb64url(s: String): ByteArray? = runCatching { Base64.decode(s, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP) }.getOrNull()
        ?.takeIf { b64url(it) == s }   // canonical only

    /** Strict parse: exactly 5 parts, canonical base64url, a count with no leading zero, 3..99999. */
    fun parse(qr: String): Code? {
        val p = qr.split(":")
        if (p.size != 5 || p[0] != "fariin-transfer" || p[1] != "v1") return null
        val tid = p[2]; val key = unb64url(p[3]) ?: return null
        if (tid.length != 22 || unb64url(tid)?.size != 16 || key.size != 32) return null
        if (p[4].startsWith("0") || p[4].any { !it.isDigit() }) return null
        val n = p[4].toIntOrNull() ?: return null
        if (n !in 3..99999) return null
        return Code(tid, key, n)
    }

    private fun folder(uid: String, tid: String) = FirebaseStorage.getInstance().reference.child("transfer/$uid/$tid")

    /** OLD phone: builds and uploads, then returns the QR text. */
    suspend fun send(ctx: Context, uid: String): String {
        val c = CryptoHub.get(ctx)
        val key = c.randomBytes(32)
        val tid = b64url(c.randomBytes(16))
        val built = BackupArchive.build(c, uid, key, null)
        for ((name, bytes) in built.files) folder(uid, tid).child(name).putBytes(bytes).await()   // header.json is last
        return "fariin-transfer:v1:$tid:${b64url(key)}:${built.files.size}"
    }

    /** OLD phone: the new one has taken it (the folder is empty or its header is gone). */
    suspend fun taken(uid: String, tid: String): Boolean = runCatching {
        folder(uid, tid).child("header.json").metadata.await(); false
    }.getOrElse { e -> (e as? com.google.firebase.storage.StorageException)?.errorCode == com.google.firebase.storage.StorageException.ERROR_OBJECT_NOT_FOUND }

    /** NEW phone: downloads, opens and files the history. Null on success, else words to show. */
    suspend fun receive(ctx: Context, uid: String, qr: String, progress: (Int, Int) -> Unit): String? {
        val code = parse(qr) ?: return "That isn't a Fariin transfer code."
        val c = CryptoHub.get(ctx)
        val dir = folder(uid, code.tid)
        val got = HashMap<String, ByteArray>()
        suspend fun fetch(name: String): ByteArray? = got[name] ?: runCatching { dir.child(name).getBytes(64L * 1024 * 1024).await() }.getOrNull()?.also {
            got[name] = it; progress(got.size, code.files)
        }
        val head = fetch("header.json")?.let { BackupArchive.readHeader(it) } ?: return "This code has expired. Show a new one on your old phone."
        val archiveKey = BackupArchive.unlock(c, head, code.key, null) ?: return "This code doesn't match. Scan it again."
        // Every file first (the archive reader takes them from memory), then file the history.
        val manifest = fetch("manifest.bin") ?: return "This code has expired. Show a new one on your old phone."
        val names = runCatching {
            val plain = c.openWith(archiveKey, manifest.copyOfRange(0, 24), manifest.copyOfRange(24, manifest.size))!!
            val a = org.json.JSONObject(plain.toString(Charsets.UTF_8)).getJSONArray("chunks"); (0 until a.length()).map { a.getString(it) }
        }.getOrNull() ?: return "This transfer can't be read."
        for (n in names + "chats.bin") if (fetch(n) == null) return "The transfer stopped. Check your connection and scan again."
        val whole = BackupArchive.restore(c, uid, archiveKey) { got[it] }
        if (!whole) return "Some chats couldn't be moved. Show a new code and try again."
        LocalDb.setMeta(uid, "restored", head.made.toString())
        LocalDb.setMeta(uid, "restore.checked", head.made.toString())
        // Same order as the upload, header last: the old phone sees "Done" when its header is gone.
        for (n in listOf("manifest.bin") + names + "chats.bin" + "header.json") runCatching { dir.child(n).delete().await() }
        return null
    }
}
