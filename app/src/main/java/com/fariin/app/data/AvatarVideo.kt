package com.fariin.app.data

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateMapOf
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Crop
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * FARIIN PLUS `animatedAvatar` (iOS AvatarVideo.swift): a short video as the profile picture.
 *
 * Edit Profile > Edit Photo > Choose Video makes a square clip, at most 10 s, no sound, 480 x 480
 * H.264 mp4 ([AvatarVideoMaker]), and its first frame. The FRAME becomes the ordinary profile photo
 * the normal way, so every list, every old app and everyone who never sees the video sees it. The clip
 * goes to transfer/<uid>/avatar-<ms>/video.mp4 and `plusAvatarVideo({path})` moves it to
 * profiles/<uid>-video.mp4 and writes users/{uid}.avatarVideo = {v, on}; only the server writes either.
 *
 * It plays in the big pictures (the profile header and the 1:1 chat header) and, since 2026-11-20, in
 * the chat list rows (ListAvatarClips), muted and looping over the still, while `on` is true, so the app needs no Plus answer of
 * its own about anybody; Plus ending turns `on` off on the server.
 */
data class AvatarVideo(val v: Long, val on: Boolean) {
    companion object {
        fun parse(raw: Any?): AvatarVideo? {
            val d = raw as? Map<*, *> ?: return null
            val v = (d["v"] as? Number)?.toLong() ?: return null
            if (v <= 0) return null
            return AvatarVideo(v, d["on"] == true)
        }
        fun storagePath(uid: String) = "profiles/$uid-video.mp4"
        /** The server's own ceiling (functions-admin lib/plus.js AV_MAX_BYTES). */
        const val MAX_BYTES = 3 * 1024 * 1024
        const val MAX_MS = 10_000L
    }
}

/**
 * uid -> their avatarVideo, filled from every users document the app reads ([Person.from], the 1:1
 * chat's listener, my own profile listener). A big picture asks [playable] while it draws; a person
 * never read yet is fetched once (at most every 10 minutes).
 */
object AvatarVideoIndex {
    val videos = mutableStateMapOf<String, AvatarVideo>()
    private val known = HashSet<String>()
    private val askedAt = HashMap<String, Long>()
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** From any thread: the raw `avatarVideo` value of a whole users document. */
    fun recordRaw(uid: String, raw: Any?) = record(uid, AvatarVideo.parse(raw))

    fun record(uid: String, video: AvatarVideo?) {
        if (uid.isEmpty()) return
        val apply = {
            known.add(uid)
            if (video == null) { if (videos.containsKey(uid)) videos.remove(uid) }
            else if (videos[uid] != video) videos[uid] = video
        }
        if (Looper.myLooper() == Looper.getMainLooper()) apply() else main.post(apply)
    }

    /** The video that should play for [uid] now, or null (none, or turned off). */
    fun playable(uid: String?): AvatarVideo? {
        if (uid.isNullOrEmpty() || '/' in uid) return null
        if (uid !in known) want(uid)
        return videos[uid]?.takeIf { it.on }
    }

    /** Mine, on or off: Save needs to know there is one to take down. */
    fun mine(): AvatarVideo? = com.fariin.app.Fb.auth.currentUser?.uid?.let { videos[it] }

    private fun want(uid: String) {
        val now = System.currentTimeMillis()
        if (askedAt[uid]?.let { now - it < 600_000 } == true) return
        askedAt[uid] = now
        scope.launch {
            val d = try { com.fariin.app.Fb.db.collection("users").document(uid).get().await().data } catch (_: Exception) { null }
                ?: return@launch
            recordRaw(uid, d["avatarVideo"])
        }
    }

    /** Sign-out. */
    fun clear(c: Context) {
        main.post { videos.clear(); known.clear(); askedAt.clear() }
        AvatarVideoCache.wipe(c)
    }
}

