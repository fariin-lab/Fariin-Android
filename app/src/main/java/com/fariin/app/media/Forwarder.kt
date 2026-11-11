package com.fariin.app.media

import kotlinx.coroutines.tasks.await
import android.content.Context
import com.fariin.app.crypto.FariinCrypto
import com.fariin.app.data.Message
import com.fariin.app.data.ThreadRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Forward (iOS ChatService.forwardMessage): the source's file is opened with the SOURCE chat's
 * keys, then sent again as a new message in each target, sealed for that target (1:1 enc1, group
 * seal, plain only in a plain group). Nothing is copied server side, so no target ever gets a
 * file it cannot open and the server never sees plain bytes. "Forwarded" follows iOS isForward.
 *
 * TODO (iOS has it): the live re-read of the source (an edit or delete in the last moments).
 */
object Forwarder {
    class SourceUnavailable : Exception("The original could not be opened.")

    /** Forwards [m] from [sourceCid] to each of [targets]; returns the targets that failed. */
    suspend fun forward(
        context: Context, me: String, crypto: FariinCrypto, m: Message, sourceCid: String, targets: List<String>,
    ): List<String> = withContext(Dispatchers.IO) {
        val src = ChatMedia(context, sourceCid, crypto)
        // Open the source once, then send per target.
        val payload = runCatching { open(context, src, m) }.getOrNull()
        val failed = mutableListOf<String>()
        // A post forwarded out of a channel says which channel (iOS forwardMessage + sealedFwdChannel);
        // one already forwarded from a channel keeps its channel. Never written into a channel.
        val fromChannel = channelJson(sourceCid) ?: m.fwdChannel?.let { json(it.cid, it.title, it.photoUrl, it.username) }
        // "Forwarded from <group or channel>" (iOS ForwardSource.of): a forward keeps the source it came
        // with; a group or channel names itself; a person names nothing.
        val source = if (m.forwarded) m.forwardSource else runCatching { sourceOf(sourceCid, m.id, me) }.getOrNull()
        for (t in targets.distinct().take(MediaRules.FORWARD_CHATS_AT_ONCE)) {
            val fwd = MediaMessages.isForward(m.forwarded, sourceCid, t)
            val fc = fromChannel?.takeIf { !isChannel(t) }
            val ok = runCatching { sendTo(context, me, crypto, t, m, payload, fwd, fc, source.takeIf { fwd }) }.isSuccess
            if (!ok) failed += t
        }
        failed
    }

    private fun json(cid: String, title: String, photo: String, username: String) =
        org.json.JSONObject().put("c", cid).put("t", title).put("p", photo).put("u", username).toString()

    private fun isChannel(cid: String): Boolean =
        com.fariin.app.channel.ChannelFollows.docs.value[cid]?.get("channel") == true ||
            com.fariin.app.data.ChatsNow.list.any { it.id == cid && it.isChannel }

    /** The source's channel card when [sourceCid] is a channel: its doc as followed, else read once. */
    private suspend fun channelJson(sourceCid: String): String? {
        // A 1:1 chat, or a chat the list knows is not a channel, is never asked about.
        if (sourceCid.contains('_')) return null
        com.fariin.app.data.ChatsNow.find(sourceCid)?.let { if (!it.isChannel) return null }
        val d = com.fariin.app.channel.ChannelFollows.docs.value[sourceCid]
            ?: runCatching { com.google.firebase.firestore.FirebaseFirestore.getInstance().collection("conversations")
                .document(sourceCid).get().await().data as Map<String, Any?>? }.getOrNull() ?: return null
        val info = com.fariin.app.channel.ChannelInfo.from(sourceCid, d)
        if (!info.isChannel) return null
        return json(sourceCid, info.title, info.avatarUrl.orEmpty(), info.username)
    }

    /** The group or channel [sourceCid] as a forward source, or null for a 1:1. Its doc as held, else read once. */
    private suspend fun sourceOf(sourceCid: String, mid: String, me: String): Message.ForwardSource? {
        if (sourceCid.contains('_')) return null
        val listed = com.fariin.app.data.ChatsNow.find(sourceCid)
        if (listed != null && !listed.isGroup) return null
        val d = com.fariin.app.channel.ChannelFollows.docs.value[sourceCid]
            ?: runCatching { com.google.firebase.firestore.FirebaseFirestore.getInstance().collection("conversations")
                .document(sourceCid).get().await().data as Map<String, Any?>? }.getOrNull()
        if (listed == null && d?.get("type") != "group") return null
        val title = listed?.displayName(me) ?: (d?.get("title") as? String).orEmpty()
        val photo = listed?.displayPhoto(me) ?: (d?.get("avatarUrl") as? String)
        return Message.ForwardSource.make(sourceCid, mid, title, photo, d?.get("isPublic") == true,
            listed?.isChannel ?: (d?.get("channel") == true))
    }

