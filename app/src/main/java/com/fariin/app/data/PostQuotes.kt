package com.fariin.app.data

import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

/** One quote of a channel post, sent in a channel that is public now (`getPostQuotes`). */
data class QuotedIn(
    val cid: String,
    val mid: String,
    val title: String = "Channel",
    val photoUrl: String = "",
    val username: String = "",
    val verified: Boolean = false,
    val followers: Int = 0,
    val text: String = "",
    /** When the quote was sent, ms. */
    val at: Long = 0,
    val views: Int = 0,
)

/** `getPostQuotes`' answer: every quote, the public ones by name (most viewed first), the rest a number. */
data class PostQuotesAnswer(val total: Int, val hidden: Int, val items: List<QuotedIn>)

/**
 * WHO QUOTED A POST (owner 2026-11-06, as iOS QuotesPage): the words, numbers and the route of a
 * tapped row, kept apart from the network and the screen so they can be tested.
 */
object PostQuoteRules {
    /** The post's `quoteFaces`: at most 3, anything that is not a string becomes "" (no photo). */
    fun faces(raw: Any?): List<String> = (raw as? List<*>)?.take(3)?.map { it as? String ?: "" } ?: emptyList()

    /** One item; null without its chat and message ids. */
    fun parseItem(d: Map<*, *>?): QuotedIn? {
        if (d == null) return null
        val cid = (d["cid"] as? String)?.takeIf { it.isNotEmpty() } ?: return null
        val mid = (d["mid"] as? String)?.takeIf { it.isNotEmpty() } ?: return null
        fun s(k: String) = d[k] as? String ?: ""
        fun n(k: String) = maxOf(0, (d[k] as? Number)?.toInt() ?: 0)
        return QuotedIn(cid, mid, s("title").ifEmpty { "Channel" }, s("photoUrl"), s("username"),
            d["verified"] as? Boolean ?: false, n("followers"), s("text"), (d["at"] as? Number)?.toLong() ?: 0, n("views"))
    }

    fun parse(d: Map<*, *>?): PostQuotesAnswer {
        val items = (d?.get("items") as? List<*>)?.mapNotNull { parseItem(it as? Map<*, *>) } ?: emptyList()
        val total = (d?.get("total") as? Number)?.toInt() ?: items.size
        val hidden = maxOf(0, (d?.get("hidden") as? Number)?.toInt() ?: 0)
        return PostQuotesAnswer(maxOf(total, items.size), hidden, items)
    }

    private fun compact(n: Int) = com.fariin.app.channel.ChannelRules.compactCount(n)

    /** The bar under the post: "1 Quote", "12 Quotes", "1.2K Quotes". */
    fun barLabel(n: Int): String = compact(n) + if (n == 1) " Quote" else " Quotes"

    /** The numbers row: quotes, channels (distinct public ones), views (summed over the public quotes). */
    data class Stat(val value: String, val label: String)

    fun stats(a: PostQuotesAnswer): List<Stat> = listOf(
        Stat(compact(a.total), if (a.total == 1) "quote" else "quotes"),
        a.items.map { it.cid }.toSet().size.let { Stat(compact(it), if (it == 1) "channel" else "channels") },
        a.items.sumOf { it.views.toLong() }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            .let { Stat(compact(it), if (it == 1) "view" else "views") },
    )

    /** The small line at the top of the sheet: "3 quotes · 2 channels · 1.3K views". */
    fun summaryLine(a: PostQuotesAnswer): String = stats(a).joinToString(" · ") { it.value + " " + it.label }

    /** Under a quoting channel's name: "1 follower", "1.2K followers", "12K followers". */
    fun followersLine(n: Int): String = compact(n) + if (n == 1) " follower" else " followers"

    /** No quote to name and none hidden: the sheet says "No quotes yet". */
    fun isEmpty(a: PostQuotesAnswer): Boolean = a.items.isEmpty() && a.hidden <= 0

    /** The words on the post's card: its text, else what kind of post it is. */
    fun cardText(text: String?, type: String?, isAlbum: Boolean): String =
        text?.trim().orEmpty().ifEmpty { kindName(type, isAlbum) }

    /** A row's eye count: "1 view", "1.2K views". */
    fun viewsLine(n: Int): String = compact(n) + if (n == 1) " view" else " views"

    /** The locked row under the list; null when every quote is public. */
    fun hiddenLine(n: Int): String? = if (n <= 0) null else "$n more in private chats and groups"

    /** The post at the top, when it has no words. */
    fun kindName(type: String?, isAlbum: Boolean): String = when {
        isAlbum || type == "album" -> "Album"
        type == "image" -> "Photo"
        type == "video" -> "Video"
        else -> "Post"
    }

    enum class Route {
        /** The quote is in the chat that is open: scroll to it and light it. */
        SAME_CHAT,
        /** A channel I have (in my chats or followed): open it at the quote. */
        MY_CHANNEL,
        /** A public channel I don't follow: its public page (fariin.com/<username>), from outside. */
        PUBLIC_PAGE,
    }

    fun route(it: QuotedIn, openCid: String?, known: Boolean): Route = when {
        it.cid == openCid -> Route.SAME_CHAT
        known || it.username.isEmpty() -> Route.MY_CHANNEL
        else -> Route.PUBLIC_PAGE
    }
}

/** The network side: one `getPostQuotes` call per open of the Quotes sheet. */
object PostQuotes {
    private val fn: FirebaseFunctions get() = FirebaseFunctions.getInstance(AuthRepository.FUNCTIONS_REGION)

    /** Null when the call failed (the sheet offers Retry). */
    suspend fun load(cid: String, mid: String): PostQuotesAnswer? = runCatching {
        val r = fn.getHttpsCallable("getPostQuotes").withTimeout(15, TimeUnit.SECONDS)
            .call(mapOf("cid" to cid, "mid" to mid)).await().getData() as? Map<*, *>
        PostQuoteRules.parse(r)
    }.getOrNull()
}
