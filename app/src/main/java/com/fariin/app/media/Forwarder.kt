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
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/**
 * Forward (iOS ChatService.forwardMessage): the source's file is opened with the SOURCE chat's
 * keys, then sent again as a new message in each target, sealed for that target (1:1 enc1, group
 * seal, plain only in a plain group). "Forwarded" follows iOS isForward.
 *
 * Instant first (owner 2026-11-13, "forwarding must be instant"): a photo, video, voice note, file or
 * album is copied sealed inside the bucket by forwardMedia and only its small file key is wrapped
 * again for the target ([MediaSender.instant]); the server still never sees plain bytes. Anything that
 * cannot go that way (function not live, R2 links, plain/sealed mixed, a key that will not open)
 * falls back to the old path above, unchanged.
 *
 * The source is read again from the server first (iOS liveForwardText, 3 s at most): one its author
 * deleted is not forwarded ([Gone]), and an edited one goes with its new words.
 */
object Forwarder {
    class SourceUnavailable : Exception("The original could not be opened.")
    /** The author deleted the message (Delete for Everyone) before it went: nothing is sent. */
    class Gone : Exception("This message was deleted.")

    private sealed class Live {
        object Deleted : Live()
        object AsHeld : Live()
        /** Edited: its new words with their own bold and links. */
        data class Edited(val fresh: Message) : Live()
    }
    /** One read per message for 30 s, however many chats it goes to (iOS forwardChecks). */
    private val liveChecks = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Live>>()

    /**
     * The words to forward now, not when the sheet opened: [Live.Deleted] when the author deleted it,
     * their edited text when edited. No answer in 3 s (offline) = the copy in hand, as before.
     */
    private suspend fun liveText(m: Message, cid: String, crypto: FariinCrypto): Live {
        if (cid == com.fariin.app.data.OfficialChannel.CID || m.id.isEmpty()) return Live.AsHeld
        val key = "$cid/${m.id}"
        liveChecks[key]?.takeIf { System.currentTimeMillis() - it.first < 30_000 }?.let { return it.second }
        val read = kotlinx.coroutines.withTimeoutOrNull(3_000) {
            runCatching {
                com.fariin.app.Fb.db.collection("conversations").document(cid).collection("messages").document(m.id)
                    .get(com.google.firebase.firestore.Source.SERVER).await()
            }.getOrNull()
        } ?: return Live.AsHeld
        val live = when {
            !read.exists() || read.getBoolean("deleted") == true -> Live.Deleted
            read.getBoolean("edited") == true -> {
                val fresh = runCatching {
                    Message.fromFirestore(m.id, read.data ?: emptyMap(), cid) { raw, author -> crypto.decrypt(raw, cid, author) }
                }.getOrNull()
                // A seal that does not open (not warm, locked) keeps the words in hand.
                val t = fresh?.text
                if (fresh == null || t == com.fariin.app.crypto.Wire.NOT_READY || t == com.fariin.app.crypto.Wire.LOCKED) Live.AsHeld
                else Live.Edited(fresh)
            }
            else -> Live.AsHeld
        }
        if (liveChecks.size > 200) liveChecks.clear()
        liveChecks[key] = System.currentTimeMillis() to live
        return live
    }

