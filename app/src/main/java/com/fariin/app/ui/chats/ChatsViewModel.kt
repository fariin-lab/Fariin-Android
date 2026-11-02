package com.fariin.app.ui.chats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fariin.app.data.ChatList
import com.fariin.app.data.Conversation
import com.fariin.app.data.ConversationsRepository
import com.fariin.app.data.CryptoHub
import com.fariin.app.crypto.FariinCrypto
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** What the Chats tab draws. */
data class ChatsUiState(
    val loading: Boolean = true,
    val chats: List<Conversation> = emptyList(),
    val error: String? = null,
    /** Incoming message requests (they live on their own page, iOS MessageRequestsView). */
    val requests: List<Conversation> = emptyList(),
)

class ChatsViewModel(
    private val me: String,
    repo: ConversationsRepository = ConversationsRepository(),
    private val crypto: FariinCrypto? = null,
) : ViewModel() {
    private val preloaded = HashSet<String>()

    val state: StateFlow<ChatsUiState> = repo.listen(me)
        // Channels I follow are not in their `users`, so they come from my follow list (iOS
        // ConversationsRepository merges ChannelFollows.conversations the same way).
        .combine(com.fariin.app.channel.ChannelFollows.docs.combine(com.fariin.app.channel.ChannelFollows.entries) { _, _ -> Unit }) { s, _ ->
            if (s is ConversationsRepository.State.Loaded) {
                val followed = com.fariin.app.channel.ChannelFollows.conversations().filter { f -> s.conversations.none { it.id == f.id } }
                if (followed.isEmpty()) s else s.copy(conversations = s.conversations + followed)
            } else s
        }
        .onEach { s ->
            if (s is ConversationsRepository.State.Loaded) {
                warmKeys(s.conversations)
                // iOS: delivered marks only from a server answer, for the chats the list shows.
                if (!s.fromCache && crypto != null) com.fariin.app.data.DeliveryReceipts.mark(ChatList.visible(s.conversations, me), me)
            }
        }
        .map { s ->
            when (s) {
                ConversationsRepository.State.Loading -> ChatsUiState(loading = true)
                is ConversationsRepository.State.Loaded ->
                    ChatsUiState(loading = false, chats = ChatList.visible(s.conversations, me),
                        requests = s.conversations.filter { ChatList.isIncomingRequest(it, me) })
                is ConversationsRepository.State.Failed -> ChatsUiState(loading = false, error = s.message)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatsUiState())

    /** Fetch every chat partner's key once, then redraw the previews that said "…" (iOS heals the
     *  list the same way when a peer key lands). */
    private fun warmKeys(convs: List<Conversation>) {
        CryptoHub.noteListed(convs)
        val c = crypto ?: return
        val fresh = convs.flatMap { it.users }.filter { it != me && it.isNotEmpty() && preloaded.add(it) }
        if (fresh.isEmpty()) return
        viewModelScope.launch {
            try { c.ensureReady() } catch (_: Exception) {}
            c.preloadKeys(fresh)
            CryptoHub.bumpKeys()
        }
    }
}

/** Chats with something unread, the number on the Chats tab (blocked chats do not count). */
fun unreadChatsBadge(chats: List<Conversation>, me: String): Int =
    chats.count { !it.isBlockedByMe(me) && it.hasUnreadMark(me) }
