package com.fariin.app.data

import com.fariin.app.crypto.EncMeta
import com.fariin.app.crypto.Wire

/**
 * One row of `conversations/{cid}/messages`, as Kulan-iOS Models.swift `Message(id:data:cid:crypto:)`
 * reads it. Text fields arrive already opened by the caller's [open] (the crypto port), so this
 * type stays plain and testable.
 *
 * Media (photo, video, voice) carries its iOS fields: `enc` (the file's EncMeta), `width` and
 * `height` in pixels, the sealed `blurhash` and `thumb` (opened here like the caption), the
 * video's poster (`thumbUrl` + `thumbEnc`), `duration`, `waveform`, `uploading`, and an album's
 * `album` items.
 *
 * TODO (iOS has these): file, gif and sticker bodies beyond a label, link previews, reactions, view-once, story replies
 * and story mentions, forwarded-from-channel, delete-for-me.
 * Contact and location cards are sealed text markers, read by CardRules.
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
    /** uids @-mentioned in a group message, in the clear (iOS Message.mentions; MentionRules). */
    val mentions: List<String> = emptyList(),
    val held: Boolean = false,
    val imageUrl: String? = null,
    val videoUrl: String? = null,
    val audioUrl: String? = null,
    val fileUrl: String? = null,
    val fileName: String? = null,
    /** The file's envelope (`enc`): sealed for a 1:1 or group, `v: 0` in a plain group. */
    val enc: EncMeta? = null,
    /** Pixel size of the photo or video (iOS writes Doubles). */
    val width: Double? = null,
    val height: Double? = null,
    /** Opened BlurHash string, or null when absent or unreadable. */
    val blurhash: String? = null,
    /** Opened base64 of the ~40 px inline JPEG, or null. */
    val thumb: String? = null,
    /** A video's poster frame and its envelope. */
    val thumbUrl: String? = null,
    val thumbEnc: EncMeta? = null,
    /** Seconds, for voice and video. */
    val duration: Double? = null,
    /** Voice bars, 0...100 each. */
    val waveform: List<Int> = emptyList(),
    /** The message is out but its file is still on the way (voice, video). */
    val uploading: Boolean = false,
    val viewOnce: Boolean = false,
    /** A view-once photo the server has burned (consumeOnceImage strips the file and sets this). */
    val consumed: Boolean = false,
    /** A file's size in bytes, written in the clear by the sender (iOS sendFile). */
    val fileSize: Long? = null,
    val album: List<AlbumItem> = emptyList(),
    /** uid to opened emoji (each sealed by its reactor; an unopened one is left out). */
    val reactions: Map<String, String> = emptyMap(),
    /** A channel post's views, counted by the server (functions-groups channelViews). */
    val views: Int = 0,
    /** A channel post's reactions as counts only: who picked what is never on the post. */
    val reactionCounts: Map<String, Int> = emptyMap(),
    /** When the disappearing timer takes this message (server `expiresAt`, ms), or null. */
    val expiresAtMillis: Double? = null,
    /** A timer notice carries the value it set (0 = turned off); null on every other message. */
    val disappearSeconds: Int? = null,
    // ---- Polls (PollRules): the question and options ride sealed in `text`; these are clear.
    /** The creator tapped Stop Poll (`pollClosed`). */
    val pollClosed: Boolean = false,
    /** A channel poll's tally by the server: option index to voters, all voters, when counted (ms). */
    val pollCounts: Map<Int, Int> = emptyMap(),
    val pollVoters: Int = 0,
    val pollTallyAt: Double = 0.0,
    // ---- Stickers (iOS StickerSend): a built-in one names its pack and file, every phone draws its
    // own copy; `imageUrl` is the public copy. One I made (`stickerPack == "mine"`) is a sealed PNG.
    val stickerPack: String? = null,
    val stickerId: String? = null,
    val stickerEmoji: String? = null,
    val stickerAnimated: Boolean = false,
    // ---- A call record (`type == "call"`, iOS Message call fields).
    val callerUid: String? = null,
    val callOutcome: String? = null,
    val callVideo: Boolean = false,
    val callDuration: Int? = null,
    val callJoinedUids: List<String>? = null,
    val callRungUids: List<String>? = null,
    /** The sender's link card, opened (iOS LinkPreviewData); null when absent or unreadable. */
    val linkPreview: LinkPreview? = null,
    /** Local only: the plain bytes of a photo or note I am sending, drawn before the upload ends. */
    val localPath: String? = null,
    /** Local only: a send of mine that has not reached the server, or failed. */
    val sendState: SendState? = null,
) {
    enum class SendState { SENDING, FAILED }

    /** iOS LinkPreviewData: fetched and sealed by the sender; the picture is a sealed file like a photo's. */
    data class LinkPreview(val url: String, val title: String, val desc: String,
                           val imageUrl: String? = null, val imageEnc: EncMeta? = null) {
        val host: String get() = StickerGifRules.host(url)
    }

    /** iOS isSticker: a sticker names both its pack and its file. */
    val isSticker: Boolean get() = !deleted && type == "sticker" && !stickerPack.isNullOrEmpty() && !stickerId.isNullOrEmpty()
    /** iOS isGif: the public GIF link (not end-to-end encrypted). */
    val isGif: Boolean get() = !deleted && type == "gif" && !imageUrl.isNullOrEmpty()

    /** [isStatus]: the quote points at a story; [storyThumb] its plain picture URL (iOS ReplyRef). */
    data class ReplyRef(
        val id: String,
        val authorId: String,
        val text: String,
        val isStatus: Boolean = false,
        val storyThumb: String? = null,
        val isReaction: Boolean = false,
    )

    /** One album tile; for a video tile [imageUrl]/[enc] are its poster, [videoUrl]/[videoEnc] the clip. */
    data class AlbumItem(
        val imageUrl: String, val enc: EncMeta?, val width: Double?, val height: Double?,
        val videoUrl: String? = null, val videoEnc: EncMeta? = null, val duration: Double? = null,
    ) {
        val isVideo: Boolean get() = videoUrl != null
    }

    enum class MediaKind { PHOTO, VIDEO, VOICE, ALBUM, FILE, VIEW_ONCE }

    /** Which media bubble draws this message, or null for a text-style bubble. */
    val mediaKind: MediaKind?
        get() = when {
            deleted -> null
            type == "album" && album.isNotEmpty() -> MediaKind.ALBUM
            viewOnce && type == "image" -> MediaKind.VIEW_ONCE
            type == "image" || (type == null && imageUrl != null) -> MediaKind.PHOTO
            type == "file" -> MediaKind.FILE
            type == "video" -> MediaKind.VIDEO
            type == "audio" -> MediaKind.VOICE
            else -> null
        }

    /** The poll this message carries (iOS Message.poll), parsed once per copy. */
    val poll: PollCard? by lazy { if (deleted) null else PollRules.parse(text) }

    /** An optimistic copy and its server echo share the clientId, so the row updates in place. */
    val rowId: String get() = clientId ?: id

    val isSystem: Boolean get() = type == "system"
    val isCall: Boolean get() = type == "call"

    /** The words drawn in the bubble. Media is a label until the media bubbles are ported. */
    val bodyText: String
        get() = when {
            deleted -> ""
            poll != null -> PollRules.safeText(poll!!)   // iOS safeText: never the raw marker
            type == "sticker" -> StickerGifRules.stickerLabel(stickerEmoji)
            type == "gif" -> "GIF"   // before the imageUrl check below: a GIF carries one too
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
                    isStatus = r["isStatus"] as? Boolean ?: false,
                    storyThumb = (r["storyThumb"] as? String)?.ifEmpty { null },
                    isReaction = r["isReaction"] as? Boolean ?: false,
                )
            }
            // Sealed like the caption; a sentinel (key not warm, tampered) is no placeholder at all.
            fun openOptional(key: String): String? {
                val v = data[key] as? String ?: return null
                if (v.isEmpty()) return null
                val clear = open(v, author)
                return clear.takeUnless { it.isEmpty() || it == Wire.NOT_READY || it == Wire.LOCKED }
            }
            val album = (data["album"] as? List<*>)?.mapNotNull { item ->
                val m = item as? Map<*, *> ?: return@mapNotNull null
                val url = m["imageUrl"] as? String ?: return@mapNotNull null
                val video = (m["kind"] as? String) == "video"
                AlbumItem(url, EncMeta.fromMap(m["enc"] as? Map<*, *>),
                    (m["width"] as? Number)?.toDouble(), (m["height"] as? Number)?.toDouble(),
                    videoUrl = if (video) m["videoUrl"] as? String else null,
                    videoEnc = if (video) EncMeta.fromMap(m["videoEnc"] as? Map<*, *>) else null,
                    duration = (m["duration"] as? Number)?.toDouble())
            } ?: emptyList()
            // iOS: the link card's words are sealed like the caption; a sentinel drops the whole card.
            val linkPreview = (data["linkPreview"] as? Map<*, *>)?.let { lp ->
                fun openLp(key: String): String {
                    val v = lp[key] as? String ?: return ""
                    if (v.isEmpty()) return ""
                    val clear = open(v, author)
                    return if (clear == Wire.NOT_READY || clear == Wire.LOCKED) "" else clear
                }
                val url = openLp("url")
                if (url.isEmpty()) null else LinkPreview(url, openLp("title"), openLp("desc"),
                    (lp["imageUrl"] as? String)?.ifEmpty { null }, EncMeta.fromMap(lp["imageEnc"] as? Map<*, *>))
            }
            val sticker = type == "sticker"
            return Message(
                id = id,
                authorId = author,
                // A sticker's sealed `text` is only what an older build shows instead (iOS): dropped.
                text = if (sticker) "" else text,
                stickerPack = if (sticker) data["stickerPack"] as? String else null,
                stickerId = if (sticker) data["stickerId"] as? String else null,
                stickerEmoji = if (sticker) data["emoji"] as? String else null,
                stickerAnimated = sticker && data["animated"] == true,
                callerUid = data["callerUid"] as? String,
                callOutcome = data["callOutcome"] as? String,
                callVideo = data["callVideo"] as? Boolean ?: false,
                callDuration = (data["callDuration"] as? Number)?.toInt(),
                callJoinedUids = (data["joinedUids"] as? List<*>)?.filterIsInstance<String>(),
                callRungUids = (data["rungUids"] as? List<*>)?.filterIsInstance<String>(),
                linkPreview = linkPreview,
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
                mentions = (data["mentions"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
                held = data["held"] as? Boolean ?: false,
                imageUrl = data["imageUrl"] as? String,
                videoUrl = data["videoUrl"] as? String,
                audioUrl = data["audioUrl"] as? String,
                fileUrl = data["fileUrl"] as? String,
                fileName = data["fileName"] as? String,
                enc = EncMeta.fromMap(data["enc"] as? Map<*, *>),
                width = (data["width"] as? Number)?.toDouble(),
                height = (data["height"] as? Number)?.toDouble(),
                blurhash = openOptional("blurhash"),
                thumb = openOptional("thumb"),
                thumbUrl = data["thumbUrl"] as? String,
                thumbEnc = EncMeta.fromMap(data["thumbEnc"] as? Map<*, *>),
                duration = (data["duration"] as? Number)?.toDouble(),
                waveform = (data["waveform"] as? List<*>)?.mapNotNull { (it as? Number)?.toInt() } ?: emptyList(),
                uploading = data["uploading"] as? Boolean ?: false,
                viewOnce = data["viewOnce"] as? Boolean ?: false,
                consumed = data["consumed"] as? Boolean ?: false,
                fileSize = (data["fileSize"] as? Number)?.toLong(),
                views = maxOf(0, (data["views"] as? Number)?.toInt() ?: 0),
                reactionCounts = (data["reactionCounts"] as? Map<*, *>)?.entries?.mapNotNull { (k, v) ->
                    val n = (v as? Number)?.toInt() ?: 0
                    if (k is String && n > 0) k to n else null
                }?.toMap() ?: emptyMap(),
                album = album,
                expiresAtMillis = Conversation.millis(data["expiresAt"]).takeIf { it > 0 },
                disappearSeconds = (data["disappearSeconds"] as? Number)?.toInt(),
                // Polls: Stop Poll, and a channel poll's server tally (iOS Polls.swift listenChannel).
                pollClosed = data["pollClosed"] as? Boolean ?: false,
                pollCounts = (data["pollCounts"] as? Map<*, *>)?.entries?.mapNotNull { (k, v) ->
                    val i = (k as? String)?.toIntOrNull()
                    val n = (v as? Number)?.toInt() ?: 0
                    if (i != null && n > 0) i to n else null
                }?.toMap() ?: emptyMap(),
                pollVoters = (data["pollVoters"] as? Number)?.toInt() ?: 0,
                pollTallyAt = (data["pollTallyAt"] as? Number)?.toDouble() ?: 0.0,
                reactions = (data["reactions"] as? Map<*, *>)?.mapNotNull { (k, v) ->
                    val uid = k as? String ?: return@mapNotNull null
                    val raw = v as? String ?: return@mapNotNull null
                    val e = if (raw.startsWith("enc")) open(raw, uid) else raw
                    if (e.isEmpty() || e == Wire.NOT_READY || e == Wire.LOCKED) null else uid to e
                }?.toMap() ?: emptyMap(),
            )
        }
    }
}
