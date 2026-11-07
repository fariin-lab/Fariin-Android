package com.fariin.app.media

import android.content.Context
import com.fariin.app.crypto.EncMeta
import com.fariin.app.crypto.FariinCrypto
import com.fariin.app.crypto.Wire
import com.google.firebase.storage.FirebaseStorage
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * The files of one chat: download, open (EncMeta), and keep the plain copy in this app's private
 * cache (iOS DiskImageCache / AudioCache keep the plain copy too), so a bubble scrolled back into
 * view does not download again.
 *
 * - `asset://demo/x.jpg` is a file bundled with debug builds, for the demo chat. No network.
 * - An absolute path is a file this phone made (an optimistic bubble of mine).
 * - Anything else is a Storage download URL. A missing `enc` is only accepted in a group (a plain
 *   group's file); in a 1:1 it is refused, as iOS refuses a plain 1:1 file.
 */
class ChatMedia(
    private val context: Context,
    private val cid: String,
    private val crypto: FariinCrypto?,
) {
    private val dir: File get() = File(context.cacheDir, "chat-media").also { it.mkdirs() }

    /** Where my own outgoing files wait (cleared with the cache, as iOS's temp folder is). */
    fun localDir(): File = File(context.cacheDir, "outgoing").also { it.mkdirs() }

    fun cached(url: String): File? = if (isLocal(url)) null else key(url).takeIf { it.exists() && it.length() > 0 }
        // Used = young again, so the size cap (trim) drops the files nobody looked at for longest.
        ?.also { it.setLastModified(System.currentTimeMillis()) }

    /** Seed the cache with what I just sent, under its URL (iOS stores the plain image the same way). */
    fun store(url: String, plain: ByteArray) {
        runCatching { atomicWrite(key(url), plain) }
    }

    suspend fun bytes(url: String, enc: EncMeta?): ByteArray? = file(url, enc)?.let { f ->
        withContext(Dispatchers.IO) { runCatching { f.readBytes() }.getOrNull() }
    }

    /**
     * A readable file with the plain bytes, or null when it cannot be had or opened. [video] puts
     * the download in the narrow lane (two at a time). The transfer runs in [MediaDownloads]'s own
     * scope: a caller that goes away (a bubble scrolled off) leaves it running for the next look.
     */
    suspend fun file(url: String, enc: EncMeta?, video: Boolean = false): File? = withContext(Dispatchers.IO) {
        when {
            url.startsWith(ASSET) -> assetFile(url)
            url.startsWith("/") -> File(url).takeIf { it.exists() }
            else -> {
                cached(url)?.let { return@withContext it }
                // A nearly full phone downloads nothing (iOS LowStorage): held, and the sheet says why.
                if (com.fariin.app.system.LowStorage.tooFullToDownload(context)) {
                    withContext(Dispatchers.Main) { com.fariin.app.system.LowStorage.warnForDownload(context) }
                    return@withContext null
                }
                val job = inflight.computeIfAbsent(url) {
                    MediaDownloads.clearCancel(url)
                    MediaDownloads.scope.async(start = CoroutineStart.LAZY) {
                        val lane = if (video) MediaDownloads.videoLane else MediaDownloads.photoLane
                        lane.withPermit { cached(url) ?: download(url, enc) }
                    }.also { d -> d.invokeOnCompletion { inflight.remove(url, d) } }
                }
                job.start()
                job.await()
            }
        }
    }

    private suspend fun download(url: String, enc: EncMeta?): File? {
        val ref = try { FirebaseStorage.getInstance().getReferenceFromUrl(url) } catch (_: Exception) { null }
        val cipher = try {
            if (ref != null) fetchWithProgress(ref, url) else fetchByUrl(url)
        } catch (e: Exception) {
            // Why a picture stays on its retry button (the kind of failure only, never the address).
            android.util.Log.w("ChatMedia", "download failed: ${e.javaClass.simpleName} ${(e as? com.google.firebase.storage.StorageException)?.errorCode ?: ""}")
            return null
        } ?: return null
        val plain = open(cipher, enc) ?: run {
            android.util.Log.w("ChatMedia", "downloaded ${cipher.size} bytes but could not open them (enc=${enc != null})")
            return null
        }
        val f = key(url)
        atomicWrite(f, plain)
        return f
    }

    /** The sealed bytes through a file task, so the bubble's ring fills by bytes and its X stops it. */
    private suspend fun fetchWithProgress(ref: com.google.firebase.storage.StorageReference, url: String): ByteArray? {
        val part = File(dir, "dl-" + sha(url) + ".part")
        val task = ref.getFile(part)
        task.addOnProgressListener { s ->
            if (s.totalByteCount > MediaRules.MAX_DOWNLOAD) task.cancel()
            else MediaDownloads.progress(url, s.bytesTransferred, s.totalByteCount.takeIf { it > 0 })
        }
        MediaDownloads.started(url) { task.cancel() }
        try {
            try { task.await() } catch (e: Exception) {
                if (task.isCanceled) return null
                throw e
            }
            return part.readBytes()
        } finally {
            MediaDownloads.ended(url)
            part.delete()
        }
    }

    /** A download address the Storage SDK cannot map to a bucket object (the local emulator's
     *  http://10.0.2.2:9199 links): read it as the plain URL it is, its token included. */
    private fun fetchByUrl(url: String): ByteArray? {
        if (!com.fariin.app.FariinApp.usingLocalBackend || !url.startsWith("http://10.0.2.2:")) return null
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 30_000
        return try {
            if (c.responseCode != 200) return null
            val out = java.io.ByteArrayOutputStream()
            c.inputStream.use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MediaRules.MAX_DOWNLOAD) return null
                }
            }
            out.toByteArray()
        } finally { c.disconnect() }
    }

    private suspend fun open(cipher: ByteArray, enc: EncMeta?): ByteArray? {
        if (enc == null) return if (Wire.isGroupCid(cid)) cipher else null
        val c = crypto ?: return null
        return c.decryptBytes(cid, cipher, enc)
    }

    private fun assetFile(url: String): File? {
        val path = url.removePrefix(ASSET)
        val f = File(dir, "asset-" + path.replace('/', '_'))
        if (f.exists()) return f
        return runCatching {
            context.assets.open(path).use { atomicWrite(f, it.readBytes()) }
            f
        }.getOrNull()
    }

    private fun key(url: String): File = File(dir, sha(url))

    private fun atomicWrite(f: File, b: ByteArray) {
        val tmp = File(f.parentFile, f.name + ".tmp" + System.nanoTime())
        tmp.writeBytes(b)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    companion object {
        const val ASSET = "asset://"
        /** iOS DiskImageCache's budget: 250 MB of opened chat files, least recently used go first. */
        const val MAX_BYTES = 250L * 1024 * 1024

        /** Keep the opened-media folder under [max] (run at start, off the main thread). */
        fun trim(context: Context, max: Long = MAX_BYTES) {
            val files = File(context.cacheDir, "chat-media").listFiles()?.filter { it.isFile } ?: return
            var total = files.sumOf { it.length() }
            if (total <= max) return
            for (f in files.sortedBy { it.lastModified() }) {
                if (total <= max) break
                total -= f.length(); f.delete()
            }
        }

        /** Sign-out: these are opened photos and voice notes of this account's chats (iOS
         *  DiskImageCache.removeAll), never left for the next account. */
        fun wipe(context: Context) {
            File(context.cacheDir, "chat-media").deleteRecursively()
            File(context.cacheDir, "outgoing").deleteRecursively()
        }

        /** One transfer per URL in this process, whichever chat or bubble asked first. */
        private val inflight = ConcurrentHashMap<String, Deferred<File?>>()

        /** True while [url] is coming down here (a bubble back on screen picks the same one up). */
        fun isFetching(url: String): Boolean = inflight.containsKey(url)

        fun isLocal(url: String) = url.startsWith(ASSET) || url.startsWith("/")
        fun sha(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
