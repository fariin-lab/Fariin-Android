package com.fariin.app.data

import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

/**
 * One reply or answer under a channel post, as the server sends it (short keys, server
 * lib/replies.js `out`). [at] is ms. [parentId] is local: the thread an answer sits in ("" = it
 * starts a thread). [uid] only comes to moderators and the Ban users right.
 */
data class PostReply(
    val id: String,
    val uid: String = "",
    val name: String = "Someone",
    val photoUrl: String = "",
    val verified: Boolean = false,
    val text: String = "",
    val at: Long = 0,
    val mine: Boolean = false,
    val canDelete: Boolean = false,
    val parentId: String = "",
    /** How many people reacted (`lc`). */
    val likes: Int = 0,
    /** I reacted to it ([my] is not ""). */
    val liked: Boolean = false,
    /** Answers in this thread (threads only). */
    val answers: Int = 0,
    /** Held after reports: only its author and moderators see it. */
    val held: Boolean = false,
    val send: Send = Send.SENT,
    /** My emoji on it ("" = none); one per person per reply (server likeReply, owner 2026-11-07). */
    val my: String = "",
    /** Each emoji's count, biggest first (server `rx`; old hearts come as the heart). */
    val rx: List<Rx> = emptyList(),
    /**
     * The channel's owner wrote it, as the channel (server `cr`, owner 2026-11-07): [name], [photoUrl]
     * and [verified] are then the channel's; the row shows the channel's squircle and "Creator".
     */
    val creator: Boolean = false,
    /** An answer to one answer (or to the thread itself): whom it answers, as the server keeps it. */
    val to: ReplyTo? = null,
    /** Changed by its author after sending (editReply); the row says "· edited". */
    val edited: Boolean = false,
) {
    enum class Send { SENT, SENDING, FAILED }
    data class Rx(val emoji: String, val count: Int)
}

/** What the server says I may do here (getReplies `may`): delete others' replies, ban, the switches. */
data class ReplyRights(val del: Boolean = false, val ban: Boolean = false, val settings: Boolean = false)

/**
 * One page of `getReplies` (newest first on the wire, turned oldest first here). [next] = the
 * cursor for the page before it ("" = no more). [rev] = the change marker when the page was read.
 */
/** Whom an answer answers: that reply's id and its author's name at the time ("@name"). */
data class ReplyTo(val id: String, val name: String)

/** A comment to open the replies sheet on: post [mid], thread [rid], answer [aid] ("" = the thread itself). */
data class ReplyFocus(val mid: String, val rid: String, val aid: String)

data class RepliesPage(
    /** In the server's order for the page's sort (Top: most replied and liked first; Newest: newest first). */
    val items: List<PostReply>,
    val next: String,
    val count: Int,
    val off: Boolean,
    val channelOff: Boolean,
    val postOff: Boolean,
    val may: ReplyRights,
    val staff: Boolean,
    val rev: Int,
    /** The first page's pinned thread (one per post; left out of [items]); null = none. */
    val pinned: PostReply? = null,
)

/** One page of a thread's answers (`getReplyAnswers`), oldest first. */
data class AnswersPage(val items: List<PostReply>, val next: String)

/**
 * REPLIES UNDER A CHANNEL POST (owner 2026-11-06, as iOS PostReplies): the words of the bar, the
 * server's answers read, and how pages, sends and refreshes are merged, kept apart from the network
 * and the screen so they can be tested.
 */
object PostReplyRules {
    /** The longest reply the server takes. */
    const val MAX_LENGTH = 250   // server addReply (owner 2026-11-07), was 1000

    /** A refresh may drop my own reply saved this recently only if the page really lacks it. */
    const val RECENT_MINE_MS = 8_000L

    /** Replies can be written: neither the channel nor the post has them off. */
    fun repliesOn(channelOff: Boolean, postOff: Boolean): Boolean = !channelOff && !postOff

    /**
     * REPLIES ARE OFF BY DEFAULT IN A CHANNEL (server, owner 2026-11-07): the post's own switch
     * (`messages/{mid}.repliesOff`, true or false) wins; with none written the channel's counts
     * (missing there = off, ChannelInfo). [local] is a switch just changed on this phone, ahead of
     * the post's doc (an older post is outside the live window and would not hear it).
     */
    fun postOff(postOff: Boolean?, channelOff: Boolean, local: Boolean? = null): Boolean = local ?: postOff ?: channelOff