    /** Forwards [m] from [sourceCid] to each of [targets]; returns the targets that failed. */
    suspend fun forward(
        context: Context, me: String, crypto: FariinCrypto, m: Message, sourceCid: String, targets: List<String>,
        /** Per target, the clientId its bubble already shows under (ForwardEchoes); else a new one. */
        clientIds: Map<String, String> = emptyMap(),
    ): List<String> = withContext(Dispatchers.IO) {
        val m = when (val live = liveText(m, sourceCid, crypto)) {
            Live.Deleted -> {
                for (t in targets) clientIds[t]?.let { com.fariin.app.data.ForwardEchoes.remove(t, it) }
                throw Gone()
            }
            Live.AsHeld -> m
            is Live.Edited -> if (live.fresh.text == m.text) m else m.copy(text = live.fresh.text, bold = live.fresh.bold, links = live.fresh.links)
        }
        val src = ChatMedia(context, sourceCid, crypto)
        // Opened once, and only if some target needs the old forward: photos, videos, voice, files and
        // albums first try the instant way (MediaSender.instant: a server copy and a re-wrapped key).
        var opened = false
        var payloadHeld: Payload? = null
        suspend fun payload(): Payload? {
            if (!opened) { opened = true; payloadHeld = runCatching { open(context, src, m) }.getOrNull() }
            return payloadHeld
        }
        val instantKind = !m.isSticker && !m.isGif && m.mediaKind != null && m.mediaKind != Message.MediaKind.VIEW_ONCE
        val failed = mutableListOf<String>()
        // A post forwarded out of a channel says which channel (iOS forwardMessage + sealedFwdChannel);
        // one already forwarded from a channel keeps its channel. Never written into a channel.
        val fromChannel = channelJson(sourceCid) ?: m.fwdChannel?.let { json(it.cid, it.title, it.photoUrl, it.username) }
        // "Forwarded from <group or channel>" (iOS ForwardSource.of): a forward keeps the source it came
        // with; a group or channel names itself; a person names nothing.
        val source = if (m.forwarded) m.forwardSource else runCatching { sourceOf(sourceCid, m.id, me) }.getOrNull()
        // The file's first home and the byte count held here (MediaOrigin), sealed per target by the sender.
        val origin = runCatching { MediaOrigin.payloadFor(m, src) }.getOrNull()
        val albumOrigins = runCatching { MediaOrigin.albumPayloads(m, src) }.getOrNull()
        for (t in targets.distinct().take(MediaRules.FORWARD_CHATS_AT_ONCE)) {
            val fwd = MediaMessages.isForward(m.forwarded, sourceCid, t)
            val fc = fromChannel?.takeIf { !isChannel(t) }
            val clientId = clientIds[t] ?: UUID.randomUUID().toString().uppercase()
            val instant = instantKind && runCatching {
                MediaSender(t, me, crypto, ChatMedia(context, t, crypto))
                    .also { it.fwdChannelJson = fc; it.fwdSource = source.takeIf { fwd }; it.quoteId = m.quoteId
                        it.originClear = origin; it.albumOrigins = albumOrigins }
                    .instant(m, sourceCid, m.text, fwd, clientId, System.currentTimeMillis().toDouble())
            }.onFailure { android.util.Log.i("FariinForward", "old forward to $t: ${it.message}") }.isSuccess
            val ok = instant || runCatching { sendTo(context, me, crypto, t, m, payload(), fwd, fc, source.takeIf { fwd }, clientId, origin, albumOrigins) }.isSuccess
            if (!ok) failed += t
            // Landed or not, the chat's own row takes over (or nothing, on a failure): the echo goes
            // once the real one is in (ThreadRepository.rebuild), or now when there is none.
            if (!ok) clientIds[t]?.let { com.fariin.app.data.ForwardEchoes.remove(t, it) }
        }
        // A channel post's shares for its Message Statistics: the forwarded copy is sealed, so the
        // server learns of it only from here (groups:notePostShare). Fire and forget.
        if (channelJson(sourceCid) != null) {
            val landed = targets.distinct().take(MediaRules.FORWARD_CHATS_AT_ONCE).filter { it !in failed }.take(20)
            if (landed.isNotEmpty()) com.fariin.app.data.PostStats.noteShare(sourceCid, m.id, landed)
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
            ?: runCatching { com.fariin.app.Fb.db.collection("conversations")
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
            ?: runCatching { com.fariin.app.Fb.db.collection("conversations")
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
            // The message's own file only: an alias copy (MediaOrigin) is never forward input.
            url?.let { src.bytes(it, enc, own = true) } ?: throw SourceUnavailable()
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
        fwdChannelJson: String?, source: Message.ForwardSource?, clientId: String,
        origin: String? = null, albumOrigins: List<String?>? = null,
    ) {
        val ts = System.currentTimeMillis().toDouble()
        val sender = MediaSender(target, me, crypto, ChatMedia(context, target, crypto))
            .also { it.fwdChannelJson = fwdChannelJson; it.fwdSource = source; it.quoteId = m.quoteId
                it.originClear = origin; it.albumOrigins = albumOrigins }
        // Registered with MediaSend like a send from the composer (owner 2026-11-11, iOS fwd-cancel):
        // the X on a forwarded video still uploading only hid the bubble, and the video arrived anyway,
        // because nothing held this send for Cancel to stop. A Cancel ends the job quietly (join
        // returns, no failure counted); a real error still throws out of coroutineScope.
        suspend fun registered(send: suspend () -> Unit): Unit = kotlinx.coroutines.coroutineScope {
            val job = this.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) { send() }
            MediaSend.register(clientId, job)
            try { job.start(); job.join() } finally { MediaSend.finish(clientId) }
        }
        when (payload ?: throw SourceUnavailable()) {
            is Payload.Photo -> registered { sender.image((payload as Payload.Photo).prep, m.text, false, fwd, clientId, ts) }
            is Payload.Video -> registered { sender.video((payload as Payload.Video).prep, m.text, fwd, clientId, ts) }
            is Payload.Album -> registered { sender.album((payload as Payload.Album).parts, m.text, fwd, clientId, ts) }
            is Payload.Voice -> registered { sender.audio((payload as Payload.Voice).bytes, m.duration ?: 0.0, m.waveform, fwd, clientId, ts) }
            is Payload.Doc -> registered { sender.file((payload as Payload.Doc).prep, fwd, clientId, ts) }
            Payload.Text -> ThreadRepository(target, me, crypto, CoroutineScope(SupervisorJob() + Dispatchers.IO))
                .sendForwardedText(m.text, fwd, fwdChannelJson, source,
                    fmt = com.fariin.app.data.TextFormat.serialize(m.bold, m.text.length, m.links), clientId = clientId, quote = m.quoteId)
            is Payload.Sticker -> ThreadRepository(target, me, crypto, CoroutineScope(SupervisorJob() + Dispatchers.IO))
                .sendSticker((payload as Payload.Sticker).ref, null)
            is Payload.Gif -> ThreadRepository(target, me, crypto, CoroutineScope(SupervisorJob() + Dispatchers.IO))
                .sendGif((payload as Payload.Gif).gif, null)
        }
    }
}
