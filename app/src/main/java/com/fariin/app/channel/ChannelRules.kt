package com.fariin.app.channel

import com.fariin.app.data.Conversation

/**
 * CHANNELS, the pure parts (Kulan-iOS ChannelService.swift, GroupDirectory.swift, Models.swift
 * Conversation channel fields, ChannelViews.swift, PublicGroupSheet.swift). A channel is a group
 * with a hat: only admins post, nobody sees who follows. Admins are in `users`; followers are NOT
 * (they live in conversations/{cid}/followers/{uid}, server written, and are mirrored in
 * users/{me}/channels/{cid} with my `read` count and `muted`).
 */
object ChannelRules {

    // ---- Counts --------------------------------------------------------------------------------

    /** GroupDirectory.shortCount: 9,999 stays whole, then 12K, 128K, 2.4M. */
    fun shortCount(n: Int): String {
        fun cut(v: Double, unit: String): String {
            val s = if (v < 10) {
                val tenths = Math.floor(v * 10).toLong()
                "${tenths / 10}.${tenths % 10}"
            } else v.toLong().toString()
            return (if (s.endsWith(".0")) s.dropLast(2) else s) + unit
        }
        if (n >= 1_000_000) return cut(n / 1_000_000.0, "M")
        if (n >= 10_000) return cut(n / 1_000.0, "K")
        return n.toString()
    }

    /** GroupDirectory.followersLine: "1 follower", "128K followers". */
    fun followersLine(n: Int): String = "${shortCount(n)} ${if (n == 1) "follower" else "followers"}"

    /** The short form the channel's page and the post's eye use (GroupInfoView.compactCount and
     *  BubbleText.compactViews): 999, 1.2K, 12K, 2.4M, 1B. iOS writes "k" on the page and "K" on
     *  posts; `lower` picks the page's. */
    fun compactCount(n: Int, lower: Boolean = false): String {
        fun short(v: Double, unit: String): String {
            if (v >= 10) return "${v.toLong()}$unit"
            val tenths = Math.floor(v * 10).toLong()
            return if (tenths % 10 == 0L) "${tenths / 10}$unit" else "${tenths / 10}.${tenths % 10}$unit"
        }
        if (n < 1000) return n.toString()
        if (n < 1_000_000) return short(n / 1000.0, if (lower) "k" else "K")
        if (n < 1_000_000_000) return short(n / 1_000_000.0, "M")
        return short(n / 1_000_000_000.0, "B")
    }

    /** The visitor / follower page's count line: "1.2k Followers" (PublicGroupSheet.countLine). */
    fun pageCountLine(n: Int): String = "${compactCount(n, lower = true)} Follower${if (n == 1) "" else "s"}"

    /** A post's eye: my own post shows at least 1 the moment it lands (the server counts the author). */
    fun shownViews(views: Int, authorId: String, me: String): Int = if (authorId == me) maxOf(views, 1) else views

    // ---- Emoji (ChannelEmoji) ------------------------------------------------------------------

    /** "❤" and "❤️" are one emoji to the server (canonEmoji): the variation selectors go. */
    fun emojiKey(e: String): String = e.replace("️", "").replace("︎", "")
    fun sameEmoji(a: String?, b: String?): Boolean = a != null && b != null && emojiKey(a) == emojiKey(b)
    fun containsEmoji(list: List<String>, e: String): Boolean = list.any { sameEmoji(it, e) }

