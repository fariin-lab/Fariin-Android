package com.fariin.app.media

import com.google.firebase.storage.FirebaseStorage
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.tasks.await

/**
 * The receive side's downloads, by URL (iOS MediaDownloads): bytes so far and the whole length
 * while one runs, so the bubble can fill its ring by bytes and say "1.2 / 15 MB"; the length of a
 * file that waits for a tap, asked once; and Cancel, which stops the transfer itself.
 *
 * A download runs in this object's own scope, not the bubble's: scrolled away and back, the bubble
 * finds the same transfer still going instead of starting again.
 */
object MediaDownloads {
    data class Progress(val received: Long, val total: Long?) {
        /** 0..1 once the length is known, else null (the ring spins). */
        val fraction: Float? get() = total?.takeIf { it > 0 }?.let { (received.toFloat() / it).coerceIn(0f, 1f) }
    }

    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Videos come down two at a time, so a scroll through a chat full of clips cannot fill the memory. */
    internal val videoLane = Semaphore(2)
    internal val photoLane = Semaphore(6)

    private val _live = MutableStateFlow<Map<String, Progress>>(emptyMap())
    val live: StateFlow<Map<String, Progress>> = _live.asStateFlow()

    private val sizes = ConcurrentHashMap<String, Long>()
    private val tasks = ConcurrentHashMap<String, () -> Unit>()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()

    /** A transfer began; [stop] cancels it (and at once, when Cancel came while it waited its turn). */
    internal fun started(url: String, stop: () -> Unit) {
        tasks[url] = stop
        if (url in cancelled) stop()
    }

    /** Throttled to whole percents, so a fast link does not repaint the ring for every packet. */
    internal fun progress(url: String, received: Long, total: Long?) {
        if (total != null && total > 0) sizes[url] = total
        val next = Progress(received, total?.takeIf { it > 0 })
        _live.update { m ->
            val old = m[url]
            val step = next.fraction?.let { f -> old?.fraction?.let { o -> (f * 100).toInt() == (o * 100).toInt() } } ?: false
            if (old != null && step && old.total == next.total) m else m + (url to next)
        }
    }

    internal fun ended(url: String) {
        tasks.remove(url)
        _live.update { it - url }
    }

    /** The X on a ring: the transfer stops and the bubble goes back to its Download arrow. */
    fun cancel(url: String) {
        cancelled.add(url)
        tasks[url]?.invoke()
    }

    /** True once for a download that ended because of [cancel] (not a failure). */
    fun takeCancelled(url: String): Boolean = cancelled.remove(url)

    /** A fresh start clears an old Cancel that never reached a transfer. */
    internal fun clearCancel(url: String) { cancelled.remove(url) }

    fun knownSize(url: String): Long? = sizes[url]

    /** The file's length for a Download button, asked from Storage once (sealed size, close to the file's). */
    suspend fun fetchSize(url: String): Long? {
        sizes[url]?.let { return it }
        if (ChatMedia.isLocal(url)) return null
        return runCatching { FirebaseStorage.getInstance().getReferenceFromUrl(url).metadata.await().sizeBytes }
            .getOrNull()?.takeIf { it > 0 }?.also { sizes[url] = it }
    }

    /** Pure rule, tested (iOS MediaDownloadBadge.mb): "640 KB", "1.2 MB", "15 MB". */
    fun sizeLabel(bytes: Long): String {
        val m = bytes / 1_048_576.0
        if (m < 0.1) return "%.0f KB".format(java.util.Locale.US, maxOf(1.0, bytes / 1024.0))
        return if (m < 10) "%.1f MB".format(java.util.Locale.US, m) else "%.0f MB".format(java.util.Locale.US, m)
    }

    /** The size pill's words: "15 MB" while it waits, "1.2 / 15 MB" while it comes down. */
    fun pillLabel(received: Long?, total: Long?): String? = when {
        total == null || total <= 0 -> null
        received == null -> sizeLabel(total)
        else -> sizeLabel(received) + " / " + sizeLabel(total)
    }
}
