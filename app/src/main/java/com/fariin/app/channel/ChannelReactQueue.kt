package com.fariin.app.channel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * One reaction request per post in flight, always carrying the newest wish (iOS ChannelFollows.react).
 * Taps while one runs only change the wish; when it answers, the newest wish goes if it differs.
 */
object ChannelReactQueue {
    private val wish = HashMap<String, String?>()
    private val running = HashSet<String>()

    @Synchronized
    fun want(scope: CoroutineScope, cid: String, mid: String, emoji: String?, onError: (Exception) -> Unit) {
        val key = "$cid/$mid"
        wish[key] = emoji
        if (!running.add(key)) return
        scope.launch {
            var sent: String? = emoji
            try {
                while (true) {
                    val counts = ChannelService.react(cid, mid, sent)
                    val next = synchronized(this@ChannelReactQueue) { wish[key] }
                    if (next == sent) { ChannelFollows.settle(cid, mid, counts, sent); break }
                    sent = next
                }
            } catch (e: Exception) { onError(e) }
            finally { synchronized(this@ChannelReactQueue) { running.remove(key); wish.remove(key) } }
        }
    }
}