    /**
     * A post's counts as shown (ChannelEmoji.merged): keys that are the same emoji folded into one
     * (drawn in the form with the selector), and my pick still on its way moved in at once.
     * `held` = my pick the server already has; `mine` = what I picked now (null = taken back).
     */
    fun mergedCounts(base: Map<String, Int>, mine: String?, held: String?): Map<String, Int> {
        val shown = LinkedHashMap<String, String>()
        val total = LinkedHashMap<String, Int>()
        for ((e, n) in base) {
            val k = emojiKey(e)
            val cur = shown[k]
            if (cur == null || cur.codePointCount(0, cur.length) < e.codePointCount(0, e.length)) shown[k] = e
            total[k] = (total[k] ?: 0) + n
        }
        if ((mine != null || held != null) && !sameEmoji(mine, held)) {
            if (held != null) total[emojiKey(held)] = maxOf(0, (total[emojiKey(held)] ?: 0) - 1)
            if (mine != null) {
                val k = emojiKey(mine)
                if (shown[k] == null) shown[k] = mine
                total[k] = (total[k] ?: 0) + 1
            }
        }
        val out = LinkedHashMap<String, Int>()
        for ((k, n) in total) if (n > 0) out[shown[k] ?: k] = n
        return out
    }

    data class Chip(val emoji: String, val count: Int, val mine: Boolean)

    /** MessageRowModelBuilder.channelChips: counts only, never faces; most first, then by emoji. */
    fun chips(counts: Map<String, Int>, mine: String?, held: String?): List<Chip> =
        mergedCounts(counts, mine, held).map { (e, n) -> Chip(e, n, sameEmoji(mine, e)) }
            .sortedWith(compareByDescending<Chip> { it.count }.thenByDescending { it.emoji })

    /** EmojiCatalog.channelReactions: the 73 a channel may offer, in their order. */
    val channelReactions: List<String> = ("❤️ 👍 👎 🔥 🥰 👏 😁 🤔 🤯 😱 🤬 😢 🎉 🤩 🤮 💩 🙏 👌 🕊️ 🤡 🥱 🥴 😍 🐳 ❤️‍🔥 🌚 🌭 💯 🤣 ⚡️ 🍌 🏆 💔 🤨 😐 " +
        "🍓 🍾 💋 🖕 😈 😴 😭 🤓 👻 👨‍💻 👀 🎃 🙈 😇 😨 🤝 ✍️ 🤗 🫡 🎅 🎄 ☃️ 💅 🤪 🗿 🆒 💘 🙉 🦄 😘 💊 🙊 😎 👾 " +
        "🤷‍♂️ 🤷 🤷‍♀️ 😡").split(" ").filter { it.isNotEmpty() }

    /** The long-press bar for a channel: its whole own list ("some", up to 12), else the catalog's first six. */
    fun offered(mode: String, list: List<String>): List<String> = when (mode) {
        "off" -> emptyList()
        "some" -> list.take(com.fariin.app.data.ChannelReactions.MAX)
        else -> channelReactions.take(6)
    }

    /** What "More" may pick from: "some" only its list, "all" the 73. */
    fun pickable(mode: String, list: List<String>): List<String> = when (mode) {
        "off" -> emptyList()
        "some" -> list
        else -> channelReactions
    }

    /** Tapping the emoji I already picked takes it back. */
    fun nextPick(current: String?, tapped: String): String? = if (sameEmoji(current, tapped)) null else tapped

    // ---- Who am I here -------------------------------------------------------------------------

    enum class Role { ADMIN, FOLLOWER, VISITOR }

    fun role(info: ChannelInfo, me: String, follows: Boolean): Role = when {
        info.users.contains(me) -> Role.ADMIN
        follows -> Role.FOLLOWER
        else -> Role.VISITOR
    }

    /** ThreadView.channelCanReact: reactions on, and I run it, follow it, or it is public. */
    fun canReact(info: ChannelInfo, role: Role, guest: Boolean): Boolean =
        info.reactMode != "off" && !guest && (role != Role.VISITOR || info.isPublic)

    /** A follower's unread: posts past my read mark, deleted ones left out (Conversation.unread). */
    fun unread(postCount: Int, read: Int, deletedSeqs: List<Int>): Int =
        maxOf(0, postCount - read - deletedSeqs.count { it > read })

    /** Conversation.subscriberCount: followers plus the admins, so a new channel reads 1, never 0. */
    fun subscriberCount(followerCount: Int, admins: Int, bias: Int = 0): Int = maxOf(0, followerCount + bias + admins)

