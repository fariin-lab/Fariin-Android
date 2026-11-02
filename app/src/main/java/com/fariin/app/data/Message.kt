package com.fariin.app.data

import com.fariin.app.crypto.Wire

/**
 * One row of `conversations/{cid}/messages`, as Kulan-iOS Models.swift `Message(id:data:cid:crypto:)`
 * reads it. Text fields arrive already opened by the caller's [open] (the crypto port), so this
 * type stays plain and testable.
 *
 * TODO (iOS has these): media bodies (photo, video, voice, file, album, gif, sticker) beyond a
 * label, link previews, polls, contact and location cards, reactions, view-once, story replies
 * and mentions, forwarded-from-channel, disappearing timers, delete-for-me.
 */
data class Message(
    val id: String,
    val authorId: String,
    /** Opened text (or the iOS sentinels "…" / the lock / "[old message]"). */
    val text: String,
    val type: String? = null,
    /** Server time in ms; for a write still in flight, this phone's time (see [hasServerTime]). */
    val createdAtMillis: Double,
    val hasServerTime: Boolean = true,
    /** The sender's tap time in ms (`clientTs`). */
    val clientTs: Double? = null,
    val clientId: String? = null,
    val replyTo: ReplyRef? = null,
    val deleted: Boolean = false,
    val deletedBy: String? = null,
    val edited: Boolean = false,
    val forwarded: Boolean = false,
    val held: Boolean = false,
    val imageUrl: String? = null,
    val videoUrl: String? = null,
    val audioUrl: String? = null,
    val fileUrl: String? = null,
    val fileName: String? = null,
    /** Local only: a send of mine that has not reached the server, or failed. */
    val sendState: SendState? = null,
) {
    enum class SendState { SENDING, FAILED }

    data class ReplyRef(val id: String, val authorId: String, val text: String)

    /** An optimistic copy and its server echo share the clientId, so the row updates in place. */
    val rowId: String get() = clientId ?: id

    val isSystem: Boolean get() = type == "system"
    val isCall: Boolean get() = type == "call"

    /** The words drawn in the bubble. Media is a label until the media bubbles are ported. */
    val bodyText: String
        get() = when {
            deleted -> ""
            text.isNotEmpty() -> text
            type == "image" || imageUrl != null -> "Photo"
            type == "video" || videoUrl != null -> "Video"
            type == "audio" || audioUrl != null -> "Voice message"
            type == "file" || fileUrl != null -> fileName ?: "File"
            type == "album" -> "Album"
            type == "gif" -> "GIF"
            else -> ""
        }

    companion object {
        const val NOT_ENCRYPTED = "This message wasn't encrypted and can't be shown."
        private val PERSON_TYPES = setOf("text", "image", "video", "audio", "file", "album", "gif")

        /**
         * Same reading rules as iOS. [open] is `crypto.decrypt(raw, cid, authorId)`; it is called
         * for the text and the reply quote, both sealed by this message's author.
         */
        fun fromFirestore(
            id: String, data: Map<String, Any?>, cid: String,
            nowMillis: Double = System.currentTimeMillis().toDouble(),
            open: (raw: String, authorId: String) -> String,
        ): Message {
            val author = data["authorId"] as? String ?: ""
            val raw = data["text"] as? String ?: ""
            val type = data["type"] as? String
            // 1:1 hardening (iOS 2026-10-23): a person's words in a 1:1 are always sealed. One stored
            // in the clear is not shown as if it were end-to-end encrypted.
            val text = if (!Wire.isGroupCid(cid) && raw.isNotEmpty() && !raw.startsWith(Wire.ENC1) &&
                !raw.startsWith(Wire.LEGACY) && (type ?: "text") in PERSON_TYPES) NOT_ENCRYPTED
            else if (raw.isEmpty()) "" else open(raw, author)
            val created = Conversation.millis(data["createdAt"])
            val reply = (data["replyTo"] as? Map<*, *>)?.let { r ->
                ReplyRef(
                    id = r["id"] as? String ?: "",
                    authorId = r["authorId"] as? String ?: "",
                    text = (r["text"] as? String)?.let { if (it.isEmpty()) "" else open(it, author) } ?: "",
                )
            }
            return Message(
                id = id,
                authorId = author,
                text = text,
                type = type,
                createdAtMillis = if (created > 0) created else nowMillis,
                hasServerTime = created > 0,
                clientTs = (data["clientTs"] as? Number)?.toDouble(),
                clientId = data["clientId"] as? String,
                replyTo = reply,
                deleted = data["deleted"] as? Boolean ?: false,
                deletedBy = data["deletedBy"] as? String,
                edited = data["edited"] as? Boolean ?: false,
                forwarded = data["forwarded"] as? Boolean ?: false,
                held = data["held"] as? Boolean ?: false,
                imageUrl = data["imageUrl"] as? String,
                videoUrl = data["videoUrl"] as? String,
                audioUrl = data["audioUrl"] as? String,
                fileUrl = data["fileUrl"] as? String,
                fileName = data["fileName"] as? String,
            )
        }
    }
}
