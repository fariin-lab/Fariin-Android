package com.fariin.app.data

import com.fariin.app.crypto.Wire

/**
 * Pinned messages, read from Kulan-iOS: ChatService.addPinnedMessage / unpin (the shared
 * `pinnedMessageIds` array on conversations/{cid}, legacy single `pinnedMessageId`),
 * Limits.pinnedMessagesPerChat (3), ThreadView.togglePin / pinLabel / pinnedBarContent, the
 * "fariin-pinned:<id>|<label>" notice (Models.swift pinNotice) sent sealed like any text, and
 * Conversation.adminCan(.pinMessages) for who may pin or unpin in a group. Pure, so it is tested.
 */
object PinRules {
    const val MARKER = "fariin-pinned:"
    /** Limits.pinnedMessagesPerChat. */
    val MAX_PINS: Int get() = com.fariin.app.data.AppConfig.int(com.fariin.app.data.AppConfig.K.pinnedMessagesPerChat)

    data class Notice(val messageId: String, val label: String)

    /** Message.pinNotice: "fariin-pinned:<messageId>|<label>", label last (any characters). */
    fun notice(text: String): Notice? {
        if (!text.startsWith(MARKER)) return null
        val rest = text.substring(MARKER.length)
        val bar = rest.indexOf('|')
        if (bar <= 0 || bar == rest.length - 1) return null
        return Notice(rest.substring(0, bar), rest.substring(bar + 1))
    }

    fun markerText(messageId: String, label: String) = "$MARKER$messageId|$label"

    /** ThreadView.pinLabel: a quoted snippet (20 characters, then an ellipsis) or a media noun. */
    fun label(m: Message): String {
        when (m.mediaKind) {
            Message.MediaKind.PHOTO, Message.MediaKind.ALBUM -> return "a photo"
            Message.MediaKind.VIDEO -> return "a video"
            Message.MediaKind.VOICE -> return "a voice message"
            Message.MediaKind.FILE -> return "a file"
            else -> {}
        }
        if (m.type == "gif") return "a GIF"
        if (m.type == "image" || m.type == "album") return "a photo"
        val t = m.text.trim()
        if (t.isEmpty()) return "a message"
        val n = t.codePointCount(0, t.length)
        return if (n > 20) "\"${t.substring(0, t.offsetByCodePoints(0, 20))}…\"" else "\"$t\""
    }

    /** ThreadRepository: `pinnedMessageIds`, else the legacy single `pinnedMessageId` ("" = none). */
    fun pinnedIds(conv: Map<String, Any?>): List<String> {
        (conv["pinnedMessageIds"] as? List<*>)?.let { l -> return l.filterIsInstance<String>().filter { it.isNotEmpty() } }
        val one = conv["pinnedMessageId"] as? String ?: ""
        return if (one.isEmpty()) emptyList() else listOf(one)
    }

