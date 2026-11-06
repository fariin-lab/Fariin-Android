package com.fariin.app.data

import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

/** One reply under a channel post, as `getReplies` / `addReply` answer it. [at] is ms. */
data class PostReply(
    val id: String,
    val uid: String,
    val name: String = "Someone",
    val photoUrl: String = "",
    val verified: Boolean = false,
    val text: String = "",
    val at: Long = 0,
    val mine: Boolean = false,
    val canDelete: Boolean = false,
    /** The reply this one answers ("" = it starts a thread). One level deep (server `addReply`). */
    val parentId: String = "",
    val likes: Int = 0,
    /** I put a heart on it. */
    val liked: Boolean = false,
)

/**
 * `getReplies`' answer: the last 300 replies, oldest first. [off] = replies are off here, for either
 * reason ([channelOff] the whole channel, [postOff] this post). [staff] = I run the channel.
 */
data class PostRepliesAnswer(
    val replies: List<PostReply>,
    val count: Int,
    val off: Boolean,
    val channelOff: Boolean,
    val postOff: Boolean,
    val staff: Boolean,
)

/**
 * REPLIES UNDER A CHANNEL POST (owner 2026-11-06, as iOS PostReplies): the words of the bar under
 * the post and when it shows, kept apart from the network and the screen so they can be tested.
 */
object PostReplyRules {
    /** The longest reply the server takes. */
    const val MAX_LENGTH = 1000

    /** Replies can be written: neither the channel nor the post has them off. */
    fun repliesOn(channelOff: Boolean, postOff: Boolean): Boolean = !channelOff && !postOff

    /**
     * The bar under a sent channel post: "3 Replies · 4 Quotes", "Reply" with neither yet, only the
     * quotes when replies are off. Null = no bar (a post still sending or failed, deleted, a notice,
     * or replies off and never quoted).
     */
    fun barLabel(replies: Int, quotes: Int, repliesOn: Boolean, sent: Boolean = true, deleted: Boolean = false): String? {
        if (!sent || deleted) return null
        if (!repliesOn && quotes <= 0) return null
        val parts = mutableListOf<String>()
        if (repliesOn) parts += if (replies <= 0) "Reply" else repliesWord(replies)
        if (quotes > 0) parts += PostQuoteRules.barLabel(quotes)
        return parts.joinToString(" · ")
    }

    /** "1 Reply", "3 Replies", "1.2K Replies". */
    fun repliesWord(n: Int): String = com.fariin.app.channel.ChannelRules.compactCount(n) + if (n == 1) " Reply" else " Replies"

    /** The quoting channels' faces show only once the post has been quoted. */
    fun showFaces(quotes: Int, faces: List<String>): Boolean = quotes > 0 && faces.isNotEmpty()

    /** Where the field would be, when replies are off. */
    fun offNote(channelOff: Boolean): String =
        if (channelOff) "Replies are turned off in this channel." else "Replies are turned off for this post."

    /** The admin menu's two lines, worded for what a tap does now. */
    fun postToggleLabel(postOff: Boolean): String =
        if (postOff) "Turn On Replies for This Post" else "Turn Off Replies for This Post"

    fun channelToggleLabel(channelOff: Boolean): String =
        if (channelOff) "Turn On Replies in Channel" else "Turn Off Replies in Channel"

    /** The words to send: trimmed; null when empty or over the server's limit. */
    fun cleanDraft(draft: String): String? = draft.trim().takeIf { it.isNotEmpty() && it.length <= MAX_LENGTH }

    /** One reply; null without its id and author. */
    fun parseReply(d: Map<*, *>?): PostReply? {
        if (d == null) return null
        val id = (d["id"] as? String)?.takeIf { it.isNotEmpty() } ?: return null
        val uid = (d["uid"] as? String)?.takeIf { it.isNotEmpty() } ?: return null
        return PostReply(
            id = id, uid = uid,
            name = (d["name"] as? String)?.takeIf { it.isNotBlank() } ?: "Someone",
            photoUrl = d["photoUrl"] as? String ?: "",
            verified = d["verified"] as? Boolean ?: false,
            text = d["text"] as? String ?: "",
            at = (d["at"] as? Number)?.toLong() ?: 0,
            mine = d["mine"] as? Boolean ?: false,
            canDelete = d["canDelete"] as? Boolean ?: false,
            parentId = d["parentId"] as? String ?: "",
            likes = maxOf(0, (d["likes"] as? Number)?.toInt() ?: 0),
            liked = d["liked"] as? Boolean ?: false,
        )
    }

    fun parse(d: Map<*, *>?): PostRepliesAnswer {
        val list = (d?.get("replies") as? List<*>)?.mapNotNull { parseReply(it as? Map<*, *>) } ?: emptyList()
        val channelOff = d?.get("channelOff") as? Boolean ?: false
        val postOff = d?.get("postOff") as? Boolean ?: false
        return PostRepliesAnswer(
            replies = list,
            count = maxOf(list.size, (d?.get("count") as? Number)?.toInt() ?: 0),
            off = (d?.get("off") as? Boolean ?: false) || channelOff || postOff,
            channelOff = channelOff,
            postOff = postOff,
            staff = d?.get("staff") as? Boolean ?: false,
        )
    }

