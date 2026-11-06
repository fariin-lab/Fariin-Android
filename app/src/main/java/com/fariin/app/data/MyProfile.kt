package com.fariin.app.data

import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * My own profile, the fields the Settings header shows. Same names as iOS UserProfile
 * (Models.swift): `users/{uid}` with name, handle, photoUrl, about.
 */
data class MyProfile(
    val uid: String,
    val name: String = "",
    val handle: String = "",
    val photoUrl: String? = null,
    val about: String = "",
    val links: List<ProfileLink> = emptyList(),
    /** iOS Verification.showsBadge: verification.status active (or the old isVerified). */
    val verified: Boolean = false,
) {
    companion object {
        fun fromFirestore(uid: String, data: Map<String, Any?>): MyProfile = MyProfile(
            uid = uid,
            name = data["name"] as? String ?: "",
            handle = data["handle"] as? String ?: "",
            photoUrl = (data["photoUrl"] as? String)?.takeIf { it.isNotEmpty() },
            about = data["about"] as? String ?: "",
            links = ProfileLink.list(data["links"]),
            verified = (data["verification"] as? Map<*, *>)?.let { v ->
                (v["status"] as? String ?: if (v["isVerified"] == true) "active" else "revoked") == "active"
            } ?: false,
        )
    }
}

/**
 * A live read of `users/{uid}` (the rules allow `get` to any signed-in user). Read only:
 * nothing here writes to Firestore. Emits null until the first snapshot or when it fails.
 */
class MyProfileRepository(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
) {
    fun listen(uid: String): Flow<MyProfile?> = callbackFlow {
        val reg = db.collection("users").document(uid).addSnapshotListener { snap, _ ->
            val data = snap?.data ?: return@addSnapshotListener
            trySend(MyProfile.fromFirestore(uid, data))
        }
        awaitClose { reg.remove() }
    }
}
