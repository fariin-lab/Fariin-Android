package com.fariin.app.data

import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Group Statistics (iOS GroupStatsView, group page): the `groupStats` callable's counts in one read.
 * An Overview of four figures against the week before, day charts for the last 30 days, the busiest
 * hours and weekdays in the phone's own time zone, the members' languages and three top-10 lists.
 * A missing key is zeros, never a failure. Pure logic; the page is ui/people/GroupStatsPage.kt.
 */
object GroupStats {

    /** iOS AppLimits.groupStatsMinMembers fallback (the panel's `group_stats_min_members`). */
    const val MIN_MEMBERS = 200

    data class Metric(val value: Int = 0, val prev: Int? = null)
    data class Person(val id: String, val name: String, val photoUrl: String?, val detail: String)
    data class Language(val index: Int, val code: String, val count: Int)
    data class Bin(val id: Int, val label: String, val value: Int)
    data class Series(val name: String, val values: List<Int>)

    data class Data(
        val nowMs: Double,
        val sinceMs: Double?,
        val days: List<LocalDate>,
        val members: Metric, val messagesMetric: Metric, val viewers: Metric, val posters: Metric,
        val growth: List<Int>, val joined: List<Int>, val left: List<Int>,
        val srcLink: List<Int>, val srcPublic: List<Int>, val srcAdded: List<Int>, val srcRequest: List<Int>,
        val messages: List<Int>, val deleted: List<Int>, val banned: List<Int>,
        val hours: List<Bin>, val weekdays: List<Bin>,
        val languages: List<Language>,
        val topPosters: List<Person>, val topAdmins: List<Person>, val topInviters: List<Person>,
    )

