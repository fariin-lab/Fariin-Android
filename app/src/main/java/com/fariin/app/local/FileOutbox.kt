package com.fariin.app.local

import android.content.Context
import com.fariin.app.crypto.EncMeta
import com.fariin.app.data.MusicRules
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A file, song or playlist I sent that has not fully reached the server yet, written down so a kill
 * cannot lose it (2026-11-22: after a restart a song spun as "Uploading", a playlist's Resend did
 * nothing and a file was gone; photos already survived in [PhotoOutbox]). iOS keeps the same thing:
 * its pending-upload record (PendingUploadStore) holds the sealed bytes and which message they
 * belong to, and the next launch finishes the upload (resumePendingUploads).
 *
 * The plain copies are already on the phone (`outgoing/file-<clientId>...`, the app's own private
 * folder). Once the message is written this also keeps its id and the sealed copies (the envelopes
 * the message names), and for a playlist each song's sealed item and the urls already up, so the
 * next start puts up only what is missing and completes that same message. Removed when it lands,
 * is cancelled or deleted. Per account, in one small prefs file, wiped at sign-out with the rest.
 */
object FileOutbox {
    data class Entry(
        val cid: String, val clientId: String, val ts: Double,
        /** "file", "song" or "playlist". */
        val kind: String,
        /** The plain copies on this phone, in pick order (one for a file or song). */
        val paths: List<String>,
        val names: List<String>,
        /** A file's preview tile or a playlist's cover (JPEG on this phone), if any. */
        val preview: String? = null,
        val title: String = "", val caption: String = "",
        /** Each song's typed title and artist (null = its own). */
        val renames: List<Pair<String?, String?>> = emptyList(),
        val replyId: String? = null, val replyAuthor: String? = null, val replyText: String? = null,
        /** Set just before the message is written: its id and the sealed copies, same order as [paths]. */
        val messageId: String? = null,
        val sealed: List<String> = emptyList(),
        /** A playlist's songs as the message names them, its sealed name, and the urls already up. */
        val songs: List<MusicRules.SealedSong> = emptyList(),
        val listNameSealed: String = "",
        val urls: List<String?> = emptyList(),
    )

    private const val PREFS = "file_outbox"
    @Volatile private var prefs: android.content.SharedPreferences? = null
    private val lock = Any()

