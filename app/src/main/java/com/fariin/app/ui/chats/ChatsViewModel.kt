package com.fariin.app.ui.chats

import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
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
import kotlinx.coroutines.flow.distinctUntilChanged
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
    /** Every loaded conversation, before the list's filter (restricted chat, call history). */
    val all: List<Conversation> = emptyList(),
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

    /** Suspends until [me] is the signed-in account again. */
    private suspend fun signedInAgain() {
        val auth = com.google.firebase.auth.FirebaseAuth.getInstance()
        kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
            val l = object : com.google.firebase.auth.FirebaseAuth.AuthStateListener {
                override fun onAuthStateChanged(a: com.google.firebase.auth.FirebaseAuth) {
                    if (a.currentUser?.uid == me && cont.isActive) { a.removeAuthStateListener(this); cont.resume(Unit) {} }
                }
            }
            auth.addAuthStateListener(l)
            cont.invokeOnCancellation { auth.removeAuthStateListener(l) }
        }
    }

    /** Bumped by Retry on the list's error page: a fresh listener, not a timer. */
    private val reloadTick = kotlinx.coroutines.flow.MutableStateFlow(0)
    fun retry() { reloadTick.value++ }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val state: StateFlow<ChatsUiState> = reloadTick.flatMapLatest { repo.listen(me)
        // iOS SessionRecovery: a refused listener comes back. A fresh token, then a new listener, on
        // 1 s, 3 s, 10 s, 30 s, 60 s and every 60 s after; it stops only when the session itself is
        // over (account removed or disabled), which the sign-out path then handles.
        .retryWhen { cause, attempt ->
            val ended = runCatching { com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.getIdToken(true)?.await() }
                .exceptionOrNull() is com.google.firebase.auth.FirebaseAuthInvalidUserException
            if (ended) {
                emit(ConversationsRepository.State.Failed(com.fariin.app.data.LoadFailure.of(cause))); false
            } else if (com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid != me) {
                // Logged out (this list outlives it, kept per account): wait quietly for this same
                // account to sign back in, then listen again. Without this the list stayed on the
                // refusal it got at Log Out after a one-tap sign-in back.
                signedInAgain(); true
            } else {
                kotlinx.coroutines.delay(longArrayOf(1_000, 3_000, 10_000, 30_000, 60_000)[minOf(attempt.toInt(), 4)])
                true
            }
        }
        // Given up (signed out, account gone): the refusal ends here. Passed on, it closed the app
        // the moment Log Out signed the account out under a live chat list.
        .catch { e -> emit(ConversationsRepository.State.Failed(com.fariin.app.data.LoadFailure.of(e))) } }
        // Channels I follow are not in their `users`, so they come from my follow list (iOS
        // ConversationsRepository merges ChannelFollows.conversations the same way).
        .combine(com.fariin.app.channel.ChannelFollows.docs.combine(com.fariin.app.channel.ChannelFollows.entries) { _, _ -> Unit }) { s, _ ->
            if (s is ConversationsRepository.State.Loaded) {
                val followed = com.fariin.app.channel.ChannelFollows.conversations().filter { f -> s.conversations.none { it.id == f.id } }
                if (followed.isEmpty()) s else s.copy(conversations = s.conversations + followed)
            } else s
        }
        .combine(burnTick) { s, _ -> s }
        // A group whose leave is waiting (or running) is off the list at once (iOS LeaveGroupUndo.hiddenIds).
        .combine(com.fariin.app.data.LeaveUndo.hidden.combine(com.fariin.app.data.FormerGroups.version) { h, _ -> h }) { s, hidden ->
            // A former group listed again with me (added back) is mine again; one whose old copy the
            // list still holds is taken off (FormerGroups.hides).
            if (s is ConversationsRepository.State.Loaded) com.fariin.app.data.FormerGroups.noteList(s.conversations, me)
            if (s is ConversationsRepository.State.Loaded)
                s.copy(conversations = s.conversations.filter { it.id !in hidden && !com.fariin.app.data.FormerGroups.hides(it) }) else s
        }
        .onEach { s ->
            if (s is ConversationsRepository.State.Loaded) {
                scheduleListBurn(s.conversations)
                warmKeys(s.conversations)
                // iOS: delivered marks only from a server answer, for the chats the list shows.
                if (!s.fromCache && crypto != null) com.fariin.app.data.DeliveryReceipts.mark(ChatList.visible(s.conversations, me), me)
            }
        }
        // A chat with only a draft (or a call going on) stays listed (iOS visible).
        .combine(com.fariin.app.local.Drafts.map.map { it.keys }.distinctUntilChanged()
            .combine(LiveCallRow.live) { d, call -> LiveCallRow.cid(me, call)?.let { d + it } ?: d }) { s, k -> s to k }
        .map { (s, keep) ->
            when (s) {
                ConversationsRepository.State.Loading -> ChatsUiState(loading = true)
                is ConversationsRepository.State.Loaded ->
                    ChatsUiState(loading = false, chats = ChatList.visible(s.conversations, me, keep).let { list ->
                        // A closed chat whose newest message ran out on the timer: the preview is
                        // blanked until the server rewrites the summary (iOS burnedIfDue).
                        val now = System.currentTimeMillis().toDouble()
                        list.map { com.fariin.app.data.Disappearing.burnedIfDue(it, now) }
                    },
                        requests = s.conversations.filter { ChatList.isIncomingRequest(it, me) },
                        archived = s.conversations.filter { it.isArchived(me) && !it.isCleared(me) && !ChatList.isIncomingRequest(it, me) }
                            .sortedByDescending { it.displayUpdatedAt(me) },
                        all = s.conversations)
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
        com.fariin.app.data.Disappearing.pruneNotifications(due.associate { it.id to (it.updatedAtMillis to (it.lastExpiresAtMillis ?: 0.0)) })
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