    /** The six of the chat's react bar, in the chat's order (a reply's long press shows them). */
    val REACT_BAR: List<String> get() = MessageRules.QUICK_REACTIONS

    /** What an old heart-only reply counts as. */
    const val HEART = "❤️"

    /**
     * The bar under a sent channel post: "3 Replies · 4 Quotes", "Reply" with neither yet. Off hides
     * the Reply part entirely (iOS, owner 2026-11-07: "in post don't show Reply"); the bar then stays
     * only for quotes. Null = no bar (a post still sending or failed, deleted, or off and never quoted).
     */
    fun barLabel(replies: Int, quotes: Int, repliesOn: Boolean, sent: Boolean = true, deleted: Boolean = false,
                 /** The channel's Quotes switch (`quotesOff` false): off leaves out the "N Quotes" part. */
                 quotesOn: Boolean = true): String? {
        if (!sent || deleted) return null
        val showQuotes = quotesOn && quotes > 0
        if (!repliesOn && !showQuotes) return null
        val parts = mutableListOf<String>()
        if (repliesOn) parts += if (replies <= 0) "Reply" else repliesWord(replies)
        if (showQuotes) parts += PostQuoteRules.barLabel(quotes)
        return parts.joinToString(" · ")
    }

    /** What the bar shows at its start: the comment icon, or up to three faces. */
    sealed interface BarStart {
        data object Icon : BarStart
        data class Faces(val urls: List<String>, val people: Boolean) : BarStart
    }

    /**
     * Replies on: the comment icon until someone replies, then the repliers' faces (iOS, owner
     * 2026-11-07). Replies off: the quoting channels' faces once quoted, as before.
     */
    fun barStart(repliesOn: Boolean, replies: Int, replyFaces: List<String>, quotes: Int, quoteFaces: List<String>): BarStart =
        if (repliesOn) {
            if (replies > 0 && replyFaces.isNotEmpty()) BarStart.Faces(replyFaces.take(3), people = true) else BarStart.Icon
        } else BarStart.Faces(if (showFaces(quotes, quoteFaces)) quoteFaces.take(3) else emptyList(), people = false)

    /** The tab the bar opens: Quotes (1) with replies off (the sheet is Quotes only then), else Replies (0). */
    fun startTab(repliesOn: Boolean): Int = if (!repliesOn) 1 else 0

    /** "1 Reply", "3 Replies", "1.2K Replies". */
    fun repliesWord(n: Int): String = com.fariin.app.channel.ChannelRules.compactCount(n) + if (n == 1) " Reply" else " Replies"

    /** The quoting channels' faces show only once the post has been quoted. */
    fun showFaces(quotes: Int, faces: List<String>): Boolean = quotes > 0 && faces.isNotEmpty()

    /** Where the field would be, when replies are off. */
    fun offNote(channelOff: Boolean): String =
        if (channelOff) "Replies are turned off in this channel." else "Replies are turned off for this post."

    /** The admin menu's lines, worded for what a tap does now. */
    fun postToggleLabel(postOff: Boolean): String =
        if (postOff) "Turn On Replies for This Post" else "Turn Off Replies for This Post"

    fun channelToggleLabel(channelOff: Boolean): String =
        if (channelOff) "Turn On Replies in Channel" else "Turn Off Replies in Channel"

    /** The words to send: trimmed; null when empty or over the server's limit. */
    fun cleanDraft(draft: String): String? = draft.trim().takeIf { it.isNotEmpty() && it.length <= MAX_LENGTH }

    /** The id a new reply goes with (the server keeps it as the reply's id: [A-Za-z0-9_-]{6,64}). */
    fun clientId(unique: String): String = unique.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(64)

    private fun n(v: Any?): Int = maxOf(0, (v as? Number)?.toInt() ?: 0)

    /**
     * The server's `rx` {emoji: count} as pills, biggest first (then by emoji, so the order holds
     * still); a server from before emoji reactions sends only `lc`: that many hearts.
     */
    fun parseRx(v: Any?, likes: Int): List<PostReply.Rx> {
        val m = v as? Map<*, *> ?: return if (likes > 0) listOf(PostReply.Rx(HEART, likes)) else emptyList()
        return m.entries.mapNotNull { (k, c) ->
            val e = (k as? String)?.takeIf { it.isNotEmpty() && it.length <= 16 } ?: return@mapNotNull null
            val count = n(c)
            if (count > 0) PostReply.Rx(e, count) else null
        }.sortedWith(compareByDescending<PostReply.Rx> { it.count }.thenBy { it.emoji })
    }

