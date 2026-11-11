package com.fariin.app.data

/**
 * A CHANNEL'S HISTORY ON THE PHONE (CHANNEL-SYNC-SPEC.md 5-8, owner 2026-11-09; iOS
 * ChannelHistoryStore.swift on the channel-link branch, ported as it is):
 *
 * - every post this phone has seen, by id;
 * - deleted ids (tombstones are NOT rows: a deleted post never takes a visible slot);
 * - the parts of the history already read, as ranges of `createdAt`. What lies between two ranges is
 *   a HOLE, read later (createdAt-descending pages) until the view has enough visible posts;
 * - `lastChg`, the channel's change counter applied so far. Sync is "every message doc with
 *   chg > lastChg", applied one by one as add / edit / delete. The visible list is never replaced
 *   wholesale and a row is never dropped because some window did not contain it.
 *
 * No Firebase here: a pure value the tests drive by hand. [ChannelSync] is the only adapter.
 * Times are `createdAt` in microseconds; [PAST] / [FUTURE] stand for "the start" and "now".
 */
class ChannelHistory<T> {
    /** One post as it arrived: its id, `createdAt` (µs), deleted mark, `chg` (null until stamped). */
    data class Doc<T>(val id: String, val at: Long, val deleted: Boolean, val chg: Long?, val value: T)

    /** Every doc with `lo < createdAt <= hi` is known. */
    data class Range(var lo: Long, var hi: Long)
    data class Tomb(val chg: Long?, val atMs: Long)
    /** The next part to read: docs with `createdAt <= upper`, newest first. */
    data class Hole(val upper: Long, val floor: Long?)
    enum class Outcome { ADDED, EDITED, REMOVED, UNCHANGED, IGNORED }
    data class PageResult(var added: Int = 0, var edited: Int = 0, var removed: Int = 0, var dropped: Int = 0)

    private val posts = HashMap<String, Doc<T>>()
    private val postChg = HashMap<String, Long>()
    private val tombs = HashMap<String, Tomb>()
    private var ranges = ArrayList<Range>()
    /** Rows kept on screen across a "too long" gap or adopted at the start: not proven current. */
    private val unverified = HashSet<String>()
    /** The last change applied from the messages (`chg`). null: this channel has never synced. */
    var lastChg: Long? = null
    /** The last hard delete / clear applied from `changes`. */
    var lastGoneChg: Long? = null

    val isSynced: Boolean get() = lastChg != null
    val size: Int get() = posts.size
    fun post(id: String): Doc<T>? = posts[id]
    fun values(): List<T> = posts.values.map { it.value }
    fun rangesCopy(): List<Range> = ranges.map { it.copy() }
    fun isTomb(id: String): Boolean = tombs.containsKey(id)

    /** Oldest first (ties by id). */
    fun sorted(): List<Doc<T>> = posts.values.sortedWith(compareBy<Doc<T>>({ it.at }, { it.id }))

    /** The hole right under the newest known range (or the newest page itself when nothing is known). */
    val topHole: Hole?
        get() {
            val top = ranges.lastOrNull() ?: return Hole(FUTURE, null)
            if (top.lo <= PAST) return null
            val floor = if (ranges.size >= 2) ranges[ranges.size - 2].hi else null
            return Hole(top.lo, floor)
        }

    val reachedStart: Boolean get() = topHole == null

    /** Posts in the newest contiguous block the view would show (rows below a hole do not count). */
    fun visibleCount(isVisible: (Doc<T>) -> Boolean): Int {
        val floor = ranges.lastOrNull()?.lo ?: FUTURE
        return posts.values.count { it.at >= floor && isVisible(it) }
    }

    fun needsFill(want: Int, isVisible: (Doc<T>) -> Boolean): Boolean = topHole != null && visibleCount(isVisible) < want

    // ---- Changes ----------------------------------------------------------------------------

    /** One change (chg listener, newest-posts listener): never older over newer. */
    fun applyChange(doc: Doc<T>, nowMs: Long): Outcome = apply(doc, nowMs, fromPage = false)

    /**
     * `fromPage`: a hole read, whose span is marked read right after. A listener's change for a post
     * not held, outside every read range, is NOT added (it would sit next to the newest block with an
     * unmarked gap between); the hole read brings it later. A delete still goes to the tombstones.
     */
    private fun apply(doc: Doc<T>, nowMs: Long, fromPage: Boolean): Outcome {
        val id = doc.id
        if (doc.deleted) return remove(id, doc.chg, nowMs)
        if (!fromPage && posts[id] == null && !isKnown(doc.at)) return Outcome.IGNORED
        tombs[id]?.let { t ->
            val tc = t.chg; val dc = doc.chg
            if (tc == null || dc == null || dc <= tc || nowMs - t.atMs <= TOMB_GRACE_MS) return Outcome.IGNORED
            tombs.remove(id)
        }
        postChg[id]?.let { known -> val dc = doc.chg; if (dc == null || dc < known) return Outcome.IGNORED }
        val old = posts[id]
        posts[id] = doc
        doc.chg?.let { postChg[id] = it }
        unverified.remove(id)
        if (old == null) return Outcome.ADDED
        return if (old.value == doc.value) Outcome.UNCHANGED else Outcome.EDITED
    }

