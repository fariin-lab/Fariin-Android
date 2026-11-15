package com.fariin.app.data

/**
 * A story made from a channel post (iOS StoryPostLink, "Repost to Story", owner 2026-11-15). The
 * card is baked into the media; its "View post" pill is a Link tap target whose url is the post's
 * own link in its id form, https://fariin.com/c/<cid>/<mid>. In the story viewer that link opens
 * the channel at the post inside the app, never the browser (the site has no page for it).
 */
object StoryPostLink {
    private const val HOST = "fariin.com"
    private val SEGMENT = Regex("^[A-Za-z0-9_-]{1,64}$")
    private val NUMBER = Regex("^[0-9]+$")

    /** iOS segmentOK: one path piece that may be a channel or post id. */
    fun segmentOK(s: String): Boolean = SEGMENT.matches(s)

    /** The post a link names (cid to mid), only for the id form /c/<cid>/<mid> with mid not a post number. */
    fun ref(url: String?): Pair<String, String>? {
        val u = runCatching { java.net.URI(url?.trim() ?: return null) }.getOrNull() ?: return null
        if (!u.scheme.equals("https", ignoreCase = true)) return null
        val h = (u.host ?: "").lowercase()
        if (h != HOST && h != "www.$HOST") return null
        val parts = (u.rawPath ?: "").split('/').filter { it.isNotEmpty() }
        if (parts.size != 3 || parts[0] != "c") return null
        val cid = parts[1]
        val mid = parts[2]
        if (!segmentOK(cid) || !segmentOK(mid) || NUMBER.matches(mid)) return null
        return cid to mid
    }
}
