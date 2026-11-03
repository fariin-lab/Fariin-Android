package com.fariin.app.data

/**
 * The tappable runs in a message's words (iOS BubbleText.body): web links, @usernames, email
 * addresses and phone numbers. Pure, so the rules are tested off the device.
 *
 * iOS finds links and emails with the system data detector and usernames with
 * `@([A-Za-z0-9_]{3,24})`. Here the same runs are found by hand: a link starts with http(s)://,
 * www. or our own fariin.com/; sentence punctuation after it is not part of it. Nothing starts
 * inside a word, so "abc@def.com" is an email and not the username "@def".
 */
object TextLinks {
    enum class Kind { WEB, HANDLE, EMAIL, PHONE }

    /** [start] inclusive, [end] exclusive, in the text's UTF-16 indices. [target]: the url to open,
     *  the username without "@", the address, or the number as typed. */
    data class Span(val start: Int, val end: Int, val kind: Kind, val target: String)

    private val WEB = Regex("""(?i)(?:https?://|www\.|fariin\.com/)[^\s<>"]+""")
    private val EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}""")
    private val HANDLE = Regex("""@([A-Za-z0-9_]{3,24})""")
    private val PHONE = Regex("""\+?\(?\d[\d ()-]{5,}\d""")

    /** Every run, in text order, none overlapping (links first, then emails, usernames, numbers). */
    fun find(text: String): List<Span> {
        if (text.isEmpty()) return emptyList()
        val out = mutableListOf<Span>()
        fun free(s: Int, e: Int) = out.none { s < it.end && it.start < e }

        for (m in WEB.findAll(text)) {
            val s = m.range.first
            if (s > 0 && wordy(text[s - 1])) continue
            val raw = trimTail(m.value)
            if (raw.isEmpty() || !hasHost(raw)) continue
            val url = if (raw.startsWith("http", ignoreCase = true)) raw else "https://$raw"
            out += Span(s, s + raw.length, Kind.WEB, url)
        }
        for (m in EMAIL.findAll(text)) {
            val s = m.range.first
            var e = m.range.last + 1
            if (s > 0 && (wordy(text[s - 1]) || text[s - 1] == '.')) continue
            while (e > s && text[e - 1] == '.') e--
            if (e < text.length && (text[e].isLetterOrDigit() || text[e] == '_' || text[e] == '@')) continue
            if (free(s, e)) out += Span(s, e, Kind.EMAIL, text.substring(s, e))
        }
        for (m in HANDLE.findAll(text)) {
            val s = m.range.first
            val e = m.range.last + 1
            if (s > 0 && (wordy(text[s - 1]) || text[s - 1] == '.' || text[s - 1] == '/')) continue
            if (e < text.length && (text[e].isLetterOrDigit() || text[e] == '_')) continue
            if (free(s, e)) out += Span(s, e, Kind.HANDLE, m.groupValues[1])
        }
        for (m in PHONE.findAll(text)) {
            val s = m.range.first
            var e = m.range.last + 1
            // An unclosed bracket at the end belongs to the sentence, not the number.
            while (e > s && !text[e - 1].isDigit()) e--
            if (s > 0 && (text[s - 1].isLetterOrDigit() || text[s - 1] in "_+.,:/-")) continue
            if (e < text.length && (text[e].isLetterOrDigit() || text[e] == '_' ||
                    ((text[e] == '.' || text[e] == ',' || text[e] == ':' || text[e] == '/') && e + 1 < text.length && text[e + 1].isDigit()))) continue
            val typed = text.substring(s, e)
            if (!phoneShaped(typed)) continue
            if (free(s, e)) out += Span(s, e, Kind.PHONE, typed)
        }
        return out.sortedBy { it.start }
    }

    /**
     * A number worth dialling: 7 to 15 digits (the longest international number), brackets
     * balanced, and a plain run without "+" needs 9 or more and at most three gaps, so dates
     * (2026-11-03), codes and lists of numbers stay text.
     */
    fun phoneShaped(typed: String): Boolean {
        val digits = typed.count { it.isDigit() }
        if (digits !in 7..15) return false
        if (typed.count { it == '(' } != typed.count { it == ')' }) return false
        if (typed.contains("  ") || typed.contains("--")) return false
        if (typed.startsWith("+")) return true
        // "10 20 30 40 50" is a list of numbers, not one to dial.
        return digits >= 9 && typed.count { it == ' ' || it == '-' } <= 3
    }

    /** Sentence punctuation after a link is not part of it; a closing bracket only when unopened
     *  (the same rule as LinkPreviews.firstUrl). */
    fun trimTail(url: String): String {
        var u = url
        while (u.isNotEmpty()) {
            val last = u.last()
            u = when {
                last in ".,;:!?'*" -> u.dropLast(1)
                last == ')' && u.count { it == '(' } < u.count { it == ')' } -> u.dropLast(1)
                last == ']' && u.count { it == '[' } < u.count { it == ']' } -> u.dropLast(1)
                else -> break
            }
        }
        return u
    }

    /** Something after the scheme with a dot in it ("https://" or "www." alone is not a link). */
    private fun hasHost(u: String): Boolean {
        val rest = u.substringAfter("://", u)
        val host = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        return host.contains('.') && !host.startsWith('.') && !host.endsWith('.')
    }

    private fun wordy(c: Char) = c.isLetterOrDigit() || c == '_' || c == '@'
}
