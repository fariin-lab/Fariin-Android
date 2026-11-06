package com.fariin.app.ui.thread

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
    val typers = typingMap.filter { (k, v) -> k != me && MessageRules.isTyping(v) }.keys.mapNotNull { it as? String }
    var typingTick by remember { mutableStateOf(0) }
    LaunchedEffect(typingMap) { if (typers.isNotEmpty()) { delay(MessageRules.TYPING_EXPIRY_MS); typingTick++ } }
    val typingSince = remember(typingMap) { System.currentTimeMillis() }
    val typingNow = typers.isNotEmpty() && System.currentTimeMillis() - typingSince < MessageRules.TYPING_EXPIRY_MS && typingTick >= 0
    val typingLine = when {
        !typingNow -> null
        receipts.iBlocked -> null
        conv?.isGroup != true -> "typing…"
        typers.size == 1 -> "${conv.names[typers[0]] ?: "Someone"} is typing…"
        else -> "${typers.size} people are typing…"
    }
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
    var notice by remember { mutableStateOf<String?>(null) }
    notice?.let { n -> LaunchedEffect(n) { com.fariin.app.ui.components.Snack.show(n); notice = null } }
    val channel = com.fariin.app.ui.channels.rememberChannelThread(cid, me, s.conv, s.messages) { notice = it }
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
        canLoadOlder = s.canLoadOlder,
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
        // Quote a channel post (iOS startQuote): the server snapshots it (and checks I may see it),
        // then the chat picked opens with the quote above its composer; this chat keeps it here.
        onQuoteTo = { m, dest ->
            vm.viewModelScope.launch {
                // The post's picture as this phone has it (channel media is sealed; QuotePicture.kt).
                val picture = com.fariin.app.media.QuotePicture.make(vm.repo.media, m)
                when (val r = com.fariin.app.data.QuoteStore.create(cid, m.id, picture)) {
                    is com.fariin.app.data.QuoteStore.Made.Refused -> notice = r.text
                    is com.fariin.app.data.QuoteStore.Made.Ok -> {
                        com.fariin.app.data.QuoteStore.pendingDraft.value = com.fariin.app.data.QuoteStore.Draft(dest, r.qid, r.card)
                        if (dest != cid) nav.chat(dest)
                    }
                }
            }
        },
        onSendQuote = { t, q, ids, failed -> vm.repo.sendQuote(t, q.qid, ids, failed) },
        onSave = { m -> vm.viewModelScope.launch { notice = com.fariin.app.media.GallerySaver.saveMessage(app, vm.repo.media, m) } },
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
                var refused = 0
                for (m in plan.forEveryone) if (!vm.repo.deleteForEveryone(m)) refused++
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

