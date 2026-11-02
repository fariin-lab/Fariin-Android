package com.fariin.app.local

import com.fariin.app.crypto.EncMeta
import com.fariin.app.data.Message
import com.google.firebase.Timestamp
import org.json.JSONArray
import org.json.JSONObject

/**
 * The on-disk shapes of the local caches, kept pure so unit tests cover them.
 *
 * Chat list: the conversation documents as Firestore gave them (their previews are still sealed;
 * the list opens them when it draws, as on iOS ConversationsDiskCache). A Timestamp is written as
 * {"$ts": [seconds, nanos]} so it reads back as a Timestamp and every parser sees the same types.
 *
 * Messages: the already-opened Message rows of one chat (iOS ThreadMessageCache keeps the
 * decrypted window too). Local-only fields (a file path, a send state) are never written.
 */
object LocalJson {
    private const val TS = "\$ts"

    fun value(v: Any?): Any? = when (v) {
        null -> JSONObject.NULL
        is Timestamp -> JSONObject().put(TS, JSONArray().put(v.seconds).put(v.nanoseconds))
        is String, is Boolean, is Int, is Long -> v
        is Double -> if (v.isNaN() || v.isInfinite()) JSONObject.NULL else v
        is Float -> value(v.toDouble())
        is Number -> v.toDouble()
        is Map<*, *> -> JSONObject().also { o -> for ((k, x) in v) if (k is String) value(x)?.let { o.put(k, it) } }
        is List<*> -> JSONArray().also { a -> for (x in v) a.put(value(x) ?: JSONObject.NULL) }
        else -> null   // a type no list or thread parser reads (a GeoPoint, a reference): left out
    }

    fun map(m: Map<String, Any?>): JSONObject = value(m) as JSONObject

    fun back(v: Any?): Any? = when (v) {
        null, JSONObject.NULL -> null
        is JSONObject -> {
            val ts = v.optJSONArray(TS)
            if (ts != null && v.length() == 1) Timestamp(ts.getLong(0), ts.getInt(1))
            else buildMap<String, Any?> { for (k in v.keys()) put(k, back(v.get(k))) }
        }
        is JSONArray -> (0 until v.length()).map { back(v.get(it)) }
        is Int -> v.toLong()   // Firestore hands integers back as Long
        else -> v
    }

    @Suppress("UNCHECKED_CAST")
    fun unmap(o: JSONObject): Map<String, Any?> = back(o) as Map<String, Any?>

    // ------------------------------------------------------------------ chat list

    fun encodeChats(docs: List<Pair<String, Map<String, Any?>>>): String =
        JSONArray().also { a -> for ((id, d) in docs) a.put(JSONObject().put("id", id).put("d", map(d))) }.toString()