    /** One reply or answer (server `out`); null without its id. [parent] = its thread for an answer. */
    fun parseReply(d: Map<*, *>?, parent: String = ""): PostReply? {
        if (d == null) return null
        val id = (d["id"] as? String)?.takeIf { it.isNotEmpty() } ?: return null
        return PostReply(
            id = id,
            uid = d["u"] as? String ?: "",
            name = (d["n"] as? String)?.takeIf { it.isNotBlank() } ?: "Someone",
            photoUrl = d["ph"] as? String ?: "",
            verified = d["v"] as? Boolean ?: false,
            text = d["t"] as? String ?: "",
            at = (d["at"] as? Number)?.toLong() ?: 0,
            mine = d["mine"] as? Boolean ?: false,
            canDelete = d["del"] as? Boolean ?: false,
            parentId = parent,
            likes = n(d["lc"]),
            liked = d["liked"] as? Boolean ?: false,
            answers = n(d["ac"]),
            held = d["held"] as? Boolean ?: false,
            my = (d["my"] as? String)?.take(16) ?: if (d["liked"] == true) HEART else "",
            rx = parseRx(d["rx"], n(d["lc"])),
            creator = d["cr"] == true,
            to = (d["to"] as? Map<*, *>)?.let { t ->
                (t["id"] as? String)?.takeIf { it.isNotEmpty() }?.let { ReplyTo(it, (t["name"] as? String)?.takeIf { n -> n.isNotBlank() } ?: "Someone") }
            },
            edited = d["edited"] == true,
        ).let { it.copy(liked = it.my.isNotEmpty()) }
    }

    fun parseRights(d: Any?): ReplyRights {
        val m = d as? Map<*, *> ?: return ReplyRights()
        return ReplyRights(m["del"] == true, m["ban"] == true, m["settings"] == true)
    }

    /** `getReplies`' answer, the page turned oldest first. */
    fun parsePage(d: Map<*, *>?): RepliesPage {
        val list = (d?.get("items") as? List<*>)?.mapNotNull { parseReply(it as? Map<*, *>) } ?: emptyList()
        val channelOff = d?.get("channelOff") as? Boolean ?: false
        // The server's `postOff` is the post's effective switch (its own true or false over the
        // channel's): a post turned on in a channel that is off takes replies (owner 2026-11-07).
        val effective = d?.get("postOff") as? Boolean
        val postOff = effective ?: false
        return RepliesPage(
            items = list,
            next = d?.get("next") as? String ?: "",
            count = n(d?.get("count")),
            off = effective ?: ((d?.get("off") as? Boolean ?: false) || channelOff),
            channelOff = channelOff,
            postOff = postOff,
            may = parseRights(d?.get("may")),
            staff = d?.get("staff") as? Boolean ?: false,
            rev = n(d?.get("rev")),
            pinned = parseReply(d?.get("pinned") as? Map<*, *>),
        )
    }

    fun parseAnswers(d: Map<*, *>?, thread: String): AnswersPage = AnswersPage(
        (d?.get("items") as? List<*>)?.mapNotNull { parseReply(it as? Map<*, *>, thread) } ?: emptyList(),
        d?.get("next") as? String ?: "",
    )

    /** The change marker moved since the sheet last read it (the first value only sets the start). */
    fun headMoved(lastRev: Int, rev: Int, loaded: Boolean): Boolean = lastRev >= 0 && rev != lastRev && loaded

    /**
     * The first page, again or for the first time: pages loaded further up stay, and my replies
     * still on their way; the page's rows replace their older copies.
     */
    fun mergeFirst(shown: List<PostReply>, page: List<PostReply>): List<PostReply> {
        val ids = page.mapTo(HashSet()) { it.id }
        val firstAt = page.firstOrNull()?.at
        val kept = shown.filter { r -> r.id !in ids && (r.send != PostReply.Send.SENT || (firstAt == null || r.at < firstAt)) }
        return (kept + page).sortedBy { it.at }
    }

    /** What [mergeNewest] gives back: the list, the "earlier" cursor when it must change, new rows by others. */
    data class Merged(val list: List<PostReply>, val resetCursor: String?, val added: Int)

