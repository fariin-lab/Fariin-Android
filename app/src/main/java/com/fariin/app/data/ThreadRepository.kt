package com.fariin.app.data

import android.content.Context
import android.net.Uri
import com.fariin.app.crypto.EncMeta
import com.fariin.app.crypto.FariinCrypto
import com.fariin.app.media.ChatMedia
import com.fariin.app.media.MediaMessages
import com.fariin.app.media.MediaRules
import com.fariin.app.media.PhotoPrep
import com.fariin.app.media.VoiceRecorder
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import java.io.File
import kotlinx.coroutines.delay
import com.fariin.app.crypto.Wire
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/**
 * One open chat: the live newest page, older pages on demand, the conversation's receipts, and
 * the text send. A port of the parts of Kulan-iOS ThreadRepository.swift and ChatService.swift
 * that a text chat needs; the Firestore writes are the iOS ones field for field, so they pass
 * the same rules.
 *
 * TODO (iOS has these): the disk cache for an instant open, the send queue that survives a kill,
 * delete, edit, reactions, typing, presence, pins, disappearing timers, video/file/album send, link previews,
 * mentions, blocking, the knock counter for a first message to a stranger.
 */
class ThreadRepository(
    val cid: String,
    private val me: String,
    private val crypto: FariinCrypto,
    private val scope: CoroutineScope,
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    /** The chat's files (download, open, cache); null where media is not wired (tests). */
    val media: ChatMedia? = null,
) {
    data class State(
        val messages: List<Message> = emptyList(),
        val receipts: Receipts = Receipts(),
        val loaded: Boolean = false,
        val canLoadOlder: Boolean = true,
        val loadingOlder: Boolean = false,
        val conv: Map<String, Any?> = emptyMap(),
        val error: String? = null,
    )

    private val convRef = db.collection("conversations").document(cid)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val order = ThreadOrder()
    /** Raw docs by id, so a key arriving later can re-open them. */
    private val liveDocs = LinkedHashMap<String, DocumentSnapshot>()
    private val olderDocs = LinkedHashMap<String, DocumentSnapshot>()
    /** Sends this phone has started, by clientId, until the server echo carries the same clientId. */
    private val pending = ConcurrentHashMap<String, Message>()
    /** My own media files by clientId: drawn and played locally until the server copy has its URL. */
    private val localFiles = ConcurrentHashMap<String, String>()
    /** What a failed media send needs to go again, by clientId. */
    private val retryPhoto = ConcurrentHashMap<String, PhotoPrep>()
    private val retryVoice = ConcurrentHashMap<String, VoiceRecorder.Result>()
    private var msgReg: ListenerRegistration? = null
    private var convReg: ListenerRegistration? = null

    fun start() {
        if (msgReg != null) return
        msgReg = convRef.collection("messages")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(PAGE_SIZE)
            .addSnapshotListener { snap, err ->
                if (snap == null) {
                    _state.update { it.copy(loaded = true, error = err?.message) }
                    return@addSnapshotListener
                }
                // Never blank an open thread on an empty offline snapshot (iOS).
                if (snap.metadata.isFromCache && snap.isEmpty && liveDocs.isNotEmpty()) return@addSnapshotListener
                liveDocs.clear()
                for (d in snap.documents) liveDocs[d.id] = d
                if (!snap.metadata.isFromCache && snap.size() < PAGE_SIZE) _state.update { it.copy(canLoadOlder = false) }
                rebuild(loaded = true)
            }
        convReg = convRef.addSnapshotListener { snap, _ ->
            val data = snap?.data ?: return@addSnapshotListener
            _state.update { it.copy(receipts = Receipts.from(data, me, cid), conv = data) }
            if (data["e2ee"] == false) CryptoHub.notePlainGroup(cid)
        }
        // Keys in the background, then re-open what drew as "…" (iOS keyPreloadTask).
        scope.launch(Dispatchers.IO) {
            try { crypto.ensureReady() } catch (_: Exception) {}
            val members = memberUids()
            crypto.preloadKeys(members.filter { it != me })
            CryptoHub.bumpKeys()
            rebuild()
        }
    }

    fun stop() {
        msgReg?.remove(); msgReg = null
        convReg?.remove(); convReg = null
    }

    private suspend fun memberUids(): List<String> {
        if (!Wire.isGroupCid(cid)) return listOf(Wire.otherUid(cid, me))
        CryptoHub.listedConversation(cid)?.users?.takeIf { it.isNotEmpty() }?.let { return it }
        return try {
            (convRef.get().await().get("users") as? List<*>)?.filterIsInstance<String>() ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    /** iOS `loadOlder`: the page before the oldest held, by value (`start(at:)`), deduped by id. */
    fun loadOlder() {
        val s = _state.value
        if (!s.canLoadOlder || s.loadingOlder || !s.loaded) return
        val oldest = (liveDocs.values + olderDocs.values).mapNotNull { it.getTimestamp("createdAt") }.minOrNull() ?: return
        _state.update { it.copy(loadingOlder = true) }
        scope.launch {
            try {
                val snap = convRef.collection("messages")
                    .orderBy("createdAt", Query.Direction.DESCENDING)
                    .startAt(oldest)
                    .limit(PAGE_SIZE)
                    .get().await()
                var added = 0
                for (d in snap.documents) {
                    if (!liveDocs.containsKey(d.id) && !olderDocs.containsKey(d.id)) { olderDocs[d.id] = d; added++ }
                }
                // A short page from the server is the start of the chat; nothing new means the same.
                val more = added > 0 && (snap.metadata.isFromCache || snap.size() >= PAGE_SIZE)
                _state.update { it.copy(loadingOlder = false, canLoadOlder = more) }
                rebuild()
            } catch (_: Exception) {
                _state.update { it.copy(loadingOlder = false) }
            }
        }
    }

    @Synchronized
    private fun rebuild(loaded: Boolean? = null) {
        val all = LinkedHashMap<String, Message>()
        val unconfirmed = HashSet<String>()
        for (d in olderDocs.values + liveDocs.values) {
            var m = parse(d)
            // My note or photo whose file is still going up: the copy on this phone draws it.
            val local = m.clientId?.let { localFiles[it] }
            if (local != null && m.audioUrl == null && m.imageUrl == null) m = m.copy(localPath = local)
            if (HiddenMessages.isHidden(m.id)) continue
            all[m.rowId] = m
            if (d.metadata.hasPendingWrites()) unconfirmed.add(m.rowId)
        }
        // A pending copy shows until its echo arrives (same clientId). It is dropped only once the
        // SERVER has the message: a refused write rolls the local echo back, and the copy must still
        // be there to turn into a failed bubble (it used to vanish without a word).
        for ((cidKey, p) in pending) {
            when {
                !all.containsKey(cidKey) -> all[cidKey] = p
                cidKey !in unconfirmed -> pending.remove(cidKey)
            }
        }
        val sorted = order.sort(all.values)
        _state.update { it.copy(messages = sorted, loaded = loaded ?: it.loaded) }
    }

    private fun parse(d: DocumentSnapshot): Message {
        val data = d.data ?: emptyMap()
        val m = Message.fromFirestore(d.id, data, cid) { raw, author -> crypto.decrypt(raw, cid, author) }
        return if (d.metadata.hasPendingWrites() && m.authorId == me) m.copy(sendState = Message.SendState.SENDING) else m
    }

    // ------------------------------------------------------------------ send

    /** The iOS SendTurn: one text at a time per chat, in tap order. */
    private val turn = Mutex()

    /** Why the composer cannot send here, or null. My own unsent request takes its one short
     *  text (the knock, iOS MessageRequests .firstMessage); the rest of requests is not ported yet. */
    fun sendBlockedReason(conv: Conversation?): String? {
        if (conv == null || conv.isGroup || conv.startedBy.isEmpty() || conv.accepted) return null
        return when {
            conv.lastSender.isEmpty() && conv.startedBy == me -> null
            conv.lastSender.isEmpty() -> "Message requests are not on Android yet."
            conv.lastSender == me -> "Waiting for them to accept your message."
            else -> null   // they wrote first: my reply accepts it
        }
    }

    fun sendText(text: String) = sendText(text, null)

    /** A text, optionally a reply ([reply] is the quoted message; its words are sealed again). */
    fun sendText(text: String, reply: Message?) {
        val t = text.trim()
        if (t.isEmpty()) return
        val ref = reply?.let { Message.ReplyRef(it.id, it.authorId, it.bodyText.take(200)) }
        val tap = System.currentTimeMillis().toDouble()
        val clientId = UUID.randomUUID().toString().uppercase()
        pending[clientId] = Message(
            id = clientId, authorId = me, text = t, createdAtMillis = tap, hasServerTime = false,
            clientTs = tap, clientId = clientId, sendState = Message.SendState.SENDING, replyTo = ref,
        )
        rebuild()
        scope.launch {
            try {
                turn.withLock {
                    if (Wire.isGroupCid(cid)) sendGroup(t, clientId, tap, ref) else sendDirect(t, clientId, tap, ref)
                }
            } catch (e: Exception) {
                pending[clientId]?.let { pending[clientId] = it.copy(sendState = Message.SendState.FAILED) }
                rebuild()
            }
        }
    }

    /** Tap a failed bubble: send the same words again under a new clientId. */
    fun retry(m: Message) {
        val id = m.clientId ?: return
        pending.remove(id)
        retryPhoto.remove(id)?.let { sendPreparedPhoto(it); return }
        retryVoice.remove(id)?.let { sendVoice(it); return }
        if (m.mediaKind != null) { rebuild(); return }
        sendText(m.text)
    }

    // ------------------------------------------------------------------ media (ChatService.sendImage / sendAudio)

    /** A photo from the system picker: made ready like iOS (sendJPEG), then sent. */
    fun sendPhoto(context: Context, uri: Uri) {
        scope.launch(Dispatchers.IO) {
            val prep = PhotoPrep.fromUri(context, uri) ?: return@launch
            sendPreparedPhoto(prep)
        }
    }

    private fun sendPreparedPhoto(prep: PhotoPrep) {
        val m = media ?: return
        val tap = System.currentTimeMillis().toDouble()
        val clientId = UUID.randomUUID().toString().uppercase()
        val local = File(m.localDir(), "send-$clientId.jpg")
        runCatching { local.writeBytes(prep.jpeg) }
        localFiles[clientId] = local.absolutePath
        pending[clientId] = Message(
            id = clientId, authorId = me, text = "", type = "image", createdAtMillis = tap, hasServerTime = false,
            clientTs = tap, clientId = clientId, sendState = Message.SendState.SENDING,
            width = prep.width.toDouble(), height = prep.height.toDouble(), thumb = prep.thumbB64,
            localPath = local.absolutePath,
        )
        rebuild()
        scope.launch(Dispatchers.IO) {
            try {
                photoNow(prep, clientId, tap)
            } catch (e: Exception) {
                retryPhoto[clientId] = prep
                pending[clientId]?.let { pending[clientId] = it.copy(sendState = Message.SendState.FAILED) }
                rebuild()
            }
        }
    }

    private suspend fun photoNow(prep: PhotoPrep, clientId: String, clientTs: Double) {
        val members = if (Wire.isGroupCid(cid)) freshMembers() else null
        val other = Wire.otherUid(cid, me)
        val (cipher, meta) = sealBytes(prep.jpeg, members)
        if (members == null) seedDirect(other)
        val msgRef = convRef.collection("messages").document()
        // The message is written only once the bytes are up (iOS, owner 2026-10-10).
        val url = upload(cipher, MediaRules.imagePath(cid, msgRef.id))
        media?.store(url, prep.jpeg)
        val blur = prep.blurhash?.let { runCatching { seal(it, members) }.getOrNull() }
        val thumb = prep.thumbB64?.let { runCatching { seal(it, members) }.getOrNull() }
        val batch = db.batch()
        batch.set(msgRef, MediaMessages.image(me, url, meta, "", clientTs, clientId, prep.width, prep.height,
            blur, thumb, FieldValue.serverTimestamp()))
        batch.update(convRef, MediaMessages.convUpdate(me, MediaRules.PHOTO_PREVIEW, members ?: listOf(other),
            FieldValue.serverTimestamp(), FieldValue.increment(1), lastImageUrl = url, lastImageEnc = meta))
        batch.commit().await()
    }

    /** A recorded note: the message goes out first (with `uploading`), the file follows (iOS). */
    fun sendVoice(r: VoiceRecorder.Result) {
        val tap = System.currentTimeMillis().toDouble()
        val clientId = UUID.randomUUID().toString().uppercase()
        localFiles[clientId] = r.file.absolutePath
        pending[clientId] = Message(
            id = clientId, authorId = me, text = "", type = "audio", createdAtMillis = tap, hasServerTime = false,
            clientTs = tap, clientId = clientId, sendState = Message.SendState.SENDING,
            duration = r.duration, waveform = r.waveform, localPath = r.file.absolutePath,
        )
        rebuild()
        scope.launch(Dispatchers.IO) {
            try {
                voiceNow(r, clientId, tap)
            } catch (e: Exception) {
                retryVoice[clientId] = r
                pending[clientId]?.let { pending[clientId] = it.copy(sendState = Message.SendState.FAILED) }
                rebuild()
            }
        }
    }

    private suspend fun voiceNow(r: VoiceRecorder.Result, clientId: String, clientTs: Double) {
        val data = r.file.readBytes()
        val members = if (Wire.isGroupCid(cid)) freshMembers() else null
        val other = Wire.otherUid(cid, me)
        val (cipher, meta) = sealBytes(data, members)
        if (members == null) seedDirect(other)
        // One fixed address (iOS textMessageRef): a retried note is never a second note.
        val msgRef = convRef.collection("messages").document(clientId)
        val batch = db.batch()
        batch.set(msgRef, MediaMessages.audio(me, meta, r.duration, r.waveform, clientTs, clientId, FieldValue.serverTimestamp()))
        batch.update(convRef, MediaMessages.convUpdate(me, MediaRules.voicePreview(r.duration), members ?: listOf(other),
            FieldValue.serverTimestamp(), FieldValue.increment(1)))
        batch.commit().await()
        val path = MediaRules.audioPath(cid, msgRef.id)
        var url: String? = null
        var attempt = 0
        while (url == null) {
            try { url = upload(cipher, path) } catch (e: Exception) {
                if (++attempt >= 6) throw e
                delay(minOf(60_000L, 2_000L shl attempt))
            }
        }
        media?.store(url, data)
        msgRef.update(MediaMessages.attachAudio(url, FieldValue.delete())).await()
    }

    private suspend fun sealBytes(data: ByteArray, members: List<String>?): Pair<ByteArray, EncMeta> =
        if (members != null) crypto.sealBytesForGroup(data, members, cid) else crypto.encryptBytes(cid, data)

    private suspend fun seal(text: String, members: List<String>?): String =
        if (members != null) crypto.sealForGroup(text, members, cid) else crypto.encryptForConversation(cid, text)

    /** iOS sendImage / sendAudio always make sure the 1:1 doc has both of us and a time. */
    private suspend fun seedDirect(other: String) {
        convRef.set(mapOf("users" to listOf(me, other), "updatedAt" to FieldValue.serverTimestamp()), SetOptions.merge()).await()
    }

    /** End-to-end encrypted bytes go up as application/octet-stream (storage.rules `chat/`). */
    private suspend fun upload(cipher: ByteArray, path: String): String {
        require(cipher.size < MediaRules.STORAGE_CHAT_LIMIT) { "file too large" }
        val ref = FirebaseStorage.getInstance().reference.child(path)
        ref.putBytes(cipher, StorageMetadata.Builder().setContentType("application/octet-stream").build()).await()
        return ref.downloadUrl.await().toString()
    }

    /** iOS freshGroupMembers: the member list from the SERVER; the cached one when offline. */
    private suspend fun freshMembers(): List<String> = try {
        val snap = convRef.get(Source.SERVER).await()
        if (snap.getBoolean("e2ee") == false) CryptoHub.notePlainGroup(cid)
        (snap.get("users") as? List<*>)?.filterIsInstance<String>()
    } catch (_: Exception) { null } ?: memberUids()

    private suspend fun sendDirect(t: String, clientId: String, clientTs: Double, reply: Message.ReplyRef? = null) {
        val other = Wire.otherUid(cid, me)
        val cipher = crypto.encryptForConversation(cid, t)
        val local = CryptoHub.listedConversation(cid)
        val answering = local != null && !local.isGroup && local.startedBy.isNotEmpty() && !local.accepted &&
            local.lastSender.isNotEmpty() && local.lastSender != me
        // The knock: my one first message into a request I opened (iOS `knocking`). The rules take
        // it as short text only and hold it to the day's budget, which the app counts first.
        val knocking = if (local != null) isKnock(local.startedBy, local.accepted, local.lastSender)
            else try {
                val d = convRef.get().await()
                isKnock(d.getString("startedBy") ?: "", d.getBoolean("accepted") ?: false, d.getString("lastSender") ?: "")
            } catch (_: Exception) { false }
        if (knocking) {
            require(t.codePointCount(0, t.length) <= FIRST_MESSAGE_LIMIT) { "a first message is at most $FIRST_MESSAGE_LIMIT characters" }
            require(reply == null) { "a first message is plain text" }
            countKnock()
        }
        // The seed, only when this phone's list does not already show both of us in it (iOS
        // `seedNeeded`; a brand-new chat is never opened from the Android list yet).
        if (local == null || !(local.users.contains(me) && local.users.contains(other))) {
            convRef.set(mapOf("users" to listOf(me, other), "updatedAt" to FieldValue.serverTimestamp()), SetOptions.merge()).await()
        }
        val batch = db.batch()
        val msg = hashMapOf<String, Any>(
            "text" to cipher,
            "authorId" to me,
            "createdAt" to FieldValue.serverTimestamp(),
            "clientTs" to clientTs,
            "clientId" to clientId,
        )
        // iOS replyEnc: {id, authorId, text sealed like the message}.
        if (reply != null) msg["replyTo"] = mapOf("id" to reply.id, "authorId" to reply.authorId, "text" to crypto.encryptForConversation(cid, reply.text))
        batch.set(convRef.collection("messages").document(clientId), msg)
        val conv = hashMapOf<String, Any>(
            "lastMessage" to cipher,
            "lastSender" to me,
            "updatedAt" to FieldValue.serverTimestamp(),
            "unreadCount.$other" to FieldValue.increment(1),
        )
        if (answering) conv["accepted"] = true
        batch.update(convRef, conv)
        batch.commit().await()
    }

    private fun isKnock(startedBy: String, accepted: Boolean, lastSender: String) =
        startedBy == me && !accepted && lastSender.isEmpty()

    /** iOS MessageRequests.countDaily("requests"): `users/{me}/limits/requests`, a 24 h window and a
     *  count that only goes up by one. Best effort, as iOS: the rules fail open with no counter. */
    private suspend fun countKnock() {
        val ref = db.collection("users").document(me).collection("limits").document("requests")
        try {
            val snap = ref.get().await()
            val start = snap.getTimestamp("windowStart")?.toDate()?.time
            if (snap.exists() && start != null && System.currentTimeMillis() - start < 24 * 3600_000L) {
                ref.update("count", FieldValue.increment(1)).await()
            } else {
                ref.set(mapOf("windowStart" to FieldValue.serverTimestamp(), "count" to 1)).await()
            }
        } catch (_: Exception) {}
    }

    private suspend fun sendGroup(t: String, clientId: String, clientTs: Double, reply: Message.ReplyRef? = null) {
        // iOS freshGroupMembers: a member removed elsewhere is not wrapped for.
        val members = freshMembers()
        val cipher = crypto.sealForGroup(t, members, cid)
        val batch = db.batch()
        val msg = hashMapOf<String, Any>(
            "text" to cipher,
            "authorId" to me,
            "createdAt" to FieldValue.serverTimestamp(),
            "clientTs" to clientTs,
            "clientId" to clientId,
        )
        // iOS replyEnc: {id, authorId, text sealed like the message}.
        if (reply != null) msg["replyTo"] = mapOf("id" to reply.id, "authorId" to reply.authorId, "text" to crypto.sealForGroup(reply.text, members, cid))
        batch.set(convRef.collection("messages").document(clientId), msg)
        val conv = hashMapOf<String, Any>(
            "lastMessage" to cipher,
            "lastSender" to me,
            "updatedAt" to FieldValue.serverTimestamp(),
        )
        // Words in the clear say they are a member's (Conversation.lastPlainTag).
        if (!cipher.startsWith("enc")) conv["lastPlainTag"] = Conversation.plainTag(cipher)
        for (m in members) if (m != me) conv["unreadCount.$m"] = FieldValue.increment(1)
        batch.update(convRef, conv)
        batch.commit().await()
    }

    // ------------------------------------------------------------------ message actions (iOS ChatService)

    private fun msgRef(id: String) = convRef.collection("messages").document(id)

    /** ChatService.editMessage: re-sealed, the replaced text appended to `edits` (at most 10). */
    suspend fun editMessage(m: Message, newText: String): Boolean {
        val t = newText.trim()
        if (t.isEmpty()) return false
        return try {
            val members = if (Wire.isGroupCid(cid)) freshMembers() else null
            val cipher = seal(t, members)
            val ref = msgRef(m.id)
            val data = ref.get().await().data ?: return false
            if (data["deleted"] == true) return false
            val edits = ((data["edits"] as? List<*>)?.mapNotNull { it as? Map<*, *> } ?: emptyList()).toMutableList()
            (data["text"] as? String)?.let { cur ->
                val at = data["editedAt"] ?: data["createdAt"] ?: com.google.firebase.Timestamp.now()
                edits.add(mapOf("t" to cur, "at" to at))
            }
            fun size() = edits.sumOf { ((it["t"] as? String)?.length ?: 0) } + cipher.length
            while (edits.size > 10 || (edits.size > 1 && size() > 700_000)) edits.removeAt(0)
            val update = hashMapOf<String, Any>("text" to cipher, "edited" to true, "editedAt" to FieldValue.serverTimestamp())
            if (size() <= 700_000) update["edits"] = edits
            ref.update(update).await()
            // The list still shows the old words when this was the newest message.
            val newest = convRef.collection("messages").orderBy("createdAt", Query.Direction.DESCENDING).limit(1).get().await()
            if (newest.documents.firstOrNull()?.id == m.id) {
                val sum = hashMapOf<String, Any>("lastMessage" to cipher)
                if (Wire.isGroupCid(cid) && !cipher.startsWith("enc")) sum["lastPlainTag"] = Conversation.plainTag(cipher)
                try { convRef.update(sum).await() } catch (_: Exception) {}
            }
            true
        } catch (_: Exception) { false }
    }

    /**
     * ChatService.deleteMessage: a tombstone (content stripped, type back to text), falling back to
     * a hard delete when refused; a second delete on a tombstone removes it. Storage files go while
     * the doc still exists; then the chat list summary is re-pointed at the newest message.
     */
    suspend fun deleteForEveryone(m: Message): Boolean {
        return try {
            val ref = msgRef(m.id)
            val snap = ref.get().await()
            if (!snap.exists()) return true
            val data = snap.data ?: emptyMap()
            val blobs = buildList {
                for (k in listOf("imageUrl", "videoUrl", "thumbUrl", "audioUrl", "fileUrl")) (data[k] as? String)?.let(::add)
                (data["album"] as? List<*>)?.forEach { ((it as? Map<*, *>)?.get("imageUrl") as? String)?.let(::add) }
            }.filter { it.startsWith("https://") || it.startsWith("gs://") }
            var marked = false
            if (data["deleted"] != true) {
                val strip = hashMapOf<String, Any>("deleted" to true, "text" to "", "type" to "text", "enc" to FieldValue.delete())
                for (k in TOMBSTONE_STRIP) strip[k] = FieldValue.delete()
                if (data["authorId"] as? String != me) strip["deletedBy"] = me
                marked = try { ref.update(strip).await(); true } catch (_: Exception) { false }
            }
            for (u in blobs) try { FirebaseStorage.getInstance().getReferenceFromUrl(u).delete().await() } catch (_: Exception) {}
            if (!marked) ref.delete().await()
            rewriteSummaryAfterDelete()
            true
        } catch (_: Exception) { false }
    }

    private suspend fun rewriteSummaryAfterDelete() {
        try {
            val group = Wire.isGroupCid(cid)
            val newest = convRef.collection("messages").orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(if (group) 25 else 1).get().await().documents
            val d = (if (group) newest.firstOrNull { it.getBoolean("deleted") != true } else newest.firstOrNull())?.data
            val update = hashMapOf<String, Any>()
            if (d == null) {
                update["lastMessage"] = ""; update["lastSender"] = ""
                update["lastImageUrl"] = FieldValue.delete(); update["lastImageEnc"] = FieldValue.delete()
            } else {
                val gone = d["deleted"] == true
                val line = if (gone) "This message was deleted" else summaryText(d)
                update["lastMessage"] = line
                update["lastSender"] = d["authorId"] as? String ?: ""
                val kind = d["type"] as? String ?: ""
                if (group && !gone && kind != "system" && kind != "call" && line.isNotEmpty() && !line.startsWith("enc")) {
                    update["lastPlainTag"] = Conversation.plainTag(line)
                }
                val u = d["imageUrl"] as? String
                val e = d["enc"]
                if (u != null && e is Map<*, *> && d["viewOnce"] != true) { update["lastImageUrl"] = u; update["lastImageEnc"] = e }
                else { update["lastImageUrl"] = FieldValue.delete(); update["lastImageEnc"] = FieldValue.delete() }
            }
            convRef.update(update).await()
        } catch (_: Exception) {}
    }

    /** iOS summaryText: the sealed words, else a label for the kind. */
    private fun summaryText(d: Map<String, Any?>): String {
        val text = d["text"] as? String ?: ""
        val type = d["type"] as? String ?: ""
        val once = d["viewOnce"] == true
        if (text.isNotEmpty() && (!once || type == "text" || type.isEmpty())) return text
        return when (type) {
            "image" -> if (once) "View-once photo" else "📷 Photo"
            "album" -> ((d["album"] as? List<*>)?.size ?: 0).let { if (it > 1) "📷 $it Photos" else "📷 Photo" }
            "video" -> "🎥 Video"
            "audio" -> if (once) "🎤 One-time voice message" else "🎤 Voice message"
            "file" -> "📄 File"
            "gif" -> "GIF"
            "call" -> {
                val video = d["callVideo"] == true
                if (d["callOutcome"] == "missed") (if (video) "📹 Missed video call" else "📞 Missed call")
                else if (video) "📹 Video call" else "📞 Call"
            }
            else -> text
        }
    }

    /** Delete for Me: hidden on this phone only (an unsent one of mine is also dropped). */
    fun hideForMe(context: Context, m: Message) {
        m.clientId?.let { pending.remove(it); retryPhoto.remove(it); retryVoice.remove(it) }
        HiddenMessages.hide(context, m.id)
        rebuild()
    }

    /**
     * ChatService.setReaction: `reactions.<me>` sealed like text (for all members in a group),
     * or deleted; then the chat list's "Reacted" preview (best effort, never bumps updatedAt).
     */
    suspend fun setReaction(m: Message, emoji: String?): Boolean {
        val ref = msgRef(m.id)
        if (emoji == null) {
            return try {
                ref.update("reactions.$me", FieldValue.delete()).await()
                val cs = convRef.get().await()
                if (cs.getString("lastReactionBy") == me) convRef.update(mapOf(
                    "lastReactionEnc" to FieldValue.delete(), "lastReactionBy" to FieldValue.delete(),
                    "lastReactionToAuthor" to FieldValue.delete(), "lastReactionAt" to FieldValue.delete(),
                    "lastReactionToEnc" to FieldValue.delete(), "lastReactionToImages" to FieldValue.delete(),
                )).await()
                true
            } catch (_: Exception) { false }
        }
        return try {
            val members = if (Wire.isGroupCid(cid)) freshMembers() else null
            val enc = seal(emoji, members)
            ref.update("reactions.$me", enc).await()
            val snippet = m.bodyText.trim().take(60).takeIf { it.isNotEmpty() }
            try {
                convRef.update(mapOf(
                    "lastReactionEnc" to enc, "lastReactionBy" to me, "lastReactionToAuthor" to m.authorId,
                    "lastReactionAt" to FieldValue.serverTimestamp(),
                    "lastReactionToEnc" to (snippet?.let { seal(it, members) } ?: FieldValue.delete()),
                    "lastReactionToImages" to FieldValue.delete(),
                )).await()
            } catch (_: Exception) {}
            true
        } catch (_: Exception) { false }
    }

    /** ChatService.setTyping: `typing.<me>` = "text-<seconds>" while typing, false after. */
    fun setTyping(on: Boolean) {
        scope.launch {
            try {
                val v = MessageRules.typingValue(on, System.currentTimeMillis())
                convRef.set(mapOf("typing" to mapOf(me to v)), SetOptions.merge()).await()
            } catch (_: Exception) {}
        }
    }

    // ------------------------------------------------------------------ read state

    private val readMutex = Mutex()
    @Volatile private var readOwed = false

    /**
     * iOS `resetUnread` + `markReadThrottled`: my badge to 0 (and my Mark as Unread flag off), then
     * `lastRead.<me>` stamped, which turns the other side's ticks solid. No timer: while one write
     * is in flight, newer calls fold into one trailing write.
     */
    fun markRead(readReceipts: Boolean = true) {
        if (readMutex.isLocked) { readOwed = true; return }
        scope.launch {
            readMutex.withLock {
                do {
                    readOwed = false
                    val local = CryptoHub.listedConversation(cid)
                    val update = hashMapOf<String, Any>("unreadCount.$me" to 0)
                    if (local?.markedUnread?.get(me) == true) update["markedUnread.$me"] = FieldValue.delete()
                    try { convRef.update(update).await() } catch (_: Exception) {}
                    val iBlocked = _state.value.receipts.iBlocked
                    if (readReceipts && !iBlocked) {
                        try {
                            convRef.set(mapOf("lastRead" to mapOf(me to FieldValue.serverTimestamp())), SetOptions.merge()).await()
                        } catch (_: Exception) {}
                    }
                } while (readOwed)
            }
        }
    }

    companion object {
        /** MessageRequests.firstMessageLimit (iOS). */
        const val FIRST_MESSAGE_LIMIT = 150
        const val PAGE_SIZE = 60L
        /** Every content field a tombstone drops (iOS deleteMessage `strip`). */
        val TOMBSTONE_STRIP = listOf("imageUrl", "videoUrl", "thumbUrl", "audioUrl", "fileUrl", "album",
            "fileName", "fileSize", "waveform", "duration", "blurhash", "linkPreview", "poll", "locationCard",
            "contactCard", "replyTo", "reactions", "videoEnc", "thumbEnc", "viewOnce", "mentions", "forwarded",
            "fwdChannel", "thumb", "edits", "editedAt")
    }
}