    // ---- The page's buttons (owner's rule: they never reshuffle after following) --------------

    enum class Action { FOLLOW, LEAVE, SHARE, SEARCH, MUTE, UNMUTE, MORE }

    data class Circle(val action: Action, val label: String, val dimmed: Boolean)

    /**
     * PublicGroupSheet visitorActionsRow / followerActionsRow: ONE order before and after (owner
     * 2026-11-01: "only Follow should change"): Follow or Leave, Share, Search, Mute. A visitor's
     * Search and Mute are dimmed (they only explain); Share is dimmed when there is no public link.
     * An admin has no Follow / Leave circle.
     */
    fun circles(role: Role, hasLink: Boolean, muted: Boolean): List<Circle> {
        val following = role != Role.VISITOR
        return buildList {
            when (role) {
                Role.VISITOR -> add(Circle(Action.FOLLOW, "Follow", false))
                Role.FOLLOWER -> add(Circle(Action.LEAVE, "Leave", false))
                Role.ADMIN -> {
                    // iOS, the owner's page (2026-11-08): Mute, Search, Share, More.
                    add(if (muted) Circle(Action.UNMUTE, "Unmute", false) else Circle(Action.MUTE, "Mute", false))
                    add(Circle(Action.SEARCH, "Search", false))
                    // Never grey for an admin (iOS, owner 2026-11-08): a private channel shares its invite link.
                    add(Circle(Action.SHARE, "Share", false))
                    add(Circle(Action.MORE, "More", false))
                    return@buildList
                }
            }
            add(Circle(Action.SHARE, "Share", !hasLink && !following))
            add(Circle(Action.SEARCH, "Search", !following))
            add(if (muted && following) Circle(Action.UNMUTE, "Unmute", false) else Circle(Action.MUTE, "Mute", !following))
        }
    }

    /** What a dimmed circle says when tapped. */
    fun visitorNotice(a: Action): String? = when (a) {
        Action.SEARCH -> "Follow the channel to search its posts."
        Action.MUTE, Action.UNMUTE -> "Follow the channel to change its notifications."
        else -> null
    }

    /** "Follow <title> on Fariin: https://fariin.com/<name>" (the visitor page's Share). */
    fun shareText(title: String, username: String): String? =
        if (username.isEmpty()) null else "Follow $title on Fariin: https://fariin.com/$username"

    /** A post's own link (fariin.com/<name>/<mid> for a public channel), copied by Share. */
    fun postLink(username: String, mid: String): String? =
        if (username.isEmpty()) null else "https://fariin.com/$username/$mid"

    const val REQUEST_SENT = "Request sent. An admin will review it."
    const val FOLLOWING_NOTICE = "You're following this channel."

    // ---- Words (ChannelWords) ------------------------------------------------------------------

    private val swaps = listOf(
        "Join Requests" to "Follow Requests", "join requests" to "follow requests",
        "Join Request" to "Follow Request", "join request" to "follow request",
        "New Members" to "New Followers", "Members" to "Followers", "members" to "followers",
        "Member" to "Follower", "member" to "follower",
        "Messages" to "Posts", "messages" to "posts", "Message" to "Post", "message" to "post",
        "Groups" to "Channels", "groups" to "channels", "Group" to "Channel", "group" to "channel",
    )

    /**
     * A system line in a channel (iOS ThreadRepository.channelNotice): only the channel's own events
     * stay, said without the admin's name. Everything an admin did (wallpaper, verification, who may
     * post, adds, restrictions) is not news for followers; null = no row. Pins and timer lines are
     * kept by the caller before this is asked.
     */
    fun channelNotice(text: String): String? {
        val renamed = " renamed the group to "
        return when {
            text == "Channel created" || text.endsWith(" created the group") || text.endsWith(" created the channel") -> "Channel created"
            text.contains(renamed) -> "Channel name changed to " + text.substringAfter(renamed)
            text.endsWith(" removed the group photo") -> "Channel photo removed"
            else -> null
        }
    }

