package com.fariin.app.ui.thread

import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import com.fariin.app.media.ChatMedia
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fariin.app.crypto.FariinCrypto
import com.fariin.app.data.Conversation
import com.fariin.app.data.CryptoHub
import com.fariin.app.data.ThreadRepository
import com.fariin.app.data.HiddenMessages
import com.fariin.app.data.MessageRules
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import com.fariin.app.local.Drafts


/** Opens a chat from anywhere under the signed-in shell (the chat list's rows use it). */
val LocalOpenChat = staticCompositionLocalOf<(Conversation) -> Unit> { {} }

class ThreadViewModel(cid: String, me: String, crypto: FariinCrypto, context: Context) : ViewModel() {
    init { HiddenMessages.load(context) }
    val repo = ThreadRepository(cid, me, crypto, viewModelScope, media = ChatMedia(context, cid, crypto)).also { it.start() }
    override fun onCleared() { repo.stop() }
}

/** The live chat for [cid]: listener, receipts, send, and mark read like iOS. */
@Composable
fun ThreadRoute(cid: String, me: String, crypto: FariinCrypto, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext
    val vm: ThreadViewModel = viewModel(key = "thread-$cid") { ThreadViewModel(cid, me, crypto, app) }
    val s by vm.repo.state.collectAsState()
    val pinnedMsgs by vm.repo.pinned.collectAsState()
    val keys by CryptoHub.keysEpoch.collectAsState()
    val listed = remember(s.conv, keys) { CryptoHub.listedConversation(cid) }
    val conv = remember(s.conv, listed) {
        if (s.conv.isNotEmpty()) Conversation.fromFirestore(cid, s.conv) else listed
    }
    // iOS syncGroupWallpapers: the group's shared wallpaper as the server has it now (null = none set).
    LaunchedEffect(conv?.isGroup, conv?.groupWallpaper) {
        conv?.takeIf { it.isGroup }?.let { com.fariin.app.data.ChatLookStore.syncGroupWallpaper(app, cid, it.groupWallpaper) }
    }
    // Blocked by me, from the chat's old shared copy or my private list (iOS repo.iBlocked):
    // no presence, typing, ticks or read receipts, and the composer gives way to Unblock.
    val blockSnap by com.fariin.app.data.BlockList.state.collectAsState()
    val receipts = if (blockSnap.owner == me && conv?.isGroup != true &&
        blockSnap.contains(com.fariin.app.crypto.Wire.otherUid(cid, me))) s.receipts.copy(iBlocked = true) else s.receipts
    // Typing: theirs from `typing.<uid>`, cleared 15 s after the value last changed (iOS expiry).
    val typingMap = s.conv["typing"] as? Map<*, *> ?: emptyMap<String, Any>()
    // Recording a voice note rides the same field ("audio-<sec>", iOS setRecording) and expiry.
    val anyLive = typingMap.any { (k, v) -> k != me && (MessageRules.isTyping(v) || MessageRules.isRecording(v)) }
    var typingTick by remember { mutableStateOf(0) }
    LaunchedEffect(typingMap) { if (anyLive) { delay(MessageRules.TYPING_EXPIRY_MS); typingTick++ } }
    val typingSince = remember(typingMap) { System.currentTimeMillis() }
    val typingNow = anyLive && System.currentTimeMillis() - typingSince < MessageRules.TYPING_EXPIRY_MS && typingTick >= 0
    // My own Typing Indicators setting hides theirs too (iOS typingPref: it is mutual); blocked shows none.
    val typingLine = if (receipts.iBlocked) null else MessageRules.liveLine(typingMap, me, conv?.isGroup == true,
        com.fariin.app.data.PrivacySettings.typingIndicators(), typingNow) { conv?.names?.get(it) }
    val unreadAtOpen = remember(cid) { CryptoHub.listedConversation(cid)?.unread(me) ?: 0 }
    // Online / last seen for a 1:1 (iOS ThreadView subtitle), redrawn each minute so "5 min. ago" moves.
    var minute by remember { mutableStateOf(0) }
    LaunchedEffect(cid) { while (true) { delay(60_000); minute++ } }
    val presenceLine = if (conv == null || conv.isGroup || receipts.iBlocked || minute < 0) null else
        com.fariin.app.data.Presence.line(
            mine = s.myPrivacy, theirs = s.otherPrivacy,
            contactOfMine = conv.lastMessageCipher.isNotEmpty() && (conv.startedBy.isEmpty() || conv.accepted),
            online = s.otherOnline, lastActiveMillis = s.otherLastActive,
            nowMillis = System.currentTimeMillis().toDouble(),
            legacySwitch = app.getSharedPreferences("fariin_privacy", Context.MODE_PRIVATE).getBoolean("shareLastSeen", true),
        )
    val admins = (s.conv["admins"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
    val ctx = LocalContext.current
    val nav = com.fariin.app.ui.people.LocalPeopleNav.current
    val hostView = androidx.compose.ui.platform.LocalView.current
    var notice by remember { mutableStateOf<String?>(null) }
    notice?.let { n -> LaunchedEffect(n) { com.fariin.app.ui.components.Snack.show(n); notice = null } }
    val channel = com.fariin.app.ui.channels.rememberChannelThread(cid, me, s.conv, s.messages) { notice = it }
    // Whether this chat's history began on its first page the last time the server said so.
    val startKnown = remember(cid) { HistoryStart.known(app, me, cid) }
    LaunchedEffect(s.synced, s.canLoadOlder) {
        if (!s.synced) return@LaunchedEffect
        if (!s.canLoadOlder && s.messages.size < ThreadRepository.PAGE_SIZE) HistoryStart.mark(app, me, cid, true)
        else if (s.canLoadOlder) HistoryStart.mark(app, me, cid, false)
    }
    val channelNav = com.fariin.app.ui.channels.LocalChannelNav.current
    val ui = ThreadUi(
        title = channel?.info?.title?.ifEmpty { null } ?: conv?.displayName(me) ?: "",
        subtitle = channel?.let { com.fariin.app.channel.ChannelRules.followersLine(it.info.shownCount) }
            ?: typingLine ?: if (conv?.isGroup == true) com.fariin.app.data.GroupRules.memberCount(conv.users.size) else presenceLine ?: "",
        photoUrl = channel?.info?.avatarUrl ?: conv?.displayPhoto(me),
        isGroup = conv?.isGroup == true,
        me = me,
        messages = s.messages,
        receipts = receipts,
        names = conv?.names ?: emptyMap(),
        roles = if (conv?.isGroup == true && channel == null) com.fariin.app.data.GroupRole.all(s.conv) else emptyMap(),
        photos = conv?.photos ?: emptyMap(),
        loaded = s.loaded,
        synced = s.synced,
        loadingOlder = s.loadingOlder,
        // Until the server answers, a chat whose whole history fitted on its first page last time is
        // taken as still complete, so its welcome card is there from the first frame (HistoryStart).
        canLoadOlder = s.canLoadOlder && !(startKnown && !s.synced && s.messages.size < ThreadRepository.PAGE_SIZE),
        sendBlocked = (if (conv?.isGroup == true && channel == null) com.fariin.app.data.GroupAdmins.announcementBlock(s.conv, me) else null)
            ?: vm.repo.sendBlockedReason(listed ?: conv),
        cid = cid,
        otherDeleted = conv?.isGroup != true && channel == null &&
            (s.otherDeleted || conv?.isDeletedAccount(conv.otherUid(me)) == true),
        adminMayDelete = conv?.isGroup == true && me in admins,
        members = conv?.users ?: emptyList(),
        memberReads = (s.conv["lastRead"] as? Map<*, *>)?.mapNotNull { (k, v) ->
            (k as? String)?.let { it to com.fariin.app.data.Conversation.millis(v) }
        }?.toMap() ?: emptyMap(),
        myPhoto = conv?.photos?.get(me),
        unreadAtOpen = unreadAtOpen,
        channel = channel,
        channelWaiting = channel == null && com.fariin.app.ui.channels.ChannelGuestPreviews.waiting(cid, me, s.conv),
        pinnedIds = com.fariin.app.data.PinRules.pinnedIds(s.conv),
        pinned = pinnedMsgs,
        canPin = com.fariin.app.data.PinRules.canPin(s.conv, me, conv?.isGroup == true),   // channels too: their owner and admins pin (iOS adminCan pinMessages)
        disappearSeconds = (s.conv["disappearSeconds"] as? Number)?.toInt() ?: conv?.disappearSeconds ?: 0,
        groupDoc = if (conv?.isGroup == true && channel == null) s.conv else emptyMap(),
        notAMember = s.former && channel == null,
        muted = conv?.takeIf { !s.former }?.isMuted(me, System.currentTimeMillis().toDouble()),
        loadError = s.error,
    )
    val atBottom = remember { MutableStateFlow(true) }
    val actions = ThreadActions(
        onReload = vm.repo::reload,
        onUnblock = {
            vm.viewModelScope.launch {
                val ok = com.fariin.app.data.FirestorePeople(me, CryptoHub.get(ctx))
                    .setBlocked(com.fariin.app.crypto.Wire.otherUid(cid, me), false)
                if (!ok) notice = "Couldn't unblock. Check your connection and try again."
            }
        },
        onReact = { m, e -> vm.viewModelScope.launch { if (!vm.repo.setReaction(m, e)) notice = "Couldn't react. Try again." } },
        onSendReply = { t, r -> vm.repo.sendText(t, r) },
        onSendMentioned = { t, r, ids -> vm.repo.sendText(t, r, ids) },
        onSendSticker = { s, r -> vm.repo.sendSticker(s, r) },
        onSendGif = { g, r -> vm.repo.sendGif(g, r) },
        onEdit = { m, t -> vm.viewModelScope.launch { if (!vm.repo.editMessage(m, t)) notice = "Couldn't edit this message." } },
        onDeleteForEveryone = { m ->
            vm.viewModelScope.launch {
                if (!vm.repo.deleteForEveryone(m)) notice = "The server refused the delete. The message is still there for both of you."
            }
        },
        onDeleteForMe = { m -> vm.repo.hideForMe(app, m) },
        onTyping = { on -> vm.repo.setTyping(on) },
        onRecordingVoice = { on -> vm.repo.setRecording(on) },
        onAtBottom = { atBottom.value = it },
        onTitleClick = {
            InChatSearchLink.openedFrom = cid   // the info page's Search circle shows only when opened from here
            if (channel != null) channelNav.page(cid) else if (conv?.isGroup == true) nav.group(cid) else conv?.otherUid(me)?.takeIf { it.isNotEmpty() }?.let(nav.contact)
        },
        onNotice = { notice = it },
        onToggleMute = {
            conv?.let { cv ->
                val on = !cv.isMuted(me, System.currentTimeMillis().toDouble())
                vm.viewModelScope.launch {
                    notice = if (com.fariin.app.ui.chats.ChatListActions.setMuted(me, cv, on)) (if (on) "Muted" else "Unmuted")
                        else "Check your connection and try again."
                }
            }
        },
        onForwardTo = { m, targets ->
            vm.viewModelScope.launch {
                val failed = com.fariin.app.media.Forwarder.forward(app, me, crypto, m, cid, targets)
                notice = when {
                    failed.isEmpty() -> if (targets.size > 1) "Forwarded to ${targets.size} chats" else "Forwarded"
                    failed.size == targets.size -> "Couldn't forward. Try again."
                    else -> "Forwarded to ${targets.size - failed.size} of ${targets.size} chats"
                }
            }
        },
        // Quote a channel post (iOS startQuote): the chat picked opens AT ONCE with a card drawn from
        // the post on screen (owner 2026-11-06: it waited seconds for the server). The server
        // snapshots the post in the background under a local token (and checks I may see it);
        // ThreadScreen puts its answer into the draft. This chat keeps the quote here.
        onQuoteTo = { m, dest ->
            val media = vm.repo.media
            // Only an older SEALED post needs this phone's picture (one shared copy per post on the
            // server, taken from its author or an admin); a plain post is pointed at as it is.
            val sealed = com.fariin.app.data.QuoteRules.isSealedPost(listOf(m.enc?.v, m.thumbEnc?.v) + m.album.map { it.enc?.v })
            val sends = com.fariin.app.data.QuoteRules.sendsPicture(sealed, mine = m.authorId == me,
                admin = me == s.conv["createdBy"] || me in admins)
            val token = com.fariin.app.data.QuoteStore.start(cid, m.id) { if (sends) com.fariin.app.media.QuotePicture.make(media, m) else null }
            val card = com.fariin.app.data.QuoteRules.localCard(cid, m.id, ui.title, ui.photoUrl,
                if (m.mediaKind == null) m.bodyText else m.text, m.type, m.album.isNotEmpty(),
                image = if (sealed) null else com.fariin.app.data.QuoteRules.pictureUrl(m.imageUrl, m.videoUrl, m.thumbUrl, m.album.firstOrNull()?.imageUrl))
            com.fariin.app.data.QuoteStore.pendingDraft.value = com.fariin.app.data.QuoteStore.Draft(dest, "", card, token)
            if (dest != cid) nav.chat(dest)
        },
        // A send before the quote has landed waits for its id; a message never carries the local token.
        onSendQuote = { t, q, ids, failed ->
            val repo = vm.repo
            val qid = com.fariin.app.data.QuoteRules.sendableQid(q)
            if (qid != null) repo.sendQuote(t, qid, ids, failed)
            else if (com.fariin.app.data.QuoteRules.isToken(q.token)) com.fariin.app.data.QuoteStore.whenMade(q.token) { r ->
                when (r) {
                    is com.fariin.app.data.QuoteStore.Made.Ok -> repo.sendQuote(t, r.qid, ids, failed)
                    is com.fariin.app.data.QuoteStore.Made.Refused -> com.fariin.app.ui.components.Snack.show(com.fariin.app.data.QuoteRules.REFUSED)
                }
            }
        },
        onSave = { m -> vm.viewModelScope.launch {
            val said = com.fariin.app.media.GallerySaver.saveMessage(app, vm.repo.media, m)
            if (said.startsWith("Saved")) hostView.lightTap()   // iOS: a light tap once it is saved
            notice = said
        } },
        // Drafts kept per chat (iOS Drafts): the box comes back with what was left in it.
        draft = remember(cid) { Drafts.text(cid) },
        onDraft = { Drafts.set(cid, it) },
        onPin = { m ->
            vm.viewModelScope.launch {
                when (vm.repo.pin(m)) {
                    null -> notice = "You can pin up to ${com.fariin.app.data.PinRules.MAX_PINS} messages."
                    false -> notice = "Couldn't pin this message. Try again."
                    true -> {}
                }
            }
        },
        onUnpin = { id ->
            vm.viewModelScope.launch {
                try { vm.repo.unpin(id) } catch (e: Exception) { notice = "Couldn't unpin: ${e.localizedMessage ?: "try again"}" }
            }
        },
        onEnsureLoaded = { id -> vm.repo.ensureLoaded(id) },
        onSearchCorpus = { vm.repo.searchCorpus() },
        onReachMatch = { id -> vm.repo.ensureLoaded(id, maxPages = com.fariin.app.data.ChatSearch.JUMP_PAGES) },
        onEditHistory = { m -> vm.repo.editHistory(m) },
        onDeleteMany = { plan ->
            for (m in plan.forMe) vm.repo.hideForMe(app, m)
            if (plan.forEveryone.isNotEmpty()) vm.viewModelScope.launch {
                // All at once, 4 on the wire at a time (iOS): each one turns into its placeholder as it
                // starts, so the picked messages go together instead of one by one.
                val gate = kotlinx.coroutines.sync.Semaphore(4)
                val refused = plan.forEveryone.map { m ->
                    async { gate.withPermit { vm.repo.deleteForEveryone(m) } }
                }.awaitAll().count { !it }
                if (refused > 0) notice = if (refused == 1) "The server refused one delete. That message is still there."
                    else "The server refused $refused deletes. Those messages are still there."
            }
        },
        onForwardManyTo = { msgs, targets ->
            vm.viewModelScope.launch {
                var failedChats = 0
                for (m in msgs) {
                    val f = com.fariin.app.media.Forwarder.forward(app, me, crypto, m, cid, targets)
                    failedChats += f.size
                }
                notice = when {
                    failedChats == 0 -> if (targets.size > 1) "Forwarded to ${targets.size} chats" else "Forwarded"
                    failedChats == msgs.size * targets.size -> "Couldn't forward. Try again."
                    else -> "Some messages couldn't be forwarded."
                }
            }
        },
    )
    // iOS: on open, once the first page is in, my badge goes to 0 and the read receipt goes out;
    // while the chat stays open and in front, every new message from them does the same.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(cid) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var handled: String? = null
            combine(vm.repo.state, atBottom) { st, bottom -> st to bottom }.collect { (st, bottom) ->
                if (!st.loaded || !bottom || st.former) return@collect
                // A group's doc is written only by its members: a visitor reading a public channel
                // from outside (or a member before the doc has come) sends no read mark.
                if (com.fariin.app.crypto.Wire.isGroupCid(cid) && me !in ((st.conv["users"] as? List<*>) ?: emptyList<Any>())) return@collect
                val newest = st.messages.lastOrNull { it.authorId != me }?.rowId ?: ""
                if (newest != handled) { handled = newest; vm.repo.markRead() }
            }
        }
    }
    val uploadCancel = remember(vm, me) { UploadCancel(me, vm.repo::cancelMediaSend) }
    CompositionLocalProvider(LocalChatMedia provides vm.repo.media, LocalUploadCancel provides uploadCancel) {
        ThreadScreen(ui, onBack = onBack, onSend = vm.repo::sendText, onLoadOlder = vm.repo::loadOlder, onRetry = vm.repo::retry,
            onSendPhoto = { uri -> vm.repo.sendPhoto(app, uri) }, onSendVoice = vm.repo::sendVoice, actions = actions,
            onSendPick = { uris, caption, once -> vm.repo.sendPick(app, uris, caption, once) },
            onSendFile = { uri -> vm.repo.sendDocument(app, uri) { notice = it } })
    }
}


/**
 * The chats whose whole history fitted on the first page the last time the server answered, per
 * account, kept across launches. Opening such a chat draws its group or channel card from the first
 * frame instead of after the server's answer (it popped in ~0.2 s late on the Pixel, 2026-11-07).
 */
internal object HistoryStart {
    private const val PREFS = "fariin_history_start"

    fun known(c: Context, me: String, cid: String): Boolean =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(me, null)?.contains(cid) == true

    fun mark(c: Context, me: String, cid: String, complete: Boolean) {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = p.getStringSet(me, null).orEmpty()
        if ((cid in now) == complete) return
        p.edit().putStringSet(me, if (complete) now + cid else now - cid).apply()
    }
}
