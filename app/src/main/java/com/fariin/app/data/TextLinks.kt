package com.fariin.app.data

/**
 * The tappable runs in a message's words (iOS BubbleText.body): web links, @usernames, email
 * addresses and phone numbers. Pure, so the rules are tested off the device.
 *
 * iOS finds links and emails with the system data detector and usernames with
 * `@([A-Za-z0-9_]{3,24})`. Here the same runs are found by hand: a link starts with http(s)://,
 * www. or our own fariin.com/, or is a bare domain with a real ending ("x.so/abc", opened as
 * https://); sentence punctuation after it is not part of it. Nothing starts
 * inside a word, so "abc@def.com" is an email and not the username "@def".
 */
object TextLinks {
    enum class Kind { WEB, HANDLE, EMAIL, PHONE }

    /** [start] inclusive, [end] exclusive, in the text's UTF-16 indices. [target]: the url to open,
     *  the username without "@", the address, or the number as typed. */
    data class Span(val start: Int, val end: Int, val kind: Kind, val target: String)

    private val WEB = Regex("""(?i)(?:https?://|www\.|fariin\.com/)[^\s<>"]+""")
    /**
     * A domain typed without a scheme ("fariin.com", "x.so/abc"), as the system link detector finds it
     * (iOS 9332362b). Only real endings count, so "photo.jpg", "v1.2" and "e.g." stay words.
     */
    // Starts only where a word starts and allows at most 10 parts of at most 63 letters, so its work
    // stays small on any text: unbounded, "a.a.a.a..." took ~180 ms per 4,000 characters to draw.
    private val BARE = Regex("""(?i)(?<![\w.@-])(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.){1,10}([a-z]{2,24})\b(?:[/?#][^\s<>"]*)?""")
    private val TLDS = setOf(
        "com", "net", "org", "io", "so", "co", "app", "dev", "me", "info", "biz", "ai", "gg", "ly", "tv", "fm",
        "xyz", "site", "online", "store", "shop", "news", "blog", "link", "live", "tech", "cloud", "page", "edu",
        "gov", "mil", "int", "us", "uk", "ca", "au", "nz", "de", "fr", "es", "it", "nl", "be", "se", "no", "dk",
        "fi", "ch", "at", "ie", "pt", "pl", "ru", "ua", "tr", "in", "pk", "bd", "jp", "kr", "cn", "hk", "tw", "sg",
        "my", "id", "ph", "th", "vn", "ae", "sa", "qa", "kw", "om", "eg", "ma", "ng", "ke", "et", "dj", "za", "br",
        "mx", "ar", "cl", "eu", "to", "ws", "cc",
    )
    // Every repeat is bounded: an unbounded domain part made the regex engine recurse once per
    // part, and a message like "x@a.a.a.a..." overflowed the stack and crashed the chat.
    private val EMAIL = Regex("""(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9-]{1,63}(?:\.[A-Za-z0-9-]{1,63}){0,8}\.[A-Za-z]{2,24}""")
    private val HANDLE = Regex("""@([A-Za-z0-9_]{3,24})""")
    /** Off: iOS links no phone numbers. */
    const val LINK_PHONES = false
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
        // Emails before bare domains, so "abc@def.com" stays one email.
        // An email needs an "@": without one the pattern is not run at all (it rescans from every
        // letter, which on a long run like "a.a.a..." cost a frame).
        val hasAt = text.indexOf('@') >= 0
        val emails = if (hasAt) EMAIL.findAll(text).map { it.range }.toList() else emptyList()
        for (m in BARE.findAll(text)) {
            val s = m.range.first
            if (s > 0 && (wordy(text[s - 1]) || text[s - 1] in "./-:")) continue
            if (m.groupValues[1].lowercase() !in TLDS) continue
            val raw = trimTail(m.value)
            if (raw.isEmpty() || !hasHost(raw)) continue
            val e = s + raw.length
            if (emails.any { s <= it.last && it.first < e }) continue
            if (free(s, e)) out += Span(s, e, Kind.WEB, "https://$raw")
        }
        if (hasAt) for (m in EMAIL.findAll(text)) {
            val s = m.range.first
            var e = m.range.last + 1
            if (s > 0 && (wordy(text[s - 1]) || text[s - 1] == '.')) continue
            while (e > s && text[e - 1] == '.') e--
            if (e < text.length && (text[e].isLetterOrDigit() || text[e] == '_' || text[e] == '@')) continue
            if (free(s, e)) out += Span(s, e, Kind.EMAIL, text.substring(s, e))
        }
        if (hasAt) for (m in HANDLE.findAll(text)) {
            val s = m.range.first
            val e = m.range.last + 1
            if (s > 0 && (wordy(text[s - 1]) || text[s - 1] == '.' || text[s - 1] == '/')) continue
            if (e < text.length && (text[e].isLetterOrDigit() || text[e] == '_')) continue
            if (free(s, e)) out += Span(s, e, Kind.HANDLE, m.groupValues[1])
        }
        // iOS does not link phone numbers (its detector is web, @handle and email only): kept off so
        // the two apps draw the same message the same way. The rule stays for a later decision.
        if (LINK_PHONES) for (m in PHONE.findAll(text)) {
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
