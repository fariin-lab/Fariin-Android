package com.fariin.app.data

import android.content.Context
import android.net.Uri
import com.fariin.app.crypto.EncMeta
import com.fariin.app.crypto.FariinCrypto
import com.fariin.app.media.ChatMedia
import com.fariin.app.media.MediaMessages
import com.fariin.app.media.MediaRules
import com.fariin.app.media.PhotoPrep
import com.fariin.app.media.FilePrep
import com.fariin.app.media.MediaSender
import com.fariin.app.media.VideoPrep
import com.fariin.app.media.VoiceRecorder
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
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
import com.fariin.app.local.LocalCache
import com.fariin.app.local.SendQueue

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
        /** The server has answered once (not the phone's copy): the unread divider waits for it. */
        val synced: Boolean = false,
        val canLoadOlder: Boolean = true,
        val loadingOlder: Boolean = false,
        val conv: Map<String, Any?> = emptyMap(),
        val error: String? = null,
        /** 1:1 presence (iOS otherOnline / otherLastActive), and both privacy maps for the rules. */
        val otherOnline: Boolean = false,
        val otherLastActive: Double? = null,
        val otherPrivacy: Map<String, String> = emptyMap(),
        val myPrivacy: Map<String, String> = emptyMap(),
        /** A group I am no longer in (removed, or left on another phone): iOS notAMember. `conv` is
         *  then its last copy with me out of `users`; the messages already here stay. */
        val former: Boolean = false,
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
    /** Video, file, album and captioned / view-once photo sends, by clientId, to go again on a tap. */
    private val retryMedia = ConcurrentHashMap<String, suspend () -> Unit>()
    /** The media sends (iOS ChatService media paths), see media/MediaSender. */
    private val sender: MediaSender by lazy { MediaSender(cid, me, crypto, media, db) }
    private var msgReg: ListenerRegistration? = null
    private var convReg: ListenerRegistration? = null
    private var presenceReg: ListenerRegistration? = null
    private var otherReg: ListenerRegistration? = null
    private var myReg: ListenerRegistration? = null

    /** True while the screen shows the phone's saved copy and no live answer has come yet. */
    @Volatile private var seeded = false
    /** Messages are only cached once the listener has really answered. */
    @Volatile private var live = false
    @Volatile private var msgFailed = false

    fun start() {
        if (msgReg != null) return
        // The phone's copy first (iOS ThreadMessageCache), so the chat opens full, offline too;
        // the listener's first answer replaces it.
        if (liveDocs.isEmpty() && _state.value.messages.isEmpty()) {
            // A disappearing message that ran out while the chat was closed never draws (iOS
            // ThreadRepository cold open), not even for a frame.
            val now = System.currentTimeMillis().toDouble()
            val saved = LocalCache.loadThread(me, cid).filter { !HiddenMessages.isHidden(it.id) && !Disappearing.isExpired(it, now) }
            if (saved.isNotEmpty()) {
                seeded = true
                _state.update { it.copy(messages = order.sort(saved), loaded = true) }
            }
        }
        open[cid] = this
        // A group I was removed from earlier this session (iOS FormerGroups): its last copy, no listeners.
        val former = FormerGroups.get(cid)
        if (former != null && CryptoHub.listedConversation(cid)?.users?.contains(me) != true) {
            _state.update { it.copy(conv = former, former = true, loaded = true, synced = true) }
            watchBack()
            return
        }
        // Texts still owed from before a kill or an outage: drawn as sending, and driven again.
        for (e in SendQueue.pending(me, cid)) resend(e)
        startListeners()
    }

    /** Back in front or back online (iOS reattachIfDead): a listener that died is made again,
     *  and texts still queued for this chat go again. */
    fun recover() {
        if (_state.value.former) return
        if (msgFailed) {
            msgReg?.remove(); msgReg = null
            convReg?.remove(); convReg = null
            msgFailed = false
            startListeners()
        } else if (!Wire.isGroupCid(cid) && presenceReg == null) {
            otherReg?.remove(); otherReg = null; myReg?.remove(); myReg = null
            attachPresence(Wire.otherUid(cid, me))
        }
        for (e in SendQueue.pending(me, cid)) if (!SendQueue.isInFlight(e.clientId)) resend(e)
    }

    private fun startListeners() {
        if (msgReg != null) return
        msgReg = convRef.collection("messages")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(PAGE_SIZE)
            // Metadata too: a media message's last write (attach the file URL, drop `uploading`)
            // changes no value the server adds, so without this the "sending" mark never clears.
            .addSnapshotListener(com.google.firebase.firestore.MetadataChanges.INCLUDE) { snap, err ->
                if (snap == null) {
                    msgFailed = true
                    _state.update { it.copy(loaded = true, synced = true, error = err?.message) }
                    return@addSnapshotListener
                }
                // Never blank an open thread on an empty offline snapshot (iOS), nor the saved copy.
                if (snap.metadata.isFromCache && snap.isEmpty && (liveDocs.isNotEmpty() || seeded)) return@addSnapshotListener
                seeded = false
                live = true
                liveDocs.clear()
                for (d in snap.documents) liveDocs[d.id] = d
                if (!snap.metadata.isFromCache && snap.size() < PAGE_SIZE) _state.update { it.copy(canLoadOlder = false) }
                rebuild(loaded = true)
                if (!snap.metadata.isFromCache && !_state.value.synced) _state.update { it.copy(synced = true) }
            }
        convReg = convRef.addSnapshotListener { snap, err ->
            // Removed from the group (or left on another phone): the rules refuse a non-member's
            // read, so the listener ends refused. Asked once more, as iOS confirmRemovedFromGroup.
            if (snap == null) {
                if (Wire.isGroupCid(cid) && (err as? com.google.firebase.firestore.FirebaseFirestoreException)?.code ==
                    com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) confirmRemoved()
                return@addSnapshotListener
            }
            val data = snap.data
            if (data == null) {
                if (Wire.isGroupCid(cid) && !snap.metadata.isFromCache) confirmRemoved()
                return@addSnapshotListener
            }
            if (me in ((data["users"] as? List<*>) ?: emptyList<Any>())) wasMember = true
            if (wasMember && !snap.metadata.isFromCache && FormerGroups.isFormer(cid, data, me)) { becomeFormer(data); return@addSnapshotListener }
            _state.update { it.copy(receipts = Receipts.from(data, me, cid), conv = data) }
            if (data["e2ee"] == false) CryptoHub.notePlainGroup(cid)
            syncPins(PinRules.pinnedIds(data))
        }
        if (!Wire.isGroupCid(cid)) attachPresence(Wire.otherUid(cid, me))
        // A block or unblock made while the chat is open re-filters what shows.
        scope.launch { BlockList.state.collect { if (msgReg != null) rebuild() } }
        // Keys in the background, then re-open what drew as "…" (iOS keyPreloadTask).
        scope.launch(Dispatchers.IO) {
            try { crypto.ensureReady() } catch (_: Exception) {}
            val members = memberUids()
            crypto.preloadKeys(members.filter { it != me })
            CryptoHub.bumpKeys()
            rebuild()
        }
    }

    /** iOS attachPresence: their presence doc (a refusal is a normal privacy answer: kept quiet,
     *  the last value stays), their privacy and mine from the user documents. */
    private fun attachPresence(other: String) {
        if (other.isEmpty() || presenceReg != null) return
        val users = db.collection("users")
        presenceReg = users.document(other).collection("presence").document("state").addSnapshotListener { snap, err ->
            if (snap == null) { presenceReg?.remove(); presenceReg = null; return@addSnapshotListener }
            val d = snap.data
            _state.update { it.copy(otherOnline = d?.get("online") as? Boolean ?: false,
                otherLastActive = d?.get("lastActive")?.let { v -> Conversation.millis(v) }?.takeIf { v -> v > 0 } ?: it.otherLastActive) }
        }
        otherReg = users.document(other).addSnapshotListener { snap, _ ->
            val d = snap?.data ?: return@addSnapshotListener
            _state.update { it.copy(otherPrivacy = Presence.privacyOf(d)) }
        }
        myReg = users.document(me).addSnapshotListener { snap, _ ->
            val d = snap?.data ?: return@addSnapshotListener
            val p = Presence.privacyOf(d)
            Presence.noteMyPrivacy(p)
            _state.update { it.copy(myPrivacy = p) }
        }
    }

    /** I was seen in `users` in this chat, so a copy without me means I was taken out (iOS old.users.contains(me)). */
    @Volatile private var wasMember = CryptoHub.listedConversation(cid)?.users?.contains(me) == true
    private var backJob: Job? = null

    /** iOS confirmRemovedFromGroup: a refusal, a missing doc or `users` without me means removed. */
    private fun confirmRemoved() {
        if (!wasMember || _state.value.former) return
        scope.launch {
            val gone = try {
                val snap = convRef.get(Source.SERVER).await()
                FormerGroups.goneFromServer(snap.exists(), (snap.get("users") as? List<*>)?.filterIsInstance<String>(), me)
            } catch (e: com.google.firebase.firestore.FirebaseFirestoreException) {
                e.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED
            } catch (_: Exception) { false }
            if (gone && !_state.value.former) becomeFormer(_state.value.conv)
        }
    }

    /** No longer a member: the last copy without me, kept for this session; the listeners end. */
    private fun becomeFormer(data: Map<String, Any?>) {
        val stripped = FormerGroups.strip(data, me)
        FormerGroups.note(cid, stripped, CryptoHub.listedConversation(cid)?.updatedAtMillis ?: 0.0)
        msgReg?.remove(); msgReg = null
        convReg?.remove(); convReg = null
        _state.update { it.copy(conv = stripped, former = true) }
        watchBack()
    }

    /** Added back (the chat list holds the group with me again): live once more. */
    private fun watchBack() {
        if (backJob != null) return
        backJob = scope.launch {
            FormerGroups.back.collect { id ->
                if (id != cid) return@collect
                wasMember = true
                msgFailed = false
                _state.update { it.copy(former = false, error = null) }
                startListeners()
            }
        }
    }

    fun stop() {
        backJob?.cancel(); backJob = null
        open.remove(cid, this)
        presenceReg?.remove(); presenceReg = null
        otherReg?.remove(); otherReg = null
        myReg?.remove(); myReg = null
        msgReg?.remove(); msgReg = null
        convReg?.remove(); convReg = null
        burnJob?.cancel(); burnJob = null
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
        val now = System.currentTimeMillis().toDouble()
        val stamped = ArrayList<Message>()
        val due = ArrayList<Message>()
        for (d in olderDocs.values + liveDocs.values) {
            var m = parse(d)
            // The disappearing timer: the server's stamp only (iOS). Due = off the screen now, from
            // the saved copy too; the server deletes it for both people.
            if (m.expiresAtMillis != null) {
                if (Disappearing.isExpired(m, now)) { due.add(m); continue }
                stamped.add(m)
            }
            // My note or photo whose file is still going up: the copy on this phone draws it.
            val local = m.clientId?.let { localFiles[it] }
            if (local != null && m.audioUrl == null && m.imageUrl == null) m = m.copy(localPath = local)
            if (HiddenMessages.isHidden(m.id)) continue
            // Sent while I had them blocked (now or in a past block): never shown (iOS BlockList.hides).
            if (m.authorId != me && BlockList.hides(m.authorId, m.createdAtMillis)) continue
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
        // While only the saved copy is on screen, a pending send joins it instead of replacing it.
        val shown = if (seeded && liveDocs.isEmpty() && olderDocs.isEmpty())
            _state.value.messages.filter { it.sendState == null && it.rowId !in all && !Disappearing.isExpired(it, now) } + all.values else all.values
        val sorted = order.sort(shown)
        _state.update { it.copy(messages = sorted, loaded = loaded ?: it.loaded) }
        if (live) LocalCache.saveThread(me, cid, sorted)
        scheduleBurn(Disappearing.nextExpiry(stamped, now))
        if (due.isNotEmpty()) burn(due)
    }

    // ------------------------------------------------------------------ disappearing messages

    private var burnJob: Job? = null
    private val burned = HashSet<String>()

    /** One timer for the soonest stamp still ahead, a fifth of a second past it (iOS scheduleNextBurn). */
    private fun scheduleBurn(next: Double?) {
        burnJob?.cancel(); burnJob = null
        if (next == null || msgReg == null) return
        val wait = maxOf(200L, (next - System.currentTimeMillis()).toLong() + 200L)
        burnJob = scope.launch { delay(wait); rebuild() }
    }

    /** Due messages: the files this phone opened go, and the server is asked to delete (it re-reads
     *  every id and refuses anything not really due). Each id once per run. */
    private fun burn(due: List<Message>) {
        val fresh = synchronized(burned) { due.filter { burned.add(it.id) } }
        if (fresh.isEmpty()) return
        media?.let { md ->
            for (m in fresh) {
                val urls = listOfNotNull(m.imageUrl, m.videoUrl, m.audioUrl, m.fileUrl, m.thumbUrl) +
                    m.album.flatMap { listOfNotNull(it.imageUrl, it.videoUrl) }
                for (u in urls) try { md.cached(u)?.delete() } catch (_: Exception) {}
            }
        }
        Disappearing.askBurn(cid, fresh.map { it.id })
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
    /** iOS ThreadRepository.iBlocked also reads my private list (users/{me}/blocked), not only the
     *  old shared blockedBy copy on the chat. */
    fun blockedByMyList(): Boolean = !Wire.isGroupCid(cid) &&
        BlockList.snapshot.owner == me && BlockList.contains(Wire.otherUid(cid, me))

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
        // A card is quoted by its noun (iOS safeText), never by its raw marker.
        val ref = reply?.let { Message.ReplyRef(it.id, it.authorId, (CardRules.previewWord(it.text) ?: it.bodyText).take(200)) }
        val tap = System.currentTimeMillis().toDouble()
        val clientId = UUID.randomUUID().toString().uppercase()
        // On disk BEFORE the network (iOS SendQueue.add), so a kill or an outage cannot lose it.
        val e = SendQueue.Entry(clientId, cid, t, tap, ref?.id, ref?.authorId, ref?.text)
        SendQueue.add(me, e)
        drive(e, checkFirst = false)
    }

    /** A queued text from an earlier run or a reconnect: sent again unless the server (or
     *  Firestore's own offline queue) already has it. */
    fun resend(e: SendQueue.Entry) = drive(e, checkFirst = true)

    private fun drive(e: SendQueue.Entry, checkFirst: Boolean) = drive(e, checkFirst, sendScope)

    private fun drive(e: SendQueue.Entry, checkFirst: Boolean, on: CoroutineScope) {
        if (!SendQueue.begin(e.clientId)) return
        val ref = e.replyId?.let { Message.ReplyRef(it, e.replyAuthor ?: "", e.replyText ?: "") }
        pending[e.clientId] = Message(
            id = e.clientId, authorId = me, text = e.text, createdAtMillis = e.ts, hasServerTime = false,
            clientTs = e.ts, clientId = e.clientId, sendState = Message.SendState.SENDING, replyTo = ref,
        )
        rebuild()
        on.launch {
            try {
                if (checkFirst && alreadySent(e.clientId)) { SendQueue.remove(me, e.clientId); return@launch }
                turn.withLock {
                    if (Wire.isGroupCid(cid)) sendGroup(e.text, e.clientId, e.ts, ref) else sendDirect(e.text, e.clientId, e.ts, ref)
                }
                SendQueue.remove(me, e.clientId)
            } catch (x: Exception) {
                // A refusal is final (the bubble offers Retry, which is a new send); anything else
                // stays queued for the next reconnect.
                if (SendQueue.isPermanent(x)) SendQueue.remove(me, e.clientId)
                pending[e.clientId]?.let { pending[e.clientId] = it.copy(sendState = Message.SendState.FAILED) }
                rebuild()
            } finally {
                SendQueue.end(e.clientId)
            }
        }
    }

    /** How a send from outside the chat screen ended (a notification Reply, system/NotifActionWorker). */
    enum class QueuedResult { SENT, REFUSED, LATER }

    /**
     * A queued text sent now and awaited, for a caller with no chat screen (a Reply from the
     * notification shade, with the app closed). Same writes as [sendText]; the entry was put in
     * [SendQueue] by the caller before this runs. LATER = still queued (offline, a slow key),
     * so the next start or reconnect drains it as usual.
     */
    suspend fun sendQueued(e: SendQueue.Entry): QueuedResult {
        if (!SendQueue.begin(e.clientId)) return QueuedResult.LATER   // another driver has it
        val ref = e.replyId?.let { Message.ReplyRef(it, e.replyAuthor ?: "", e.replyText ?: "") }
        return try {
            if (alreadySent(e.clientId)) { SendQueue.remove(me, e.clientId); return QueuedResult.SENT }
            turn.withLock {
                if (Wire.isGroupCid(cid)) sendGroup(e.text, e.clientId, e.ts, ref) else sendDirect(e.text, e.clientId, e.ts, ref)
            }
            SendQueue.remove(me, e.clientId)
            QueuedResult.SENT
        } catch (x: Exception) {
            if (SendQueue.isPermanent(x)) { SendQueue.remove(me, e.clientId); QueuedResult.REFUSED } else QueuedResult.LATER
        } finally {
            SendQueue.end(e.clientId)
        }
    }

    /** iOS SendQueue.alreadySent: the doc (id = clientId) in Firestore's offline store means its
     *  write is already queued there; on the server means it landed before the app died. */
    private suspend fun alreadySent(clientId: String): Boolean {
        val ref = convRef.collection("messages").document(clientId)
        try { if (ref.get(Source.CACHE).await().exists()) return true } catch (_: Exception) {}
        return try { ref.get(Source.SERVER).await().exists() } catch (_: Exception) { false }
    }

    /** Tap a failed bubble: send the same words again under a new clientId. */
    fun retry(m: Message) {
        val id = m.clientId ?: return
        if (SendQueue.isInFlight(id)) return
        SendQueue.remove(me, id)
        pending.remove(id)
        retryPhoto.remove(id)?.let { sendPreparedPhoto(it); return }
        retryVoice.remove(id)?.let { sendVoice(it); return }
        retryMedia.remove(id)?.let { work ->
            pending[id]?.let { pending[id] = it.copy(sendState = Message.SendState.SENDING) }
            rebuild()
            runMediaSend(id, work)
            return
        }
        if (m.mediaKind != null) { rebuild(); return }
        sendText(m.text)
    }

    // ------------------------------------------------------------------ media picks (attach sheet)

    /**
     * What the attach sheet hands over: photos and videos in the order picked, one caption, and
     * view-once (one photo only). One item = a photo or video message; more = albums of ten
     * (Limits.albumMaxItems), in order, the caption on the first.
     */
    fun sendPick(context: Context, uris: List<Uri>, caption: String, viewOnce: Boolean) {
        val m = media ?: return
        if (uris.isEmpty()) return
        val tap = System.currentTimeMillis().toDouble()
        val chunks = MediaRules.albumChunks(uris.take(MediaRules.MEDIA_PER_PICK))
        chunks.forEachIndexed { n, chunk ->
            val clientId = UUID.randomUUID().toString().uppercase()
            val words = if (n == 0) caption.trim() else ""
            val ts = tap + n
            val once = viewOnce && chunk.size == 1
            pending[clientId] = Message(
                id = clientId, authorId = me, text = words, type = if (chunk.size == 1) "image" else "album",
                createdAtMillis = ts, hasServerTime = false, clientTs = ts, clientId = clientId,
                sendState = Message.SendState.SENDING, viewOnce = once,
            )
            rebuild()
            runMediaSend(clientId) {
                val kinds = chunk.map { isVideoUri(context, it) }
                if (chunk.size == 1 && kinds[0]) {
                    val v = VideoPrep.fromUri(context, chunk[0], m.localDir(), clientId) ?: error("video not readable or over 64 MB")
                    val poster = File(m.localDir(), "poster-$clientId.jpg").also { it.writeBytes(v.poster.jpeg) }
                    pending[clientId]?.let { p ->
                        pending[clientId] = p.copy(type = "video", localPath = poster.absolutePath, width = v.width.toDouble(),
                            height = v.height.toDouble(), duration = v.duration, thumb = v.poster.thumbB64)
                    }
                    rebuild()
                    val work: suspend () -> Unit = { sender.video(v, words, false, clientId, ts) }
                    retryMedia[clientId] = work
                    work()
                } else if (chunk.size == 1) {
                    val prep = PhotoPrep.fromUri(context, chunk[0], com.fariin.app.media.SentQuality.photo(context)) ?: error("photo not readable")
                    val local = File(m.localDir(), "send-$clientId.jpg").also { it.writeBytes(prep.jpeg) }
                    pending[clientId]?.let { p ->
                        pending[clientId] = p.copy(localPath = local.absolutePath, width = prep.width.toDouble(),
                            height = prep.height.toDouble(), thumb = if (once) null else prep.thumbB64)
                    }
                    rebuild()
                    val work: suspend () -> Unit = { sender.image(prep, words, once, false, clientId, ts) }
                    retryMedia[clientId] = work
                    work()
                } else {
                    val parts = chunk.mapIndexed { i, u ->
                        if (kinds[i]) MediaSender.AlbumPart.Video(VideoPrep.fromUri(context, u, m.localDir(), "$clientId-$i")
                            ?: error("video not readable or over 64 MB"))
                        else MediaSender.AlbumPart.Photo(PhotoPrep.fromUri(context, u, com.fariin.app.media.SentQuality.photo(context)) ?: error("photo not readable"))
                    }
                    pending[clientId]?.let { p ->
                        pending[clientId] = p.copy(album = parts.mapIndexed { i, part ->
                            val pp = when (part) { is MediaSender.AlbumPart.Photo -> part.prep; is MediaSender.AlbumPart.Video -> part.prep.poster }
                            val f = File(m.localDir(), "tile-$clientId-$i.jpg").also { it.writeBytes(pp.jpeg) }
                            val vid = (part as? MediaSender.AlbumPart.Video)?.prep
                            Message.AlbumItem(f.absolutePath, null, pp.width.toDouble(), pp.height.toDouble(),
                                videoUrl = vid?.file?.absolutePath, duration = vid?.duration)
                        })
                    }
                    rebuild()
                    val work: suspend () -> Unit = { sender.album(parts, words, false, clientId, ts) }
                    retryMedia[clientId] = work
                    work()
                }
            }
        }
    }

    /** A document from the system file picker (iOS sendFile); a refusal is handed to [onRefused]. */
    fun sendDocument(context: Context, uri: Uri, onRefused: (String) -> Unit) {
        val m = media ?: return
        scope.launch(Dispatchers.IO) {
            val f = when (val r = FilePrep.fromUri(context, uri, m.localDir())) {
                is FilePrep.Companion.Result.Ok -> r.prep
                FilePrep.Companion.Result.TooLarge -> { onRefused("Files can be up to 25 MB."); return@launch }
                FilePrep.Companion.Result.Unreadable -> { onRefused("Couldn't read this file."); return@launch }
            }
            val tap = System.currentTimeMillis().toDouble()
            val clientId = UUID.randomUUID().toString().uppercase()
            val local = File(m.localDir(), "file-$clientId").also { it.writeBytes(f.bytes) }
            pending[clientId] = Message(
                id = clientId, authorId = me, text = "", type = "file", createdAtMillis = tap, hasServerTime = false,
                clientTs = tap, clientId = clientId, sendState = Message.SendState.SENDING,
                fileName = f.name, fileSize = f.bytes.size.toLong(), localPath = local.absolutePath,
            )
            rebuild()
            val work: suspend () -> Unit = { sender.file(f, false, clientId, tap) }
            retryMedia[clientId] = work
            runMediaSend(clientId, work)
        }
    }

    /** Runs one media send; a failure leaves a red bubble whose tap runs the same work again. */
    private fun runMediaSend(clientId: String, work: suspend () -> Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                work()
                retryMedia.remove(clientId)
            } catch (e: Exception) {
                if (!retryMedia.containsKey(clientId)) retryMedia[clientId] = work
                com.fariin.app.media.UploadProgress.done(clientId)
                pending[clientId]?.let { pending[clientId] = it.copy(sendState = Message.SendState.FAILED) }
                rebuild()
            }
        }
    }

    private fun isVideoUri(context: Context, uri: Uri): Boolean =
        (context.contentResolver.getType(uri) ?: "").startsWith("video/")

    /** A text forwarded into this chat (iOS sendText with `forwarded`), straight to the server. */
    suspend fun sendForwardedText(text: String, forwarded: Boolean) {
        val t = text.trim()
        if (t.isEmpty()) return
        val clientId = UUID.randomUUID().toString().uppercase()
        val ts = System.currentTimeMillis().toDouble()
        turn.withLock {
            if (Wire.isGroupCid(cid)) sendGroup(t, clientId, ts, null, forwarded) else sendDirect(t, clientId, ts, null, forwarded)
        }
    }

    // ------------------------------------------------------------------ media (ChatService.sendImage / sendAudio)

    /** A photo from the system picker: made ready like iOS (sendJPEG), then sent. */
    fun sendPhoto(context: Context, uri: Uri) {
        scope.launch(Dispatchers.IO) {
            val prep = PhotoPrep.fromUri(context, uri, com.fariin.app.media.SentQuality.photo(context)) ?: return@launch
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
        com.fariin.app.media.ChatSounds.sent()
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
        com.fariin.app.media.ChatSounds.sent()
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
        val seed = mutableMapOf<String, Any>("users" to listOf(me, other), "updatedAt" to FieldValue.serverTimestamp())
        // iOS sendMessage: a chat born from its first send takes the Settings default timer. Only
        // when the server says the chat is not there yet, so a timer someone chose is never undone.
        val def = Disappearing.defaultSeconds
        if (def > 0 && CryptoHub.listedConversation(cid) == null &&
            runCatching { !convRef.get(Source.SERVER).await().exists() }.getOrDefault(false)) seed["disappearSeconds"] = def
        convRef.set(seed, SetOptions.merge()).await()
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

    private suspend fun sendDirect(t: String, clientId: String, clientTs: Double, reply: Message.ReplyRef? = null,
                                   forwarded: Boolean = false, countsAsUnread: Boolean = true) {
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
        if (local == null || !(local.users.contains(me) && local.users.contains(other))) seedDirect(other)
        val batch = db.batch()
        val msg = hashMapOf<String, Any>(
            "text" to cipher,
            "authorId" to me,
            "createdAt" to FieldValue.serverTimestamp(),
            "clientTs" to clientTs,
            "clientId" to clientId,
        )
        // iOS replyEnc: {id, authorId, text sealed like the message}.
        if (reply != null) msg["replyTo"] = replyMap(reply, crypto.encryptForConversation(cid, reply.text))
        if (forwarded) msg["forwarded"] = true
        // A poll says in the clear only pollMulti / pollEndsAt (iOS ChatService); the rest stays sealed.
        msg.putAll(PollRules.clearFields(t))
        batch.set(convRef.collection("messages").document(clientId), msg)
        val conv = hashMapOf<String, Any>(
            "lastMessage" to cipher,
            "lastSender" to me,
            "updatedAt" to FieldValue.serverTimestamp(),
        )
        // iOS countsAsUnread: a pin notice is a notice, not a message, so no badge for it.
        if (countsAsUnread) conv["unreadCount.$other"] = FieldValue.increment(1)
        if (answering) conv["accepted"] = true
        batch.update(convRef, conv)
        batch.commit().await()
        com.fariin.app.media.ChatSounds.sent()
    }

    /** iOS replyEnc: {id, authorId, text sealed like the message}, plus a story quote's flags. */
    private fun replyMap(r: Message.ReplyRef, sealed: String): Map<String, Any> {
        val e = hashMapOf<String, Any>("id" to r.id, "authorId" to r.authorId, "text" to sealed)
        if (r.isStatus) e["isStatus"] = true
        if (r.isReaction) e["isReaction"] = true
        if (!r.storyThumb.isNullOrEmpty()) e["storyThumb"] = r.storyThumb   // plain story URL, not sealed
        return e
    }

    /**
     * A reply from the story viewer (iOS StoryViewer reply: ChatService.sendText with a status
     * quote). Sent straight away, not through the offline queue; a failure is thrown to the caller.
     */
    suspend fun sendStoryReply(text: String, ref: Message.ReplyRef) {
        val t = text.trim()
        if (t.isEmpty()) return
        val clientId = UUID.randomUUID().toString().uppercase()
        turn.withLock { sendDirect(t, clientId, System.currentTimeMillis().toDouble(), ref) }
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

    private suspend fun sendGroup(t: String, clientId: String, clientTs: Double, reply: Message.ReplyRef? = null,
                                  forwarded: Boolean = false, countsAsUnread: Boolean = true) {
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
        if (reply != null) msg["replyTo"] = replyMap(reply, crypto.sealForGroup(reply.text, members, cid))
        if (forwarded) msg["forwarded"] = true
        // A poll says in the clear only pollMulti / pollEndsAt (iOS ChatService); the rest stays sealed.
        msg.putAll(PollRules.clearFields(t))
        batch.set(convRef.collection("messages").document(clientId), msg)
        val conv = hashMapOf<String, Any>(
            "lastMessage" to cipher,
            "lastSender" to me,
            "updatedAt" to FieldValue.serverTimestamp(),
        )
        // Words in the clear say they are a member's (Conversation.lastPlainTag).
        if (!cipher.startsWith("enc")) conv["lastPlainTag"] = Conversation.plainTag(cipher)
        if (countsAsUnread) for (m in members) if (m != me) conv["unreadCount.$m"] = FieldValue.increment(1)
        batch.update(convRef, conv)
        batch.commit().await()
        com.fariin.app.media.ChatSounds.sent()
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
            // iOS deleteMessage -> removePinnedMessage: a pin must not outlive what it points at.
            if (m.id in PinRules.pinnedIds(_state.value.conv)) try { unpin(m.id) } catch (_: Exception) {}
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

    // ------------------------------------------------------------------ pins (iOS ChatService pins)

    /** The pinned messages by id, fetched on their own so a pin older than the loaded page still
     *  draws in the bar (iOS pinnedPreviews). A tombstone or a missing document is left out. */
    private val _pinned = MutableStateFlow<Map<String, Message>>(emptyMap())
    val pinned: StateFlow<Map<String, Message>> = _pinned.asStateFlow()
    private val pinFetching = ConcurrentHashMap.newKeySet<String>()

    private fun syncPins(ids: List<String>) {
        _pinned.update { cur -> cur.filterKeys { it in ids } }
        for (id in ids) {
            if (_pinned.value.containsKey(id) || !pinFetching.add(id)) continue
            scope.launch(Dispatchers.IO) {
                try {
                    val d = msgRef(id).get().await()
                    if (d.exists()) {
                        val m = parse(d)
                        if (!m.deleted) _pinned.update { it + (id to m) }
                    }
                } catch (_: Exception) {
                } finally { pinFetching.remove(id) }
            }
        }
    }

    /** ThreadView.togglePin's pin half: arrayUnion, then the sealed "pinned" notice (no badge).
     *  False when the server refused; null when the chat already holds [PinRules.MAX_PINS]. */
    suspend fun pin(m: Message): Boolean? {
        if (PinRules.pinnedIds(_state.value.conv).size >= PinRules.MAX_PINS) return null
        return try {
            convRef.set(mapOf("pinnedMessageIds" to FieldValue.arrayUnion(m.id)), SetOptions.merge()).await()
            try {
                val text = PinRules.markerText(m.id, PinRules.label(m))
                val clientId = UUID.randomUUID().toString().uppercase()
                val ts = System.currentTimeMillis().toDouble()
                turn.withLock {
                    if (Wire.isGroupCid(cid)) sendGroup(text, clientId, ts, countsAsUnread = false)
                    else sendDirect(text, clientId, ts, countsAsUnread = false)
                }
            } catch (_: Exception) {}
            true
        } catch (_: Exception) { false }
    }

    /** ChatService.unpin: arrayRemove (the reference app posts no unpin notice). Throws on refusal. */
    suspend fun unpin(id: String) {
        convRef.set(mapOf("pinnedMessageIds" to FieldValue.arrayRemove(id)), SetOptions.merge()).await()
    }

    /** iOS ensureLoaded: page older history until [id] is in the window (at most 12 pages). */
    suspend fun ensureLoaded(id: String): Boolean {
        repeat(12) {
            if (_state.value.messages.any { it.id == id }) return true
            if (!_state.value.canLoadOlder) return false
            loadOlder()
            // loadOlder runs on its own; wait for that page to land.
            var waited = 0
            while ((_state.value.loadingOlder || waited == 0) && waited < 100) { delay(50); waited++ }
        }
        return _state.value.messages.any { it.id == id }
    }

    /** ChatService.editHistory: the earlier versions (oldest first), opened on this phone. Throws
     *  when the document cannot be read. Each is (text, at ms; 0 when unknown). */
    suspend fun editHistory(m: Message): List<Pair<String, Double>> {
        val data = msgRef(m.id).get().await().data ?: emptyMap()
        if (data["deleted"] == true) return emptyList()
        val author = data["authorId"] as? String ?: m.authorId
        return ((data["edits"] as? List<*>) ?: emptyList<Any>()).mapNotNull { it as? Map<*, *> }.map { e ->
            val raw = e["t"] as? String ?: ""
            val clear = if (raw.isEmpty()) "" else try { crypto.decrypt(raw, cid, author) } catch (_: Exception) { "" }
            clear to ((e["at"] as? com.google.firebase.Timestamp)?.toDate()?.time?.toDouble() ?: 0.0)
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
            val snippet = (CardRules.previewWord(m.text) ?: m.bodyText).trim().take(60).takeIf { it.isNotEmpty() }
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
        // Privacy > Messages > Typing Indicators off: I send none (an "off" still goes out).
        if (on && !PrivacySettings.typingIndicators()) return
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
    fun markRead(readReceipts: Boolean = PrivacySettings.readReceipts()) {
        if (readMutex.isLocked) { readOwed = true; return }
        scope.launch { markReadNow(readReceipts) }
    }

    /** [markRead] awaited (the notification's Mark as read, with no chat screen). */
    suspend fun markReadNow(readReceipts: Boolean = PrivacySettings.readReceipts()) {
        readMutex.withLock {
            do {
                readOwed = false
                val local = CryptoHub.listedConversation(cid)
                val update = hashMapOf<String, Any>("unreadCount.$me" to 0)
                if (local?.markedUnread?.get(me) == true) update["markedUnread.$me"] = FieldValue.delete()
                try { convRef.update(update).await() } catch (_: Exception) {}
                val iBlocked = _state.value.receipts.iBlocked || blockedByMyList()
                if (readReceipts && !iBlocked) {
                    try {
                        convRef.set(mapOf("lastRead" to mapOf(me to FieldValue.serverTimestamp())), SetOptions.merge()).await()
                    } catch (_: Exception) {}
                }
            } while (readOwed)
        }
    }

    companion object {
        /** The repository of each chat on screen, so a queue drain sends through it (its bubble
         *  turns from failed back to sending) rather than through a second copy. */
        private val open = ConcurrentHashMap<String, ThreadRepository>()

        /** Sends outlive the chat screen: leaving a chat mid-send never cancels the send. */
        private val sendScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)


        /** Every queued text of [me]: through the open chat's repository, or a quiet one. */
        fun drainQueue(me: String, crypto: FariinCrypto, scope: CoroutineScope) {
            for ((cid, list) in SendQueue.all(me).groupBy { it.cid }) {
                val repo = open[cid] ?: ThreadRepository(cid, me, crypto, scope)
                for (e in list) if (!SendQueue.isInFlight(e.clientId)) repo.resend(e)
            }
        }

        /** The chat's repository on screen, or a quiet one (no listeners) for work from outside it. */
        fun openOrQuiet(cid: String, me: String, crypto: FariinCrypto, scope: CoroutineScope,
                        media: ChatMedia? = null): ThreadRepository =
            open[cid] ?: ThreadRepository(cid, me, crypto, scope, media = media)

        /** Back in front: every open chat checks its listeners and its queue. */
        fun recoverOpen() { for (r in open.values) r.recover() }

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
