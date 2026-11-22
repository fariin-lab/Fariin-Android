package com.fariin.app.data

import org.json.JSONArray

/**
 * Bold runs in a channel post (iOS TextFormat). On the wire the message's `fmt` is a JSON string
 * `[[location,length,"b"],...]`: UTF-16 code units of the text exactly as sent (trimmed), sorted,
 * merged, clamped to the text. "b" is the only kind so far; anything else is skipped. Sealed with
 * the same group seal as `text` when the text is sealed, plain in a plain channel.
 */
object TextFormat {
    /** A bold run: [start] inclusive, [end] exclusive, in UTF-16 code units. */
    data class Span(val start: Int, val end: Int) {
        val length: Int get() = end - start
    }

    private const val BOLD = "b"
    private const val LINK = "l"

    /**
     * Format > Link (iOS f09ef437c, 2026-11-16): the author's own address on [start, end), UTF-16
     * units of the sent text, like a bold run. On the wire `[location, length, "l", url]`, in the
     * same array as the bold runs.
     */
    data class Link(val start: Int, val end: Int, val url: String)

    /**
     * The address as the wire may carry it (iOS TextLink.cleanURL): http/https with a dotted host
     * (or localhost), mailto:, tel:, fariin:, kulan:; a bare "fariin.com" or "site.com:8080" gets
     * https:// in front, a bare "a@b.com" gets mailto:. At most 2048 characters, no spaces, no
     * invisible characters (they hide what the address is). Anything else (javascript:, file:, ...) is null.
     */
    fun cleanUrl(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty() || t.length > 2048 || t.any { it.isWhitespace() }) return null
        if (t.any { it in "\u200B\u200C\u200D\u2060\uFEFF\u202E" }) return null
        val low = t.lowercase()
        val scheme = Regex("""^[a-z][a-z0-9+.-]*:""").find(low)?.value?.dropLast(1)
        for (p in listOf("fariin", "kulan")) if (scheme == p) return t
        if (scheme == "tel") return if (t.length > 4) t else null
        val s = when {
            scheme == "mailto" -> t
            scheme == "http" || scheme == "https" -> t
            '@' in t && '/' !in t && ':' !in t -> "mailto:$t"
            // "site.com:8080" is a host and a port, not a scheme.
            scheme != null && !Regex("""^[a-z0-9.-]+:[0-9]""").containsMatchIn(low) -> return null
            else -> "https://$t"
        }
        if (s.startsWith("mailto:", ignoreCase = true)) {
            val addr = s.substring(7).substringBefore('?')
            return if (addr.contains('@') && !addr.startsWith('@') && !addr.endsWith('@')) s else null
        }
        val withScheme = s
        val host = withScheme.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
            .substringAfterLast('@').substringBefore(':')
        if (host.lowercase() == "localhost") return if (withScheme.length > 2048) null else withScheme
        if (!host.contains('.') || host.startsWith('.') || host.endsWith('.')) return null
        return if (withScheme.length > 2048) null else withScheme
    }

    /** The links in `fmt`, in text order: bad addresses, runs outside the text and later overlaps dropped. */
    fun parseLinks(json: String?, textLength: Int): List<Link> {
        if (json.isNullOrEmpty() || textLength <= 0 || !json.contains("\"l\"") || json.length > 40_000) return emptyList()
        val a = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val found = ArrayList<Link>()
        for (i in 0 until minOf(a.length(), 1000)) {
            val r = a.optJSONArray(i) ?: continue
            if (r.length() < 4 || r.optString(2) != LINK) continue
            val loc = (r.opt(0) as? Number)?.toInt() ?: continue
            val len = (r.opt(1) as? Number)?.toInt() ?: continue
            // iOS normalizedLinks: a link running past the words is cut at their end.
            if (loc < 0 || len <= 0 || loc >= textLength) continue
            val url = cleanUrl(r.optString(3)) ?: continue
            found.add(Link(loc, loc + minOf(len, textLength), url))
        }
        return normalizeLinks(found, textLength)
    }

    /** Clamped, sorted, empty runs dropped; of two that overlap, the earlier one stays. */
    fun normalizeLinks(links: List<Link>, textLength: Int): List<Link> {
        val out = ArrayList<Link>()
        for (l in links.sortedBy { it.start }) {
            val a = l.start.coerceIn(0, textLength)
            val b = l.end.coerceIn(0, textLength)
            if (b <= a) continue
            if (out.any { a < it.end && it.start < b }) continue
            out.add(Link(a, b, l.url))
        }
        return out
    }

    /** The words are the address itself ("fariin.com" linked to https://fariin.com/): no question before opening. */
    fun wordsAreAddress(words: String, url: String): Boolean {
        fun bare(x: String) = x.trim().lowercase().removePrefix("https://").removePrefix("http://")
            .removePrefix("mailto:").removePrefix("tel:").removePrefix("www.").trimEnd('/')
        return bare(words) == bare(url)
    }

    /** Sorted, clamped to [0, textLength], empty runs dropped, touching or overlapping runs joined. */
    fun normalize(spans: List<Span>, textLength: Int): List<Span> {
        if (textLength <= 0) return emptyList()
        val clamped = spans.mapNotNull { s ->
            val a = s.start.coerceIn(0, textLength)
            val b = s.end.coerceIn(0, textLength)
            if (b > a) Span(a, b) else null
        }.sortedBy { it.start }
        val out = ArrayList<Span>(clamped.size)
        for (s in clamped) {
            val last = out.lastOrNull()
            if (last != null && s.start <= last.end) out[out.size - 1] = Span(last.start, maxOf(last.end, s.end))
            else out.add(s)
        }
        return out
    }

    /** The wire JSON for [text]'s bold runs and links; null when there is neither (nothing is written). */
    fun serialize(spans: List<Span>, textLength: Int, links: List<Link> = emptyList()): String? {
        val n = normalize(spans, textLength)
        val l = normalizeLinks(links, textLength)
        if (n.isEmpty() && l.isEmpty()) return null
        val a = JSONArray()
        for (s in n) a.put(JSONArray().put(s.start).put(s.length).put(BOLD))
        for (k in l) a.put(JSONArray().put(k.start).put(k.end - k.start).put(LINK).put(k.url))
        return a.toString()
    }

    /**
     * Reads `fmt` (already opened), as iOS TextFormat.parse: a run starting or as long as more than
     * the text is dropped, one that only runs past its end is cut there; a malformed value reads as
     * no bold at all.
     */
    fun parse(json: String?, textLength: Int): List<Span> {
        if (json.isNullOrEmpty() || textLength <= 0 || json.length > 40_000) return emptyList()
        val a = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val out = ArrayList<Span>()
        for (i in 0 until minOf(a.length(), 1000)) {
            val r = a.optJSONArray(i) ?: continue
            if (r.length() < 3 || r.optString(2) != BOLD) continue
            val loc = (r.opt(0) as? Number)?.toInt() ?: continue
            val len = (r.opt(1) as? Number)?.toInt() ?: continue
            if (loc < 0 || len <= 0 || loc > textLength || len > textLength) continue
            out.add(Span(loc, loc + len))
        }
        return normalize(out, textLength)
    }

    /** Every code unit of [start, end) is bold already. */
    fun isAllBold(spans: List<Span>, start: Int, end: Int): Boolean {
        if (end <= start) return false
        return spans.any { it.start <= start && it.end >= end }
    }

    /** The Bold menu item: a selection that is all bold goes regular, anything else goes bold. */
    fun toggle(spans: List<Span>, start: Int, end: Int, textLength: Int): List<Span> {
        val a = minOf(start, end).coerceIn(0, textLength)
        val b = maxOf(start, end).coerceIn(0, textLength)
        if (b <= a) return normalize(spans, textLength)
        val n = normalize(spans, textLength)
        return if (isAllBold(n, a, b)) remove(n, a, b, textLength) else normalize(n + Span(a, b), textLength)
    }

    private fun remove(spans: List<Span>, a: Int, b: Int, textLength: Int): List<Span> {
        val out = ArrayList<Span>()
        for (s in spans) {
            if (s.end <= a || s.start >= b) { out.add(s); continue }
            if (s.start < a) out.add(Span(s.start, a))
            if (s.end > b) out.add(Span(b, s.end))
        }
        return normalize(out, textLength)
    }

    /**
     * The composer's runs kept in step with a change from [old] to [new]: the one changed stretch
     * (common start and end kept) is cut out, and what was typed or pasted there starts regular,
     * even right after or inside a bold run.
     */
    fun adjust(spans: List<Span>, old: String, new: String): List<Span> {
        if (spans.isEmpty() || old == new) return normalize(spans, new.length)
        var p = 0
        val maxP = minOf(old.length, new.length)
        while (p < maxP && old[p] == new[p]) p++
        var s = 0
        while (s < old.length - p && s < new.length - p && old[old.length - 1 - s] == new[new.length - 1 - s]) s++
        val removedEnd = old.length - s          // old [p, removedEnd) replaced
        val delta = new.length - old.length
        val out = ArrayList<Span>()
        for (sp in spans) {
            if (sp.start < p) out.add(Span(sp.start, minOf(sp.end, p)))
            if (sp.end > removedEnd) out.add(Span(maxOf(sp.start, removedEnd) + delta, sp.end + delta))
        }
        // Two halves of one run either side of a deletion join again (nothing new between them).
        return normalize(out, new.length)
    }

    /** The runs for [raw].trim(): moved by the trimmed-off front, clamped to the trimmed text. */
    fun forTrimmed(spans: List<Span>, raw: String): List<Span> {
        val lead = raw.length - raw.trimStart().length
        val t = raw.trim()
        return normalize(spans.map { Span(it.start - lead, it.end - lead) }, t.length)
    }

    /**
     * The composer's links kept in step with a change from [old] to [new] (iOS): typing inside a link
     * grows it, typing right after it (or right before it) does not; a link whose words are all
     * deleted goes.
     */
    fun adjustLinks(links: List<Link>, old: String, new: String): List<Link> {
        if (links.isEmpty() || old == new) return normalizeLinks(links, new.length)
        var p = 0
        val maxP = minOf(old.length, new.length)
        while (p < maxP && old[p] == new[p]) p++
        var s = 0
        while (s < old.length - p && s < new.length - p && old[old.length - 1 - s] == new[new.length - 1 - s]) s++
        val removedEnd = old.length - s
        val delta = new.length - old.length
        val ins = new.length - s - p
        val out = ArrayList<Link>()
        for (l in links) {
            when {
                l.end <= p -> out.add(l)
                l.start >= removedEnd -> out.add(Link(l.start + delta, l.end + delta, l.url))
                else -> {
                    val a = if (l.start < p) l.start else p + ins
                    val b = if (l.end > removedEnd) l.end + delta else p
                    if (b > a) out.add(Link(a, b, l.url))
                }
            }
        }
        return normalizeLinks(out, new.length)
    }

    /** The link on [start, end) set to [url] (null removes the links there); others it touches give way. */
    fun setLink(links: List<Link>, start: Int, end: Int, url: String?, textLength: Int): List<Link> {
        val a = minOf(start, end).coerceIn(0, textLength)
        val b = maxOf(start, end).coerceIn(0, textLength)
        val kept = links.filter { it.end <= a || it.start >= b }
        return normalizeLinks(if (url == null || b <= a) kept else kept + Link(a, b, url), textLength)
    }

    /** The link a selection sits in (Edit Link), if one covers all of it. */
    fun linkAt(links: List<Link>, start: Int, end: Int): Link? =
        links.firstOrNull { it.start <= minOf(start, end) && it.end >= maxOf(start, end) && (it.end > it.start) }

    /** The links for [raw].trim(), as [forTrimmed] does for bold. */
    fun linksForTrimmed(links: List<Link>, raw: String): List<Link> {
        val lead = raw.length - raw.trimStart().length
        return normalizeLinks(links.map { Link(it.start - lead, it.end - lead, it.url) }, raw.trim().length)
    }

    /**
     * A long text sent as several bubbles (iOS ThreadView.send, 2026-11-19): each part keeps the bold
     * and links that fall inside it, measured from the part's own start. [fmt] is for [whole]; the
     * [parts] are found in [whole] in order. A part not found, or with nothing on it, gets null.
     */
    fun forParts(fmt: String?, whole: String, parts: List<String>): List<String?> {
        val bold = parse(fmt, whole.length)
        val links = parseLinks(fmt, whole.length)
        if (bold.isEmpty() && links.isEmpty()) return parts.map { null }
        var cursor = 0
        return parts.map { p ->
            val lo = whole.indexOf(p, cursor)
            if (lo < 0) return@map null
            val hi = lo + p.length
            cursor = hi
            val pb = bold.mapNotNull { s ->
                val a = maxOf(lo, s.start); val b = minOf(hi, s.end)
                if (b > a) Span(a - lo, b - lo) else null
            }
            val pl = links.mapNotNull { k ->
                val a = maxOf(lo, k.start); val b = minOf(hi, k.end)
                if (b > a) Link(a - lo, b - lo, k.url) else null
            }
            serialize(pb, p.length, pl)
        }
    }

    /** As inclusive ranges, for the bubble's text drawing. */
    fun ranges(spans: List<Span>): List<IntRange> = spans.map { it.start until it.end }
}
