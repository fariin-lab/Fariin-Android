package com.fariin.app.media

import com.fariin.app.data.Message
import com.fariin.app.crypto.EncMeta
import com.fariin.app.crypto.FariinCrypto
import com.fariin.app.crypto.Wire
import com.fariin.app.data.CryptoHub
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.tasks.await
import java.util.concurrent.ConcurrentHashMap

/**
 * The media sends of one chat, written as Kulan-iOS ChatService writes them: sendImage (caption,
 * view-once, forwarded), announceVideo + attachVideo, sendFile, sendMixedAlbum and sendAudio.
 * Every file is sealed for this chat before it leaves (enc1 bytes for a 1:1, the group seal for
 * a group, plain only in a plain group), uploaded as application/octet-stream, and the message
 * is written by the same field builders ([MediaMessages]) the tests check.
 *
 * Works for a chat that is not open (forwarding), so it reads the members itself.
 */
class MediaSender(
    val cid: String,
    private val me: String,
    private val crypto: FariinCrypto,
    private val media: ChatMedia?,
    private val db: FirebaseFirestore = com.fariin.app.Fb.db,
) {
    private val convRef = db.collection("conversations").document(cid)
    private val isGroup = Wire.isGroupCid(cid)
    private val other = if (isGroup) "" else Wire.otherUid(cid, me)

    sealed class AlbumPart {
        data class Photo(val prep: PhotoPrep) : AlbumPart()
        data class Video(val prep: VideoPrep) : AlbumPart()
    }

    /** iOS freshGroupMembers (server first, then the cached doc); null for a 1:1. */
    private suspend fun members(): List<String>? {
        if (!isGroup) {
            // iOS: the 1:1 doc always carries both of us and a time before a message lands.
            convRef.set(mapOf("users" to listOf(me, other), "updatedAt" to FieldValue.serverTimestamp()), SetOptions.merge()).await()
            return null
        }
        val snap = try { convRef.get(Source.SERVER).await() } catch (_: Exception) { convRef.get(Source.CACHE).await() }
        if (snap.getBoolean("e2ee") == false) CryptoHub.notePlainGroup(cid)
        return (snap.get("users") as? List<*>)?.filterIsInstance<String>()?.takeIf { it.isNotEmpty() }
            ?: error("no members")
    }

    private suspend fun sealBytes(data: ByteArray, members: List<String>?): Pair<ByteArray, EncMeta> =
        if (members != null) crypto.sealBytesForGroup(data, members, cid) else crypto.encryptBytes(cid, data)

    private suspend fun seal(text: String, members: List<String>?): String =
        if (members != null) crypto.sealForGroup(text, members, cid) else crypto.encryptForConversation(cid, text)

    /** A seal that may fail quietly (caption, blurhash, thumb: iOS `try?`). */
    private suspend fun sealOrNull(text: String?, members: List<String>?): String? =
        text?.takeIf { it.isNotEmpty() }?.let { runCatching { seal(it, members) }.getOrNull()?.ifEmpty { null } }

    private fun others(members: List<String>?) = members ?: listOf(other)

    /** A forward from a channel: its {c,t,p,u} JSON, sealed onto each message written (iOS sealedFwdChannel). */
    var fwdChannelJson: String? = null

    private suspend fun setMsg(batch: com.google.firebase.firestore.WriteBatch, ref: com.google.firebase.firestore.DocumentReference,
                               members: List<String>?, msg: Map<String, Any>, reply: Message.ReplyRef? = null) {
        val fc = sealOrNull(fwdChannelJson, members)
        var out = if (fc != null) msg + ("fwdChannel" to fc) else msg
        // Media sent while replying: replyTo sealed as a text reply seals it, on the message itself (an album too).
        if (reply != null) out = out + ("replyTo" to com.fariin.app.data.ReplyQuoteRules.wire(reply, seal(reply.text, members)))
        quoteId?.takeIf { com.fariin.app.data.QuoteRules.isQid(it) }?.let { out = out + ("quote" to com.fariin.app.data.QuoteStore.wire(it)) }
        // The group or channel it came from (iOS sealedForwardSource), only beside `forwarded`. A
        // plain group's seal hands the JSON back, so there the map itself goes. A failed seal drops only this.
        val src = fwdSource?.takeIf { msg["forwarded"] == true }
        if (src != null) {
            val j = src.json()
            sealOrNull(j, members)?.let { out = out + ("fwd" to (if (it == j) src.map() else it)) }
        }
        // The file's first home (MediaOrigin), only beside `forwarded`: sealed like the text in a 1:1
        // or an end-to-end group (the server could match a clear key to its chat); a plain group's seal
        // hands it back clear. A seal that fails drops the field, never the message.
        if (msg["forwarded"] == true) {
            originClear?.let { o -> sealOrNull(o, members)?.let { out = out + ("origin" to it) } }
            val items = msg["album"] as? List<*>
            val os = albumOrigins
            if (items != null && os != null && os.size == items.size) {
                out = out + ("album" to items.mapIndexed { i, it ->
                    val item = it as? Map<*, *> ?: return@mapIndexed it
                    // A group seal grows one wrap per member: past 4096 characters the item goes without.
                    val sealed = os[i]?.let { o -> sealOrNull(o, members) }?.takeIf { s -> s.length <= 4096 }
                    if (sealed != null) item + ("origin" to sealed) else item
                })
            }
        }
        batch.set(ref, out)
    }

    /** A forward's clear `origin` payload (MediaOrigin.payloadFor), and one per album item. */
    var originClear: String? = null
    var albumOrigins: List<String?>? = null

    /** The group or channel a forward comes from (Message.ForwardSource), written as `fwd`. */
    var fwdSource: com.fariin.app.data.Message.ForwardSource? = null
    /** A forwarded post's quoted card (iOS 80b0470bf): the same {qid} as the original, so it is not a new quote. */
    var quoteId: String? = null

    /** End-to-end encrypted bytes go up as application/octet-stream (storage.rules `chat/`). */
    private suspend fun upload(cipher: ByteArray, path: String, onProgress: ((Float) -> Unit)? = null): String {
        require(cipher.size < MediaRules.STORAGE_CHAT_LIMIT) { "file too large" }
        // Cloudflare R2 while config/features.r2Media is on (iOS 9bbac4e65): signed by mediaSign;
        // Storage stays the way if R2 refuses, so a send never fails on it.
        if (com.fariin.app.data.AppFeatures.r2Media && com.fariin.app.media.R2Media.allowed(path) && cipher.size < MediaRules.STORAGE_CHAT_LIMIT) {
            com.fariin.app.media.R2Media.put(path, cipher, onProgress = onProgress)?.let { link -> onProgress?.invoke(1f); return link }
        }
        val ref = FirebaseStorage.getInstance().reference.child(path)
        val task = ref.putBytes(cipher, StorageMetadata.Builder().setContentType("application/octet-stream").build())
        if (onProgress != null) task.addOnProgressListener { s ->
            if (s.totalByteCount > 0) onProgress(s.bytesTransferred.toFloat() / s.totalByteCount)
        }
        // Cancel (MediaSend.cancel) stops the transfer itself, not only the wait for it.
        try { task.await() } catch (e: kotlinx.coroutines.CancellationException) { task.cancel(); throw e }
        return ref.downloadUrl.await().toString()
    }

    /** iOS retryUpload: a few more tries with a growing pause before the send counts as failed. */
    private suspend fun uploadRetrying(cipher: ByteArray, path: String, onProgress: ((Float) -> Unit)? = null): String {
        var attempt = 0
        while (true) {
            try { return upload(cipher, path, onProgress) } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // A refusal (403, no permission) is not going to change on a retry: stop at once. But
                // first, as iOS: our own earlier try may have landed and lost its reply, and the
                // create-only rule then refuses the retry. Same bytes there = that was our upload.
                if (refused(e)) { alreadyUploaded(path, cipher)?.let { onProgress?.invoke(1f); return it }; throw e }
                if (++attempt >= 4) throw e
                delay(minOf(30_000L, 1_000L shl attempt))
            }
        }
    }

    /**
     * Storage or Firestore said no for good (iOS isRetryableUpload false): not authorized or signed
     * in, over quota, no such object, cancelled, or a 401 / 403.
     */
    private fun refused(e: Exception): Boolean = when (e) {
        is com.google.firebase.storage.StorageException -> e.errorCode in setOf(
            com.google.firebase.storage.StorageException.ERROR_NOT_AUTHORIZED,
            com.google.firebase.storage.StorageException.ERROR_NOT_AUTHENTICATED,
            com.google.firebase.storage.StorageException.ERROR_QUOTA_EXCEEDED,
            com.google.firebase.storage.StorageException.ERROR_OBJECT_NOT_FOUND,
            com.google.firebase.storage.StorageException.ERROR_CANCELED,
        ) || e.httpResultCode == 401 || e.httpResultCode == 403
        is com.google.firebase.firestore.FirebaseFirestoreException ->
            e.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED
        else -> false
    }

    /** iOS alreadyUploaded: the object at [path] is byte for byte [bytes] (same MD5): its link, else null. */
    private suspend fun alreadyUploaded(path: String, bytes: ByteArray): String? = runCatching {
        val ref = FirebaseStorage.getInstance().reference.child(path)
        val meta = ref.metadata.await()
        if (!MediaRules.sameUpload(meta.md5Hash, bytes)) null else ref.downloadUrl.await().toString()
    }.getOrNull()

    private fun progress(clientId: String?): ((Float) -> Unit)? = clientId?.let { id -> { f -> UploadProgress.set(id, f) } }

    // ------------------------------------------------------------------ poll picture

    /**
     * One poll option's picture (iOS uploadPollImage, poll-media-1114): [jpeg] is already the 512 px
     * JPEG, sealed exactly like a photo in this chat (1:1 sealed, sealed group for its members, plain
     * group or channel in the clear) and stored where a photo is, `chat/{cid}/{id}.enc`, under a fresh
     * message-style id. No message doc: the poll's own text carries the url and the key.
     */
    suspend fun pollImage(jpeg: ByteArray): com.fariin.app.data.PollImage {
        require(jpeg.isNotEmpty()) { "That picture could not be read." }
        val members = members()
        val (cipher, meta) = sealBytes(jpeg, members)
        val fileId = convRef.collection("messages").document().id
        val url = uploadRetrying(cipher, MediaRules.imagePath(cid, fileId))
        return com.fariin.app.data.PollImage(url, meta.takeIf { !it.isPlain })
    }

    // ------------------------------------------------------------------ photo

    suspend fun image(
        prep: PhotoPrep, caption: String, viewOnce: Boolean, forwarded: Boolean, clientId: String?, clientTs: Double,
        reply: Message.ReplyRef? = null,
    ) {
        val members = members()
        val (cipher, meta) = sealBytes(prep.jpeg, members)
        val msgRef = convRef.collection("messages").document()
        // The message is written only once the bytes are up (iOS, owner 2026-10-10).
        val url = uploadRetrying(cipher, MediaRules.imagePath(cid, msgRef.id), progress(clientId))
        // A view-once photo is not kept in the sender's open cache either.
        if (!viewOnce) media?.store(url, prep.jpeg)
        val captionCipher = sealOrNull(caption.trim(), members) ?: ""
        val blur = if (viewOnce) null else sealOrNull(prep.blurhash, members)
        val thumb = if (viewOnce) null else sealOrNull(prep.thumbB64, members)
        val batch = db.batch()
        setMsg(batch, msgRef, members, MediaMessages.image(me, url, meta, captionCipher, clientTs, clientId, prep.width, prep.height,
            blur, thumb, FieldValue.serverTimestamp(), viewOnce = viewOnce, forwarded = forwarded), reply)
        // A view-once photo publishes NO thumbnail to the conversation doc (iOS audit).
        batch.update(convRef, MediaMessages.convUpdate(me,
            if (viewOnce) MediaRules.VIEW_ONCE_PREVIEW else MediaRules.PHOTO_PREVIEW, others(members),
            FieldValue.serverTimestamp(), FieldValue.increment(1),
            lastImageUrl = if (viewOnce) null else url, lastImageEnc = if (viewOnce) null else meta, forwarded = forwarded))
        batch.commit().await()
        com.fariin.app.media.ChatSounds.sent()
        clientId?.let { UploadProgress.done(it) }
    }

    // ------------------------------------------------------------------ video

    /**
     * announceVideo then attachVideo: the poster goes up and the message appears with `uploading`
     * (the other side draws the poster with a ring), then the clip and its envelope land on it.
     * [onAnnounced] gets the message id once the first write is in.
     */
    suspend fun video(
        v: VideoPrep, caption: String, forwarded: Boolean, clientId: String?, clientTs: Double,
        onAnnounced: (String) -> Unit = {}, reply: Message.ReplyRef? = null,
    ) {
        val members = members()
        val msgRef = convRef.collection("messages").document()
        val (thCipher, thMeta) = sealBytes(v.poster.jpeg, members)
        val thumbUrl = uploadRetrying(thCipher, MediaRules.videoThumbPath(cid, msgRef.id))
        media?.store(thumbUrl, v.poster.jpeg)
        val captionCipher = sealOrNull(caption.trim(), members) ?: ""
        val batch = db.batch()
        setMsg(batch, msgRef, members, MediaMessages.videoAnnounce(me, thumbUrl, thMeta, v.duration, v.width.toDouble(), v.height.toDouble(),
            captionCipher, clientTs, clientId, sealOrNull(v.poster.blurhash, members), sealOrNull(v.poster.thumbB64, members),
            forwarded, FieldValue.serverTimestamp()), reply)
        batch.update(convRef, MediaMessages.convUpdate(me, MediaRules.videoPreview(v.duration), others(members),
            FieldValue.serverTimestamp(), FieldValue.increment(1), lastImageUrl = thumbUrl, lastImageEnc = thMeta, forwarded = forwarded))
        MediaSend.noteAnnounced(clientId, msgRef.id)   // before the write: a Cancel during it still takes it down
        batch.commit().await()
        onAnnounced(msgRef.id)
        val clip = v.file.readBytes()
        val (cipher, meta) = sealBytes(clip, members)
        // The ring's last 70% (the first 30% was the compression, VideoPrep).
        val url = uploadRetrying(cipher, MediaRules.videoPath(cid, msgRef.id),
            clientId?.let { id -> { f: Float -> UploadProgress.raise(id, 0.3f + 0.7f * f) } })
        media?.store(url, clip)
        msgRef.update(MediaMessages.attachVideo(url, meta, FieldValue.delete())).await()
        com.fariin.app.media.ChatSounds.sent()
        clientId?.let { UploadProgress.done(it) }
    }

    // ------------------------------------------------------------------ file

    /** sendFile: the message (name, size, sealed preview) first with `uploading`, then `fileUrl`. */
    /** A song send kept across a Resend: its written message and the sealed file, so it is not written twice. */
    class FileUpload {
        @Volatile var messageId: String? = null
        var cipher: ByteArray = ByteArray(0)
    }

    suspend fun file(f: FilePrep, forwarded: Boolean, clientId: String?, clientTs: Double, reply: Message.ReplyRef? = null,
                     rename: Pair<String?, String?>? = null, job: FileUpload? = null) {
        // A Resend of a song whose message is already written: only the file goes up, then the attach.
        job?.messageId?.let { id ->
            val ref = convRef.collection("messages").document(id)
            val url = uploadRetrying(job.cipher, MediaRules.filePath(cid, id), progress(clientId))
            media?.store(url, f.bytes)
            ref.update(MediaMessages.attachFile(url, FieldValue.delete())).await()
            com.fariin.app.media.ChatSounds.sent()
            clientId?.let { UploadProgress.done(it) }
            return
        }
        val members = members()
        // A song renamed in the send sheet (iOS sendFile `renamed`): one audio item in `album`, sealed as text is.
        val renamed = rename?.let { (t, a) ->
            com.fariin.app.data.MusicRules.songRenameAlbum(t?.trim()?.takeIf { it.isNotEmpty() }?.let { seal(it, members) },
                a?.trim()?.takeIf { it.isNotEmpty() }?.let { seal(it, members) })
        }
        val (cipher, meta) = sealBytes(f.bytes, members)
        val msgRef = convRef.collection("messages").document()
        var thumbUrl: String? = null
        var thumbMeta: EncMeta? = null
        f.previewJpeg?.let { jpg ->
            runCatching {
                val (tc, tm) = sealBytes(jpg, members)
                thumbUrl = upload(tc, MediaRules.fileThumbPath(cid, msgRef.id))
                thumbMeta = tm
                media?.store(thumbUrl!!, jpg)
            }
        }
        val batch = db.batch()
        val msg = MediaMessages.file(me, f.name, f.bytes.size, meta, clientTs, clientId, thumbUrl, thumbMeta,
            forwarded, FieldValue.serverTimestamp())
        setMsg(batch, msgRef, members, if (renamed != null) msg + ("album" to renamed) else msg, reply)
        batch.update(convRef, MediaMessages.convUpdate(me, MediaRules.filePreview(f.name), others(members),
            FieldValue.serverTimestamp(), FieldValue.increment(1), forwarded = forwarded))
        MediaSend.noteAnnounced(clientId, msgRef.id)
        batch.commit().await()
        job?.let { it.cipher = cipher; it.messageId = msgRef.id }
        val url = uploadRetrying(cipher, MediaRules.filePath(cid, msgRef.id), progress(clientId))
        media?.store(url, f.bytes)
        msgRef.update(MediaMessages.attachFile(url, FieldValue.delete())).await()
        com.fariin.app.media.ChatSounds.sent()
        clientId?.let { UploadProgress.done(it) }
    }

    // ------------------------------------------------------------------ playlist

    /**
     * SEVERAL SONGS PICKED TOGETHER ARE ONE MESSAGE (iOS sendPlaylist, 2026-11-21): a file message
     * whose own file is the first song, every song in `album` as a `kind: "audio"` item, the name
     * sealed as `listName` on the first item ("" = none), the caption sealed in `text`, the cover as
     * the sealed preview tile. The message is written first, the songs go up one after another, and
     * one update attaches them all (MusicRules builds the maps key for key). Returns the message id.
     */
    /**
     * A playlist send kept across a Resend: its message, each song sealed (the envelopes the message
     * already names), and the urls of the songs already up. A failed upload leaves it as it was, so a
     * Resend puts up only the songs still missing (MusicRules.songsToUpload) and then attaches them.
     */
    class PlaylistUpload {
        @Volatile var messageId: String? = null
        var ciphers: List<ByteArray> = emptyList()
        var list: List<com.fariin.app.data.MusicRules.SealedSong> = emptyList()
        var listNameSealed: String = ""
        var urls: Array<String?> = emptyArray()
    }

    suspend fun playlist(songs: List<FilePrep>, title: String, caption: String, cover: ByteArray?,
                         clientId: String?, clientTs: Double, reply: Message.ReplyRef? = null,
                         renames: List<Pair<String?, String?>> = emptyList(), job: PlaylistUpload = PlaylistUpload()): String {
        require(songs.size >= 2) { "A playlist needs two songs or more." }
        if (job.messageId == null) {
            val members = members()
            val sealed = songs.map { sealBytes(it.bytes, members) }
            // Each song's typed title and artist, sealed as text is (iOS sendPlaylist `renamed`).
            suspend fun sealedWord(s: String?) = s?.trim()?.takeIf { it.isNotEmpty() }?.let { seal(it, members) }
            val list = songs.mapIndexed { i, s ->
                val (t, a) = renames.getOrNull(i) ?: (null to null)
                com.fariin.app.data.MusicRules.SealedSong(sealed[i].second.asMap(), s.name, null, s.bytes.size,
                    sealedTitle = sealedWord(t), sealedArtist = sealedWord(a))
            }
            val name = title.trim()
            val listNameSealed = if (name.isEmpty()) "" else seal(name, members)
            val words = caption.trim()
            val captionSealed = if (words.isEmpty()) "" else seal(words, members)
            val msgRef = convRef.collection("messages").document()
            // The cover rides as the sealed preview tile, as a single song's does. Best effort.
            var thumbUrl: String? = null
            var thumbMeta: EncMeta? = null
            cover?.let { jpg ->
                runCatching {
                    val (tc, tm) = sealBytes(jpg, members)
                    thumbUrl = upload(tc, MediaRules.fileThumbPath(cid, msgRef.id))
                    thumbMeta = tm
                    media?.store(thumbUrl!!, jpg)
                }
            }
            val batch = db.batch()
            setMsg(batch, msgRef, members, com.fariin.app.data.MusicRules.playlistMessage(me, list, captionSealed, listNameSealed,
                clientTs, clientId, thumbUrl, thumbMeta?.asMap(), FieldValue.serverTimestamp()), reply)
            batch.update(convRef, MediaMessages.convUpdate(me, com.fariin.app.data.MusicRules.playlistPreview(list.size), others(members),
                FieldValue.serverTimestamp(), FieldValue.increment(1)))
            MediaSend.noteAnnounced(clientId, msgRef.id)
            batch.commit().await()
            job.ciphers = sealed.map { it.first }
            job.list = list
            job.listNameSealed = listNameSealed
            job.urls = arrayOfNulls(songs.size)
            job.messageId = msgRef.id
        }
        val messageId = job.messageId!!
        val msgRef = convRef.collection("messages").document(messageId)
        // One after another, so ten songs never hold ten uploads open at once. A Resend skips the
        // songs already up: their urls are kept with the envelopes the message names.
        val n = job.urls.size
        for (i in com.fariin.app.data.MusicRules.songsToUpload(job.urls.toList())) {
            val each: ((Float) -> Unit)? = clientId?.let { id -> { f -> UploadProgress.set(id, (i + f) / n) } }
            val url = uploadRetrying(job.ciphers[i], com.fariin.app.data.MusicRules.songPath(cid, messageId, i), each)
            songs.getOrNull(i)?.let { media?.store(url, it.bytes) }
            job.urls[i] = url
        }
        msgRef.update(com.fariin.app.data.MusicRules.playlistAttach(job.list, job.urls.map { it!! }, job.listNameSealed,
            FieldValue.delete())).await()
        com.fariin.app.media.ChatSounds.sent()
        clientId?.let { UploadProgress.done(it) }
        return messageId
    }

    // ------------------------------------------------------------------ album

    /** sendMixedAlbum (sendAlbum when all photos): every tile sealed and up, then ONE message. */
    suspend fun album(parts: List<AlbumPart>, caption: String, forwarded: Boolean, clientId: String?, clientTs: Double,
                      reply: Message.ReplyRef? = null) {
        require(parts.size in 1..MediaRules.ALBUM_MAX_ITEMS)
        val members = members()
        val msgRef = convRef.collection("messages").document()
        val id = msgRef.id
        val fractions = ConcurrentHashMap<Int, Float>()
        fun keyOf(i: Int) = clientId?.let { MediaSend.itemKey(it, i) }
        // Each tile fills its own ring ("clientId#index"); the whole is kept for anything reading the message's.
        fun tileProgress(i: Int): ((Float) -> Unit)? = clientId?.let { cId ->
            { f: Float -> fractions[i] = f; UploadProgress.set(MediaSend.itemKey(cId, i), f); UploadProgress.set(cId, fractions.values.sum() / parts.size) }
        }
        fun dropped(i: Int) = keyOf(i)?.let { MediaSend.isItemCancelled(it) } == true
        val gate = Semaphore(10)   // iOS maxAlbumUploadsInFlight
        val kept = coroutineScope {
            val jobs = parts.mapIndexed { i, p ->
                if (dropped(i)) return@mapIndexed null
                async {
                    val tile = gate.withPermit {
                        when (p) {
                            is AlbumPart.Photo -> {
                                val (c, m) = sealBytes(p.prep.jpeg, members)
                                val url = uploadRetrying(c, MediaRules.albumTilePath(cid, id, "$i"), tileProgress(i))
                                media?.store(url, p.prep.jpeg)
                                MediaMessages.Tile(url, m, p.prep.width.toDouble(), p.prep.height.toDouble())
                            }
                            is AlbumPart.Video -> {
                                val (tc, tm) = sealBytes(p.prep.poster.jpeg, members)
                                val posterUrl = uploadRetrying(tc, MediaRules.albumTilePath(cid, id, "$i-thumb"))
                                media?.store(posterUrl, p.prep.poster.jpeg)
                                val clip = p.prep.file.readBytes()
                                val (vc, vm) = sealBytes(clip, members)
                                val vurl = uploadRetrying(vc, MediaRules.albumTilePath(cid, id, "$i-video"), tileProgress(i))
                                media?.store(vurl, clip)
                                MediaMessages.Tile(posterUrl, tm, p.prep.width.toDouble(), p.prep.height.toDouble(), vurl, vm, p.prep.duration)
                            }
                        }
                    }
                    keyOf(i)?.let { UploadProgress.done(it); MediaSend.itemEnded(it) }
                    tile
                }.also { d -> keyOf(i)?.let { MediaSend.registerItem(it, d) } }
            }
            jobs.mapIndexed { i, d ->
                val tile = try { d?.await() } catch (e: kotlinx.coroutines.CancellationException) {
                    // That tile's X (its own job was cancelled), not the whole send: it is left out.
                    if (dropped(i) && kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.isActive == true) null else throw e
                }
                // X'd after its bytes were already up: still left out, as the tile has left the bubble.
                if (tile == null || dropped(i)) null else i to tile
            }.filterNotNull()
        }
        // Every item X'd: nothing is sent at all (iOS).
        if (kept.isEmpty()) throw kotlinx.coroutines.CancellationException("every album item was cancelled")
        val tiles = kept.map { it.second }
        val first = when (val p = parts[kept.first().first]) { is AlbumPart.Photo -> p.prep; is AlbumPart.Video -> p.prep.poster }
        val captionCipher = sealOrNull(caption.trim(), members) ?: ""
        val batch = db.batch()
        setMsg(batch, msgRef, members, MediaMessages.album(me, tiles, captionCipher, clientTs, clientId,
            sealOrNull(first.blurhash, members), sealOrNull(first.thumbB64, members), forwarded, FieldValue.serverTimestamp()), reply)
        val cover = tiles.firstOrNull { !it.isVideo } ?: tiles.first()
        val ordered = listOf(cover) + tiles.filter { it !== cover }
        batch.update(convRef, MediaMessages.convUpdate(me, MediaMessages.albumPreview(tiles), others(members),
            FieldValue.serverTimestamp(), FieldValue.increment(1), lastImageUrl = cover.imageUrl, lastImageEnc = cover.imageMeta,
            lastImages = MediaMessages.lastImages(ordered), forwarded = forwarded))
        batch.commit().await()
        com.fariin.app.media.ChatSounds.sent()
        clientId?.let { UploadProgress.done(it) }
    }

    // ------------------------------------------------------------------ instant forward

    /** The instant forward cannot be done; the caller sends the old way (nothing was written). */
    class NotInstant(why: String) : Exception(why)

    /**
     * Instant forward (iOS forward-instant, spec fariin-plans/to-android/2026-11-13-instant-forward.md):
     * every file key of [m] re-wrapped for this chat first (a key problem costs nothing), then the
     * server copies the sealed files (forwardMedia) and ONE message is written the way a normal send
     * writes that type, complete. Any refusal throws [NotInstant] before anything is left behind.
     */
    suspend fun instant(m: Message, srcCid: String, caption: String, forwarded: Boolean, clientId: String, clientTs: Double) {
        if (m.viewOnce || m.uploading || m.sendState != null) throw NotInstant("not a settled media message")
        val members = members()
        val dst = convRef.collection("messages").document()
        // 1. Every envelope re-wrapped for here (n kept). One that will not: the old forward.
        // A sealed file into a plain group or channel (spec 2026-11-14-forward-sealed-into-plain): its
        // file key goes to the server (`keys`), which writes the copy in the clear; the new envelope is
        // the plain marker, as a plain group's own upload.
        val keyByUrl = HashMap<String, Map<String, String>>()
        // A CLEAR file (a channel's, a plain group's) into a sealed chat (iOS forward-seal 2026-11-15):
        // a fresh key wrapped for here, and the server seals the copy with it (`seal`); no download,
        // no re-upload. Plain into plain stays plain (rewrapFile).
        val sealByUrl = HashMap<String, Map<String, String>>()
        val dstSealed = !crypto.isPlainGroup(cid)
        suspend fun re(url: String, given: EncMeta?): EncMeta {
            // Group and channel media written in the clear may carry no envelope: that is the plain one.
            val e = given ?: if (Wire.isGroupCid(srcCid)) EncMeta.PLAIN else throw NotInstant("key")
            if (e.isPlain && Wire.isGroupCid(srcCid) && dstSealed) {
                val (meta, nk) = crypto.sealForwardKey(cid, members) ?: throw NotInstant("seal key")
                sealByUrl[url] = nk
                return meta
            }
            crypto.plainForwardKey(srcCid, e, cid)?.let { k ->
                keyByUrl[url] = mapOf("n" to e.n, "k" to com.fariin.app.crypto.B64.encode(k))
                return EncMeta.PLAIN
            }
            return crypto.rewrapFile(srcCid, e, cid, members) ?: throw NotInstant("key")
        }
        // 2. The files to copy, by their Storage path ("r2:" and anything not Storage: the old forward).
        val urls = buildList {
            when (m.mediaKind) {
                Message.MediaKind.PHOTO -> add(m.imageUrl)
                Message.MediaKind.VIDEO -> { add(m.videoUrl); add(m.thumbUrl) }
                Message.MediaKind.VOICE -> add(m.audioUrl)
                Message.MediaKind.FILE -> { add(m.fileUrl); m.thumbUrl?.let { add(it) } }
                Message.MediaKind.ALBUM -> m.album.forEach { add(it.imageUrl); if (it.isVideo) add(it.videoUrl) }
                else -> throw NotInstant("kind")
            }
        }.map { it ?: throw NotInstant("missing url") }.distinct()
        if (urls.isEmpty() || urls.size > 40) throw NotInstant("count")
        val pathOf = urls.associateWith { storagePath(it) ?: throw NotInstant("not storage") }
        val metas: Map<String, EncMeta> = when (m.mediaKind) {
            Message.MediaKind.ALBUM -> buildMap {
                m.album.forEach { t -> put(t.imageUrl, re(t.imageUrl, t.enc)); if (t.isVideo) put(t.videoUrl!!, re(t.videoUrl, t.videoEnc)) }
            }
            Message.MediaKind.VIDEO -> mapOf(m.videoUrl!! to re(m.videoUrl, m.enc), m.thumbUrl!! to re(m.thumbUrl, m.thumbEnc))
            Message.MediaKind.FILE -> buildMap { put(m.fileUrl!!, re(m.fileUrl, m.enc)); m.thumbUrl?.let { put(it, re(it, m.thumbEnc)) } }
            else -> mapOf(urls.first() to re(urls.first(), m.enc))
        }
        val keys: Map<String, Map<String, String>> = keyByUrl.mapKeys { pathOf.getValue(it.key) }
        val seals: Map<String, Map<String, String>> = sealByUrl.mapKeys { pathOf.getValue(it.key) }
        // The server refuses `keys` and `seal` in one call (mixed plain and sealed parts): the old forward.
        if (keys.isNotEmpty() && seals.isNotEmpty()) throw NotInstant("keys and seal")
        // 3. The server copy (it reads, opens and writes the bytes when it has keys: a longer wait).
        val res = try {
            val args = hashMapOf<String, Any>("srcCid" to srcCid, "msgId" to m.id, "dstCid" to cid, "dstMsgId" to dst.id,
                "objects" to pathOf.values.toList())
            if (keys.isNotEmpty()) args["keys"] = keys
            if (seals.isNotEmpty()) args["seal"] = seals
            // iOS final check 2026-11-15: a seal or an open moves the whole file through the function,
            // so that one runs beside the bucket (us-east1, forwardMediaUs) and gets 310 s, never shorter
            // than the server's own 300 (giving up first would undo a copy still being written). A plain
            // copy moves no bytes: 30 s, so a stalled call falls back to the old way fast.
            val movesBytes = keys.isNotEmpty() || seals.isNotEmpty()
            (if (movesBytes) com.google.firebase.functions.FirebaseFunctions.getInstance("us-east1").getHttpsCallable("forwardMediaUs")
                else com.google.firebase.functions.FirebaseFunctions.getInstance(com.fariin.app.data.AuthRepository.FUNCTIONS_REGION).getHttpsCallable("forwardMedia"))
                .also { it.setTimeout(if (movesBytes) 310L else 30L, java.util.concurrent.TimeUnit.SECONDS) }
                .call(args).await().getData() as? Map<*, *>
        } catch (e: Exception) { throw NotInstant("forwardMedia: ${e.message}") }
        if (res?.get("ok") != true) throw NotInstant("forwardMedia refused")
        val answered = (res["objects"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }
        // Every part sent with a key must come back opened; an older server copies the sealed bytes
        // and ignores `keys`, and a plain marker over sealed bytes would be a file nobody can open.
        val openedPaths = answered.filter { it["opened"] == true }.mapNotNull { it["src"] as? String }.toSet()
        // Likewise every part sent with `seal` must come back `sealed: true`: an older server copies the
        // clear bytes, and a sealed envelope over clear bytes is a file nobody can open.
        val sealedPaths = answered.filter { it["sealed"] == true }.mapNotNull { it["src"] as? String }.toSet()
        if (!openedPaths.containsAll(keys.keys) || !sealedPaths.containsAll(seals.keys)) {
            runCatching {
                com.google.firebase.functions.FirebaseFunctions.getInstance(com.fariin.app.data.AuthRepository.FUNCTIONS_REGION)
                    .getHttpsCallable("forwardMedia").call(hashMapOf("undo" to true, "dstCid" to cid, "dstMsgId" to dst.id)).await()
            }
            throw NotInstant("keys not opened")
        }
        val newUrlByPath = answered.mapNotNull { o ->
            (o["src"] as? String)?.let { s -> (o["url"] as? String)?.let { u -> s to u } }
        }.toMap()
        fun nu(old: String): String = newUrlByPath[pathOf[old]] ?: throw NotInstant("no copy for $old")
        // 4. One message, written as that type's send writes it, complete (no `uploading`).
        try {
            val cap = sealOrNull(caption.trim(), members) ?: ""
            val blur = sealOrNull(m.blurhash, members)
            val thumb = sealOrNull(m.thumb, members)
            val now = FieldValue.serverTimestamp()
            val msg: Map<String, Any>
            val preview: String
            var lastUrl: String? = null; var lastEnc: EncMeta? = null; var lastImages: List<Map<String, Any>>? = null
            when (m.mediaKind) {
                Message.MediaKind.PHOTO -> {
                    val u = nu(m.imageUrl!!); val e = metas.getValue(m.imageUrl)
                    msg = MediaMessages.image(me, u, e, cap, clientTs, clientId, m.width?.toInt(), m.height?.toInt(), blur, thumb, now, forwarded = forwarded)
                    preview = MediaRules.PHOTO_PREVIEW; lastUrl = u; lastEnc = e
                }
                Message.MediaKind.VIDEO -> {
                    val tu = nu(m.thumbUrl!!); val te = metas.getValue(m.thumbUrl)
                    msg = MediaMessages.videoAnnounce(me, tu, te, m.duration ?: 0.0, m.width ?: 0.0, m.height ?: 0.0, cap, clientTs,
                        clientId, blur, thumb, forwarded, now) - "uploading" + MediaMessages.attachVideo(nu(m.videoUrl!!), metas.getValue(m.videoUrl), "").minus("uploading")
                    preview = MediaRules.videoPreview(m.duration ?: 0.0); lastUrl = tu; lastEnc = te
                }
                Message.MediaKind.VOICE -> {
                    msg = MediaMessages.audio(me, metas.getValue(m.audioUrl!!), m.duration ?: 0.0, m.waveform, clientTs, clientId, now, forwarded) -
                        "uploading" + ("audioUrl" to nu(m.audioUrl))
                    preview = MediaRules.voicePreview(m.duration ?: 0.0)
                }
                Message.MediaKind.FILE -> {
                    val th = m.thumbUrl?.let { nu(it) to metas.getValue(it) }
                    msg = MediaMessages.file(me, m.fileName ?: "File", (m.fileSize ?: 0L).toInt(), metas.getValue(m.fileUrl!!), clientTs, clientId,
                        th?.first, th?.second, forwarded, now) - "uploading" + ("fileUrl" to nu(m.fileUrl))
                    preview = MediaRules.FILE_PREVIEW
                }
                else -> {
                    val tiles = m.album.map { t ->
                        MediaMessages.Tile(nu(t.imageUrl), metas.getValue(t.imageUrl), t.width ?: 0.0, t.height ?: 0.0,
                            t.videoUrl?.let { nu(it) }, t.videoUrl?.let { metas.getValue(it) }, t.duration ?: 0.0)
                    }
                    msg = MediaMessages.album(me, tiles, cap, clientTs, clientId, blur, thumb, forwarded, now)
                    val cover = tiles.firstOrNull { !it.isVideo } ?: tiles.first()
                    preview = MediaMessages.albumPreview(tiles); lastUrl = cover.imageUrl; lastEnc = cover.imageMeta
                    lastImages = MediaMessages.lastImages(listOf(cover) + tiles.filter { it !== cover })
                }
            }
            val batch = db.batch()
            setMsg(batch, dst, members, msg)
            batch.update(convRef, MediaMessages.convUpdate(me, preview, others(members), now, FieldValue.increment(1),
                lastImageUrl = lastUrl, lastImageEnc = lastEnc, lastImages = lastImages, forwarded = forwarded))
            batch.commit().await()
        } catch (e: Exception) {
            // The copy worked, the message did not: the copies go, then the old forward.
            runCatching {
                com.google.firebase.functions.FirebaseFunctions.getInstance(com.fariin.app.data.AuthRepository.FUNCTIONS_REGION)
                    .getHttpsCallable("forwardMedia").call(hashMapOf("undo" to true, "dstCid" to cid, "dstMsgId" to dst.id)).await()
            }
            throw NotInstant("write: ${e.message}")
        }
        com.fariin.app.media.ChatSounds.sent()
        UploadProgress.done(clientId)
    }

    /** The Storage object path of a Firebase download URL (…/o/<encoded path>?…); null for anything else (R2). */
    private fun storagePath(url: String): String? {
        if (!url.startsWith("https://firebasestorage.googleapis.com/")) return null
        val enc = url.substringAfter("/o/", "").substringBefore('?').ifEmpty { return null }
        return java.net.URLDecoder.decode(enc, "UTF-8").takeIf { it.startsWith("chat/") }
    }

    // ------------------------------------------------------------------ voice (forwarded notes)

    /** sendAudio: the message first with `uploading`, then the sealed note and `audioUrl`. */
    suspend fun audio(data: ByteArray, duration: Double, waveform: List<Int>, forwarded: Boolean, clientId: String, clientTs: Double) {
        val members = members()
        val (cipher, meta) = sealBytes(data, members)
        val msgRef = convRef.collection("messages").document(clientId)
        val batch = db.batch()
        setMsg(batch, msgRef, members, MediaMessages.audio(me, meta, duration, waveform, clientTs, clientId, FieldValue.serverTimestamp(), forwarded))
        batch.update(convRef, MediaMessages.convUpdate(me, MediaRules.voicePreview(duration), others(members),
            FieldValue.serverTimestamp(), FieldValue.increment(1), forwarded = forwarded))
        batch.commit().await()
        val url = uploadRetrying(cipher, MediaRules.audioPath(cid, msgRef.id))
        media?.store(url, data)
        msgRef.update(MediaMessages.attachAudio(url, FieldValue.delete())).await()
        com.fariin.app.media.ChatSounds.sent()
    }
}
