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
    /** The folder; made only where something is written (a bubble's look-up asked the disk to make it). */
    private val dir: File = File(context.cacheDir, "chat-media")
    private fun dirMade(): File = dir.also { it.mkdirs() }

    /** Where my own outgoing files wait (cleared with the cache, as iOS's temp folder is). */
    fun localDir(): File = outgoingDir(context)

    fun cached(url: String): File? = if (isLocal(url)) null else showable(url)
        // Used = young again, so the size cap (trim) drops the files nobody looked at for longest.
        ?.also { it.setLastModified(System.currentTimeMillis()) }

    /** [url]'s own file, else its alias copy (MediaOrigin: the same file from another message). */
    private fun showable(url: String): File? {
        val h = sha(url)
        return File(dir, h).takeIf { it.exists() && it.length() > 0 }
            ?: File(dir, "a-$h").takeIf { it.exists() && it.length() > 0 }
    }

    /** The byte count of [url]'s OWN cached file (never an alias), or null: what a forward claims. */
    fun ownBytes(url: String): Long? = if (isLocal(url)) null else key(url).length().takeIf { it > 0 }
        // A plain clip watched through the stream cache is its own copy too (never an alias).
        ?: VideoStream.fullLength(context, url)

    /**
     * [cached] without marking it used: the disk write moved off the main thread (a chat's first
     * frame paid it once per picture). The caller marks it with [touch] in the background.
     */
    fun peek(url: String): File? = if (isLocal(url)) null else showable(url)

    /** Marks [f] used (young again for the size cap). Disk work: off the main thread. */
    fun touch(f: File) { runCatching { f.setLastModified(System.currentTimeMillis()) } }

    /** Whether the file is in the cache, without touching it (the mark's media hold asks often). */
    fun has(url: String): Boolean = !isLocal(url) && showable(url) != null

    /** Seed the cache with what I just sent, under its URL (iOS stores the plain image the same way). */
    fun store(url: String, plain: ByteArray) {
        runCatching { atomicWrite(key(url), plain); MediaOrigin.cached(url, plain.size.toLong()) }
    }

    /** [store] from a plain file on disk (a sent clip), copied, so its bytes never sit in memory. */
    fun storeFile(url: String, plain: File) {
        runCatching {
            val f = key(url); f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp" + System.nanoTime())
            plain.copyTo(tmp, overwrite = true)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            MediaOrigin.cached(url, f.length())
        }
    }

    /** [own]: the message's own file only, never an alias copy (forward input, MediaOrigin). */
    suspend fun bytes(url: String, enc: EncMeta?, own: Boolean = false): ByteArray? = file(url, enc, own = own)?.let { f ->
        withContext(Dispatchers.IO) { runCatching { f.readBytes() }.getOrNull() }
    }

    /**
     * A readable file with the plain bytes, or null when it cannot be had or opened. [video] puts
     * the download in the narrow lane (two at a time). The transfer runs in [MediaDownloads]'s own
     * scope: a caller that goes away (a bubble scrolled off) leaves it running for the next look.
     */
    suspend fun file(url: String, enc: EncMeta?, video: Boolean = false, own: Boolean = false): File? = withContext(Dispatchers.IO) {
        when {
            url.startsWith(ASSET) -> assetFile(url)
            url.startsWith("/") -> File(url).takeIf { it.exists() }
            else -> {
                val mine = key(url)
                if (mine.exists() && mine.length() > 0) {
                    mine.setLastModified(System.currentTimeMillis())
                    // Seen again: it names its origin for other messages (measured from the file).
                    MediaOrigin.cached(url, mine.length())
                    return@withContext mine
                }
                if (!own) cached(url)?.let { return@withContext it }
                // A nearly full phone downloads nothing (iOS LowStorage): held, and the sheet says why.
                if (com.fariin.app.system.LowStorage.tooFullToDownload(context)) {
                    withContext(Dispatchers.Main) { com.fariin.app.system.LowStorage.warnForDownload(context) }
                    return@withContext null
                }
                val slot = if (own) "own:$url" else url
                val job = inflight.computeIfAbsent(slot) {
                    MediaDownloads.clearCancel(url)
                    MediaDownloads.scope.async(start = CoroutineStart.LAZY) {
                        val lane = if (video) MediaDownloads.videoLane else MediaDownloads.photoLane
                        lane.withPermit {
                            (if (own) key(url).takeIf { it.exists() && it.length() > 0 } else cached(url))
                                ?: (if (own) null else fromOrigin(url)) ?: download(url, enc)
                        }
                    }.also { d -> d.invokeOnCompletion { inflight.remove(slot, d) } }
                }
                job.start()
                job.await()
            }
        }
    }

    /**
     * A forward of a file this phone already holds under another message (MediaOrigin): copied into
     * [url]'s alias file, nothing downloaded. Only when the other file still has the exact byte count
     * the forward claims.
     */
    private fun fromOrigin(url: String): File? {
        val (other, size) = MediaOrigin.source(url) ?: run {
            // Not in the index yet: measure the own copies of the same file this session has read.
            for (o in MediaOrigin.ownersOf(url)) ownBytes(o)?.let { MediaOrigin.cached(o, it) }
            MediaOrigin.source(url)
        } ?: return null
        val src = key(other)
        // A plain clip watched through the stream cache: copied out to its own file first.
        dirMade()
        if (!src.exists() || src.length() == 0L) VideoStream.copyCached(context, other, src)
        if (!src.exists() || src.length() != size) return null
        val out = alias(url)
        return runCatching {
            val tmp = File(dirMade(), out.name + ".tmp" + System.nanoTime())
            src.copyTo(tmp, overwrite = true)
            if (!tmp.renameTo(out)) { out.delete(); tmp.renameTo(out) }
            out.takeIf { it.exists() && it.length() == size }
        }.getOrNull()?.also { android.util.Log.i("ChatMedia", "origin hit: ${size} bytes shown from this phone, not downloaded") }
    }

    /** The user removed [url]'s media: its own file and alias go, and its slot is not filled again. */
    fun forget(url: String) {
        if (isLocal(url)) return
        runCatching { key(url).delete() }
        runCatching { alias(url).delete() }
        // A voice note's playable Ogg copy (VoicePlayer.playableFile), when one was made.
        runCatching { File(dir, "voice-play-" + key(url).name + ".ogg").delete() }
        runCatching { File(dir, "voice-play-" + alias(url).name + ".ogg").delete() }
        MediaOrigin.forget(url)
    }

    private suspend fun download(url: String, enc: EncMeta?): File? {
        dirMade()
        // A plain clip already watched to the end through the stream cache: copied out, not fetched again.
        if ((enc == null || enc.isPlain) && Wire.isGroupCid(cid) && url.startsWith("https://")) {
            val f = key(url)
            if (VideoStream.copyCached(context, url, f)) { MediaOrigin.cached(url, f.length()); return f }
        }
        val ref = if (R2Media.isR2(url)) null else try { FirebaseStorage.getInstance().getReferenceFromUrl(url) } catch (_: Exception) { null }
        val cipher = try {
            when {
                // Cloudflare R2 (iOS 9bbac4e65): signed by mediaSign, the same ring and size cap.
                R2Media.isR2(url) -> fetchR2(url)
                ref != null -> fetchWithProgress(ref, url)
                else -> fetchByUrl(url)
            }
        } catch (e: OutOfMemoryError) {
            // A clip too big for this phone's memory: stays on its retry button, never a crash.
            android.util.Log.w("ChatMedia", "download too big for memory")
            return null
        } catch (e: Exception) {
            // Why a picture stays on its retry button (the kind of failure only, never the address).
            android.util.Log.w("ChatMedia", "download failed: ${e.javaClass.simpleName} ${(e as? com.google.firebase.storage.StorageException)?.errorCode ?: ""}")
            return null
        } ?: return null
        val plain = (try { open(cipher, enc) } catch (_: OutOfMemoryError) { null }) ?: run {
            android.util.Log.w("ChatMedia", "downloaded ${cipher.size} bytes but could not open them (enc=${enc != null})")
            return null
        }
        val f = key(url)
        atomicWrite(f, plain)
        MediaOrigin.cached(url, plain.size.toLong())
        return f
    }

    /** The sealed bytes through a file task, so the bubble's ring fills by bytes and its X stops it. */
    private suspend fun fetchWithProgress(ref: com.google.firebase.storage.StorageReference, url: String): ByteArray? {
        val part = File(dirMade(), "dl-" + sha(url) + ".part")
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

    /** Cloudflare R2: streamed into a .part file (never all in memory while it comes), its X stops it. */
    private suspend fun fetchR2(url: String): ByteArray? {
        val part = File(dirMade(), "dl-" + sha(url) + ".part")
        try {
            if (!R2Media.getToFile(url, part, MediaRules.MAX_DOWNLOAD) { done, total -> MediaDownloads.progress(url, done, total) }) return null
            return part.readBytes()
        } finally { part.delete() }
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
        val f = File(dirMade(), "asset-" + path.replace('/', '_'))
        if (f.exists()) return f
        return runCatching {
            context.assets.open(path).use { atomicWrite(f, it.readBytes()) }
            f
        }.getOrNull()
    }

    private fun key(url: String): File = File(dir, sha(url))
    /** Where a file from another message is shown for [url] (never forward input). */
    private fun alias(url: String): File = File(dir, "a-" + sha(url))

    private fun atomicWrite(f: File, b: ByteArray) {
        f.parentFile?.mkdirs()
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
            // Played voice notes' Ogg copies from before they moved into chat-media (H-133).
            context.cacheDir.listFiles()?.forEach { if (it.name.startsWith("voice-play-")) it.delete() }
            File(context.cacheDir, "outgoing").deleteRecursively()
            File(context.noBackupFilesDir, "outgoing").deleteRecursively()
            VideoStream.wipe(context)
        }

        /** One transfer per URL in this process, whichever chat or bubble asked first. */
        private val inflight = ConcurrentHashMap<String, Deferred<File?>>()

        /** True while [url] is coming down here (a bubble back on screen picks the same one up). */
        fun isFetching(url: String): Boolean = inflight.containsKey(url)

        /**
         * Sends still waiting for the server (K-45): outside the cache, so the system's Clear cache
         * and low storage never take them; not backed up. Older sends may still sit in cacheDir/outgoing.
         */
        fun outgoingDir(context: Context): File = File(context.noBackupFilesDir, "outgoing").also { it.mkdirs() }

        fun isLocal(url: String) = url.startsWith(ASSET) || url.startsWith("/")
        /**
         * The cache name of [s]: SHA-256 in hex. Remembered per string, and hex by table: a chat's
         * first frame asks it for every picture, and a String.format per byte made it ~2 ms a call on
         * the Oppo (chat open profile 2026-11-21).
         */
        fun sha(s: String): String {
            shaMemo.get(s)?.let { return it }
            val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
            val out = CharArray(d.size * 2)
            for (i in d.indices) {
                val v = d[i].toInt() and 0xff
                out[i * 2] = HEX[v ushr 4]; out[i * 2 + 1] = HEX[v and 0x0f]
            }
            return String(out).also { shaMemo.put(s, it) }
        }
        private val HEX = "0123456789abcdef".toCharArray()
        private val shaMemo = StringMemo(2048)
    }
}
