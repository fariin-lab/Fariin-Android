package com.fariin.app.data

import com.google.firebase.firestore.FieldValue
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.tasks.await

/**
 * Edit Story (iOS 2026-11-20, rules live): my posted story changed in place.
 * - Nothing changed: nothing written.
 * - Only the caption: `caption` + `captionEditedAt`.
 * - A new picture: uploaded create-only to `stories/{id}/e{rev}-{tail}/photo.jpg`, then ONE update of
 *   mediaUrl, blurThumb, stickers, mediaRev = old + 1, editedAt (and the caption with its time when it
 *   changed). The rules allow a new mediaUrl only with mediaRev + 1 and a URL under that folder.
 * The public copy of an Everyone story follows (best effort).
 */
object StoryEdit {
    class Failed(message: String) : Exception(message)

    suspend fun save(story: Story, newMedia: StoryMedia.Prepared?, caption: String, onProgress: (Float) -> Unit = {}) {
        val me = com.fariin.app.Fb.auth.currentUser?.uid ?: throw Failed("Not signed in.")
        if (story.authorUid != me) throw Failed("Only your own story can be edited.")
        val cap = caption.trim()
        val captionChanged = cap != story.caption.trim()
        if (newMedia == null && !captionChanged) return
        val db = com.fariin.app.Fb.db
        val ref = db.collection("stories").document(story.id)
        val update = HashMap<String, Any>()
        if (captionChanged) { update["caption"] = cap; update["captionEditedAt"] = FieldValue.serverTimestamp() }
        if (newMedia != null) {
            val rev = ((ref.get().await().get("mediaRev") as? Number)?.toInt() ?: 0) + 1
            val tail = java.util.UUID.randomUUID().toString().replace("-", "").take(10)
            val file = FirebaseStorage.getInstance().reference.child("stories/${story.id}/e$rev-$tail/photo.jpg")
            val task = file.putBytes(newMedia.jpeg, StorageMetadata.Builder().setContentType("image/jpeg").build())
            task.addOnProgressListener { s -> if (s.totalByteCount > 0) onProgress(s.bytesTransferred.toFloat() / s.totalByteCount) }
            task.await()
            val url = file.downloadUrl.await().toString()
            update["mediaUrl"] = url
            update["thumbUrl"] = ""
            update["blurThumb"] = newMedia.blurThumb
            update["stickers"] = newMedia.stickers.map { StoryTaps.asMap(it) }
            update["mediaRev"] = rev
            update["editedAt"] = FieldValue.serverTimestamp()
        }
        try { ref.update(update).await() } catch (e: Exception) { throw Failed("Couldn't save the story. Try again.") }
        if (story.isPublic) runCatching {
            val mirror = HashMap<String, Any>(update)
            mirror.remove("mediaRev")
            db.collection("users").document(me).collection("publicStories").document(story.id).update(mirror).await()
        }
    }
}
