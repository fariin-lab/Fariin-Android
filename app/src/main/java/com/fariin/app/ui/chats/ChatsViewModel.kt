package com.fariin.app.ui.chats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fariin.app.data.ChatList
import com.fariin.app.data.Conversation
import com.fariin.app.data.ConversationsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** What the Chats tab draws. */
data class ChatsUiState(
    val loading: Boolean = true,
    val chats: List<Conversation> = emptyList(),
    val error: String? = null,
)

class ChatsViewModel(
    private val me: String,
    repo: ConversationsRepository = ConversationsRepository(),
) : ViewModel() {
    val state: StateFlow<ChatsUiState> = repo.listen(me)
        .map { s ->
            when (s) {
                ConversationsRepository.State.Loading -> ChatsUiState(loading = true)
                is ConversationsRepository.State.Loaded ->
                    ChatsUiState(loading = false, chats = ChatList.visible(s.conversations, me))
                is ConversationsRepository.State.Failed -> ChatsUiState(loading = false, error = s.message)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatsUiState())
}

/** Chats with something unread, the number on the Chats tab (blocked chats do not count). */
fun unreadChatsBadge(chats: List<Conversation>, me: String): Int =
    chats.count { !it.isBlockedByMe(me) && it.hasUnreadMark(me) }
