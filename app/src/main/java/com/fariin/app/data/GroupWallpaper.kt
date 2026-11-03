package com.fariin.app.data

import android.content.Context
import android.graphics.Bitmap
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * ONE WALLPAPER FOR THE WHOLE GROUP (iOS WallpaperStore.publishGroupWallpaper + ChatService
 * uploadGroupWallpaper / setGroupWallpaper): only someone who may change group info sets it, and
 * every member's phone draws it over their own pick. The value is ChatWallpaper.stored for a theme,
 * colour or none, and "u:<download link>" for a photo, which goes up as a plain JPEG at
 * profiles/group_<cid>-wp<millis>.jpg (a new name each time, so the link changes with it).
 * Written by the server: setGroupWallpaper {cid, wallpaper} -> {ok}. One notice line follows.
 */
object GroupWallpaper {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** The notice line iOS writes after the change. */
    fun noticeText(name: String, stored: String): String =
        if (stored == "none") "$name removed the group wallpaper" else "$name changed the group wallpaper"

    /**
     * Apply in a group: shown at once on this phone, then (a photo uploaded first) written for
     * everyone. On failure the chat goes back to the group's previous wallpaper and [onError] says why.
     */
    fun publish(c: Context, cid: String, w: ChatWallpaper, onError: (String) -> Unit) {
        val app = c.applicationContext
        val previous = ChatLookStore.groupStored(app, cid)
        if (w is ChatWallpaper.Photo) {
            // The same photo the group already has: nothing to send.
            if (ChatLookRules.photoLink(previous)?.let { ChatLookRules.photoId(it) } == w.id) return
        } else if (previous == w.stored || (previous == null && w == ChatWallpaper.None)) return
        ChatLookStore.setGroupPending(cid, w)
        scope.launch {
            try {
                var stored = w.stored
                if (w is ChatWallpaper.Photo) {
                    val jpeg = withContext(Dispatchers.IO) { ChatLookStore.libraryImage(app, w.id)?.let { uploadJpeg(it) } }
                        ?: throw IllegalStateException("Try choosing the photo again.")
                    val link = upload(cid, jpeg)
                    ChatLookStore.keepGroupPhoto(app, link, w.id)    // this phone already has the picture
                    stored = "u:$link"
                }
                FirebaseFunctions.getInstance(AuthRepository.FUNCTIONS_REGION).getHttpsCallable("setGroupWallpaper")
                    .withTimeout(30, TimeUnit.SECONDS).call(mapOf("cid" to cid, "wallpaper" to stored)).await()
                ChatLookStore.syncGroupWallpaper(app, cid, stored)
                runCatching { GroupManage.systemLine(cid, noticeText(GroupManage.myName(cid), stored)) }
                ChatLookRules.photoLink(previous)?.takeIf { previous != stored }?.let { old ->
                    runCatching { FirebaseStorage.getInstance().getReferenceFromUrl(old).delete().await() }
                }
            } catch (e: Exception) {
                onError("Could not set the wallpaper: " + GroupManage.errorText(e, e.message ?: "Try again."))
            }
            ChatLookStore.setGroupPending(cid, null)
        }
    }

    /** iOS sharedUploadJPEG: longest side 2000, JPEG 0.8. */
    private fun uploadJpeg(b: Bitmap): ByteArray {
        val long = maxOf(b.width, b.height)
        val s = if (long > 2000) 2000f / long else 1f
        val img = if (s < 1f) Bitmap.createScaledBitmap(b, (b.width * s).toInt().coerceAtLeast(1), (b.height * s).toInt().coerceAtLeast(1), true) else b
        return ByteArrayOutputStream().also { img.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
    }

    private suspend fun upload(cid: String, jpeg: ByteArray): String {
        val ref = FirebaseStorage.getInstance().reference.child("profiles/group_$cid-wp${System.currentTimeMillis()}.jpg")
        ref.putBytes(jpeg, StorageMetadata.Builder().setContentType("image/jpeg").build()).await()
        // Without ":443": the rules and the server check the plain host (iOS 2026-10-27).
        return ref.downloadUrl.await().toString().replace("firebasestorage.googleapis.com:443/", "firebasestorage.googleapis.com/")
    }
}
