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

    fun canCopy(m: Message): Boolean = !m.deleted && !m.viewOnce && m.text.isNotEmpty() && !unopened(m)

    /** Reply needs a server copy (a clientId-only send cannot be quoted yet). */
    fun canReply(m: Message): Boolean = m.sendState == null && !m.deleted && !m.isSystem

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

    /** Toggle: the same emoji again takes my reaction off (null), else it becomes mine. */
    fun nextReaction(current: String?, picked: String): String? = if (current == picked) null else picked

    /** The typing value iOS writes: "text-<seconds>" on, false off (a changing value re-arms 15 s). */
    fun typingValue(on: Boolean, nowMs: Long): Any = if (on) "text-${nowMs / 1000}" else false

    /** Receivers accept Bool true (older builds) or a "text" string; "audio" means recording. */
    fun isTyping(v: Any?): Boolean = v == true || (v as? String)?.startsWith("text") == true
    fun isRecording(v: Any?): Boolean = (v as? String)?.startsWith("audio") == true
    const val TYPING_EXPIRY_MS = 15_000L

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
