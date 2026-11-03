package com.fariin.app.data

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The group page's row of circles (iOS GroupInfoView actionsRow): Voice, Video, Mute, Search.
 * iOS has a fifth, More; on Android that menu is the bar's three-dot overflow. A circle I may not
 * use stays on the row, greyed, and a tap says why (owner 2026-10-19).
 */
object GroupInfoCircles {
    /** iOS ChatService.muteUntil: "Always" is the far-future stamp every client reads as forever. */
    const val FOREVER = 9_999_999_999_999.0

    /** iOS muteMenuItems "Mute this chat for…", in its order; null hours = Always. */
    val MUTE_CHOICES: List<Pair<String, Double?>> =
        listOf("1 hour" to 1.0, "8 hours" to 8.0, "1 day" to 24.0, "1 week" to 168.0, "Always" to null)

    const val MUTE_HEADER = "Mute this chat for..."
    const val MUTE_FAILED = "Your mute setting was not changed. Check your connection and try again."

    fun muteUntil(hours: Double?, nowMs: Long = System.currentTimeMillis()): Double =
        hours?.let { nowMs + it * 3_600_000 } ?: FOREVER

    /** `mutedBy.<me>` from the group doc; 0 when not muted. */
    fun mutedUntil(conv: Map<String, Any?>, me: String): Double =
        ((conv["mutedBy"] as? Map<*, *>)?.get(me) as? Number)?.toDouble() ?: 0.0

    /** iOS muteUntilLabel: "Muted always" past five years out, else the end (time today, date and time later). */
    fun mutedLabel(untilMs: Double, nowMs: Long, is24h: Boolean, locale: Locale = Locale.getDefault()): String {
        if (untilMs > nowMs + 5 * 365 * 86_400_000.0) return "Muted always"
        val end = Calendar.getInstance().apply { timeInMillis = untilMs.toLong() }
        val now = Calendar.getInstance().apply { timeInMillis = nowMs }
        val sameDay = end.get(Calendar.YEAR) == now.get(Calendar.YEAR) && end.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
        val time = if (is24h) "HH:mm" else "h:mm a"
        val t = SimpleDateFormat(if (sameDay) time else "MMM d, $time", locale).format(Date(untilMs.toLong()))
        return "Muted until $t"
    }

    /** What a Voice or Video tap does (iOS callTapped). */
    sealed interface CallTap {
        /** My own call of this group is up: bring it back. */
        data object Restore : CallTap
        /** Join the running call, or start one. */
        data class Go(val start: Boolean) : CallTap
        /** Said in the page's notice. */
        data class Refuse(val notice: String) : CallTap
        /** iOS showSoloCall: the centred "Can't Call" alert. */
        data object Solo : CallTap
    }

    const val SOLO_TITLE = "Can't Call"
    const val SOLO_TEXT = "You're the only one in this group."

    /**
     * iOS callTapped, in its order: my own call comes back; any other call is "already on a call";
     * an ended group says so; only admins may start one (joining a running call is open to every
     * member); nobody else in the group is the alert.
     */
    fun callTap(myGroupCall: String?, otherCallLive: Boolean, cid: String, ended: Boolean, mayStart: Boolean,
                live: Boolean, memberCount: Int): CallTap = when {
        myGroupCall == cid -> CallTap.Restore
        myGroupCall != null || otherCallLive -> CallTap.Refuse("You're already on a call")
        ended -> CallTap.Refuse("This group has ended.")
        !mayStart && !live -> CallTap.Refuse("Only admins can start a call")
        // Only once the group has loaded (an empty list is "not read yet").
        memberCount == 1 -> CallTap.Solo
        else -> CallTap.Go(start = !live)
    }

    /** iOS callReady: the call circles read as usable (else greyed, 0.45). */
    fun callReady(myGroupCall: String?, otherCallLive: Boolean, cid: String, ended: Boolean, mayStart: Boolean, live: Boolean): Boolean {
        val busy = otherCallLive || (myGroupCall != null && myGroupCall != cid)
        return (mayStart || live) && !busy && !ended
    }
}
