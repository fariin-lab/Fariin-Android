package com.fariin.app.backup

import com.fariin.app.crypto.B64
import com.fariin.app.crypto.FariinCrypto
import com.fariin.app.local.LocalDb
import com.fariin.app.local.LocalJson

/**
 * Backup archive v1 (device-storage plan, agreed with iOS BackupArchive.swift). Files:
 *   header.json  plain: {kdf, made, v:1, wrapped:{password?, store}}
 *   manifest.bin {chunks:[...], count, made, uid}
 *   m-0001.bin…  JSON lines of message rows (the db columns), <= 2000 rows or 2,097,152 bytes each
 *   chats.bin    JSON lines of chats rows
 * Every .bin = 24-byte nonce || secretbox(plain, archiveKey). The archive key (32 random bytes, new
 * per backup) is wrapped under the platform-store key and, optionally, Argon2id13(password).
 * Row `body` is a JSON STRING: messages carry the shared wire body, chats their stored body.
 */
object BackupArchive {
    const val V = 1
    private const val MAX_ROWS = 2000
    private const val MAX_BYTES = 2_097_152
    const val OPS = 3L
    const val MEM = 67_108_864L

    class Built(val files: LinkedHashMap<String, ByteArray>, val count: Int, val made: Long)

    private fun seal(c: FariinCrypto, key: ByteArray, plain: ByteArray): ByteArray {
        val (n, box) = c.sealWith(key, plain) ?: error("seal failed")
        return n + box
    }

    private fun open(c: FariinCrypto, key: ByteArray, file: ByteArray): ByteArray? =
        if (file.size < 24 + 16) null else c.openWith(key, file.copyOfRange(0, 24), file.copyOfRange(24, file.size))

    fun passwordKey(c: FariinCrypto, password: String, salt: ByteArray): ByteArray? =
        c.pinHash(32, password.toByteArray(Charsets.UTF_8), salt, OPS, MEM)

    /**
     * Builds the whole archive from the phone's history. Chats rows are read FIRST (safety review
     * item 7); a chat with any message that would not convert keeps no mark in the archive.
     */
    /** [passwordWrap] = (Argon2id key, its salt) when the person set a backup password or 64-digit key. */
    fun build(c: FariinCrypto, uid: String, storeKey: ByteArray, passwordWrap: Pair<ByteArray, ByteArray>?,
              extraManifest: Map<String, Any?> = emptyMap()): Built {
        val made = System.currentTimeMillis()
        val archiveKey = c.randomBytes(32)
        val chats = LocalDb.rawChats(uid)
        val dropped = HashSet<String>()
        val files = LinkedHashMap<String, ByteArray>()
        val chunks = ArrayList<String>()
        val buf = StringBuilder(); var rows = 0; var bytes = 0; var count = 0
        fun flush() {
            if (rows == 0) return
            val name = "m-%04d.bin".format(chunks.size + 1)
            files[name] = seal(c, archiveKey, buf.toString().toByteArray(Charsets.UTF_8))
            chunks.add(name); buf.setLength(0); rows = 0; bytes = 0
        }
        LocalDb.eachRawMessage(uid) { r ->
            val cid = r["cid"] as String
            val stored = r["body"] as? String ?: ""
            val wire: String? = when {
                stored == "{}" || stored.isEmpty() -> stored
                else -> runCatching {
                    LocalJson.decodeMessage(org.json.JSONObject(stored))?.let { Canonical.encode(WireBody.toWire(it, cid)) }
                }.getOrNull()
            }
            if (wire == null) { dropped.add(cid); return@eachRawMessage }
            val line = Canonical.encode(r + ("body" to wire)) + "\n"
            val size = line.toByteArray(Charsets.UTF_8).size
            if (rows > 0 && (rows >= MAX_ROWS || bytes + size > MAX_BYTES)) flush()
            buf.append(line); rows++; bytes += size; count++
        }
        flush()
        val chatLines = StringBuilder()
        for (r in chats) {
            val row = if (r["cid"] in dropped) r + ("synced_up_to" to null) else r
            chatLines.append(Canonical.encode(row)).append("\n")
        }
        files["chats.bin"] = seal(c, archiveKey, chatLines.toString().toByteArray(Charsets.UTF_8))
        files["manifest.bin"] = seal(c, archiveKey, Canonical.encode(
            mapOf("chunks" to chunks, "count" to count, "made" to made, "uid" to uid) + extraManifest).toByteArray(Charsets.UTF_8))
        val wrapped = LinkedHashMap<String, Any?>()
        wrapped["store"] = B64.encode(seal(c, storeKey, archiveKey))
        var kdf: Map<String, Any?>? = null
        if (passwordWrap != null) {
            val (pk, salt) = passwordWrap
            wrapped["password"] = B64.encode(seal(c, pk, archiveKey))
            kdf = mapOf("mem" to MEM, "ops" to OPS, "salt" to B64.encode(salt))
        }
        val header = Canonical.encode(mapOf("kdf" to kdf, "made" to made, "v" to V, "wrapped" to wrapped))
        // Upload order is manifest, chunks, chats, header LAST (the header makes the set real).
        val ordered = LinkedHashMap<String, ByteArray>()
        ordered["manifest.bin"] = files.remove("manifest.bin")!!
        for (n in chunks) ordered[n] = files.remove(n)!!
        ordered["chats.bin"] = files.remove("chats.bin")!!
        ordered["header.json"] = header.toByteArray(Charsets.UTF_8)
        return Built(ordered, count, made)
    }

