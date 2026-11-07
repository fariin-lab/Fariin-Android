package com.fariin.app.data

import com.fariin.app.crypto.Wire
import kotlin.random.Random

/**
 * The rules behind the chat's long-press menu, reactions, typing, delivered marks, usernames and
 * message requests, each one read from Kulan-iOS (ThreadView canEdit / canDeleteHere /
 * canDeleteForEveryone / canForward, MessageRowModelBuilder.reactionChips, ChatService.setTyping,
 * DeliveryReceipts.mark, ChatService.handleShapeProblem, MessageRequests). Pure, so it is tested.
 */
object MessageRules {
    /** Limits.editWindowSeconds (15 min). The server allows 1020 s, a small clock margin. */
    const val EDIT_WINDOW_MS = 15 * 60 * 1000.0
    /**
     * Limits.deleteForEveryoneSeconds (48 h). iOS defines it but never applies it (no rule and no
     * menu check uses it), so Delete for Everyone has no time limit on iOS. Kept the same here.
     */
    const val DELETE_FOR_EVERYONE_MS = 48 * 3600 * 1000.0

    /** QuickReaction.bar, in this order always (owner order on iOS). */
    val QUICK_REACTIONS = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

    private fun unopened(m: Message) = m.text == Wire.LOCKED || m.text == Wire.NOT_READY

    /** ThreadView.canEdit: mine, not forwarded, has words, delivered, inside the window. */
    fun canEdit(m: Message, me: String, nowMs: Double): Boolean =
        m.authorId == me && !m.forwarded && !m.deleted && m.type != "audio" && !m.isCall && !m.isSystem &&
            !m.viewOnce && m.text.isNotEmpty() && !unopened(m) && m.sendState == null &&
            nowMs - m.createdAtMillis < EDIT_WINDOW_MS

    /** ThreadView.canDeleteForEveryone. [adminMayDelete]: I hold the group "Delete messages" right. */
    fun canDeleteForEveryone(m: Message, me: String, adminMayDelete: Boolean = false, authorIsOwner: Boolean = false,
                             iAmOwner: Boolean = false): Boolean {
        if (m.sendState != null || m.deleted || m.isCall) return false
        if (m.authorId == me) return true
        if (!adminMayDelete) return false
        return iAmOwner || !authorIsOwner
    }

    /** ThreadView.canDeleteHere: a 1:1 always (Delete for Me); a group only what goes for everyone. */
    fun canDeleteHere(m: Message, me: String, isGroup: Boolean, adminMayDelete: Boolean = false): Boolean =
        !isGroup || m.sendState != null || canDeleteForEveryone(m, me, adminMayDelete)

    /** ThreadView.canForward. */
    fun canForward(m: Message): Boolean =
        m.sendState == null && !m.isCall && !m.isSystem && !m.deleted && !m.viewOnce && !unopened(m)

    /** A service line in a channel: a pin notice ("fariin-pinned:…", shown as "You pinned …") or type "system". */
    fun isServiceLine(m: Message): Boolean = m.isSystem || m.text.startsWith(PinRules.MARKER)

    /** Quote: where Forward is, never on a service line (the server refuses them too). */
    fun canQuote(m: Message): Boolean = canForward(m) && !isServiceLine(m)

    fun canCopy(m: Message): Boolean = !m.deleted && !m.viewOnce && m.text.isNotEmpty() && !unopened(m)

    /** Reply needs a server copy (a clientId-only send cannot be quoted yet). */
    fun canReply(m: Message): Boolean = m.sendState == null && !m.deleted && !m.isSystem

    /** iOS Message.isStoryReaction: an emoji sent from a story's react bar takes no reply. */
    fun isStoryReaction(m: Message): Boolean = m.replyTo?.let { it.isStatus && it.isReaction } == true

    /**
     * Reply offered here, by menu row and by swipe (iOS: `!m.isStoryReaction && mayReplyHere`).
     * [mayReply] is false in a group where only admins send and I am not one.
     */
    fun canReplyHere(m: Message, mayReply: Boolean): Boolean = mayReply && canReply(m) && !isStoryReaction(m)

