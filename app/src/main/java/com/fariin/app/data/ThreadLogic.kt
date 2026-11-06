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
 * Where a message sits, iOS ThreadRepository `assignOrderKeys`: the server stamp decides between
 * people; within one author the keys follow TAP order (a slow photo keeps its place above a text
 * typed after it); a row still in flight uses its tap time shifted by its author's measured clock
 * offset; and a key never changes once given, so a bubble does not jump when its ack lands.
 */
class ThreadOrder {
    private val sticky = HashMap<String, Double>()
    private var offsets: Map<String, Double> = emptyMap()

    fun sort(msgs: Collection<Message>): List<Message> {
        // Median clock offset per author, from rows that have both halves.
        offsets = msgs.filter { it.hasServerTime && it.clientTs != null }
            .groupBy { it.authorId }
            .mapValues { (_, l) -> l.map { it.createdAtMillis - it.clientTs!! }.sorted().let { it[it.size / 2] } }
        for ((author, list) in msgs.groupBy { it.authorId }) {
            val offset = offsets[author] ?: 0.0
            val tapOrdered = list.sortedWith(compareBy<Message> { it.clientTs ?: it.createdAtMillis }.thenBy { it.rowId })
            var runningMax = Double.NEGATIVE_INFINITY
            var runningMaxHasServerTime = false
            for (m in tapOrdered) {
                val kept = sticky[m.rowId]
                if (kept != null) {
                    runningMax = maxOf(runningMax, kept)
                    runningMaxHasServerTime = m.hasServerTime
                    continue
                }
                val raw = if (m.hasServerTime) m.createdAtMillis else (m.clientTs ?: m.createdAtMillis) + offset
                val k = if (m.hasServerTime && runningMaxHasServerTime) raw else maxOf(raw, runningMax + 1.0)
                sticky[m.rowId] = k
                runningMax = k
                runningMaxHasServerTime = m.hasServerTime
            }
        }
        return msgs.sortedWith(compareBy<Message> { sticky[it.rowId] ?: it.createdAtMillis }.thenBy { it.rowId })
    }
}