    class Header(val made: Long, val hasPassword: Boolean, val store: ByteArray?, val password: ByteArray?, val salt: ByteArray?, val ops: Long, val mem: Long)

    fun readHeader(bytes: ByteArray): Header? = runCatching {
        val o = org.json.JSONObject(bytes.toString(Charsets.UTF_8))
        if (o.optInt("v") != V) return null
        val w = o.getJSONObject("wrapped")
        val kdf = o.optJSONObject("kdf")
        Header(o.getLong("made"), w.has("password"), w.optString("store").takeIf { it.isNotEmpty() }?.let { B64.decode(it) },
            w.optString("password").takeIf { it.isNotEmpty() }?.let { B64.decode(it) },
            kdf?.optString("salt")?.let { B64.decode(it) }, kdf?.optLong("ops") ?: OPS, kdf?.optLong("mem") ?: MEM)
    }.getOrNull()

    /** The archive key from the store copy, or else from the password. */
    fun unlock(c: FariinCrypto, h: Header, storeKey: ByteArray?, password: String?): ByteArray? {
        if (storeKey != null && h.store != null) open(c, storeKey, h.store)?.let { return it }
        if (password != null && h.password != null && h.salt != null) {
            val pk = c.pinHash(32, password.toByteArray(Charsets.UTF_8), h.salt, h.ops, h.mem) ?: return null
            return open(c, pk, h.password)
        }
        return null
    }

    /**
     * Files the archive into the phone's history: chats first, then every chunk (rows already
     * here win). True only when every message the manifest counts was read (then "restored").
     */
    /** The opened manifest of the last [restore] (its optional "media" / "mediaKey"). */
    @Volatile var lastManifest: org.json.JSONObject? = null

    fun restore(c: FariinCrypto, uid: String, key: ByteArray, fetch: (String) -> ByteArray?): Boolean {
        val manifest = fetch("manifest.bin")?.let { open(c, key, it) } ?: return false
        val mo = org.json.JSONObject(manifest.toString(Charsets.UTF_8))
        // Another Fariin account's backup (same Google Drive or iCloud) is never filed into this one.
        if (mo.optString("uid").let { it.isNotEmpty() && it != uid }) return false
        lastManifest = mo
        val count = mo.getInt("count")
        val chunks = mo.getJSONArray("chunks").let { a -> (0 until a.length()).map { a.getString(it) } }
        val chatsPlain = fetch("chats.bin")?.let { open(c, key, it) } ?: return false
        val chatRows = chatsPlain.toString(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }
            .mapNotNull { runCatching { @Suppress("UNCHECKED_CAST") (Canonical.decode(it) as Map<String, Any?>) }.getOrNull() }.toList()
        var read = 0
        for (name in chunks) {
            val plain = fetch(name)?.let { open(c, key, it) } ?: return false
            val rows = ArrayList<Map<String, Any?>>()
            for (line in plain.toString(Charsets.UTF_8).lineSequence()) {
                if (line.isBlank()) continue
                @Suppress("UNCHECKED_CAST") val r = (Canonical.decode(line) as? Map<String, Any?>) ?: continue
                read++
                val cid = r["cid"] as? String ?: continue
                val wire = r["body"] as? String ?: ""
                val own = if (wire == "{}" || wire.isEmpty()) wire else runCatching {
                    @Suppress("UNCHECKED_CAST") val w = Canonical.decode(wire) as Map<String, Any?>
                    WireBody.fromWire(w, cid)?.let { LocalJson.encodeMessage(it).toString() }
                }.getOrNull() ?: continue
                rows.add(r + ("body" to own))
            }
            LocalDb.importMessages(uid, rows)
        }
        LocalDb.importChats(uid, chatRows)
        return read == count
    }
}