    /**
     * The newest page merged into what is shown (iOS refreshNewest): new rows added, changed ones
     * updated, ones gone from inside the page's time range removed. My own replies still sending,
     * failed, or saved in the last seconds ([recentMine]) are kept. A page that does not reach back
     * to what is shown replaces the list and the "earlier" cursor, so no gap is left between them.
     */
    fun mergeNewest(shown: List<PostReply>, page: List<PostReply>, next: String, recentMine: Set<String>): Merged {
        fun keepMine(r: PostReply) = r.send != PostReply.Send.SENT || r.id in recentMine
        val oldestShown = page.firstOrNull()?.at
            ?: return Merged(if (next.isEmpty()) shown.filter(::keepMine) else shown, null, 0)
        val ids = page.mapTo(HashSet()) { it.id }
        val newestKnown = shown.filter { it.send == PostReply.Send.SENT }.maxOfOrNull { it.at }
        val overlaps = newestKnown == null || newestKnown >= oldestShown
        val list = if (overlaps) shown.filter { keepMine(it) || it.at < oldestShown || it.id in ids }.toMutableList()
            else shown.filter(::keepMine).toMutableList()
        var added = 0
        for (r in page) {
            val i = list.indexOfFirst { it.id == r.id }
            if (i >= 0) list[i] = r else { list += r; if (!r.mine) added++ }
        }
        return Merged(list.sortedBy { it.at }, if (overlaps) null else next, added)
    }

    /** An earlier page put above what is shown, each row once. */
    fun mergeOlder(shown: List<PostReply>, page: List<PostReply>): List<PostReply> {
        val have = shown.mapTo(HashSet()) { it.id }
        return page.filter { it.id !in have } + shown
    }

    /** A thread's answers: a first page replaces the saved ones, a later one adds; my unsent ones stay at the end. */
    fun mergeAnswers(shown: List<PostReply>, page: List<PostReply>, more: Boolean): List<PostReply> {
        val local = shown.filter { it.send != PostReply.Send.SENT }
        val list = (if (more) shown.filter { it.send == PostReply.Send.SENT } else emptyList()).toMutableList()
        for (r in page) if (list.none { it.id == r.id }) list += r
        for (l in local) if (list.none { it.id == l.id }) list += l
        return list
    }

    /** A pick still on its way ([want], "" = none) wins over what an earlier answer said. */
    fun overlay(r: PostReply, want: String?): PostReply = if (want == null) r else withMine(r, want)

    /**
     * Mine becomes [emoji] ("" takes mine back): its pill and the old one move with it, a pill at 0
     * goes, and the people count changes only when I start or stop reacting.
     */
    fun withMine(r: PostReply, emoji: String): PostReply {
        if (emoji == r.my) return r
        val rx = r.rx.toMutableList()
        if (r.my.isNotEmpty()) {
            val i = rx.indexOfFirst { it.emoji == r.my }
            if (i >= 0) { val left = rx[i].count - 1; if (left <= 0) rx.removeAt(i) else rx[i] = rx[i].copy(count = left) }
        }
        if (emoji.isNotEmpty()) {
            val i = rx.indexOfFirst { it.emoji == emoji }
            if (i >= 0) rx[i] = rx[i].copy(count = rx[i].count + 1) else rx += PostReply.Rx(emoji, 1)
        }
        val likes = maxOf(0, r.likes + (if (r.my.isEmpty()) 1 else 0) - (if (emoji.isEmpty()) 1 else 0))
        return r.copy(my = emoji, liked = emoji.isNotEmpty(), rx = rx, likes = likes)
    }

    /** At most this many pills under a reply (iOS). */
    const val MAX_PILLS = 4

    /**
     * The pills a reply shows: the 4 most used (the list comes biggest first); when mine is not
     * among them it takes the 4th place, so my own reaction always stays in sight.
     */
    fun shownPills(rx: List<PostReply.Rx>, my: String): List<PostReply.Rx> {
        val sorted = rx.sortedWith(compareByDescending<PostReply.Rx> { it.count }.thenBy { it.emoji })
        val top = sorted.take(MAX_PILLS)
        if (my.isEmpty() || top.any { it.emoji == my }) return top
        val mine = sorted.firstOrNull { it.emoji == my } ?: return top
        return top.take(MAX_PILLS - 1) + mine
    }

