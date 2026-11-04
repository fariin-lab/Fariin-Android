package com.fariin.app.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/** The chat list as the shell last saw it, for code outside the list's own screens. */
object ChatsNow {
    /** Snapshot state, so a screen reading it (a restricted chat's menus) redraws when it changes. */
    var list: List<Conversation> by androidx.compose.runtime.mutableStateOf(emptyList())
    fun find(cid: String): Conversation? = list.firstOrNull { it.id == cid }
}

/**
 * Restricted chat (iOS ChatRestrictions). Each person's own switch (`restrictedBy[uid]`); the chat
 * is restricted while anyone's is on, and then nobody in it can forward, copy, save or share, or
 * take a screenshot. The older shared fields still count until a newer build clears them. Only the
 * person who turned it on can turn it off; the other can ask, and the request lapses after a day.
 * Every change leaves a line in the chat naming who made it.
 */
object ChatRestrictions {
    const val REQUEST_LIFETIME_MS = 24L * 3600 * 1000
    /** The writes outlive the page that asked for them (a switch flipped, then Back). */
    val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    private val FIELDS = listOf("noScreenshots", "noForwarding", "noSaving")
    private val db get() = FirebaseFirestore.getInstance()

    /** The open chat's restriction, read from the live list (every screen asks the same place). */
    fun isOn(cid: String): Boolean = cid.isNotEmpty() && ChatsNow.find(cid)?.isRestricted == true

    fun restrictedByMe(c: Conversation, me: String) = c.restrictedBy[me] == true
    fun restrictedByThem(c: Conversation, me: String) =
        c.restrictedBy.any { it.key != me && it.value } || (c.isRestricted && c.restrictedBy.isEmpty())

    /** A request to turn it off still open: made, not answered, not lapsed. */
    fun pendingRequestBy(c: Conversation, now: Long = System.currentTimeMillis()): String? {
        val by = c.restrictRequestBy ?: return null
        if (c.restrictRequestAtMs > 0 && now - c.restrictRequestAtMs > REQUEST_LIFETIME_MS) return null
        return by
    }

    suspend fun setMine(cid: String, me: String, myName: String, on: Boolean) {
        val ref = db.collection("conversations").document(cid)
        val data = runCatching { ref.get().await().data }.getOrNull().orEmpty()
        val mine = (data["restrictedBy"] as? Map<*, *>)?.get(me) == true
        if (mine == on) return
        val f = hashMapOf<String, Any>("restrictedBy.$me" to if (on) true else FieldValue.delete())
        for (r in FIELDS) if (data[r] != null) f[r] = false
        // Turning mine on answers any request I had made of them: it is moot now.
        if (on && (data["restrictRequest"] as? Map<*, *>)?.get("by") == me) f["restrictRequest"] = FieldValue.delete()
        if (runCatching { ref.update(f).await() }.isFailure) return
        notice(cid, me, "${myName.ifEmpty { "Someone" }} turned ${if (on) "on" else "off"} Restricted chat.")
    }

    suspend fun requestOff(cid: String, me: String, myName: String) {
        val ref = db.collection("conversations").document(cid)
        val ok = runCatching { ref.update("restrictRequest", mapOf("by" to me, "at" to FieldValue.serverTimestamp())).await() }.isSuccess
        if (ok) notice(cid, me, "${myName.ifEmpty { "Someone" }} asked to turn off Restricted chat.", mapOf("restrictRequest" to true))
    }

    /** Their request, answered by me: accepting turns MY switch off; either way the request closes. */
    suspend fun answer(cid: String, me: String, myName: String, accept: Boolean) {
        val ref = db.collection("conversations").document(cid)
        val f = hashMapOf<String, Any>("restrictRequest" to FieldValue.delete())
        if (accept) f["restrictedBy.$me"] = FieldValue.delete()
        if (runCatching { ref.update(f).await() }.isFailure) return
        val n = myName.ifEmpty { "Someone" }
        notice(cid, me, if (accept) "$n turned off Restricted chat." else "$n kept Restricted chat on.")
    }

    private suspend fun notice(cid: String, me: String, text: String, extra: Map<String, Any> = emptyMap()) {
        val ref = db.collection("conversations").document(cid)
        runCatching {
            val b = db.batch()
            b.set(ref.collection("messages").document(), mapOf("text" to text, "authorId" to me, "type" to "system",
                "createdAt" to FieldValue.serverTimestamp()) + extra)
            b.update(ref, mapOf("lastMessage" to text, "lastSender" to me, "updatedAt" to FieldValue.serverTimestamp()))
            b.commit().await()
        }
    }
}

/** Restricted while anyone's switch is on (or an older build's shared fields say so). */
val Conversation.isRestricted: Boolean get() = restrictedBy.isNotEmpty() || restrictLegacy