    /** Info: my own delivered message in a group (iOS). */
    fun canInfo(m: Message, me: String, isGroup: Boolean): Boolean = isGroup && m.authorId == me && m.sendState == null

    data class Chip(val emoji: String, val count: Int, val mine: Boolean, val faces: List<String>)

    /**
     * reactionChips: one chip per emoji, up to three reactor faces (sorted uids) else a count,
     * most picked first, ties by emoji descending. A [hidden] one (an uploaded emoji taken off the
     * admin list) is left out entirely, as if nobody had picked it.
     */
    fun reactionChips(reactions: Map<String, String>, me: String, hidden: (String) -> Boolean = { false }): List<Chip> {
        if (reactions.isEmpty()) return emptyList()
        val mine = reactions[me]
        val byEmoji = LinkedHashMap<String, MutableList<String>>()
        for ((uid, e) in reactions) if (e.isNotEmpty() && !hidden(e)) byEmoji.getOrPut(e) { mutableListOf() }.add(uid)
        return byEmoji.map { (e, uids) ->
            Chip(e, uids.size, mine == e, if (uids.size > 3) emptyList() else uids.sorted())
        }.sortedWith(compareByDescending<Chip> { it.count }.thenByDescending { it.emoji })
    }

    /**
     * iOS ReactorsSheet tabs: (emoji, count), most picked first, ties by emoji descending (the
     * pills' own order). "All" is drawn in front of them by the sheet.
     */
    fun reactorTabs(reactions: Map<String, String>): List<Pair<String, Int>> =
        reactions.values.filter { it.isNotEmpty() }.groupingBy { it }.eachCount().toList()
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenByDescending { it.first })

    data class Reactor(val uid: String, val emoji: String, val name: String)

    /**
     * iOS ReactorsSheet rows: the reactors of [emoji] (null = all), sorted by shown name, case
     * blind, ties by uid. A tab whose last reactor took theirs back reads as All ([activeTab]).
     */
    fun reactorRows(reactions: Map<String, String>, emoji: String?, name: (String) -> String): List<Reactor> =
        reactions.filter { (_, e) -> e.isNotEmpty() && (emoji == null || e == emoji) }
            .map { (uid, e) -> Reactor(uid, e, name(uid)) }
            .sortedWith(compareBy<Reactor, String>(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.uid })

    fun activeTab(reactions: Map<String, String>, picked: String?): String? =
        picked?.takeIf { it in reactions.values }

    /**
     * iOS jumbomojiCount: words made of emoji only (spaces ignored) count their emoji, 1 to 5;
     * anything else, or six or more, is 0 and keeps its bubble. A joined family, a flag, a skin
     * tone or a keycap counts as one.
     */
    fun jumbomojiCount(text: String): Int {
        var count = 0
        var afterJoiner = false
        var flagOpen = false
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            val next = if (i < text.length) text.codePointAt(i) else -1
            when {
                Character.isWhitespace(cp) -> { afterJoiner = false; flagOpen = false }
                cp == 0x200D -> afterJoiner = true
                cp in 0xFE00..0xFE0F || cp == 0x20E3 || cp in 0x1F3FB..0x1F3FF || cp in 0xE0020..0xE007F -> {}
                cp in 0x1F1E6..0x1F1FF -> {   // two regional letters make one flag
                    if (flagOpen) flagOpen = false else { count++; flagOpen = true }
                    afterJoiner = false
                }
                isEmojiBase(cp) || (cp.toChar() in "0123456789#*" && (next == 0xFE0F || next == 0x20E3)) -> {
                    if (!afterJoiner) count++
                    afterJoiner = false; flagOpen = false
                }
                else -> return 0
            }
            if (count > 5) return 0
        }
        return count
    }

    private fun isEmojiBase(cp: Int): Boolean =
        cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF ||
            cp in 0x2194..0x2199 || cp == 0x21A9 || cp == 0x21AA || cp == 0x231A || cp == 0x231B || cp == 0x2328 ||
            cp == 0x23CF || cp in 0x23E9..0x23F3 || cp in 0x23F8..0x23FA || cp == 0x25AA || cp == 0x25AB ||
            cp == 0x25B6 || cp == 0x25C0 || cp in 0x25FB..0x25FE || cp == 0x2934 || cp == 0x2935 ||
            cp in 0x2B05..0x2B07 || cp == 0x2B1B || cp == 0x2B1C || cp == 0x2B50 || cp == 0x2B55 ||
            cp == 0x00A9 || cp == 0x00AE || cp == 0x203C || cp == 0x2049 || cp == 0x2122 || cp == 0x2139 ||
            cp == 0x24C2 || cp == 0x3030 || cp == 0x303D || cp == 0x3297 || cp == 0x3299

    /** iOS jumbomojiPointSize: the body's 17 times 3.5, 3, 2.75, 2.5, 2.25 for 1 to 5 emoji. */
    fun jumbomojiScale(count: Int): Float = when (count) {
        1 -> 3.5f; 2 -> 3.0f; 3 -> 2.75f; 4 -> 2.5f; else -> 2.25f
    }

    /** Toggle: the same emoji again takes my reaction off (null), else it becomes mine. */
    fun nextReaction(current: String?, picked: String): String? = if (current == picked) null else picked

    /** The typing value iOS writes: "text-<seconds>" on, false off (a changing value re-arms 15 s). */
    fun typingValue(on: Boolean, nowMs: Long): Any = if (on) "text-${nowMs / 1000}" else false

    /** Receivers accept Bool true (older builds) or a "text" string; "audio" means recording. */
    fun isTyping(v: Any?): Boolean = v == true || (v as? String)?.startsWith("text") == true
    fun isRecording(v: Any?): Boolean = (v as? String)?.startsWith("audio") == true
    const val TYPING_EXPIRY_MS = 15_000L

    /** The value iOS setRecording writes while the mic is live: "audio-<seconds>", re-sent every 10 s. */
    fun recordingValue(on: Boolean, nowMs: Long): Any = if (on) "audio-${nowMs / 1000}" else false
    const val RECORDING_REFRESH_MS = 10_000L

    /**
     * The header's live line from `typing` (iOS presenceSubtitle): typing first, then recording.
     * A 1:1 says "typing…" / "recording…"; a group names one person or counts them ("N people
     * typing…"). None at all with my own Typing Indicators off (it is mutual), or when [live] is
     * false (the values went stale).
     */
    fun liveLine(typing: Map<*, *>, me: String, isGroup: Boolean, typingPref: Boolean, live: Boolean,
                 name: (String) -> String?): String? {
        if (!typingPref || !live) return null
        val typers = typing.filter { (k, v) -> k != me && isTyping(v) }.keys.mapNotNull { it as? String }.sorted()
        val recorders = typing.filter { (k, v) -> k != me && isRecording(v) }.keys.mapNotNull { it as? String }.sorted()
        fun group(uids: List<String>, verb: String) =
            if (uids.size == 1) "${name(uids[0]) ?: "Someone"} is $verb…" else "${uids.size} people $verb…"
        return when {
            typers.isNotEmpty() -> if (isGroup) group(typers, "typing") else "typing…"
            recorders.isNotEmpty() -> if (isGroup) group(recorders, "recording") else "recording…"
            else -> null
        }
    }

    /**
     * DeliveryReceipts.mark: the 1:1 chats whose newest message is theirs and newer than what I
     * confirmed, as cid to the updatedAt to write into `delivered.<me>`. At most 400.
     */
    fun deliveredWrites(convs: List<Conversation>, me: String, written: Map<String, Double>,
                        blocked: Set<String> = emptySet()): Map<String, Double> {
        val out = LinkedHashMap<String, Double>()
        for (c in convs) {
            if (c.isGroup || c.lastSender.isEmpty() || c.lastSender == me || c.updatedAtMillis <= 0) continue
            if (c.updatedAtMillis <= (c.delivered[me] ?: 0.0) || c.updatedAtMillis <= (written[c.id] ?: 0.0)) continue
            if (c.blockedBy[me] == true || c.otherUid(me) in blocked) continue
            out[c.id] = c.updatedAtMillis
            if (out.size >= 400) break
        }
        return out
    }

    /** The unread divider sits above the oldest of my [unread] newest messages from others. */
    fun unreadDividerId(messages: List<Message>, me: String, unread: Int): String? {
        if (unread <= 0) return null
        var left = unread
        for (m in messages.asReversed()) {
            if (m.authorId == me || m.isSystem) continue
            left--
            if (left == 0) return m.rowId
        }
        return messages.firstOrNull { it.authorId != me && !it.isSystem }?.rowId
    }
}