    /**
     * EXACTLY ONE EMOJI, NOTHING AROUND IT (server okEmoji, owner 2026-11-07): spaces and invisible
     * marks that are no part of an emoji (zero-width spaces and joiners outside a sequence, word
     * joiners, BOM, soft hyphens, bidi marks) are dropped; what is left must be a single emoji (a
     * pictograph, with only the joiners, variation selectors, skin tones, tag characters or flag
     * letters an emoji is built from). Null when it is not one emoji (several, text, a custom key).
     */
    fun cleanEmoji(raw: String): String? {
        val cps = raw.codePoints().toArray().filterNot { cp ->
            Character.isWhitespace(cp) || cp == 0x200B || cp == 0x200C || cp == 0x2060 || cp == 0xFEFF || cp == 0x00AD ||
                cp == 0x200E || cp == 0x200F || cp in 0x202A..0x202E || cp in 0x2066..0x2069
        }.toMutableList()
        // A joiner or selector left hanging at either end is not part of the emoji.
        while (cps.isNotEmpty() && (cps.first() == 0x200D || cps.first() == 0xFE0F || cps.first() == 0xFE0E)) cps.removeAt(0)
        while (cps.isNotEmpty() && cps.last() == 0x200D) cps.removeAt(cps.lastIndex)
        if (cps.isEmpty() || cps.size > 16) return null
        fun part(cp: Int) = cp == 0x200D || cp == 0xFE0E || cp == 0xFE0F || cp in 0x1F3FB..0x1F3FF || cp in 0xE0020..0xE007F || cp == 0x20E3
        fun flag(cp: Int) = cp in 0x1F1E6..0x1F1FF
        fun picto(cp: Int) = cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x2300..0x23FF || cp in 0x2B00..0x2BFF ||
            cp == 0x00A9 || cp == 0x00AE || cp == 0x203C || cp == 0x2049 || cp == 0x2122 || cp == 0x2139 || cp in 0x2194..0x21AA ||
            cp == 0x24C2 || cp in 0x25AA..0x25FE || cp == 0x3030 || cp == 0x303D || cp == 0x3297 || cp == 0x3299
        if (cps.any { !part(it) && !flag(it) && !picto(it) }) return null
        // One emoji: flags come as exactly one pair; otherwise every pictograph after the first
        // must follow a joiner (a ZWJ sequence such as a family is still one emoji).
        val flags = cps.count(::flag)
        if (flags > 0) return if (flags == 2 && cps.size == 2) String(cps.toIntArray(), 0, 2) else null
        // A skin tone sits inside the pictograph range but is part of the emoji before it.
        val pictos = cps.withIndex().filter { picto(it.value) && !part(it.value) }
        if (pictos.isEmpty()) return null
        for (p in pictos.drop(1)) if (p.index == 0 || cps[p.index - 1] != 0x200D) return null
        return String(cps.toIntArray(), 0, cps.size)
    }

    /** A pick on [emoji]: the same one again takes mine back, as in the chat. */
    fun nextPick(r: PostReply, emoji: String): String = if (emoji == r.my) "" else emoji

    /** A reply of mine on its way, shown at once ("Sending…"). */
    fun pending(id: String, text: String, name: String, photoUrl: String, parent: String, now: Long, to: ReplyTo? = null): PostReply =
        PostReply(id = id, name = name.ifBlank { "You" }, photoUrl = photoUrl, text = text, at = now, mine = true,
            canDelete = true, parentId = parent, send = PostReply.Send.SENDING, to = to)

    // ---- Replies v2 (server 2026-11-07): sort, answers to answers, edit ----

    /** The two orders of the Replies tab; Top is the default. The server takes them as `sort`. */
    enum class Sort(val wire: String, val label: String) { TOP("top", "Top"), NEW("new", "Newest") }

    /** My reply may be edited this long after it was sent (server EDIT_MS). */
    const val EDIT_MS = 15 * 60 * 1000L

    /** Edit is offered on my own sent reply, within 15 minutes of sending, while replies are on. */
    fun canEdit(r: PostReply, off: Boolean, now: Long): Boolean =
        r.mine && r.send == PostReply.Send.SENT && !off && r.at > 0 && now - r.at <= EDIT_MS

    /**
     * FIVE AT A TIME (iOS ab0a283a, owner 2026-11-08): a thread of five or fewer shows them all on
     * "View N replies"; a longer one shows five, and each "View N more replies" adds five more.
     */
    const val ANSWERS_FIRST = 5

    /** "View 1 more reply", "View 4 more replies". */
    fun moreAnswers(n: Int): String = "View $n more " + if (n == 1) "reply" else "replies"

    /** Under the name: "5m", "5m · edited". */
    fun ageLine(r: PostReply, nowMs: Long): String = age(r.at, nowMs) + if (r.edited) " · edited" else ""

    /** A reply's words with its "@name " in front when it answers an answer; the name's length for the accent. */
    fun mentionPrefix(r: PostReply): String = r.to?.let { "@${it.name} " } ?: ""

