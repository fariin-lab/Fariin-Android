package com.fariin.app.data

/**
 * The chat list's forward arrow (shared contract with iOS, 2026-11-14): when a chat's newest
 * message is a forward, the summary its sender writes as the conversation's `lastMessage` starts
 * with [MARK] ("↪ "), sealed like the rest of the line where the line is sealed. A reader takes
 * the mark off and draws the arrow glyph before the thumbnail, icon or words. An old build just
 * shows "↪ Photo".
 */
object ForwardMark {
    const val MARK = "↪ "
    private const val BARE = "↪"

    /** [line] as a forward's summary: the mark in front, never twice. */
    fun mark(line: String): String = if (line.startsWith(MARK)) line else MARK + line

    /** [line] marked only when [forwarded]. */
    fun markIf(forwarded: Boolean, line: String): String = if (forwarded) mark(line) else line

    data class Split(val forwarded: Boolean, val text: String)

    /**
     * The mark read off a summary line: at the very start, or straight after a short "Name: "
     * sender prefix (kept, only the mark goes). A line that is the mark alone is a forward with no
     * words. Anything else comes back as it was.
     */
    fun split(line: String): Split {
        if (line.startsWith(MARK)) return Split(true, line.substring(MARK.length))
        if (line == BARE) return Split(true, "")
        val at = line.indexOf(": $BARE")
        val markAt = at + 2
        val whole = at > 0 && (line.startsWith(MARK, markAt) || line.length == markAt + BARE.length)
        if (whole && at <= PREFIX_MAX && line.lastIndexOf(": ", at - 1) < 0 && '\n' !in line.substring(0, at)) {
            val rest = line.substring(minOf(line.length, markAt + MARK.length))
            return Split(true, line.substring(0, markAt) + rest)
        }
        return Split(false, line)
    }

    /** [line] without the mark. */
    fun strip(line: String): String = split(line).text

    /** The longest "Name" a sender prefix may have before ": ". */
    private const val PREFIX_MAX = 64
}
