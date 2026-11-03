package com.fariin.app.data

import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * In-chat search, after Kulan-iOS ChatSearch + ThreadView.updateSearchMatches + InChatSearchList.
 * Messages are end-to-end encrypted, so the server can never search them: this runs on the phone,
 * over text already opened here, and nothing of it is saved.
 *
 *  - Normalising: case, accents and width folded (cafe finds café), punctuation and symbols become
 *    spaces.
 *  - Query: words with digits split out, plus the digit runs ("abc123" asks for "abc" and "123").
 *  - Match: EVERY term is the start of some word of the message ("hel wor" finds "Hello World").
 *  - Two characters at least, the newest 500 matches kept.
 */
object ChatSearch {
    /** iOS: matches are only computed at two characters and up. */
    const val MIN_QUERY = 2
    /** iOS: the newest 500 matches are kept (the result cap). */
    const val MAX_RESULTS = 500
    /** iOS MessageSearch.loadChat: the newest 1000 messages are read for the corpus. */
    const val CORPUS_LIMIT = 1000
    /** iOS goToCurrentMatch: up to 18 older pages are loaded to reach a match. */
    const val JUMP_PAGES = 18

    private val MARKS = Regex("\\p{M}+")

    /** One character folded the way iOS folds it: case, diacritics and width. May be empty or longer. */
    private fun foldChar(s: String): String =
        MARKS.replace(Normalizer.normalize(s, Normalizer.Form.NFKD), "").lowercase(Locale.ROOT)

    /** Fold + strip a string down to searchable form (the same for messages and queries). */
    fun normalize(text: String): String {
        val folded = foldChar(Normalizer.normalize(text, Normalizer.Form.NFC))
        val out = StringBuilder(folded.length)
        var i = 0
        while (i < folded.length) {
            val cp = folded.codePointAt(i)
            if (Character.isLetterOrDigit(cp)) out.appendCodePoint(cp) else out.append(' ')
            i += Character.charCount(cp)
        }
        return out.toString()
    }

    private fun words(norm: String) = norm.split(' ').filter { it.isNotEmpty() }

    /** A message's tokens: each word whole AND its letter / digit runs ("abc123" -> abc123, abc, 123). */
    fun tokens(text: String): List<String> {
        val out = ArrayList<String>()
        for (w in words(normalize(text))) {
            out.add(w)
            val runs = ArrayList<String>()
            val cur = StringBuilder()
            var curDigit: Boolean? = null
            for (ch in w) {
                val d = ch.isDigit()
                if (curDigit == d) cur.append(ch) else {
                    if (cur.isNotEmpty()) runs.add(cur.toString())
                    cur.setLength(0); cur.append(ch); curDigit = d
                }
            }
            if (cur.isNotEmpty()) runs.add(cur.toString())
            if (runs.size > 1) out.addAll(runs)
        }
        return out
    }

    /** Query terms: the words with digits split out, and the digit runs; deduped. */
    fun queryTerms(query: String): List<String> {
        val norm = normalize(query)
        if (norm.isBlank()) return emptyList()
        val terms = LinkedHashSet<String>()
        val ws = words(norm.map { if (it.isDigit()) ' ' else it }.joinToString(""))
        terms.addAll(ws)
        // Numbers alone ("555 123") stay one term, a number typed in pieces; numbers between words
        // ("room 5 floor 2") are each their own term.
        if (ws.isEmpty()) {
            val digits = norm.filter { it.isDigit() }
            if (digits.isNotEmpty()) terms.add(digits)
        } else {
            terms.addAll(words(norm.map { if (it.isDigit()) it else ' ' }.joinToString("")))
        }
        return terms.toList()
    }

    /** AND of prefix matches: every term starts at least one token. */
    fun matches(tokens: List<String>, terms: List<String>): Boolean =
        terms.isNotEmpty() && terms.all { t -> tokens.any { it.startsWith(t) } }

    fun matches(text: String, query: String): Boolean = matches(tokens(text), queryTerms(query))

    /** The text folded character by character, and where each folded character came from. */
    private class Folded(val text: String, val from: IntArray)

    private fun fold(text: String): Folded {
        val sb = StringBuilder()
        val map = ArrayList<Int>()
        var i = 0
        while (i < text.length) {
            val n = Character.charCount(text.codePointAt(i))
            val f = foldChar(text.substring(i, i + n))
            for (k in f.indices) map.add(i)
            sb.append(f)
            i += n
        }
        return Folded(sb.toString(), map.toIntArray())
    }

    /** Every place [term] occurs in [text], folded; ranges in [text]'s own indices (end exclusive). */
    private fun occurrences(text: String, f: Folded, term: String, wordStart: Boolean): List<IntRange> {
        val out = ArrayList<IntRange>()
        if (term.isEmpty()) return out
        var from = 0
        while (true) {
            val at = f.text.indexOf(term, from)
            if (at < 0) break
            val start = f.from[at]
            val lastSrc = f.from[at + term.length - 1]
            val end = lastSrc + Character.charCount(text.codePointAt(lastSrc))
            val before = if (start == 0) null else text.codePointBefore(start)
            if (!wordStart || before == null || !Character.isLetterOrDigit(before)) out.add(start until end)
            from = at + term.length
        }
        return out
    }

