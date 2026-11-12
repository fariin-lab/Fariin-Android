package com.fariin.app.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.fariin.app.data.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * The phone's own message history (device-storage plan phase 1, 2026-11-12, shared with iOS:
 * Documents/fariin-plans/2026-11-12-device-storage-plan.md). One SQLite file per account,
 * fariin-local-<uid>.db, the same tables and columns as the iPhone's:
 *
 *   messages(cid, id, created_at, sender, type, deleted, edited_at, expires_at, body, updated_at)
 *     PRIMARY KEY (cid, id); INDEX (cid, created_at); INDEX (expires_at)
 *   chats(cid PRIMARY KEY, last_id, last_at, unread, delivered_up_to, synced_up_to, oldest_pulled, body)
 *   meta(key PRIMARY KEY, value)
 *
 * body is this app's own JSON of the opened message ([LocalJson]); plaintext on purpose, in the
 * app's private storage, kept out of every system backup and phone-to-phone copy
 * (data_extraction_rules). Phase 1: every message the app opens is written here, a chat opens from
 * here, scrolling up reads here before the server. The server still keeps everything.
 *
 * Disappearing messages leave this file at the moment they leave the screen ([purgeExpired]);
 * delete-for-everyone keeps a row with deleted = 1 and an empty body. Sign-out keeps the file
 * (history must outlive the server in phase 2); account deletion removes it ([deleteFor]).
 */
object LocalDb {
    const val SCHEMA = 1
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var app: Context? = null
    private val helpers = ConcurrentHashMap<String, Helper>()
    /** What was last written per row, so an unchanged message is never written again. */
    private val written = ConcurrentHashMap<String, Int>()

    fun init(c: Context) { if (app == null) app = c.applicationContext }

