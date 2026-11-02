package com.fariin.app.media

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
        for (t in targets.distinct().take(MediaRules.FORWARD_CHATS_AT_ONCE)) {
            val fwd = MediaMessages.isForward(m.forwarded, sourceCid, t)
            val ok = runCatching { sendTo(context, me, crypto, t, m, payload, fwd) }.isSuccess
            if (!ok) failed += t
        }
        failed
    }

    private sealed class Payload {
        data class Photo(val prep: PhotoPrep) : Payload()
        data class Video(val prep: VideoPrep) : Payload()
        data class Album(val parts: List<MediaSender.AlbumPart>) : Payload()
        data class Voice(val bytes: ByteArray) : Payload()
        data class Doc(val prep: FilePrep) : Payload()
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
    ) {
        val ts = System.currentTimeMillis().toDouble()
        val clientId = UUID.randomUUID().toString().uppercase()
        val sender = MediaSender(target, me, crypto, ChatMedia(context, target, crypto))
        when (payload ?: throw SourceUnavailable()) {
            is Payload.Photo -> sender.image((payload as Payload.Photo).prep, m.text, false, fwd, clientId, ts)
            is Payload.Video -> sender.video((payload as Payload.Video).prep, m.text, fwd, clientId, ts)
            is Payload.Album -> sender.album((payload as Payload.Album).parts, m.text, fwd, clientId, ts)
            is Payload.Voice -> sender.audio((payload as Payload.Voice).bytes, m.duration ?: 0.0, m.waveform, fwd, clientId, ts)
            is Payload.Doc -> sender.file((payload as Payload.Doc).prep, fwd, clientId, ts)
            Payload.Text -> ThreadRepository(target, me, crypto, CoroutineScope(SupervisorJob() + Dispatchers.IO))
                .sendForwardedText(m.text, fwd)
        }
    }
}
