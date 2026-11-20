package com.fariin.app.local

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * A single photo I sent that has not reached the server yet, written down so a kill cannot lose it
 * (2026-11-20: a photo sent offline was gone after the app was closed; the text queue already
 * survived). The prepared JPEG is already on the phone (`outgoing/send-<clientId>.jpg`); this keeps
 * what is needed to send it again under the same clientId. Removed when it lands, is cancelled or
 * deleted. Per account, in one small prefs file, wiped at sign-out with the rest.
 */
object PhotoOutbox {
    data class Entry(
        val cid: String, val clientId: String, val ts: Double, val path: String,
        val width: Int, val height: Int, val blurhash: String?, val thumb: String?,
        val caption: String, val viewOnce: Boolean,
        /** "photo" or "video" (a single clip: [path] is the converted clip, [poster] its picture). */
        val kind: String = "photo", val poster: String? = null, val duration: Double = 0.0,
        /** A voice note's bars ("voice": [path] is the recording). */
        val wave: List<Int> = emptyList(),
    )

    private const val PREFS = "photo_outbox"
    @Volatile private var prefs: android.content.SharedPreferences? = null
    private val lock = Any()

    fun init(c: Context) {
        if (prefs == null) prefs = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private fun read(uid: String): MutableList<Entry> {
        val raw = prefs?.getString(uid, null) ?: return mutableListOf()
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                Entry(o.getString("cid"), o.getString("id"), o.getDouble("ts"), o.getString("path"),
                    o.optInt("w"), o.optInt("h"), o.optString("blur").ifEmpty { null }, o.optString("thumb").ifEmpty { null },
                    o.optString("cap"), o.optBoolean("once"), o.optString("kind").ifEmpty { "photo" },
                    o.optString("poster").ifEmpty { null }, o.optDouble("dur", 0.0),
                    o.optString("wave").split(',').mapNotNull { it.trim().toIntOrNull() })
            }.toMutableList()
        }.getOrDefault(mutableListOf())
    }

    private fun write(uid: String, list: List<Entry>) {
        val a = JSONArray()
        for (e in list) a.put(JSONObject().put("cid", e.cid).put("id", e.clientId).put("ts", e.ts).put("path", e.path)
            .put("w", e.width).put("h", e.height).put("blur", e.blurhash ?: "").put("thumb", e.thumb ?: "")
            .put("cap", e.caption).put("once", e.viewOnce).put("kind", e.kind).put("poster", e.poster ?: "").put("dur", e.duration).put("wave", e.wave.joinToString(",")))
        prefs?.edit()?.apply { if (list.isEmpty()) remove(uid) else putString(uid, a.toString()) }?.apply()
    }

    fun add(uid: String, e: Entry) = synchronized(lock) {
        val l = read(uid); l.removeAll { it.clientId == e.clientId }; l.add(e); write(uid, l)
    }

    fun remove(uid: String, clientId: String) = synchronized(lock) {
        val l = read(uid); if (l.removeAll { it.clientId == clientId }) write(uid, l)
    }

    fun all(uid: String): List<Entry> = synchronized(lock) { read(uid) }

    fun has(uid: String, clientId: String): Boolean = all(uid).any { it.clientId == clientId }

    fun wipe(c: Context) { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply() }
}