    private sealed class Payload {
        data class Photo(val prep: PhotoPrep) : Payload()
        data class Video(val prep: VideoPrep) : Payload()
        data class Album(val parts: List<MediaSender.AlbumPart>) : Payload()
        data class Voice(val bytes: ByteArray) : Payload()
        data class Doc(val prep: FilePrep) : Payload()
        data class Sticker(val ref: com.fariin.app.data.StickerRef) : Payload()
        data class Gif(val gif: com.fariin.app.data.StickerGifRules.Gif) : Payload()
        object Text : Payload()
    }

    private suspend fun open(context: Context, src: ChatMedia, m: Message): Payload {
        val dir = File(context.cacheDir, "outgoing").also { it.mkdirs() }
        suspend fun bytes(url: String?, enc: com.fariin.app.crypto.EncMeta?): ByteArray =
            url?.let { src.bytes(it, enc) } ?: throw SourceUnavailable()
        suspend fun video(url: String?, enc: com.fariin.app.crypto.EncMeta?, posterUrl: String?,
                          posterEnc: com.fariin.app.crypto.EncMeta?): VideoPrep {
            val clip = File(dir, "fwd-${UUID.randomUUID()}.mp4").also { it.writeBytes(bytes(url, enc)) }
            val poster = posterUrl?.let { runCatching { bytes(it, posterEnc) }.getOrNull() }
            return VideoPrep.describe(clip, poster) ?: throw SourceUnavailable()
        }
        // A sticker or a GIF has no words and no media kind: it went down the text path, sent
        // nothing, and still said "Forwarded" (overnight test 2026-11-10). Each goes as itself.
        if (m.isSticker) return Payload.Sticker(com.fariin.app.data.StickerRef(m.stickerPack!!, m.stickerId!!, m.stickerEmoji.orEmpty(), m.stickerAnimated))
        if (m.isGif) return Payload.Gif(com.fariin.app.data.StickerGifRules.Gif(m.id, m.imageUrl!!, m.width ?: 200.0, m.height ?: 200.0))
        return when (m.mediaKind) {
            // iOS forwards a photo at .high quality.
            Message.MediaKind.PHOTO -> Payload.Photo(PhotoPrep.fromBytes(bytes(m.imageUrl, m.enc), MediaRules.PhotoQuality.HIGH)
                ?: throw SourceUnavailable())
            Message.MediaKind.VIDEO -> Payload.Video(video(m.videoUrl, m.enc, m.thumbUrl, m.thumbEnc))
            Message.MediaKind.ALBUM -> Payload.Album(m.album.take(MediaRules.ALBUM_MAX_ITEMS).map { it ->
                if (it.isVideo) MediaSender.AlbumPart.Video(video(it.videoUrl, it.videoEnc, it.imageUrl, it.enc))
                else MediaSender.AlbumPart.Photo(PhotoPrep.fromBytes(bytes(it.imageUrl, it.enc), MediaRules.PhotoQuality.HIGH)
                    ?: throw SourceUnavailable())
            })
            Message.MediaKind.VOICE -> Payload.Voice(bytes(m.audioUrl, m.enc))
            Message.MediaKind.FILE -> {
                val b = bytes(m.fileUrl, m.enc)
                val name = m.fileName ?: "File"
                Payload.Doc(FilePrep(name, b, FilePrep.pdfPreview(name, b, dir)))
            }
            Message.MediaKind.VIEW_ONCE -> throw SourceUnavailable()   // never forwarded (MessageRules.canForward)
            null -> Payload.Text
        }
    }

    private suspend fun sendTo(
        context: Context, me: String, crypto: FariinCrypto, target: String, m: Message, payload: Payload?, fwd: Boolean,
        fwdChannelJson: String?, source: Message.ForwardSource?,
    ) {
        val ts = System.currentTimeMillis().toDouble()
        val clientId = UUID.randomUUID().toString().uppercase()
        val sender = MediaSender(target, me, crypto, ChatMedia(context, target, crypto))
            .also { it.fwdChannelJson = fwdChannelJson; it.fwdSource = source }
        when (payload ?: throw SourceUnavailable()) {
            is Payload.Photo -> sender.image((payload as Payload.Photo).prep, m.text, false, fwd, clientId, ts)
            is Payload.Video -> sender.video((payload as Payload.Video).prep, m.text, fwd, clientId, ts)
            is Payload.Album -> sender.album((payload as Payload.Album).parts, m.text, fwd, clientId, ts)
            is Payload.Voice -> sender.audio((payload as Payload.Voice).bytes, m.duration ?: 0.0, m.waveform, fwd, clientId, ts)
            is Payload.Doc -> sender.file((payload as Payload.Doc).prep, fwd, clientId, ts)
            Payload.Text -> ThreadRepository(target, me, crypto, CoroutineScope(SupervisorJob() + Dispatchers.IO))
                .sendForwardedText(m.text, fwd, fwdChannelJson, source)
            is Payload.Sticker -> ThreadRepository(target, me, crypto, CoroutineScope(SupervisorJob() + Dispatchers.IO))
                .sendSticker((payload as Payload.Sticker).ref, null)
            is Payload.Gif -> ThreadRepository(target, me, crypto, CoroutineScope(SupervisorJob() + Dispatchers.IO))
                .sendGif((payload as Payload.Gif).gif, null)
        }
    }
}