    /**
     * Page one again (a refresh or a first load), merged by id in the sort's order: the page's rows
     * replace their copies; a row that was on page one before and is gone from it now (deleted,
     * held, or fallen to a later page) goes unless a later page holds it; my own still sending,
     * failed or just saved stay. Rows never seen on page one (later pages, mine) stay. [added] =
     * others' rows new to the list.
     */
    data class PageOne(val list: List<PostReply>, val added: List<String>)

    fun mergePageOne(shown: List<PostReply>, page: List<PostReply>, prevFirst: Set<String>, later: Set<String>,
                     recentMine: Set<String>): PageOne {
        val ids = page.mapTo(HashSet()) { it.id }
        val known = shown.mapTo(HashSet()) { it.id }
        val kept = shown.filter { r -> r.id !in ids &&
            (r.send != PostReply.Send.SENT || r.id in recentMine || r.id in later || r.id !in prevFirst) }
        return PageOne(kept + page, page.filter { it.id !in known && !it.mine }.map { it.id })
    }

    /**
     * The threads as drawn (the pinned one goes first separately): my own new ones not yet in any
     * page at the top, newest first; then the pages' rows in the server's order.
     */
    fun displayOrder(threads: List<PostReply>, order: List<String>, pinnedId: String): List<PostReply> {
        val byId = threads.associateBy { it.id }
        val inOrder = LinkedHashSet(order)
        val rest = threads.filter { it.id !in inOrder && it.id != pinnedId }.sortedByDescending { it.at }
        return rest + inOrder.mapNotNull { id -> byId[id]?.takeIf { it.id != pinnedId } }
    }

    /** A row's long-press menu, in order. */
    enum class Action { REPLY, COPY, EDIT, REPORT, DELETE, BAN, RETRY, PIN, UNPIN }

    /**
     * What the long-press offers, gated by the server's answer: Reply while replies are on; Copy;
     * Report on someone else's; Delete on mine or with the Delete messages right (the row's `del`);
     * Ban (and remove their replies) with the Ban users right, never on my own. A failed send offers
     * Retry and Delete only; one still sending, Copy only. Pin / Unpin with the switches' right
     * (owner, or Change info), on a top-level reply only, never an answer or a held one.
     */
    fun actions(r: PostReply, off: Boolean, may: ReplyRights, pinnedId: String = "", editable: Boolean = false): List<Action> = when (r.send) {
        PostReply.Send.SENDING -> listOf(Action.COPY)
        PostReply.Send.FAILED -> listOf(Action.RETRY, Action.COPY, Action.DELETE)
        PostReply.Send.SENT -> buildList {
            if (!off) add(Action.REPLY)
            if (may.settings && r.parentId.isEmpty() && !r.held) add(if (r.id == pinnedId) Action.UNPIN else Action.PIN)
            add(Action.COPY)
            if (editable) add(Action.EDIT)
            if (!r.mine) add(Action.REPORT)
            if (r.canDelete) add(Action.DELETE)
            if (may.ban && !r.mine) add(Action.BAN)
        }
    }

    /** The "..." menu shows at all: the switches (Change info) or reply alerts (Delete messages). */
    fun showsAdminMenu(may: ReplyRights): Boolean = may.settings || may.del

    /**
     * A reply alert's push (server replyPushJob: `kind` "postReplies" for an admin's summary,
     * "replyAnswer" for "X replied to you"): the channel and post it opens, or null for any other push.
     */
    fun alertTarget(kind: String?, cid: String?, mid: String?): Pair<String, String>? {
        // "replyReact" (owner 2026-11-09, Activity): someone reacted to my comment; same tap route.
        // "postQuote": someone quoted my post (Activity); it opens my post's sheet.
        if (kind != "postReplies" && kind != "replyAnswer" && kind != "replyReact" && kind != "postQuote") return null
        if (cid.isNullOrEmpty() || mid.isNullOrEmpty()) return null
        if (!com.fariin.app.channel.ChannelRules.safeId(cid) || !com.fariin.app.channel.ChannelRules.safeId(mid)) return null
        return cid to mid
    }

    /**
     * The comment a "replied to your comment" push is about (data `rid`, and `aid` for an answer):
     * the sheet opens on it, its thread open and the row lit. Null without a usable thread id.
     */
    fun focusOf(mid: String, rid: String?, aid: String?): ReplyFocus? {
        if (rid.isNullOrEmpty() || !com.fariin.app.channel.ChannelRules.safeId(rid)) return null
        val a = aid?.takeIf { it.isNotEmpty() && com.fariin.app.channel.ChannelRules.safeId(it) } ?: ""
        return ReplyFocus(mid, rid, a)
    }