/** The clips, read with the signed-in Storage SDK (no public link) into cache/avatar-videos/<uid>-<v>.mp4. */
object AvatarVideoCache {
    private val lock = Mutex()
    private val inFlight = HashMap<String, Deferred<File?>>()
    private val failedAt = HashMap<String, Long>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun folder(c: Context) = File(c.cacheDir, "avatar-videos")
    fun file(c: Context, uid: String, v: Long) = File(folder(c), "$uid-$v.mp4")

    private fun dropOlder(c: Context, uid: String, keep: String) {
        folder(c).listFiles()?.forEach { if (it.name.startsWith("$uid-") && it.name != keep) it.delete() }
    }

    /** The local file of this clip, fetched when needed; null when it cannot be read (asked again after 30 s). */
    suspend fun get(c: Context, uid: String, v: Long): File? {
        val f = file(c, uid, v)
        if (f.exists() && f.length() > 0) return f
        val job = lock.withLock {
            if (failedAt[f.name]?.let { System.currentTimeMillis() - it < 30_000 } == true) return null
            inFlight[f.name] ?: scope.async {
                val bytes = try {
                    FirebaseStorage.getInstance().reference.child(AvatarVideo.storagePath(uid))
                        .getBytes((AvatarVideo.MAX_BYTES + 512 * 1024).toLong()).await()
                } catch (_: Exception) { null }
                if (bytes == null || bytes.isEmpty()) return@async null
                folder(c).mkdirs()
                dropOlder(c, uid, f.name)
                val tmp = File(folder(c), f.name + ".part")
                runCatching { tmp.writeBytes(bytes); tmp.renameTo(f) }.getOrNull()?.takeIf { it }?.let { f }
            }.also { inFlight[f.name] = it }
        }
        val out = job.await()
        lock.withLock {
            inFlight.remove(f.name)
            if (out == null) failedAt[f.name] = System.currentTimeMillis() else failedAt.remove(f.name)
        }
        return out
    }

    /** My own new clip, already on this phone: copied in so it plays without a download. */
    fun seed(c: Context, uid: String, v: Long, local: File) {
        val f = file(c, uid, v)
        folder(c).mkdirs()
        dropOlder(c, uid, f.name)
        runCatching { local.copyTo(f, overwrite = true) }
    }

    fun wipe(c: Context) { runCatching { folder(c).deleteRecursively() } }
}

/** Square, centre-cropped, at most 10 s from the start, no sound, 480 x 480 H.264 mp4, plus its first frame. */
object AvatarVideoMaker {
    class Clip(val file: File, val still: ByteArray)
    private const val SIDE = 480

    /** Duration in ms (0 when it cannot be read). */
    suspend fun durationMs(c: Context, uri: Uri): Long = withContext(Dispatchers.IO) {
        val r = android.media.MediaMetadataRetriever()
        try {
            r.setDataSource(c, uri)
            r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) { 0L } finally { runCatching { r.release() } }
    }

