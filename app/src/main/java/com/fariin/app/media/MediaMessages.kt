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
        viewOnce: Boolean = false, forwarded: Boolean = false,
    ): Map<String, Any> = buildMap {
        put("type", "image")
        if (viewOnce) put("viewOnce", true)
        if (forwarded) put("forwarded", true)
        put("enc", meta.asMap())
        put("text", captionCipher)
        put("imageUrl", url)
        put("authorId", me)
        put("createdAt", serverTime)
        put("clientTs", clientTs)
        clientId?.let { put("clientId", it) }
        if (width != null && height != null) { put("width", width.toDouble()); put("height", height.toDouble()) }
        // A view-once photo publishes neither preview: both are a copy kept after the one view (iOS).
        if (!viewOnce && !blurSealed.isNullOrEmpty()) put("blurhash", blurSealed)
        if (!viewOnce && !thumbSealed.isNullOrEmpty()) put("thumb", thumbSealed)
    }

    /**
     * announceVideo: the poster is up, the clip is not. `type video`, the sealed poster
     * (`thumbUrl` + `thumbEnc`), length, size, caption, `uploading: true`; [attachVideo] adds the clip.
     */
    fun videoAnnounce(
        me: String, thumbUrl: String, thumbMeta: EncMeta, duration: Double, width: Double, height: Double,
        captionCipher: String, clientTs: Double, clientId: String?, blurSealed: String?, thumbSealed: String?,
        forwarded: Boolean, serverTime: Any,
    ): Map<String, Any> = buildMap {
        put("type", "video")
        put("thumbUrl", thumbUrl)
        put("thumbEnc", thumbMeta.asMap())
        put("duration", duration)
        put("width", width)
        put("height", height)
        put("text", captionCipher)
        put("authorId", me)
        put("createdAt", serverTime)
        put("clientTs", clientTs)
        put("uploading", true)
        if (!blurSealed.isNullOrEmpty()) put("blurhash", blurSealed)
        if (!thumbSealed.isNullOrEmpty()) put("thumb", thumbSealed)
        clientId?.let { put("clientId", it) }
        if (forwarded) put("forwarded", true)
    }

    /** attachVideo: the clip's URL and its own envelope, and `uploading` goes. */
    fun attachVideo(url: String, meta: EncMeta, delete: Any): Map<String, Any> =
        mapOf("videoUrl" to url, "enc" to meta.asMap(), "uploading" to delete)

    /** sendFile: name and size in the clear (as iOS), the bytes sealed, `uploading` until [attachFile]. */
    fun file(
        me: String, fileName: String, fileSize: Int, meta: EncMeta, clientTs: Double, clientId: String?,
        thumbUrl: String?, thumbMeta: EncMeta?, forwarded: Boolean, serverTime: Any,
    ): Map<String, Any> = buildMap {
        put("type", "file")
        put("fileName", fileName)
        put("fileSize", fileSize)
        put("enc", meta.asMap())
        put("text", "")
        put("authorId", me)
        put("createdAt", serverTime)
        put("clientTs", clientTs)
        put("uploading", true)
        if (thumbUrl != null && thumbMeta != null) { put("thumbUrl", thumbUrl); put("thumbEnc", thumbMeta.asMap()) }
        clientId?.let { put("clientId", it) }
        if (forwarded) put("forwarded", true)
    }

    fun attachFile(url: String, delete: Any): Map<String, Any> = mapOf("fileUrl" to url, "uploading" to delete)

    /** One finished album tile; a video tile also has its clip ([videoUrl], [videoMeta], [duration]). */
    data class Tile(
        val imageUrl: String, val imageMeta: EncMeta, val width: Double, val height: Double,
        val videoUrl: String? = null, val videoMeta: EncMeta? = null, val duration: Double = 0.0,
    ) {
        val isVideo get() = videoUrl != null && videoMeta != null
        fun asMap(mixed: Boolean): Map<String, Any> = buildMap {
            if (mixed) put("kind", if (isVideo) "video" else "image")
            put("imageUrl", imageUrl)
            put("enc", imageMeta.asMap())
            if (isVideo) { put("videoUrl", videoUrl!!); put("videoEnc", videoMeta!!.asMap()); put("duration", duration) }
            put("width", width)
            put("height", height)
        }
    }

    /**
     * sendAlbum (photos only: items without `kind`, plus `albumSizes`) or sendMixedAlbum (any
     * video in it: every item has `kind`). One caption, one blurhash and thumb for the first tile.
     */
    fun album(
        me: String, tiles: List<Tile>, captionCipher: String, clientTs: Double, clientId: String?,
        blurSealed: String?, thumbSealed: String?, forwarded: Boolean, serverTime: Any,
    ): Map<String, Any> = buildMap {
        val mixed = tiles.any { it.isVideo } || forwarded
        put("type", "album")
        put("album", tiles.map { it.asMap(mixed) })
        // iOS sendAlbum also writes `albumSizes` [[w, h]]; Firestore keeps no array inside an array
        // and the Android SDK refuses one, so it is left out: every reader takes the items' width/height.
        put("text", captionCipher)
        put("authorId", me)
        put("createdAt", serverTime)
        put("clientTs", clientTs)
        if (!blurSealed.isNullOrEmpty()) put("blurhash", blurSealed)
        if (!thumbSealed.isNullOrEmpty()) put("thumb", thumbSealed)
        clientId?.let { put("clientId", it) }
        if (forwarded) put("forwarded", true)
    }

    /** The chat list words for an album (iOS sendAlbum / sendMixedAlbum). */
    fun albumPreview(tiles: List<Tile>): String {
        val videos = tiles.count { it.isVideo }
        val photos = tiles.size - videos
        return when {
            videos > 0 && photos > 0 -> "🎬 ${tiles.size} Media"
            videos > 0 -> "🎥 $videos Video" + if (videos > 1) "s" else ""
            tiles.size == 1 -> "📷 Photo"
            else -> "📷 $photos Photos"
        }
    }

    /** `lastImages` for the list's stacked thumbnails: the first three covers, {imageUrl, enc}. */
    fun lastImages(tiles: List<Tile>): List<Map<String, Any>> =
        tiles.take(3).map { mapOf("imageUrl" to it.imageUrl, "enc" to it.imageMeta.asMap()) }

    /**
     * iOS isForward: a message is "Forwarded" when it goes to another chat, or when it had already
     * arrived forwarded (it stays forwarded wherever it goes, its own chat included).
     */
    fun isForward(alreadyForwarded: Boolean, sourceCid: String, targetCid: String) =
        alreadyForwarded || sourceCid != targetCid

    /** sendAudio's message, written BEFORE the upload with `uploading: true` and no `audioUrl`. */
    fun audio(
        me: String, meta: EncMeta, duration: Double, waveform: List<Int>, clientTs: Double, clientId: String?,
        serverTime: Any, forwarded: Boolean = false,
    ): Map<String, Any> = buildMap {
        if (forwarded) put("forwarded", true)
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
        lastImageUrl: String? = null, lastImageEnc: EncMeta? = null, lastImages: List<Map<String, Any>>? = null,
    ): Map<String, Any> = buildMap {
        if (!lastImages.isNullOrEmpty() && lastImages.size > 1) put("lastImages", lastImages)
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
