package com.fariin.app.data

/**
 * How long one typed message may be (iOS Limits.messageMaxChars / messageBubbleChars, owner
 * 2026-10-29): the field takes up to 6,400 characters, and a send longer than one bubble's 2,500 goes
 * out as several bubbles, in order. config/limits has no keys for these, so they are iOS's numbers.
 *
 * The reason is the chat screen, not the database: every line of a bubble is measured and drawn on
 * both phones, so one pasted wall of text would freeze the chat for whoever opens it. Nothing typed is
 * ever cut at the send; the field simply stops taking more.
 *
 * Lengths are UTF-16 units, as iOS NSString counts them in the field; a cut never lands inside an
 * emoji or other composed character.
 */
object MessageLength {
    const val MAX_CHARS = 6_400
    const val BUBBLE_CHARS = 2_500

    /**
     * iOS shouldChangeTextIn: the field's next text when [next] (with the cursor at [cursor]) would
     * pass [max]. A deletion, or anything that fits, stands as it is. A paste that overflows is
     * trimmed to the room there was (the end of what came in, before the cursor, is dropped), rather
     * than refused whole. Returns the text and the cursor; null = keep [old] unchanged.
     */
    fun fit(old: String, next: String, cursor: Int, max: Int = MAX_CHARS): Pair<String, Int>? {
        if (next.length <= max || next.length <= old.length) return next to cursor
        if (old.length >= max) return null
        val overflow = next.length - max
        val end = cursor.coerceIn(0, next.length)
        // What came in sits just before the cursor; the part that does not fit is its tail.
        var start = (end - overflow).coerceAtLeast(0)
        start = boundaryAtOrBefore(next, start)
        if (start >= end) return null
        val out = next.removeRange(start, end)
        return if (out.length > max || out == old) null else out to start
    }

    /**
     * iOS ThreadView.splitForBubbles: text longer than one bubble, cut in reading order at the last
     * line break in the window, else the last space past its middle, else at a character boundary.
     * Each piece is trimmed; empty pieces are dropped.
     */
    fun splitForBubbles(text: String, max: Int = BUBBLE_CHARS): List<String> {
        val out = mutableListOf<String>()
        var rest = text
        while (rest.length > max) {
            val edge = boundaryAtOrBefore(rest, max).takeIf { it > 0 } ?: max
            val window = rest.substring(0, edge)
            val nl = window.lastIndexOf('\n')
            val sp = window.lastIndexOf(' ')
            val cut = when {
                nl > max / 2 -> nl
                sp > max / 2 -> sp
                else -> edge
            }
            val piece = rest.substring(0, cut).trim()
            if (piece.isNotEmpty()) out += piece
            rest = rest.substring(cut).trimStart()
        }
        rest.trim().takeIf { it.isNotEmpty() }?.let { out += it }
        return out
    }

    /** The nearest character boundary at or before [i] (never inside a surrogate pair or an emoji). */
    private fun boundaryAtOrBefore(s: String, i: Int): Int {
        if (i <= 0) return 0
        if (i >= s.length) return s.length
        val bi = java.text.BreakIterator.getCharacterInstance()
        bi.setText(s)
        if (bi.isBoundary(i)) return i
        val b = bi.preceding(i)
        return if (b == java.text.BreakIterator.DONE) 0 else b
    }
}
