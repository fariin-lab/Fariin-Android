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

/** Opens a chat from anywhere under the signed-in shell (the chat list's rows use it). */
val LocalOpenChat = staticCompositionLocalOf<(Conversation) -> Unit> { {} }

class ThreadViewModel(cid: String, me: String, crypto: FariinCrypto, context: Context) : ViewModel() {
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
    val ui = ThreadUi(
        title = conv?.displayName(me) ?: "",
        subtitle = if (conv?.isGroup == true) "${conv.users.size} members" else "",
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
    )
    // iOS: on open, once the first page is in, my badge goes to 0 and the read receipt goes out;
    // while the chat stays open and in front, every new message from them does the same.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(cid) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var handled: String? = null
            vm.repo.state.collect { st ->
                if (!st.loaded) return@collect
                val newest = st.messages.lastOrNull { it.authorId != me }?.rowId ?: ""
                if (newest != handled) { handled = newest; vm.repo.markRead() }
            }
        }
    }
    CompositionLocalProvider(LocalChatMedia provides vm.repo.media) {
        ThreadScreen(ui, onBack = onBack, onSend = vm.repo::sendText, onLoadOlder = vm.repo::loadOlder, onRetry = vm.repo::retry,
            onSendPhoto = { uri -> vm.repo.sendPhoto(app, uri) }, onSendVoice = vm.repo::sendVoice)
    }
}

