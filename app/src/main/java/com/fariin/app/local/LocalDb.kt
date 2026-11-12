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
    fun save(uid: String, cid: String, messages: List<Message>) {
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
            }.onFailure { for (m in changed) written.remove("$uid/$cid/${m.id}") }
        }
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

    /** Rows the server no longer has (a removed message): gone from here too. */
    fun forget(uid: String, cid: String, ids: Collection<String>) {
        if (ids.isEmpty()) return
        for (id in ids) written.remove("$uid/$cid/$id")
        io.launch {
            val db = db(uid) ?: return@launch
            runCatching { for (id in ids) db.delete("messages", "cid = ? AND id = ?", arrayOf(cid, id)) }
        }
    }

    /** "Clear chat" on this phone: every row of the chat goes. */
    fun clearChat(uid: String, cid: String) {
        written.keys.removeAll { it.startsWith("$uid/$cid/") }
        io.launch { db(uid)?.let { runCatching { it.delete("messages", "cid = ?", arrayOf(cid)) } } }
    }

    /** Disappearing messages leave the file when they leave the screen. */
    fun purgeExpired(uid: String) {
        io.launch {
            db(uid)?.let { runCatching { it.delete("messages", "expires_at IS NOT NULL AND expires_at <= ?", arrayOf(System.currentTimeMillis().toString())) } }
        }
    }

    /** Account deletion: the whole file goes. */
    fun deleteFor(uid: String) {
        helpers.remove(uid)?.close()
        written.keys.removeAll { it.startsWith("$uid/") }
        app?.deleteDatabase(fileName(uid))
    }
}