/** ChatService handle rules and the username page's suggestions (SettingsView.suggest). */
object Usernames {
    const val MIN = 5
    const val MAX = 30
    private val allowed = (('a'..'z') + ('A'..'Z') + ('0'..'9') + '_').toSet()

    /** sanitizeHandle: drop what is not allowed as it is typed, case kept, at most 30. */
    fun sanitize(raw: String): String = raw.filter { it in allowed }.take(MAX)

    /** handleShapeProblem: null while still short or when the shape is fine. */
    fun shapeProblem(h: String): String? = when {
        h.length < MIN -> null
        h.length > MAX -> "Usernames are at most $MAX characters."
        h.startsWith("_") || h.endsWith("_") -> "Usernames can't start or end with _."
        h.contains("__") -> "Usernames can't contain two _ in a row."
        h.none { it.isLetter() } -> "Usernames need at least one letter."
        else -> null
    }

    fun isValid(h: String): Boolean =
        h.lowercase() == "v" || (h.length in MIN..MAX && h.all { it in allowed })

    /** The lookup key iOS uses for `usernames/{h}`, or null for text that can never be a handle. */
    fun lookupKey(typed: String): String? {
        var h = typed.trim().lowercase()
        if (h.startsWith("@")) h = h.drop(1)
        if (h.isEmpty() || h.contains('/') || h == "." || h == ".." || (h.startsWith("__") && h.endsWith("__"))) return null
        if (h.toByteArray().size > 128) return null
        return h
    }

