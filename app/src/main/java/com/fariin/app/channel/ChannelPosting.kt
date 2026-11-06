package com.fariin.app.channel

import androidx.compose.runtime.mutableStateMapOf
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

/**
 * An admin's "post without sound" switch for one channel (iOS ChannelPosting): kept on my own
 * users/{me}/channelPrefs/{cid} {silent}, read by the server for each new post, which then sends
 * the followers' banner without sound. On until switched off; read once per channel per run.
 */
object ChannelPosting {
    private val silent = mutableStateMapOf<String, Boolean>()
    private val loaded = HashSet<String>()

    fun isSilent(cid: String): Boolean = silent[cid] ?: false

    fun load(cid: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        if (!loaded.add(cid)) return
        FirebaseFirestore.getInstance().collection("users").document(uid).collection("channelPrefs").document(cid)
            .get().addOnSuccessListener { snap -> (snap.get("silent") as? Boolean)?.let { silent[cid] = it } }
            .addOnFailureListener { loaded.remove(cid) }
    }

    /** Shown at once; put back (and [onFail] told) if the write is refused. */
    fun setSilent(cid: String, on: Boolean, onFail: () -> Unit) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        silent[cid] = on
        FirebaseFirestore.getInstance().collection("users").document(uid).collection("channelPrefs").document(cid)
            .set(mapOf("silent" to on))
            .addOnFailureListener { silent[cid] = !on; onFail() }
    }
}
