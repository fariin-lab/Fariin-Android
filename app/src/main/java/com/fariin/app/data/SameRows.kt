package com.fariin.app.data

/**
 * Keeps the old object for every row whose data did not change, so a screen redraws only the rows
 * that really changed.
 *
 * Each answer from the database builds every row object again. Compose compares a row's object by
 * identity, so one changed chat (a read mark, a delivered time) rebuilt every chat row of the list,
 * also while the list was hidden under an open chat (Pixel 5 trace 2026-11-15: 15 to 35 ms frames,
 * one or two of them every time a chat was opened). With the old objects kept, only the changed
 * rows rebuild, and an answer that changed nothing gives back the very same list.
 */
object SameRows {
    /** [next] with each item replaced by its equal in [prev] (same [key]), and [prev] itself when nothing changed. */
    fun <T : Any, K> keep(prev: List<T>?, next: List<T>, key: (T) -> K): List<T> {
        if (prev == null || prev.isEmpty() || next.isEmpty()) return next
        if (prev === next) return prev
        val old = HashMap<K, T>(prev.size * 2)
        for (p in prev) old[key(p)] = p
        var allSame = prev.size == next.size
        val out = ArrayList<T>(next.size)
        for ((i, n) in next.withIndex()) {
            val o = old[key(n)]
            val kept = if (o != null && (o === n || o == n)) o else n
            if (allSame && prev[i] !== kept) allSame = false
            out.add(kept)
        }
        return if (allSame) prev else out
    }
}
