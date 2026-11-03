package com.fariin.app.data

/**
 * Opens the sealed last line of a chat for the list preview. The real one will come from the
 * crypto package (a port of Kulan-iOS/Kulan/Services/Crypto.swift); until it is wired, the
 * placeholder returns null and the row says "Message".
 */
fun interface MessageDecryptor {
    /** Plain text of [conv]'s last message as [me] may read it, or null when it cannot be opened. */
    fun previewText(conv: Conversation, me: String): String?

    /** Any other sealed line of [conv] (a reaction's emoji, the words it was on), written by [author]. */
    fun open(conv: Conversation, sealed: String, author: String): String? = null
}

object NoDecryptor : MessageDecryptor {
    override fun previewText(conv: Conversation, me: String): String? = null
}

/** What the chat list shows and in what order. Mirrors `ChatsView.visible` in MainShell.swift. */
object ChatList {

    /** A pending request from somebody else lives on the Message Requests page, not in the list. */
    fun isIncomingRequest(c: Conversation, me: String): Boolean {
        if (c.isGroup || c.reqOff) return false
        if (c.startedBy.isEmpty() || c.accepted) return false
        // TODO: iOS also asks MsgPolicy (the person's "message requests on" switch).
        return c.lastSender.isNotEmpty() && c.lastSender != me
    }

    fun visible(all: List<Conversation>, me: String): List<Conversation> =
        all.asSequence()
            .filter { !it.isCleared(me) && !it.isArchived(me) }
            .filter { !isIncomingRequest(it, me) }
            // A 1:1 chat merely opened, with nothing ever said, stays out until something real
            // happens. Groups always list. TODO: drafts also keep a chat listed on iOS.
            .filter { it.isGroup || it.lastMessageCipher.isNotEmpty() || it.hasUnreadMark(me) || it.isPinned(me) }
            .sortedWith { a, b ->
                val ap = a.isPinned(me)
                val bp = b.isPinned(me)
                when {
                    ap != bp -> if (ap) -1 else 1
                    ap && bp && a.pinRank(me) != b.pinRank(me) -> b.pinRank(me).compareTo(a.pinRank(me))
                    else -> b.listSortDate(me).compareTo(a.listSortDate(me))
                }
            }
            .toList()

    /** The small icon iOS draws before some preview lines (MainShell previewRow): the line's
     *  words after any "Name: " sender prefix decide it. */
    enum class PreviewIcon { PIN, CONTACT, LOCATION }
    fun previewIcon(line: String): PreviewIcon? = when (line.substringAfter(": ", line)) {
        "Pinned a message" -> PreviewIcon.PIN
        "Contact" -> PreviewIcon.CONTACT
        "Location" -> PreviewIcon.LOCATION
        else -> null
    }

    /**
     * "Reacted 😂 to "Photo"" when the newest event is a reaction (iOS MainShell reactionPreview):
     * mine "You reacted 😂", a group member's "Omar reacted 😂", on my message without its words
     * "Reacted 😂 to your message". Null when there is no fresh reaction or it cannot be opened.
     */
    fun reactionPreview(c: Conversation, me: String, decryptor: MessageDecryptor, senderName: (String) -> String?): String? {
        if (!c.freshReaction(me)) return null
        val emoji = decryptor.open(c, c.lastReactionEnc ?: return null, c.lastReactionBy)?.takeIf { it.isNotEmpty() && it != "…" && it != "🔒" } ?: return null
        val words = c.lastReactionToEnc?.let { decryptor.open(c, it, c.lastReactionBy) }
        val on = if (words.isNullOrEmpty() || words == "…" || words == "🔒") "" else " to \"$words\""
        if (c.lastReactionBy == me) return "You reacted $emoji$on"
        if (c.isGroup) {
            val n = senderName(c.lastReactionBy) ?: c.names[c.lastReactionBy] ?: "Someone"
            return "${n.split(" ").first()} reacted $emoji$on"
        }
        if (on.isNotEmpty()) return "Reacted $emoji$on"
        return if (c.lastReactionToAuthor == me) "Reacted $emoji to your message" else "Reacted $emoji"
    }

    /** The preview line under the name. */
    fun previewLine(c: Conversation, me: String, decryptor: MessageDecryptor, senderName: (String) -> String?): String {
        reactionPreview(c, me, decryptor, senderName)?.let { return it }
        if (c.lastMessageCipher.isEmpty() && c.lastImageUrl.isNullOrEmpty()) return ""
        val text = when {
            c.isGroup && c.e2eeOff && c.lastIsPlainText -> c.lastMessageCipher
            else -> decryptor.previewText(c, me)
        }.let { t -> if (t != null && t.startsWith(PinRules.MARKER)) "Pinned a message" else t }   // iOS MainShell pin notice
            .let { t -> if (t != null && PollRules.isPoll(t)) PollRules.PREVIEW else t }   // iOS MainShell poll row
            .let { t -> CardRules.previewWord(t) ?: t }   // iOS MainShell contact / location rows
            ?: if (!c.lastImageUrl.isNullOrEmpty()) "Photo" else "Message"
        // In a group the line starts with who said it, as on iOS (never for my own lines), first name
        // only. System notices ("X created the group") stay bare (iOS lastSenderPrefix): they are
        // plain words, not a sealed "enc" line, a media marker or a member's plain-text line.
        if (c.isGroup && !c.isChannel && c.lastSender.isNotEmpty() && c.lastSender != me) {
            val raw = c.lastMessageCipher
            val spoken = raw.startsWith("enc") || c.lastIsPlainText || raw.isEmpty() ||
                !raw.first().isLetterOrDigit()
            val who = senderName(c.lastSender) ?: c.names[c.lastSender]
            if (spoken && !who.isNullOrEmpty()) return "${who.split(" ").first()}: $text"
        }
        return text
    }
}
