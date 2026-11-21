package com.fariin.app.data

import kotlinx.coroutines.tasks.await

/**
 * Tag Search (iOS 2026-11-20, free for everyone): #hashtags and $cashtags in a channel post open the
 * posts of every PUBLIC channel that use the tag. Server: callable `tagPosts({tag, scope, cursor?})`
 * (me-central1) answers `{state, rows:[{channel, post}], next, count}`, 20 a page, newest first.
 * The tag goes with its sigil, lowercased.
 */
object TagSearch {
    /**
     * `#` then letters, digits or _ with at least one letter or _, not glued to a word or another `#`;
     * `$` then 1 to 8 ASCII letters, not glued, not followed by a letter or digit ($100 is no tag).
     */
    private val HASH = Regex("""(?<![\p{L}\p{N}_#&])#([\p{L}\p{N}_]{1,64})""")
    private val CASH = Regex("""(?<![\p{L}\p{N}_$])\$([A-Za-z]{1,8})(?![\p{L}\p{N}_])""")

    /** Every tag in [text], in order, as UTF-16 ranges with the lowercased tag. */
    fun find(text: String): List<TextLinks.Span> {
        if (text.isEmpty() || (!text.contains('#') && !text.contains('$'))) return emptyList()
        val out = ArrayList<TextLinks.Span>()
        for (m in HASH.findAll(text)) {
            val body = m.groupValues[1]
            if (body.none { it.isLetter() || it == '_' }) continue
            out += TextLinks.Span(m.range.first, m.range.last + 1, TextLinks.Kind.TAG, m.value.lowercase())
        }
        for (m in CASH.findAll(text)) out += TextLinks.Span(m.range.first, m.range.last + 1, TextLinks.Kind.TAG, m.value.lowercase())
        return out.sortedBy { it.start }
    }

    data class Channel(val cid: String, val name: String, val photoUrl: String?, val verified: Boolean, val publicName: String?)
    data class Post(val mid: String, val text: String, val type: String, val imageUrl: String?, val thumbUrl: String?,
                    val sealedMedia: Boolean, val views: Int, val createdAt: Long, val edited: Boolean, val albumCount: Int,
                    /** The server's post as it came (stored field names), for [message]. */
                    val raw: Map<String, Any?> = emptyMap())
    data class Row(val channel: Channel, val post: Post)
    data class Page(val state: String, val rows: List<Row>, val next: String?, val count: Int)

    /**
     * The result's post as the app's own [Message], through the same reader a channel post from
     * Firestore goes through (iOS TagPosts.message). Only the time (ms here, a Timestamp there) and
     * the media envelope differ: channel media is stored in the clear, so a missing envelope is the
     * plain one.
     */
    fun message(r: Row): Message? {
        val p = r.post.raw
        if (r.post.mid.isEmpty()) return null
        val d = HashMap<String, Any?>(p.filterValues { it != null })
        d.remove("mid"); d.remove("clientId")
        if (d["authorId"] !is String) d["authorId"] = ""
        val ms = (p["createdAt"] as? Number)?.toLong() ?: 0L
        d["createdAt"] = com.google.firebase.Timestamp(java.util.Date(if (ms > 0) ms else System.currentTimeMillis()))
        val plain = com.fariin.app.crypto.EncMeta.PLAIN.asMap()
        fun has(k: String, m: Map<*, *>) = !(m[k] as? String).isNullOrEmpty()
        if (d["enc"] == null && (has("imageUrl", d) || has("videoUrl", d))) d["enc"] = plain
        if (d["thumbEnc"] == null && has("thumbUrl", d)) d["thumbEnc"] = plain
        (d["album"] as? List<*>)?.let { album ->
            d["album"] = album.mapNotNull { (it as? Map<*, *>)?.let { item ->
                val i = HashMap<String, Any?>(item.entries.mapNotNull { (k, v) -> (k as? String)?.takeIf { v != null }?.let { it to v } }.toMap())
                if (i["enc"] == null) i["enc"] = plain
                if (i["videoEnc"] == null && has("videoUrl", i)) i["videoEnc"] = plain
                i
            } }
        }
        return runCatching { Message.fromFirestore(r.post.mid, d, r.channel.cid) { raw, _ -> raw } }.getOrNull()
    }

    suspend fun fetch(tag: String, following: Boolean, cursor: String?): Page {
        val args = hashMapOf<String, Any>("tag" to tag.lowercase(), "scope" to if (following) "following" else "all")
        cursor?.let { args["cursor"] = it }
        val d = com.google.firebase.functions.FirebaseFunctions.getInstance(AuthRepository.FUNCTIONS_REGION)
            .getHttpsCallable("tagPosts").call(args).await().getData() as? Map<*, *> ?: error("no answer")
        val rows = (d["rows"] as? List<*>).orEmpty().mapNotNull { r ->
            val m = r as? Map<*, *> ?: return@mapNotNull null
            val c = m["channel"] as? Map<*, *> ?: return@mapNotNull null
            val p = m["post"] as? Map<*, *> ?: return@mapNotNull null
            val cid = c["cid"] as? String ?: return@mapNotNull null
            val mid = p["mid"] as? String ?: return@mapNotNull null
            val text = (p["text"] as? String).orEmpty()
            Row(
                Channel(cid, (c["name"] as? String).orEmpty(), (c["photoUrl"] as? String)?.takeIf { it.isNotEmpty() },
                    c["verified"] == true, c["publicName"] as? String),
                Post(mid, if (text.startsWith(com.fariin.app.crypto.Wire.ENC1) || text.startsWith("enc")) "" else text,
                    (p["type"] as? String).orEmpty(),
                    (p["imageUrl"] as? String)?.takeIf { it.startsWith("https://") },
                    (p["thumbUrl"] as? String)?.takeIf { it.startsWith("https://") },
                    p["enc"] != null || p["thumbEnc"] != null,
                    (p["views"] as? Number)?.toInt() ?: 0, (p["createdAt"] as? Number)?.toLong() ?: 0L,
                    p["edited"] == true, (p["album"] as? List<*>)?.size ?: 0,
                    p.entries.mapNotNull { (k, v) -> (k as? String)?.let { it to v } }.toMap()),
            )
        }
        return Page((d["state"] as? String) ?: "on", rows, (d["next"] as? String)?.takeIf { it.isNotEmpty() }, (d["count"] as? Number)?.toInt() ?: rows.size)
    }
}
