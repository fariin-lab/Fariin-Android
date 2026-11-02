package com.fariin.app.data

import android.content.Context

/**
 * The missed-call badge on the Calls tab (iOS MainShell missedBadge / markCallsSeen): missed
 * incoming calls newer than the last call I saw there, minus the missed calls already seen by id
 * (a row whose date moves never counts twice). Kept on this phone only, like iOS.
 */
object CallsSeen {
    private const val PREFS = "fariin_calls"
    private const val SEEN_AT = "callsSeenAt"
    private const val SEEN_IDS = "callsSeenIds"
    private const val KEEP = 300

    data class Mark(val seenAtMs: Long, val ids: List<String>)

    fun load(c: Context): Mark {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var at = p.getLong(SEEN_AT, 0L)
        // A fresh install counts nothing from before it (iOS: a 0 mark badged every old miss).
        if (at == 0L) { at = System.currentTimeMillis(); p.edit().putLong(SEEN_AT, at).apply() }
        val ids = p.getString(SEEN_IDS, "").orEmpty().split(',').filter { it.isNotEmpty() }
        return Mark(at, ids)
    }

    fun count(calls: List<CallEntry>, me: String, mark: Mark, nowMs: Long = System.currentTimeMillis()): Int {
        val seen = mark.ids.toHashSet()
        return calls.count { it.missedIncoming(me, nowMs) && it.dateMs > mark.seenAtMs && it.id !in seen }
    }

    /** The Calls tab is on screen: every loaded missed call is seen, and the mark moves to the newest row. */
    fun seen(calls: List<CallEntry>, me: String, mark: Mark, nowMs: Long = System.currentTimeMillis()): Mark {
        val have = mark.ids.toHashSet()
        val ids = (mark.ids + calls.filter { it.missedIncoming(me, nowMs) && it.id !in have }.map { it.id }).takeLast(KEEP)
        val newest = calls.maxOfOrNull { it.dateMs } ?: 0L
        return Mark(maxOf(mark.seenAtMs, newest), ids)
    }

    fun save(c: Context, m: Mark) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(SEEN_AT, m.seenAtMs).putString(SEEN_IDS, m.ids.joinToString(",")).apply()
    }
}
