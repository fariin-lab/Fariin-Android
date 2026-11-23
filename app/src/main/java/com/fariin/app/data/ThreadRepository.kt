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
import kotlinx.coroutines.flow.drop
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
    private val db: FirebaseFirestore = com.fariin.app.Fb.db,
    /** The chat's files (download, open, cache); null where media is not wired (tests). */
    val media: ChatMedia? = null,
) {
    init { Message.myUid = me }

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
        /** Fariin Plus bubble styles (users/{uid}.plusBubble), theirs and mine, from these same listeners. */
        val otherPlusBubble: PlusBubble? = null,
        val myPlusBubble: PlusBubble? = null,
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
    // The message listener answers on a background thread (busy groups, 2026-11-15) while older
    // pages answer on main: every change and every walk of these two maps holds [docsLock].
    private val liveDocs = LinkedHashMap<String, DocumentSnapshot>()
    private val olderDocs = LinkedHashMap<String, DocumentSnapshot>()
    private val docsLock = Any()
    /** Busy groups: listener answers redraw at most once per interval (150 ms, 250 ms on a LOW phone). */
    private val msgGate = PublishGate(BusyChatRules.publishIntervalMs(com.fariin.app.perf.DeviceClass.peek()))
    private val convGate = PublishGate(BusyChatRules.publishIntervalMs(com.fariin.app.perf.DeviceClass.peek()))
    /** Bumped by each message listener answer; [rebuild] notes the value it drew, so a timed redraw
     *  that a direct rebuild already covered is skipped. */
    @Volatile private var docsSeq = 0L
    @Volatile private var builtSeq = 0L
    @Volatile private var owedSynced = false
    @Volatile private var firstMsgAnswer = true
    @Volatile private var firstConvAnswer = true
    @Volatile private var latestConv: DocumentSnapshot? = null
    /** The screen closed: a timed redraw still queued on the listener thread does nothing. (Not
     *  msgReg: the first answer can reach the listener thread before msgReg is assigned.) */
    @Volatile private var closed = false
    /** Older messages drawn from the phone's own history before the server's page lands (phase 1). */
    private val dbOlder = java.util.concurrent.ConcurrentHashMap<String, Message>()
    /** One listener per older page, so an edit, delete or reaction on an older message shows at once. */
    private val olderRegs = ArrayList<ListenerRegistration>()
    /** The ids each older page last held, to drop a message the server deleted from that page. */
    private val olderPageIds = ArrayList<Set<String>>()

    /** H-86: a listener over rows that slid out of the live window (a time span), so an edit or a
     *  delete there still reaches the open chat. Kept oldest first, under [docsLock]. */
    private class SlidChunk(val lo: com.google.firebase.Timestamp, val hi: com.google.firebase.Timestamp, val count: Int) {
        var reg: ListenerRegistration? = null
        @Volatile var ids: Set<String> = emptySet()
    }
    private val slidChunks = ArrayList<SlidChunk>()
    /** Rows that slid out and no listener covers yet, by id, with their time (under [docsLock]). */
    private val slidWaiting = HashMap<String, com.google.firebase.Timestamp>()
    @Volatile private var slidScheduled = false
    /** Sends this phone has started, by clientId, until the server echo carries the same clientId. */
    private val pending = ConcurrentHashMap<String, Message>()
    private var echoWatch: kotlinx.coroutines.Job? = null
    /** My own media files by clientId: drawn and played locally until the server copy has its URL. */
    private val localFiles = ConcurrentHashMap<String, String>()
    /** The picked files of my photo, video or album sends by clientId, in tile order: the bubble
     *  draws them from its first frame and its server echo keeps drawing them while its own opens. */
    private val localUris = ConcurrentHashMap<String, List<String>>()
    /** What a failed media send needs to go again, by clientId. */
    private val retryPhoto = ConcurrentHashMap<String, PhotoPrep>()
    private val retryVoice = ConcurrentHashMap<String, VoiceRecorder.Result>()
    /** The reply a voice note was recorded under (iOS sendAudio replyTo), by clientId. */
    private val voiceReplies = ConcurrentHashMap<String, Message.ReplyRef>()
    /** Video, file, album and captioned / view-once photo sends, by clientId, to go again on a tap. */
    private val retryMedia = ConcurrentHashMap<String, suspend () -> Unit>()
    /**
     * Media sends of mine that failed after their message was written (a playlist whose songs did not
     * go up, say), by clientId. The server's copy still says `uploading`, so it is drawn failed instead
     * of spinning for ever: the red mark with Resend and Delete, as a failed send before its write.
     */
    private val failedMedia: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** H-28: half-sent video messages of mine being taken down (doc ids): never drawn, so the
     *  failed copy on this phone shows in their place until the delete lands. */
    private val takenDown: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val processStartMs = ThreadRepositoryStart.ms
    /** File, song and playlist sends, by clientId: the kinds whose Resend picks up where it stopped (MediaSender.PlaylistUpload, FileUpload). */
    private val playlistSends: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** iOS localCards: the link card on my own bubble until the server copy carries it, by clientId. */
    private val localCards = ConcurrentHashMap<String, Message.LinkPreview>()
    /** The link a text send attaches its card for once the text has landed, by clientId. */
    private val cardFor = ConcurrentHashMap<String, String>()
    /** The media sends (iOS ChatService media paths), see media/MediaSender. */
    private val sender: MediaSender by lazy { MediaSender(cid, me, crypto, media, db) }
    @Volatile private var msgReg: ListenerRegistration? = null
    @Volatile private var convReg: ListenerRegistration? = null
    private var presenceReg: ListenerRegistration? = null
    private var otherReg: ListenerRegistration? = null
    private var myReg: ListenerRegistration? = null

    /** True while the screen shows the phone's saved copy and no live answer has come yet. */
    @Volatile private var seeded = false
    /** Messages are only cached once the listener has really answered. */
    @Volatile private var live = false
    /** The live window's last answer came from the server (not the phone's cache): only then may
     *  this chat vouch for what it filed (safety review 2026-11-12, both apps). */
    @Volatile private var windowFromServer = false
    /** The live window's last server answer was short: it holds the whole chat (H-87). */
    @Volatile private var windowWhole = false
    /** An older page was answered from the cache once: older pages no longer count as one unbroken run. */
    @Volatile private var olderFromCache = false
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
            // The phone's own history first (device-storage plan phase 1), the old sealed copy
            // only until the database has this chat.
            val fromDb = ThreadRowsPrefetch.take(me, cid)
                ?: com.fariin.app.local.LocalDb.newest(me, cid, com.fariin.app.local.LocalCache.THREAD_CAP)
            val saved = (fromDb.ifEmpty { LocalCache.loadThread(me, cid) })
                .filter { !HiddenMessages.isHidden(it.id) && !Disappearing.isExpired(it, now) }
            // A forward just sent here is in the first frame too, under the saved rows.
            val echoes = ForwardEchoes.of(cid).filter { e -> saved.none { it.rowId == e.rowId } }
            savedRows = saved.associate { it.id to it.createdAtMillis.toLong() }
            // The "messages from before you signed in" line as the last open drew it (owner's Oppo
            // 2026-11-20: it came a beat after the chat opened, once the server answered).
            val keysLine = KeysNoticeMemo.get(me, cid)?.takeIf { (at, _) -> saved.any { it.createdAtMillis <= at } || saved.isEmpty() }
                ?.let { (at, text) -> Message(id = KEYS_NOTICE_ID, authorId = "", text = text, type = "system", createdAtMillis = at, hasServerTime = true) }
            if (saved.isNotEmpty() || echoes.isNotEmpty()) {
                // Owner's Pixel 2026-11-09: a first open after launch slid in empty and the rows came
                // a beat later, a second open was full at once. The saved rows now draw on the first
                // frame always (as iOS ThreadMessageCache paints before the push); rows the list knows
                // are newer arrive from the listener at the bottom, as a message arriving would.
                if (saved.isNotEmpty()) seeded = true
                _state.update { it.copy(messages = order.sort(saved + listOfNotNull(keysLine) + echoes), loaded = saved.isNotEmpty() || it.loaded) }
            }
        }
        // A forward that fails here takes its bubble away; one that lands is swapped in by rebuild.
        if (echoWatch == null) echoWatch = scope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) {
            var last = ForwardEchoes.version.value
            ForwardEchoes.version.collect { v -> if (v != last) { last = v; if (liveDocs.isNotEmpty() || olderDocs.isNotEmpty()) rebuild() } }
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
        resendAll(SendQueue.pending(me, cid))
        // ...and photos the app was closed on (PhotoOutbox): their bubbles, and the send if none runs.
        resumeOutbox()
        startListeners()
        startChannelSync()
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
        resendAll(SendQueue.pending(me, cid).filter { !it.refused && !SendQueue.isInFlight(it.clientId) })
        chSync?.reattach()
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

    /** The saved rows the chat opened with (id to time), until the server's first answer checks them. */
    @Volatile private var savedRows: Map<String, Long> = emptyMap()

    /**
     * Saved rows inside the newest page's span that the server no longer has (a message removed
     * there, a send that never landed) leave the phone's copy (owner's Pixel 2026-11-17: a removed
     * forward stayed in the saved copy of "believe" and drew on every open, then went). The same
     * check the older pages already make.
     */
    private fun dropRemovedSaved(snap: com.google.firebase.firestore.QuerySnapshot) {
        val rows = savedRows
        if (rows.isEmpty()) return
        savedRows = emptyMap()
        val ids = snap.documents.mapTo(HashSet()) { it.id }
        // A short page is the whole chat; a full one covers from its oldest row up.
        val lo = if (snap.size() < PAGE_SIZE) Long.MIN_VALUE
            else snap.documents.mapNotNull { it.ts("createdAt")?.toDate()?.time }.minOrNull() ?: return
        val gone = rows.filter { (id, at) -> at >= lo && id !in ids }.keys
        if (gone.isNotEmpty()) com.fariin.app.local.LocalDb.forget(me, cid, gone)
    }

    private fun startListeners() {
        if (msgReg != null) return
        closed = false
        firstMsgAnswer = true
        firstConvAnswer = true
        msgReg = convRef.collection("messages")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(PAGE_SIZE)
            // Metadata too: a media message's last write (attach the file URL, drop `uploading`)
            // changes no value the server adds, so without this the "sending" mark never clears.
            // Answered on the background thread: parse, open and sort happen there, the screen
            // only gets the finished list (a busy group froze cheap phones on main).
            .addSnapshotListener(listenExec, com.google.firebase.firestore.MetadataChanges.INCLUDE) { snap, err ->
                if (snap == null) {
                    msgFailed = true
                    com.fariin.app.CrashReports.refusal(err, "chat:" + cid.takeLast(6))
                    _state.update { it.copy(loaded = true, synced = true, error = LoadFailure.of(err)) }
                    return@addSnapshotListener
                }
                if (_state.value.error != null) _state.update { it.copy(error = null) }
                // Never blank an open thread on an empty offline snapshot (iOS), nor the saved copy.
                if (snap.metadata.isFromCache && snap.isEmpty && (synchronized(docsLock) { liveDocs.isNotEmpty() } || seeded)) return@addSnapshotListener
                // The first answer and any change to my own message draw at once (no added send
                // latency); everything else is folded into one redraw per interval.
                val urgent = firstMsgAnswer || snap.documentChanges.any { it.document.str("authorId") == me }
                firstMsgAnswer = false
                if (!snap.metadata.isFromCache) dropRemovedSaved(snap)
                seeded = false
                live = true
                windowFromServer = !snap.metadata.isFromCache
                windowWhole = !snap.metadata.isFromCache && snap.size() < PAGE_SIZE
                val cut = synchronized(docsLock) {
                    val cut = keepSlidOut(snap.documents, full = snap.size() >= PAGE_SIZE)
                    liveDocs.clear()
                    for (d in snap.documents) liveDocs[d.id] = d
                    docsSeq++
                    cut
                }
                if (cut) _state.update { it.copy(canLoadOlder = true, loadingOlder = false) }
                if (!snap.metadata.isFromCache && snap.size() < PAGE_SIZE) _state.update { it.copy(canLoadOlder = false) }
                if (!snap.metadata.isFromCache) owedSynced = true
                askRedraw(urgent)
            }
        convReg = convRef.addSnapshotListener(listenExec) { snap, err ->
            // Busy groups: every member's lastRead, unreadCount and typing write lands here. The
            // newest copy is kept and handled at most once per interval; the first one at once.
            if (snap != null) {
                latestConv = snap
                val wait = convGate.ask(System.currentTimeMillis(), urgent = firstConvAnswer) ?: return@addSnapshotListener
                firstConvAnswer = false
                if (wait == 0L) handleConv() else listenExec.schedule({ handleConv() }, wait, java.util.concurrent.TimeUnit.MILLISECONDS)
                return@addSnapshotListener
            }
            onConv(null, err)
        }
        if (!Wire.isGroupCid(cid)) attachPresence(Wire.otherUid(cid, me))
        // A block or unblock made while the chat is open re-filters what shows. The list as it is now
        // is skipped (the listener's first answer applies it), and a later change rebuilds off the main
        // thread: this rebuild used to run inside the opening frame, waiting on the listener's lock.
        scope.launch(Dispatchers.Default) { BlockList.state.drop(1).collect { if (msgReg != null) rebuild() } }
        // Keys in the background, then re-open what drew as "…" (iOS keyPreloadTask).
        // Only when something new came: every open bumped the epoch, and every chat row under the
        // sliding chat watches it, so the whole list rebuilt mid-slide (cheap phone trace 2026-11-16).
        scope.launch(Dispatchers.IO) {
            val wasReady = crypto.isReady
            try { crypto.ensureReady() } catch (_: Exception) {}
            val others = memberUids().filter { it != me }
            val missing = others.filterNot(crypto::hasKey)
            crypto.preloadKeys(others)
            if (!wasReady && crypto.isReady || missing.any(crypto::hasKey)) {
                CryptoHub.bumpKeys()
                rebuild()
            }
        }
    }

    /** One redraw from listener answers: at once when [urgent], else folded per [msgGate] (listener thread only). */
    private fun askRedraw(urgent: Boolean) {
        val wait = msgGate.ask(System.currentTimeMillis(), urgent) ?: return
        if (wait == 0L) redrawFromListener() else listenExec.schedule({ redrawFromListener() }, wait, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    private fun redrawFromListener() {
        msgGate.ran(System.currentTimeMillis())
        // A group that ended (or that I left) before its first page was drawn still draws that page
        // once: the chat doc's answer can come first and make it former, and the page then stayed
        // on grey placeholder bubbles for good.
        if (closed || (_state.value.former && _state.value.loaded)) return
        if (docsSeq != builtSeq || !_state.value.loaded) rebuild(loaded = true)
        if (owedSynced) {
            owedSynced = false
            if (!_state.value.synced) _state.update { it.copy(synced = true) }
        }
    }

    private fun handleConv() {
        convGate.ran(System.currentTimeMillis())
        if (closed || _state.value.former) return
        onConv(latestConv ?: return, null)
    }

    /** The conversation document's answer (listener thread). */
    private fun onConv(snap: DocumentSnapshot?, err: Exception?) {
        run {
            // Removed from the group (or left on another phone): the rules refuse a non-member's
            // read, so the listener ends refused. Asked once more, as iOS confirmRemovedFromGroup.
            if (snap == null) {
                if (Wire.isGroupCid(cid) && (err as? com.google.firebase.firestore.FirebaseFirestoreException)?.code ==
                    com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) confirmRemoved()
                return@run
            }
            val data = snap.data
            if (data == null) {
                if (Wire.isGroupCid(cid) && !snap.metadata.isFromCache) confirmRemoved()
                return@run
            }
            if (me in ((data["users"] as? List<*>) ?: emptyList<Any>())) wasMember = true
            if (wasMember && !snap.metadata.isFromCache && FormerGroups.isFormer(cid, data, me)) { becomeFormer(data); return@run }
            val startBefore = if (Wire.isGroupCid(cid)) GroupManage.historyStart(_state.value.conv, me) else null
            val clearedBefore = clearedAtOf(_state.value.conv)
            val receipts = Receipts.from(data, me, cid)
            // Only the chat list's summary moved (a send, the other side's counts, my own typing):
            // the open chat keeps its copy, so the whole chat does not rebuild for it.
            if (ThreadConvRules.onlySummaryChanged(_state.value.conv, data, me, group = Wire.isGroupCid(cid))) {
                if (receipts != _state.value.receipts) _state.update { it.copy(receipts = receipts) }
            } else _state.update { it.copy(receipts = receipts, conv = data) }
            // Chat History for new members changed (or my join time arrived): the list is cut again.
            if (Wire.isGroupCid(cid) && msgReg != null && GroupManage.historyStart(data, me) != startBefore) rebuild()
            // My Clear Chat / Delete for Me moved (here or on my other device): cut the chat again.
            else if (msgReg != null && clearedAtOf(data) != clearedBefore) rebuild()
            if (data["e2ee"] == false) CryptoHub.notePlainGroup(cid)
            syncPins(PinRules.pinnedIds(data))
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
            val ob = PlusBubble.parse(d["plusBubble"])
            AvatarVideoIndex.recordRaw(other, d["avatarVideo"])   // Plus animatedAvatar: the header moves
            _state.update { it.copy(otherPrivacy = Presence.privacyOf(d).also { p -> com.fariin.app.call.CallPrivacyIndex.record(other, p) }, otherPlusBubble = ob) }
            notePresence()
        }
        myReg = users.document(me).addSnapshotListener { snap, _ ->
            val d = snap?.data ?: return@addSnapshotListener
            val p = Presence.privacyOf(d)
            Presence.noteMyPrivacy(p)
            val mb = PlusBubble.parse(d["plusBubble"])
            AvatarVideoIndex.recordRaw(me, d["avatarVideo"])
            PlusBubbles.record(mb)
            _state.update { it.copy(myPrivacy = p, myPlusBubble = mb) }
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
        stopOlder()
        _state.update { it.copy(conv = stripped, former = true) }
        // Nothing drawn yet (the chat doc answered before the messages): what is here is drawn (the
        // window if it came, else the phone's saved rows), never the placeholder bubbles for good.
        if (!_state.value.loaded) {
            if (synchronized(docsLock) { liveDocs.isNotEmpty() }) rebuild(loaded = true)
            else _state.update { it.copy(loaded = true, synced = true) }
        }
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
        closed = true
        backJob?.cancel(); backJob = null
        open.remove(cid, this)
        presenceReg?.remove(); presenceReg = null
        otherReg?.remove(); otherReg = null
        myReg?.remove(); myReg = null
        msgReg?.remove(); msgReg = null
        convReg?.remove(); convReg = null
        stopOlder()
        burnJob?.cancel(); burnJob = null
    }

    private suspend fun memberUids(): List<String> {
        if (!Wire.isGroupCid(cid)) return listOf(Wire.otherUid(cid, me))
        CryptoHub.listedConversation(cid)?.users?.takeIf { it.isNotEmpty() }?.let { return it }
        return try {
            (convRef.get().await().get("users") as? List<*>)?.filterIsInstance<String>() ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    /**
     * The live window is the newest [PAGE_SIZE]; each new message pushes its oldest one out. A message
     * that left by sliding (older than everything the window holds now) moves to [olderDocs] instead
     * of vanishing from the open screen. One that left while newer than the window's oldest was
     * deleted, and goes.
     */
    private fun keepSlidOut(now: List<DocumentSnapshot>, full: Boolean): Boolean {
        if (!full || liveDocs.isEmpty()) return false
        val ids = now.mapTo(HashSet()) { it.id }
        // H-27: nothing in common with the window on screen = maybe a hole in between. What was
        // held goes, and older pages start again from the new window (WindowGapRules).
        if (WindowGapRules.cutOff(liveDocs.keys, ids, full)) {
            stopOlder()
            olderDocs.clear()
            dbOlder.clear()
            olderFromCache = false
            return true
        }
        val floor = now.mapNotNull { it.tsEstimate("createdAt") }.minOrNull() ?: return false
        for ((id, d) in liveDocs) {
            if (id in ids) continue
            val at = d.tsEstimate("createdAt") ?: continue
            if (at <= floor) {
                olderDocs[id] = d
                if (olderPageIds.none { id in it } && slidChunks.none { id in it.ids }) slidWaiting[id] = at
            }
        }
        // H-86: a listener for them, a few seconds later so a burst of messages makes one.
        if (slidWaiting.isNotEmpty() && !slidScheduled) {
            slidScheduled = true
            listenExec.schedule({ attachSlid() }, 4, java.util.concurrent.TimeUnit.SECONDS)
        }
        return false
    }

    /**
     * H-86: one listener over the rows waiting in [slidWaiting]. Two neighbour listeners merge into
     * one when the older holds no more rows than the newer (a binary counter): few listeners, and
     * each row is read again only a few times however long the chat stays open.
     */
    private fun attachSlid() {
        val gone = ArrayList<ListenerRegistration>()
        val c = synchronized(docsLock) {
            slidScheduled = false
            if (closed || slidWaiting.isEmpty()) return
            var lo = slidWaiting.values.minOrNull() ?: return
            val hi = slidWaiting.values.maxOrNull() ?: return
            var count = slidWaiting.size
            val ids = HashSet(slidWaiting.keys)
            slidWaiting.clear()
            while (slidChunks.isNotEmpty() && slidChunks.last().count <= count) {
                val prev = slidChunks.removeAt(slidChunks.size - 1)
                prev.reg?.let { gone.add(it) }
                lo = minOf(lo, prev.lo); count += prev.count; ids.addAll(prev.ids)
            }
            SlidChunk(lo, hi, count).also { it.ids = ids; slidChunks.add(it) }
        }
        val reg = convRef.collection("messages")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .startAt(c.hi).endAt(c.lo)
            .addSnapshotListener(listenExec) { snap, _ ->
                if (snap == null || closed) return@addSnapshotListener
                val ids = snap.documents.mapTo(HashSet()) { it.id }
                synchronized(docsLock) {
                    if (slidChunks.none { it === c }) return@addSnapshotListener
                    for (d in snap.documents) if (!liveDocs.containsKey(d.id)) olderDocs[d.id] = d
                    // Gone from the server (only the server's answer says so): gone from the screen,
                    // unless another page or listener still holds it.
                    if (!snap.metadata.isFromCache) for (id in c.ids) {
                        if (id in ids || liveDocs.containsKey(id)) continue
                        if (olderPageIds.none { id in it } && slidChunks.none { it !== c && id in it.ids }) olderDocs.remove(id)
                    }
                    if (!snap.metadata.isFromCache || ids.containsAll(c.ids)) c.ids = ids
                    docsSeq++
                }
                rebuild()
            }
        val keep = synchronized(docsLock) { if (slidChunks.any { it === c }) { c.reg = reg; true } else false }
        if (!keep) reg.remove()
        gone.forEach { it.remove() }
    }

    private fun stopOlder() {
        val regs = synchronized(docsLock) {
            val slid = slidChunks.mapNotNull { it.reg }
            slidChunks.clear(); slidWaiting.clear()
            olderRegs.toList().also { olderRegs.clear(); olderPageIds.clear() } + slid
        }
        regs.forEach { it.remove() }
    }

    /**
     * iOS `loadOlder`: the page before the oldest held, by value (`start(at:)`), deduped by id. The page
     * stays listened to while the chat is open, so changes to older messages reach the screen.
     */
    fun loadOlder() {
        val s = _state.value
        if (!s.canLoadOlder || s.loadingOlder || !s.loaded) return
        val oldest = synchronized(docsLock) { (liveDocs.values + olderDocs.values).toList() }.mapNotNull { it.ts("createdAt") }.minOrNull() ?: return
        // The phone's own history (device-storage plan phase 1, same rule as iOS): used for an
        // older page only when the server cannot answer (offline, or nothing in 8 s). Online the
        // server stays the authority, so a message deleted for everyone never comes back from a
        // stale copy before phase 2's events exist.
        fun fromDb() {
            val oldestHeldMs = minOf(oldest.toDate().time, dbOlder.values.minOfOrNull { it.createdAtMillis.toLong() } ?: Long.MAX_VALUE)
            val rows = com.fariin.app.local.LocalDb.older(me, cid, oldestHeldMs, PAGE_SIZE.toInt())
            if (rows.isEmpty()) return
            for (m in rows) if (!HiddenMessages.isHidden(m.id)) dbOlder[m.id] = m
            rebuild()
        }
        _state.update { it.copy(loadingOlder = true) }
        val page = synchronized(docsLock) { olderPageIds.add(emptySet()); olderPageIds.size - 1 }
        // Touched only on the listener thread (the answers and the 8 s give-up both run there).
        var first = true
        // Answered on the listener thread like the newest page: this answer used to parse, open and
        // rebuild the whole chat on the main thread, and wait there for the listener's lock while it
        // opened messages; a short chat asks for it as it opens, so it landed mid-slide (cheap
        // phone trace 2026-11-16: up to 16 ms a frame blocked on that lock alone).
        val reg = convRef.collection("messages")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .startAt(oldest)
            .limit(PAGE_SIZE)
            .addSnapshotListener(listenExec) { snap, _ ->
                if (synchronized(docsLock) { page >= olderPageIds.size }) return@addSnapshotListener
                if (snap == null) {
                    if (first) { first = false; _state.update { it.copy(loadingOlder = false) } }
                    return@addSnapshotListener
                }
                val ids = snap.documents.mapTo(HashSet()) { it.id }
                // An empty first answer from the phone's own copy waits for the server's (a one-shot
                // read would have asked the server first).
                if (first && snap.metadata.isFromCache && snap.isEmpty) return@addSnapshotListener
                // Offline (the answer came from the cache): the phone's history fills in behind it.
                if (first && snap.metadata.isFromCache) { olderFromCache = true; fromDb() }
                if (first) {
                    first = false
                    // Rows the database drew (while offline) for this span that the server no longer has: gone.
                    if (!snap.metadata.isFromCache && snap.documents.isNotEmpty() && dbOlder.isNotEmpty()) {
                        val lo = snap.documents.mapNotNull { it.ts("createdAt")?.toDate()?.time }.minOrNull() ?: 0L
                        val hi = oldest.toDate().time
                        val gone = dbOlder.values.filter { it.createdAtMillis.toLong() in lo..hi && it.id !in ids }.map { it.id }
                        if (gone.isNotEmpty()) { gone.forEach { dbOlder.remove(it) }; com.fariin.app.local.LocalDb.forget(me, cid, gone) }
                    }
                    var added = 0
                    synchronized(docsLock) {
                        for (d in snap.documents) {
                            if (!liveDocs.containsKey(d.id) && !olderDocs.containsKey(d.id)) added++
                            if (!liveDocs.containsKey(d.id)) olderDocs[d.id] = d
                        }
                        if (page < olderPageIds.size) olderPageIds[page] = ids
                    }
                    // A short page from the server is the start of the chat; nothing new means the same.
                    val more = added > 0 && (snap.metadata.isFromCache || snap.size() >= PAGE_SIZE)
                    _state.update { it.copy(loadingOlder = false, canLoadOlder = more) }
                    rebuild()
                    return@addSnapshotListener
                }
                // A later change on this page: edits, reactions, delete-for-everyone, or a message
                // the server removed (gone from this page and from every other one).
                val newest = synchronized(docsLock) {
                    if (page >= olderPageIds.size) return@addSnapshotListener
                    for (d in snap.documents) if (!liveDocs.containsKey(d.id)) olderDocs[d.id] = d
                    for (id in olderPageIds[page]) {
                        if (id in ids || liveDocs.containsKey(id)) continue
                        if (olderPageIds.withIndex().none { (i, set) -> i != page && id in set }) olderDocs.remove(id)
                    }
                    olderPageIds[page] = ids
                    page == olderPageIds.size - 1
                }
                if (newest && !snap.metadata.isFromCache && snap.size() < PAGE_SIZE)
                    _state.update { it.copy(canLoadOlder = false) }
                rebuild()
            }
        synchronized(docsLock) { olderRegs.add(reg) }
        // Offline with nothing saved for that page, no answer comes: after 8 s the spinner stops and
        // this page's listener goes, so the next scroll asks again instead of waiting forever.
        listenExec.schedule({
            if (!closed && first && synchronized(docsLock) { page < olderPageIds.size }) {
                first = false
                reg.remove(); synchronized(docsLock) { olderRegs.remove(reg) }
                _state.update { it.copy(loadingOlder = false) }
                fromDb()   // no answer at all: the phone's own history
            }
        }, 8, java.util.concurrent.TimeUnit.SECONDS)
    }

    /** A message this phone can never open (iOS, same rule): sent BEFORE this phone's keypair was
     *  made, and a lock for words or a photo/video/file/album whose key will not open. A newer one
     *  keeps its lock (a stale key can still heal); an unknown date hides nothing. Deleted messages
     *  and plain (unsealed) ones never count. */
    private fun wontOpenHere(m: Message, since: Long?): Boolean {
        if (m.deleted || since == null || !m.hasServerTime || m.createdAtMillis >= since) return false
        if (m.text == Wire.LOCKED) return true
        if (m.enc != null && crypto.fileWillNotOpen(cid, m.enc)) return true
        return m.album.isNotEmpty() && m.album.all { crypto.fileWillNotOpen(cid, it.enc) }
    }

    /** My `clearedAt` mark in the chat doc [conv] (ms), 0 when I never cleared it. */
    private fun clearedAtOf(conv: Map<String, Any?>): Double =
        ((conv["clearedAt"] as? Map<*, *>)?.get(me) as? Number)?.toDouble() ?: 0.0

    @Synchronized
    private fun rebuild(loaded: Boolean? = null) {
        // The maps as they are now: the listener thread may change them while this runs.
        val liveDocs: Map<String, DocumentSnapshot>
        val olderDocs: Map<String, DocumentSnapshot>
        synchronized(docsLock) {
            liveDocs = LinkedHashMap(this.liveDocs)
            olderDocs = LinkedHashMap(this.olderDocs)
            builtSeq = docsSeq
        }
        val all = LinkedHashMap<String, Message>()
        val unconfirmed = HashSet<String>()
        val now = System.currentTimeMillis().toDouble()
        val stamped = ArrayList<Message>()
        val due = ArrayList<Message>()
        // Chat History for new members hidden before I joined (iOS historyStart): the chat starts
        // when I came in, as a new group would. My own messages are always there.
        val historyStart = if (Wire.isGroupCid(cid)) GroupManage.historyStart(_state.value.conv, me) else null
        // iOS isClearedForMe: my Clear Chat / Delete for Me mark (`clearedAt.<me>`, server ms) hides
        // every sent, server-stamped message at or before it, also when my other device set it.
        val clearedAt = clearedAtOf(_state.value.conv)
        // Messages from before this phone's keys: left out, ONE notice at the newest one's place (iOS).
        val keysSince = crypto.keysSince()
        // The file sends this phone wrote down (FileOutbox), read once per rebuild and only if needed.
        val keptHere by lazy { com.fariin.app.local.FileOutbox.all(me).mapTo(HashSet()) { it.clientId } }
        var newestUnopened: Message? = null
        // iOS notForMe: an old end-to-end group message sealed without me, sent before I came in
        // (memberSince + 5 s), is not mine to read; it goes instead of drawing as a lock. One sealed by
        // a phone that had not seen me join yet stays (the lock), as iOS.
        val joinedAt = if (Wire.isGroupCid(cid))
            Conversation.millis((_state.value.conv["memberSince"] as? Map<*, *>)?.get(me)) else 0.0
        // A synced channel draws its store (ChannelSync); everything else the window and older pages.
        for (d in chSync?.docsOrNull() ?: (olderDocs.values + liveDocs.values)) {
            if (d.id in takenDown) continue
            var m = parse(d)
            if (m.uploading && m.authorId == me && m.sendState == null) m.clientId?.let { k ->
                // A file, song or playlist THIS phone started, still `uploading` from before this app
                // started, with no send left running: it failed too (the app was closed or killed on
                // it), red with Resend and Delete. One from another phone of mine keeps spinning
                // until the stuck sweep (iOS clearStuckSends), so Delete never takes down its upload.
                val orphan = m.hasServerTime && m.createdAtMillis < processStartMs &&
                    com.fariin.app.local.FileOutbox.failedHere(m.type, k, keptHere, com.fariin.app.media.MediaSend::isInFlight)
                if (k in failedMedia || orphan) m = m.copy(sendState = Message.SendState.FAILED)
            }
            if (historyStart != null && m.hasServerTime && m.createdAtMillis < historyStart && m.authorId != me) continue
            if (clearedAt > 0 && m.sendState == null && m.hasServerTime && m.createdAtMillis <= clearedAt) continue
            // Only a message that could not be opened (drawn as the lock) is worth parsing again: the
            // envelope check is heavy, and rebuild runs on every change (mid phones).
            if (m.text == Wire.LOCKED && Wire.isGroupCid(cid)) {
                val raw = d.get("text") as? String ?: ""
                if (raw.startsWith(Wire.ENCG1) && crypto.groupEnvelopeSkipsMe(raw, cid) &&
                    (joinedAt <= 0 || m.createdAtMillis < joinedAt + 5000)) continue
            }
            // The disappearing timer: the server's stamp only (iOS). Due = off the screen now, from
            // the saved copy too; the server deletes it for both people.
            if (m.expiresAtMillis != null) {
                if (Disappearing.isExpired(m, now)) { due.add(m); continue }
                stamped.add(m)
            }
            // My note or photo whose file is still going up: the copy on this phone draws it.
            // This phone's files and cards are for my own sends only (H-56: a reused clientId).
            val ownKey = m.clientId?.takeIf { m.authorId == me }
            val local = ownKey?.let { localFiles[it] }
            if (local != null && m.audioUrl == null && m.imageUrl == null) m = m.copy(localPath = local)
            ownKey?.let { k -> localUris[k] }?.let { picked -> m = withPicked(m, picked) }
            ownKey?.let { k -> localCards[k]?.let { card ->
                if (m.linkPreview == null && !m.deleted) m = m.copy(linkPreview = card) else localCards.remove(k)
            } }
            if (HiddenMessages.isHidden(m.id)) continue
            // Sealed to a key this phone no longer has (a reinstall, a new phone): left out, never a
            // lock or a grey box (owner 2026-11-11, the big messengers' way). The server keeps it,
            // and the other person still has it; a key backup brings it back.
            if (wontOpenHere(m, keysSince)) {
                if (newestUnopened == null || m.createdAtMillis > newestUnopened!!.createdAtMillis) newestUnopened = m
                continue
            }
            // Sent while I had them blocked (now or in a past block): never shown (iOS BlockList.hides).
            if (m.authorId != me && BlockList.hides(m.authorId, m.createdAtMillis)) continue
            all[m.rowId] = m
            if (d.metadata.hasPendingWrites()) unconfirmed.add(m.rowId)
        }
        // The phone's own older history (phase 1), under the same rules as a server row; a server
        // row with the same id always wins.
        if (chSync == null) for (m in dbOlder.values) {
            if (all.containsKey(m.rowId) || liveDocs.containsKey(m.id) || olderDocs.containsKey(m.id)) continue
            if (historyStart != null && m.createdAtMillis < historyStart && m.authorId != me) continue
            if (clearedAt > 0 && m.hasServerTime && m.createdAtMillis <= clearedAt) continue
            if (Disappearing.isExpired(m, now) || HiddenMessages.isHidden(m.id)) continue
            if (wontOpenHere(m, keysSince)) continue
            if (m.authorId != me && BlockList.hides(m.authorId, m.createdAtMillis)) continue
            all[m.rowId] = m
        }
        newestUnopened?.let { n ->
            // Older messages this phone can open still show above the line (owner 2026-11-19, kreak's
            // chat: a video from before it read as "aren't available"), so then it says "Some".
            val olderShown = all.values.any { !it.isSystem && it.createdAtMillis < n.createdAtMillis }
            val text = if (olderShown) KEYS_NOTICE_SOME else KEYS_NOTICE
            all[KEYS_NOTICE_ID] = Message(id = KEYS_NOTICE_ID, authorId = "", text = text, type = "system",
                createdAtMillis = n.createdAtMillis, hasServerTime = true)
            KeysNoticeMemo.set(me, cid, n.createdAtMillis, text)
        }
        // None in a server window that reaches back past the remembered one: it is gone for good.
        if (newestUnopened == null && liveDocs.isNotEmpty()) KeysNoticeMemo.get(me, cid)?.let { (at, _) ->
            val oldest = all.values.filter { !it.isSystem }.minOfOrNull { it.createdAtMillis }
            if (oldest != null && oldest <= at) KeysNoticeMemo.clear(me, cid)
        }
        // "You blocked / unblocked this person" (iOS blockNotices): from my own block list, for my
        // eyes only, never written to the chat. 1:1 only, and only inside the loaded history.
        if (!Wire.isGroupCid(cid) && cid.contains('_')) {
            val other = Wire.otherUid(cid, me)
            val snap = BlockList.snapshot
            val events = ArrayList<Pair<Double, Boolean>>()
            snap.spans[other]?.forEach { events += it.start to true; events += it.endInclusive to false }
            snap.entries[other]?.let { events += it to true }
            val floor = if (_state.value.canLoadOlder) (all.values.filter { it.hasServerTime }.minOfOrNull { it.createdAtMillis } ?: Double.MAX_VALUE) else Double.NEGATIVE_INFINITY
            for ((ms, on) in events) {
                if (ms <= 0 || ms < floor) continue
                val id = "local-block-${if (on) "on" else "off"}-${ms.toLong()}"
                all[id] = Message(id = id, authorId = "", text = if (on) BLOCKED_NOTICE else UNBLOCKED_NOTICE, type = "system",
                    createdAtMillis = ms, hasServerTime = true)
            }
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
        // A forward on its way here (ForwardEchoes): drawn until the real message (same clientId) is in.
        for (e in ForwardEchoes.of(cid)) {
            val k = e.clientId ?: continue
            if (!all.containsKey(k)) all[k] = e else ForwardEchoes.remove(cid, k)
        }
        // While only the saved copy is on screen, a pending send joins it instead of replacing it.
        val shown = if (seeded && liveDocs.isEmpty() && olderDocs.isEmpty())
            _state.value.messages.filter { it.sendState == null && it.rowId !in all && !Disappearing.isExpired(it, now) } + all.values else all.values
        val sorted = order.sort(sweepStuck(shown, now))
        _state.update { it.copy(messages = sorted, loaded = loaded ?: it.loaded) }
        // A channel never shows a deleted post: its saved copy leaves them out, so the 60 kept rows
        // reach back to real posts (VE3: the newest 60 were all deleted, every open began empty).
        if (live) LocalCache.saveThread(me, cid, if (isChannelChat()) sorted.filter { !it.deleted } else sorted)
        // Every message this chat has opened goes into the phone's history (phase 1: the server
        // still keeps everything; only rows that changed are written).
        if (live) {
            // The no-gap rule's inputs: the oldest server row read in one unbroken run (the live
            // window plus the older pages, all contiguous from the newest), and whether the server
            // said there is nothing older.
            // Safety review 2026-11-12: vouch only for server rows (never cache, seed or local-db
            // rows), never past a message this phone could not open yet ("…", or a group lock whose
            // key may still be released), and the newest point is the newest SERVER row.
            // H-87 (as iOS): only the live window vouches. It is one unbroken run from the newest by
            // definition; older pages and rows that slid out may sit across a hole (a reconnect after
            // 60+ new messages), and vouching for them moved the mark and the cursor over it.
            val times = liveDocs.values.filter { !it.metadata.hasPendingWrites() }.mapNotNull { it.ts("createdAt")?.toDate()?.time }
            val unopened = sorted.any { !it.deleted && (it.text == "\u2026" || it.text == Wire.LOCKED) }
            // 1:1 only (owner 2026-11-12): every group stays on the server, so it never vouches.
            val vouch = windowFromServer && !unopened && chSync == null && !isChannelChat() && !Wire.isGroupCid(cid) && times.isNotEmpty()
            com.fariin.app.local.LocalDb.save(me, cid, sorted, cursor = vouch,
                oldestFiledMs = times.minOrNull(), newestFiledMs = times.maxOrNull(),
                reachedStart = windowWhole, mediaWatch = vouch)
        }
        scheduleBurn(Disappearing.nextExpiry(stamped, now))
        if (due.isNotEmpty()) burn(due)
    }

    // ------------------------------------------------------------------ disappearing messages

    private var burnJob: Job? = null
    private val burned = HashSet<String>()

    // ------------------------------------------------------------------ stuck sends

    private var sweepJob: Job? = null
    /** Texts already shown failed by the sweep: they stay failed until their echo lands (iOS). */
    private val sweptSends = ConcurrentHashMap.newKeySet<String>()
    /** When this repository started the current try of a send: a queued text from an earlier run
     *  gets its grace from now, not from the old tap time (it showed red at once on open). */
    private val sweepFrom = ConcurrentHashMap<String, Double>()

    /**
     * iOS sweepStuckSends: a text of mine never spins "sending" for ever. With no network it shows
     * the red mark after 5 s (2 min with one, a slow but real send). Only the look changes: the
     * write stays queued and goes by itself, and its echo from the server puts the tick back.
     * Media is left out (its upload may still be running and has its own retry).
     */
    private fun sweepStuck(shown: Collection<Message>, now: Double): Collection<Message> {
        val grace = if (ConnectionStatus.offline) 5_000.0 else 120_000.0
        var next = Double.MAX_VALUE
        val out = shown.map { m ->
            if (m.sendState != Message.SendState.SENDING || m.authorId != me || m.hasServerTime ||
                m.mediaKind != null || !(m.type == null || m.type == "text")) return@map m
            val id = m.clientId ?: m.id
            val at = maxOf(m.clientTs ?: m.createdAtMillis, sweepFrom[id] ?: 0.0) + grace
            if (id in sweptSends || at <= now) { sweptSends += id; m.copy(sendState = Message.SendState.FAILED) }
            else { next = minOf(next, at); m }
        }
        // Looked at again at least every 15 s (iOS's timer), so a signal lost meanwhile shows too.
        sweepJob?.cancel(); sweepJob = null
        if (next < Double.MAX_VALUE && !closed) {
            val wait = minOf(15_000L, maxOf(500L, (next - now).toLong() + 200L))
            sweepJob = scope.launch { delay(wait); rebuild() }
        }
        return out
    }

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
        // It leaves the phone's history at the same moment it leaves the screen (iOS burnDue),
        // not only at the next app start.
        if (me.isNotEmpty()) com.fariin.app.local.LocalDb.purgeExpired(me)
        val fresh = synchronized(burned) { due.filter { burned.add(it.id) } }
        if (fresh.isEmpty()) return
        media?.let { md ->
            for (m in fresh) {
                val urls = listOfNotNull(m.imageUrl, m.videoUrl, m.audioUrl, m.fileUrl, m.thumbUrl) +
                    m.album.flatMap { listOfNotNull(it.imageUrl, it.videoUrl) }
                // Gone with its slot (MediaOrigin): another message's copy never fills it again.
                for (u in urls) try { md.forget(u) } catch (_: Exception) {}
            }
        }
        Disappearing.askBurn(cid, fresh.map { it.id })
    }

    private class Parsed(val snap: DocumentSnapshot, val data: Map<String, Any?>, val pending: Boolean, val epoch: Int, val message: Message)
    /**
     * Each update re-built the whole page (60 to 120 messages), opening every sealed message again on
     * the main thread, twice per send (metadata too). A message whose fields, pending state and the
     * keys it was opened with are unchanged is not opened again (the reference app keeps parsed
     * messages too; speed work 2026-11-09).
     */
    private val parsed = java.util.concurrent.ConcurrentHashMap<String, Parsed>()   // pins parse on IO too

    private fun parse(d: DocumentSnapshot): Message {
        val epoch = CryptoHub.keysEpoch.value
        // The very same snapshot as last time (an older page kept as it was): nothing to compare.
        // d.data builds a fresh map of every field on each call, and the compare walked them all,
        // for every message on every update (Pixel 2026-11-11: the chat hitched as pages came in).
        parsed[d.id]?.let { p -> if (p.snap === d && p.epoch == epoch) return p.message }
        val data = d.data ?: emptyMap()
        val pending = d.metadata.hasPendingWrites()
        parsed[d.id]?.let { p -> if (p.pending == pending && p.epoch == epoch && p.data == data) {
            parsed[d.id] = Parsed(d, p.data, pending, epoch, p.message); return p.message } }
        val m0 = Message.fromFirestore(d.id, data, cid) { raw, author -> crypto.decrypt(raw, cid, author) }
        val m = if (pending && m0.authorId == me) m0.copy(sendState = Message.SendState.SENDING) else m0
        // Was 400: a long chat scrolled past 400 wiped it, and every page after opened all again.
        if (parsed.size > 4000) parsed.clear()
        parsed[d.id] = Parsed(d, data, pending, epoch, m)
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
    fun sendText(text: String, reply: Message?, mentions: List<String>, fmt: String?) = sendText(text, reply, mentions, fmt, null)

    /** [fx]: a Fariin Plus send effect; only on a single bubble (never on a split long text). */
    fun sendText(text: String, reply: Message?, mentions: List<String>, fmt: String?, fx: String?) {
        val t = text.trim()
        if (t.isEmpty()) return
        // iOS ThreadView.send: one bubble holds 2,500 (owner 2026-10-29); longer text goes out as
        // several messages, in order, each one waiting for the one before. The reply, the
        // @-mentions and the link card ride the first (a mention pings once, not once per bubble).
        // Each part keeps its own bold and links (iOS 2026-11-19: before, every part went out plain).
        val parts = MessageLength.splitForBubbles(t)
        if (parts.size > 1) {
            val partFmt = TextFormat.forParts(fmt, t, parts)
            var before: kotlinx.coroutines.Job? = null
            parts.forEachIndexed { i, p ->
                before = sendOne(p, if (i == 0) reply else null, if (i == 0) mentions else emptyList(), before, card = i == 0,
                    fmt = partFmt[i])
            }
            return
        }
        sendOne(t, reply, mentions, null, fmt = fmt, fx = fx?.takeIf { it in SendEffects.IDS })
    }

    private var lastTap = 0.0

    /** One bubble's text; [after] = the send it must follow (a long text's earlier bubble). */
    private fun sendOne(t: String, reply: Message?, mentions: List<String>, after: kotlinx.coroutines.Job?, card: Boolean = true,
                        fmt: String? = null, fx: String? = null): kotlinx.coroutines.Job? {
        // A card is quoted by its noun (iOS safeText), never by its raw marker.
        val ref = reply?.let { ReplyQuoteRules.ref(it) }
        // Never the same millisecond as the bubble before (a long text's parts keep their order on screen).
        val tap = maxOf(System.currentTimeMillis().toDouble(), lastTap + 1).also { lastTap = it }
        val clientId = UUID.randomUUID().toString().uppercase()
        val e = SendQueue.Entry(clientId, cid, t, tap, ref?.id, ref?.authorId, ref?.text, mentions,
            fmt = fmt, fx = fx)
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

    /** Queued texts of this chat, one after the other in the order they were typed (H-89): each
     *  waits for the one before it to land or fail, so the other person reads them in order. */
    fun resendAll(list: List<SendQueue.Entry>) {
        var prev: Job? = null
        val group = Wire.isGroupCid(cid)
        for (e in SendQueue.drainOrder(list)) {
            val before = prev
            // H-29: slow mode lets one message through per wait. The next queued text waits it out,
            // instead of going at once and being removed by the server after it showed as sent.
            val after = if (!group || before == null) before else sendScope.launch {
                before.join()
                val d = _state.value.conv.takeIf { it.isNotEmpty() } ?: runCatching { convRef.get().await().data }.getOrNull()
                val wait = d?.let { if (GroupAdmins.slows(it, me)) GroupAdmins.slowSeconds(it) else 0 } ?: 0
                if (wait > 0) kotlinx.coroutines.delay(wait * 1000L + 1000L)
            }
            drive(e, checkFirst = true, on = sendScope, after = after)?.let { prev = it }
        }
    }

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
        e.fx?.let { fxAtSend[e.clientId] = it }
        pending[e.clientId] = Message(
            id = e.clientId, authorId = me, text = e.text, createdAtMillis = e.ts, hasServerTime = false,
            clientTs = e.ts, clientId = e.clientId, replyTo = ref,
            sendState = if (e.refused) Message.SendState.FAILED else Message.SendState.SENDING,
            mentions = e.mentions, bubbleStyle = style.first, bubbleDecor = style.second,   // from the first frame
            fx = e.fx,
            bold = TextFormat.parse(e.fmt, e.text.length),
            links = TextFormat.parseLinks(e.fmt, e.text.length),
        )
        sweptSends.remove(e.clientId); sweepFrom[e.clientId] = System.currentTimeMillis().toDouble()   // a new try: sending again until the sweep says otherwise
        rebuild()
        // Refused in an earlier run (iOS Entry.refused): drawn failed with Retry and Delete, never sent.
        if (e.refused) { SendQueue.end(e.clientId); return null }
        return on.launch {
            try {
                if (save) SendQueue.add(me, e)
                // A long text's later bubble goes up only after the one before it (landed or
                // failed); it is on disk first, like any send.
                after?.join()
                if (checkFirst) when (whereIs(e.clientId)) {
                    Held.SERVER -> { SendQueue.remove(me, e.clientId); return@launch }
                    // Firestore's offline queue has it from an earlier run: never written twice, and the
                    // entry stays until the server takes it, so the list keeps its clock till then.
                    Held.LOCAL -> { awaitServer(); SendQueue.remove(me, e.clientId); return@launch }
                    Held.NONE -> {}
                }
                // Deleted while it waited (iOS cancelTextSend): nothing is written.
                var called = false
                turn.withLock {
                    if (e.clientId in cancelledTexts) { called = true; return@withLock }
                    if (Wire.isGroupCid(cid)) sendGroup(e.text, e.clientId, e.ts, ref, mentions = e.mentions, fmt = e.fmt) else sendDirect(e.text, e.clientId, e.ts, ref, fmt = e.fmt)
                }
                if (called) return@launch
                // Deleted after its write was handed over: taken back (iOS cancelAnnounced).
                if (cancelledTexts.remove(e.clientId)) { cancelAnnounced(e.clientId); return@launch }
                // How long the server took to have it: a slow one is not a clock sample (ThreadOrder).
                order.noteAck(e.clientId, System.currentTimeMillis() - e.ts)
                SendQueue.remove(me, e.clientId)
                styleAtSend.remove(e.clientId)
                fxAtSend.remove(e.clientId)
                cardFor.remove(e.clientId)?.let { u -> on.launch { attachCard(e.clientId, u) } }
            } catch (x: Exception) {
                cardFor.remove(e.clientId)
                localCards.remove(e.clientId)
                // A refusal is final (the bubble offers Retry, which is a new send): kept, marked, so
                // the failed bubble is still there after a restart (iOS markRefused). Anything else
                // stays queued for the next reconnect.
                if (SendQueue.isPermanent(x)) SendQueue.markRefused(me, e.clientId)
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
            when (whereIs(e.clientId)) {
                Held.SERVER -> { SendQueue.remove(me, e.clientId); return QueuedResult.SENT }
                Held.LOCAL -> return QueuedResult.LATER   // in Firestore's queue; the next drain waits for it
                Held.NONE -> {}
            }
            turn.withLock {
                if (Wire.isGroupCid(cid)) sendGroup(e.text, e.clientId, e.ts, ref, mentions = e.mentions, fmt = e.fmt) else sendDirect(e.text, e.clientId, e.ts, ref, fmt = e.fmt)
            }
            SendQueue.remove(me, e.clientId)
            QueuedResult.SENT
        } catch (x: Exception) {
            if (SendQueue.isPermanent(x)) { SendQueue.remove(me, e.clientId); QueuedResult.REFUSED } else QueuedResult.LATER
        } finally {
            SendQueue.end(e.clientId)
        }
    }

    private enum class Held { SERVER, LOCAL, NONE }

    /** iOS SendQueue.alreadySent: the doc (id = clientId) on the server means it landed before the app
     *  died; in Firestore's offline store with a pending write means it is queued there, not sent yet. */
    private suspend fun whereIs(clientId: String): Held {
        val ref = convRef.collection("messages").document(clientId)
        try {
            val d = ref.get(Source.CACHE).await()
            if (d.exists()) return if (d.metadata.hasPendingWrites()) Held.LOCAL else Held.SERVER
        } catch (_: Exception) {}
        return try { if (ref.get(Source.SERVER).await().exists()) Held.SERVER else Held.NONE } catch (_: Exception) { Held.NONE }
    }

    /** Until the server has every write this phone queued before now (Firestore's own wait). */
    private suspend fun awaitServer() {
        try { com.fariin.app.Fb.db.waitForPendingWrites().await() } catch (_: Exception) {}
    }

    /** Tap a failed bubble: send the same words again under the same clientId. */
    fun retry(m: Message) {
        val id = m.clientId ?: return
        if (SendQueue.isInFlight(id)) return
        // A file, song or playlist kept across a restart whose copies are gone: nothing to send again, only Delete.
        if (!retryMedia.containsKey(id) && com.fariin.app.local.FileOutbox.get(me, id)
                ?.let { !com.fariin.app.local.FileOutbox.readable(it) { p -> File(p).length() } } == true) return
        SendQueue.remove(me, id)
        pending.remove(id)
        retryPhoto.remove(id)?.let { com.fariin.app.local.PhotoOutbox.remove(me, id); sendPreparedPhoto(it); return }
        // A new send under a new id: the old written-down entry goes, or a restart would send it twice.
        retryVoice.remove(id)?.let { com.fariin.app.local.PhotoOutbox.remove(me, id); sendVoiceRef(it, voiceReplies.remove(id)); return }
        failedMedia.remove(id)
        retryMedia.remove(id)?.let { work ->
            pending[id]?.let { pending[id] = it.copy(sendState = Message.SendState.SENDING) }
            rebuild()
            runMediaSend(id, work)
            return
        }
        if (m.mediaKind != null) { rebuild(); return }
        // iOS resend: the same clientId (dropped if the first try did land after all), with its
        // quoted reply, its mentions and its bold. It used to go as a new plain message.
        val tap = maxOf(System.currentTimeMillis().toDouble(), lastTap + 1).also { lastTap = it }
        val fmt = TextFormat.serialize(m.bold, m.text.length, m.links)
        val e = SendQueue.Entry(id, cid, m.text, tap, m.replyTo?.id, m.replyTo?.authorId, m.replyTo?.text, m.mentions, fmt = fmt, fx = m.fx)
        drive(e, checkFirst = true, sendScope, save = true)
    }

    /** A failed media send this process can still re-drive (its prepared bytes are held). */
    fun canResendMedia(clientId: String) =
        retryPhoto.containsKey(clientId) || retryVoice.containsKey(clientId) || retryMedia.containsKey(clientId)

    // ------------------------------------------------------------------ media picks (attach sheet)

    /**
     * What the attach sheet hands over: photos and videos in the order picked, one caption, and
     * view-once (one photo only). One item = a photo or video message; more = albums of ten
     * (Limits.albumMaxItems), in order, the caption on the first.
     */
    fun sendPick(context: Context, uris: List<Uri>, caption: String, viewOnce: Boolean) = sendPick(context, uris, caption, viewOnce, null)

    /** As above; a pick sent while replying carries the reply on its first message (photo, video or album). */
    fun sendPick(context: Context, uris: List<Uri>, caption: String, viewOnce: Boolean, reply: Message?) {
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
            val ref = reply?.let { ReplyQuoteRules.ref(it) }
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
                // The reply rides on the first message only, as the caption does.
                val re = if (n == 0) ref else null
                val once = viewOnce && chunk.size == 1
                val first = shapes[idx[0]]
                // A lone video is a video bubble from the first frame (owner 2026-11-04: it showed as a
                // photo, then turned into a video while sending).
                pending[clientId] = if (chunk.size == 1) Message(
                    id = clientId, authorId = me, text = words, type = if (kinds[idx[0]]) "video" else "image",
                    createdAtMillis = ts, hasServerTime = false, clientTs = ts, clientId = clientId,
                    sendState = Message.SendState.SENDING, viewOnce = once, replyTo = re,
                    width = first?.width?.toDouble(), height = first?.height?.toDouble(), duration = first?.duration,
                    localUri = if (once) null else chunk[0].toString(),
                ) else Message(
                    id = clientId, authorId = me, text = words, type = "album",
                    createdAtMillis = ts, hasServerTime = false, clientTs = ts, clientId = clientId,
                    sendState = Message.SendState.SENDING, replyTo = re,
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
                        // The ring's first 30% is the compression (a long clip took minutes with a still ring).
                        val v = VideoPrep.fromUri(context, chunk[0], m.localDir(), clientId) {
                            com.fariin.app.media.UploadProgress.raise(clientId, 0.3f * it)
                        } ?: error("video not readable or over 64 MB")
                        val poster = File(m.localDir(), "poster-$clientId.jpg").also { it.writeBytes(v.poster.jpeg) }
                        // The server's copy keeps drawing this poster until its own has loaded (no blur step).
                        localFiles[clientId] = poster.absolutePath
                        pending[clientId]?.let { p ->
                            pending[clientId] = p.copy(type = "video", localPath = poster.absolutePath, width = v.width.toDouble(),
                                height = v.height.toDouble(), duration = v.duration, thumb = v.poster.thumbB64)
                        }
                        rebuild()
                        // Written down before the upload, as a photo is (PhotoOutbox): the clip and poster are on the phone.
                        com.fariin.app.local.PhotoOutbox.add(me, com.fariin.app.local.PhotoOutbox.Entry(cid, clientId, ts,
                            v.file.absolutePath, v.width, v.height, null, v.poster.thumbB64, words, false,
                            kind = "video", poster = poster.absolutePath, duration = v.duration,
                            replyId = re?.id, replyAuthor = re?.authorId, replyText = re?.text))
                        val work: suspend () -> Unit = { sender.video(v, words, false, clientId, ts, reply = re); com.fariin.app.local.PhotoOutbox.remove(me, clientId) }
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
                        // Written down before the upload, so a kill (offline, say) cannot lose it (PhotoOutbox).
                        com.fariin.app.local.PhotoOutbox.add(me, com.fariin.app.local.PhotoOutbox.Entry(cid, clientId, ts,
                            local.absolutePath, prep.width, prep.height, prep.blurhash, prep.thumbB64, words, once,
                            replyId = re?.id, replyAuthor = re?.authorId, replyText = re?.text))
                        // A view-once photo keeps no plain copy on my phone once it is up (as the one-time voice note),
                        // nor the photo editor's copy in cache/edited when it was drawn on or cropped first.
                        val work: suspend () -> Unit = { sender.image(prep, words, once, false, clientId, ts, reply = re); com.fariin.app.local.PhotoOutbox.remove(me, clientId); if (once) { local.delete(); com.fariin.app.ui.thread.deleteEditedCopy(context, chunk[0]) } }
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
                        // Written down before the upload, as a single photo is (PhotoOutbox): every
                        // tile's picture (and clip) is on the phone now.
                        val items = parts.mapIndexed { i, part ->
                            val pp = when (part) { is MediaSender.AlbumPart.Photo -> part.prep; is MediaSender.AlbumPart.Video -> part.prep.poster }
                            val vid = (part as? MediaSender.AlbumPart.Video)?.prep
                            com.fariin.app.local.PhotoOutbox.Item(File(m.localDir(), "tile-$clientId-$i.jpg").absolutePath, pp.width, pp.height,
                                pp.blurhash, pp.thumbB64, vid?.file?.absolutePath, vid?.duration ?: 0.0)
                        }
                        com.fariin.app.local.PhotoOutbox.add(me, com.fariin.app.local.PhotoOutbox.Entry(cid, clientId, ts,
                            items[0].path, items[0].width, items[0].height, null, null, words, false, kind = "album", items = items,
                            replyId = re?.id, replyAuthor = re?.authorId, replyText = re?.text))
                        val work: suspend () -> Unit = { sender.album(parts, words, false, clientId, ts, reply = re); com.fariin.app.local.PhotoOutbox.remove(me, clientId) }
                        retryMedia[clientId] = work
                        work()
                    }
                }
            }
        }
    }

    /** A document from the system file picker (iOS sendFile); a refusal is handed to [onRefused]. */
    fun sendDocument(context: Context, uri: Uri, onRefused: (String) -> Unit) = sendDocument(context, uri, null, onRefused)

    /** As above; a file sent while replying carries the reply. */
    fun sendDocument(context: Context, uri: Uri, reply: Message?, onRefused: (String) -> Unit) = sendDocument(context, uri, reply, null, onRefused)

    /** As above; [rename] is a song's title and artist typed in the playlist sheet (null = its own). */
    fun sendDocument(context: Context, uri: Uri, reply: Message?, rename: Pair<String?, String?>?, onRefused: (String) -> Unit) {
        val m = media ?: return
        scope.launch(Dispatchers.IO) {
            val f = when (val r = FilePrep.fromUri(context, uri, m.localDir())) {
                is FilePrep.Companion.Result.Ok -> r.prep
                FilePrep.Companion.Result.TooLarge -> { onRefused("File too large (max ${FilePrep.MAX_BYTES / (1024 * 1024)} MB)."); return@launch }
                FilePrep.Companion.Result.Unreadable -> { onRefused("Couldn't read this file."); return@launch }
            }
            val tap = System.currentTimeMillis().toDouble()
            val clientId = UUID.randomUUID().toString().uppercase()
            val ref = reply?.let { ReplyQuoteRules.ref(it) }
            val local = File(m.localDir(), "file-$clientId").also { it.writeBytes(f.bytes) }
            pending[clientId] = Message(
                id = clientId, authorId = me, text = "", type = "file", createdAtMillis = tap, hasServerTime = false,
                clientTs = tap, clientId = clientId, sendState = Message.SendState.SENDING, replyTo = ref,
                fileName = f.name, fileSize = f.bytes.size.toLong(), localPath = local.absolutePath,
            )
            rebuild()
            // Any file, song or not: a failed upload after its write shows failed and its Resend
            // sends only the file into the same message (as a playlist's does).
            val song = com.fariin.app.media.MediaRules.isSong(f.name)
            val upload = com.fariin.app.media.MediaSender.FileUpload()
            // Written down first, so a kill cannot lose it (FileOutbox, iOS PendingUploadStore).
            keepFile(if (song) "song" else "file", clientId, tap, listOf(local), listOf(f.name), f.previewJpeg, ref = ref,
                renames = listOfNotNull(rename))
            val work: suspend () -> Unit = {
                sender.file(f, false, clientId, tap, reply = ref, rename = rename, job = upload, save = fileSave(clientId))
                playlistSends.remove(clientId)
                com.fariin.app.local.FileOutbox.remove(me, clientId)
            }
            playlistSends += clientId
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
    fun sendDocuments(context: Context, uris: List<Uri>, onRefused: (String) -> Unit) = sendDocuments(context, uris, null, onRefused)

    /** As above; sent while replying, the first file carries the reply. */
    fun sendDocuments(context: Context, uris: List<Uri>, reply: Message?, onRefused: (String) -> Unit) {
        val m = media ?: return
        if (uris.isEmpty()) return
        // Several songs picked together are ONE playlist message (iOS sendPlaylist, 2026-11-21).
        if (uris.size >= 2 && uris.all { MediaRules.isSong(displayName(context, it)) }) {
            sendPlaylist(context, uris, "", "", reply, onRefused)
            return
        }
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
                val re = if (queued.isEmpty()) reply?.let { ReplyQuoteRules.ref(it) } else null
                val local = File(m.localDir(), "file-$clientId").also { it.writeBytes(f.bytes) }
                pending[clientId] = Message(
                    id = clientId, authorId = me, text = "", type = "file", createdAtMillis = tap, hasServerTime = false,
                    clientTs = tap, clientId = clientId, sendState = Message.SendState.SENDING, replyTo = re,
                    fileName = f.name, fileSize = f.bytes.size.toLong(), localPath = local.absolutePath,
                )
                // The bytes are read back from this phone's copy when the send starts, so twenty
                // photos never sit in memory at once.
                val name = f.name
                val preview = f.previewJpeg
                keepFile("file", clientId, tap, listOf(local), listOf(name), preview, ref = re)
                // As sendDocument: a failed upload after the write goes red, and Resend completes that message.
                val upload = com.fariin.app.media.MediaSender.FileUpload()
                val work: suspend () -> Unit = {
                    sender.file(FilePrep(name, local.readBytes(), preview), false, clientId, tap, reply = re, job = upload, save = fileSave(clientId))
                    playlistSends.remove(clientId)
                    com.fariin.app.local.FileOutbox.remove(me, clientId)
                }
                playlistSends += clientId
                retryMedia[clientId] = work
                queued += clientId to work
                rebuild()
            }
            val skipped = listOfNotNull(
                unreadable.takeIf { it > 0 }?.let { n -> if (n == 1) "1 file couldn't be read, so it was skipped." else "$n files couldn't be read, so they were skipped." },
                tooLarge.takeIf { it > 0 }?.let { n -> if (n == 1) "1 file is over ${FilePrep.MAX_BYTES / (1024 * 1024)} MB, so it was skipped." else "$n files are over ${FilePrep.MAX_BYTES / (1024 * 1024)} MB, so they were skipped." },
            )
            if (skipped.isNotEmpty()) onRefused(skipped.joinToString(" "))
            // One after another: each send's message is written before the next one starts.
            for ((clientId, work) in queued) runMediaSend(clientId, work).join()
        }
    }

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    /**
     * A playlist (iOS sendPlaylist): up to ten songs in pick order, named [title] ("" = none), with
     * [caption]. The cover is the first song's own picture (iOS sheet's default cover). My bubble
     * plays the songs from this phone's copies while they go up.
     */
    fun sendPlaylist(context: Context, uris: List<Uri>, title: String, caption: String, reply: Message?, onRefused: (String) -> Unit) =
        sendPlaylist(context, uris, title, caption, reply, emptyList(), null, onRefused)

    /**
     * As above, from the send sheet: [renames] is each song's typed title and artist in [uris]' order
     * (null = its own), [cover] the chosen cover: null = the first song's own picture, an empty array
     * = removed, else a chosen photo's JPEG (iOS SongSheetCover).
     */
    fun sendPlaylist(context: Context, uris: List<Uri>, title: String, caption: String, reply: Message?,
                     renames: List<Pair<String?, String?>>, cover: ByteArray?, onRefused: (String) -> Unit) {
        val m = media ?: return
        scope.launch(Dispatchers.IO) {
            val songs = ArrayList<FilePrep>()
            val okUris = ArrayList<Uri>()
            val okRenames = ArrayList<Pair<String?, String?>>()
            var skipped = 0
            for (uri in uris.take(MusicRules.MAX_TRACKS)) {
                when (val r = FilePrep.fromUri(context, uri, m.localDir())) {
                    is FilePrep.Companion.Result.Ok -> { songs += r.prep; okUris += uri; okRenames += renames.getOrNull(uris.indexOf(uri)) ?: (null to null) }
                    else -> skipped++
                }
            }
            val over = uris.size - MusicRules.MAX_TRACKS
            listOfNotNull(
                skipped.takeIf { it > 0 }?.let { n -> if (n == 1) "1 song couldn't be read or is too large, so it was skipped." else "$n songs couldn't be read or are too large, so they were skipped." },
                over.takeIf { it > 0 }?.let { "A playlist holds up to ${MusicRules.MAX_TRACKS} songs; the first ${MusicRules.MAX_TRACKS} were sent." },
            ).takeIf { it.isNotEmpty() }?.let { onRefused(it.joinToString(" ")) }
            if (songs.size < 2) {
                // One song left is a song message, as one song picked is.
                okUris.firstOrNull()?.let { sendDocument(context, it, reply, okRenames.firstOrNull(), onRefused) }
                return@launch
            }
            val tap = System.currentTimeMillis().toDouble()
            val clientId = UUID.randomUUID().toString().uppercase()
            val ref = reply?.let { ReplyQuoteRules.ref(it) }
            val locals = songs.mapIndexed { i, s -> File(m.localDir(), "file-$clientId-t$i").also { it.writeBytes(s.bytes) } }
            pending[clientId] = Message(
                id = clientId, authorId = me, text = caption.trim(), type = "file", createdAtMillis = tap, hasServerTime = false,
                clientTs = tap, clientId = clientId, sendState = Message.SendState.SENDING, replyTo = ref,
                fileName = songs[0].name, fileSize = songs[0].bytes.size.toLong(),
                tracks = songs.mapIndexed { i, s -> MusicRules.Track(locals[i].absolutePath, null, s.name, s.bytes.size.toLong(),
                    okRenames[i].first?.trim()?.ifEmpty { null }, okRenames[i].second?.trim()?.ifEmpty { null }) },
                listName = title.trim(),
            )
            rebuild()
            val tile = when {
                cover == null -> songs[0].previewJpeg
                cover.isEmpty() -> null
                else -> cover
            }
            val names = songs.map { it.name }
            // Kept across a Resend: the message and the songs already up are not sent twice.
            val upload = com.fariin.app.media.MediaSender.PlaylistUpload()
            // Written down first, so a kill cannot lose it (FileOutbox): the next start puts up only
            // the songs still missing and completes the same message.
            keepFile("playlist", clientId, tap, locals, names, tile, title, caption, okRenames, ref)
            val work: suspend () -> Unit = {
                // Read back from this phone's copies when the send starts, as a list of documents is.
                sender.playlist(names.mapIndexed { i, n -> FilePrep(n, locals[i].readBytes(), null) }, title, caption, tile, clientId, tap, reply = ref,
                    renames = okRenames, job = upload, save = playlistSave(clientId))
                playlistSends.remove(clientId)
                com.fariin.app.local.FileOutbox.remove(me, clientId)
            }
            playlistSends += clientId
            retryMedia[clientId] = work
            runMediaSend(clientId, work)
        }
    }

    // ------------------------------------------------------------------ "sending a photo…"

    /** My uploads running in this chat now, clientId to kind ("photo", "video", "file"). */
    private val sendingNow = ConcurrentHashMap<String, String>()
    @Volatile private var sendingTicker: Job? = null

    /** The kind a pending media bubble is sending, or null (a voice note says "recording" instead). */
    private fun sendingKind(clientId: String): String? {
        val m = pending[clientId] ?: return "photo"
        return when (m.mediaKind) {
            Message.MediaKind.VIDEO -> "video"
            Message.MediaKind.ALBUM -> if (m.album.any { it.isVideo }) "video" else "photo"
            Message.MediaKind.FILE -> "file"
            Message.MediaKind.VOICE -> null
            Message.MediaKind.VIEW_ONCE -> "photo"
            else -> if (m.type == "video") "video" else "photo"
        }
    }

    /**
     * "sending a photo…" (owner 2026-11-14, shared with iOS): while any of my uploads runs here,
     * `typing.<me>` = "photo-<s>" / "video-<s>" / "file-<s>" (a video anywhere wins, then photo),
     * re-sent every 10 s; false once the last one ends, fails or is cancelled. Same Typing
     * Indicators switch and membership check as typing.
     */
    private val sendingWrites = kotlinx.coroutines.sync.Mutex()

    private fun publishSending() {
        if (Wire.isGroupCid(cid) && me !in ((_state.value.conv["users"] as? List<*>) ?: emptyList<Any>())) return
        SendScope.launch {
            // One write at a time, each with the state as it is WHEN it runs: a fast photo's "off"
            // used to overtake its "on" and the "on" landed last and stayed (Pixel test 2026-11-14).
            sendingWrites.withLock {
                val kinds = sendingNow.values.toSet()
                val kind = when { "video" in kinds -> "video"; "photo" in kinds -> "photo"; "file" in kinds -> "file"; else -> null }
                if (kind != null && !PrivacySettings.typingIndicators()) return@withLock
                try {
                    val v = MessageRules.sendingValue(kind, System.currentTimeMillis())
                    convRef.set(mapOf("typing" to mapOf(me to v)), SetOptions.merge()).await()
                } catch (_: Exception) {}
            }
        }
    }

    private fun sendingStart(clientId: String) {
        // Never in a channel (iOS the same): followers do not see admins at work.
        if (isChannelChat()) return
        val kind = sendingKind(clientId) ?: return
        sendingNow[clientId] = kind
        publishSending()
        if (sendingTicker?.isActive != true) sendingTicker = SendScope.launch {
            while (sendingNow.isNotEmpty()) {
                kotlinx.coroutines.delay(MessageRules.RECORDING_REFRESH_MS)
                if (sendingNow.isNotEmpty()) publishSending()
            }
        }
    }

    private fun sendingEnd(clientId: String) {
        if (sendingNow.remove(clientId) == null) return
        if (sendingNow.isEmpty()) sendingTicker?.cancel()
        publishSending()
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
            sendingStart(clientId)
            try {
                work()
                retryMedia.remove(clientId)
                failedMedia.remove(clientId)
                com.fariin.app.media.MediaSend.clearItems(clientId)
                com.fariin.app.media.MediaSend.finish(clientId)
            } catch (e: Exception) {
                // H-28 (iOS cancels it too): a video whose message went out before its clip. Files,
                // songs and playlists resume on their own message instead (playlistSends).
                val announced = if (clientId in playlistSends) null else com.fariin.app.media.MediaSend.announcedId(clientId)
                if (mediaSendEnded(clientId, e)) { com.fariin.app.local.PhotoOutbox.remove(me, clientId); com.fariin.app.local.FileOutbox.remove(me, clientId); return@launch }
                if (announced != null) {
                    // The half message is taken down and this phone's copy drawn failed in its place,
                    // so Resend writes one new message, never a second one beside a ring that spins
                    // for ever on both phones.
                    val shown = _state.value.messages.firstOrNull { it.clientId == clientId && it.authorId == me && it.id == announced }
                    if (pending[clientId] == null && shown != null)
                        pending[clientId] = shown.copy(id = clientId, uploading = false, sendState = Message.SendState.FAILED)
                    takenDown.add(announced)
                    SendScope.launch(Dispatchers.IO) { cancelAnnounced(announced) }
                }
                // Refused for good: no resend at the next start either.
                if (SendQueue.isPermanent(e)) { com.fariin.app.local.PhotoOutbox.remove(me, clientId); com.fariin.app.local.FileOutbox.remove(me, clientId) }
                if (!retryMedia.containsKey(clientId)) retryMedia[clientId] = work
                if (clientId in playlistSends) failedMedia += clientId
                com.fariin.app.media.UploadProgress.done(clientId)
                pending[clientId]?.let { pending[clientId] = it.copy(sendState = Message.SendState.FAILED) }
                rebuild()
            } finally { sendingEnd(clientId) }
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
        retryMedia.remove(clientId); retryPhoto.remove(clientId); failedMedia.remove(clientId)
        com.fariin.app.local.PhotoOutbox.remove(me, clientId)
        com.fariin.app.local.FileOutbox.remove(me, clientId)
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
            // H-28: a send this phone still holds in its outbox (or is running) is never swept: its
            // resume finishes it, or takes the half message down and sends it again.
            val kept = (com.fariin.app.local.PhotoOutbox.all(me).map { it.clientId } +
                com.fariin.app.local.FileOutbox.all(me).map { it.clientId }).toHashSet()
            for (d in snap.documents) {
                val made = d.ts("createdAt")?.toDate()?.time ?: continue
                val k = d.get("clientId") as? String
                if (k != null && (k in kept || com.fariin.app.media.MediaSend.isInFlight(k))) continue
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
                                  fwdSource: Message.ForwardSource? = null, fmt: String? = null,
                                  clientId: String = UUID.randomUUID().toString().uppercase(),
                                  /** The forwarded post's quoted card (iOS 80b0470bf). */ quote: String? = null) {
        val t = text.trim()
        val q = quote?.takeIf { QuoteRules.isQid(it) }
        if (t.isEmpty()) return
        val ts = System.currentTimeMillis().toDouble()
        // The source's bold goes along only while the words are exactly the ones it was measured on.
        val f = fmt?.takeIf { t == text }
        turn.withLock {
            if (Wire.isGroupCid(cid)) sendGroup(t, clientId, ts, null, forwarded, fwdChannelJson = fwdChannelJson, fwdSource = fwdSource, fmt = f, quote = q)
            else sendDirect(t, clientId, ts, null, forwarded, fwdChannelJson = fwdChannelJson, fwdSource = fwdSource, fmt = f, quote = q)
        }
    }

    /**
     * A link shared from a Share sheet (iOS ForwardPicker share): sent as my own text, not forwarded,
     * and its card added to that same message once built (given up on after 2 s, then it goes bare).
     */
    suspend fun sendSharedLink(text: String) {
        val clientId = UUID.randomUUID().toString().uppercase()
        sendForwardedText(text, forwarded = false, clientId = clientId)
        val u = LinkPreviews.firstUrl(text) ?: return
        if (!LinkPreviews.allowedIn(CryptoHub.listedConversation(cid), me)) return
        val d = LinkPreviews.draftWithin(u, 2_000) ?: return
        LinkPreviews.attach(convRef, cid, me, crypto, clientId, d, if (Wire.isGroupCid(cid)) freshMembers() else null)
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
            links = TextFormat.parseLinks(fmt, t.length),
        )
        rebuild()
        sendScope.launch {
            try {
                turn.withLock {
                    if (Wire.isGroupCid(cid)) sendGroup(t, clientId, ts, mentions = mentions, quote = qid, fmt = fmt)
                    else sendDirect(t, clientId, ts, quote = qid, fmt = fmt)
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
        val ref = reply?.let { ReplyQuoteRules.ref(it) }
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
        val ref = reply?.let { ReplyQuoteRules.ref(it) }
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
        runMediaSend(clientId) { writeMineSticker(s, png, w, h, ref, clientId, tap) }
    }

    /** The write of a sticker I made: the message with `uploading`, then its sealed PNG attached. */
    private suspend fun writeMineSticker(s: StickerRef, png: ByteArray, w: Double, h: Double, ref: Message.ReplyRef?,
                                         clientId: String, tap: Double, forwarded: Boolean = false,
                                         fwdSource: Message.ForwardSource? = null) {
        val members = if (Wire.isGroupCid(cid)) freshMembers() else null
        val fields = StickerForwardRules.mineFields(s, w, h)
        writeLooseMessage(fields, StickerGifRules.OLD_BUILD_MARKER, StickerGifRules.stickerLabel(s.emoji), ref, clientId, tap,
            forwarded, fwdSource)
        val msg = convRef.collection("messages").document(clientId)
        try {
            val (cipher, meta) = StickerForwardRules.sealPng(png, members,
                { p, ms -> crypto.sealBytesForGroup(p, ms, cid) }, { p -> crypto.encryptBytes(cid, p) })
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

    /**
     * A forwarded sticker (iOS StickerSend.forward): a built-in one again by name; one somebody made
     * as its PNG, opened from the source chat by the caller and sealed again for this one. Written
     * now, a failure thrown to the forward sheet. The chat list says "😂 Sticker" with no mark (iOS).
     */
    suspend fun forwardSticker(s: StickerRef, png: ByteArray?, width: Double?, height: Double?, forwarded: Boolean,
                               fwdSource: Message.ForwardSource?, clientId: String) {
        val tap = System.currentTimeMillis().toDouble()
        if (StickerForwardRules.needsSourcePng(s)) {
            writeMineSticker(s, png ?: error("sticker not readable"), width ?: 512.0, height ?: 512.0, null, clientId, tap, forwarded, fwdSource)
            return
        }
        val fields = StickerForwardRules.packFields(s)
        writeLooseMessage(fields, s.emoji.ifEmpty { StickerGifRules.OLD_BUILD_MARKER },
            StickerGifRules.stickerLabel(s.emoji), null, clientId, tap, forwarded, fwdSource)
    }

    /** A forwarded GIF (iOS forwardMessage): its caption sealed along, "↪ GIF" in the list when forwarded. */
    suspend fun forwardGif(g: StickerGifRules.Gif, caption: String, forwarded: Boolean,
                           fwdSource: Message.ForwardSource?, clientId: String) {
        val t = caption.trim()
        writeLooseMessage(hashMapOf("type" to "gif", "imageUrl" to g.url, "width" to g.width, "height" to g.height),
            t.ifEmpty { null }, ForwardMark.markIf(forwarded, "GIF"), null, clientId, System.currentTimeMillis().toDouble(),
            forwarded, fwdSource)
    }

    /** iOS ChatService.sendGif: the public GIPHY link and its size; `text` is the caption sealed like
     *  a text (empty when none, "Add Caption" 2026-11-21); the list says "GIF" either way. */
    /** A poll option's picture, stored like a photo in this chat (MediaSender.pollImage). */
    suspend fun uploadPollImage(jpeg: ByteArray): PollImage =
        // This phone already has the picture: seed the cache so the bubble draws it at once (iOS).
        sender.pollImage(jpeg).also { media?.store(it.url, jpeg) }

    fun sendGif(g: StickerGifRules.Gif, reply: Message?, caption: String = "") {
        val tap = System.currentTimeMillis().toDouble()
        val clientId = UUID.randomUUID().toString().uppercase()
        val ref = reply?.let { ReplyQuoteRules.ref(it) }
        // The caption at most the photo caption's length (iOS Limits.captionMaxChars), plain words.
        val words = caption.trim().take(com.fariin.app.media.MediaRules.CAPTION_CHARS)
        pending[clientId] = Message(
            id = clientId, authorId = me, text = words, type = "gif", createdAtMillis = tap, hasServerTime = false,
            clientTs = tap, clientId = clientId, sendState = Message.SendState.SENDING, replyTo = ref,
            imageUrl = g.url, width = g.width, height = g.height,
        )
        rebuild()
        runMediaSend(clientId) {
            writeLooseMessage(hashMapOf("type" to "gif", "imageUrl" to g.url, "width" to g.width, "height" to g.height),
                words.ifEmpty { null }, "GIF", ref, clientId, tap)
        }
    }

    /** One message whose words are not the preview (a sticker, a GIF): the fields, the sealed
     *  [text] (null = empty), the clear [label] for the chat list, the badge for everyone else. */
    private suspend fun writeLooseMessage(fields: Map<String, Any>, text: String?, label: String,
                                          reply: Message.ReplyRef?, clientId: String, clientTs: Double,
                                          forwarded: Boolean = false, fwdSource: Message.ForwardSource? = null) {
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
        // Where it came from (iOS sealedForwardSource): a failed seal drops only `fwd`.
        if (forwarded) msg.putAll(StickerForwardRules.forwardFields(fwdSource) { seal(it, members) })
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
    /**
     * Photos of this chat written down by [com.fariin.app.local.PhotoOutbox] and not sent yet (the app
     * was closed before they went): sent again under the same clientId, unless the server or
     * Firestore's own queue already has a message with it.
     */
    fun resumeOutbox() {
        for (e in com.fariin.app.local.FileOutbox.all(me)) if (e.cid == cid && !pending.containsKey(e.clientId)) resumeFile(e)
        for (e in com.fariin.app.local.PhotoOutbox.all(me)) {
            if (e.cid != cid || pending.containsKey(e.clientId)) continue
            val f = File(e.path)
            if (!f.exists() || f.length() == 0L) { com.fariin.app.local.PhotoOutbox.remove(me, e.clientId); continue }
            val video = e.kind == "video"
            val voice = e.kind == "voice"
            if (e.kind == "album") { resumeAlbum(e); continue }
            if (video && e.poster?.let { File(it).exists() } != true) { com.fariin.app.local.PhotoOutbox.remove(me, e.clientId); continue }
            // The reply it was sent under, kept across the restart.
            val ref = if (e.replyId != null && e.replyAuthor != null) Message.ReplyRef(e.replyId, e.replyAuthor, e.replyText ?: "") else null
            if (voice && ref != null) voiceReplies[e.clientId] = ref
            pending[e.clientId] = Message(
                id = e.clientId, authorId = me, text = e.caption, type = if (voice) "audio" else if (video) "video" else "image", createdAtMillis = e.ts, hasServerTime = false,
                clientTs = e.ts, clientId = e.clientId, sendState = Message.SendState.SENDING, viewOnce = e.viewOnce, replyTo = ref,
                width = e.width.toDouble(), height = e.height.toDouble(), thumb = if (e.viewOnce) null else e.thumb,
                localPath = if (video) e.poster else e.path, duration = if (video || voice) e.duration else null,
                waveform = if (voice && !e.viewOnce) e.wave else emptyList(),
            )
            localFiles[e.clientId] = if (video) e.poster!! else e.path
            rebuild()
            val work: suspend () -> Unit = {
                val found = try {
                    // My own doc only (H-56): someone else's message with the same clientId is not my send.
                    val q = convRef.collection("messages").whereEqualTo("clientId", e.clientId).whereEqualTo("authorId", me).limit(1)
                    q.get(Source.CACHE).await().documents.firstOrNull() ?: q.get(Source.SERVER).await().documents.firstOrNull()
                } catch (_: Exception) { null }
                // H-28: a clip whose message went out before the kill with its upload unfinished (the
                // server says so): that half message is taken down and the clip sent again as one new
                // message. Before, the stuck sweep deleted it after 30 minutes and the clip was lost.
                val half = video && found != null && found.getBoolean("uploading") == true &&
                    runCatching { found.reference.get(Source.SERVER).await().getBoolean("uploading") == true }.getOrDefault(false)
                if (half) { takenDown.add(found!!.id); cancelAnnounced(found.id) }
                val landed = found != null && !half
                if (!landed && voice) {
                    voiceNow(com.fariin.app.media.VoiceRecorder.Result(f, e.duration, e.wave, e.viewOnce), e.clientId, e.ts)
                } else if (!landed && video) {
                    val v = com.fariin.app.media.VideoPrep.describe(f, File(e.poster!!).readBytes()) ?: error("clip not readable")
                    sender.video(v, e.caption, false, e.clientId, e.ts, reply = ref)
                } else if (!landed) {
                    val prep = PhotoPrep(f.readBytes(), e.width, e.height, e.blurhash, e.thumb)
                    sender.image(prep, e.caption, e.viewOnce, false, e.clientId, e.ts, reply = ref)
                }
                com.fariin.app.local.PhotoOutbox.remove(me, e.clientId)
                // Sent: a view-once photo's plain copy goes (the voice note's own send deletes its file).
                if (e.viewOnce && !voice && !video) f.delete()
            }
            // Already going (the start-up drain's quiet copy of this chat): this chat waits on that
            // send and redraws the bubble when it ends.
            if (!claimResume(e.clientId)) { awaitResume(e.clientId, work) { photoQueued(e.clientId) }; continue }
            resumeSend(e.clientId, work)
        }
    }

    private fun photoQueued(clientId: String) = com.fariin.app.local.PhotoOutbox.all(me).any { it.clientId == clientId }

    // ------------------------------------------------------------------ files, songs, playlists across a kill (FileOutbox)

    /** Writes down a file, song or playlist send before it starts ([preview]: its tile or cover, kept beside it). */
    private fun keepFile(kind: String, clientId: String, tap: Double, locals: List<File>, names: List<String>, preview: ByteArray?,
                         title: String = "", caption: String = "", renames: List<Pair<String?, String?>> = emptyList(),
                         ref: Message.ReplyRef? = null) {
        val dir = media?.localDir() ?: return
        val tile = preview?.takeIf { it.isNotEmpty() }?.let { b -> File(dir, "fprev-$clientId.jpg").also { it.writeBytes(b) }.absolutePath }
        com.fariin.app.local.FileOutbox.add(me, com.fariin.app.local.FileOutbox.Entry(cid, clientId, tap, kind, locals.map { it.absolutePath }, names, tile,
            title, caption, renames, ref?.id, ref?.authorId, ref?.text))
    }

    /** The message id and its sealed copies, written down once, just before the message write. */
    private fun keepSealed(clientId: String, messageId: String, ciphers: List<ByteArray>,
                           songs: List<MusicRules.SealedSong>, listName: String, urls: List<String?>) {
        val cur = com.fariin.app.local.FileOutbox.get(me, clientId) ?: return
        // A quiet chat (the start-up drain) has no media: the copies go beside the file's own.
        val dir = media?.localDir() ?: cur.paths.firstOrNull()?.let { File(it).parentFile } ?: return
        val sealed = if (cur.messageId == messageId && cur.sealed.size == ciphers.size) cur.sealed
            else ciphers.mapIndexed { i, c -> File(dir, "seal-$clientId-$i").also { it.writeBytes(c) }.absolutePath }
        com.fariin.app.local.FileOutbox.update(me, clientId) { it.copy(messageId = messageId, sealed = sealed, songs = songs, listNameSealed = listName, urls = urls) }
    }

    private fun fileSave(clientId: String): (String, ByteArray) -> Unit =
        { id, c -> keepSealed(clientId, id, listOf(c), emptyList(), "", emptyList()) }

    private fun playlistSave(clientId: String): (MediaSender.PlaylistUpload) -> Unit =
        { j -> j.messageId?.let { keepSealed(clientId, it, j.ciphers, j.list, j.listNameSealed, j.urls.toList()) } }

    /**
     * A file, song or playlist from [com.fariin.app.local.FileOutbox] (the app was closed on it, iOS resumePendingUploads):
     * not written yet, it is sent again under the same clientId; written, only what is missing goes
     * up (a playlist's missing songs, MusicRules.songsToUpload) and the same message is completed.
     * One that can no longer be read stays a failed bubble with Delete.
     */
    private fun resumeFile(e: com.fariin.app.local.FileOutbox.Entry) {
        val ref = if (e.replyId != null && e.replyAuthor != null) Message.ReplyRef(e.replyId, e.replyAuthor, e.replyText ?: "") else null
        val playlist = e.kind == "playlist"
        val ok = com.fariin.app.local.FileOutbox.readable(e) { File(it).length() }
        pending[e.clientId] = Message(
            id = e.clientId, authorId = me, text = e.caption, type = "file", createdAtMillis = e.ts, hasServerTime = false,
            clientTs = e.ts, clientId = e.clientId, sendState = if (ok) Message.SendState.SENDING else Message.SendState.FAILED, replyTo = ref,
            fileName = e.names.firstOrNull(), fileSize = e.paths.firstOrNull()?.let { File(it).length() },
            localPath = if (playlist) null else e.paths.firstOrNull(),
            tracks = if (!playlist) null else e.names.mapIndexed { i, n -> MusicRules.Track(e.paths.getOrNull(i) ?: "", null, n, File(e.paths.getOrNull(i) ?: "").length(),
                e.renames.getOrNull(i)?.first, e.renames.getOrNull(i)?.second) },
            listName = if (playlist) e.title else null,
        )
        rebuild()
        // Its message written and its bytes gone: the server's copy shows failed (Delete). The entry
        // stays as the mark that this phone started it (FileOutbox.failedHere); Delete removes it.
        if (!ok) { if (e.messageId != null) { pending.remove(e.clientId); rebuild() }; return }
        val work: suspend () -> Unit = {
            val cur = com.fariin.app.local.FileOutbox.get(me, e.clientId) ?: e
            // Written before the kill? Asked by id: this phone's cache first, then the server.
            val written = cur.messageId?.let { id ->
                val doc = convRef.collection("messages").document(id)
                val cached = try { doc.get(Source.CACHE).await().exists() } catch (_: Exception) { false }
                cached || doc.get(Source.SERVER).await().exists()
            } ?: false
            val preview = cur.preview?.let { p -> File(p).takeIf { it.length() > 0 }?.readBytes() }
            val songs = cur.names.mapIndexed { i, n -> FilePrep(n, File(cur.paths[i]).readBytes(), if (playlist) null else preview) }
            if (playlist) {
                val job = MediaSender.PlaylistUpload()
                if (written) {
                    job.messageId = cur.messageId
                    job.ciphers = cur.sealed.map { File(it).readBytes() }
                    job.list = cur.songs
                    job.listNameSealed = cur.listNameSealed
                    job.urls = cur.urls.toTypedArray()
                }
                sender.playlist(songs, cur.title, cur.caption, preview, e.clientId, e.ts, reply = ref,
                    renames = cur.renames, job = job, save = playlistSave(e.clientId))
            } else {
                val job = if (written) MediaSender.FileUpload().also { it.messageId = cur.messageId; it.cipher = File(cur.sealed[0]).readBytes() } else null
                sender.file(songs[0], false, e.clientId, e.ts, reply = ref, rename = cur.renames.firstOrNull(), job = job, save = fileSave(e.clientId))
            }
            playlistSends.remove(e.clientId)
            com.fariin.app.local.FileOutbox.remove(me, e.clientId)
        }
        // A failure leaves it red with a Resend that runs this again (the entry stays).
        playlistSends += e.clientId
        if (!claimResume(e.clientId)) { awaitResume(e.clientId, work) { com.fariin.app.local.FileOutbox.get(me, e.clientId) != null }; return }
        retryMedia[e.clientId] = work
        resumeSend(e.clientId, work)
    }

    /**
     * One resume per outbox entry at a time: the start-up drain and an opened chat can both reach
     * the same entry, on any thread. The add is the check, so only one of them gets it.
     */
    private fun claimResume(clientId: String): Boolean =
        !com.fariin.app.media.MediaSend.isInFlight(clientId) && resuming.claim(clientId)

    /** Runs a claimed resume; the claim goes when that send ends (sent, failed or cancelled). */
    private fun resumeSend(clientId: String, work: suspend () -> Unit) {
        runMediaSend(clientId, work).invokeOnCompletion { resuming.release(clientId) }
    }

    /**
     * The claim was taken (the start-up drain's quiet copy of this chat is sending it): wait for that
     * send to end, then redraw from the outbox, so the bubble never spins with nothing watching.
     * [queued] true: it failed, red with a Resend that runs [work] here. False: it went (or was
     * dropped), so the pending copy goes and the server's copy shows.
     */
    private fun awaitResume(clientId: String, work: suspend () -> Unit, queued: () -> Boolean) {
        retryMedia[clientId] = work
        val settle: () -> Unit = {
            SendScope.launch(Dispatchers.IO) {
                if (queued()) {
                    if (clientId in playlistSends) failedMedia += clientId
                    pending[clientId]?.let { pending[clientId] = it.copy(sendState = Message.SendState.FAILED) }
                } else {
                    retryMedia.remove(clientId); playlistSends.remove(clientId)
                    pending.remove(clientId)
                }
                rebuild()
            }
        }
        if (resuming.onRelease(clientId, settle)) return
        // No resume claim: a send of it runs in this process anyway (or one that failed is still filed).
        val job = com.fariin.app.media.MediaSend.job(clientId)
        if (job != null) job.invokeOnCompletion { settle() } else settle()
    }

    /** An album from [com.fariin.app.local.PhotoOutbox]: its tiles drawn again and sent under the same clientId. */
    private fun resumeAlbum(e: com.fariin.app.local.PhotoOutbox.Entry) {
        val ok = e.items.size >= 2 && e.items.all { t -> File(t.path).length() > 0 && (t.clip == null || File(t.clip).length() > 0) }
        if (!ok) { com.fariin.app.local.PhotoOutbox.remove(me, e.clientId); return }
        val ref = if (e.replyId != null && e.replyAuthor != null) Message.ReplyRef(e.replyId, e.replyAuthor, e.replyText ?: "") else null
        pending[e.clientId] = Message(
            id = e.clientId, authorId = me, text = e.caption, type = "album", createdAtMillis = e.ts, hasServerTime = false,
            clientTs = e.ts, clientId = e.clientId, sendState = Message.SendState.SENDING, replyTo = ref,
            album = e.items.map { t ->
                Message.AlbumItem(t.path, null, t.width.toDouble(), t.height.toDouble(), videoUrl = t.clip,
                    duration = if (t.clip != null) t.duration else null, localVideo = t.clip != null)
            },
        )
        rebuild()
        val resume: suspend () -> Unit = {
            val landed = try {
                // My own doc only (H-56): someone else's message with the same clientId is not my send.
                val q = convRef.collection("messages").whereEqualTo("clientId", e.clientId).whereEqualTo("authorId", me).limit(1)
                !q.get(Source.CACHE).await().isEmpty || !q.get(Source.SERVER).await().isEmpty
            } catch (_: Exception) { false }
            if (!landed) {
                val parts = e.items.map { t ->
                    val pic = PhotoPrep(File(t.path).readBytes(), t.width, t.height, t.blurhash, t.thumb)
                    if (t.clip != null) MediaSender.AlbumPart.Video(com.fariin.app.media.VideoPrep.describe(File(t.clip), pic.jpeg) ?: error("clip not readable"))
                    else MediaSender.AlbumPart.Photo(pic)
                }
                val work: suspend () -> Unit = { sender.album(parts, e.caption, false, e.clientId, e.ts, reply = ref); com.fariin.app.local.PhotoOutbox.remove(me, e.clientId) }
                retryMedia[e.clientId] = work
                work()
            }
            com.fariin.app.local.PhotoOutbox.remove(me, e.clientId)
        }
        if (!claimResume(e.clientId)) { awaitResume(e.clientId, resume) { photoQueued(e.clientId) }; return }
        resumeSend(e.clientId, resume)
    }

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
        // Written down first, so a kill cannot lose it (PhotoOutbox; sent again as a picked photo is).
        com.fariin.app.local.PhotoOutbox.add(me, com.fariin.app.local.PhotoOutbox.Entry(cid, clientId, tap, local.absolutePath,
            prep.width, prep.height, prep.blurhash, prep.thumbB64, "", false))
        val job = scope.launch(Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.LAZY) {
            sendingStart(clientId)
            try {
                photoNow(prep, clientId, tap)
                com.fariin.app.local.PhotoOutbox.remove(me, clientId)
                com.fariin.app.media.MediaSend.finish(clientId)
            } catch (e: Exception) {
                if (mediaSendEnded(clientId, e)) { com.fariin.app.local.PhotoOutbox.remove(me, clientId); return@launch }
                if (SendQueue.isPermanent(e)) com.fariin.app.local.PhotoOutbox.remove(me, clientId)
                retryPhoto[clientId] = prep
                pending[clientId]?.let { pending[clientId] = it.copy(sendState = Message.SendState.FAILED) }
                rebuild()
            } finally { sendingEnd(clientId) }
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
    fun sendVoice(r: VoiceRecorder.Result) = sendVoiceRef(r, null)

    /** A voice note recorded while replying carries the reply, as iOS sendAudio. */
    fun sendVoice(r: VoiceRecorder.Result, reply: Message?) = sendVoiceRef(r, reply?.let { ReplyQuoteRules.ref(it) })

    private fun sendVoiceRef(r: VoiceRecorder.Result, ref: Message.ReplyRef?) {
        val tap = System.currentTimeMillis().toDouble()
        val clientId = UUID.randomUUID().toString().uppercase()
        localFiles[clientId] = r.file.absolutePath
        if (ref != null) voiceReplies[clientId] = ref
        pending[clientId] = Message(
            id = clientId, authorId = me, text = "", type = "audio", createdAtMillis = tap, hasServerTime = false,
            clientTs = tap, clientId = clientId, sendState = Message.SendState.SENDING, replyTo = ref,
            duration = r.duration, waveform = if (r.viewOnce) emptyList() else r.waveform, localPath = r.file.absolutePath,
            viewOnce = r.viewOnce,
        )
        rebuild()
        // Written down first, so a kill (offline, say) cannot lose the note (PhotoOutbox).
        com.fariin.app.local.PhotoOutbox.add(me, com.fariin.app.local.PhotoOutbox.Entry(cid, clientId, tap, r.file.absolutePath,
            0, 0, null, null, "", r.viewOnce, kind = "voice", duration = r.duration, wave = r.waveform,
            replyId = ref?.id, replyAuthor = ref?.authorId, replyText = ref?.text))
        scope.launch(Dispatchers.IO) {
            try {
                voiceNow(r, clientId, tap)
                com.fariin.app.local.PhotoOutbox.remove(me, clientId)
            } catch (e: Exception) {
                if (SendQueue.isPermanent(e)) com.fariin.app.local.PhotoOutbox.remove(me, clientId)
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
        voiceReplies[clientId]?.let { fields["replyTo"] = replyMap(it, seal(it.text, members)) }
        val batch = db.batch()
        batch.set(msgRef, fields)
        batch.update(convRef, MediaMessages.convUpdate(me, com.fariin.app.media.VoiceRecordRules.preview(r.duration, r.viewOnce), members ?: listOf(other),
            FieldValue.serverTimestamp(), FieldValue.increment(1)))
        batch.commit().await()
        com.fariin.app.media.ChatSounds.sent()
        if (r.viewOnce) r.file.delete()
        voiceReplies.remove(clientId)
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
        // Cloudflare R2 while config/features.r2Media is on (iOS 9bbac4e65): signed by mediaSign;
        // Storage stays the way if R2 refuses, so a send never fails on it.
        if (com.fariin.app.data.AppFeatures.r2Media && com.fariin.app.media.R2Media.allowed(path) && cipher.size < MediaRules.STORAGE_CHAT_LIMIT) {
            com.fariin.app.media.R2Media.put(path, cipher)?.let { link -> return link }
        }

        val ref = FirebaseStorage.getInstance().reference.child(path)
        val task = ref.putBytes(cipher, StorageMetadata.Builder().setContentType("application/octet-stream").build())
        // Cancel stops the transfer itself, not only the wait for it.
        try { task.await() } catch (e: kotlinx.coroutines.CancellationException) { task.cancel(); throw e }
        return ref.downloadUrl.await().toString()
    }

    /** iOS freshGroupMembers: the member list from the SERVER; the cached one when offline. */
    private suspend fun freshMembers(): List<String> {
        heldMembers()?.let { return it }
        return serverMembers()
    }

    /**
     * The members as the chat's own live listener has them from the server. A server read of a
     * document that is already listened to is answered from that same copy, only after waiting its
     * turn behind every change the listener is still taking in: 1 to 4.5 s per send in a busy group
     * on a cheap phone. Null while the listener has no server copy (then the server is asked).
     */
    private fun heldMembers(): List<String>? {
        val snap = latestConv ?: return null
        if (convReg == null || snap.metadata.isFromCache || !snap.exists()) return null
        if (snap.bool("e2ee") == false) CryptoHub.notePlainGroup(cid)
        return (snap.get("users") as? List<*>)?.filterIsInstance<String>()?.takeIf { it.isNotEmpty() }
    }

    private suspend fun serverMembers(): List<String> = try {
        val snap = convRef.get(Source.SERVER).await()
        if (snap.bool("e2ee") == false) CryptoHub.notePlainGroup(cid)
        (snap.get("users") as? List<*>)?.filterIsInstance<String>()
    } catch (_: Exception) { null } ?: memberUids()

    /** clientId -> Plus send effect for that text; dropped once it is on the server. */
    private val fxAtSend = java.util.concurrent.ConcurrentHashMap<String, String>()

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
                                   quote: String? = null, fwdSource: Message.ForwardSource? = null, fmt: String? = null) {
        val other = Wire.otherUid(cid, me)
        val cipher = crypto.encryptForConversation(cid, t)
        // A quote sent with no words of mine: the list says "Quoted post" (sealed like any preview), not a blank line.
        // A forward's line starts with the arrow mark (ForwardMark), sealed with it.
        val lastCipher = when {
            forwarded -> crypto.encryptForConversation(cid, ForwardMark.mark(if (t.isEmpty() && quote != null) QUOTED_POST else t))
            t.isEmpty() && quote != null -> crypto.encryptForConversation(cid, QUOTED_POST)
            else -> cipher
        }
        val local = CryptoHub.listedConversation(cid)
        val answering = local != null && !local.isGroup && local.startedBy.isNotEmpty() && !local.accepted &&
            local.lastSender.isNotEmpty() && local.lastSender != me
        // The knock: my one first message into a request I opened (iOS `knocking`). The rules take
        // it as short text only and hold it to the day's budget, which the app counts first.
        val knocking = if (local != null) isKnock(local.startedBy, local.accepted, local.lastSender)
            else try {
                val d = convRef.get().await()
                isKnock(d.str("startedBy") ?: "", d.bool("accepted") ?: false, d.str("lastSender") ?: "")
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
        // Bold runs sealed like the words (iOS bold-everywhere); never written unless the seal worked.
        if (fmt != null && t.isNotEmpty()) runCatching { crypto.encryptForConversation(cid, fmt) }.getOrNull()
            ?.takeIf { it.startsWith(Wire.ENC1) }?.let { msg["fmt"] = it }
        if (forwarded) msg["forwarded"] = true
        // A story mention card (iOS ChatService: the plain story id and thumbnail).
        // The channel a forwarded post came from, sealed like the text (iOS sealedFwdChannel).
        fwdChannelJson?.let { j -> runCatching { crypto.encryptForConversation(cid, j) }.getOrNull()?.let { msg["fwdChannel"] = it } }
        // The group or channel it came from (iOS sealedForwardSource); a failed seal drops only this.
        if (forwarded) fwdSource?.let { s -> runCatching { crypto.encryptForConversation(cid, s.json()) }.getOrNull()?.let { msg["fwd"] = it } }
        if (storyMention != null) msg["storyMention"] = mapOf("id" to storyMention.id, "thumb" to storyMention.thumbUrl)
        // A quoted channel post: only the server snapshot's id, in the clear (iOS ChatService).
        if (quote != null) msg["quote"] = QuoteStore.wire(quote)
        if (countsAsUnread && storyMention == null) putBubbleStyle(msg, clientId)
        if (!forwarded && quote == null && storyMention == null) fxAtSend[clientId]?.let { msg["fx"] = it }
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
    private fun replyMap(r: Message.ReplyRef, sealed: String): Map<String, Any> = ReplyQuoteRules.wire(r, sealed)

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
            val start = snap.ts("windowStart")?.toDate()?.time
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
        val lastCipher = when {
            forwarded -> crypto.sealForGroup(ForwardMark.mark(if (t.isEmpty() && quote != null) QUOTED_POST else t), members, cid)
            t.isEmpty() && quote != null -> crypto.sealForGroup(QUOTED_POST, members, cid)
            else -> cipher
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
        if (quote != null) msg["quote"] = QuoteStore.wire(quote)   // a quoted channel post (iOS sendGroupText)
        if (!forwarded && quote == null) fxAtSend[clientId]?.let { msg["fx"] = it }
        // Bold runs (TextFormat), sealed like the text: plain in a plain channel.
        if (fmt != null && t.isNotEmpty()) msg["fmt"] = crypto.sealForGroup(fmt, members, cid)
        // No bubble style in a group or channel (iOS spec 2026-11-10: styles are 1:1 only).
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
        return try { convRef.get().await().bool("channel") == true } catch (_: Exception) { false }
    }

    /** ChatService.editMessage: re-sealed, the replaced text appended to `edits` (at most 10). */
    suspend fun editMessage(m: Message, newText: String, fmt: String? = null): Boolean {
        val t = newText.trim()
        // A photo/video's caption may be cleared (iOS c0ee63879): stored as "" unsealed, like a caption-less send.
        val media = MessageRules.captionable(m)
        if (t.isEmpty() && !media) return false
        return try {
            val members = if (Wire.isGroupCid(cid)) freshMembers() else null
            val cipher = if (t.isEmpty()) "" else seal(t, members)
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
            val sealedFmt = fmt?.let { f -> runCatching { seal(f, members) }.getOrNull() }
                ?.takeIf { members != null || it.startsWith(Wire.ENC1) }
            if (sealedFmt != null) update["fmt"] = sealedFmt
            else if (data.containsKey("fmt")) update["fmt"] = FieldValue.delete()
            // Not in a channel post (owner 2026-11-06): every follower reads the post, so the old
            // versions stay off it; the server keeps them for the owner and admins.
            if (size() <= 700_000 && !isChannel()) update["edits"] = edits
            // The list still shows the old words when this was the newest message: the line goes in
            // the SAME write, so an edit made offline and the app killed cannot leave the old words
            // there (it was a second write after the server's ack; Oppo 2026-11-20, as Delete).
            val sum = hashMapOf<String, Any>("lastMessage" to cipher)
            if (Wire.isGroupCid(cid) && !cipher.startsWith("enc")) sum["lastPlainTag"] = Conversation.plainTag(cipher)
            val batch = db.batch().update(ref, update)
            // A media caption edit leaves the line alone: it shows the media's label, never the caption.
            val shownNewest = !media && isNewestShown(m.id) && !isChannelChat() && !isChannel()   // a channel line is the server's
            if (shownNewest) batch.update(convRef, sum)
            batch.commit().await()
            if (!shownNewest && !media) {
                val newest = convRef.collection("messages").orderBy("createdAt", Query.Direction.DESCENDING).limit(1).get().await()
                if (newest.documents.firstOrNull()?.id == m.id) try { convRef.update(sum).await() } catch (_: Exception) {}
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
            if (!snap.exists()) {
                // iOS: already gone is success, but still clean up a pin or summary the doc left behind.
                if (m.id in PinRules.pinnedIds(_state.value.conv)) try { unpin(m.id) } catch (_: Exception) {}
                rewriteSummaryAfterDelete()
                return true
            }
            val data = snap.data ?: emptyMap()
            val blobs = buildList {
                for (k in listOf("imageUrl", "videoUrl", "thumbUrl", "audioUrl", "fileUrl")) (data[k] as? String)?.let(::add)
                (data["album"] as? List<*>)?.forEach { ((it as? Map<*, *>)?.get("imageUrl") as? String)?.let(::add) }
                // The link card's sealed picture goes too (iOS storageUrls).
                ((data["linkPreview"] as? Map<*, *>)?.get("imageUrl") as? String)?.let(::add)
            }.filter { it.startsWith("https://") || it.startsWith("gs://") }
            val wasNewest = isNewestShown(m.id)
            var marked = false
            if (data["deleted"] != true) {
                val strip = hashMapOf<String, Any>("deleted" to true, "text" to "", "type" to "text", "enc" to FieldValue.delete())
                for (k in TOMBSTONE_STRIP) strip[k] = FieldValue.delete()
                if (data["authorId"] as? String != me) strip["deletedBy"] = me
                // The newest message (1:1 or group): the chat list line changes in the SAME write. Done after
                // the server's ack only, an app killed offline (or before the ack) kept the deleted
                // words as the line for good (Oppo 2026-11-20). The full rewrite below still runs.
                val batch = db.batch().update(ref, strip)
                if (!isChannelChat() && !isChannel() && isNewestShown(m.id)) {
                    batch.update(convRef, hashMapOf<String, Any>("lastMessage" to "This message was deleted",
                        "lastSender" to (data["authorId"] as? String ?: me),
                        "lastImageUrl" to FieldValue.delete(), "lastImageEnc" to FieldValue.delete(), "lastImages" to FieldValue.delete()))
                }
                // Refused with the line (a group's rules), the tombstone alone, as before.
                marked = try { batch.commit().await(); true } catch (_: Exception) {
                    try { ref.update(strip).await(); true } catch (_: Exception) { false }
                }
            }
            for (u in blobs) try { FirebaseStorage.getInstance().getReferenceFromUrl(u).delete().await() } catch (_: Exception) {}
            // R2 files (iOS): removed through mediaSign's delete, author only, while the doc still names them.
            if (data["authorId"] as? String == me) {
                val r2 = buildList {
                    for (k in listOf("imageUrl", "videoUrl", "thumbUrl", "audioUrl", "fileUrl")) (data[k] as? String)?.let(::add)
                    (data["album"] as? List<*>)?.forEach { a -> (a as? Map<*, *>)?.let { m -> (m["imageUrl"] as? String)?.let(::add); (m["videoUrl"] as? String)?.let(::add) } }
                }.filter { com.fariin.app.media.R2Media.isR2(it) }
                for (u in r2) com.fariin.app.media.R2Media.delete(u)
            }
            if (!marked) ref.delete().await()
            // iOS deleteMessage -> removePinnedMessage: a pin must not outlive what it points at.
            if (m.id in PinRules.pinnedIds(_state.value.conv)) try { unpin(m.id) } catch (_: Exception) {}
            // Only when it was the newest: an older message is not the chat line (audit H-150).
            if (wasNewest) rewriteSummaryAfterDelete()
            clearReactionPreviewIfFrom(data["reactions"] as? Map<*, *>)
            true
        } catch (_: Exception) { false }
    }

    /**
     * iOS clearReactionPreviewIfFrom: the chat list's "reacted to" line came from a reaction on the
     * message just deleted, so it goes with it. Best effort.
     */
    private suspend fun clearReactionPreviewIfFrom(reactions: Map<*, *>?) {
        if (reactions.isNullOrEmpty()) return
        try {
            val d = convRef.get().await()
            val by = d.str("lastReactionBy") ?: return
            val enc = d.str("lastReactionEnc") ?: return
            if (reactions[by] as? String != enc) return
            convRef.update(mapOf<String, Any>(
                "lastReactionEnc" to FieldValue.delete(), "lastReactionBy" to FieldValue.delete(),
                "lastReactionToAuthor" to FieldValue.delete(), "lastReactionAt" to FieldValue.delete(),
                "lastReactionToEnc" to FieldValue.delete(), "lastReactionToImages" to FieldValue.delete(),
            )).await()
        } catch (_: Exception) {}
    }

    /** This message is the newest one on screen (calls and system lines count, as the summary's query does). */
    private fun isNewestShown(id: String): Boolean =
        _state.value.messages.maxByOrNull { it.createdAtMillis }?.id == id

    private suspend fun rewriteSummaryAfterDelete() {
        // A channel's preview is the server's (CHANNEL-SYNC-SPEC 3-4, iOS serverOwnsPreview; audit A7).
        if (isChannelChat() || isChannel()) return
        try { writeSummaryGuarded {
            val group = Wire.isGroupCid(cid)
            // From the server only (iOS summaryOfNewest): an offline answer from a cache holding none
            // of this chat's messages looked like "nothing left" and wiped the line. A failed read
            // writes nothing.
            val newest = convRef.collection("messages").orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(if (group) 25 else 1).get(Source.SERVER).await().documents
            val d = (if (group) newest.firstOrNull { it.bool("deleted") != true } else newest.firstOrNull())?.data
            // A group whose 25 newest are all deleted is not proof the chat is empty: keep the line (iOS).
            if (d == null && group && newest.isNotEmpty()) return@writeSummaryGuarded null
            val update = hashMapOf<String, Any>()
            if (d == null) {
                update["lastMessage"] = ""; update["lastSender"] = ""
                update["lastImageUrl"] = FieldValue.delete(); update["lastImageEnc"] = FieldValue.delete()
                update["lastImages"] = FieldValue.delete()
            } else {
                val gone = d["deleted"] == true
                // A forward's clear label keeps its arrow mark; sealed words cannot be re-marked here.
                val label = if (gone) "This message was deleted" else summaryText(d)
                val line = if (!gone && d["forwarded"] == true && label.isNotEmpty() && !label.startsWith("enc")) ForwardMark.mark(label) else label
                update["lastMessage"] = line
                update["lastSender"] = d["authorId"] as? String ?: ""
                val kind = d["type"] as? String ?: ""
                if (group && !gone && kind != "system" && kind != "call" && line.isNotEmpty() && !line.startsWith("enc")) {
                    update["lastPlainTag"] = Conversation.plainTag(line)
                }
                // An album has no picture of its own: its first tiles are the line's thumbnails, as
                // its send wrote them (lastImageUrl/Enc from the first, lastImages up to three).
                val tiles = (if (gone) null else d["album"] as? List<*>)?.mapNotNull { t ->
                    val m = t as? Map<*, *> ?: return@mapNotNull null
                    val tu = m["imageUrl"] as? String ?: return@mapNotNull null
                    val te = m["enc"] as? Map<*, *> ?: return@mapNotNull null
                    mapOf("imageUrl" to tu, "enc" to te)
                }.orEmpty()
                // A video's picture is its poster (thumbUrl/thumbEnc), never imageUrl (iOS final-check 7).
                val video = kind == "video"
                val u = if (gone) null else if (video) d["thumbUrl"] as? String
                    else (d["imageUrl"] as? String ?: tiles.firstOrNull()?.get("imageUrl") as? String)
                val e = if (video) d["thumbEnc"] else if (d["imageUrl"] is String) d["enc"] else tiles.firstOrNull()?.get("enc")
                if (u != null && e is Map<*, *> && d["viewOnce"] != true) { update["lastImageUrl"] = u; update["lastImageEnc"] = e }
                else { update["lastImageUrl"] = FieldValue.delete(); update["lastImageEnc"] = FieldValue.delete() }
                if (tiles.isNotEmpty()) update["lastImages"] = tiles.take(3) else update["lastImages"] = FieldValue.delete()
            }
            update
        } } catch (_: Exception) {}
    }

    /**
     * iOS rewriteSummaryGuarded (audit H-150): write a recomputed chat line only if no message landed
     * while it was computed. The chat's `updatedAt` (every send stamps it) is read first and checked
     * again in a transaction; if it moved, the line is computed again (three tries). Offline, or a
     * failed transaction, falls back to the plain write. [compute] null = nothing to write.
     */
    private suspend fun writeSummaryGuarded(compute: suspend () -> Map<String, Any>?) {
        fun version(d: Map<String, Any?>?): String = when (val v = d?.get("updatedAt")) {
            is com.google.firebase.Timestamp -> "${v.seconds}.${v.nanoseconds}"
            is Number -> v.toString()
            else -> ""
        }
        repeat(3) {
            val before = try { convRef.get(Source.SERVER).await() } catch (_: Exception) { null }
            if (before == null) {
                compute()?.let { u -> try { convRef.update(u).await() } catch (_: Exception) {} }
                return
            }
            val seen = version(before.data)
            val update = compute() ?: return
            val wrote: Boolean? = try {
                db.runTransaction { t ->
                    if (version(t.get(convRef).data) != seen) false else { t.update(convRef, update); true }
                }.await()
            } catch (_: Exception) { null }
            when (wrote) {
                true -> return
                false -> {}   // a newer message landed: compute against it
                null -> { try { convRef.update(update).await() } catch (_: Exception) {}; return }
            }
        }
        // Lost three times: messages are still arriving, and each one set its own line.
    }

    /** iOS summaryText: the sealed words, else a label for the kind. */
    private fun summaryText(d: Map<String, Any?>): String {
        val text = d["text"] as? String ?: ""
        val type = d["type"] as? String ?: ""
        val once = d["viewOnce"] == true
        // The shared wallpaper notice reads as its short line in the list (iOS 4df24f5a5).
        if (type == "system" && text.endsWith(SharedWallpapers.SET_SUFFIX)) return "Wallpaper changed"
        if (type == "system" && text.endsWith(SharedWallpapers.REMOVED_SUFFIX)) return "Wallpaper removed"
        if (text.isNotEmpty() && (!once || type == "text" || type.isEmpty())) return text
        return when (type) {
            "image" -> if (once) "View-once photo" else "📷 Photo"
            "album" -> ((d["album"] as? List<*>)?.size ?: 0).let { if (it > 1) "📷 $it Photos" else "📷 Photo" }
            // As the send writes it (iOS final-check 7): the length when the message has one.
            "video" -> ((d["duration"] as? Number)?.toDouble() ?: 0.0).let { if (it > 0) com.fariin.app.media.MediaRules.videoPreview(it) else "🎥 Video" }
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
     *  draws in the bar (iOS pinnedPreviews). A tombstone, or a document the server says is not
     *  there, is kept as deleted (iOS pinnedGone), so the bar and See All leave it out. */
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
                        _pinned.update { it + (id to m) }
                    } else if (!d.metadata.isFromCache) {
                        // Proven gone: a stand-in marked deleted, so the bar stops saying "Tap to view".
                        _pinned.update { it + (id to Message(id = id, authorId = "", text = "", createdAtMillis = 0.0, deleted = true)) }
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
        kotlinx.coroutines.withContext(Dispatchers.Default) { readCorpus(limit) }.also { ChatSearch.seen["$me/$cid"] = it }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { emptyList() }

    /** My Clear Chat mark for this chat, from the chat's own document (0 when never cleared). */
    private fun clearedAtForMe(): Double =
        Conversation.millis((_state.value.conv["clearedAt"] as? Map<*, *>)?.get(me))

    /** The corpus this account last read for this chat, this run of the app (iOS loading audit). */
    fun seenCorpus(): List<ChatSearch.Item>? = ChatSearch.seen["$me/$cid"]

    private suspend fun readCorpus(limit: Int): List<ChatSearch.Item> {
        val snap = convRef.collection("messages")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(limit.toLong())
            .get().await()
        // Clear Chat (iOS loadChat): what the chat no longer shows, its search does not find.
        val clearedMs = maxOf(clearedAtForMe(), CryptoHub.listedConversation(cid)?.clearedAt?.get(me) ?: 0.0)
        val now = System.currentTimeMillis().toDouble()
        return snap.documents.mapNotNull { d ->
            val m = Message.fromFirestore(d.id, d.data ?: emptyMap(), cid) { raw, author -> crypto.decrypt(raw, cid, author) }
            when {
                clearedMs > 0 && m.createdAtMillis <= clearedMs -> null
                HiddenMessages.isHidden(m.id) -> null
                m.authorId != me && BlockList.hides(m.authorId, m.createdAtMillis) -> null
                m.text == Message.NOT_ENCRYPTED -> null
                // Ran out on the timer, the server has not deleted it yet: search never finds it.
                Disappearing.isExpired(m, now) -> null
                else -> ChatSearch.searchText(m)?.let { ChatSearch.Item(m.id, it, m.authorId, m.createdAtMillis, expiresAtMillis = m.expiresAtMillis) }
            }
        }
    }

    /** iOS isHiddenByBlock: a fetched message left out because I had its author blocked when it was sent. */
    fun isHiddenByBlock(id: String): Boolean {
        val d = synchronized(docsLock) { liveDocs[id] ?: olderDocs[id] } ?: return false
        val author = d.str("authorId") ?: return false
        return author != me && BlockList.hides(author, Conversation.millis(d.get("createdAt")))
    }

    /** iOS ensureLoaded: page older history until [id] is in the window (at most [maxPages] pages). */
    suspend fun ensureLoaded(id: String, maxPages: Int = 12): Boolean {
        repeat(maxPages) {
            if (_state.value.messages.any { it.id == id }) return true
            if (!_state.value.canLoadOlder) return false
            loadOlderPage()
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
        // An unsent one also leaves the send queue and drops what it was waiting to resend (iOS
        // deleteForMe on a failed message: queue entry, upload and bubble all go).
        m.clientId?.let { pending.remove(it); retryPhoto.remove(it); retryVoice.remove(it); voiceReplies.remove(it)
            if (m.sendState != null && !SendQueue.isInFlight(it)) { SendQueue.remove(me, it); retryMedia.remove(it); com.fariin.app.local.PhotoOutbox.remove(me, it); com.fariin.app.local.FileOutbox.remove(me, it) }
            // A failed media send whose message the server holds (still `uploading`): it goes for
            // both people, or the other phone would spin on it until the stuck sweep.
            if (failedMedia.remove(it) || (m.sendState == Message.SendState.FAILED && m.uploading && m.authorId == me && m.id != it && m.type == "file"))
                SendScope.launch(Dispatchers.IO) { cancelAnnounced(m.id) } }
        // An unsent text still on its way (offline, waiting for the server) is called off too, as
        // iOS cancelTextSend: a send not written yet stops before its write, and one Firestore already
        // holds is deleted, so the message deleted here never reaches the other phone.
        val id = m.clientId
        // Never one the server already has (hasServerTime): an offline edit also marks it pending.
        if (id != null && m.sendState != null && !m.hasServerTime && m.mediaKind == null && m.authorId == me) {
            SendQueue.remove(me, id)
            if (SendQueue.isInFlight(id)) cancelledTexts.add(id)
            SendScope.launch(Dispatchers.IO) {
                val held = runCatching { msgRef(m.id).get(Source.CACHE).await().exists() }.getOrDefault(false)
                if (held) cancelAnnounced(m.id)
            }
        }
        // The chat's newest message: the chat list shows the one before it from now on (HiddenLast).
        val shown = _state.value.messages.filter { !it.isSystem && !HiddenMessages.isHidden(it.id) }
        if (shown.maxByOrNull { it.createdAtMillis }?.id == m.id) {
            val before = shown.filter { it.id != m.id }.maxByOrNull { it.createdAtMillis }
            HiddenLast.set(context, cid, m.createdAtMillis, before?.let { MessageInfoRules.snippet(it) } ?: "")
        }
        HiddenMessages.hide(context, m.id)
        rebuild()
    }

    /**
     * iOS ThreadView reaction thumbs: the photo(s) a reaction was on, for the other person's chat list
     * (an album's first three tiles, a video's poster). The same url + envelope the message already
     * carries. Never view-once media (photo, video or voice): its picture must not reach the list.
     */
    private fun reactionImages(m: Message): List<Map<String, Any>> {
        if (m.viewOnce) return emptyList()
        fun row(url: String?, enc: EncMeta?): Map<String, Any>? =
            url?.takeIf { it.isNotEmpty() }?.let { u -> buildMap { put("url", u); enc?.let { put("enc", it.asMap()) } } }
        return when (m.mediaKind) {
            Message.MediaKind.ALBUM -> m.album.take(3).mapNotNull { row(it.imageUrl, it.enc) }
            Message.MediaKind.PHOTO -> listOfNotNull(row(m.imageUrl, m.enc))
            Message.MediaKind.VIDEO -> listOfNotNull(row(m.thumbUrl, m.thumbEnc))
            else -> emptyList()
        }
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
                if (cs.str("lastReactionBy") == me) convRef.update(mapOf(
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
            val snippet = MessageRules.reactionTarget(m).trim().take(60).takeIf { it.isNotEmpty() }
            try {
                convRef.update(mapOf(
                    "lastReactionEnc" to enc, "lastReactionBy" to me, "lastReactionToAuthor" to m.authorId,
                    "lastReactionAt" to FieldValue.serverTimestamp(),
                    "lastReactionToEnc" to (snippet?.let { seal(it, members) } ?: FieldValue.delete()),
                    "lastReactionToImages" to (reactionImages(m).takeIf { members == null && it.isNotEmpty() } ?: FieldValue.delete()),
                )).await()
            } catch (_: Exception) {}
            true
        } catch (_: Exception) { false }
    }

    /** An "on" was written this visit (typing or recording), so the next "off" must go out. */
    @Volatile private var typingSent = false

    /** Whether a typing/recording write is needed (QuietWrites.typingWrite); notes an "on". The
     *  composer says "off" when it opens and when it closes: with nothing on, those were two
     *  writes per visit, each a chat-list update (Pixel 5 trace 2026-11-15). */
    private fun quietTyping(on: Boolean): Boolean {
        val doc = (_state.value.conv["typing"] as? Map<*, *>)?.get(me)
        if (!QuietWrites.typingWrite(on, typingSent, doc)) return false
        typingSent = on
        return true
    }

    /** ChatService.setTyping: `typing.<me>` = "text-<seconds>" while typing, false after. */
    fun setTyping(on: Boolean) {
        // Privacy > Messages > Typing Indicators off: I send none (an "off" still goes out).
        if (on && !PrivacySettings.typingIndicators()) return
        // Only a group's members may write its doc (a channel visitor never does).
        if (Wire.isGroupCid(cid) && me !in ((_state.value.conv["users"] as? List<*>) ?: emptyList<Any>())) return
        if (!quietTyping(on)) return
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
        if (!quietTyping(on)) return
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
        scope.launch { markReadNow(readReceipts, skipIfRead = true) }
    }

    /** While a chat is open: the read mark at most once per 2.5 s (BusyChatRules, same rule on
     *  iOS); new messages in between fold into one trailing write. Same fields as [markRead]. */
    private val readThrottle = ReadThrottle()
    private var readTimer: Job? = null

    fun markReadThrottled() {
        val wait = readThrottle.ask(System.currentTimeMillis()) ?: return
        if (wait == 0L) { markRead(); return }
        readTimer = readScope.launch {
            delay(wait)
            if (readThrottle.takeOwed(System.currentTimeMillis())) markReadOn(readScope)
        }
    }

    /** Leaving the chat or the app going to the background: a read mark still owed goes now. */
    fun flushRead() {
        if (!readThrottle.takeOwed(System.currentTimeMillis())) return
        readTimer?.cancel(); readTimer = null
        markReadOn(readScope)
    }

    /** [markRead] on a scope that outlives the chat screen (a flush on leaving must still land). */
    private fun markReadOn(on: CoroutineScope) {
        if (readMutex.isLocked) { readOwed = true; return }
        on.launch { markReadNow(skipIfRead = true) }
    }

    /**
     * [markRead] awaited (the notification's Mark as read, with no chat screen). [skipIfRead]: the
     * open chat's own marks write nothing when nothing is unread (QuietWrites.readMarkNeeded). Each
     * open used to write my badge 0 and a fresh lastRead even then: four chat-list updates per open
     * and close (the local write and the server's answer for each), every one a slow frame on the
     * Pixel 5 (trace 2026-11-15). What the other side sees is the same.
     */
    suspend fun markReadNow(readReceipts: Boolean = PrivacySettings.readReceipts(), skipIfRead: Boolean = false) {
        readMutex.withLock {
            do {
                readOwed = false
                val local = CryptoHub.listedConversation(cid)
                if (skipIfRead) {
                    val st = _state.value
                    val doc = st.conv
                    val unread = ((doc["unreadCount"] as? Map<*, *>)?.get(me) as? Number)?.toInt() ?: (local?.unreadCount?.get(me) ?: 0)
                    val markedUnread = (doc["markedUnread"] as? Map<*, *>)?.get(me) == true || local?.markedUnread?.get(me) == true
                    val lastRead = ((doc["lastRead"] as? Map<*, *>)?.get(me) as? com.google.firebase.Timestamp)?.toDate()?.time
                    val newestTheirs = st.messages.lastOrNull { it.authorId != me }?.createdAtMillis?.toLong()
                    if (!QuietWrites.readMarkNeeded(unread, markedUnread, lastRead, newestTheirs)) continue
                }
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

    // ------------------------------------------------------------------ channel sync (CN-03)

    /** A channel its member or follower opened follows its change counter (ChannelSync). */
    @Volatile private var chSync: ChannelSync? = null

    private fun startChannelSync() {
        if (chSync != null || _state.value.former || !Wire.isGroupCid(cid) || !isChannelChat()) return
        val member = CryptoHub.listedConversation(cid)?.users?.contains(me) == true
        if (!member && !com.fariin.app.channel.ChannelFollows.follows(cid)) return   // a visitor: the window path
        val sync = ChannelSync(cid, me, convRef, scope, PAGE_SIZE,
            held = { synchronized(docsLock) { (olderDocs.values + liveDocs.values).toList() } },
            onChange = { loaded -> rebuild(loaded) },
            setOlder = { loading, can -> _state.update { it.copy(loadingOlder = loading ?: it.loadingOlder, canLoadOlder = can ?: it.canLoadOlder) } },
            listenOn = listenExec,
        )
        // Assigned BEFORE start(): start() paints at once from the store, and rebuild() must already
        // see it (else the first paint takes the window path and can show "loaded" with no rows).
        chSync = sync
        sync.start()
    }

    /** The list's "older" request: a synced channel reads its next hole, anything else [loadOlder]. */
    fun loadOlderPage() {
        if (chSync?.loadOlder() == true) return
        loadOlder()
    }

    /** The screen is gone: the channel's listeners end (its store stays for the next open). */
    fun stopChannelSync() {
        chSync?.stop(); chSync = null
    }

    companion object {
        /** Outbox entries being resumed now, by clientId (claimResume). */
        private val resuming = ResumeClaims()
        /** One background thread for the open chats' listeners (message window and chat doc). */
        /** Read marks owed when a chat is left are written here, past the screen's own scope. */
        private val readScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
        private val listenExec: java.util.concurrent.ScheduledExecutorService =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "fariin-chat-listen").apply { isDaemon = true } }

        /** My own block lines (iOS blockNotices), never written to the chat. */
        const val BLOCKED_NOTICE = "You blocked this person."
        const val UNBLOCKED_NOTICE = "You unblocked this person."
        /** The one row standing for the messages sealed before this phone's keys (iOS words). */
        const val KEYS_NOTICE_ID = "fariin-keys-notice"
        const val KEYS_NOTICE = "Messages from before you signed in on this device aren't available here. They're end-to-end encrypted."
        const val KEYS_NOTICE_SOME = "Some messages from before you signed in on this device aren't available here. They're end-to-end encrypted."
        /** The list's line for a quote sent without words of mine. */
        const val QUOTED_POST = "Quoted post"

        /** The repository of each chat on screen, so a queue drain sends through it (its bubble
         *  turns from failed back to sending) rather than through a second copy. */
        private val open = ConcurrentHashMap<String, ThreadRepository>()

        /** Unsent texts deleted while their send was still running (iOS cancelledTextSends). */
        private val cancelledTexts = ConcurrentHashMap.newKeySet<String>()

        /** True while a chat is on screen (ChatHistoryPreloader leaves warming until it closes). */
        fun anyOpen(): Boolean = open.isNotEmpty()

        /** Sends outlive the chat screen: leaving a chat mid-send never cancels the send. */
        private val sendScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)


        /** Every queued text of [me]: through the open chat's repository, or a quiet one. */
        fun drainQueue(me: String, crypto: FariinCrypto, scope: CoroutineScope) {
            // A refused send left 30 days goes (iOS drainAll); the rest is never driven on its own.
            SendQueue.pruneRefused(me, System.currentTimeMillis() - 30 * 86_400_000.0)
            for ((cid, list) in SendQueue.all(me).filter { !it.refused }.groupBy { it.cid }) {
                val repo = open[cid] ?: ThreadRepository(cid, me, crypto, scope)
                repo.resendAll(list.filter { !SendQueue.isInFlight(it.clientId) })
            }
            // Photos, files, songs and playlists the app was closed on before they went (PhotoOutbox, FileOutbox).
            for (cid in (com.fariin.app.local.PhotoOutbox.all(me).map { it.cid } + com.fariin.app.local.FileOutbox.all(me).map { it.cid }).distinct())
                (open[cid] ?: ThreadRepository(cid, me, crypto, scope)).resumeOutbox()
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
            "fwdChannel", "fwd", "thumb", "edits", "editedAt", "quote", "bs", "bsd", "fx", "fmt", "origin", "fileNameEnc")   // bs/bsd: the rules refuse a tombstone keeping a bubble style (iOS b189891b)
    }
}

/** Sends that must outlive the chat screen (media uploads and their attach): the app's lifetime. */
internal val SendScope: CoroutineScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

/** The keys notice's place and words per chat, so a chat opens with it already drawn (see the seed above). */
internal object KeysNoticeMemo {
    private val mem = java.util.concurrent.ConcurrentHashMap<String, Pair<Double, String>>()
    @Volatile private var prefs: android.content.SharedPreferences? = null
    private fun p(): android.content.SharedPreferences? = prefs ?: runCatching {
        com.google.firebase.FirebaseApp.getInstance().applicationContext.getSharedPreferences("keys_notice", android.content.Context.MODE_PRIVATE)
    }.getOrNull()?.also { prefs = it }
    private fun k(me: String, cid: String) = "$me|$cid"
    fun get(me: String, cid: String): Pair<Double, String>? = mem[k(me, cid)] ?: p()?.getString(k(me, cid), null)?.let { raw ->
        val at = raw.substringBefore('|').toDoubleOrNull() ?: return null
        (at to raw.substringAfter('|')).also { mem[k(me, cid)] = it }
    }
    fun set(me: String, cid: String, at: Double, text: String) {
        val v = at to text
        if (get(me, cid) == v) return
        mem[k(me, cid)] = v
        p()?.edit()?.putString(k(me, cid), "$at|$text")?.apply()
    }
    fun clear(me: String, cid: String) { mem.remove(k(me, cid)); p()?.edit()?.remove(k(me, cid))?.apply() }
}

/** When this app process started: a send of mine still `uploading` from before it has nothing running. */
private object ThreadRepositoryStart {
    val ms: Double = (System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime() +
        android.os.Process.getStartElapsedRealtime()).toDouble()
}

// Other people write these docs: never a typed getter, which throws on a wrong type (audit H-54).
private fun DocumentSnapshot.ts(field: String): com.google.firebase.Timestamp? = get(field) as? com.google.firebase.Timestamp
private fun DocumentSnapshot.tsEstimate(field: String): com.google.firebase.Timestamp? =
    get(field, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) as? com.google.firebase.Timestamp
private fun DocumentSnapshot.str(field: String): String? = get(field) as? String
private fun DocumentSnapshot.bool(field: String): Boolean? = get(field) as? Boolean
