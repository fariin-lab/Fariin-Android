package com.fariin.app.call

import com.fariin.app.data.Audience
import com.fariin.app.data.CryptoHub
import com.fariin.app.data.PrivacyRules
import com.fariin.app.crypto.Wire
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.concurrent.ConcurrentHashMap

/**
 * Who has limited calls, answered at the moment the call button is pressed (iOS CallPrivacyIndex).
 * Filled by every users document the app already reads (Person.from), so it costs no extra read.
 *
 * A MISS MEANS RING. A person whose settings were never seen is called, and their own phone
 * decides; this can never wrongly block a call on a guess. Not a security boundary: the callee
 * refuses on their side and the rules stand behind that. This only gives the caller a sentence.
 */
object CallPrivacyIndex {
    const val TITLE = "Can't Call"
    const val TEXT = "This person restricts who can call them."

    private val audiences = ConcurrentHashMap<String, Audience>()
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Set when a dial was refused here; the shell shows the alert and clears it. */
    val refused = MutableStateFlow(false)

    fun record(uid: String, privacy: Map<String, String>) {
        if (uid.isEmpty()) return
        val a = Audience.of(privacy["calls"]) ?: PrivacyRules.default("calls")
        audiences[uid] = PrivacyRules.normalized(a, "calls")
    }

    /** Unknown answers false, always. */
    fun refuses(uid: String): Boolean = when (audiences[uid]) {
        Audience.CONTACTS, Audience.NOBODY -> !iAmContactOf(uid)
        else -> false
    }

    /** iOS iAmContactOf: our 1:1 exists and is accepted (a request) or carries a message. */
    private fun iAmContactOf(uid: String): Boolean {
        val me = FirebaseAuth.getInstance().currentUser?.uid ?: return false
        val conv = CryptoHub.listedConversation(Wire.directCid(me, uid))?.takeIf { !it.isGroup } ?: return false
        return if (conv.startedBy.isNotEmpty()) conv.accepted else conv.lastMessageCipher.isNotEmpty()
    }

    /** Freshened on every press, so a person who opened calls again is not refused forever. */
    fun refresh(uid: String) {
        if (uid.isEmpty()) return
        io.launch {
            runCatching { FirebaseFirestore.getInstance().collection("users").document(uid).get().await() }.getOrNull()
                ?.let { s -> (s.get("privacy") as? Map<*, *>)?.let { m ->
                    record(uid, m.mapNotNull { (k, v) -> (k as? String)?.let { it to (v as? String ?: "") } }.toMap())
                } }
        }
    }

    /** Sign-out: the next account must not inherit who refuses calls. */
    fun clear() { audiences.clear(); refused.value = false }
}