    /** The Statistics row on Group Info (iOS showStatsRow): owner or admin, not locked, big enough or a Fariin admin. */
    fun showRow(conv: Map<String, Any?>, me: String, appAdmin: Boolean, featureOn: Boolean = true, min: Int = MIN_MEMBERS): Boolean {
        if (!featureOn || locked(conv, System.currentTimeMillis().toDouble())) return false
        val admins = (conv["admins"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
        if (conv["createdBy"] != me && me !in admins) return false
        val users = (conv["users"] as? List<*>)?.size ?: 0
        return users >= min || appAdmin
    }

    /** iOS GroupMod.locksGroup: disabled, or suspended and not yet over, by Fariin. */
    fun locked(conv: Map<String, Any?>, nowMs: Double): Boolean {
        val mod = conv["mod"] as? Map<*, *> ?: return false
        return when (mod["state"] as? String) {
            "disabled" -> true
            "suspended" -> {
                val until = (mod["until"] as? com.google.firebase.Timestamp)?.toDate()?.time?.toDouble() ?: 0.0
                until <= 0 || until > nowMs
            }
            else -> false
        }
    }

    fun parse(d: Map<*, *>, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault(), today: LocalDate = LocalDate.now(zone)): Data {
        val days = days(d["days"], today)
        val n = days.size
        val o = dict(d["overview"])
        val s = dict(d["sources"])
        val a = dict(d["actions"])
        val local = localHourOfWeek(ints(d["hourOfWeekUTC"], 168), zone)
        val langs = mutableListOf<Language>()
        (d["languages"] as? List<*>)?.forEach { item ->
            val m = item as? Map<*, *> ?: return@forEach
            val count = int(m["count"])
            if (count > 0) langs.add(Language(langs.size, (m["code"] as? String ?: "").lowercase(), count))
        }
        val f = number(locale)
        return Data(
            nowMs = date(d["now"]) ?: System.currentTimeMillis().toDouble(),
            sinceMs = date(d["since"]),
            days = days,
            members = metric(o["members"]), messagesMetric = metric(o["messages"]), viewers = metric(o["viewers"]), posters = metric(o["posters"]),
            growth = ints(d["growth"], n), joined = ints(d["joined"], n), left = ints(d["left"], n),
            srcLink = ints(s["link"], n), srcPublic = ints(s["public"], n), srcAdded = ints(s["added"], n), srcRequest = ints(s["request"], n),
            messages = ints(d["messages"], n), deleted = ints(a["deleted"], n), banned = ints(a["banned"], n),
            hours = hourBins(local), weekdays = weekdayBins(local, locale),
            languages = langs,
            topPosters = people(d["topPosters"]) { m ->
                val c = int(m["messages"]); val avg = int(m["avgChars"])
                "${f.format(c)} ${if (c == 1) "message" else "messages"} · avg ${f.format(avg)} chars"
            },
            topAdmins = people(d["topAdmins"]) { m -> "${f.format(int(m["deleted"]))} deleted · ${f.format(int(m["banned"]))} removed" },
            topInviters = people(d["topInviters"]) { m -> "${f.format(int(m["invited"]))} invited" },
        )
    }

    fun number(locale: Locale = Locale.getDefault()): NumberFormat = NumberFormat.getIntegerInstance(locale)

    fun int(v: Any?): Int = (v as? Number)?.toInt() ?: 0
    private fun optInt(v: Any?): Int? = (v as? Number)?.toInt()
    private fun dict(v: Any?): Map<*, *> = v as? Map<*, *> ?: emptyMap<String, Any>()
    private fun date(v: Any?): Double? = (v as? Number)?.toDouble()?.takeIf { it > 0 }
    private fun metric(v: Any?): Metric { val m = dict(v); return Metric(int(m["value"]), optInt(m["prev"])) }

    /** The last [count] values, zeros in front when short (iOS GroupStatsParse.ints). */
    fun ints(v: Any?, count: Int): List<Int> {
        var out = (v as? List<*>)?.map { int(it) } ?: emptyList()
        if (out.size > count) out = out.takeLast(count)
        return List(count - out.size) { 0 } + out
    }

    /** "yyyy-MM-dd" as local days; none sent = the 30 days ending today. */
    fun days(v: Any?, today: LocalDate): List<LocalDate> {
        val out = (v as? List<*>)?.mapNotNull { (it as? String)?.let { s -> runCatching { LocalDate.parse(s) }.getOrNull() } } ?: emptyList()
        if (out.isNotEmpty()) return out
        return (29 downTo 0).map { today.minusDays(it.toLong()) }
    }

    private fun people(v: Any?, detail: (Map<*, *>) -> String): List<Person> {
        val out = mutableListOf<Person>()
        (v as? List<*>)?.forEach { item ->
            val m = item as? Map<*, *> ?: return@forEach
            out.add(Person(m["uid"] as? String ?: "row${out.size}", m["name"] as? String ?: "",
                (m["photoUrl"] as? String)?.ifEmpty { null }, detail(m)))
        }
        return out
    }

    /** The server's 168 UTC hours (index = weekday*24 + hour, 0 = Sunday) in the phone's zone; whole hours, rounded down. */
    fun localHourOfWeek(utc: List<Int>, zone: ZoneId): List<Int> {
        val out = IntArray(168)
        if (utc.size != 168) return out.toList()
        val offset = zone.rules.getOffset(Instant.now()).totalSeconds
        val shift = floor(offset / 3600.0).toInt()
        for (i in 0 until 168) out[((i + shift) % 168 + 168) % 168] += utc[i]
        return out.toList()
    }

    fun hourBins(week: List<Int>): List<Bin> {
        val sums = IntArray(24)
        week.forEachIndexed { i, v -> sums[i % 24] += v }
        return sums.mapIndexed { h, v -> Bin(h, "$h", v) }
    }

    /** Monday first, as the reference app shows them. */
    fun weekdayBins(week: List<Int>, locale: Locale = Locale.getDefault()): List<Bin> {
        val sums = IntArray(7)
        week.forEachIndexed { i, v -> sums[minOf(i / 24, 6)] += v }
        return listOf(1, 2, 3, 4, 5, 6, 0).map { d ->
            // 0 = Sunday on the server; DayOfWeek counts Monday = 1 .. Sunday = 7.
            Bin(d, DayOfWeek.of(if (d == 0) 7 else d).getDisplayName(TextStyle.SHORT, locale), sums[d])
        }
    }

    fun languageName(code: String, locale: Locale = Locale.getDefault()): String = when (code) {
        "other" -> "Other"
        "", "unknown", "und" -> "Unknown"
        else -> Locale(code).getDisplayLanguage(locale).takeIf { it.isNotEmpty() && it != code }
            ?.replaceFirstChar { it.titlecase(locale) } ?: code.uppercase()
    }

    fun busiestHour(bins: List<Bin>): String {
        val top = bins.maxByOrNull { it.value }
        return if (top == null || top.value <= 0) "Your local time" else "Busiest at " + String.format(Locale.US, "%02d:00", top.id)
    }

    fun busiestDay(bins: List<Bin>): String {
        val top = bins.maxByOrNull { it.value }
        return if (top == null || top.value <= 0) "Your local time" else "Busiest on " + top.label
    }

    /** "+12 (8%)" / "−3 (2%)" against the week before; null when it is unknown or zero. Positive is green. */
    fun change(value: Double, prev: Double?): Pair<String, Int>? {
        if (prev == null || prev <= 0) return null
        val diff = value - prev
        val pct = (abs(diff) / prev * 100).roundToInt()
        return when {
            diff > 0 -> "+${plain(diff)} ($pct%)" to 1
            diff < 0 -> "−${plain(abs(diff))} ($pct%)" to -1
            else -> "+0 (0%)" to 0
        }
    }

    private fun plain(v: Double): String = if (v == floor(v)) number().format(v.toLong()) else String.format(Locale.getDefault(), "%.1f", v)

    /** "Oct 19 - Oct 26": the week the Overview covers. */
    fun period(nowMs: Double, zone: ZoneId = ZoneId.systemDefault(), pattern: String = "MMM d", locale: Locale = Locale.getDefault()): String {
        val end = Instant.ofEpochMilli(nowMs.toLong()).atZone(zone).toLocalDate()
        val f = DateTimeFormatter.ofPattern(pattern, locale)
        return end.minusDays(7).format(f) + " – " + end.format(f)
    }

    /**
     * A day chart's caption (iOS GroupStatsDayChart.caption): the picked day's values, or "Now N" for
     * a line, or "Last 30 days: ..." totals.
     */
    fun caption(series: List<Series>, days: List<LocalDate>, selected: Int?, line: Boolean, dayText: (LocalDate) -> String,
                locale: Locale = Locale.getDefault()): String {
        val f = number(locale)
        fun v(s: Series, i: Int) = s.values.getOrElse(i) { 0 }
        if (selected != null && selected in days.indices) {
            val parts = series.map { s -> if (series.size > 1) "${s.name} ${f.format(v(s, selected))}" else f.format(v(s, selected)) }
            return dayText(days[selected]) + ": " + parts.joinToString(" · ")
        }
        if (line && series.isNotEmpty() && days.isNotEmpty()) return "Now ${f.format(v(series[0], days.lastIndex))}"
        val parts = series.map { s -> val t = s.values.sum(); if (series.size > 1) "${s.name} ${f.format(t)}" else f.format(t) }
        return "Last 30 days: " + parts.joinToString(" · ")
    }

    /** The failure line (iOS GroupStatsView.message), in a channel's words for a channel. */
    fun failure(code: String?, channel: Boolean = false): String {
        val what = if (channel) "channel" else "group"
        return when (code) {
            "PERMISSION_DENIED" -> "Only the $what's owner and admins can see statistics."
            "FAILED_PRECONDITION" -> "Statistics appear once the $what is bigger."
            else -> "Couldn't load statistics. Check your connection and try again."
        }
    }

    /** Only a failed load is worth trying again; a rule (too small, not allowed) is not. */
    fun canRetry(code: String?): Boolean = code != "PERMISSION_DENIED" && code != "FAILED_PRECONDITION"

    /** Each language's share, rounded to a whole percent. */
    fun percent(count: Int, langs: List<Language>): Int = (count * 100.0 / maxOf(1, langs.sumOf { it.count })).roundToInt()
}
