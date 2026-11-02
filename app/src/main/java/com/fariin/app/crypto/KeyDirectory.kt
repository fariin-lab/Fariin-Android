package com.fariin.app.crypto

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await

/**
 * Where public keys are published: `users/{uid}` fields `publicKey` (b64 of the 32-byte box
 * public key) and `publicKeyHistory` (array of every b64 key the account has published,
 * appended with arrayUnion). Crypto.swift:315-341, 591-608.
 */
interface KeyDirectory {
    sealed class Fetch {
        class Key(val publicKeyB64: String, val history: List<String>) : Fetch()
        data object NoKey : Fetch()
        data object Failed : Fetch()
    }

    suspend fun fetch(uid: String, serverOnly: Boolean = false): Fetch

    /** Writes [b64] when it differs from what is published, keeping the old one in the history. */
    suspend fun publishIfChanged(uid: String, b64: String)
}

/**
 * [mayPublish] is the hook for the iOS "a phone a newer sign-in replaced publishes nothing" check
 * (Crypto.swift:320-331, needs this install's device record in `users/{uid}/devices`). The device
 * registry is not ported yet, so it defaults to always publishing.
 */
class FirestoreKeyDirectory(
    private val db: FirebaseFirestore,
    private val mayPublish: suspend (uid: String) -> Boolean = { true },
) : KeyDirectory {

    override suspend fun fetch(uid: String, serverOnly: Boolean): KeyDirectory.Fetch = try {
        val snap = db.collection("users").document(uid).get(if (serverOnly) Source.SERVER else Source.DEFAULT).await()
        val b64 = snap.getString("publicKey")
        @Suppress("UNCHECKED_CAST")
        val history = (snap.get("publicKeyHistory") as? List<*>)?.filterIsInstance<String>() ?: emptyList()
        when {
            b64 != null && B64.decode(b64) != null -> KeyDirectory.Fetch.Key(b64, history)
            // An empty answer from the local copy can never remove a key.
            snap.metadata.isFromCache -> KeyDirectory.Fetch.Failed
            else -> KeyDirectory.Fetch.NoKey
        }
    } catch (e: FirebaseFirestoreException) {
        // A refusal is an answer, not a bad line (Crypto.swift:603-605).
        if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) KeyDirectory.Fetch.NoKey else KeyDirectory.Fetch.Failed
    } catch (_: Exception) {
        KeyDirectory.Fetch.Failed
    }

    override suspend fun publishIfChanged(uid: String, b64: String) {
        try {
            val ref = db.collection("users").document(uid)
            val published = ref.get().await().getString("publicKey")
            if (published == b64) return
            if (!mayPublish(uid)) return
            val keep = listOfNotNull(published, b64)
            ref.set(
                mapOf("publicKey" to b64, "publicKeyHistory" to FieldValue.arrayUnion(*keep.toTypedArray())),
                SetOptions.merge(),
            ).await()
        } catch (_: Exception) {
            // Retried on the next launch (publishPublicKey self-heals), as on iOS.
        }
    }
}