    private fun upright(c: Context, uri: Uri): Pair<Int, Int> {
        val r = android.media.MediaMetadataRetriever()
        return try {
            r.setDataSource(c, uri)
            val w = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) h to w else w to h
        } catch (_: Exception) { 0 to 0 } finally { runCatching { r.release() } }
    }

    /** The clip from [startMs], or null when this video cannot be used. */
    suspend fun make(c: Context, uri: Uri, startMs: Long): Clip? {
        val app = c.applicationContext
        val total = durationMs(app, uri)
        val (w, h) = withContext(Dispatchers.IO) { upright(app, uri) }
        if (total <= 0 || w <= 0 || h <= 0) return null
        val begin = startMs.coerceIn(0L, total)
        val end = minOf(total, begin + AvatarVideo.MAX_MS)
        if (end - begin < 500) return null
        val out = File(app.cacheDir, "avatar-${System.currentTimeMillis()}.mp4")
        val ok = withContext(Dispatchers.Main) { export(app, uri, out, begin, end, w, h) }
        if (!ok || out.length() <= 0 || out.length() > AvatarVideo.MAX_BYTES) { out.delete(); return null }
        val still = withContext(Dispatchers.IO) {
            val r = android.media.MediaMetadataRetriever()
            try {
                r.setDataSource(out.absolutePath)
                val bmp = r.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return@withContext null
                java.io.ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it); bmp.recycle() }.toByteArray()
            } catch (_: Exception) { null } finally { runCatching { r.release() } }
        }
        if (still == null) { out.delete(); return null }
        return Clip(out, still)
    }

    private suspend fun export(c: Context, uri: Uri, out: File, begin: Long, end: Long, w: Int, h: Int): Boolean =
        suspendCancellableCoroutine { cont ->
            val encoders = DefaultEncoderFactory.Builder(c)
                // 10 s at 1.5 Mbit/s is about 1.9 MB, well under the 3 MB the server takes.
                .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(1_500_000).build())
                .build()
            val t = Transformer.Builder(c)
                .setEncoderFactory(encoders)
                .setMuxerFactory(com.fariin.app.media.NoLocationMuxerFactory())
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (cont.isActive) cont.resume(out.length() > 0)
                    }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        if (cont.isActive) cont.resume(false)
                    }
                })
                .build()
            val item = MediaItem.Builder().setUri(uri).setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(begin).setEndPositionMs(end).build(),
            ).build()
            // The centred square, in the frame's own -1..1 space.
            val crop = if (w >= h) { val k = h.toFloat() / w; Crop(-k, k, -1f, 1f) } else { val k = w.toFloat() / h; Crop(-1f, 1f, -k, k) }
            val square = minOf(w, h)
            val video = ArrayList<androidx.media3.common.Effect>()
            video += crop
            for (s in StoryCameraRules.halvingSteps(square, SIDE)) video += Presentation.createForShortSide(s)
            video += Presentation.createForWidthAndHeight(SIDE, SIDE, Presentation.LAYOUT_SCALE_TO_FIT)
            val edited = EditedMediaItem.Builder(item).setRemoveAudio(true).setEffects(Effects(emptyList(), video)).build()
            try { t.start(edited, out.absolutePath) } catch (_: Exception) { if (cont.isActive) cont.resume(false) }
            cont.invokeOnCancellation { runCatching { t.cancel() } }
        }
}

/** Setting and taking down my own video (callable `plusAvatarVideo`, me-central1). */
object AvatarVideoService {
    private val fns get() = FirebaseFunctions.getInstance(PlusStore.REGION)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Uploads [clip] to my transfer folder and asks the server to take it; returns `v`. */
    suspend fun publish(c: Context, clip: File): Long {
        val me = com.fariin.app.Fb.auth.currentUser?.uid ?: error("Signed out")
        val ms = System.currentTimeMillis()
        val path = "transfer/$me/avatar-$ms/video.mp4"
        FirebaseStorage.getInstance().reference.child(path)
            .putBytes(withContext(Dispatchers.IO) { clip.readBytes() }, StorageMetadata.Builder().setContentType("video/mp4").build()).await()
        val d = fns.getHttpsCallable("plusAvatarVideo").call(mapOf("path" to path)).await().getData() as? Map<*, *>
        val v = (d?.get("v") as? Number)?.toLong() ?: ms
        AvatarVideoCache.seed(c.applicationContext, me, v, clip)
        AvatarVideoIndex.record(me, AvatarVideo(v, true))
        return v
    }

    suspend fun remove() {
        val me = com.fariin.app.Fb.auth.currentUser?.uid ?: return
        fns.getHttpsCallable("plusAvatarVideo").call(mapOf("remove" to true)).await()
        AvatarVideoIndex.record(me, null)
    }

    /** After Save: the clip goes up, failures shown as one short notice; the temp file is removed. */
    fun publishInBackground(c: Context, clip: File) {
        val app = c.applicationContext
        scope.launch {
            try { publish(app, clip) }
            catch (e: Exception) {
                val msg = (e as? com.google.firebase.functions.FirebaseFunctionsException)?.message?.takeIf { it.isNotBlank() }
                com.fariin.app.ui.components.Snack.show(msg ?: "Couldn't save your video. Try again.")
            } finally { clip.delete() }
        }
    }

    fun removeInBackground() { scope.launch { runCatching { remove() } } }
}
