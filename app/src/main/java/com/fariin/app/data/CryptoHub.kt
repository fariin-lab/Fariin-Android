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
            FirestoreKeyDirectory(FirebaseFirestore.getInstance(),
                // A phone a newer sign-in replaced publishes nothing (Crypto.swift:320-331).
                mayPublish = { uid -> com.fariin.app.device.DeviceRegistry.mayPublish(context.applicationContext, uid) }),
            { FirebaseAuth.getInstance().currentUser?.uid },
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
    override fun previewText(conv: Conversation, me: String): String? {
        val raw = conv.lastMessageCipher
        if (raw.isEmpty()) return null
        // Released keys on the conversation itself open an old sealed group line (Crypto.swift rk).
        if (conv.isGroup && conv.releasedKeys.isNotEmpty()) crypto.noteReleased(conv.id, conv.releasedKeys)
        return crypto.decrypt(raw, conv.id, conv.lastSender)
    }

    override fun open(conv: Conversation, sealed: String, author: String): String? =
        try { crypto.decrypt(sealed, conv.id, author) } catch (_: Exception) { null }
}
