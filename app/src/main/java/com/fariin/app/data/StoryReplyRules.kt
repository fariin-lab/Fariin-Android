package com.fariin.app.data

/**
 * A story reaction in the chat, read from Kulan-iOS MessageRowModelBuilder (StoryReplyChrome) and
 * MessageRowLayout (the "story reaction sits on the card's corner" branch). Pure, so it is tested.
 */
object StoryReplyRules {
    /** The line above the story card (StoryReplyChrome.caption). */
    fun caption(isMe: Boolean, storyIsMine: Boolean, senderName: String): String = when {
        isMe -> if (storyIsMine) "You replied to your story" else "You replied to their story"
        else -> if (storyIsMine) "$senderName replied to your story" else "$senderName replied to their story"
    }

    /**
     * The emoji drawn big on the card's corner, or null when the message keeps the ordinary
     * layout. Only the react bar's emoji (`replyTo.isReaction`), with the story's picture, no
     * sender name above it, not forwarded, not deleted, and the words only emoji (iOS isJumbo).
     * A typed reply, even one emoji, is not a reaction.
     */
    fun reactionGlyph(m: Message, showsSenderName: Boolean): String? {
        val r = m.replyTo ?: return null
        if (!r.isStatus || !r.isReaction || r.storyThumb.isNullOrEmpty()) return null
        if (m.deleted || m.forwarded || showsSenderName || m.mediaKind != null) return null
        val t = m.text.trim()
        return t.takeIf { isEmojiOnly(it) }
    }

    /** Short and made of emoji only: no letters, digits or spaces (a keycap emoji is the one miss). */
    fun isEmojiOnly(t: String): Boolean {
        if (t.isEmpty() || t.length > 32) return false
        var i = 0
        var glyphs = 0
        while (i < t.length) {
            val cp = t.codePointAt(i)
            if (Character.isLetterOrDigit(cp) || Character.isWhitespace(cp)) return false
            // Count base glyphs, not joiners, variation selectors or skin tones.
            val joiner = cp == 0x200D || cp in 0xFE00..0xFE0F || cp in 0x1F3FB..0x1F3FF || cp in 0xE0020..0xE007F
            if (!joiner) glyphs++
            i += Character.charCount(cp)
        }
        return glyphs in 1..8
    }
}
