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
    val keys by CryptoHub.keysEpoch.collectAsState()
    val listed = remember(s.conv, keys) { CryptoHub.listedConversation(cid) }
    val conv = remember(s.conv, listed) {
        if (s.conv.isNotEmpty()) Conversation.fromFirestore(cid, s.conv) else listed
    }
    // Typing: theirs from `typing.<uid>`, cleared 15 s after the value last changed (iOS expiry).
    val typingMap = s.conv["typing"] as? Map<*, *> ?: emptyMap<String, Any>()
    val typers = typingMap.filter { (k, v) -> k != me && MessageRules.isTyping(v) }.keys.mapNotNull { it as? String }
    var typingTick by remember { mutableStateOf(0) }
    LaunchedEffect(typingMap) { if (typers.isNotEmpty()) { delay(MessageRules.TYPING_EXPIRY_MS); typingTick++ } }
    val typingSince = remember(typingMap) { System.currentTimeMillis() }
    val typingNow = typers.isNotEmpty() && System.currentTimeMillis() - typingSince < MessageRules.TYPING_EXPIRY_MS && typingTick >= 0
    val typingLine = when {
        !typingNow -> null
        conv?.isGroup != true -> "typing…"
        typers.size == 1 -> "${conv.names[typers[0]] ?: "Someone"} is typing…"
        else -> "${typers.size} people are typing…"
    }
    val unreadAtOpen = remember(cid) { CryptoHub.listedConversation(cid)?.unread(me) ?: 0 }
    // Online / last seen for a 1:1 (iOS ThreadView subtitle), redrawn each minute so "5 min. ago" moves.
    var minute by remember { mutableStateOf(0) }
    LaunchedEffect(cid) { while (true) { delay(60_000); minute++ } }
    val presenceLine = if (conv == null || conv.isGroup || s.receipts.iBlocked || minute < 0) null else
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
    notice?.let { n -> LaunchedEffect(n) { android.widget.Toast.makeText(ctx, n, android.widget.Toast.LENGTH_SHORT).show(); notice = null } }
    val ui = ThreadUi(
        title = conv?.displayName(me) ?: "",
        subtitle = typingLine ?: if (conv?.isGroup == true) "${conv.users.size} members" else presenceLine ?: "",
        photoUrl = conv?.displayPhoto(me),
        isGroup = conv?.isGroup == true,
        me = me,
        messages = s.messages,
        receipts = s.receipts,
        names = conv?.names ?: emptyMap(),
        photos = conv?.photos ?: emptyMap(),
        loaded = s.loaded,
        loadingOlder = s.loadingOlder,
        sendBlocked = vm.repo.sendBlockedReason(listed ?: conv),
        cid = cid,
        adminMayDelete = conv?.isGroup == true && me in admins,
        members = conv?.users ?: emptyList(),
        memberReads = (s.conv["lastRead"] as? Map<*, *>)?.mapNotNull { (k, v) ->
            (k as? String)?.let { it to com.fariin.app.data.Conversation.millis(v) }
        }?.toMap() ?: emptyMap(),
        myPhoto = conv?.photos?.get(me),
        unreadAtOpen = unreadAtOpen,
    )
    val atBottom = remember { MutableStateFlow(true) }
    val actions = ThreadActions(
        onReact = { m, e -> vm.viewModelScope.launch { if (!vm.repo.setReaction(m, e)) notice = "Couldn't react. Try again." } },
        onSendReply = { t, r -> vm.repo.sendText(t, r) },
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
            if (conv?.isGroup == true) nav.group(cid) else conv?.otherUid(me)?.takeIf { it.isNotEmpty() }?.let(nav.contact)
        },
        onNotice = { notice = it },
        // Drafts kept per chat (iOS Drafts): the box comes back with what was left in it.
        draft = remember(cid) { Drafts.text(cid) },
        onDraft = { Drafts.set(cid, it) },
    )
    // iOS: on open, once the first page is in, my badge goes to 0 and the read receipt goes out;
    // while the chat stays open and in front, every new message from them does the same.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(cid) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var handled: String? = null
            combine(vm.repo.state, atBottom) { st, bottom -> st to bottom }.collect { (st, bottom) ->
                if (!st.loaded || !bottom) return@collect
                val newest = st.messages.lastOrNull { it.authorId != me }?.rowId ?: ""
                if (newest != handled) { handled = newest; vm.repo.markRead() }
            }
        }
    }
    CompositionLocalProvider(LocalChatMedia provides vm.repo.media) {
        ThreadScreen(ui, onBack = onBack, onSend = vm.repo::sendText, onLoadOlder = vm.repo::loadOlder, onRetry = vm.repo::retry,
            onSendPhoto = { uri -> vm.repo.sendPhoto(app, uri) }, onSendVoice = vm.repo::sendVoice, actions = actions)
    }
}