    /** Up to four candidates near a taken name ("base_NN" or "baseNNN"), each a valid shape. */
    fun suggestionPool(value: String, rnd: Random = Random.Default): List<String> {
        val base = value.take(MAX - 4)
        val pool = mutableListOf<String>()
        repeat(12) {
            if (pool.size >= 4) return@repeat
            val pick = if (rnd.nextBoolean()) "${base}_${rnd.nextInt(10, 100)}" else "$base${rnd.nextInt(100, 1000)}"
            if (shapeProblem(pick) == null && pick.length >= MIN && pick !in pool) pool.add(pick)
        }
        return pool
    }

    fun profileLink(h: String) = "https://fariin.com/u/$h"
}

/** MessageRequests: who may write and how much before the other side accepts. */
object Requests {
    /** MessageRequests.firstMessageLimit: characters in a first message to a stranger. */
    const val FIRST_MESSAGE_LIMIT = 150
    const val DECLINE_COOLDOWN_DAYS = 7

    enum class Stance { OPEN, AWAITING_REPLY, INCOMING, FIRST_MESSAGE }

    /** MessageRequests.rawStance (the MsgPolicy switches are server-side admin settings, on). */
    fun stance(c: Conversation, me: String, unfriendedBy: String = ""): Stance {
        if (c.reqOff && !c.isGroup) return Stance.OPEN
        if (c.isGroup || c.startedBy.isEmpty() || c.accepted) return Stance.OPEN
        if (c.lastSender.isEmpty()) {
            return if (c.startedBy != me && unfriendedBy == me) Stance.AWAITING_REPLY else Stance.FIRST_MESSAGE
        }
        return if (c.lastSender == me) Stance.AWAITING_REPLY else Stance.INCOMING
    }

    fun firstMessageTooLong(text: String): Boolean = text.trim().length > FIRST_MESSAGE_LIMIT
}
