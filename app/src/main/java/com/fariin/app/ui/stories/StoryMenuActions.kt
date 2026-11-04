package com.fariin.app.ui.stories

import android.content.Context
import android.content.Intent
import com.fariin.app.data.PostedStoriesRules
import com.fariin.app.data.Story
import com.fariin.app.media.GallerySaver
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

/** The story "..." menu's actions (iOS StoriesViews storyAction*): Save, Share, Report. */
object StoryMenuActions {
    /** The story's picture or clip into the gallery; the words for the toast. */
    suspend fun save(c: Context, s: Story): String = withContext(Dispatchers.IO) {
        val ok = runCatching {
            val f = File(c.cacheDir, "story-save-${s.id}.${if (s.isVideo) "mp4" else "jpg"}")
            URL(s.mediaUrl).openStream().use { input -> f.outputStream().use { input.copyTo(it) } }
            GallerySaver.save(c, f, s.isVideo).also { f.delete() }
        }.getOrDefault(false)
        if (ok) "Saved" else "Couldn't save. Check your connection and try again."
    }

    /** The story's address through Android's share sheet; mine carries my @name, as iOS links do. */
    suspend fun shareLink(c: Context, s: Story, me: String) {
        val handle = if (s.authorUid == me) runCatching {
            FirebaseFirestore.getInstance().collection("users").document(me).get().await().getString("handle")
        }.getOrNull() else null
        val link = PostedStoriesRules.storyLink(s.id, handle)
        withContext(Dispatchers.Main) {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link)
            c.startActivity(Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** iOS reportStory: one `reports` document; the server reads it. */
    suspend fun report(me: String, s: Story) {
        runCatching {
            FirebaseFirestore.getInstance().collection("reports").add(mapOf(
                "type" to "story", "storyId" to s.id, "authorUid" to s.authorUid, "reporterUid" to me,
                "createdAt" to FieldValue.serverTimestamp())).await()
        }
    }
}
