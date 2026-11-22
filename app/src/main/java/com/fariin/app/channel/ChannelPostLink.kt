package com.fariin.app.channel

import com.fariin.app.data.Message
import kotlinx.coroutines.tasks.await

/**
 * One channel post's own link (iOS ChannelPostLink, owner 2026-11-08 / 11-12): a public channel's
 * `https://fariin.com/<name>/<postSeq>`, a private one's `https://fariin.com/c/<cid>/<postSeq>`.
 * A post with no number yet (the server writes `postSeq` a moment after) uses its id; ids are never
 * only digits, so a digits-only part is always a number. Opening one finds the channel (its id, or
 * its @name looked up) and the post (its id, or its number looked up), then the channel's thread
 * scrolls to it and lights it.
 */
object ChannelPostLink {
    data class Target(val name: String?, val cid: String?, val mid: String)

    private val SEGMENT = Regex("^[A-Za-z0-9_-]{1,64}$")
    private val NAME = Regex("^[A-Za-z0-9_]{1,30}$")
    private val NUMBER = Regex("^[0-9]{1,9}$")
    private val HOST = Regex("^(?:https?://)?(?:www\\.)?fariin\\.com/([^?#]*)", RegexOption.IGNORE_CASE)

    /** iOS KulanApp.sitePages plus the site's own one-letter paths: never read as a channel name. */
    private val SITE_PAGES = setOf("download", "privacy", "terms", "support", "help", "about", "faq", "news", "safety",
        "security", "reset", "admin", "console", "index", "g", "u", "s", "a", "c", "hc", "call", "voice", "video", "link")

    /** A post number rather than an id: digits only. */
    fun isNumber(s: String): Boolean = NUMBER.matches(s)

    /** The link for one post, or null when it cannot have one (no number and an id that looks like one). */
    fun link(username: String, cid: String, postSeq: Int?, mid: String): String? {
        val ref = postSeq?.takeIf { it > 0 }?.toString()
            ?: mid.takeIf { !isNumber(it) && SEGMENT.matches(it) } ?: return null
        return when {
            username.isNotEmpty() -> "https://fariin.com/$username/$ref"
            SEGMENT.matches(cid) -> "https://fariin.com/c/$cid/$ref"
            else -> null
        }
    }

    /** iOS KulanApp.route .post: `/c/<cid>/<ref>` or `/<name>/<ref>`, never one of the site's own paths. */
    fun parse(url: String?): Target? {
        val path = HOST.find(url?.trim() ?: return null)?.groupValues?.get(1) ?: return null
        val parts = path.trimEnd('/').split('/').filter { it.isNotEmpty() }
        if (parts.size == 3 && parts[0].equals("c", ignoreCase = true) && SEGMENT.matches(parts[1]) && SEGMENT.matches(parts[2]))
            return Target(null, parts[1], parts[2])
        if (parts.size == 2 && NAME.matches(parts[0]) && parts[0].lowercase() !in SITE_PAGES && SEGMENT.matches(parts[1]))
            return Target(parts[0], null, parts[1])
        return null
    }

    /** The channel a link names: its id as written, or its @name looked up (iOS GroupDirectory.resolve). */
    suspend fun cid(t: Target): String? = t.cid ?: t.name?.let { com.fariin.app.data.PublicGroups.resolveGroup(it) }

    /**
     * The post's id: as written, or its number found among [local] first, else one read in the channel
     * (the channel's own read rule decides, so a stranger to a private channel gets nothing).
     */
    suspend fun mid(t: Target, cid: String, local: List<Message> = emptyList()): String? {
        if (!isNumber(t.mid)) return t.mid
        val n = t.mid.toIntOrNull() ?: return null
        local.firstOrNull { it.postSeq == n }?.let { return it.id }
        if (!SEGMENT.matches(cid)) return null
        return runCatching {
            com.fariin.app.Fb.db.collection("conversations").document(cid).collection("messages")
                .whereEqualTo("postSeq", n).limit(1).get().await().documents.firstOrNull()?.id
        }.getOrNull()
    }
}