    fun decodeChats(s: String): List<Pair<String, Map<String, Any?>>> {
        val a = JSONArray(s)
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            id to unmap(o.optJSONObject("d") ?: JSONObject())
        }
    }

    // ------------------------------------------------------------------ messages

    private fun enc(e: EncMeta?): Any? = e?.let { map(it.asMap()) }
    private fun encBack(o: JSONObject?): EncMeta? = o?.let { EncMeta.fromMap(unmap(it)) }

    fun encodeMessage(m: Message): JSONObject = JSONObject().apply {
        put("id", m.id); put("a", m.authorId); put("t", m.text); put("c", m.createdAtMillis)
        if (!m.hasServerTime) put("pendingTime", true)
        m.type?.let { put("type", it) }
        m.clientTs?.let { put("cts", it) }
        m.clientId?.let { put("cid", it) }
        m.replyTo?.let { put("re", JSONObject().put("id", it.id).put("a", it.authorId).put("t", it.text)) }
        if (m.deleted) put("del", true)
        m.deletedBy?.let { put("delBy", it) }
        if (m.edited) put("ed", true)
        if (m.forwarded) put("fw", true)
        if (m.held) put("held", true)
        m.imageUrl?.let { put("img", it) }
        m.videoUrl?.let { put("vid", it) }
        m.audioUrl?.let { put("aud", it) }
        m.fileUrl?.let { put("file", it) }
        m.fileName?.let { put("fname", it) }
        enc(m.enc)?.let { put("enc", it) }
        m.width?.let { put("w", it) }
        m.height?.let { put("h", it) }
        m.blurhash?.let { put("bh", it) }
        m.thumb?.let { put("th", it) }
        m.thumbUrl?.let { put("thu", it) }
        enc(m.thumbEnc)?.let { put("the", it) }
        m.duration?.let { put("dur", it) }
        if (m.waveform.isNotEmpty()) put("wave", JSONArray(m.waveform))
        if (m.uploading) put("up", true)
        if (m.viewOnce) put("once", true)
        if (m.consumed) put("spent", true)
        m.fileSize?.let { put("fsize", it) }
        if (m.album.isNotEmpty()) put("album", JSONArray().also { a ->
            for (it in m.album) a.put(JSONObject().put("u", it.imageUrl).apply {
                enc(it.enc)?.let { e -> put("e", e) }
                it.width?.let { w -> put("w", w) }
                it.height?.let { h -> put("h", h) }
                it.videoUrl?.let { v -> put("v", v) }
                enc(it.videoEnc)?.let { e -> put("ve", e) }
                it.duration?.let { d -> put("d", d) }
            })
        })
        if (m.reactions.isNotEmpty()) put("rx", JSONObject(m.reactions))
    }

    fun decodeMessage(o: JSONObject): Message? {
        val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return null
        fun s(k: String): String? = if (o.has(k) && !o.isNull(k)) o.getString(k) else null
        fun d(k: String): Double? = if (o.has(k) && !o.isNull(k)) o.getDouble(k) else null
        return Message(
            id = id, authorId = o.optString("a"), text = o.optString("t"), type = s("type"),
            createdAtMillis = o.optDouble("c", 0.0), hasServerTime = !o.optBoolean("pendingTime"),
            clientTs = d("cts"), clientId = s("cid"),
            replyTo = o.optJSONObject("re")?.let { Message.ReplyRef(it.optString("id"), it.optString("a"), it.optString("t")) },
            deleted = o.optBoolean("del"), deletedBy = s("delBy"), edited = o.optBoolean("ed"),
            forwarded = o.optBoolean("fw"), held = o.optBoolean("held"),
            imageUrl = s("img"), videoUrl = s("vid"), audioUrl = s("aud"), fileUrl = s("file"), fileName = s("fname"),
            enc = encBack(o.optJSONObject("enc")), width = d("w"), height = d("h"),
            blurhash = s("bh"), thumb = s("th"), thumbUrl = s("thu"), thumbEnc = encBack(o.optJSONObject("the")),
            duration = d("dur"),
            waveform = o.optJSONArray("wave")?.let { a -> (0 until a.length()).map { a.getInt(it) } } ?: emptyList(),
            uploading = o.optBoolean("up"), viewOnce = o.optBoolean("once"),
            consumed = o.optBoolean("spent"), fileSize = if (o.has("fsize")) o.getLong("fsize") else null,
            album = o.optJSONArray("album")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    val x = a.optJSONObject(i) ?: return@mapNotNull null
                    Message.AlbumItem(x.optString("u"), encBack(x.optJSONObject("e")),
                        if (x.has("w")) x.getDouble("w") else null, if (x.has("h")) x.getDouble("h") else null,
                        videoUrl = if (x.has("v")) x.getString("v") else null, videoEnc = encBack(x.optJSONObject("ve")),
                        duration = if (x.has("d")) x.getDouble("d") else null)
                }
            } ?: emptyList(),
            reactions = o.optJSONObject("rx")?.let { r -> r.keys().asSequence().associateWith { r.getString(it) } } ?: emptyMap(),
        )
    }

    fun encodeMessages(list: List<Message>): String =
        JSONArray().also { a -> for (m in list) a.put(encodeMessage(m)) }.toString()

    fun decodeMessages(s: String): List<Message> {
        val a = JSONArray(s)
        return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::decodeMessage) }
    }

    /** What a chat's file keeps: server-known rows only (no unsent copy, no local file), newest
     *  [cap] of them, oldest first as the thread holds them. */
    fun windowToKeep(sortedOldestFirst: List<Message>, cap: Int): List<Message> =
        sortedOldestFirst.filter { it.sendState == null && it.localPath == null && !it.uploading }.takeLast(cap)
}
