package com.fariin.app.data

/**
 * A quoted channel post (iOS QuoteCard, owner 2026-11-06). The message carries only `quote: { qid }`
 * in the clear beside its sealed text; what the card shows is the server's answer for this reader
 * (`getQuotes`), which checks every time whether the source channel may be seen.
 */
data class QuoteCard(
    val state: State,
    val cid: String = "",
    val mid: String = "",
    val title: String = "",
    val photoUrl: String = "",
    val username: String = "",
    val text: String = "",
    /** "text", "photo", "video" or "album". */
    val kind: String = "text",
    val image: String = "",
) {
    /** OK: the post as it was quoted. DELETED: the channel's face, "Post deleted". HIDDEN: the
     *  channel is private to this reader, nothing of the post. GONE: the channel no longer exists. */
    enum class State { OK, DELETED, HIDDEN, GONE }
}

/** The card's words and the cache's age rule, kept apart from the network so they can be tested. */
object QuoteRules {
    private val QID = Regex("^[a-z0-9]{16}$")

    /** How long an answer is trusted before the server is asked again (access can change). */
    const val FRESH_MS = 120_000L

    /** At most this many ids in one getQuotes call. */
    const val BATCH = 50

    fun isQid(s: String?): Boolean = s != null && QID.matches(s)

    /** One entry of getQuotes' `quotes` map (or createQuote's answer with [forceOk]); null when unreadable. */
    fun parse(d: Map<*, *>?, forceOk: Boolean = false): QuoteCard? {
        if (d == null) return null
        val state = if (forceOk) QuoteCard.State.OK else when (d["state"] as? String) {
            "ok" -> QuoteCard.State.OK
            "deleted" -> QuoteCard.State.DELETED
            "private" -> QuoteCard.State.HIDDEN
            "gone" -> QuoteCard.State.GONE
            else -> return null
        }
        fun s(k: String) = d[k] as? String ?: ""
        return QuoteCard(state, s("cid"), s("mid"), s("title"), s("photoUrl"), s("username"), s("text"),
            (d["kind"] as? String)?.ifEmpty { null } ?: "text", s("image"))
    }

    /** The card's title: the channel's name, or "Channel post" when nothing of it may be shown. */
    fun heading(c: QuoteCard?): String = when (c?.state) {
        null -> "Channel post"
        QuoteCard.State.OK, QuoteCard.State.DELETED -> c.title.ifEmpty { "Channel" }
        QuoteCard.State.HIDDEN, QuoteCard.State.GONE -> "Channel post"
    }

    /** The card's middle line. Null card: the quiet loading line. */
    fun body(c: QuoteCard?): String = when (c?.state) {
        null -> "Loading…"
        QuoteCard.State.OK -> c.text.ifEmpty {
            when (c.kind) {
                "photo" -> "Photo"
                "video" -> "Video"
                "album" -> "Album"
                else -> "Post"
            }
        }
        QuoteCard.State.DELETED -> "Post deleted"
        QuoteCard.State.HIDDEN -> "This channel is private"
        QuoteCard.State.GONE -> "Content unavailable"
    }

    /** The small line under them. */
    fun footer(c: QuoteCard?): String =
        if (c?.state == QuoteCard.State.OK && c.username.isNotEmpty()) "Quoted from @${c.username}" else "Quoted post"

    /** The picture on top: only for a post this reader may see. */
    fun image(c: QuoteCard?): String? = c?.takeIf { it.state == QuoteCard.State.OK }?.image?.ifEmpty { null }

    /** What a tap says instead of opening, or null when it opens the post. */
    fun tapNotice(c: QuoteCard?): String? = when (c?.state) {
        null -> null
        QuoteCard.State.OK -> null
        QuoteCard.State.DELETED -> "This post was deleted"
        QuoteCard.State.HIDDEN -> "This channel is private"
        QuoteCard.State.GONE -> "Content unavailable"
    }

    /** Whether the answer fetched at [fetchedAt] (ms, null = never) should be asked for again. */
    fun isStale(fetchedAt: Long?, now: Long): Boolean = fetchedAt == null || now - fetchedAt > FRESH_MS

    /** After a failed call: the time to record so the id is asked again about 15 s later, not in a loop. */
    fun retryStamp(now: Long): Long = now - FRESH_MS + 15_000L
}
