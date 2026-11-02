package com.fariin.app.media

import com.fariin.app.crypto.EncMeta

/**
 * The Firestore writes of a photo and a voice note, field for field as Kulan-iOS
 * ChatService.sendImage / sendAudio write them, so they pass the same rules and the iPhone reads
 * them with no change. Server values come in as parameters so this stays testable without
 * Firebase: [serverTime] is `FieldValue.serverTimestamp()`, [increment] `FieldValue.increment(1)`.
 */
object MediaMessages {
    /** sendImage's message: `type image`, `enc`, caption as `text`, `imageUrl`, size, sealed previews. */
    fun image(
        me: String, url: String, meta: EncMeta, captionCipher: String, clientTs: Double, clientId: String?,
        width: Int?, height: Int?, blurSealed: String?, thumbSealed: String?, serverTime: Any,
    ): Map<String, Any> = buildMap {
        put("type", "image")
        put("enc", meta.asMap())
        put("text", captionCipher)
        put("imageUrl", url)
        put("authorId", me)
        put("createdAt", serverTime)
        put("clientTs", clientTs)
        clientId?.let { put("clientId", it) }
        if (width != null && height != null) { put("width", width.toDouble()); put("height", height.toDouble()) }
        if (!blurSealed.isNullOrEmpty()) put("blurhash", blurSealed)
        if (!thumbSealed.isNullOrEmpty()) put("thumb", thumbSealed)
    }

    /** sendAudio's message, written BEFORE the upload with `uploading: true` and no `audioUrl`. */
    fun audio(
        me: String, meta: EncMeta, duration: Double, waveform: List<Int>, clientTs: Double, clientId: String?,
        serverTime: Any,
    ): Map<String, Any> = buildMap {
        put("type", "audio")
        put("duration", duration)
        put("waveform", waveform)
        put("enc", meta.asMap())
        put("text", "")
        put("authorId", me)
        put("createdAt", serverTime)
        put("clientTs", clientTs)
        put("uploading", true)
        clientId?.let { put("clientId", it) }
    }

    /** attachMedia: the URL lands and `uploading` goes ([delete] is `FieldValue.delete()`). */
    fun attachAudio(url: String, delete: Any): Map<String, Any> = mapOf("audioUrl" to url, "uploading" to delete)

    /**
     * The conversation update both sends make: the plain marker as `lastMessage` (iOS writes these
     * markers in the clear for media), `lastSender`, `updatedAt`, and +1 unread for everyone else.
     * A photo also carries `lastImageUrl` + `lastImageEnc` for the chat list thumbnail.
     */
    fun convUpdate(
        me: String, lastMessage: String, others: Collection<String>, serverTime: Any, increment: Any,
        lastImageUrl: String? = null, lastImageEnc: EncMeta? = null,
    ): Map<String, Any> = buildMap {
        put("lastMessage", lastMessage)
        put("lastSender", me)
        put("updatedAt", serverTime)
        if (lastImageUrl != null && lastImageEnc != null) {
            put("lastImageUrl", lastImageUrl)
            put("lastImageEnc", lastImageEnc.asMap())
        }
        for (m in others) if (m != me) put("unreadCount.$m", increment)
    }
}