    /** Under a reply with answers: "View 1 reply", "View 3 replies", or "Hide replies" when open. */
    fun threadToggle(n: Int, open: Boolean): String =
        if (open) "Hide replies" else "View $n " + if (n == 1) "reply" else "replies"

    /** The pill over the list when others' replies land off-screen: "1 new reply", "3 new replies". */
    fun newPill(n: Int): String = "$n new " + if (n == 1) "reply" else "replies"

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
}

/** The network side of a post's replies (callables in me-central1). */
object PostReplies {
    private val fn: FirebaseFunctions get() = FirebaseFunctions.getInstance(AuthRepository.FUNCTIONS_REGION)

    private suspend fun call(name: String, args: Map<String, Any>, seconds: Long = 15): Map<*, *>? =
        fn.getHttpsCallable(name).withTimeout(seconds, TimeUnit.SECONDS).call(args).await().getData() as? Map<*, *>

    /** The ids of one reply or answer as the server takes them: `rid` the thread, `aid` the answer. */
    private fun target(r: PostReply): Map<String, Any> =
        if (r.parentId.isNotEmpty()) mapOf("rid" to r.parentId, "aid" to r.id) else mapOf("rid" to r.id)

    /**
     * A page of threads in [sort]'s order ([cursor] "" = page one; the server's cursor goes back as
     * it came). The sort is always sent: the server reads a missing one as Newest. Null when the call failed.
     */
    suspend fun page(cid: String, mid: String, cursor: String = "", sort: PostReplyRules.Sort = PostReplyRules.Sort.TOP): RepliesPage? = runCatching {
        val a = mutableMapOf<String, Any>("cid" to cid, "mid" to mid, "sort" to sort.wire)
        if (cursor.isNotEmpty()) a["cursor"] = cursor
        PostReplyRules.parsePage(call("getReplies", a))
    }.getOrNull()

    /** A page of one thread's answers. Null when the call failed. */
    suspend fun answers(cid: String, mid: String, rid: String, cursor: String = ""): AnswersPage? = runCatching {
        val a = mutableMapOf<String, Any>("cid" to cid, "mid" to mid, "rid" to rid)
        if (cursor.isNotEmpty()) a["cursor"] = cursor
        PostReplyRules.parseAnswers(call("getReplyAnswers", a), rid)
    }.getOrNull()

    /**
     * Sends a reply, or an answer in thread [rid]. [clientId] becomes the reply's id: a resend
     * with the same id after a lost answer gets the same reply back, counted once.
     */
    suspend fun add(cid: String, mid: String, clientId: String, text: String, rid: String, to: ReplyTo? = null): Result<PostReply> = runCatching {
        val a = mutableMapOf<String, Any>("cid" to cid, "mid" to mid, "text" to text, "clientId" to clientId)
        if (rid.isNotEmpty()) a["rid"] = rid
        // Whom an answer answers (the server puts that reply's own author name on it).
        if (rid.isNotEmpty() && to != null) a["to"] = mapOf("id" to to.id, "name" to to.name)
        PostReplyRules.parseReply(call("addReply", a)?.get("reply") as? Map<*, *>, rid) ?: error("Couldn't send your reply.")
    }.recoverCatching { e -> throw IllegalStateException(sentence(e)) }

    /** My reply's new words (author only, within 15 minutes). The saved row, or the server's reason. */
    suspend fun edit(cid: String, mid: String, r: PostReply, text: String): Result<PostReply> = runCatching {
        PostReplyRules.parseReply(call("editReply", mapOf("cid" to cid, "mid" to mid, "text" to text) + target(r))?.get("reply") as? Map<*, *>,
            r.parentId) ?: error("Couldn't save your edit.")
    }.recoverCatching { e ->
        val fe = e as? FirebaseFunctionsException
        throw IllegalStateException(if (fe != null && fe.code != FirebaseFunctionsException.Code.INTERNAL &&
            fe.code != FirebaseFunctionsException.Code.UNAVAILABLE) fe.message ?: "Couldn't save your edit." else "Couldn't save your edit. Try again.")
    }

    /** How many replies went (a thread takes its answers); null when the server refused. */
    suspend fun delete(cid: String, mid: String, r: PostReply): Int? = runCatching {
        n(call("deleteReply", mapOf("cid" to cid, "mid" to mid) + target(r))?.get("removed"))
    }.getOrNull()

