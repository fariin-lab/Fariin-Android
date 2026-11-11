package com.fariin.app.media

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheWriter
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * The first piece (~1.5 MB) of a streamable clip that comes on screen is fetched into the stream
 * cache before the tap, so Play starts at once (the reference app preloads the same way). Wi-Fi
 * always; mobile data only when Videos may auto-download there. Two at a time; a clip that leaves
 * the screen stops (what it already has stays in the cache for the player).
 */
@OptIn(UnstableApi::class)
object VideoPreload {
    const val BYTES = 1_536L * 1024
    private const val AT_ONCE = 2

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lane = Semaphore(AT_ONCE)
    private val jobs = ConcurrentHashMap<String, Job>()

    /** May the phone spend data on a preload now. */
    fun allowedNow(c: Context): Boolean =
        AutoDownload.onWifi(c) || AutoDownload.allowedNow(c, AutoDownload.Kind.VIDEOS)

    /** Starts (or keeps) the preload of [url]; the caller has checked [VideoStream.streamable]. */
    fun start(c: Context, url: String) {
        if (jobs.containsKey(url)) return
        val app = c.applicationContext
        if (!allowedNow(app)) return
        if (com.fariin.app.system.LowStorage.tooFullToDownload(app)) return
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            lane.withPermit {
                val t0 = android.os.SystemClock.elapsedRealtime()
                var got = 0L
                val writer = CacheWriter(
                    VideoStream.dataSource(app).createDataSource(),
                    DataSpec.Builder().setUri(url).setKey(VideoStream.key(url)).setPosition(0).setLength(BYTES).build(),
                    null,
                ) { _, cached, _ -> got = cached }
                try {
                    runInterruptible { writer.cache() }
                    VideoStream.log("preload done bytes=$got ms=${android.os.SystemClock.elapsedRealtime() - t0}")
                } catch (e: Exception) {
                    writer.cancel()
                    VideoStream.log("preload stopped bytes=$got reason=${e.javaClass.simpleName}")
                }
            }
        }
        if (jobs.putIfAbsent(url, job) != null) return
        job.invokeOnCompletion { jobs.remove(url, job) }
        job.start()
    }

    /** The clip left the screen: its preload stops (a waiting one never starts). */
    fun cancel(url: String) {
        jobs.remove(url)?.cancel()
    }
}