    fun init(c: Context) {
        if (prefs == null) prefs = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private fun opt(o: JSONObject, k: String): String? = o.optString(k).ifEmpty { null }

    private fun encJson(m: Map<String, Any>): JSONObject {
        val e = EncMeta.fromMap(m) ?: return JSONObject()
        val o = JSONObject().put("v", e.v).put("n", e.n).put("k", e.k).put("kn", e.kn)
        e.w?.let { w -> o.put("w", JSONObject().also { j -> w.forEach { (u, s) -> j.put(u, s) } }) }
        e.a?.let { o.put("a", it) }
        return o
    }

    private fun encMap(o: JSONObject?): Map<String, Any> {
        o ?: return emptyMap()
        val w = o.optJSONObject("w")?.let { j -> j.keys().asSequence().associateWith { j.getString(it) } }
        return EncMeta(o.optInt("v"), o.optString("n"), o.optString("k"), o.optString("kn"), w, opt(o, "a")).asMap()
    }

    private fun strings(a: JSONArray?): List<String> = a?.let { j -> (0 until j.length()).map { j.optString(it) } }.orEmpty()

    /** The stored form of [list] (pure; unit tested). */
    internal fun encode(list: List<Entry>): String {
        val a = JSONArray()
        for (e in list) a.put(JSONObject().put("cid", e.cid).put("id", e.clientId).put("ts", e.ts).put("kind", e.kind)
            .put("paths", JSONArray(e.paths)).put("names", JSONArray(e.names)).put("preview", e.preview ?: "")
            .put("title", e.title).put("cap", e.caption)
            .put("renames", JSONArray().also { r -> e.renames.forEach { (t, ar) -> r.put(JSONObject().put("t", t ?: "").put("a", ar ?: "")) } })
            .put("reId", e.replyId ?: "").put("reAuthor", e.replyAuthor ?: "").put("reText", e.replyText ?: "")
            .put("mid", e.messageId ?: "").put("sealed", JSONArray(e.sealed))
            .put("songs", JSONArray().also { s -> e.songs.forEach { g -> s.put(JSONObject().put("enc", encJson(g.enc))
                .put("name", g.plainName).put("sname", g.sealedName ?: "").put("size", g.size)
                .put("stitle", g.sealedTitle ?: "").put("sartist", g.sealedArtist ?: "")) } })
            .put("lname", e.listNameSealed)
            .put("urls", JSONArray().also { u -> e.urls.forEach { u.put(it ?: "") } }))
        return a.toString()
    }

    /** Back from [encode]; a damaged entry is skipped, a damaged store reads as empty. */
    internal fun decode(raw: String?): List<Entry> {
        raw ?: return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                runCatching {
                    Entry(o.getString("cid"), o.getString("id"), o.getDouble("ts"), o.getString("kind"),
                        strings(o.optJSONArray("paths")), strings(o.optJSONArray("names")), opt(o, "preview"),
                        o.optString("title"), o.optString("cap"),
                        o.optJSONArray("renames")?.let { r -> (0 until r.length()).map { j ->
                            val x = r.optJSONObject(j); (x?.let { opt(it, "t") }) to (x?.let { opt(it, "a") }) } }.orEmpty(),
                        opt(o, "reId"), opt(o, "reAuthor"), opt(o, "reText"),
                        opt(o, "mid"), strings(o.optJSONArray("sealed")),
                        o.optJSONArray("songs")?.let { s -> (0 until s.length()).mapNotNull { j -> s.optJSONObject(j)?.let { g ->
                            MusicRules.SealedSong(encMap(g.optJSONObject("enc")), g.optString("name"), opt(g, "sname"), g.optInt("size"),
                                opt(g, "stitle"), opt(g, "sartist"))
                        } } }.orEmpty(),
                        o.optString("lname"),
                        strings(o.optJSONArray("urls")).map { it.ifEmpty { null } })
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Can this entry still be sent (pure; unit tested)? Every plain copy must be readable, and once
     * the message is written every sealed copy too: the message names those envelopes, so the plain
     * bytes cannot be sealed again in their place.
     */
    internal fun readable(e: Entry, size: (String) -> Long): Boolean {
        if (e.paths.isEmpty() || e.paths.size != e.names.size) return false
        if (e.kind == "playlist" && e.paths.size < 2) return false
        if (e.paths.any { size(it) <= 0L }) return false
        if (e.messageId != null) {
            if (e.sealed.size != e.paths.size || e.sealed.any { size(it) <= 0L }) return false
            if (e.kind == "playlist" && (e.songs.size != e.paths.size || e.urls.size != e.paths.size)) return false
        }
        return true
    }

    private fun read(uid: String): MutableList<Entry> = decode(prefs?.getString(uid, null)).toMutableList()

    private fun write(uid: String, list: List<Entry>) {
        prefs?.edit()?.apply { if (list.isEmpty()) remove(uid) else putString(uid, encode(list)) }?.apply()
    }

    fun add(uid: String, e: Entry) = synchronized(lock) {
        val l = read(uid); l.removeAll { it.clientId == e.clientId }; l.add(e); write(uid, l)
    }

    /** Changes the entry of [clientId] in place, if it is still there. */
    fun update(uid: String, clientId: String, change: (Entry) -> Entry) = synchronized(lock) {
        val l = read(uid)
        val i = l.indexOfFirst { it.clientId == clientId }
        if (i >= 0) { l[i] = change(l[i]); write(uid, l) }
    }

    /** Gone: the entry and its sealed copies (the plain ones still draw the bubble). */
    fun remove(uid: String, clientId: String) = synchronized(lock) {
        val l = read(uid)
        val gone = l.filter { it.clientId == clientId }
        if (gone.isEmpty()) return@synchronized
        l.removeAll { it.clientId == clientId }; write(uid, l)
        gone.forEach { e -> e.sealed.forEach { runCatching { File(it).delete() } } }
    }

    fun get(uid: String, clientId: String): Entry? = synchronized(lock) { read(uid).firstOrNull { it.clientId == clientId } }

    fun all(uid: String): List<Entry> = synchronized(lock) { read(uid) }

    fun wipe(c: Context) { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply() }
}