    /**
     * My reaction on a reply: [emoji] ("" = take mine off; the server's default is the heart). False
     * when the server did not take it (the caller rolls back).
     */
    suspend fun react(cid: String, mid: String, r: PostReply, emoji: String): Boolean = reactOrWhy(cid, mid, r, emoji) == null

    /**
     * The same, with the server's reason when it refuses (replies turned off for the post, too
     * fast), or a short line of our own; null = taken. Only one clean emoji ever goes out.
     */
    suspend fun reactOrWhy(cid: String, mid: String, r: PostReply, emoji: String): String? {
        val e = if (emoji.isEmpty()) "" else PostReplyRules.cleanEmoji(emoji) ?: return "Pick an emoji."
        return runCatching {
            val a = mutableMapOf<String, Any>("cid" to cid, "mid" to mid, "on" to e.isNotEmpty())
            if (e.isNotEmpty()) a["emoji"] = e
            call("likeReply", a + target(r)); null
        }.getOrElse { ex ->
            val fe = ex as? FirebaseFunctionsException
            when (fe?.code) {
                FirebaseFunctionsException.Code.FAILED_PRECONDITION, FirebaseFunctionsException.Code.PERMISSION_DENIED,
                FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED, FirebaseFunctionsException.Code.INVALID_ARGUMENT ->
                    fe.message ?: "Couldn't react. Try again."
                else -> "Couldn't react. Try again."
            }
        }
    }

    /** Delete and ban; [all] also removes every reply they wrote in this channel. */
    suspend fun ban(cid: String, mid: String, r: PostReply, all: Boolean): Boolean = runCatching {
        call("banReplier", mapOf("cid" to cid, "mid" to mid, "all" to all) + target(r), seconds = 120); true
    }.getOrDefault(false)

    /** Pins thread [rid] under the post (one pin per post, a new one replaces it); "" unpins. */
    suspend fun pin(cid: String, mid: String, rid: String): Boolean = runCatching {
        val a = mutableMapOf<String, Any>("cid" to cid, "mid" to mid)
        if (rid.isNotEmpty()) a["rid"] = rid
        call("pinReply", a); true
    }.getOrDefault(false)

    /**
     * Who wrote [r], for a tap on its photo or name (pages carry no authors' ids): their uid, or
     * null with [channel] true for a Creator reply (it speaks as the channel). Failure = the error.
     */
    data class Author(val uid: String, val channel: Boolean)

    suspend fun author(cid: String, mid: String, r: PostReply): Result<Author> = runCatching {
        val d = call("replyAuthor", mapOf("cid" to cid, "mid" to mid) + target(r))
        Author(d?.get("uid") as? String ?: "", d?.get("channel") == true)
    }

    /** Change info right: [mid] null = the whole channel. */
    suspend fun setOff(cid: String, mid: String?, off: Boolean): Boolean = runCatching {
        val args = mutableMapOf<String, Any>("cid" to cid, "off" to off)
        if (mid != null) args["mid"] = mid
        call("setReplies", args); true
    }.getOrDefault(false)

    /** The channel's Quotes switch (setChannelQuotes {cid, off}, changeInfo right; writes quotesOff). Throws on refusal. */
    suspend fun setQuotesOff(cid: String, off: Boolean) {
        call("setChannelQuotes", mapOf("cid" to cid, "off" to off))
    }

    /** An admin's summaries of new replies in this channel, on or off. */
    suspend fun setAlerts(cid: String, on: Boolean): Boolean = runCatching {
        call("setReplyAlerts", mapOf("cid" to cid, "on" to on)); true
    }.getOrDefault(false)

    private fun n(v: Any?): Int = maxOf(0, (v as? Number)?.toInt() ?: 0)

    private fun sentence(e: Throwable): String {
        val fe = e as? FirebaseFunctionsException
            ?: return (e as? IllegalStateException)?.message ?: "Couldn't send your reply. Try again."
        return when (fe.code) {
            FirebaseFunctionsException.Code.FAILED_PRECONDITION,
            FirebaseFunctionsException.Code.PERMISSION_DENIED,
            FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED,
            FirebaseFunctionsException.Code.NOT_FOUND,
            FirebaseFunctionsException.Code.INVALID_ARGUMENT -> fe.message ?: "Couldn't send your reply."
            else -> "Couldn't send your reply. Try again."
        }
    }
}
