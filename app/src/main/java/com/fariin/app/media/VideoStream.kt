package com.fariin.app.media

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.fariin.app.crypto.EncMeta
import com.fariin.app.crypto.Wire
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Chat videos that play while they come down. Only a file that is NOT sealed can do this: a plain
 * group's or a channel's (`enc` missing or v:0). A 1:1 video is sealed as one piece and is still
 * downloaded whole, opened, then played ([ChatMedia]).
 *
 * The player reads the plain https address through one shared disk cache ([SimpleCache], 512 MB,
 * least recently used go first); the preloader ([VideoPreload]) writes the first piece of a clip
 * into the same cache before the tap, and a fully watched clip is copied out of it for Save and
 * Forward instead of being fetched again.
 *
 * Remote switch `config/features.videoStream` (on unless the panel turns it off). A clip that did
 * not show its first frame in time falls back to the whole download and is not streamed again in
 * this run.
 */
@OptIn(UnstableApi::class)
object VideoStream {
    const val MAX_CACHE = 512L * 1024 * 1024
    /** No first frame by then: the whole-file path takes over. */
    const val WATCHDOG_MS = 8_000L

    @Volatile private var cache: SimpleCache? = null
    @Volatile private var logOn = false
    private val failed = ConcurrentHashMap.newKeySet<String>()

    val enabled: Boolean get() = com.fariin.app.data.AppFeatures.videoStream

    /** May this clip stream: a plain file (group or channel) at an https address, switch on. */
    fun streamable(cid: String, url: String?, enc: EncMeta?): Boolean =
        enabled && url != null && url.startsWith("https://") && Wire.isGroupCid(cid) &&
            (enc == null || enc.isPlain) && url !in failed

    /** The stream stalled: this clip goes the whole-file way from now on (this run). */
    fun markFailed(url: String) { failed += url }

    /** One cache key per address, the same for the player, the preloader and the copy-out. */
    fun key(url: String): String = "v-" + ChatMedia.sha(url)

    private val keys = CacheKeyFactory { spec -> spec.key ?: key(spec.uri.toString()) }

    /** Turns the VSTREAM# lines on for debug and test (pixelfast) builds. */
    fun init(c: Context) {
        val app = c.applicationContext
        logOn = (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 ||
            runCatching { app.resources.getBoolean(com.fariin.app.R.bool.fariin_debug_appcheck) }.getOrDefault(false)
    }

    fun cache(c: Context): SimpleCache = cache ?: synchronized(this) {
        cache ?: run {
            val app = c.applicationContext
            init(app)
            SimpleCache(File(app.cacheDir, "video-stream"), LeastRecentlyUsedCacheEvictor(MAX_CACHE),
                StandaloneDatabaseProvider(app)).also { cache = it }
        }
    }

    private val http: DefaultHttpDataSource.Factory by lazy {
        DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(15_000)
            .setAllowCrossProtocolRedirects(false)
            .setUserAgent("Fariin")
    }

    fun dataSource(c: Context): CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(cache(c))
        .setCacheKeyFactory(keys)
        .setUpstreamDataSourceFactory(http)
        // A broken cache never blocks playback: it reads from the network instead.
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    fun mediaSourceFactory(c: Context): ProgressiveMediaSource.Factory = ProgressiveMediaSource.Factory(dataSource(c))

    /** Every byte of [url] is in the cache (its length known and all of it there). */
    fun fullyCached(c: Context, url: String): Boolean = runCatching {
        val sc = cache(c)
        val k = key(url)
        val len = ContentMetadata.getContentLength(sc.getContentMetadata(k))
        len > 0 && sc.isCached(k, 0, len)
    }.getOrDefault(false)

    /** How many bytes from the start of [url] are already cached (what a preload left). */
    fun cachedPrefix(c: Context, url: String): Long =
        runCatching { cache(c).getCachedLength(key(url), 0, VideoPreload.BYTES * 4) }.getOrDefault(0L).coerceAtLeast(0L)

    /** The cached clip written to [out] (for Save / Forward / Share); false when it is not all here. */
    fun copyCached(c: Context, url: String, out: File): Boolean {
        if (!fullyCached(c, url)) return false
        val tmp = File(out.parentFile, out.name + ".vs" + System.nanoTime())
        return try {
            // Cache only: a missing piece fails the read instead of going to the network.
            val src = CacheDataSource.Factory().setCache(cache(c)).setCacheKeyFactory(keys)
                .setUpstreamDataSourceFactory(null).setCacheWriteDataSinkFactory(null).createDataSource()
            src.open(DataSpec.Builder().setUri(url).setKey(key(url)).build())
            try {
                tmp.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = src.read(buf, 0, buf.size)
                        if (n == androidx.media3.common.C.RESULT_END_OF_INPUT) break
                        o.write(buf, 0, n)
                    }
                }
            } finally { src.close() }
            if (tmp.length() == 0L) { tmp.delete(); return false }
            if (!tmp.renameTo(out)) { out.delete(); if (!tmp.renameTo(out)) { tmp.delete(); return false } }
            log("copied from stream cache bytes=${out.length()}")
            true
        } catch (e: Exception) {
            tmp.delete()
            false
        }
    }

    /** Sign-out: another account's clips are never left on the phone. */
    fun wipe(c: Context) {
        synchronized(this) {
            runCatching { cache?.release() }
            cache = null
        }
        val dir = File(c.cacheDir, "video-stream")
        runCatching { SimpleCache.delete(dir, StandaloneDatabaseProvider(c.applicationContext)) }
        dir.deleteRecursively()
        failed.clear()
    }

    /** VSTREAM# lines, only in debug and test (pixelfast) builds. Never the address. */
    fun log(msg: String) {
        if (logOn) android.util.Log.i("VSTREAM#", msg)
    }
}
