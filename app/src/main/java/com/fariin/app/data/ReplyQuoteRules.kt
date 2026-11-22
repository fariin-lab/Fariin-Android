package com.fariin.app.data

/**
 * The line a reply seals about the message it quotes (iOS replyQuoteText). It travels in
 * `replyTo.text`, so both apps write the same words. The kind goes first: a photo is quoted as
 * "📷 Photo" even with a caption, and a view-once photo never gives away its caption.
 */
object ReplyQuoteRules {
    fun quoteText(m: Message): String = when {
        m.isCall -> if (m.callVideo) "📹 Video call" else "📞 Voice call"
        m.type == "album" -> "📷 Photos"
        m.type == "image" -> if (m.viewOnce) "View-once photo" else "📷 Photo"
        m.type == "video" -> "🎥 Video"
        m.type == "audio" -> "🎤 Voice message"
        m.type == "file" -> "📄 " + (m.fileName ?: "Document")
        m.type == "gif" -> "GIF"
        m.type == "sticker" -> StickerGifRules.stickerLabel(m.stickerEmoji)
        // Text, a card or a poll: its safe words, never a raw marker (iOS safeText).
        else -> CardRules.previewWord(m.text) ?: m.bodyText
    }

    /** The reply to send for [m]: its id, author and quote line (capped as before). */
    fun ref(m: Message): Message.ReplyRef = Message.ReplyRef(m.id, m.authorId, quoteText(m).take(200))

    private val GLYPHS = listOf("📹 ", "📞 ", "📷 ", "🎥 ", "🎤 ", "📄 ")

    /** A quote line drawn next to our own small icon loses the emoji it travelled with. */
    fun withoutGlyph(s: String): String = GLYPHS.firstOrNull { s.startsWith(it) }?.let { s.removePrefix(it) } ?: s
}
