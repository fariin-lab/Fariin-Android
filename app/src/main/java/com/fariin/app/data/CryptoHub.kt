package com.fariin.app.data

import android.content.Context
import com.fariin.app.crypto.FariinCrypto
import com.fariin.app.crypto.FirestoreKeyDirectory
import com.fariin.app.crypto.Wire
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The app's one [FariinCrypto] (iOS `Crypto.shared`), plus the little shared state the chat
 * screens need around it. Built on first use; nothing here runs in demo mode.
 */
object CryptoHub {
    @Volatile private var instance: FariinCrypto? = null

    /** When this phone's keypair was made (ms), or null when not known or not set up yet. */
    fun keysSince(): Long? = instance?.keysSince()

    /** Groups known to send in the clear (`e2ee == false`). The switch only goes one way, so a
     *  remembered "plain" never goes stale (ChatService.groupIsPlain). */
    private val plainGroups = ConcurrentHashMap.newKeySet<String>()

    /** The chat list's latest copy, by id (iOS reads `ConversationsRepository.shared.conversations`). */
    private val listed = ConcurrentHashMap<String, Conversation>()

    /** Goes up whenever a key arrives that may open text drawn earlier as "…". */
    private val _epoch = MutableStateFlow(0)
    val keysEpoch: StateFlow<Int> = _epoch.asStateFlow()

    fun get(context: Context): FariinCrypto = instance ?: synchronized(this) {
        instance ?: FariinCrypto.android(
            context.applicationContext,
            FirestoreKeyDirectory(com.fariin.app.Fb.db,
                // A phone a newer sign-in replaced publishes nothing (Crypto.swift:320-331).
                mayPublish = { uid -> com.fariin.app.device.DeviceRegistry.mayPublish(context.applicationContext, uid) }),
            { com.fariin.app.Fb.auth.currentUser?.uid },
            groupIsPlain = { cid -> isPlainGroup(cid) },
        ).also { instance = it }
    }

    /** Only after [get] was called once (the signed-in shell does that first). */
    val crypto: FariinCrypto get() = instance ?: error("CryptoHub.get(context) not called yet")

    fun isPlainGroup(cid: String): Boolean {
        if (!Wire.isGroupCid(cid)) return false
        if (plainGroups.contains(cid)) return true
        val e2eeOff = listed[cid]?.e2eeOff ?: false
        if (e2eeOff) plainGroups.add(cid)
        return e2eeOff
    }

    fun notePlainGroup(cid: String) { if (Wire.isGroupCid(cid)) plainGroups.add(cid) }

    fun noteListed(convs: List<Conversation>) {
        listed.clear()
        for (c in convs) {
            listed[c.id] = c
            if (c.isGroup && c.e2eeOff) plainGroups.add(c.id)
        }
        // Old blocks kept on the shared chat move to my private list (waits until that list loaded).
        BlockList.migrate(convs)
    }

    fun listedConversation(cid: String): Conversation? = listed[cid]

    /** Every chat the list holds now (Settings: Hide From, Blocked Users names, the account file). */
    fun listedAll(): List<Conversation> = listed.values.toList()

    fun bumpKeys() { _epoch.value = _epoch.value + 1 }

    /** Sign-out: forget the keys in memory (the stored key stays, keys are per uid). */
    fun signedOut() {
        instance?.wipeIdentity()
        listed.clear()
        bumpKeys()
    }
}

/** The chat list's preview opener: the real decrypt, with the iOS sentinels ("…", the lock). */
class CryptoDecryptor(private val crypto: FariinCrypto) : MessageDecryptor {
    /**
     * D5 (device-storage plan, same as iOS c4a7f23a7): a private chat whose server summary has no
     * words falls back to the newest text this phone stored, when that is at least as new. Never a
     * channel or a public group (their summary stays the server's).
     */
    private fun local(conv: Conversation): String? {
        if (conv.isChannel || (conv.isGroup && conv.isPublicGroup)) return null
        // A line blanked because its timer ran out stays blank: the phone's copy of that
        // message must not come back in its place (iOS blankLine / localOverlay).
        conv.lastExpiresAtMillis?.let { if (it <= System.currentTimeMillis()) return null }
        val (text, at) = com.fariin.app.local.LocalDb.summary(conv.id) ?: return null
        return text.takeIf { it.isNotEmpty() && at + 2_000 >= conv.updatedAtMillis.toLong() }
    }

    override fun previewText(conv: Conversation, me: String): String? {
        val raw = conv.lastMessageCipher
        if (raw.isEmpty()) return local(conv)
        // Released keys on the conversation itself open an old sealed group line (Crypto.swift rk).
        if (conv.isGroup && conv.releasedKeys.isNotEmpty()) crypto.noteReleased(conv.id, conv.releasedKeys)
        // A crafted line must never crash the chat list: it shows as "Message".
        return try { crypto.decrypt(raw, conv.id, conv.lastSender) } catch (_: Throwable) { null }
    }

    override fun open(conv: Conversation, sealed: String, author: String): String? =
        try { crypto.decrypt(sealed, conv.id, author) } catch (_: Throwable) { null }

    override fun peekPreview(conv: Conversation, me: String): String? {
        val raw = conv.lastMessageCipher
        if (raw.isEmpty()) return local(conv)
        return crypto.cachedDecrypt(raw, conv.id, conv.lastSender) ?: ChatList.PEEK_MISS
    }

    override fun peekOpen(conv: Conversation, sealed: String, author: String): String? =
        try { crypto.cachedDecrypt(sealed, conv.id, author) ?: ChatList.PEEK_MISS } catch (_: Exception) { null }
}
