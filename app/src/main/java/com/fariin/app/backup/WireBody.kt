package com.fariin.app.backup

import com.fariin.app.crypto.Wire
import com.fariin.app.data.Message

/**
 * The shared "wire" message body of the backup archive (device-storage plan, agreed with iOS
 * 2026-11-12, iOS WireBody.swift): the Firestore message document's field names, with createdAt /
 * expiresAt / clientTs as ms numbers and every sealed value written OPEN (text, fileName, thumb,
 * blurhash, fmt, linkPreview words, replyTo text, reactions, fwd as its map). A value that is the
 * default (false, 0, "", empty list or map, none) is left out; always present: id, cid, authorId,
 * createdAt, text. "🔒" and "…" are the two unreadable texts.
 */
object WireBody {

    /** Message -> wire map. */
    fun toWire(m: Message, cid: String): Map<String, Any?> {
        val w = LinkedHashMap<String, Any?>()
        fun put(k: String, v: Any?) {
            when (v) {
                null -> {}
                is Boolean -> if (v) w[k] = true
                is Number -> if (v.toDouble() != 0.0) w[k] = v
                is String -> if (v.isNotEmpty()) w[k] = v
                is Collection<*> -> if (v.isNotEmpty()) w[k] = v
                is Map<*, *> -> if (v.isNotEmpty()) w[k] = v
                else -> w[k] = v
            }
        }
        w["id"] = m.id; w["cid"] = cid; w["authorId"] = m.authorId
        w["createdAt"] = m.createdAtMillis.toLong()
        w["text"] = if (m.deleted) "" else m.text
        put("type", m.type); put("clientId", m.clientId); put("clientTs", m.clientTs?.toLong())
        m.replyTo?.let { r ->
            put("replyTo", buildMap<String, Any?> {
                put("id", r.id); put("authorId", r.authorId); put("text", r.text)
                if (r.isStatus) put("isStatus", true); if (r.isReaction) put("isReaction", true)
                r.storyThumb?.let { put("storyThumb", it) }
            })
        }
        put("deleted", m.deleted); put("deletedBy", m.deletedBy); put("edited", m.edited)
        put("forwarded", m.forwarded); m.forwardSource?.let { put("fwd", it.map()) }
        put("mentions", m.mentions); put("held", m.held)
        put("imageUrl", m.imageUrl); put("videoUrl", m.videoUrl); put("audioUrl", m.audioUrl)
        put("fileUrl", m.fileUrl); put("fileName", m.fileName); put("fileSize", m.fileSize)
        m.enc?.let { put("enc", it.asMap()) }
        put("width", m.width); put("height", m.height); put("blurhash", m.blurhash)
        put("thumb", m.thumb); put("thumbUrl", m.thumbUrl); m.thumbEnc?.let { put("thumbEnc", it.asMap()) }
        put("duration", m.duration); put("waveform", m.waveform)
        put("viewOnce", m.viewOnce); put("consumed", m.consumed); put("views", m.views)
        put("quoteCount", m.quoteCount); put("quoteFaces", m.quoteFaces)
        put("replyCount", m.replyCount); m.repliesOff?.let { w["repliesOff"] = it }; put("replyFaces", m.replyFaces)
        put("reactionCounts", m.reactionCounts); put("reactions", m.reactions)
        if (m.album.isNotEmpty()) put("album", m.album.map { a ->
            buildMap<String, Any?> {
                put("kind", if (a.videoUrl != null) "video" else "image"); put("imageUrl", a.imageUrl)
                a.enc?.let { put("enc", it.asMap()) }; a.width?.let { put("width", it) }; a.height?.let { put("height", it) }
                a.videoUrl?.let { put("videoUrl", it) }; a.videoEnc?.let { put("videoEnc", it.asMap()) }
                a.duration?.let { put("duration", it) }
            }
        })
        put("expiresAt", m.expiresAtMillis?.toLong()); put("disappearSeconds", m.disappearSeconds)
        put("pollClosed", m.pollClosed); put("pollCounts", m.pollCounts.mapKeys { it.key.toString() })
        put("pollVoters", m.pollVoters); put("pollTallyAt", m.pollTallyAt.takeIf { it > 0 }?.toLong())
        m.linkPreview?.let { lp ->
            put("linkPreview", buildMap<String, Any?> {
                put("url", lp.url); put("title", lp.title); put("desc", lp.desc)
                lp.imageUrl?.let { put("imageUrl", it) }; lp.imageEnc?.let { put("imageEnc", it.asMap()) }
            })
        }
        m.fwdChannel?.let { put("fwdChannel", Canonical.encode(mapOf("c" to it.cid, "t" to it.title, "p" to it.photoUrl, "u" to it.username))) }
        m.quoteId?.let { put("quote", mapOf("qid" to it)) }
        put("bs", m.bubbleStyle); put("bsd", m.bubbleDecor)
        m.storyMention?.let { put("storyMention", mapOf("id" to it.id, "thumb" to it.thumbUrl)) }
        put("fmt", com.fariin.app.data.TextFormat.serialize(m.bold, m.text.length))
        put("stickerPack", m.stickerPack); put("stickerId", m.stickerId); put("emoji", m.stickerEmoji); put("animated", m.stickerAnimated)
        put("callerUid", m.callerUid); put("callOutcome", m.callOutcome); put("callVideo", m.callVideo)
        put("callDuration", m.callDuration); put("joinedUids", m.callJoinedUids); put("rungUids", m.callRungUids)
        put("restrictRequest", m.isRestrictRequest)
        return w
    }

    /**
     * Wire map -> Message, through the app's own Firestore parser: every open value is handed in
     * marked as already opened, so the parser's sealed-only rules (a 1:1's words must be sealed,
     * bold only on sealed words) read it as the sealed value it was on the server.
     */
    fun fromWire(w: Map<String, Any?>, cid: String): Message? {
        val id = w["id"] as? String ?: return null
        val mark = Wire.ENC1
        fun sealed(v: Any?): Any? = (v as? String)?.let { if (it.isEmpty()) it else mark + it } ?: v
        val data = HashMap<String, Any?>(w)
        for (k in listOf("text", "fmt", "thumb", "blurhash")) data[k] = sealed(w[k])
        (w["replyTo"] as? Map<*, *>)?.let { r -> data["replyTo"] = r.toMutableMap().also { it["text"] = sealed(r["text"]) } }
        (w["linkPreview"] as? Map<*, *>)?.let { lp ->
            data["linkPreview"] = lp.toMutableMap().also { m -> for (k in listOf("url", "title", "desc")) m[k] = sealed(lp[k]) }
        }
        (w["fwdChannel"] as? String)?.let { data["fwdChannel"] = sealed(it) }
        val m = Message.fromFirestore(id, data, cid) { raw, _ -> if (raw.startsWith(mark)) raw.removePrefix(mark) else raw }
        return if (m.text == "🔒" || m.text == "…") m.copy(text = Wire.LOCKED) else m
    }
}
