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
 * - A new clip (iOS replaceStoryMedia): the exported clip and its poster go to the same folder as
 *   video.mp4 and thumb.jpg, and the update also carries thumbUrl, duration, width, height, bytes
 *   and preloadPrefix.
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
            val folder = FirebaseStorage.getInstance().reference.child("stories/${story.id}/e$rev-$tail")
            val clip = newMedia.video
            if (clip != null) {
                // The poster first, then the clip with the progress (iOS).
                val thumb = folder.child("thumb.jpg")
                thumb.putBytes(newMedia.jpeg, StorageMetadata.Builder().setContentType("image/jpeg").build()).await()
                val file = folder.child("video.mp4")
                val task = file.putFile(clip.file, StorageMetadata.Builder().setContentType("video/mp4").build())
                task.addOnProgressListener { s -> if (s.totalByteCount > 0) onProgress(s.bytesTransferred.toFloat() / s.totalByteCount) }
                task.await()
                update["mediaUrl"] = file.downloadUrl.await().toString()
                update["thumbUrl"] = thumb.downloadUrl.await().toString()
                update["duration"] = clip.info.durationSec
                update["width"] = clip.info.width
                update["height"] = clip.info.height
                update["bytes"] = clip.info.bytes
                update["preloadPrefix"] = minOf(clip.info.bytes, 1536L * 1024)
            } else {
                val file = folder.child("photo.jpg")
                val task = file.putBytes(newMedia.jpeg, StorageMetadata.Builder().setContentType("image/jpeg").build())
                task.addOnProgressListener { s -> if (s.totalByteCount > 0) onProgress(s.bytesTransferred.toFloat() / s.totalByteCount) }
                task.await()
                update["mediaUrl"] = file.downloadUrl.await().toString()
                update["thumbUrl"] = ""
            }
            update["blurThumb"] = newMedia.blurThumb
            update["stickers"] = newMedia.stickers.map { StoryTaps.asMap(it) }
            update["mediaRev"] = rev
            update["editedAt"] = FieldValue.serverTimestamp()
        }
        try { ref.update(update).await() } catch (e: Exception) { throw Failed("Couldn't save the story. Try again.") }
        // The live document, not the snapshot: Edit Viewers may have changed the audience (iOS).
        val live = runCatching { ref.get().await().data }.getOrNull()
        val isPublic = (live?.get("public") as? Boolean) ?: story.isPublic
        if (isPublic && !story.oneTime && story.groupId.isNullOrEmpty()) runCatching {
            // The mirror rule is a fixed key list without mediaRev, editedAt or captionEditedAt:
            // any of those refuses the whole write, so only these keys go (iOS).
            val mirror = HashMap<String, Any>()
            mirror["caption"] = cap
            if (newMedia != null) {
                mirror["mediaUrl"] = update["mediaUrl"] as String
                mirror["blurThumb"] = newMedia.blurThumb
                mirror["stickers"] = newMedia.stickers.map { StoryTaps.asMap(it) }
                // A clip's poster and length follow too (iOS); a photo's mirror keeps its keys as before.
                if (newMedia.video != null) {
                    mirror["thumbUrl"] = update["thumbUrl"] as String
                    mirror["duration"] = update["duration"] as Double
                }
            }
            db.collection("users").document(me).collection("publicStories").document(story.id).update(mirror).await()
        }
        // Tell only the @names the edit added, checked against the live audience (iOS).
        @Suppress("UNCHECKED_CAST")
        val tokens = (live?.get("recipientUids") as? List<String>).orEmpty()
        if (captionChanged && story.groupId.isNullOrEmpty() && tokens.isNotEmpty()) {
            val thumb = if (newMedia?.video != null) update["thumbUrl"] as String else (update["mediaUrl"] as? String) ?: story.previewUrl
            StoryMentions.send(story.id, cap, thumb, emptySet(), me,
                recipientTokens = tokens.toSet(), skipping = StoryMentions.handles(story.caption))
        }
    }
}