    /** The replies that start a thread, oldest first. */
    fun threads(list: List<PostReply>): List<PostReply> = list.filter { it.parentId.isEmpty() }

    /** The answers under one reply, oldest first. */
    fun answers(list: List<PostReply>, id: String): List<PostReply> = list.filter { it.parentId == id }

    /** The thread an answer joins: answering an answer goes under the same top-level reply. */
    fun threadOf(r: PostReply): String = r.parentId.ifEmpty { r.id }

    /** Under a reply with answers: "View 1 reply", "View 3 replies", or "Hide replies" when open. */
    fun threadToggle(n: Int, open: Boolean): String =
        if (open) "Hide replies" else "View $n " + if (n == 1) "reply" else "replies"

    /** A reply's age, short: "now", "5m", "3h", "2d", "6w". */
    fun age(atMs: Long, nowMs: Long): String {
        val s = maxOf(0L, nowMs - atMs) / 1000
        return when {
            s < 60 -> "now"
            s < 3600 -> "${s / 60}m"
            s < 86_400 -> "${s / 3600}h"
            s < 7 * 86_400 -> "${s / 86_400}d"
            else -> "${s / (7 * 86_400)}w"
        }
    }

    /** The heart flipped on one reply, its count moved with it (never below 0). */
    fun toggledLike(list: List<PostReply>, id: String): List<PostReply> = list.map {
        if (it.id != id) it else it.copy(liked = !it.liked, likes = maxOf(0, it.likes + if (it.liked) -1 else 1))
    }

    /** The list once a reply is deleted: a top-level reply takes its answers with it. */
    fun removed(list: List<PostReply>, id: String): List<PostReply> = list.filterNot { it.id == id || it.parentId == id }

    /** A reply just sent joins the list once, even if a refresh brought it first. */
    fun appended(list: List<PostReply>, r: PostReply): List<PostReply> =
        if (list.any { it.id == r.id }) list else list + r
}

/** The network side of a post's replies (callables in me-central1). */
object PostReplies {
    private val fn: FirebaseFunctions get() = FirebaseFunctions.getInstance(AuthRepository.FUNCTIONS_REGION)

    private suspend fun call(name: String, args: Map<String, Any>): Map<*, *>? =
        fn.getHttpsCallable(name).withTimeout(15, TimeUnit.SECONDS).call(args).await().getData() as? Map<*, *>

    /** Null when the call failed (the sheet offers Retry). */
    suspend fun load(cid: String, mid: String): PostRepliesAnswer? = runCatching {
        PostReplyRules.parse(call("getReplies", mapOf("cid" to cid, "mid" to mid)))
    }.getOrNull()

    /** The new reply, or the sentence to show (the server's own when it refused, e.g. replies off or too fast). */
    suspend fun add(cid: String, mid: String, text: String, parentId: String? = null): Result<PostReply> = runCatching {
        val args = mutableMapOf<String, Any>("cid" to cid, "mid" to mid, "text" to text)
        if (!parentId.isNullOrEmpty()) args["parentId"] = parentId
        val d = call("addReply", args)
        PostReplyRules.parseReply(d?.get("reply") as? Map<*, *>) ?: error("Couldn't send your reply.")
    }.recoverCatching { e -> throw IllegalStateException(sentence(e)) }

    suspend fun delete(cid: String, mid: String, rid: String): Boolean = runCatching {
        call("deleteReply", mapOf("cid" to cid, "mid" to mid, "rid" to rid)); true
    }.getOrDefault(false)

    /** A heart on or off; false when the server did not take it (the caller rolls back). */
    suspend fun like(cid: String, mid: String, rid: String, on: Boolean): Boolean = runCatching {
        call("likeReply", mapOf("cid" to cid, "mid" to mid, "rid" to rid, "on" to on)); true
    }.getOrDefault(false)

    /** Admins only: [mid] null = the whole channel. */
    suspend fun setOff(cid: String, mid: String?, off: Boolean): Boolean = runCatching {
        val args = mutableMapOf<String, Any>("cid" to cid, "off" to off)
        if (mid != null) args["mid"] = mid
        call("setReplies", args); true
    }.getOrDefault(false)

    private fun sentence(e: Throwable): String {
        val fe = e as? FirebaseFunctionsException
            ?: return (e as? IllegalStateException)?.message ?: "Couldn't send your reply. Try again."
        return when (fe.code) {
            FirebaseFunctionsException.Code.FAILED_PRECONDITION,
            FirebaseFunctionsException.Code.PERMISSION_DENIED,
            FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED,
            FirebaseFunctionsException.Code.INVALID_ARGUMENT -> fe.message ?: "Couldn't send your reply."
            else -> "Couldn't send your reply. Try again."
        }
    }
}
