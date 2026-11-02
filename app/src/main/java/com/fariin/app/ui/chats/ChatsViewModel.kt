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
    /** Chats I archived (the overflow menu's Archive page). */
    val archived: List<Conversation> = emptyList(),
)

class ChatsViewModel(
    private val me: String,
    repo: ConversationsRepository = ConversationsRepository(),
    private val crypto: FariinCrypto? = null,
) : ViewModel() {
    private val preloaded = HashSet<String>()
    /** Bumped when a chat's newest message falls due on its timer (iOS scheduleListBurn). */
    private val burnTick = kotlinx.coroutines.flow.MutableStateFlow(0)
    private var burnJob: kotlinx.coroutines.Job? = null

    val state: StateFlow<ChatsUiState> = repo.listen(me)
        // Channels I follow are not in their `users`, so they come from my follow list (iOS
        // ConversationsRepository merges ChannelFollows.conversations the same way).
        .combine(com.fariin.app.channel.ChannelFollows.docs.combine(com.fariin.app.channel.ChannelFollows.entries) { _, _ -> Unit }) { s, _ ->
            if (s is ConversationsRepository.State.Loaded) {
                val followed = com.fariin.app.channel.ChannelFollows.conversations().filter { f -> s.conversations.none { it.id == f.id } }
                if (followed.isEmpty()) s else s.copy(conversations = s.conversations + followed)
            } else s
        }
        .combine(burnTick) { s, _ -> s }
        .onEach { s ->
            if (s is ConversationsRepository.State.Loaded) {
                scheduleListBurn(s.conversations)
                warmKeys(s.conversations)
                // iOS: delivered marks only from a server answer, for the chats the list shows.
                if (!s.fromCache && crypto != null) com.fariin.app.data.DeliveryReceipts.mark(ChatList.visible(s.conversations, me), me)
            }
        }
        .map { s ->
            when (s) {
                ConversationsRepository.State.Loading -> ChatsUiState(loading = true)
                is ConversationsRepository.State.Loaded ->
                    ChatsUiState(loading = false, chats = ChatList.visible(s.conversations, me).let { list ->
                        // A closed chat whose newest message ran out on the timer: the preview is
                        // blanked until the server rewrites the summary (iOS burnedIfDue).
                        val now = System.currentTimeMillis().toDouble()
                        list.map { com.fariin.app.data.Disappearing.burnedIfDue(it, now) }
                    },
                        requests = s.conversations.filter { ChatList.isIncomingRequest(it, me) },
                        archived = s.conversations.filter { it.isArchived(me) && !it.isCleared(me) && !ChatList.isIncomingRequest(it, me) }
                            .sortedByDescending { it.displayUpdatedAt(me) })
                is ConversationsRepository.State.Failed -> ChatsUiState(loading = false, error = s.message)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatsUiState())

    /** Asks the server to delete what is due, then waits for the next expiry; something due that
     *  the server has not rewritten yet gets another look every 30 s. */
    private fun scheduleListBurn(convs: List<Conversation>) {
        burnJob?.cancel()
        val now = System.currentTimeMillis().toDouble()
        val due = convs.filter { c -> c.lastExpiresAtMillis?.let { it <= now } == true }
        if (crypto != null) for (c in due) com.fariin.app.data.Disappearing.askBurn(c.id, emptyList())
        var next = convs.mapNotNull { it.lastExpiresAtMillis }.filter { it > now }.minOrNull()
        if (due.isNotEmpty()) next = minOf(next ?: Double.MAX_VALUE, now + 30_000)
        if (next == null) return
        val wait = maxOf(200L, (next - now).toLong() + 200L)
        burnJob = viewModelScope.launch { kotlinx.coroutines.delay(wait); burnTick.value++ }
    }

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
