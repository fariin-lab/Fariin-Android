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

    /** The wire JSON for [text]'s bold runs; null when there is none (nothing is written). */
    fun serialize(spans: List<Span>, textLength: Int): String? {
        val n = normalize(spans, textLength)
        if (n.isEmpty()) return null
        val a = JSONArray()
        for (s in n) a.put(JSONArray().put(s.start).put(s.length).put(BOLD))
        return a.toString()
    }

    /**
     * Reads `fmt` (already opened). A run reaching outside the text is dropped whole (the text
     * is not what the runs were made for); a malformed value reads as no bold at all.
     */
    fun parse(json: String?, textLength: Int): List<Span> {
        if (json.isNullOrEmpty() || textLength <= 0) return emptyList()
        val a = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val out = ArrayList<Span>()
        for (i in 0 until a.length()) {
            val r = a.optJSONArray(i) ?: continue
            if (r.length() < 3 || r.optString(2) != BOLD) continue
            val loc = (r.opt(0) as? Number)?.toInt() ?: continue
            val len = (r.opt(1) as? Number)?.toInt() ?: continue
            if (loc < 0 || len <= 0 || loc.toLong() + len > textLength) continue
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

    /** As inclusive ranges, for the bubble's text drawing. */
    fun ranges(spans: List<Span>): List<IntRange> = spans.map { it.start until it.end }
}