    fun fileName(uid: String) = "fariin-local-" + uid.map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '-' }.joinToString("") + ".db"

    private class Helper(c: Context, name: String) : SQLiteOpenHelper(c, name, null, SCHEMA) {
        override fun onConfigure(db: SQLiteDatabase) { db.enableWriteAheadLogging() }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE messages (cid TEXT NOT NULL, id TEXT NOT NULL, created_at INTEGER NOT NULL,
                sender TEXT, type TEXT, deleted INTEGER NOT NULL DEFAULT 0, edited_at INTEGER, expires_at INTEGER,
                body TEXT NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY (cid, id))""")
            db.execSQL("CREATE INDEX messages_cid_created ON messages (cid, created_at)")
            db.execSQL("CREATE INDEX messages_expires ON messages (expires_at)")
            db.execSQL("""CREATE TABLE chats (cid TEXT PRIMARY KEY, last_id TEXT, last_at INTEGER, unread INTEGER,
                delivered_up_to INTEGER, synced_up_to INTEGER, oldest_pulled INTEGER, body TEXT)""")
            db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT)")
            db.execSQL("INSERT INTO meta (key, value) VALUES ('schema_version', '$SCHEMA')")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }

    private fun db(uid: String): SQLiteDatabase? {
        val c = app ?: return null
        return runCatching { helpers.getOrPut(uid) { Helper(c, fileName(uid)) }.writableDatabase }.getOrNull()
    }

    /** Server-known rows only: no unsent copy, no row still uploading, no notice made on the phone. */
    private fun keepable(m: Message) = m.sendState == null && !m.uploading && m.hasServerTime && m.id.isNotEmpty() &&
        !m.id.startsWith("fariin-")

    /** Write what changed of [messages] (any order), off the main thread. */
    /**
     * [cursor]: may this chat's delivery cursor move (not a channel). The NO-GAP rule (iOS ced63a4fd,
     * both apps): chats.synced_up_to is the only thing that moves the cursor, and it moves only when
     * what was filed joins the mark with no hole: [reachedStart] (the server's history was read to
     * its first message) or [oldestFiledMs] (the oldest server row read in one unbroken run down from
     * the newest) is at or before the existing mark. No mark and not at the start = vouch for nothing.
     */
    fun save(uid: String, cid: String, messages: List<Message>, cursor: Boolean = false,
             oldestFiledMs: Long? = null, newestFiledMs: Long? = null, reachedStart: Boolean = false) {
        val rows = messages.filter(::keepable)
        if (rows.isEmpty()) return
        val changed = rows.filter { written["$uid/$cid/${it.id}"] != it.hashCode() }
        if (changed.isEmpty()) return
        for (m in changed) written["$uid/$cid/${m.id}"] = m.hashCode()
        io.launch {
            val db = db(uid) ?: return@launch
            val now = System.currentTimeMillis()
            runCatching {
                db.beginTransaction()
                try {
                    for (m in changed) {
                        val body = if (m.deleted) "{}" else LocalJson.encodeMessage(m).toString()
                        db.insertWithOnConflict("messages", null, ContentValues().apply {
                            put("cid", cid); put("id", m.id); put("created_at", m.createdAtMillis.toLong())
                            put("sender", m.authorId); put("type", m.type); put("deleted", if (m.deleted) 1 else 0)
                            m.expiresAtMillis?.let { put("expires_at", it.toLong()) } ?: putNull("expires_at")
                            put("body", body); put("updated_at", now)
                        }, SQLiteDatabase.CONFLICT_REPLACE)
                    }
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
                runCatching { refreshSummary(db, cid) }
                // Stored for good: the mark (and the cursor the server deletes by) may move, no hole allowed.
                if (cursor && newestFiledMs != null) {
                    val newest = newestFiledMs
                    val mark = mark(db, cid)
                    if (reachedStart || (mark != null && oldestFiledMs != null && oldestFiledMs <= mark)) {
                        val next = maxOf(mark ?: 0L, newest)
                        if (mark == null || next > mark) setMark(db, cid, next)
                        Cursors.advance(cid, next)
                    }
                }
            }.onFailure { for (m in changed) written.remove("$uid/$cid/${m.id}") }
        }
    }

    /** The same write as [save], done now on the caller's thread; true once committed. */
    fun saveNow(uid: String, cid: String, messages: List<Message>): Boolean {
        val rows = messages.filter(::keepable)
        if (rows.isEmpty()) return true
        val db = db(uid) ?: return false
        val now = System.currentTimeMillis()
        return runCatching {
            db.beginTransaction()
            try {
                for (m in rows) {
                    db.insertWithOnConflict("messages", null, ContentValues().apply {
                        put("cid", cid); put("id", m.id); put("created_at", m.createdAtMillis.toLong())
                        put("sender", m.authorId); put("type", m.type); put("deleted", if (m.deleted) 1 else 0)
                        m.expiresAtMillis?.let { put("expires_at", it.toLong()) } ?: putNull("expires_at")
                        put("body", if (m.deleted) "{}" else LocalJson.encodeMessage(m).toString()); put("updated_at", now)
                    }, SQLiteDatabase.CONFLICT_REPLACE)
                    written["$uid/$cid/${m.id}"] = m.hashCode()
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            runCatching { refreshSummary(db, cid) }
            true
        }.getOrDefault(false)
    }

    fun meta(uid: String, key: String): String? = db(uid)?.let { d ->
        runCatching { d.rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key)).use { c -> if (c.moveToFirst()) c.getString(0) else null } }.getOrNull()
    }

    fun setMeta(uid: String, key: String, value: String) {
        db(uid)?.let { d -> runCatching { d.insertWithOnConflict("meta", null, ContentValues().apply { put("key", key); put("value", value) }, SQLiteDatabase.CONFLICT_REPLACE) } }
    }

    fun syncedUpTo(uid: String, cid: String): Long? = db(uid)?.let { runCatching { mark(it, cid) }.getOrNull() }

    fun setSyncedUpTo(uid: String, cid: String, ms: Long) { db(uid)?.let { runCatching { setMark(it, cid, ms) } } }

    /**
     * The chats row's summary (D5, same as iOS c4a7f23a7): last_id / last_at = the newest message
     * still on the phone (a group skips deleted ones, an expired one never counts), and body = JSON
     * {text, type, authorId, deleted, readAt}; readAt (my read mark) is kept as it was.
     */
    /** Each chat's newest stored text and time, in memory for the chat list (D5 overlay). */
    private val summaries = ConcurrentHashMap<String, Pair<String, Long>>()

    /** The newest text this phone has for [cid] and its time, or null. Never touches the disk. */
    fun summary(cid: String): Pair<String, Long>? = summaries[cid]

    /** Reads every chat's summary into memory once (off the main thread, at start). */
    fun warmSummaries(uid: String) {
        io.launch {
            for (r in rawChats(uid)) {
                val cid = r["cid"] as? String ?: continue
                val at = (r["last_at"] as? Number)?.toLong() ?: continue
                val text = runCatching { org.json.JSONObject(r["body"] as? String ?: "").optString("text") }.getOrDefault("")
                summaries[cid] = text to at
            }
        }
    }

    private fun refreshSummary(db: SQLiteDatabase, cid: String) {
        val now = System.currentTimeMillis().toString()
        val group = com.fariin.app.crypto.Wire.isGroupCid(cid)
        val sql = "SELECT id, body, deleted, created_at, sender, type FROM messages WHERE cid = ? AND (expires_at IS NULL OR expires_at > ?)" +
            (if (group) " AND deleted = 0" else "") + " ORDER BY created_at DESC LIMIT 1"
        db.rawQuery(sql, arrayOf(cid, now)).use { c ->
            val oldBody = db.rawQuery("SELECT body FROM chats WHERE cid = ?", arrayOf(cid)).use { o -> if (o.moveToFirst()) o.getString(0) else null }
            val readAt = runCatching { oldBody?.let { org.json.JSONObject(it).opt("readAt") } }.getOrNull()
            val v = ContentValues()
            if (c.moveToFirst()) {
                val deleted = c.getInt(2) == 1
                val text = if (deleted) "" else runCatching { org.json.JSONObject(c.getString(1)).optString("t") }.getOrDefault("")
                v.put("last_id", c.getString(0)); v.put("last_at", c.getLong(3))
                summaries[cid] = text to c.getLong(3)
                v.put("body", org.json.JSONObject().put("text", text).put("type", c.getString(5) ?: "text")
                    .put("authorId", c.getString(4) ?: "").put("deleted", deleted).put("readAt", readAt ?: org.json.JSONObject.NULL).toString())
            } else { v.putNull("last_id"); v.putNull("last_at"); summaries.remove(cid) }
            if (db.update("chats", v, "cid = ?", arrayOf(cid)) == 0) { v.put("cid", cid); db.insert("chats", null, v) }
        }
    }

    private fun mark(db: SQLiteDatabase, cid: String): Long? =
        db.rawQuery("SELECT synced_up_to FROM chats WHERE cid = ?", arrayOf(cid)).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }

    private fun setMark(db: SQLiteDatabase, cid: String, ms: Long) {
        if (db.update("chats", ContentValues().apply { put("synced_up_to", ms) }, "cid = ?", arrayOf(cid)) == 0)
            db.insert("chats", null, ContentValues().apply { put("cid", cid); put("synced_up_to", ms) })
    }

    private fun read(uid: String, sql: String, args: Array<String>): List<Message> {
        val db = db(uid) ?: return emptyList()
        val out = ArrayList<Message>()
        runCatching {
            db.rawQuery(sql, args).use { cur ->
                val idCol = cur.getColumnIndexOrThrow("id"); val bodyCol = cur.getColumnIndexOrThrow("body")
                val delCol = cur.getColumnIndexOrThrow("deleted"); val atCol = cur.getColumnIndexOrThrow("created_at")
                val fromCol = cur.getColumnIndexOrThrow("sender")
                while (cur.moveToNext()) {
                    val deleted = cur.getInt(delCol) == 1
                    val m = if (deleted) Message(id = cur.getString(idCol), authorId = cur.getString(fromCol) ?: "", text = "",
                            createdAtMillis = cur.getLong(atCol).toDouble(), hasServerTime = true, deleted = true)
                        else runCatching { LocalJson.decodeMessage(org.json.JSONObject(cur.getString(bodyCol))) }.getOrNull()
                    if (m != null) out.add(m)
                }
            }
        }
        return out
    }

    /** The newest [limit] messages of a chat, oldest first, none already expired. Blocking: call off the main thread when you can. */
    fun newest(uid: String, cid: String, limit: Int): List<Message> =
        read(uid, "SELECT id, body, deleted, created_at, sender FROM messages WHERE cid = ? AND (expires_at IS NULL OR expires_at > ?) " +
            "ORDER BY created_at DESC LIMIT $limit", arrayOf(cid, System.currentTimeMillis().toString())).asReversed()

    /** Up to [limit] messages strictly older than [beforeMs], oldest first. */
    fun older(uid: String, cid: String, beforeMs: Long, limit: Int): List<Message> =
        read(uid, "SELECT id, body, deleted, created_at, sender FROM messages WHERE cid = ? AND created_at < ? " +
            "AND (expires_at IS NULL OR expires_at > ?) ORDER BY created_at DESC LIMIT $limit",
            arrayOf(cid, beforeMs.toString(), System.currentTimeMillis().toString())).asReversed()

    /** One stored message, or null. */
    fun message(uid: String, cid: String, id: String): Message? =
        read(uid, "SELECT id, body, deleted, created_at, sender FROM messages WHERE cid = ? AND id = ?", arrayOf(cid, id)).firstOrNull()

    /** Rows the server no longer has (a removed message): gone from here too. */
    fun forget(uid: String, cid: String, ids: Collection<String>) {
        if (ids.isEmpty()) return
        for (id in ids) written.remove("$uid/$cid/$id")
        io.launch {
            val db = db(uid) ?: return@launch
            runCatching { for (id in ids) db.delete("messages", "cid = ? AND id = ?", arrayOf(cid, id)); refreshSummary(db, cid) }
        }
    }

    /** "Clear chat" on this phone: every row of the chat goes. */
    fun clearChat(uid: String, cid: String) {
        written.keys.removeAll { it.startsWith("$uid/$cid/") }
        io.launch { db(uid)?.let { runCatching { it.delete("messages", "cid = ?", arrayOf(cid)); refreshSummary(it, cid) } } }
    }

    /** Disappearing messages leave the file when they leave the screen. */
    fun purgeExpired(uid: String) {
        io.launch {
            db(uid)?.let { runCatching { it.delete("messages", "expires_at IS NOT NULL AND expires_at <= ?", arrayOf(System.currentTimeMillis().toString())) } }
        }
    }

    // ------------------------------------------------------------------ backup archive (phase 3)

    val MESSAGE_COLUMNS = listOf("cid", "id", "created_at", "sender", "type", "deleted", "edited_at", "expires_at", "body", "updated_at")
    val CHAT_COLUMNS = listOf("cid", "last_id", "last_at", "unread", "delivered_up_to", "synced_up_to", "oldest_pulled", "body")

    private fun cell(c: android.database.Cursor, i: Int): Any? = when (c.getType(i)) {
        android.database.Cursor.FIELD_TYPE_NULL -> null
        android.database.Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
        android.database.Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
        else -> c.getString(i)
    }

    /** Every chats row, column -> value (one consistent read, before the messages). */
    fun rawChats(uid: String): List<Map<String, Any?>> {
        val db = db(uid) ?: return emptyList()
        return runCatching {
            db.rawQuery("SELECT ${CHAT_COLUMNS.joinToString()} FROM chats", null).use { c ->
                val out = ArrayList<Map<String, Any?>>()
                while (c.moveToNext()) out.add(CHAT_COLUMNS.mapIndexed { i, k -> k to cell(c, i) }.toMap())
                out
            }
        }.getOrDefault(emptyList())
    }

    /** Every message row, oldest first, handed to [each] one at a time (a big history never sits in memory whole). */
    fun eachRawMessage(uid: String, each: (Map<String, Any?>) -> Unit) {
        val db = db(uid) ?: return
        db.rawQuery("SELECT ${MESSAGE_COLUMNS.joinToString()} FROM messages ORDER BY created_at, cid, id", null).use { c ->
            while (c.moveToNext()) each(MESSAGE_COLUMNS.mapIndexed { i, k -> k to cell(c, i) }.toMap())
        }
    }

    /** Restore: rows already here win (INSERT OR IGNORE); returns how many messages were filed. */
    fun importMessages(uid: String, rows: List<Map<String, Any?>>): Int {
        val db = db(uid) ?: return 0
        var n = 0
        db.beginTransaction()
        try {
            for (r in rows) {
                val v = ContentValues()
                for (k in MESSAGE_COLUMNS) when (val x = r[k]) {
                    null -> v.putNull(k); is Number -> v.put(k, x.toLong()); else -> v.put(k, x.toString())
                }
                if (db.insertWithOnConflict("messages", null, v, SQLiteDatabase.CONFLICT_IGNORE) != -1L) n++
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return n
    }

    /** Restore: a chats row merges; synced_up_to = the larger (null in the archive never lowers one). */
    fun importChats(uid: String, rows: List<Map<String, Any?>>) {
        val db = db(uid) ?: return
        for (r in rows) {
            val cid = r["cid"] as? String ?: continue
            val had = mark(db, cid)
            val theirs = (r["synced_up_to"] as? Number)?.toLong()
            val v = ContentValues()
            for (k in CHAT_COLUMNS) if (k != "cid" && k != "synced_up_to") when (val x = r[k]) {
                null -> {}; is Number -> v.put(k, x.toLong()); else -> v.put(k, x.toString())
            }
            if (had != null || theirs != null) v.put("synced_up_to", maxOf(had ?: Long.MIN_VALUE, theirs ?: Long.MIN_VALUE))
            if (db.update("chats", v, "cid = ?", arrayOf(cid)) == 0) { v.put("cid", cid); db.insert("chats", null, v) }
            runCatching { refreshSummary(db, cid) }
        }
    }

    /** Any message at all on this phone for this account (the restore offer looks at META first). */
    fun hasAnyMessage(uid: String): Boolean = db(uid)?.let { d ->
        runCatching { d.rawQuery("SELECT 1 FROM messages LIMIT 1", null).use { it.moveToFirst() } }.getOrDefault(false)
    } ?: false

    /** A full history to back up: some chat has a no-gap mark, or a backup was restored here
     *  (same test as iOS hasSyncedMarks: a fresh phone's first few rows are not a history). */
    fun hasSyncedMarks(uid: String): Boolean {
        if (!meta(uid, "restored").isNullOrEmpty()) return true
        return db(uid)?.let { d -> runCatching { d.rawQuery("SELECT 1 FROM chats WHERE synced_up_to IS NOT NULL LIMIT 1", null).use { it.moveToFirst() } }.getOrDefault(false) } ?: false
    }

    /** Account deletion: the whole file goes. */
    fun deleteFor(uid: String) {
        helpers.remove(uid)?.close()
        written.keys.removeAll { it.startsWith("$uid/") }
        app?.deleteDatabase(fileName(uid))
    }
}
