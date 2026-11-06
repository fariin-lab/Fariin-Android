package com.fariin.app.data

import java.text.Normalizer

/**
 * "Did you mean ...?" (owner 2026-10-31, iOS SearchSuggest): the known name a mistyped search was
 * most likely aiming at. Edit distance on the folded text (no case, no accents), against the whole
 * name and each of its words, one slip allowed for a short search and two for a longer one.
 * Nothing when the search is shorter than three letters or already appears in a name.
 */
object SearchSuggest {
    fun closest(query: String, names: List<String>): String? {
        val q = fold(query)
        if (q.length < 3) return null
        val limit = if (q.length <= 4) 1 else 2
        var best: String? = null
        var bestD = 99
        for (name in names.distinct()) {
            if (name.isEmpty()) continue
            val n = fold(name)
            if (n.contains(q)) return null
            val parts = listOf(n) + n.split(' ').filter { it.isNotEmpty() }
            val d = parts.minOf { distance(q, it.take(q.length + limit)) }
            if (d <= limit && d < bestD) { best = name; bestD = d }
        }
        return best
    }

    private fun fold(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase().trim()

    private fun distance(a: String, b: String): Int {
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }
}
