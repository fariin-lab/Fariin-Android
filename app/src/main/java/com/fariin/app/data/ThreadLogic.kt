package com.fariin.app.data

import com.google.firebase.Timestamp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** The footer mark on my own bubble (iOS `BubbleTicks.Kind`). */
enum class Tick { NONE, SENDING, FAILED, SENT, DELIVERED, READ }

/**
 * What the other side has confirmed, read off the conversation document the way iOS
 * ThreadRepository's conversation listener does.
 */
data class Receipts(
    /** 1:1: when they last read. Group: the SLOWEST other member (read only once everyone has). */
    val otherLastReadMillis: Double = 0.0,
    /** 1:1 only: the conversation `updatedAt` their phone confirmed it had (`delivered.<uid>`). */
    val otherDeliveredMillis: Double = 0.0,
    val iBlocked: Boolean = false,
) {
    companion object {
        fun from(data: Map<String, Any?>, me: String, cid: String): Receipts {
            val users = (data["users"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
            val group = com.fariin.app.crypto.Wire.isGroupCid(cid)
            val read = data["lastRead"] as? Map<*, *> ?: emptyMap<String, Any>()
            fun readOf(uid: String): Double = (read[uid] as? Timestamp)?.let { Conversation.millis(it) } ?: 0.0
            val blocked = (data["blockedBy"] as? Map<*, *>)?.get(me) as? Boolean ?: false
            return if (!group) {
                val other = com.fariin.app.crypto.Wire.otherUid(cid, me)
                val delivered = ((data["delivered"] as? Map<*, *>)?.get(other) as? Number)?.toDouble() ?: 0.0
                Receipts(readOf(other), delivered, blocked)
            } else {
                val others = users.filter { it != me }
                Receipts(if (others.isEmpty()) 0.0 else others.minOf { readOf(it) }, 0.0, false)
            }
        }
    }
}

object ThreadLogic {
    /** Two bubbles further apart than this start a new run (ThreadView `clusterGap`). */
    const val CLUSTER_GAP_MS = 5 * 60 * 1000.0

    /** MessageRowModelBuilder's tick: one = on the server, two faded = on their phone, two solid = read. */
    fun tick(m: Message, me: String, r: Receipts, isChannel: Boolean = false): Tick {
        val authored = m.authorId == me
        if (isChannel && authored && !m.deleted && m.sendState == Message.SendState.SENDING) return Tick.SENDING
        if (!authored || m.deleted) return Tick.NONE
        return when (m.sendState) {
            Message.SendState.SENDING -> Tick.SENDING
            Message.SendState.FAILED -> Tick.FAILED
            null -> when {
                // My photo, video, voice note or file whose file is still going up: the clock, not a
                // tick (the other phone can not open it yet). Kept apart from sendState so the message,
                // already on the server, can still be deleted for everyone.
                m.uploading -> Tick.SENDING
                m.held -> Tick.SENT
                !r.iBlocked && r.otherLastReadMillis >= m.createdAtMillis -> Tick.READ
                !r.iBlocked && r.otherDeliveredMillis >= m.createdAtMillis -> Tick.DELIVERED
                else -> Tick.SENT
            }
        }
    }

    /** System and call lines are not bubbles and never join a run. */
    fun breaksCluster(m: Message): Boolean = m.isSystem || m.isCall

    data class RowGeometry(val showDate: Boolean, val first: Boolean, val last: Boolean, val gapDp: Int)

    /** [items] oldest first. Same day, same sender, both bubbles, within 5 minutes = one run. */
    fun geometry(items: List<Message>, zone: ZoneId = ZoneId.systemDefault()): List<RowGeometry> {
        val n = items.size
        val day = items.map { Instant.ofEpochMilli(it.createdAtMillis.toLong()).atZone(zone).toLocalDate() }
        val joins = BooleanArray(n)
        val sameDay = BooleanArray(n)
        for (i in 1 until n) {
            sameDay[i] = day[i - 1] == day[i]
            joins[i] = sameDay[i] &&
                items[i - 1].authorId == items[i].authorId &&
                !breaksCluster(items[i - 1]) && !breaksCluster(items[i]) &&
                items[i - 1].forwarded == items[i].forwarded &&
                items[i].createdAtMillis - items[i - 1].createdAtMillis <= CLUSTER_GAP_MS
        }
        return List(n) { i ->
            val showDate = i == 0 || !sameDay[i]
            val first = !joins[i]
            val last = i == n - 1 || !joins[i + 1]
            RowGeometry(showDate, first, last, if (showDate) 0 else if (first) 14 else 2)
        }
    }

    private val mediumDate = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    private val longDate = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)

    /** The day separator: Today, Yesterday, "Nov 2, 2026"; six months back or more, the long
     *  form with the month spelt out (ThreadView `dayLabel`). */
    fun dayLabel(millis: Double, nowMillis: Double, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        val d = Instant.ofEpochMilli(millis.toLong()).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(nowMillis.toLong()).atZone(zone).toLocalDate()
        return when {
            d == today -> "Today"
            d == today.minusDays(1) -> "Yesterday"
            ChronoUnit.MONTHS.between(d, today) >= 6 -> longDate.withLocale(locale).format(d)
            else -> mediumDate.withLocale(locale).format(d)
        }
    }

    /** The bubble's time: "9:41 AM" or "09:41" with the phone's 24-hour setting. */
    fun timeLabel(millis: Double, is24h: Boolean, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        val t = Instant.ofEpochMilli(millis.toLong()).atZone(zone).toLocalTime()
        return DateTimeFormatter.ofPattern(if (is24h) "HH:mm" else "h:mm a", locale).format(t)
    }
}

/**
 * Where a message sits (iOS ThreadRepository `assignOrderKeys`, outlier fix 2026-11-15):
 * 1. A message with a server stamp takes the stamp as its key. Within one author the keys follow
 *    TAP order (a slow photo keeps its place above a text typed after it). Between two stamped
 *    rows of one author (two phones, two clocks) the stamps alone decide. The key never changes
 *    once given.
 * 2. A message still on its way (no server stamp) sits at the END, below everything stamped, in
 *    the order it was tapped. Its key is not kept: once the server has it, rule 1 places it.
 *    (It used to keep the key it got while in flight, its tap time plus its author's clock
 *    offset; one 20 s slow send made that offset 20 s, and the next message stayed below newer
 *    ones for good.)
 * 3. The clock offset is only a tie-in for in-flight rows: measured from sends that reached the
 *    server fast (a send whose own trip took over [SLOW_ACK_MS] is left out), at least
 *    [MIN_SAMPLES] of them, the low quarter (network delay only ever adds), clamped to [MAX_OFFSET_MS].
 */
class ThreadOrder {
    private val sticky = HashMap<String, Double>()
    private var offsets: Map<String, Double> = emptyMap()
    /** rowId -> ms from my tap to the server's ack, for my own sends (written from the send's thread). */
    private val ackTrip = java.util.concurrent.ConcurrentHashMap<String, Double>()

    /** My send [rowId] was acked [tripMs] after its tap. */
    fun noteAck(rowId: String, tripMs: Double) { ackTrip[rowId] = tripMs }

    /** The offset in use for [author] (tests). */
    fun offsetMs(author: String): Double = offsets[author] ?: 0.0

    fun sort(msgs: Collection<Message>): List<Message> {
        offsets = msgs.filter { it.hasServerTime && it.clientTs != null && (ackTrip[it.rowId] ?: 0.0) <= SLOW_ACK_MS }
            .groupBy { it.authorId }
            .mapValues { (_, l) -> robustOffset(l.map { it.createdAtMillis - it.clientTs!! }) }
        fun tap(m: Message) = m.clientTs ?: m.createdAtMillis
        val keys = HashMap<String, Double>()
        // Rule 1: stamped rows (and the rare row with no stamp that is not a send of mine on its way).
        for ((_, list) in msgs.filter { !inFlight(it) }.groupBy { it.authorId }) {
            var runningMax = Double.NEGATIVE_INFINITY
            var runningMaxHasServerTime = false
            for (m in list.sortedWith(compareBy<Message> { tap(it) }.thenBy { it.rowId })) {
                val k = sticky[m.rowId] ?: run {
                    // Two stamped rows first seen stamped: the stamps alone decide. Otherwise tap order
                    // holds (the photo rule), also for my row that drew in flight and is stamped now.
                    val wasInFlight = m.rowId in drawnInFlight
                    val raw = if (m.hasServerTime) m.createdAtMillis else tap(m) + (offsets[m.authorId] ?: 0.0)
                    val free = m.hasServerTime && runningMaxHasServerTime && !wasInFlight
                    (if (free) raw else maxOf(raw, runningMax + 1.0)).also { sticky[m.rowId] = it }
                }
                drawnInFlight.remove(m.rowId)
                keys[m.rowId] = k
                runningMax = maxOf(runningMax, k)
                runningMaxHasServerTime = m.hasServerTime
            }
        }
        // Rule 2: in-flight rows at the end, in tap order.
        var end = keys.values.maxOrNull() ?: Double.NEGATIVE_INFINITY
        for (m in msgs.filter { inFlight(it) }.sortedWith(compareBy<Message> { tap(it) }.thenBy { it.rowId })) {
            val k = maxOf(tap(m) + (offsets[m.authorId] ?: 0.0), end + 1.0)
            keys[m.rowId] = k
            drawnInFlight.add(m.rowId)
            end = k
        }
        return msgs.sortedWith(compareBy<Message> { keys[it.rowId] ?: it.createdAtMillis }.thenBy { it.rowId })
    }

    private val drawnInFlight = HashSet<String>()

    /** A send of mine the server has not stamped yet (sending, or failed). */
    private fun inFlight(m: Message) = !m.hasServerTime && m.sendState != null

    companion object {
        const val SLOW_ACK_MS = 2_000.0
        const val MIN_SAMPLES = 3
        const val MAX_OFFSET_MS = 5_000.0

        /** server minus tap, per send: none until [MIN_SAMPLES]; the low quarter; at most +-[MAX_OFFSET_MS]. */
        fun robustOffset(samples: List<Double>): Double {
            if (samples.size < MIN_SAMPLES) return 0.0
            val s = samples.sorted()
            return s[s.size / 4].coerceIn(-MAX_OFFSET_MS, MAX_OFFSET_MS)
        }
    }
}