    /**
     * The rows a channel's list shows (iOS ThreadRepository, owner 2026-11-06). A deleted post leaves
     * no row for anyone. A follower or visitor sees posts only: every system line and every pin
     * notice is left out. The owner and admins (`admin`) keep the pins, timer lines and the channel's
     * own events, said without the admin's name ([channelNotice]).
     */
    fun shownMessages(messages: List<com.fariin.app.data.Message>, admin: Boolean): List<com.fariin.app.data.Message> {
        // A pin notice keeps the post's words from the moment of the pin; once that post is deleted
        // the notice says "a message" instead, so deleted words never stay readable.
        val deletedIds = messages.filter { it.deleted }.mapTo(HashSet()) { it.id }
        return messages.mapNotNull { m ->
            val note = com.fariin.app.data.PinRules.notice(m.text)
            val pin = note != null
            when {
                m.deleted -> null
                !admin && (m.isSystem || pin) -> null
                note != null && note.messageId in deletedIds -> m.copy(text = com.fariin.app.data.PinRules.markerText(note.messageId, "a message"))
                !m.isSystem || m.disappearSeconds != null || pin -> m
                else -> channelNotice(m.text)?.let { m.copy(text = it) }
            }
        }
    }

    /** The admin card's line (iOS ChannelAdminCard): "You created this channel · 9 followers". */
    fun adminCardLine(createdByMe: Boolean, shownCount: Int): String =
        (if (createdByMe) "You created this channel" else "You're an admin of this channel") + " · " +
            (if (shownCount == 1) "1 follower" else "$shownCount followers")

    /** Whole words only, so "remember" never turns into "refollower". */
    fun words(s: String): String = swaps.fold(s) { t, (from, to) ->
        t.replace(Regex("\\b" + Regex.escape(from) + "\\b"), to)
    }

    // ---- New channel ---------------------------------------------------------------------------

    const val NAME_MAX = 100
    const val ABOUT_MAX = 255
    const val LINK_MIN = 5

    /**
     * A first public link from the name (owner 2026-11-09, iOS GroupTypeView.suggestedLink): lower
     * case, spaces and dashes to _, only a-z 0-9 _, LINK_MIN..LINK_MAX long. Only a suggestion; the
     * live check still says whether it is free.
     */
    fun suggestLink(title: String, noun: String): String {
        val sb = StringBuilder()
        for (ch in title.lowercase()) {
            if (ch == ' ' || ch == '-') { if (!sb.endsWith("_")) sb.append('_') }
            else if (ch in 'a'..'z' || ch in '0'..'9' || ch == '_') sb.append(ch)
        }
        var out = sb.toString().trim('_')
        if (out.length < LINK_MIN) out = if (out.isEmpty()) noun else "${out}_$noun"
        return out.take(LINK_MAX)
    }
    const val LINK_MAX = 30

    /** GroupDirectory.localProblem: the shape rules the phone can judge; null = fine (or empty). */
    fun linkProblem(name: String): String? {
        if (name.isEmpty()) return null
        if (!Regex("^[A-Za-z0-9_]+$").matches(name)) return "Use only letters, numbers and _."
        if (name.length < LINK_MIN) return "A link must have at least $LINK_MIN characters."
        if (name.length > LINK_MAX) return "A link can have at most $LINK_MAX characters."
        if (name.first() == '_' || name.last() == '_') return "A link can't start or end with _."
        if (!name.first().isLetter()) return "A link must start with a letter."
        return null
    }

    fun typeFooter(public: Boolean): String = if (public) "Anyone can find this channel in search and follow it."
    else "People can follow only with an invite link, or when an admin adds them."

    fun protectFooter(on: Boolean): String = if (on) "Followers can't forward, copy, save or screenshot content from this channel."
    else "Followers can copy, save and forward content from this channel."

    /** GroupDirectory.safeId: one path segment, safe as a Firestore document id. */
    fun safeId(v: String): Boolean = v.isNotEmpty() && !v.contains("/") && v != "." && v != ".." &&
        !(v.startsWith("__") && v.endsWith("__")) && v.length <= 128
}

