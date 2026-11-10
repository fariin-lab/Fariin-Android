package com.fariin.app.data

import android.content.Context
import android.net.Uri
import com.fariin.app.crypto.EncMeta
import com.fariin.app.crypto.FariinCrypto
import com.fariin.app.media.ChatMedia
import com.fariin.app.media.MediaMessages
import com.fariin.app.media.MediaRules
import com.fariin.app.media.LocalPreview
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
        /** 1:1 only: the other person's profile is gone (iOS otherAccountDeleted, see [DeletedAccount]). */
        val otherDeleted: Boolean = false,
    )

    private val convRef = db.collection("conversations").document(cid)
    // Drawn from the first frame as it will stay (ThreadSeed): the chat list's copy of this
    // conversation (ticks, title) and the last presence seen, until the listeners answer.
    private val _state = MutableStateFlow(run {
        val seedConv = ThreadSeed.conv(cid).orEmpty()
        val p = if (Wire.isGroupCid(cid)) null else ThreadSeed.presence(Wire.otherUid(cid, me))
        State(
            receipts = if (seedConv.isEmpty()) Receipts() else Receipts.from(seedConv, me, cid), conv = seedConv,
            otherOnline = p?.online ?: false, otherLastActive = p?.lastActive,
            otherPrivacy = p?.theirPrivacy.orEmpty(), myPrivacy = p?.myPrivacy ?: Presence.myPrivacyNow().orEmpty(),
        )
    })
    private fun notePresence() {
        if (Wire.isGroupCid(cid)) return
        val s = _state.value
        ThreadSeed.notePresence(Wire.otherUid(cid, me), ThreadSeed.PresenceSeen(s.otherOnline, s.otherLastActive, s.otherPrivacy, s.myPrivacy))
    }
    val state: StateFlow<State> = _state.asStateFlow()

    private val order = ThreadOrder()
    /** Raw docs by id, so a key arriving later can re-open them. */
    private val liveDocs = LinkedHashMap<String, DocumentSnapshot>()
    private val olderDocs = LinkedHashMap<String, DocumentSnapshot>()
    /** Sends this phone has started, by clientId, until the server echo carries the same clientId. */
    private val pending = ConcurrentHashMap<String, Message>()
    /** My own media files by clientId: drawn and played locally until the server copy has its URL. */
    private val localFiles = ConcurrentHashMap<String, String>()
    /** The picked files of my photo, video or album sends by clientId, in tile order: the bubble
     *  draws them from its first frame and its server echo keeps drawing them while its own opens. */
    private val localUris = ConcurrentHashMap<String, List<String>>()
    /** What a failed media send needs to go again, by clientId. */
    private val retryPhoto = ConcurrentHashMap<String, PhotoPrep>()
    private val retryVoice = ConcurrentHashMap<String, VoiceRecorder.Result>()
    /** Video, file, album and captioned / view-once photo sends, by clientId, to go again on a tap. */
    private val retryMedia = ConcurrentHashMap<String, suspend () -> Unit>()
    /** iOS localCards: the link card on my own bubble until the server copy carries it, by clientId. */
    private val localCards = ConcurrentHashMap<String, Message.LinkPreview>()
    /** The link a text send attaches its card for once the text has landed, by clientId. */
    private val cardFor = ConcurrentHashMap<String, String>()
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

    /** This chat is a channel, as the chat list or my followed channels know it. */
    private fun isChannelChat(): Boolean = CryptoHub.listedConversation(cid)?.isChannel == true || _state.value.conv["channel"] == true ||
        com.fariin.app.channel.ChannelFollows.docs.value[cid]?.get("channel") == true

    fun start() {
        if (msgReg != null) return
        clearStuckSends()
        // The phone's copy first (iOS ThreadMessageCache), so the chat opens full, offline too;
        // the listener's first answer replaces it.
        if (liveDocs.isEmpty() && _state.value.messages.isEmpty()) {
            // A disappearing message that ran out while the chat was closed never draws (iOS
            // ThreadRepository cold open), not even for a frame.
            val now = System.currentTimeMillis().toDouble()
            val saved = LocalCache.loadThread(me, cid).filter { !HiddenMessages.isHidden(it.id) && !Disappearing.isExpired(it, now) }
            if (saved.isNotEmpty()) {
                // Owner's Pixel 2026-11-09: a first open after launch slid in empty and the rows came
                // a beat later, a second open was full at once. The saved rows now draw on the first
                // frame always (as iOS ThreadMessageCache paints before the push); rows the list knows
                // are newer arrive from the listener at the bottom, as a message arriving would.
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

    /** "Try Again" on a failed first load: the message listeners are made again, once. */
    fun reload() {
        if (_state.value.former || !msgFailed) return
        msgReg?.remove(); msgReg = null
        convReg?.remove(); convReg = null
        msgFailed = false
        _state.update { it.copy(error = null, loaded = it.messages.isNotEmpty()) }
        startListeners()
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
                    _state.update { it.copy(loaded = true, synced = true, error = LoadFailure.of(err)) }
                    return@addSnapshotListener
                }
                if (_state.value.error != null) _state.update { it.copy(error = null) }
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
            notePresence()
        }
        otherReg = users.document(other).addSnapshotListener { snap, _ ->
            if (snap != null) {
                val gone = DeletedAccount.fromProfile(snap.exists(), snap.metadata.isFromCache, _state.value.otherDeleted)
                if (gone != _state.value.otherDeleted) _state.update { it.copy(otherDeleted = gone) }
            }
            val d = snap?.data ?: return@addSnapshotListener
            MsgPolicy.record(other, d)   // their own Message Requests override (admin panel)
            _state.update { it.copy(otherPrivacy = Presence.privacyOf(d)) }
            notePresence()
        }
        myReg = users.document(me).addSnapshotListener { snap, _ ->
            val d = snap?.data ?: return@addSnapshotListener
            val p = Presence.privacyOf(d)
            Presence.noteMyPrivacy(p)
            _state.update { it.copy(myPrivacy = p) }
            notePresence()
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
            m.clientId?.let { k -> localUris[k] }?.let { picked -> m = withPicked(m, picked) }
            m.clientId?.let { k -> localCards[k]?.let { card ->
                if (m.linkPreview == null && !m.deleted) m = m.copy(linkPreview = card) else localCards.remove(k)
            } }
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
                !all.containsKey(cidKey) -> all[cidKey] = localCards[cidKey]?.let { p.copy(linkPreview = it) } ?: p
                cidKey !in unconfirmed -> pending.remove(cidKey)
            }
        }
        // While only the saved copy is on screen, a pending send joins it instead of replacing it.
        val shown = if (seeded && liveDocs.isEmpty() && olderDocs.isEmpty())
            _state.value.messages.filter { it.sendState == null && it.rowId !in all && !Disappearing.isExpired(it, now) } + all.values else all.values
        val sorted = order.sort(shown)
        _state.update { it.copy(messages = sorted, loaded = loaded ?: it.loaded) }
        // A channel never shows a deleted post: its saved copy leaves them out, so the 60 kept rows
        // reach back to real posts (VE3: the newest 60 were all deleted, every open began empty).
        if (live) LocalCache.saveThread(me, cid, if (isChannelChat()) sorted.filter { !it.deleted } else sorted)
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

    private class Parsed(val data: Map<String, Any?>, val pending: Boolean, val epoch: Int, val message: Message)
    /**
     * Each update re-built the whole page (60 to 120 messages), opening every sealed message again on
     * the main thread, twice per send (metadata too). A message whose fields, pending state and the
     * keys it was opened with are unchanged is not opened again (the reference app keeps parsed
     * messages too; speed work 2026-11-09).
     */
    private val parsed = java.util.concurrent.ConcurrentHashMap<String, Parsed>()   // pins parse on IO too

    private fun parse(d: DocumentSnapshot): Message {
        val data = d.data ?: emptyMap()
        val pending = d.metadata.hasPendingWrites()
        val epoch = CryptoHub.keysEpoch.value
        parsed[d.id]?.let { p -> if (p.pending == pending && p.epoch == epoch && p.data == data) return p.message }
        val m0 = Message.fromFirestore(d.id, data, cid) { raw, author -> crypto.decrypt(raw, cid, author) }
        val m = if (pending && m0.authorId == me) m0.copy(sendState = Message.SendState.SENDING) else m0
        if (parsed.size > 400) parsed.clear()
        parsed[d.id] = Parsed(data, pending, epoch, m)
        return m
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
        // owner 2026-11-09: with Message Requests OFF for the other person (admin panel), or a chat
        // whose first message went while they were off (`reqOff`), this is an ordinary chat:
        // no "waiting to accept" bar (iOS MessageRequests.stance).
        if (conv.reqOff || !MsgPolicy.requestsOn(Wire.otherUid(conv.id, me))) return null
        return when {
            conv.lastSender.isEmpty() && conv.startedBy == me -> null
            conv.lastSender.isEmpty() -> "Message requests are not on Android yet."
            conv.lastSender == me -> "Waiting for them to accept your message."
            else -> null   // they wrote first: my reply accepts it
        }
    }

    fun sendText(text: String) = sendText(text, null)

    /** A text, optionally a reply ([reply] is the quoted message; its words are sealed again). */
    fun sendText(text: String, reply: Message?) = sendText(text, reply, emptyList())

    /** [mentions]: the uids @-mentioned in a group (MentionRules.forSend), written in the clear. */
    fun sendText(text: String, reply: Message?, mentions: List<String>) = sendText(text, reply, mentions, null)

    /** [fmt]: a channel post's bold runs as wire JSON (TextFormat) for text.trim(); null = none. */
    fun sendText(text: String, reply: Message?, mentions: List<String>, fmt: String?) {
        val t = text.trim()
        if (t.isEmpty()) return
        // iOS ThreadView.send: one bubble holds 2,500 (owner 2026-10-29); longer text goes out as
        // several messages, in order, each one waiting for the one before. The reply, the
        // @-mentions and the link card ride the first (a mention pings once, not once per bubble).
        // The bold does not survive the split (iOS): the runs were made for the whole text.
        val parts = MessageLength.splitForBubbles(t)
        if (parts.size > 1) {
            var before: kotlinx.coroutines.Job? = null
            parts.forEachIndexed { i, p ->
                before = sendOne(p, if (i == 0) reply else null, if (i == 0) mentions else emptyList(), before, card = i == 0)
            }
            return
        }
        sendOne(t, reply, mentions, null, fmt = fmt)
    }

    private var lastTap = 0.0

    /** One bubble's text; [after] = the send it must follow (a long text's earlier bubble). */
    private fun sendOne(t: String, reply: Message?, mentions: List<String>, after: kotlinx.coroutines.Job?, card: Boolean = true,
                        fmt: String? = null): kotlinx.coroutines.Job? {
        // A card is quoted by its noun (iOS safeText), never by its raw marker.
        val ref = reply?.let { Message.ReplyRef(it.id, it.authorId, (CardRules.previewWord(it.text) ?: it.bodyText).take(200)) }
        // Never the same millisecond as the bubble before (a long text's parts keep their order on screen).
        val tap = maxOf(System.currentTimeMillis().toDouble(), lastTap + 1).also { lastTap = it }
        val clientId = UUID.randomUUID().toString().uppercase()
        val e = SendQueue.Entry(clientId, cid, t, tap, ref?.id, ref?.authorId, ref?.text, mentions,
            fmt = fmt?.takeIf { Wire.isGroupCid(cid) })
        // The link card follows the text (iOS attachLinkPreview): one built while typing shows on
        // my bubble at once; otherwise it is fetched for up to 6 s beside the send.
        if (card) LinkPreviews.cardUrlFor(t, LinkPreviews.enabled(), LinkPreviews.takeSuppressed(cid))
            ?.takeIf { LinkPreviews.allowedIn(CryptoHub.listedConversation(cid), me) }?.let { u ->
                cardFor[clientId] = u
                LinkPreviews.cached(u)?.let { showLocalCard(clientId, it) }
            }
        // On disk BEFORE the network (iOS SendQueue.add), so a kill or an outage cannot lose it;
        // written by the send's own coroutine, off the main thread (see drive).
        return drive(e, checkFirst = false, sendScope, save = true, after = after)
    }

    private fun showLocalCard(clientId: String, d: LinkPreviews.Draft) {
        if (localCards.containsKey(clientId)) return
        val path = d.jpeg?.let { j -> media?.let { runCatching { File(it.localDir(), "lp-$clientId.jpg").apply { writeBytes(j) }.path }.getOrNull() } }
        localCards[clientId] = Message.LinkPreview(d.url, d.title, d.desc, path)
        rebuild()
    }

    /** After the text landed: the card fetched (or taken from the composer), sealed and added. */
    private suspend fun attachCard(clientId: String, url: String) {
        val d = LinkPreviews.draftWithin(url, 6_000)
        val ok = d != null && run {
            showLocalCard(clientId, d)
            LinkPreviews.attach(convRef, cid, me, crypto, clientId, d, if (Wire.isGroupCid(cid)) freshMembers() else null)
        }
        if (!ok && localCards.remove(clientId) != null) rebuild()
    }

    /** A queued text from an earlier run or a reconnect: sent again unless the server (or
     *  Firestore's own offline queue) already has it. */
    fun resend(e: SendQueue.Entry) = drive(e, checkFirst = true)

    private fun drive(e: SendQueue.Entry, checkFirst: Boolean) = drive(e, checkFirst, sendScope)

    /**
     * [save]: a new send, put in [SendQueue] first. That write seals the file with the phone's
     * keystore and was on the main thread at the tap: part of the frames lost right as the bubble
     * should start moving. It now runs here, still before any network write.
     */
    private fun drive(e: SendQueue.Entry, checkFirst: Boolean, on: CoroutineScope, save: Boolean = false,
                      after: kotlinx.coroutines.Job? = null): kotlinx.coroutines.Job? {
        if (!SendQueue.begin(e.clientId)) return null
        val ref = e.replyId?.let { Message.ReplyRef(it, e.replyAuthor ?: "", e.replyText ?: "") }
        // My bubble style read ONCE, at the tap: the bubble on screen and the message sent carry the
        // same one even if the style is changed while this one is still going up.
        val style = styleAtSend.getOrPut(e.clientId) { BubbleStyles.forSending(cid).let { it to BubbleStyles.decorForSending(it) } }
        pending[e.clientId] = Message(
            id = e.clientId, authorId = me, text = e.text, createdAtMillis = e.ts, hasServerTime = false,
            clientTs = e.ts, clientId = e.clientId, sendState = Message.SendState.SENDING, replyTo = ref,
            mentions = e.mentions, bubbleStyle = style.first, bubbleDecor = style.second,   // from the first frame
            bold = TextFormat.parse(e.fmt, e.text.length),
        )
        rebuild()
        return on.launch {
            try {
                if (save) SendQueue.add(me, e)
                // A long text's later bubble goes up only after the one before it (landed or
                // failed); it is on disk first, like any send.
                after?.join()
                if (checkFirst && alreadySent(e.clientId)) { SendQueue.remove(me, e.clientId); return@launch }
                turn.withLock {
                    if (Wire.isGroupCid(cid)) sendGroup(e.text, e.clientId, e.ts, ref, mentions = e.mentions, fmt = e.fmt) else sendDirect(e.text, e.clientId, e.ts, ref)
                }
                SendQueue.remove(me, e.clientId)
                styleAtSend.remove(e.clientId)
                cardFor.remove(e.clientId)?.let { u -> on.launch { attachCard(e.clientId, u) } }
            } catch (x: Exception) {
                cardFor.remove(e.clientId)
                localCards.remove(e.clientId)
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
                if (Wire.isGroupCid(cid)) sendGroup(e.text, e.clientId, e.ts, ref, mentions = e.mentions, fmt = e.fmt) else sendDirect(e.text, e.clientId, e.ts, ref)
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
        // iOS deliver: a retry keeps the queued entry's mentions.
        sendText(m.text, null, m.mentions)
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
        val picked = uris.take(MediaRules.MEDIA_PER_PICK)
        // iOS adds the pending photo, video or album with its local pictures at once. The shapes
        // are read here, off the main thread, BEFORE the bubbles go in: each bubble is inserted at
        // its final size (the send glide measures it then) and never jumps when the prep is done.
        scope.launch(Dispatchers.IO) {
            val kinds = picked.map { isVideoUri(context, it) }
            val shapes = picked.mapIndexed { i, u ->
                async { if (kinds[i]) LocalPreview.videoShape(context, u) else LocalPreview.photoShape(context, u) }
            }.awaitAll()
            val chunks = MediaRules.albumChunks(picked.indices.toList())
            // The pictures themselves, decoded small in the background; the bubbles wait a moment
            // for them so the first frame already shows the photo, never longer than that.
            val lone = chunks.size == 1 && chunks[0].size == 1
            val warm = chunks.flatMap { c -> c.map { i ->
                async {
                    if (viewOnce && c.size == 1) return@async null
                    LocalPreview.load(context, picked[i].toString(), kinds[i],
                        if (c.size == 1) LocalPreview.LONE_SIDE else LocalPreview.TILE_SIDE)
                }
            } }
            kotlinx.coroutines.withTimeoutOrNull(if (lone) 400L else 250L) { warm.awaitAll() }
            chunks.forEachIndexed { n, idx ->
                val chunk = idx.map { picked[it] }
                val clientId = UUID.randomUUID().toString().uppercase()
                val words = if (n == 0) caption.trim() else ""
                val ts = tap + n
                val once = viewOnce && chunk.size == 1
                val first = shapes[idx[0]]
                // A lone video is a video bubble from the first frame (owner 2026-11-04: it showed as a
                // photo, then turned into a video while sending).
                pending[clientId] = if (chunk.size == 1) Message(
                    id = clientId, authorId = me, text = words, type = if (kinds[idx[0]]) "video" else "image",
                    createdAtMillis = ts, hasServerTime = false, clientTs = ts, clientId = clientId,
                    sendState = Message.SendState.SENDING, viewOnce = once,
                    width = first?.width?.toDouble(), height = first?.height?.toDouble(), duration = first?.duration,
                    localUri = if (once) null else chunk[0].toString(),
                ) else Message(
                    id = clientId, authorId = me, text = words, type = "album",
                    createdAtMillis = ts, hasServerTime = false, clientTs = ts, clientId = clientId,
                    sendState = Message.SendState.SENDING,
                    album = idx.map { i ->
                        Message.AlbumItem("", null, shapes[i]?.width?.toDouble(), shapes[i]?.height?.toDouble(),
                            duration = shapes[i]?.duration, localUri = picked[i].toString(), localVideo = kinds[i])
                    },
                )
                if (!once) localUris[clientId] = chunk.map { it.toString() }
                rebuild()
                val chunkKinds = idx.map { kinds[it] }
                runMediaSend(clientId) {
                    if (chunk.size == 1 && chunkKinds[0]) {
                        val v = VideoPrep.fromUri(context, chunk[0], m.localDir(), clientId) ?: error("video not readable or over 64 MB")
                        val poster = File(m.localDir(), "poster-$clientId.jpg").also { it.writeBytes(v.poster.jpeg) }
                        // The server's copy keeps drawing this poster until its own has loaded (no blur step).
                        localFiles[clientId] = poster.absolutePath
                        pending[clientId]?.let { p ->
                            pending[clientId] = p.copy(type = "video", localPath = poster.absolutePath, width = v.width.toDouble(),
                                height = v.height.toDouble(), duration = v.duration, thumb = v.poster.thumbB64)
                        }
                        rebuild()
                        val work: suspend () -> Unit = { sender.video(v, words, false, clientId, ts) }
                        retryMedia[clientId] = work
                        work()
                    } else if (chunk.size == 1) {
                        val prep = PhotoPrep.fromUri(context, chunk[0], com.fariin.app.media.SentQuality.photo(context, chunk[0])) ?: error("photo not readable")
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
                            if (chunkKinds[i]) MediaSender.AlbumPart.Video(VideoPrep.fromUri(context, u, m.localDir(), "$clientId-$i")
                                ?: error("video not readable or over 64 MB"))
                            else MediaSender.AlbumPart.Photo(PhotoPrep.fromUri(context, u, com.fariin.app.media.SentQuality.photo(context, u)) ?: error("photo not readable"))
                        }
                        pending[clientId]?.let { p ->
                            pending[clientId] = p.copy(album = parts.mapIndexed { i, part ->
                                val pp = when (part) { is MediaSender.AlbumPart.Photo -> part.prep; is MediaSender.AlbumPart.Video -> part.prep.poster }
                                val f = File(m.localDir(), "tile-$clientId-$i.jpg").also { it.writeBytes(pp.jpeg) }
                                val vid = (part as? MediaSender.AlbumPart.Video)?.prep
                                // The tile keeps drawing its picked picture (no swap to the prepared copy).
                                Message.AlbumItem(f.absolutePath, null, pp.width.toDouble(), pp.height.toDouble(),
                                    videoUrl = vid?.file?.absolutePath, duration = vid?.duration,
                                    localUri = p.album.getOrNull(i)?.localUri, localVideo = chunkKinds[i])
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

    /**
     * PHOTOS SENT AS FILES (Add files > Photos, iOS f46637b3, owner 2026-11-08): each photo is its own
     * file message with the stored bytes as they are (ContentResolver, no decode or re-encode, the
     * original name). The bubbles land in tick order, and the sends go one after another so the
     * server keeps that order. A photo that cannot be read is skipped; one line gives the count.
     */
    fun sendDocuments(context: Context, uris: List<Uri>, onRefused: (String) -> Unit) {
        val m = media ?: return
        if (uris.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            val base = System.currentTimeMillis().toDouble()
            val queued = ArrayList<Pair<String, suspend () -> Unit>>()
            var unreadable = 0
            var tooLarge = 0
            uris.forEachIndexed { i, uri ->
                val f = when (val r = FilePrep.fromUri(context, uri, m.localDir())) {
                    is FilePrep.Companion.Result.Ok -> r.prep
                    FilePrep.Companion.Result.TooLarge -> { tooLarge++; return@forEachIndexed }
                    FilePrep.Companion.Result.Unreadable -> { unreadable++; return@forEachIndexed }
                }
                // One millisecond apart, so the bubbles sort in tick order.
                val tap = base + i
                val clientId = UUID.randomUUID().toString().uppercase()
                val local = File(m.localDir(), "file-$clientId").also { it.writeBytes(f.bytes) }
                pending[clientId] = Message(
                    id = clientId, authorId = me, text = "", type = "file", createdAtMillis = tap, hasServerTime = false,
                    clientTs = tap, clientId = clientId, sendState = Message.SendState.SENDING,
                    fileName = f.name, fileSize = f.bytes.size.toLong(), localPath = local.absolutePath,
                )
                // The bytes are read back from this phone's copy when the send starts, so twenty
                // photos never sit in memory at once.
                val name = f.name
                val preview = f.previewJpeg
                val work: suspend () -> Unit = { sender.file(FilePrep(name, local.readBytes(), preview), false, clientId, tap) }
                retryMedia[clientId] = work
                queued += clientId to work
                rebuild()
            }
            val skipped = listOfNotNull(
                unreadable.takeIf { it > 0 }?.let { n -> if (n == 1) "1 photo couldn't be read, so it was skipped." else "$n photos couldn't be read, so they were skipped." },
                tooLarge.takeIf { it > 0 }?.let { n -> if (n == 1) "1 photo is over 25 MB, so it was skipped." else "$n photos are over 25 MB, so they were skipped." },
            )
            if (skipped.isNotEmpty()) onRefused(skipped.joinToString(" "))
            // One after another: each send's message is written before the next one starts.
            for ((clientId, work) in queued) runMediaSend(clientId, work).join()
        }
    }

    /** Runs one media send; a failure leaves a red bubble whose tap runs the same work again. */
    private fun runMediaSend(clientId: String, work: suspend () -> Unit): Job {
        // On the app's scope, not the chat's: leaving the chat right after Send cancelled the chat's
        // scope between the upload and the attach, so the clip landed in Storage but the message
        // stayed "uploading" forever on the other phone (iOS item 24, 2026-11-09). Only the
        // person's own Cancel (MediaSend.cancel, which cancels this job) stops it now.
        val job = SendScope.launch(Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.LAZY) {
            // A try again starts its rings over (a failed send's were settled).
            com.fariin.app.media.UploadProgress.reset(clientId)
            try {
                work()
                retryMedia.remove(clientId)
                com.fariin.app.media.MediaSend.clearItems(clientId)
                com.fariin.app.media.MediaSend.finish(clientId)
            } catch (e: Exception) {
                if (mediaSendEnded(clientId, e)) return@launch
                if (!retryMedia.containsKey(clientId)) retryMedia[clientId] = work
                com.fariin.app.media.UploadProgress.done(clientId)
                pending[clientId]?.let { pending[clientId] = it.copy(sendState = Message.SendState.FAILED) }
                rebuild()
            }
        }
        com.fariin.app.media.MediaSend.register(clientId, job)
        job.start()
        return job
    }

    /**
     * A media send threw: was it the person's Cancel? Then a document it wrote after the Cancel
     * looked is taken down too, and the bubble goes quietly instead of turning red (iOS
     * runRegisteredSend). Forgets the send either way.
     */
    private fun mediaSendEnded(clientId: String, e: Exception): Boolean {
        val send = com.fariin.app.media.MediaSend
        val cancelled = send.wasCancelled(clientId) || e is kotlinx.coroutines.CancellationException
        if (cancelled) send.announcedId(clientId)?.let { id -> SendScope.launch(Dispatchers.IO) { cancelAnnounced(id) } }
        send.finish(clientId)
        if (!cancelled) return false
        com.fariin.app.media.UploadProgress.done(clientId)
        retryMedia.remove(clientId); retryPhoto.remove(clientId)
        pending.remove(clientId)
        rebuild()
        return true
    }

    /**
     * The ring's tap on my own photo, video or album going up (iOS cancelMediaSend): the upload
     * stops, the bubble goes, and a message document the send already wrote is deleted, so it does
     * not spin for ever on both phones. A row already back from the server counts only while it is
     * still marked `uploading`, so a delivered photo is never taken down.
     */
    fun cancelMediaSend(m: Message) {
        val clientId = m.clientId ?: return
        val send = com.fariin.app.media.MediaSend
        send.cancel(clientId)
        val announced = send.announcedId(clientId)
            ?: m.id.takeIf { m.sendState == Message.SendState.SENDING && it != clientId }
            ?: m.id.takeIf { m.uploading && m.authorId == me && it != clientId }
        if (announced != null) scope.launch(Dispatchers.IO) { cancelAnnounced(announced) }
        com.fariin.app.media.UploadProgress.done(clientId)
        retryMedia.remove(clientId); retryPhoto.remove(clientId)
        send.clearItems(clientId)
        pending.remove(clientId)
        rebuild()
    }

    /**
     * The X on one tile of my album going up (iOS cancelItem, owner 2026-09-29): that item stops
     * and leaves the bubble at once, the album goes without it. The last one left is the whole send.
     */
    fun cancelAlbumItem(m: Message, index: Int) {
        val clientId = m.clientId ?: return
        val send = com.fariin.app.media.MediaSend
        val left = m.album.indices.filter { it != index && !send.isItemCancelled(send.itemKey(clientId, it)) }
        if (left.isEmpty()) { cancelMediaSend(m); return }
        send.cancelItem(send.itemKey(clientId, index))
        // The echo will hold only the items left: its picked pictures must line up with them.
        localUris[clientId]?.let { uris -> if (uris.size == m.album.size) localUris[clientId] = left.map { uris[it] } }
        rebuild()
    }

    /**
     * iOS ThreadView.clearStuckSends: my own messages still `uploading` 30 minutes after they were
     * made never got their file (the send died before the attach, item 24), so the other side would
     * spin forever. They are taken down. Younger ones are left for a send that may still be running.
     */
    private fun clearStuckSends() {
        SendScope.launch(Dispatchers.IO) {
            val cutoff = System.currentTimeMillis() - 30 * 60 * 1000L
            val snap = runCatching {
                convRef.collection("messages").whereEqualTo("authorId", me).whereEqualTo("uploading", true).get().await()
            }.getOrNull() ?: return@launch
            for (d in snap.documents) {
                val made = d.getTimestamp("createdAt")?.toDate()?.time ?: continue
                if (made < cutoff) cancelAnnounced(d.id)
            }
        }
    }

    /** iOS cancelAnnounced: the document goes and the chat list line is rewritten from what is left. */
    private suspend fun cancelAnnounced(messageId: String) {
        try { msgRef(messageId).delete().await() } catch (_: Exception) {}
        rewriteSummaryAfterDelete()
    }

    /** The server's copy of my own photo, video or album, still drawing the picked pictures. */
    private fun withPicked(m: Message, picked: List<String>): Message = when {
        m.deleted || m.viewOnce -> m
        m.type == "album" && m.album.size == picked.size ->
            m.copy(album = m.album.mapIndexed { i, a -> a.copy(localUri = picked[i]) })
        (m.type == "image" || m.type == "video") && picked.size == 1 -> m.copy(localUri = picked[0])
        else -> m
    }

    private fun isVideoUri(context: Context, uri: Uri): Boolean =
        (context.contentResolver.getType(uri) ?: "").startsWith("video/")

    /** A text forwarded into this chat (iOS sendText with `forwarded`), straight to the server. */
    suspend fun sendForwardedText(text: String, forwarded: Boolean, fwdChannelJson: String? = null,
                                  fwdSource: Message.ForwardSource? = null) {
        val t = text.trim()
        if (t.isEmpty()) return
        val clientId = UUID.randomUUID().toString().uppercase()
        val ts = System.currentTimeMillis().toDouble()
        turn.withLock {
            if (Wire.isGroupCid(cid)) sendGroup(t, clientId, ts, null, forwarded, fwdChannelJson = fwdChannelJson, fwdSource = fwdSource)
            else sendDirect(t, clientId, ts, null, forwarded, fwdChannelJson = fwdChannelJson, fwdSource = fwdSource)
        }
    }

    /**
     * My words with a quoted channel post (iOS ThreadView.sendQuote): the bubble shows at once with
     * the card (seeded when the quote was made), then the message goes out carrying only the quote's
     * id. Sent straight away, not through the offline queue: a send that fails takes the bubble back
     * and [onFailed] puts the quote and the words back in the composer, rather than leaving a Resend
     * that would go without its quote.
     */
    fun sendQuote(text: String, qid: String, mentions: List<String>, onFailed: () -> Unit, fmt: String? = null) {
        if (!QuoteRules.isQid(qid)) return
        val t = text.trim()
        val clientId = UUID.randomUUID().toString().uppercase()
        val ts = System.currentTimeMillis().toDouble()
        pending[clientId] = Message(
            id = clientId, authorId = me, text = t, createdAtMillis = ts, hasServerTime = false,
            clientTs = ts, clientId = clientId, sendState = Message.SendState.SENDING, mentions = mentions, quoteId = qid,
            bold = TextFormat.parse(fmt, t.length),
        )
        rebuild()
        sendScope.launch {
            try {
                turn.withLock {
                    if (Wire.isGroupCid(cid)) sendGroup(t, clientId, ts, mentions = mentions, quote = qid, fmt = fmt)
                    else sendDirect(t, clientId, ts, quote = qid)
                }
            } catch (_: Exception) {
                pending.remove(clientId)
                rebuild()
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onFailed() }
            }
        }
    }

    // ------------------------------------------------------------------ stickers and GIFs

    /**
     * A built-in sticker (iOS StickerSend.write): pack + file + emoji + animated, 512 x 512, its
     * public copy in `imageUrl`; nothing uploaded. The sealed `text` is what an older build shows
     * (the emoji, else the old-build marker). The chat list says "😂 Sticker".
     */
    fun sendSticker(s: StickerRef, reply: Message?) {
        if (s.packId == StickerFavorites.MINE) { sendMineSticker(s, reply); return }
        val tap = System.currentTimeMillis().toDouble()
        val clientId = UUID.randomUUID().toString().uppercase()
        val ref = reply?.let { Message.ReplyRef(it.id, it.authorId, (CardRules.previewWord(it.text) ?: it.bodyText).take(200)) }
        pending[clientId] = Message(
            id = clientId, authorId = me, text = "", type = "sticker", createdAtMillis = tap, hasServerTime = false,
            clientTs = tap, clientId = clientId, sendState = Message.SendState.SENDING, replyTo = ref,
            width = 512.0, height = 512.0, stickerPack = s.packId, stickerId = s.stickerId,
            stickerEmoji = s.emoji, stickerAnimated = s.animated,
            imageUrl = StickerGifRules.stickerPublicUrl(s.packId, s.stickerId),
        )
        rebuild()
        runMediaSend(clientId) {
            val fields = hashMapOf<String, Any>(
                "type" to "sticker", "stickerPack" to s.packId, "stickerId" to s.stickerId,
                "animated" to s.animated, "width" to 512.0, "height" to 512.0,
                "imageUrl" to StickerGifRules.stickerPublicUrl(s.packId, s.stickerId),
            )
            if (s.emoji.isNotEmpty()) fields["emoji"] = s.emoji
            writeLooseMessage(fields, s.emoji.ifEmpty { StickerGifRules.OLD_BUILD_MARKER },
                StickerGifRules.stickerLabel(s.emoji), ref, clientId, tap)
        }
    }

    /**
     * A sticker I made (iOS StickerSend.write, source .mine): the transparent PNG is sealed on the
     * photo path and never re-encoded (JPEG would drop the alpha). The message goes first with
     * `uploading`, the file lands on it after; a failed upload takes the message down again. Each
     * send uploads its own copy: chat files are named after their message and deleted with it.
     */
    private fun sendMineSticker(s: StickerRef, reply: Message?) {
        val file = StickerFavorites.png(s.stickerId) ?: return
        val tap = System.currentTimeMillis().toDouble()
        val clientId = UUID.randomUUID().toString().uppercase()
        val ref = reply?.let { Message.ReplyRef(it.id, it.authorId, (CardRules.previewWord(it.text) ?: it.bodyText).take(200)) }
        val png = file.readBytes()
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(png, 0, png.size, opts)
        val w = opts.outWidth.coerceAtLeast(1).toDouble(); val h = opts.outHeight.coerceAtLeast(1).toDouble()
        pending[clientId] = Message(
            id = clientId, authorId = me, text = "", type = "sticker", createdAtMillis = tap, hasServerTime = false,
            clientTs = tap, clientId = clientId, sendState = Message.SendState.SENDING, replyTo = ref,
            width = w, height = h, stickerPack = s.packId, stickerId = s.stickerId, stickerEmoji = s.emoji,
            stickerAnimated = false, localPath = file.absolutePath,
        )
        rebuild()
        runMediaSend(clientId) {
            val members = if (Wire.isGroupCid(cid)) freshMembers() else null
            val fields = hashMapOf<String, Any>(
                "type" to "sticker", "stickerPack" to s.packId, "stickerId" to s.stickerId,
                "animated" to false, "width" to w, "height" to h, "uploading" to true,
            )
            if (s.emoji.isNotEmpty()) fields["emoji"] = s.emoji
            writeLooseMessage(fields, StickerGifRules.OLD_BUILD_MARKER, StickerGifRules.stickerLabel(s.emoji), ref, clientId, tap)
            val msg = convRef.collection("messages").document(clientId)
            try {
                val (cipher, meta) = if (members != null) crypto.sealBytesForGroup(png, members, cid) else crypto.encryptBytes(cid, png)
                val sref = com.google.firebase.storage.FirebaseStorage.getInstance().reference
                    .child(com.fariin.app.media.MediaRules.imagePath(cid, clientId))
                sref.putBytes(cipher, com.google.firebase.storage.StorageMetadata.Builder()
                    .setContentType("application/octet-stream").build()).await()
                val url = sref.downloadUrl.await().toString()
                msg.update(mapOf("imageUrl" to url, "enc" to meta.asMap(), "uploading" to false)).await()
            } catch (e: Exception) {
                runCatching { msg.delete().await() }
                throw e
            }
        }
    }

    /** iOS ChatService.sendGif: the public GIPHY link and its size, `text` empty; the list says "GIF". */
    fun sendGif(g: StickerGifRules.Gif, reply: Message?) {
        val tap = System.currentTimeMillis().toDouble()
        val clientId = UUID.randomUUID().toString().uppercase()
        val ref = reply?.let { Message.ReplyRef(it.id, it.authorId, (CardRules.previewWord(it.text) ?: it.bodyText).take(200)) }
        pending[clientId] = Message(
            id = clientId, authorId = me, text = "", type = "gif", createdAtMillis = tap, hasServerTime = false,
            clientTs = tap, clientId = clientId, sendState = Message.SendState.SENDING, replyTo = ref,
            imageUrl = g.url, width = g.width, height = g.height,
        )
        rebuild()
        runMediaSend(clientId) {
            writeLooseMessage(hashMapOf("type" to "gif", "imageUrl" to g.url, "width" to g.width, "height" to g.height),
                null, "GIF", ref, clientId, tap)
        }
    }

    /** One message whose words are not the preview (a sticker, a GIF): the fields, the sealed
     *  [text] (null = empty), the clear [label] for the chat list, the badge for everyone else. */
    private suspend fun writeLooseMessage(fields: Map<String, Any>, text: String?, label: String,
                                          reply: Message.ReplyRef?, clientId: String, clientTs: Double) {
        val members = if (Wire.isGroupCid(cid)) freshMembers() else null
        val other = Wire.otherUid(cid, me)
        if (members == null) seedDirect(other)
        val msg = HashMap<String, Any>(fields)
        msg["text"] = text?.let { seal(it, members) } ?: ""
        msg["authorId"] = me
        msg["createdAt"] = FieldValue.serverTimestamp()
        msg["clientTs"] = clientTs
        msg["clientId"] = clientId
        if (reply != null) msg["replyTo"] = replyMap(reply, seal(reply.text, members))
        val batch = db.batch()
        batch.set(convRef.collection("messages").document(clientId), msg)
        val conv = hashMapOf<String, Any>("lastMessage" to label, "lastSender" to me, "updatedAt" to FieldValue.serverTimestamp())
        for (u in members ?: listOf(other)) if (u != me) conv["unreadCount.$u"] = FieldValue.increment(1)
        batch.update(convRef, conv)
        batch.commit().await()
        com.fariin.app.media.ChatSounds.sent()
    }

    // ------------------------------------------------------------------ media (ChatService.sendImage / sendAudio)

    /** A photo from the system picker: made ready like iOS (sendJPEG), then sent. */
    fun sendPhoto(context: Context, uri: Uri) {
        scope.launch(Dispatchers.IO) {
            val prep = PhotoPrep.fromUri(context, uri, com.fariin.app.media.SentQuality.photo(context, uri)) ?: return@launch
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
        val job = scope.launch(Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                photoNow(prep, clientId, tap)
                com.fariin.app.media.MediaSend.finish(clientId)
            } catch (e: Exception) {
                if (mediaSendEnded(clientId, e)) return@launch
                retryPhoto[clientId] = prep
                pending[clientId]?.let { pending[clientId] = it.copy(sendState = Message.SendState.FAILED) }
                rebuild()
            }
        }
        com.fariin.app.media.MediaSend.register(clientId, job)
        job.start()
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
            duration = r.duration, waveform = if (r.viewOnce) emptyList() else r.waveform, localPath = r.file.absolutePath,
            viewOnce = r.viewOnce,
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
        // The sound goes up FIRST, the message after (owner 2026-11-06, the long-standing "voice note
        // keeps loading"): written first, the message reached the other phone before its file, which
        // then showed a loading note until (or unless) the upload finished. Now they only ever get a
        // note that plays; here it shows the clock (the pending copy) while the file goes up. The
        // Storage rule checks chat membership from the path, not the message, so this is allowed.
        val path = MediaRules.audioPath(cid, msgRef.id)
        var url: String? = null
        var attempt = 0
        while (url == null) {
            try { url = upload(cipher, path) } catch (e: Exception) {
                if (++attempt >= 6) throw e
                delay(minOf(60_000L, 2_000L shl attempt))
            }
        }
        // A one-time note keeps no plain copy here (iOS: the sender cannot replay it either).
        if (!r.viewOnce) media?.store(url, data)
        val fields = MediaMessages.audio(me, meta, r.duration, r.waveform, clientTs, clientId, FieldValue.serverTimestamp(),
            viewOnce = r.viewOnce).toMutableMap().apply { remove("uploading"); put("audioUrl", url) }
        val batch = db.batch()
        batch.set(msgRef, fields)
        batch.update(convRef, MediaMessages.convUpdate(me, com.fariin.app.media.VoiceRecordRules.preview(r.duration, r.viewOnce), members ?: listOf(other),
            FieldValue.serverTimestamp(), FieldValue.increment(1)))
        batch.commit().await()
        com.fariin.app.media.ChatSounds.sent()
        if (r.viewOnce) r.file.delete()
    }

    private suspend fun sealBytes(data: ByteArray, members: List<String>?): Pair<ByteArray, EncMeta> =
        if (members != null) crypto.sealBytesForGroup(data, members, cid) else crypto.encryptBytes(cid, data)

    private suspend fun seal(text: String, members: List<String>?): String =
        if (members != null) crypto.sealForGroup(text, members, cid) else crypto.encryptForConversation(cid, text)

    /** iOS sendImage / sendAudio always make sure the 1:1 doc has both of us and a time. */
    private suspend fun seedDirect(other: String, reqOff: Boolean = false) {
        val seed = mutableMapOf<String, Any>("users" to listOf(me, other), "updatedAt" to FieldValue.serverTimestamp())
        // iOS sendMessage: a chat born from its first send takes the Settings default timer. Only
        // when the server says the chat is not there yet, so a timer someone chose is never undone.
        val def = Disappearing.defaultSeconds
        if (def > 0 && CryptoHub.listedConversation(cid) == null &&
            runCatching { !convRef.get(Source.SERVER).await().exists() }.getOrDefault(false)) seed["disappearSeconds"] = def
        if (!reqOff) { convRef.set(seed, SetOptions.merge()).await(); return }
        try {
            convRef.set(seed + ("reqOff" to true), SetOptions.merge()).await()
        } catch (e: com.google.firebase.firestore.FirebaseFirestoreException) {
            // The stamp alone was refused (the switch came back on, or they wrote first): the
            // message still goes, as an ordinary first message (iOS).
            if (e.code != com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) throw e
            convRef.set(seed, SetOptions.merge()).await()
        }
    }

    /** End-to-end encrypted bytes go up as application/octet-stream (storage.rules `chat/`). */
    private suspend fun upload(cipher: ByteArray, path: String): String {
        require(cipher.size < MediaRules.STORAGE_CHAT_LIMIT) { "file too large" }
        val ref = FirebaseStorage.getInstance().reference.child(path)
        val task = ref.putBytes(cipher, StorageMetadata.Builder().setContentType("application/octet-stream").build())
        // Cancel stops the transfer itself, not only the wait for it.
        try { task.await() } catch (e: kotlinx.coroutines.CancellationException) { task.cancel(); throw e }
        return ref.downloadUrl.await().toString()
    }

    /** iOS freshGroupMembers: the member list from the SERVER; the cached one when offline. */
    private suspend fun freshMembers(): List<String> = try {
        val snap = convRef.get(Source.SERVER).await()
        if (snap.getBoolean("e2ee") == false) CryptoHub.notePlainGroup(cid)
        (snap.get("users") as? List<*>)?.filterIsInstance<String>()
    } catch (_: Exception) { null } ?: memberUids()

    /** clientId -> (bs, bsd) as read when the text was sent; dropped once it is on the server. */
    private val styleAtSend = java.util.concurrent.ConcurrentHashMap<String, Pair<String?, Boolean>>()

    /**
     * My bubble style on a text I send (iOS ChatService): plain `bs`, and `bsd` for a colour style's
     * decorations. The one read at the tap when there is one, else the current one.
     */
    private fun putBubbleStyle(msg: HashMap<String, Any>, clientId: String) {
        val (bs, bsd) = styleAtSend[clientId] ?: BubbleStyles.forSending(cid).let { it to BubbleStyles.decorForSending(it) }
        if (bs == null) return
        msg["bs"] = bs
        if (bsd) msg["bsd"] = true
    }

    private suspend fun sendDirect(t: String, clientId: String, clientTs: Double, reply: Message.ReplyRef? = null,
                                   forwarded: Boolean = false, countsAsUnread: Boolean = true,
                                   storyMention: Message.StoryMentionRef? = null, fwdChannelJson: String? = null,
                                   quote: String? = null, fwdSource: Message.ForwardSource? = null) {
        val other = Wire.otherUid(cid, me)
        val cipher = crypto.encryptForConversation(cid, t)
        // A quote sent with no words of mine: the list says "Quoted post" (sealed like any preview), not a blank line.
        val lastCipher = if (t.isEmpty() && quote != null) crypto.encryptForConversation(cid, QUOTED_POST) else cipher
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
        // iOS: before a first message, read the live switch once, since the rules read it.
        val firstMessage = knocking || local == null
        if (firstMessage) MsgPolicy.confirm(other)
        val requestsOff = !MsgPolicy.requestsOn(other)
        // A first message sent while requests are OFF for them stays an ordinary chat if they come
        // back on: stamped `reqOff` with the seed (rules reqOffStampOk), as iOS.
        val stampReqOff = knocking && requestsOff && local?.reqOff != true
        if (knocking && !requestsOff) {
            require(t.codePointCount(0, t.length) <= FIRST_MESSAGE_LIMIT) { "a first message is at most $FIRST_MESSAGE_LIMIT characters" }
            require(reply == null) { "a first message is plain text" }
            countKnock()
        }
        // The seed, only when this phone's list does not already show both of us in it (iOS
        // `seedNeeded`; a brand-new chat is never opened from the Android list yet).
        if (stampReqOff || local == null || !(local.users.contains(me) && local.users.contains(other))) seedDirect(other, stampReqOff)
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
        // A story mention card (iOS ChatService: the plain story id and thumbnail).
        // The channel a forwarded post came from, sealed like the text (iOS sealedFwdChannel).
        fwdChannelJson?.let { j -> runCatching { crypto.encryptForConversation(cid, j) }.getOrNull()?.let { msg["fwdChannel"] = it } }
        // The group or channel it came from (iOS sealedForwardSource); a failed seal drops only this.
        if (forwarded) fwdSource?.let { s -> runCatching { crypto.encryptForConversation(cid, s.json()) }.getOrNull()?.let { msg["fwd"] = it } }
        if (storyMention != null) msg["storyMention"] = mapOf("id" to storyMention.id, "thumb" to storyMention.thumbUrl)
        // A quoted channel post: only the server snapshot's id, in the clear (iOS ChatService).
        if (quote != null) msg["quote"] = mapOf("qid" to quote)
        if (countsAsUnread && storyMention == null) putBubbleStyle(msg, clientId)
        // A poll says in the clear only pollMulti / pollEndsAt (iOS ChatService); the rest stays sealed.
        msg.putAll(PollRules.clearFields(t))
        batch.set(convRef.collection("messages").document(clientId), msg)
        val conv = hashMapOf<String, Any>(
            "lastMessage" to lastCipher,
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

    /** iOS StoryMentions: the mention message, its id fixed per story and person. */
    suspend fun sendStoryMention(text: String, clientId: String, ref: Message.StoryMentionRef) {
        turn.withLock { sendDirect(text, clientId, System.currentTimeMillis().toDouble(), storyMention = ref) }
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
                                  forwarded: Boolean = false, countsAsUnread: Boolean = true,
                                  mentions: List<String> = emptyList(), fwdChannelJson: String? = null,
                                  quote: String? = null, fmt: String? = null, fwdSource: Message.ForwardSource? = null) {
        // iOS freshGroupMembers: a member removed elsewhere is not wrapped for.
        val members = freshMembers()
        val cipher = crypto.sealForGroup(t, members, cid)
        val lastCipher = if (t.isEmpty() && quote != null) crypto.sealForGroup(QUOTED_POST, members, cid) else cipher
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
        // iOS sendGroupText: the uids in the clear, for the server's "mentioned you" push.
        if (mentions.isNotEmpty()) msg["mentions"] = mentions
        if (forwarded) msg["forwarded"] = true
        fwdChannelJson?.let { j -> runCatching { crypto.sealForGroup(j, members, cid) }.getOrNull()?.let { msg["fwdChannel"] = it } }
        // Where it came from (iOS sealedForwardSource): a plain group's seal hands the JSON back, so the map goes.
        if (forwarded) fwdSource?.let { s ->
            val j = s.json()
            runCatching { crypto.sealForGroup(j, members, cid) }.getOrNull()?.let { msg["fwd"] = if (it == j) s.map() else it }
        }
        if (quote != null) msg["quote"] = mapOf("qid" to quote)   // a quoted channel post (iOS sendGroupText)
        // Bold runs (TextFormat), sealed like the text: plain in a plain channel.
        if (fmt != null && t.isNotEmpty()) msg["fmt"] = crypto.sealForGroup(fmt, members, cid)
        if (countsAsUnread && CryptoHub.listedConversation(cid)?.isChannel != true) putBubbleStyle(msg, clientId)
        // A poll says in the clear only pollMulti / pollEndsAt (iOS ChatService); the rest stays sealed.
        msg.putAll(PollRules.clearFields(t))
        batch.set(convRef.collection("messages").document(clientId), msg)
        val conv = hashMapOf<String, Any>(
            "lastMessage" to lastCipher,
            "lastSender" to me,
            "updatedAt" to FieldValue.serverTimestamp(),
        )
        // Words in the clear say they are a member's (Conversation.lastPlainTag).
        if (!lastCipher.startsWith("enc")) conv["lastPlainTag"] = Conversation.plainTag(lastCipher)
        if (countsAsUnread) for (m in members) if (m != me) conv["unreadCount.$m"] = FieldValue.increment(1)
        batch.update(convRef, conv)
        batch.commit().await()
        com.fariin.app.media.ChatSounds.sent()
    }

    // ------------------------------------------------------------------ message actions (iOS ChatService)

    private fun msgRef(id: String) = convRef.collection("messages").document(id)

    /** This chat is a channel: the group doc says so (read once from the server when not loaded yet). */
    private suspend fun isChannel(): Boolean {
        if (!Wire.isGroupCid(cid)) return false
        (_state.value.conv["channel"] as? Boolean)?.let { return it }
        return try { convRef.get().await().getBoolean("channel") == true } catch (_: Exception) { false }
    }

    /** ChatService.editMessage: re-sealed, the replaced text appended to `edits` (at most 10). */
    suspend fun editMessage(m: Message, newText: String, fmt: String? = null): Boolean {
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
            // The bold goes with the new words (iOS); an edit that leaves none takes the old runs off.
            if (fmt != null && members != null) update["fmt"] = seal(fmt, members)
            else if (data.containsKey("fmt")) update["fmt"] = FieldValue.delete()
            // Not in a channel post (owner 2026-11-06): every follower reads the post, so the old
            // versions stay off it; the server keeps them for the owner and admins.
            if (size() <= 700_000 && !isChannel()) update["edits"] = edits
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
        // A channel's preview is the server's (CHANNEL-SYNC-SPEC 3-4, iOS serverOwnsPreview; audit A7).
        if (isChannelChat() || isChannel()) return
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
            "sticker" -> StickerGifRules.stickerLabel(d["emoji"] as? String)
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

    /**
     * iOS MessageSearch.loadChat: the newest 1000 messages read and opened on this phone for the
     * in-chat search. Not kept anywhere. Skipped like the thread skips them: view-once, deleted,
     * hidden for me, sent while I had them blocked, nothing to read.
     * Off the main thread: it is called from the chat's composition, and opening up to 1000
     * messages there froze the screen while the search keyboard was coming up (owner 2026-11-08,
     * "Search on the profile glitches").
     */
    suspend fun searchCorpus(limit: Int = ChatSearch.CORPUS_LIMIT): List<ChatSearch.Item> = try {
        kotlinx.coroutines.withContext(Dispatchers.Default) { readCorpus(limit) }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { emptyList() }

    private suspend fun readCorpus(limit: Int): List<ChatSearch.Item> {
        val snap = convRef.collection("messages")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(limit.toLong())
            .get().await()
        return snap.documents.mapNotNull { d ->
            val m = Message.fromFirestore(d.id, d.data ?: emptyMap(), cid) { raw, author -> crypto.decrypt(raw, cid, author) }
            when {
                HiddenMessages.isHidden(m.id) -> null
                m.authorId != me && BlockList.hides(m.authorId, m.createdAtMillis) -> null
                m.text == Message.NOT_ENCRYPTED -> null
                else -> ChatSearch.searchText(m)?.let { ChatSearch.Item(m.id, it, m.authorId, m.createdAtMillis) }
            }
        }
    }

    /** iOS ensureLoaded: page older history until [id] is in the window (at most [maxPages] pages). */
    suspend fun ensureLoaded(id: String, maxPages: Int = 12): Boolean {
        repeat(maxPages) {
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
        // A channel's history is on the server, for its owner and admins only (owner 2026-11-06):
        // getChannelEdits {cid, mid} -> {versions:[{t, at ms}]}; anyone else is refused.
        if (isChannel()) {
            val r = com.google.firebase.functions.FirebaseFunctions.getInstance(AuthRepository.FUNCTIONS_REGION)
                .getHttpsCallable("getChannelEdits").call(mapOf("cid" to cid, "mid" to m.id)).await().getData() as? Map<*, *>
            return ((r?.get("versions") as? List<*>) ?: emptyList<Any>()).mapNotNull { it as? Map<*, *> }.map { v ->
                val raw = v["t"] as? String ?: ""
                val clear = if (raw.isEmpty()) "" else try { crypto.decrypt(raw, cid, author) } catch (_: Exception) { "" }
                clear to ((v["at"] as? Number)?.toDouble() ?: 0.0)
            }
        }
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
        // Only a group's members may write its doc (a channel visitor never does).
        if (Wire.isGroupCid(cid) && me !in ((_state.value.conv["users"] as? List<*>) ?: emptyList<Any>())) return
        scope.launch {
            try {
                val v = MessageRules.typingValue(on, System.currentTimeMillis())
                convRef.set(mapOf("typing" to mapOf(me to v)), SetOptions.merge()).await()
            } catch (_: Exception) {}
        }
    }

    /**
     * iOS setRecording: while my mic captures a voice note, `typing.<me>` = "audio-<seconds>",
     * re-sent every 10 s by the composer (receivers drop it after 15 s); false when it stops. Same
     * setting and membership checks as typing.
     */
    fun setRecording(on: Boolean) {
        if (on && !PrivacySettings.typingIndicators()) return
        if (Wire.isGroupCid(cid) && me !in ((_state.value.conv["users"] as? List<*>) ?: emptyList<Any>())) return
        scope.launch {
            try {
                val v = MessageRules.recordingValue(on, System.currentTimeMillis())
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
        /** The list's line for a quote sent without words of mine. */
        const val QUOTED_POST = "Quoted post"

        /** The repository of each chat on screen, so a queue drain sends through it (its bubble
         *  turns from failed back to sending) rather than through a second copy. */
        private val open = ConcurrentHashMap<String, ThreadRepository>()

        /** True while a chat is on screen (ChatHistoryPreloader leaves warming until it closes). */
        fun anyOpen(): Boolean = open.isNotEmpty()

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
            "fwdChannel", "fwd", "thumb", "edits", "editedAt", "quote", "bs", "bsd", "fmt")   // bs/bsd: the rules refuse a tombstone keeping a bubble style (iOS b189891b)
    }
}

/** Sends that must outlive the chat screen (media uploads and their attach): the app's lifetime. */
internal val SendScope: CoroutineScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
