package com.fariin.app.data

import com.fariin.app.crypto.FariinCrypto
import com.fariin.app.crypto.Wire
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/**
 * One open chat: the live newest page, older pages on demand, the conversation's receipts, and
 * the text send. A port of the parts of Kulan-iOS ThreadRepository.swift and ChatService.swift
 * that a text chat needs; the Firestore writes are the iOS ones field for field, so they pass
 * the same rules.
 *
 * TODO (iOS has these): the disk cache for an instant open, the send queue that survives a kill,
 * delete, edit, reactions, typing, presence, pins, disappearing timers, media, link previews,
 * mentions, blocking, the knock counter for a first message to a stranger.
 */
class ThreadRepository(
    val cid: String,
    private val me: String,
    private val crypto: FariinCrypto,
    private val scope: CoroutineScope,
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
) {
    data class State(
        val messages: List<Message> = emptyList(),
        val receipts: Receipts = Receipts(),
        val loaded: Boolean = false,
        val canLoadOlder: Boolean = true,
        val loadingOlder: Boolean = false,
        val conv: Map<String, Any?> = emptyMap(),
        val error: String? = null,
    )

    private val convRef = db.collection("conversations").document(cid)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val order = ThreadOrder()
    /** Raw docs by id, so a key arriving later can re-open them. */
    private val liveDocs = LinkedHashMap<String, DocumentSnapshot>()
    private val olderDocs = LinkedHashMap<String, DocumentSnapshot>()
    /** Sends this phone has started, by clientId, until the server echo carries the same clientId. */
    private val pending = ConcurrentHashMap<String, Message>()
    private var msgReg: ListenerRegistration? = null
    private var convReg: ListenerRegistration? = null

    fun start() {
        if (msgReg != null) return
        msgReg = convRef.collection("messages")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(PAGE_SIZE)
            .addSnapshotListener { snap, err ->
                if (snap == null) {
                    _state.update { it.copy(loaded = true, error = err?.message) }
                    return@addSnapshotListener
                }
                // Never blank an open thread on an empty offline snapshot (iOS).
                if (snap.metadata.isFromCache && snap.isEmpty && liveDocs.isNotEmpty()) return@addSnapshotListener
                liveDocs.clear()
                for (d in snap.documents) liveDocs[d.id] = d
                if (!snap.metadata.isFromCache && snap.size() < PAGE_SIZE) _state.update { it.copy(canLoadOlder = false) }
                rebuild(loaded = true)
            }
        convReg = convRef.addSnapshotListener { snap, _ ->
            val data = snap?.data ?: return@addSnapshotListener
            _state.update { it.copy(receipts = Receipts.from(data, me, cid), conv = data) }
            if (data["e2ee"] == false) CryptoHub.notePlainGroup(cid)
        }
        // Keys in the background, then re-open what drew as "…" (iOS keyPreloadTask).
        scope.launch(Dispatchers.IO) {
            try { crypto.ensureReady() } catch (_: Exception) {}
            val members = memberUids()
            crypto.preloadKeys(members.filter { it != me })
            CryptoHub.bumpKeys()
            rebuild()
        }
    }

    fun stop() {
        msgReg?.remove(); msgReg = null
        convReg?.remove(); convReg = null
    }

    private suspend fun memberUids(): List<String> {
        if (!Wire.isGroupCid(cid)) return listOf(Wire.otherUid(cid, me))
        CryptoHub.listedConversation(cid)?.users?.takeIf { it.isNotEmpty() }?.let { return it }
        return try {
            (convRef.get().await().get("users") as? List<*>)?.filterIsInstance<String>() ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    /** iOS `loadOlder`: the page before the oldest held, by value (`start(at:)`), deduped by id. */
    fun loadOlder() {
        val s = _state.value
        if (!s.canLoadOlder || s.loadingOlder || !s.loaded) return
        val oldest = (liveDocs.values + olderDocs.values).mapNotNull { it.getTimestamp("createdAt") }.minOrNull() ?: return
        _state.update { it.copy(loadingOlder = true) }
        scope.launch {
            try {
                val snap = convRef.collection("messages")
                    .orderBy("createdAt", Query.Direction.DESCENDING)
                    .startAt(oldest)
                    .limit(PAGE_SIZE)
                    .get().await()
                var added = 0
                for (d in snap.documents) {
                    if (!liveDocs.containsKey(d.id) && !olderDocs.containsKey(d.id)) { olderDocs[d.id] = d; added++ }
                }
                // A short page from the server is the start of the chat; nothing new means the same.
                val more = added > 0 && (snap.metadata.isFromCache || snap.size() >= PAGE_SIZE)
                _state.update { it.copy(loadingOlder = false, canLoadOlder = more) }
                rebuild()
            } catch (_: Exception) {
                _state.update { it.copy(loadingOlder = false) }
            }
        }
    }

    @Synchronized
    private fun rebuild(loaded: Boolean? = null) {
        val all = LinkedHashMap<String, Message>()
        for (d in olderDocs.values + liveDocs.values) {
            val m = parse(d)
            all[m.rowId] = m
        }
        // A pending copy shows until its echo arrives (same clientId).
        for ((cidKey, p) in pending) {
            if (all.containsKey(cidKey)) pending.remove(cidKey) else all[cidKey] = p
        }
        val sorted = order.sort(all.values)
        _state.update { it.copy(messages = sorted, loaded = loaded ?: it.loaded) }
    }

    private fun parse(d: DocumentSnapshot): Message {
        val data = d.data ?: emptyMap()
        val m = Message.fromFirestore(d.id, data, cid) { raw, author -> crypto.decrypt(raw, cid, author) }
        return if (d.metadata.hasPendingWrites() && m.authorId == me) m.copy(sendState = Message.SendState.SENDING) else m
    }

    // ------------------------------------------------------------------ send

    /** The iOS SendTurn: one text at a time per chat, in tap order. */
    private val turn = Mutex()

    /** Why the composer cannot send here, or null. Message requests are not ported yet. */
    fun sendBlockedReason(conv: Conversation?): String? {
        if (conv == null || conv.isGroup || conv.startedBy.isEmpty() || conv.accepted) return null
        return when {
            conv.lastSender.isEmpty() -> "Message requests are not on Android yet."
            conv.lastSender == me -> "Waiting for them to accept your message."
            else -> null   // they wrote first: my reply accepts it
        }
    }

    fun sendText(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val tap = System.currentTimeMillis().toDouble()
        val clientId = UUID.randomUUID().toString().uppercase()
        pending[clientId] = Message(
            id = clientId, authorId = me, text = t, createdAtMillis = tap, hasServerTime = false,
            clientTs = tap, clientId = clientId, sendState = Message.SendState.SENDING,
        )
        rebuild()
        scope.launch {
            try {
                turn.withLock {
                    if (Wire.isGroupCid(cid)) sendGroup(t, clientId, tap) else sendDirect(t, clientId, tap)
                }
            } catch (e: Exception) {
                pending[clientId]?.let { pending[clientId] = it.copy(sendState = Message.SendState.FAILED) }
                rebuild()
            }
        }
    }

    /** Tap a failed bubble: send the same words again under a new clientId. */
    fun retry(m: Message) {
        val id = m.clientId ?: return
        pending.remove(id)
        sendText(m.text)
    }

    private suspend fun sendDirect(t: String, clientId: String, clientTs: Double) {
        val other = Wire.otherUid(cid, me)
        val cipher = crypto.encryptForConversation(cid, t)
        val local = CryptoHub.listedConversation(cid)
        val answering = local != null && !local.isGroup && local.startedBy.isNotEmpty() && !local.accepted &&
            local.lastSender.isNotEmpty() && local.lastSender != me
        // The seed, only when this phone's list does not already show both of us in it (iOS
        // `seedNeeded`; a brand-new chat is never opened from the Android list yet).
        if (local == null || !(local.users.contains(me) && local.users.contains(other))) {
            convRef.set(mapOf("users" to listOf(me, other), "updatedAt" to FieldValue.serverTimestamp()), SetOptions.merge()).await()
        }
        val batch = db.batch()
        batch.set(convRef.collection("messages").document(clientId), mapOf(
            "text" to cipher,
            "authorId" to me,
            "createdAt" to FieldValue.serverTimestamp(),
            "clientTs" to clientTs,
            "clientId" to clientId,
        ))
        val conv = hashMapOf<String, Any>(
            "lastMessage" to cipher,
            "lastSender" to me,
            "updatedAt" to FieldValue.serverTimestamp(),
            "unreadCount.$other" to FieldValue.increment(1),
        )
        if (answering) conv["accepted"] = true
        batch.update(convRef, conv)
        batch.commit().await()
    }

    private suspend fun sendGroup(t: String, clientId: String, clientTs: Double) {
        // iOS freshGroupMembers: the member list from the SERVER, so a member removed elsewhere is
        // not wrapped for; the cached list when offline.
        val members = try {
            val snap = convRef.get(Source.SERVER).await()
            if (snap.getBoolean("e2ee") == false) CryptoHub.notePlainGroup(cid)
            (snap.get("users") as? List<*>)?.filterIsInstance<String>()
        } catch (_: Exception) { null } ?: memberUids()
        val cipher = crypto.sealForGroup(t, members, cid)
        val batch = db.batch()
        batch.set(convRef.collection("messages").document(clientId), mapOf(
            "text" to cipher,
            "authorId" to me,
            "createdAt" to FieldValue.serverTimestamp(),
            "clientTs" to clientTs,
            "clientId" to clientId,
        ))
        val conv = hashMapOf<String, Any>(
            "lastMessage" to cipher,
            "lastSender" to me,
            "updatedAt" to FieldValue.serverTimestamp(),
        )
        // Words in the clear say they are a member's (Conversation.lastPlainTag).
        if (!cipher.startsWith("enc")) conv["lastPlainTag"] = Conversation.plainTag(cipher)
        for (m in members) if (m != me) conv["unreadCount.$m"] = FieldValue.increment(1)
        batch.update(convRef, conv)
        batch.commit().await()
    }

    // ------------------------------------------------------------------ read state

    private val readMutex = Mutex()
    @Volatile private var readOwed = false

    /**
     * iOS `resetUnread` + `markReadThrottled`: my badge to 0 (and my Mark as Unread flag off), then
     * `lastRead.<me>` stamped, which turns the other side's ticks solid. No timer: while one write
     * is in flight, newer calls fold into one trailing write.
     */
    fun markRead(readReceipts: Boolean = true) {
        if (readMutex.isLocked) { readOwed = true; return }
        scope.launch {
            readMutex.withLock {
                do {
                    readOwed = false
                    val local = CryptoHub.listedConversation(cid)
                    val update = hashMapOf<String, Any>("unreadCount.$me" to 0)
                    if (local?.markedUnread?.get(me) == true) update["markedUnread.$me"] = FieldValue.delete()
                    try { convRef.update(update).await() } catch (_: Exception) {}
                    val iBlocked = _state.value.receipts.iBlocked
                    if (readReceipts && !iBlocked) {
                        try {
                            convRef.set(mapOf("lastRead" to mapOf(me to FieldValue.serverTimestamp())), SetOptions.merge()).await()
                        } catch (_: Exception) {}
                    }
                } while (readOwed)
            }
        }
    }

    companion object {
        const val PAGE_SIZE = 60L
    }
}