    /** Conversation.adminCan: the owner always; an admin with the right (a legacy admin with no
     *  `adminRights` entry holds every right but addAdmins). */
    fun adminCan(conv: Map<String, Any?>, me: String, right: String): Boolean {
        val owner = conv["createdBy"] as? String ?: ""
        if (owner.isNotEmpty() && owner == me) return true
        val admins = (conv["admins"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
        if (me !in admins) return false
        val granted = (conv["adminRights"] as? Map<*, *>)?.get(me) as? List<*> ?: return right != "addAdmins"
        return right in granted
    }

    /** `canPin` in ThreadView: anyone in a 1:1; in a group the Pin messages right. Unpin is the same. */
    fun canPin(conv: Map<String, Any?>, me: String, isGroup: Boolean): Boolean =
        !isGroup || adminCan(conv, me, "pinMessages")

    /** The menu offers Pin / Unpin on a delivered message that is not view-once, a notice or a call. */
    fun offers(m: Message): Boolean =
        m.sendState == null && !m.deleted && !m.viewOnce && !m.isSystem && !m.isCall && notice(m.text) == null &&
            m.text != Wire.LOCKED && m.text != Wire.NOT_READY

    /** The bar's second line (ThreadView pinnedBarContent). */
    fun snippet(m: Message?): String {
        if (m == null) return "Tap to view"
        return when {
            m.mediaKind == Message.MediaKind.ALBUM -> m.text.ifEmpty { "${m.album.size} Photos" }
            m.type == "gif" -> "GIF"
            m.mediaKind == Message.MediaKind.PHOTO -> "Photo"
            m.mediaKind == Message.MediaKind.VIEW_ONCE -> "View-once photo"
            m.mediaKind == Message.MediaKind.VIDEO -> "Video"
            m.mediaKind == Message.MediaKind.VOICE -> "Voice message"
            m.mediaKind == Message.MediaKind.FILE -> m.fileName ?: "File"
            else -> m.text
        }
    }

    /** Next pin the bar shows after a jump (it cycles, as iOS `pinIndex = (idx + 1) % count`). */
    fun nextIndex(index: Int, count: Int): Int = if (count <= 1) 0 else (index.coerceIn(0, count - 1) + 1) % count

    /** The See All sheet's rows (PinnedMessagesSheet): the pins found (loaded or fetched by id),
     *  tombstones left out, oldest first. */
    fun sheetOrder(ids: List<String>, lookup: (String) -> Message?): List<Message> =
        ids.mapNotNull(lookup).filter { !it.deleted }.distinctBy { it.id }.sortedBy { it.createdAtMillis }
}

/**
 * Select mode, read from Kulan-iOS ThreadView: selectionHasMine, selectionDeleteSplit,
 * selectionIsForwardable (every pick forwardable, at most Limits.maxForwardSelection = 32),
 * bulkDelete (mine for everyone, the rest hidden for me in a 1:1 only).
 */
object SelectRules {
    val MAX_FORWARD: Int get() = com.fariin.app.data.AppConfig.int(com.fariin.app.data.AppConfig.K.forwardSelectionMax)

    /** Rows that take a tick: a person's message or a tombstone; never a notice, call or pin line. */
    fun selectable(m: Message): Boolean = !m.isSystem && !m.isCall && PinRules.notice(m.text) == null

    /**
     * ThreadView.canDeleteNotice (owner 2026-10-29): a group notice that I, holding "Delete messages",
     * may remove for everyone; such a notice takes Select and Delete, and a tick in select mode.
     * Not one the owner wrote unless I am the owner (canDeleteForEveryone).
     */
    fun canDeleteNotice(m: Message, groupDoc: Map<String, Any?>, me: String): Boolean {
        if (!m.isSystem || m.deleted || (groupDoc["type"] as? String) != "group") return false
        val t = GroupAdmins.Team.from(groupDoc)
        if (!t.can(me, GroupAdmins.Right.DELETE_MESSAGES)) return false
        return MessageRules.canDeleteForEveryone(m, me, adminMayDelete = true,
            authorIsOwner = t.isOwner(m.authorId), iAmOwner = t.isOwner(me))
    }

    data class Split(val everyone: Int, val onlyMe: Int)

    fun split(picked: List<Message>, me: String, adminMayDelete: Boolean): Split {
        val n = picked.count { MessageRules.canDeleteForEveryone(it, me, adminMayDelete) }
        return Split(n, picked.size - n)
    }

    fun forwardable(picked: List<Message>): Boolean =
        picked.isNotEmpty() && picked.size <= MAX_FORWARD && picked.all(MessageRules::canForward)

    /** What Delete for Everyone (everyone = true) or Delete for Me does to each pick. */
    data class Plan(val forEveryone: List<Message>, val forMe: List<Message>)

    fun plan(picked: List<Message>, everyone: Boolean, me: String, isGroup: Boolean, adminMayDelete: Boolean): Plan {
        val all = mutableListOf<Message>()
        val mine = mutableListOf<Message>()
        for (m in picked) {
            if (everyone && MessageRules.canDeleteForEveryone(m, me, adminMayDelete)) all += m
            else if (!isGroup || m.sendState != null) mine += m   // a group has no Delete for Me
        }
        return Plan(all, mine)
    }

    /** Copy of several: the words of each in chat order, a blank line between. */
    fun copyText(picked: List<Message>): String =
        picked.sortedBy { it.createdAtMillis }.filter(MessageRules::canCopy).joinToString("\n\n") { it.text }
}