/** The channel half of a conversation document (Models.swift Conversation channel fields). */
data class ChannelInfo(
    val cid: String,
    val isChannel: Boolean = false,
    val title: String = "",
    val avatarUrl: String? = null,
    val description: String = "",
    val users: List<String> = emptyList(),
    val followerCount: Int = 0,
    val postCount: Int = 0,
    val deletedPostSeqs: List<Int> = emptyList(),
    val signPosts: Boolean = false,
    val reactMode: String = "all",
    val reactList: List<String> = emptyList(),
    val isPublic: Boolean = false,
    val username: String = "",
    val verified: Boolean = false,
    val label: String = "",
    val restricted: Boolean = false,
    val bannedUids: List<String> = emptyList(),
    val createdAtMillis: Double = 0.0,
    val names: Map<String, String> = emptyMap(),
    /**
     * Replies off for every post without its own switch (`repliesOff`, set by `setReplies`). OFF BY
     * DEFAULT (server, owner 2026-11-07): missing = off; only an explicit false turns them on.
     */
    val repliesOff: Boolean = true,
    /**
     * The Quotes switch off (`quotesOff`, set by `setChannelQuotes`): nobody can quote its posts, owner
     * and admins included (createQuote refuses everyone). Missing = on.
     */
    val quotesOff: Boolean = false,
) {

    val shownCount: Int get() = ChannelRules.subscriberCount(followerCount, users.size)

    companion object {
        fun from(cid: String, d: Map<String, Any?>): ChannelInfo {
            val rs = d["reactSet"] as? Map<*, *>
            val mode = (rs?.get("mode") as? String)?.takeIf { it in setOf("all", "some", "off") } ?: "all"
            val pub = (d["username"] as? String ?: "")
            val isPublic = d["type"] == "group" && pub.isNotEmpty() && (d["isPublic"] as? Boolean ?: false)
            val mod = d["mod"] as? Map<*, *>
            val label = (mod?.get("label") as? String)?.takeIf { it == "scam" || it == "fake" } ?: ""
            return ChannelInfo(
                cid = cid,
                isChannel = d["channel"] as? Boolean ?: false,
                title = d["title"] as? String ?: "",
                avatarUrl = (d["avatarUrl"] as? String)?.takeIf { it.isNotEmpty() },
                description = (d["desc"] as? String ?: "").trim(),
                users = (d["users"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
                followerCount = maxOf(0, (d["followerCount"] as? Number)?.toInt() ?: 0),
                postCount = maxOf(0, (d["postCount"] as? Number)?.toInt() ?: 0),
                deletedPostSeqs = (d["deletedPostSeqs"] as? List<*>)?.mapNotNull { (it as? Number)?.toInt() } ?: emptyList(),
                signPosts = d["signPosts"] as? Boolean ?: false,
                reactMode = mode,
                reactList = (rs?.get("list") as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
                isPublic = isPublic,
                username = if (isPublic) pub else "",
                verified = (d["verified"] as? Boolean ?: false) && label.isEmpty(),
                label = label,
                restricted = (d["restrictedBy"] as? Map<*, *>)?.isNotEmpty() == true,
                bannedUids = (d["bannedUids"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
                createdAtMillis = Conversation.millis(d["createdAt"]),
                names = (d["names"] as? Map<*, *>)?.entries?.mapNotNull { (k, v) -> if (k is String && v is String) k to v else null }?.toMap() ?: emptyMap(),
                repliesOff = d["repliesOff"] as? Boolean ?: true,
                // A private channel starts with Quotes off (iOS e5086b91b, owner 2026-11-08): no stored
                // choice reads as off; turning it on in Channel Settings sticks. The server reads it alike.
                quotesOff = d["quotesOff"] as? Boolean ?: !isPublic,
            )
        }

        /** The visitor's copy, from publicGroupPreview (the channel document stays members-only). */
        fun fromPreview(p: ChannelPreview) = ChannelInfo(
            cid = p.cid, isChannel = p.isChannel, title = p.title, avatarUrl = p.photoUrl, description = p.description,
            // The preview's count already holds the admins (users stays empty here).
            followerCount = p.shownCount, isPublic = p.username.isNotEmpty(), username = p.username,
            verified = p.verified, label = p.label, createdAtMillis = p.createdAt,
        )
    }
}

/** One row of Find Channels (ChannelService.Discovered, from discoverChannels). */
data class DiscoveredChannel(
    val cid: String,
    val handle: String,
    val title: String,
    val photoUrl: String?,
    val description: String,
    /** Followers plus admins, the one number every page shows. */
    val followerCount: Int,
    val admins: Int = 0,
    val verified: Boolean = false,
    val label: String = "",
    /** The official @fariin channel ("Fariin News"), pinned first by the server's ranking. */
    val official: Boolean = false,
) {
    companion object {
        fun from(d: Map<*, *>): DiscoveredChannel? {
            val cid = d["cid"] as? String ?: return null
            if (!ChannelRules.safeId(cid)) return null
            val photo = d["photoUrl"] as? String ?: ""
            return DiscoveredChannel(
                cid = cid, handle = d["handle"] as? String ?: "",
                title = (d["title"] as? String)?.takeIf { it.isNotBlank() } ?: "Channel",
                photoUrl = photo.ifEmpty { null }, description = d["description"] as? String ?: "",
                followerCount = (d["followerCount"] as? Number)?.toInt() ?: 0,
                admins = (d["admins"] as? Number)?.toInt() ?: 0,
                verified = d["verified"] as? Boolean ?: false, label = d["label"] as? String ?: "",
                official = d["official"] as? Boolean ?: false,
            )
        }

        /** The answer of discoverChannels: { country, channels: [...] }. */
        fun list(answer: Map<*, *>?): List<DiscoveredChannel> =
            (answer?.get("channels") as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.let(::from) } ?: emptyList()
    }
}

/** publicGroupPreview's answer for a channel (GroupDirectory.PublicGroupPreview). */
data class ChannelPreview(
    val cid: String,
    val username: String,
    val title: String,
    val photoUrl: String?,
    val description: String,
    val memberCount: Int,
    val followerCount: Int,
    val isMember: Boolean,
    val needsApproval: Boolean,
    val requested: Boolean,
    val verified: Boolean,
    val label: String,
    val isChannel: Boolean,
    val createdAt: Double,
) {
    /** ChannelCountBias.visitorCount with no bias: followers plus the admins. */
    val shownCount: Int get() = maxOf(0, followerCount + maxOf(0, memberCount - followerCount))

    companion object {
        fun from(d: Map<*, *>?): ChannelPreview? {
            d ?: return null
            val cid = d["cid"] as? String ?: return null
            if (!ChannelRules.safeId(cid)) return null
            val label = (d["label"] as? String)?.takeIf { it == "scam" || it == "fake" } ?: ""
            return ChannelPreview(
                cid = cid, username = d["username"] as? String ?: "",
                title = (d["title"] as? String)?.trim()?.ifEmpty { null } ?: if (d["channel"] == true) "Channel" else "Group",
                photoUrl = (d["photoUrl"] as? String)?.ifEmpty { null },
                description = (d["description"] as? String ?: "").trim(),
                memberCount = (d["memberCount"] as? Number)?.toInt() ?: 0,
                followerCount = (d["followerCount"] as? Number)?.toInt() ?: 0,
                isMember = d["isMember"] as? Boolean ?: false,
                needsApproval = d["needsApproval"] as? Boolean ?: false,
                requested = d["requested"] as? Boolean ?: false,
                verified = (d["verified"] as? Boolean ?: false) && label.isEmpty(),
                label = label, isChannel = d["channel"] as? Boolean ?: false,
                createdAt = (d["createdAt"] as? Number)?.toDouble() ?: 0.0,
            )
        }
    }
}
