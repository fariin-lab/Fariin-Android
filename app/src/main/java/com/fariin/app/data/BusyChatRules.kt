package com.fariin.app.data

import com.fariin.app.perf.PerfClass
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * A busy group (5 to 20 messages a second) must not freeze a cheap phone. The rules here are pure,
 * so they are unit tested: how often the open chat may redraw, how often my read mark may be
 * written, when arrival animations are skipped, and the row geometry worked out only where the
 * list changed (speed work 2026-11-15).
 */
object BusyChatRules {
    /** At most one redraw of the open chat per this many ms from listener answers. */
    fun publishIntervalMs(cls: PerfClass?): Long = if (cls == PerfClass.LOW) 250L else 150L

    /** My read mark (badge to 0, then lastRead) is written at most once per this many ms per open
     *  chat, plus once when I leave it or the app goes to the background. Same rule on iOS. */
    const val READ_MARK_INTERVAL_MS = 2_500L

    /** On a LOW phone, more than [BURST_ROWS] new rows inside [BURST_WINDOW_MS] is a burst. */
    const val BURST_ROWS = 3
    const val BURST_WINDOW_MS = 1_000L
}

/**
 * Coalesces redraws: the first ask runs at once, later asks inside [intervalMs] of the last run
 * fold into one run at the end of the interval. An urgent ask (the first answer, my own send)
 * always runs at once. Not thread safe: one thread asks.
 */
class PublishGate(private val intervalMs: Long) {
    private var lastRun = Long.MIN_VALUE / 2
    private var scheduled = false

    /** 0 = run now; above 0 = schedule a run after that many ms; null = a run is already scheduled. */
    fun ask(nowMs: Long, urgent: Boolean = false): Long? {
        if (urgent) return 0L
        if (scheduled) return null
        val wait = lastRun + intervalMs - nowMs
        if (wait <= 0) return 0L
        scheduled = true
        return wait
    }

    /** A run happened (now or scheduled). */
    fun ran(nowMs: Long) {
        lastRun = nowMs
        scheduled = false
    }
}

/**
 * The read mark while a chat is open: one write at once, then at most one per [intervalMs]; every
 * ask in between folds into one trailing write. [takeOwed] on leaving says whether a write is
 * still owed (it is then done at once). Thread safe.
 */
class ReadThrottle(private val intervalMs: Long = BusyChatRules.READ_MARK_INTERVAL_MS) {
    private var lastWrite = Long.MIN_VALUE / 2
    private var owed = false

    /** 0 = write now; above 0 = schedule the trailing write after that many ms; null = already owed. */
    @Synchronized
    fun ask(nowMs: Long): Long? {
        if (owed) return null
        val wait = lastWrite + intervalMs - nowMs
        if (wait <= 0) { lastWrite = nowMs; return 0L }
        owed = true
        return wait
    }

    /** The trailing write is going now (its timer fired, or the chat was left). False if none owed. */
    @Synchronized
    fun takeOwed(nowMs: Long): Boolean {
        if (!owed) return false
        owed = false
        lastWrite = nowMs
        return true
    }
}

/** LOW phones only: more than [maxRows] new rows inside [windowMs] means arrivals draw without animation. */
class ArrivalBurst(private val windowMs: Long = BusyChatRules.BURST_WINDOW_MS, private val maxRows: Int = BusyChatRules.BURST_ROWS) {
    private val times = ArrayDeque<Long>()

    /** [newRows] rows came at [nowMs]; true while the last [windowMs] held more than [maxRows]. */
    fun note(nowMs: Long, newRows: Int): Boolean {
        repeat(newRows.coerceAtMost(maxRows + 1)) { times.addLast(nowMs) }
        while (times.isNotEmpty() && times.first() <= nowMs - windowMs) times.removeFirst()
        while (times.size > maxRows + 1) times.removeFirst()
        return times.size > maxRows
    }

    companion object {
        /** Rows after the previous newest row ([prevLast] rowId), counted from the end. */
        fun newAtEnd(items: List<Message>, prevLast: String?): Int {
            if (prevLast == null) return 0
            for (i in items.indices.reversed()) if (items[i].rowId == prevLast) return items.size - 1 - i
            return 0
        }
    }
}

/**
 * [ThreadLogic.geometry] worked out again only from where the list changed. Rows are compared by
 * identity: the repository keeps the same Message object for a message that did not change, so
 * a new message at the bottom recomputes two rows, not the whole page. Same answer as the full one.
 */
class IncrementalGeometry {
    private var items: List<Message> = emptyList()
    private var geo: List<ThreadLogic.RowGeometry> = emptyList()
    private var days: List<LocalDate> = emptyList()
    private var zone: ZoneId? = null
    private var lastChannel = false

    fun update(next: List<Message>, z: ZoneId = ZoneId.systemDefault(), channel: Boolean = false): List<ThreadLogic.RowGeometry> {
        val n = next.size
        var p = 0
        if (z == zone && channel == lastChannel) {
            val lim = minOf(items.size, n)
            while (p < lim && items[p] === next[p]) p++
        }
        if (p == n && n == items.size) return geo
        // Row i depends on rows i-1, i and i+1, so everything below p-1 stays as it was.
        val keep = maxOf(0, p - 1)
        val day = ArrayList<LocalDate>(n)
        for (i in 0 until n) day.add(if (i < p) days[i] else Instant.ofEpochMilli(next[i].createdAtMillis.toLong()).atZone(z).toLocalDate())
        // A channel's posts never run; a bare picture never runs with a bubble (iOS 2026-11-21).
        fun joins(i: Int): Boolean = i in 1 until n && day[i - 1] == day[i] && !channel &&
            ThreadLogic.kindsRun(next[i - 1], next[i]) &&
            next[i - 1].authorId == next[i].authorId &&
            !ThreadLogic.breaksCluster(next[i - 1]) && !ThreadLogic.breaksCluster(next[i]) &&
            next[i - 1].forwarded == next[i].forwarded &&
            next[i].createdAtMillis - next[i - 1].createdAtMillis <= ThreadLogic.CLUSTER_GAP_MS
        val out = ArrayList<ThreadLogic.RowGeometry>(n)
        for (i in 0 until keep) out.add(geo[i])
        for (i in keep until n) {
            val showDate = i == 0 || day[i - 1] != day[i]
            val first = !joins(i)
            val last = i == n - 1 || !joins(i + 1)
            out.add(ThreadLogic.RowGeometry(showDate, first, last, if (showDate) 0 else if (first) 14 else 2))
        }
        items = next; geo = out; days = day; zone = z; lastChannel = channel
        return out
    }
}
