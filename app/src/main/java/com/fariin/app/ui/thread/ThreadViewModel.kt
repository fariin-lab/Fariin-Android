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
import com.fariin.app.ui.components.collectSettled
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import com.fariin.app.local.Drafts


/** Opens a chat from anywhere under the signed-in shell (the chat list's rows use it). */
val LocalOpenChat = staticCompositionLocalOf<(Conversation) -> Unit> { {} }

class ThreadViewModel(cid: String, me: String, crypto: FariinCrypto, context: Context) : ViewModel() {
    // The phone's class first: the repository picks its redraw interval from it (cheap after launch).
    init { HiddenMessages.load(context); com.fariin.app.perf.DeviceClass.get(context) }
    val repo = ThreadRepository(cid, me, crypto, viewModelScope, media = ChatMedia(context, cid, crypto)).also { it.start() }
    override fun onCleared() { repo.flushRead(); repo.stop(); repo.stopChannelSync() }
}

/** The live chat for [cid]: listener, receipts, send, and mark read like iOS. */
@Composable
fun ThreadRoute(cid: String, me: String, crypto: FariinCrypto, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext
    remember { ThreadWarm.done = true }   // a real chat is open: no warm-up after this (ThreadWarmUp)
    val vm: ThreadViewModel = viewModel(key = "thread-$cid") { ThreadViewModel(cid, me, crypto, app) }
    // Held while a page slides: each answer (server page, synced, pins) used to rebuild the whole
    // chat mid-slide, 4 to 6 times in the first second on a cheap phone; they land together after.
    // A send brings about ten updates (the bubble, the local write, the ack, the chat doc, delivered,
    // read...), each a rebuild of the whole chat: 35 in three sends on the Pixel trace 2026-11-20, a
    // late frame each on the Oppo. The first shows at once; the rest within 120 ms land as one.
    val s by vm.repo.state.collectSettled(ready = { it.loaded }, throttleMs = 120)
    val pinnedMsgs by vm.repo.pinned.collectSettled()
    val keys by CryptoHub.keysEpoch.collectAsState()
    val listed = remember(s.conv, keys) { CryptoHub.listedConversation(cid) }
    val conv = remember(s.conv, listed) {
        if (s.conv.isNotEmpty()) Conversation.fromFirestore(cid, s.conv) else listed
    }
    // iOS syncGroupWallpapers: the group's shared wallpaper as the server has it now (null = none set).
    LaunchedEffect(conv?.isGroup, conv?.groupWallpaper) {
        conv?.takeIf { it.isGroup }?.let { com.fariin.app.data.ChatLookStore.syncGroupWallpaper(app, cid, it.groupWallpaper) }
    }
    // Fariin Plus sharedWallpaper: a 1:1's shared one as the server has it now.
    LaunchedEffect(conv?.isGroup, s.conv["sharedWallpaper"]) {
        if (s.conv.isNotEmpty() && conv?.isGroup == false) com.fariin.app.data.SharedWallpapers.sync(app, cid, s.conv["sharedWallpaper"])
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
    // The divider's count for this whole visit (owner 2026-11-10: "shows, then goes away"). Coming back
    // from a profile or a photo builds the chat again, and by then the count is 0 because it was read;
    // saved with the page, the line stays where it was. The scroll still restores the reader's place.
    val dividerAt = androidx.compose.runtime.saveable.rememberSaveable(cid) { mutableStateOf(unreadAtOpen) }
    // Message requests in a 1:1 (iOS requestStance / cannotMessageThem / otherHasKey / keyAccepted).
    val otherUid = if (conv?.isGroup == true) "" else com.fariin.app.crypto.Wire.otherUid(cid, me)
    val keyAccepted = androidx.compose.runtime.saveable.rememberSaveable(cid) { mutableStateOf(false) }
    // Asked once per chat whether they have a Chat Key (who has one is private; iOS ChatPin.keyExists).
    var otherHasKey by remember(cid) { mutableStateOf(false) }
    var keyResolved by remember(cid) { mutableStateOf(otherUid.isEmpty()) }
    LaunchedEffect(cid, otherUid) {
        if (otherUid.isEmpty()) return@LaunchedEffect
        otherHasKey = com.fariin.app.data.FirestorePeople(me, crypto).chatKeyExists(otherUid) ?: false
        keyResolved = true
    }
    val requestBar = requestBarFor(conv, me, otherUid, s.otherPrivacy, s.messages, receipts.iBlocked || s.former,
        keyAccepted.value, otherHasKey, keyResolved, com.fariin.app.data.MsgPolicy.revision)
    // iOS showKeyAsk: once per chat, when my first message turns it into "request sent" and they
    // have a Chat Key, ask whether I know it. Not marked as asked when they have no key yet.
    val stanceNow = conv?.takeIf { !it.isGroup }?.let { com.fariin.app.data.Requests.stance(it, me) }
    var lastStance by remember(cid) { mutableStateOf(stanceNow) }
    var keyAsk by remember(cid) { mutableStateOf(false) }
    var keyAskSheet by remember(cid) { mutableStateOf(false) }
    LaunchedEffect(cid, stanceNow) {
        val old = lastStance
        lastStance = stanceNow
        if (old != com.fariin.app.data.Requests.Stance.FIRST_MESSAGE ||
            stanceNow != com.fariin.app.data.Requests.Stance.AWAITING_REPLY) return@LaunchedEffect
        if (!otherHasKey || !com.fariin.app.data.MsgPolicy.chatKeyVisible) return@LaunchedEffect
        val prefs = app.getSharedPreferences("fariin_requests", Context.MODE_PRIVATE)
        if (prefs.getBoolean("chatKeyAsked.$cid", false)) return@LaunchedEffect
        prefs.edit().putBoolean("chatKeyAsked.$cid", true).apply()
        keyAsk = true
    }
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
    // Channel or not is known on the FIRST frame (iOS: the list's conversation says so). A channel I
    // follow is not in my chats query, so the thread has no seeded doc until its listener answers;
    // the follow list's copy of the channel doc stands in. Without it the first frames drew a group
    // chat (white, "1 member", sender names, a message field) and then jumped (owner 2026-11-07).
    // Quote cards kept from earlier answers, in memory before the first row draws (QuoteStore).
    remember(cid) { com.fariin.app.data.QuoteStore.warm() }
    val followedDocs by com.fariin.app.channel.ChannelFollows.docs.collectAsState()
    val channelDoc = s.conv.ifEmpty { followedDocs[cid] ?: com.fariin.app.data.ThreadSeed.conv(cid).orEmpty() }
    val channel = com.fariin.app.ui.channels.rememberChannelThread(cid, me, channelDoc, s.messages) { notice = it }
    // A public group I am not in (Explore, a link): its header from the preview, Join where the composer goes.
    val leavingNow by com.fariin.app.data.LeaveUndo.hidden.collectAsState()
    val guest = com.fariin.app.ui.people.rememberGroupGuest(cid, me, channelDoc, channel != null,
        left = s.former || cid in leavingNow) { vm.repo.reload() }
    // A guest may not read the group's names: each sender's own profile gives it, 20 at a time
    // (iOS fillGuestNames). Without it every bubble said "Someone".
    val guestNames = remember(cid) { androidx.compose.runtime.mutableStateMapOf<String, String>() }
    LaunchedEffect(guest != null, s.messages.size) {
        if (guest == null) return@LaunchedEffect
        val missing = s.messages.asSequence().filter { !it.isSystem && it.authorId.isNotEmpty() && it.authorId != me }
            .map { it.authorId }.distinct().filter { it !in guestNames }.take(20).toList()
        if (missing.isEmpty()) return@LaunchedEffect
        val people = com.fariin.app.data.FirestorePeople(me, CryptoHub.get(ctx))
        for (uid in missing) guestNames[uid] = people.profile(uid)?.name?.ifEmpty { null } ?: "User"
    }
    // Whether this chat's history began on its first page the last time the server said so.
    val startKnown = remember(cid) { HistoryStart.known(app, me, cid) }
    LaunchedEffect(s.synced, s.canLoadOlder) {
        if (!s.synced) return@LaunchedEffect
        if (!s.canLoadOlder && s.messages.size < ThreadRepository.PAGE_SIZE) HistoryStart.mark(app, me, cid, true)
        else if (s.canLoadOlder) HistoryStart.mark(app, me, cid, false)
    }
    val channelNav = com.fariin.app.ui.channels.LocalChannelNav.current
    // Clear Messages with Undo (iOS 2026-11-21): everything up to the cut is hidden while the Undo
    // waits and after the clear; Undo shows it again at once from what this chat holds.
    val clearCuts by com.fariin.app.data.ClearCuts.cuts.collectAsState()
    val clearCut = clearCuts[cid]
    val shownMessages = remember(s.messages, clearCut) {
        if (clearCut == null) s.messages else s.messages.filterNot { com.fariin.app.data.ClearCuts.hides(clearCut, it) }
    }
    LaunchedEffect(s.messages) {
        var newest = 0.0
        for (m in s.messages) if (m.sendState == null && m.createdAtMillis > newest) newest = m.createdAtMillis
        com.fariin.app.data.ClearCuts.noteNewest(cid, newest)
    }
    // Leaving the chat runs its waiting clear now (iOS commitClear); the cut stays, so nothing old redraws.
    androidx.compose.runtime.DisposableEffect(cid) { onDispose { com.fariin.app.data.LeaveUndo.commitClear(cid) } }
    // A timed suspension ends by the clock (iOS muteLiftTick): when `until` passes the chat is read
    // again, so the bar and the hidden messages come back without a new doc from the server.
    val modGroup = conv?.isGroup == true && channel == null
    val modUntil = if (modGroup) com.fariin.app.data.GroupMod.untilMillis(s.conv) else 0.0
    var modTick by remember(cid) { mutableStateOf(0) }
    LaunchedEffect(modUntil) {
        val wait = modUntil - System.currentTimeMillis()
        if (modUntil <= 0 || wait <= 0) return@LaunchedEffect
        delay(wait.toLong() + 500)
        modTick++
    }
    val modNow = if (modTick >= 0) System.currentTimeMillis().toDouble() else 0.0
    val modBlocks = modGroup && com.fariin.app.data.GroupMod.blocksSending(s.conv, modNow)
    val ui = ThreadUi(
        otherPlusBubble = s.otherPlusBubble, myPlusBubble = s.myPlusBubble,
        title = channel?.info?.title?.ifEmpty { null } ?: guest?.preview?.title ?: conv?.displayName(me) ?: "",
        subtitle = if (conv?.fariinLocked() == true) "" else channel?.let { com.fariin.app.channel.ChannelRules.followersLine(it.info.shownCount) }
            ?: guest?.let { com.fariin.app.data.GroupRules.memberCount(it.preview.memberCount) }
            ?: typingLine ?: if (conv?.isGroup == true) com.fariin.app.data.GroupRules.memberCount(conv.users.size) else presenceLine ?: "",
        photoUrl = channel?.info?.avatarUrl ?: guest?.preview?.photoUrl ?: conv?.displayPhoto(me),
        isGroup = conv?.isGroup == true || guest != null,
        me = me,
        messages = shownMessages,
        receipts = receipts,
        names = if (guest != null) guestNames.toMap() else conv?.names ?: emptyMap(),
        roles = if (conv?.isGroup == true && channel == null) com.fariin.app.data.GroupRole.all(s.conv) else emptyMap(),
        photos = conv?.photos ?: emptyMap(),
        loaded = s.loaded,
        synced = s.synced,
        loadingOlder = s.loadingOlder,
        // Until the server answers, a chat whose whole history fitted on its first page last time is
        // taken as still complete, so its welcome card is there from the first frame (HistoryStart).
        canLoadOlder = s.canLoadOlder && !(startKnown && !s.synced && s.messages.size < ThreadRepository.PAGE_SIZE),
        // An ended, suspended or disabled group first (iOS groupModBlocked), then announcement mode.
        sendBlocked = (if (modGroup) com.fariin.app.data.GroupMod.sendBlock(s.conv, modNow) else null)
            ?: (if (conv?.isGroup == true && channel == null) com.fariin.app.data.GroupAdmins.announcementBlock(s.conv, me) else null)
            ?: vm.repo.sendBlockedReason(listed ?: conv),
        sendBlockedByMod = modBlocks,
        // iOS groupModHidesChat: suspended (not over) or disabled by Fariin hides the chat; ended keeps it.
        modHidden = modBlocks && com.fariin.app.data.GroupMod.state(s.conv) != "ended",
        cid = cid,
        otherDeleted = conv?.isGroup != true && channel == null &&
            (s.otherDeleted || conv?.isDeletedAccount(conv.otherUid(me)) == true),
        // iOS adminCan(me, .deleteMessages): an admin holding the delete right, not every admin.
        adminMayDelete = conv?.isGroup == true && me in admins && (if (channel != null)
            com.fariin.app.channel.ChannelAdmins.Team.from(s.conv).can(me, com.fariin.app.channel.ChannelAdmins.Right.DELETE_MESSAGES)
            else com.fariin.app.data.GroupAdmins.Team.from(s.conv).can(me, com.fariin.app.data.GroupAdmins.Right.DELETE_MESSAGES)),
        ownerId = s.conv["createdBy"] as? String ?: "",
        members = conv?.users ?: emptyList(),
        memberReads = (s.conv["lastRead"] as? Map<*, *>)?.mapNotNull { (k, v) ->
            (k as? String)?.let { it to com.fariin.app.data.Conversation.millis(v) }
        }?.toMap() ?: emptyMap(),
        myPhoto = conv?.photos?.get(me),
        unreadAtOpen = unreadAtOpen,
        dividerAt = dividerAt.value,
        requestBar = requestBar,
        knock = conv != null && !conv.isGroup && channel == null &&
            com.fariin.app.data.Requests.stance(conv, me) == com.fariin.app.data.Requests.Stance.FIRST_MESSAGE,
        awaiting = conv != null && !conv.isGroup && channel == null &&
            com.fariin.app.data.Requests.stance(conv, me) == com.fariin.app.data.Requests.Stance.AWAITING_REPLY,
        channel = channel,
        channelWaiting = channel == null && com.fariin.app.ui.channels.ChannelGuestPreviews.waiting(cid, me, channelDoc),
        pinnedIds = com.fariin.app.data.PinRules.pinnedIds(s.conv),
        pinned = pinnedMsgs,
        clearedAtMs = conv?.clearedAt?.get(me) ?: 0.0,
        canPin = com.fariin.app.data.PinRules.canPin(s.conv, me, conv?.isGroup == true),   // channels too: their owner and admins pin (iOS adminCan pinMessages)
        disappearSeconds = (s.conv["disappearSeconds"] as? Number)?.toInt() ?: conv?.disappearSeconds ?: 0,
        groupDoc = if (conv?.isGroup == true && channel == null) s.conv else emptyMap(),
        notAMember = s.former && channel == null && guest == null,
        // iOS RejoinGroupBar: a public group I left, and nobody banned me, can be joined again from here.
        canRejoin = s.former && channel == null && guest == null && conv?.isPublicGroup == true &&
            me !in ((s.conv["bannedUids"] as? List<*>) ?: emptyList<Any>()),
        groupGuest = guest,
        muted = conv?.takeIf { !s.former }?.isMuted(me, System.currentTimeMillis().toDouble()),
        loadError = s.error,
    )
    // iOS autoRetryFailedMedia: when the chat is in from the server, and again whenever the
    // connection comes back, each failed photo, video or voice note is resent once by itself (the
    // stuck "uploading" sweep is ThreadRepository.clearStuckSends).
    val link by com.fariin.app.data.ConnectionStatus.state.collectAsState()
    val autoRetried = remember(cid) { HashSet<String>() }
    LaunchedEffect(cid, link, s.synced) {
        if (link != com.fariin.app.data.ConnectionStatus.State.ONLINE || !s.synced) return@LaunchedEffect
        for (m in vm.repo.state.value.messages) {
            val key = m.clientId ?: continue
            if (m.sendState != com.fariin.app.data.Message.SendState.FAILED || m.mediaKind == null || key in autoRetried) continue
            if (!vm.repo.canResendMedia(key)) continue
            autoRetried += key
            vm.repo.retry(m)
        }
    }
    val atBottom = remember { MutableStateFlow(true) }
    val actions = ThreadActions(
        onReload = vm.repo::reload,
        onKeyAccepted = { keyAccepted.value = true },
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
        onSendBold = { t, r, ids, fmt -> vm.repo.sendText(t, r, ids, fmt) },
        onSendFx = { t, r, ids, fmt, fx -> vm.repo.sendText(t, r, ids, fmt, fx) },
        onSendSticker = { s, r -> vm.repo.sendSticker(s, r) },
        onSendGif = { g, r -> vm.repo.sendGif(g, r) },
        onPollImage = { jpeg -> vm.repo.uploadPollImage(jpeg) },
        onEdit = { m, t, fmt -> vm.viewModelScope.launch { if (!vm.repo.editMessage(m, t, fmt)) notice = "Couldn't edit this message." } },
        onDeleteForEveryone = { m ->
            vm.viewModelScope.launch {
                if (!vm.repo.deleteForEveryone(m)) notice = "The server refused the delete. The message is still there for both of you."
            }
        },
        onDeleteForMe = { m -> vm.repo.hideForMe(app, m) },
        onTyping = { on -> vm.repo.setTyping(on) },
        onRecordingVoice = { on -> vm.repo.setRecording(on) },
        onAtBottom = { atBottom.value = it },
        onOpenPerson = { uid -> nav.contact(uid) },
        onMessagePerson = { other -> nav.chat(com.fariin.app.crypto.Wire.directCid(me, other)) },
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
                val failed = try { com.fariin.app.media.Forwarder.forward(app, me, crypto, m, cid, targets) }
                    catch (gone: com.fariin.app.media.Forwarder.Gone) { notice = gone.message; return@launch }
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
        onSendQuote = { t, q, ids, fmt, failed ->
            val repo = vm.repo
            val qid = com.fariin.app.data.QuoteRules.sendableQid(q)
            if (qid != null) repo.sendQuote(t, qid, ids, failed, fmt)
            else if (com.fariin.app.data.QuoteRules.isToken(q.token)) com.fariin.app.data.QuoteStore.whenMade(q.token) { r ->
                when (r) {
                    is com.fariin.app.data.QuoteStore.Made.Ok -> repo.sendQuote(t, r.qid, ids, failed, fmt)
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
        onHiddenByBlock = { id -> vm.repo.isHiddenByBlock(id) },
        onSearchCorpus = { vm.repo.searchCorpus() },
        onSearchCorpusSeen = { vm.repo.seenCorpus() },
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
        // The sheet's Send (owner 2026-11-07, the reference's forward): the forwards oldest first,
        // then the note as its own message in each chat. One chat picked opens it at once, so the
        // forward is seen arriving; the work runs off this screen so leaving it does not stop it.
        onForwardWithNote = { msgs, targets, note ->
            val land = targets.singleOrNull()?.takeIf { it != cid }
            val ordered = msgs.sortedBy { it.createdAtMillis }
            // One chat picked: the forwards are already in it, sending, when it opens (owner 2026-11-14,
            // the big messengers' forward: you and the message land together, not the message later).
            val ids = HashMap<String, String>()
            if (land != null) {
                val now = System.currentTimeMillis().toDouble()
                // "Forwarded from <this group or channel>" on the bubble from the first frame, as the
                // real message will say (Forwarder.sourceOf / channelJson, from the chat list here).
                val here = com.fariin.app.data.ChatsNow.find(cid)?.takeIf { it.isGroup || it.isChannel }
                for ((i, m) in ordered.withIndex()) {
                    if (!com.fariin.app.data.ForwardEchoes.drawable(m) || (m.mediaKind == null && m.text.isBlank())) continue
                    val id = java.util.UUID.randomUUID().toString().uppercase()
                    ids[m.rowId] = id
                    val fwd = com.fariin.app.media.MediaMessages.isForward(m.forwarded, cid, land)
                    var echo = com.fariin.app.data.ForwardEchoes.echoOf(m, me, id, fwd, now + i)
                    if (here != null && !m.forwarded && fwd) {
                        val title = here.displayName(me); val photo = here.displayPhoto(me)
                        echo = echo.copy(forwardSource = com.fariin.app.data.Message.ForwardSource.make(cid, m.id, title, photo, false, here.isChannel),
                            fwdChannel = if (here.isChannel) com.fariin.app.data.Message.FwdChannel(cid, title, photo.orEmpty(), "") else null)
                    }
                    com.fariin.app.data.ForwardEchoes.add(land, echo)
                }
            }
            val work = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
            work.launch {
                var failedChats = 0
                var gone = 0
                for (m in ordered) {
                    val pinned = if (land != null) ids[m.rowId]?.let { mapOf(land to it) }.orEmpty() else emptyMap()
                    failedChats += try { com.fariin.app.media.Forwarder.forward(app, me, crypto, m, cid, targets, pinned).size }
                        catch (_: com.fariin.app.media.Forwarder.Gone) { gone++; targets.distinct().take(com.fariin.app.media.MediaRules.FORWARD_CHATS_AT_ONCE).size }
                }
                if (note.isNotBlank()) for (t in targets.distinct().take(com.fariin.app.media.MediaRules.FORWARD_CHATS_AT_ONCE)) {
                    com.fariin.app.data.ThreadRepository(t, me, crypto, work).sendText(note)
                }
                val said = when {
                    // Deleted by their author before they went (Forwarder.Gone): said as such, not "try again".
                    gone > 0 && gone == ordered.size -> if (gone == 1) "This message was deleted." else "These messages were deleted."
                    failedChats == 0 -> if (land != null) null else if (targets.size > 1) "Forwarded to ${targets.size} chats" else "Forwarded"
                    failedChats == msgs.size * targets.size -> "Couldn't forward. Try again."
                    else -> "Some messages couldn't be forwarded."
                }
                if (said != null) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    if (land != null) com.fariin.app.ui.components.Snack.show(said) else notice = said
                }
            }
            if (land != null) nav.chat(land)
        },
        onForwardManyTo = { msgs, targets ->
            vm.viewModelScope.launch {
                var failedChats = 0
                var gone = 0
                for (m in msgs) {
                    failedChats += try { com.fariin.app.media.Forwarder.forward(app, me, crypto, m, cid, targets).size }
                        catch (_: com.fariin.app.media.Forwarder.Gone) { gone++; targets.distinct().take(com.fariin.app.media.MediaRules.FORWARD_CHATS_AT_ONCE).size }
                }
                notice = when {
                    gone > 0 && gone == msgs.size -> if (gone == 1) "This message was deleted." else "These messages were deleted."
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
            // Busy groups: one read mark per 2.5 s at most while open (never one per message), and
            // the one still owed goes when the chat is left or the app goes to the background.
            try { combine(vm.repo.state, atBottom) { st, bottom -> st to bottom }.collect { (st, bottom) ->
                if (!st.loaded || !bottom || st.former) return@collect
                // A group's doc is written only by its members: a visitor reading a public channel
                // from outside (or a member before the doc has come) sends no read mark.
                if (com.fariin.app.crypto.Wire.isGroupCid(cid) && me !in ((st.conv["users"] as? List<*>) ?: emptyList<Any>())) return@collect
                val newest = st.messages.lastOrNull { it.authorId != me }?.rowId ?: ""
                if (newest != handled) { handled = newest; vm.repo.markReadThrottled() }
            } } finally { vm.repo.flushRead() }
        }
    }
    // A channel I follow: its read mark moves up only while the newest post is in view and the app is
    // in front (iOS resetUnread at the bottom). Opening at the first unread post no longer reads them all.
    val followerPosts = channel?.takeIf { it.role == com.fariin.app.channel.ChannelRules.Role.FOLLOWER }?.info?.postCount
    val followerPostsNow = androidx.compose.runtime.rememberUpdatedState(followerPosts)
    LaunchedEffect(cid) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            combine(androidx.compose.runtime.snapshotFlow { followerPostsNow.value }, atBottom, vm.repo.state) { n, bottom, st ->
                // The server answer too (synced): a cached list reports the bottom before a saved place or a
                // jump has scrolled, and the posts would be marked read unseen.
                if (n != null && bottom && st.loaded && st.synced) n else null
            }.distinctUntilChanged().collectLatest { n ->
                n ?: return@collectLatest
                // The list reports where it landed a moment after the open: wait for it.
                delay(400)
                com.fariin.app.channel.ChannelService.markRead(cid, n)
            }
        }
    }
    val uploadCancel = remember(vm, me) { UploadCancel(me, vm.repo::cancelMediaSend, vm.repo::cancelAlbumItem) }
    // The run of voice notes the player follows when one ends, also after this chat is left (iOS handOff).
    LaunchedEffect(ui.cid, ui.messages) { com.fariin.app.data.SharedMediaSource.noteLive(ui.cid, ui.messages) }
    LaunchedEffect(ui.cid, ui.messages) { vm.repo.media?.let { com.fariin.app.media.VoiceRun.publish(ui.cid, ui.messages, it) } }
    CompositionLocalProvider(LocalChatMedia provides vm.repo.media, LocalUploadCancel provides uploadCancel) {
        ThreadScreen(ui, onBack = onBack, onSend = vm.repo::sendText, onLoadOlder = vm.repo::loadOlderPage, onRetry = vm.repo::retry,
            onSendPhoto = { uri -> vm.repo.sendPhoto(app, uri) }, onSendVoice = vm.repo::sendVoice, actions = actions,
            onSendPick = { uris, caption, once -> vm.repo.sendPick(app, uris, caption, once) },
            onSendFile = { uri -> vm.repo.sendDocument(app, uri) { notice = it } },
            onSendFiles = { uris -> vm.repo.sendDocuments(app, uris) { notice = it } })
    }
    // The question itself: Not Now and the request simply waits; Use Chat Key opens the key sheet.
    if (keyAsk) com.fariin.app.ui.components.GlassAlert(
        onDismissRequest = { keyAsk = false },
        title = "Know ${ui.title}'s Chat Key?",
        message = "Your message request is sent. If you know their Chat Key, you can message them now instead of waiting.",
        actions = listOf(
            com.fariin.app.ui.components.GlassAlertAction("Not Now", com.fariin.app.ui.components.GlassAlertStyle.Bold) { keyAsk = false },
            com.fariin.app.ui.components.GlassAlertAction("Use Chat Key") { keyAsk = false; keyAskSheet = true },
        ),
    )
    if (keyAskSheet) com.fariin.app.ui.people.ChatKeySheet(
        remember(me) { com.fariin.app.data.FirestorePeople(me, crypto) },
        com.fariin.app.data.Person(otherUid, name = ui.title, handle = ""),
        onDismiss = { keyAskSheet = false },
        onOpened = { keyAskSheet = false; keyAccepted.value = true },
    )
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