    /** iOS highlightRanges: where each query term starts a word in [text], for the bubble's yellow marks. */
    fun highlightRanges(text: String, query: String): List<IntRange> {
        if (query.trim().length < MIN_QUERY) return emptyList()
        val f = fold(text)
        return queryTerms(query).flatMap { occurrences(text, f, it, wordStart = true) }.sortedBy { it.first }
    }

    /** The list row's line: the text on one line, cut to start near the first match, and the matches. */
    data class Snippet(val text: String, val marks: List<IntRange>)

    /** iOS InChatSearchList.snippet: past 18 characters in, back up 12 and then to the word's start, "…" in front. */
    fun snippet(raw: String, terms: List<String>): Snippet {
        val text = raw.replace('\n', ' ')
        val f = fold(text)
        val first = terms.mapNotNull { t -> occurrences(text, f, t, wordStart = false).firstOrNull()?.first }.minOrNull()
        var start = 0
        var prefix = ""
        if (first != null && text.codePointCount(0, first) > 18) {
            var s = maxOf(0, offsetBack(text, first, 12))
            while (s > 0 && !Character.isWhitespace(text.codePointBefore(s))) s -= Character.charCount(text.codePointBefore(s))
            start = s
            prefix = "…"
        }
        val shown = prefix + text.substring(start)
        val sf = fold(shown)
        val marks = terms.filter { it.isNotEmpty() }.flatMap { occurrences(shown, sf, it, wordStart = false) }.sortedBy { it.first }
        return Snippet(shown, marks)
    }

    private fun offsetBack(text: String, index: Int, count: Int): Int {
        var i = index
        repeat(count) { if (i > 0) i -= Character.charCount(text.codePointBefore(i)) }
        return i
    }

    /** What the search runs over for one message, iOS quoteSafeLabel: never a raw marker. */
    fun searchText(m: Message): String? {
        if (m.deleted || m.viewOnce || m.text.isEmpty()) return null
        CardRules.previewWord(m.text)?.let { return it }
        if (m.text.startsWith(PinRules.MARKER)) return "📌 Pinned a message"
        m.poll?.let { return PollRules.safeText(it) }
        return m.text
    }

    /** One message in the corpus: its id, words, author and time. */
    data class Item(val id: String, val text: String, val authorId: String, val atMillis: Double,
                    val tokens: List<String> = tokens(text))

    /**
     * iOS updateSearchMatches: the corpus and the loaded window merged by id, matched, oldest to
     * newest, the newest [MAX_RESULTS] kept. Below [MIN_QUERY] characters nothing runs.
     */
    fun run(query: String, corpus: List<Item>, window: List<Item>): List<Item> {
        val q = query.trim()
        if (q.length < MIN_QUERY) return emptyList()
        val terms = queryTerms(q)
        if (terms.isEmpty()) return emptyList()
        val seen = HashSet<String>()
        val pool = ArrayList<Item>()
        for (m in corpus) if (seen.add(m.id)) pool.add(m)
        for (m in window) if (seen.add(m.id)) pool.add(m)
        return pool.filter { matches(it.tokens, terms) }.sortedBy { it.atMillis }.takeLast(MAX_RESULTS)
    }

    /**
     * iOS: the focused place is kept across a refined query, counted from the NEWEST match, and a
     * silent refresh (the chat changed) keeps the same message where it survives.
     */
    fun keepIndex(old: List<Item>, oldIndex: Int, new: List<Item>, sameMessage: Boolean): Int {
        if (new.isEmpty()) return 0
        val fromNewest = if (old.isEmpty()) 0 else maxOf(0, old.size - 1 - oldIndex)
        val clamped = maxOf(0, new.size - 1 - minOf(fromNewest, new.size - 1))
        if (sameMessage) {
            val id = old.getOrNull(oldIndex)?.id
            val i = new.indexOfFirst { it.id == id }
            if (i >= 0) return i
        }
        return clamped
    }

    /** The bar's words: "3 of 12" counted from the newest as 1, "12 messages" in list mode, "No results". */
    fun countText(count: Int, index: Int, asList: Boolean): String = when {
        count == 0 -> "No results"
        asList -> if (count == 1) "1 message" else "$count messages"
        else -> "${count - index} of $count"
    }

    /** The list row's date, the chat list's rule: the time today, the weekday within the week, else the date. */
    fun dateText(millis: Double, is24h: Boolean, nowMillis: Long = System.currentTimeMillis(),
                 zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        val at = Instant.ofEpochMilli(millis.toLong()).atZone(zone)
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val day: LocalDate = at.toLocalDate()
        val days = ChronoUnit.DAYS.between(day, today)
        return when {
            days == 0L -> DateTimeFormatter.ofPattern(if (is24h) "HH:mm" else "h:mm a", locale).format(at)
            days in 1..6 -> DateTimeFormatter.ofPattern("EEE", locale).format(at)
            else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale).format(at)
        }
    }
}
