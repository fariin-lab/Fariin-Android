package com.fariin.app.data

/**
 * Opens the sealed last line of a chat for the list preview. The real one will come from the
 * crypto package (a port of Kulan-iOS/Kulan/Services/Crypto.swift); until it is wired, the
 * placeholder returns null and the row says "Message".
 */
fun interface MessageDecryptor {
    /** Plain text of [conv]'s last message as [me] may read it, or null when it cannot be opened. */
    fun previewText(conv: Conversation, me: String): String?
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

    /** The preview line under the name. */
    fun previewLine(c: Conversation, me: String, decryptor: MessageDecryptor, senderName: (String) -> String?): String {
        if (c.lastMessageCipher.isEmpty() && c.lastImageUrl.isNullOrEmpty()) return ""
        val text = when {
            c.isGroup && c.e2eeOff && c.lastIsPlainText -> c.lastMessageCipher
            else -> decryptor.previewText(c, me)
        } ?: if (!c.lastImageUrl.isNullOrEmpty()) "Photo" else "Message"
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