    /** The post is gone (deleted for everyone, or hard-deleted). Final: no older copy brings it back. */
    fun remove(id: String, chg: Long?, nowMs: Long): Outcome {
        val had = posts.remove(id) != null
        postChg.remove(id)
        unverified.remove(id)
        val prev = tombs[id]
        tombs[id] = Tomb(listOfNotNull(chg, prev?.chg).maxOrNull(), prev?.atMs ?: nowMs)
        if (tombs.size > TOMB_CAP + 500) pruneTombs()
        return if (had) Outcome.REMOVED else Outcome.UNCHANGED
    }

    private fun pruneTombs() {
        val keep = tombs.entries.sortedByDescending { it.value.atMs }.take(TOMB_CAP).associate { it.key to it.value }
        tombs.clear(); tombs.putAll(keep)
    }

    // ---- Reading history --------------------------------------------------------------------

    /**
     * A page read newest-first from `upper` (inclusive). Every doc is applied (tombstones included),
     * and when the SERVER answered, the whole span it covers is marked read: from the page's oldest
     * doc up to `upper`, or down to the start for a short page. A deleted post never stops the fill.
     */
    fun applyPage(docs: List<Doc<T>>, upper: Long, full: Boolean, fromServer: Boolean, nowMs: Long): PageResult {
        val r = PageResult()
        for (d in docs) when (apply(d, nowMs, fromPage = true)) {
            Outcome.ADDED -> r.added++
            Outcome.EDITED -> r.edited++
            Outcome.REMOVED -> r.removed++
            else -> {}
        }
        if (!fromServer) return r   // a cache answer may have holes of its own
        var lo = PAST
        if (full) {
            val oldest = docs.minOfOrNull { it.at } ?: upper
            // A full page of one single stamp would mark nothing and be read again for ever.
            lo = if (oldest < upper) oldest else upper - 1
        }
        if (unverified.isNotEmpty()) {
            val pageIds = docs.mapTo(HashSet()) { it.id }
            val gone = unverified.filter { id ->
                val p = posts[id]
                p != null && id !in pageIds && p.at > lo && p.at <= upper
            }
            for (id in gone) { remove(id, null, nowMs); r.dropped++ }
        }
        markKnown(lo, upper)
        return r
    }

    fun isKnown(at: Long): Boolean = ranges.any { it.lo < at && at <= it.hi }

    /** Clear Messages on the server (`changes` record `{clear: true, before}`): posts at or before go. */
    fun clearBefore(beforeMicros: Long, nowMs: Long): List<Doc<T>> {
        val gone = posts.values.filter { it.at <= beforeMicros }
        for (p in gone) remove(p.id, null, nowMs)
        return gone
    }

    /** Marks `lo < createdAt <= hi` read, merging with any range it touches. */
    fun markKnown(lo: Long, hi: Long) {
        if (lo >= hi) return
        val merged = Range(lo, hi)
        val out = ArrayList<Range>()
        for (r in ranges) {
            if (r.hi < merged.lo || r.lo > merged.hi) out.add(r)
            else { merged.lo = minOf(merged.lo, r.lo); merged.hi = maxOf(merged.hi, r.hi) }
        }
        out.add(merged)
        out.sortBy { it.lo }
        ranges = out
    }

    /** More changes waited than one listener answer holds: everything held stays, unproven; the
     *  newest page read next goes on top, and what lies between is a hole. */
    fun resetForTooLong() {
        unverified.clear(); unverified.addAll(posts.keys)
        ranges = ArrayList()
    }

    /** Rows paged in before this store knew the channel's change counter: shown, not yet proven. */
    fun adoptUnverified(extra: List<Doc<T>>) {
        for (p in extra) if (!p.deleted && posts[p.id] == null && tombs[p.id] == null) {
            posts[p.id] = p
            unverified.add(p.id)
        }
    }

    /**
     * Keep the newest `n` visible posts (and the hidden ones among them). Older rows go, and the
     * ranges are cut at the same place, so what went is a hole again, read back when needed.
     */
    fun bound(keepVisible: Int, isVisible: (Doc<T>) -> Boolean): Int {
        var shown = 0
        var cut: Long? = null
        for (p in sorted().asReversed()) {
            if (shown >= keepVisible) { cut = p.at; break }
            if (isVisible(p)) shown++
        }
        val c = cut ?: return 0
        val gone = posts.values.filter { it.at <= c }.map { it.id }
        for (id in gone) { posts.remove(id); postChg.remove(id); unverified.remove(id) }
        ranges = ArrayList(ranges.mapNotNull { r -> if (r.hi <= c) null else Range(maxOf(r.lo, c), r.hi) })
        return gone.size
    }

    companion object {
        const val PAST = Long.MIN_VALUE
        const val FUTURE = Long.MAX_VALUE
        /** A deleted id keeps out everything this long: a page read before the delete can still land. */
        const val TOMB_GRACE_MS = 60_000L
        const val TOMB_CAP = 4000
    }
}
