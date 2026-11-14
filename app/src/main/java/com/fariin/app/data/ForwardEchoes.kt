package com.fariin.app.data

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A forward's bubble in the chat it goes to, before the server has it (owner 2026-11-14, the big
 * messengers' forward): one chat picked opens it at once, and the forward is already at the bottom,
 * sending, instead of arriving a second later. The real message carries the same clientId, so the
 * chat's own list swaps the echo for it ([ThreadRepository.rebuild]); a forward that fails takes
 * its echo away.
 */
object ForwardEchoes {
    private val byChat = ConcurrentHashMap<String, ConcurrentHashMap<String, Message>>()
    private val _version = MutableStateFlow(0)
    /** Bumps on every change, so an open chat redraws. */
    val version: StateFlow<Int> = _version

    /** The bubble for [m] forwarded by [me] into a chat, keyed by the clientId the real send will use. */
    fun echoOf(m: Message, me: String, clientId: String, forwarded: Boolean, now: Double): Message = m.copy(
        id = clientId, clientId = clientId, authorId = me, createdAtMillis = now, hasServerTime = false, clientTs = now,
        sendState = Message.SendState.SENDING, forwarded = forwarded, replyTo = null, edited = false, deletedBy = null,
        mentions = emptyList(), held = false, reactions = emptyMap(), views = 0, quoteCount = 0, quoteFaces = emptyList(),
        replyCount = 0, repliesOff = null, replyFaces = emptyList(), reactionCounts = emptyMap(), expiresAtMillis = null,
        disappearSeconds = null, pollClosed = false, pollCounts = emptyMap(), pollVoters = 0, pollTallyAt = 0.0,
        bubbleStyle = null, bubbleDecor = false, uploading = false,
    )

    fun add(cid: String, echo: Message) {
        val k = echo.clientId ?: return
        byChat.getOrPut(cid) { ConcurrentHashMap() }[k] = echo
        _version.value++
    }

    fun remove(cid: String, clientId: String) {
        if (byChat[cid]?.remove(clientId) != null) _version.value++
    }

    fun of(cid: String): Collection<Message> = byChat[cid]?.values ?: emptyList()

    /** Is a forward of exactly this kind one the chat can draw ahead of the server? */
    fun drawable(m: Message): Boolean = !m.isSticker && !m.isGif && !m.viewOnce && !m.deleted && m.sendState == null
}
