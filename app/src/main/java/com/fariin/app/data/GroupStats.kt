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

    /**
     * One row of a channel's Recent Posts (iOS GroupStatsPost): a post or a story, with only the counts
     * the server sent. [quotes] is how many times a post was quoted; never shown for a story.
     */
    data class Post(
        val id: String, val isStory: Boolean, val atMs: Double?, val preview: String, val thumb: String?,
        val views: Int?, val reactions: Int?, val shares: Int?, val quotes: Int?,
    )

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
        val recentPosts: List<Post> = emptyList(),
    )

    // ---- A channel's statistics (iOS GroupStatsChannelData, owner 2026-10-29) ----------------------
    // The same `groupStats` callable answers `kind: "channel"` for a channel: Overview tiles of
    // followers and per-post / per-story means, day charts, views by hour, recent posts. A figure the
    // server has no data for is null and its tile or chart is left out; nothing is made up.

    data class ChannelTile(val label: String, val value: String, val change: Pair<String, Int>?)

    /** Days and one value list per series, aligned (a chart's input). Empty = no chart. */
    data class DaySeries(val days: List<LocalDate>, val series: List<Series>) { val isEmpty get() = days.isEmpty() }

    data class ChannelData(
        val tiles: List<ChannelTile>,
        val growth: DaySeries, val followers: DaySeries, val notifications: DaySeries,
        val hours: List<Bin>, val interactions: DaySeries, val emoji: DaySeries, val stories: DaySeries,
        val recentPosts: List<Post>,
    ) {
        val isEmpty get() = tiles.isEmpty() && growth.isEmpty && followers.isEmpty && notifications.isEmpty &&
            hours.isEmpty() && interactions.isEmpty && emoji.isEmpty && stories.isEmpty && recentPosts.isEmpty()
    }

    fun isChannelAnswer(d: Map<*, *>) = d["kind"] == "channel"

    fun parseChannel(d: Map<*, *>, zone: ZoneId = ZoneId.systemDefault()): ChannelData {
        val c = dict(d["charts"])
        val utc = (c["viewsByHour"] as? List<*>)?.map { int(it) }.orEmpty()
        val hours = if (utc.size == 24) hourBins(localHourOfWeek(utc + List(144) { 0 }, zone)) else emptyList()
        // Story shares only when any story was shared, so an all-zero line does not crowd the chart.
        val storyRows = c["stories"] as? List<*> ?: emptyList<Any>()
        val shared = storyRows.any { int(dict(it)["reposts"]) > 0 }
        val storyKeys = listOf("views" to "Views", "reactions" to "Reactions") + (if (shared) listOf("reposts" to "Shares") else emptyList())
        return ChannelData(
            tiles = channelTiles(dict(d["overview"])),
            growth = daySeries(c["growth"], listOf("followers" to "Followers")),
            followers = daySeries(c["followers"], listOf("joined" to "Joined", "left" to "Left")),
            notifications = daySeries(c["notifications"], listOf("muted" to "Muted", "unmuted" to "Unmuted")),
            hours = hours,
            interactions = daySeries(c["interactions"], listOf("views" to "Views", "reactions" to "Reactions")),
            emoji = emojiSeries(c["reactionsByEmoji"]),
            stories = daySeries(storyRows, storyKeys),
            recentPosts = posts(d["recentPosts"]),
        )
    }

    /** iOS tiles(): eight in the reference order; a null figure, or a zero with no change, is left out. */
    private fun channelTiles(o: Map<*, *>): List<ChannelTile> {
        val out = mutableListOf<ChannelTile>()
        fun add(label: String, v: Any?) {
            val m = dict(v)
            val now = (m["now"] as? Number)?.toDouble() ?: return
            val ch = change(now, (m["prev"] as? Number)?.toDouble())
            if (now == 0.0 && ch == null) return
            out += ChannelTile(label, number().format(kotlin.math.round(now).toLong()), ch)
        }
        add("Followers", o["followers"])
        val n = dict(o["enabledNotifications"])
        val part = (n["part"] as? Number)?.toDouble(); val total = (n["total"] as? Number)?.toDouble()
        if (part != null && total != null && total > 0 && part > 0) out += ChannelTile("Enabled Notifications", "${kotlin.math.round(part / total * 100).toInt()}%", null)
        add("Views Per Post", o["viewsPerPost"]); add("Views Per Story", o["viewsPerStory"])
        add("Shares Per Post", o["sharesPerPost"]); add("Shares Per Story", o["sharesPerStory"])
        add("Reactions Per Post", o["reactionsPerPost"]); add("Reactions Per Story", o["reactionsPerStory"])
        return out
    }

    private fun parseDay(v: Any?): LocalDate? = (v as? String)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    /** Day rows `{ d, key: n }`; a day from before counting started (`counted: false`) is left out. */
    private fun daySeries(v: Any?, keys: List<Pair<String, String>>): DaySeries {
        val rows = (v as? List<*>).orEmpty().map { dict(it) }.filter { it["counted"] != false }
            .mapNotNull { r -> parseDay(r["d"])?.let { it to r } }
        if (rows.isEmpty()) return DaySeries(emptyList(), emptyList())
        return DaySeries(rows.map { it.first }, keys.map { (k, name) -> Series(name, rows.map { int(it.second[k]) }) })
    }

    /** Reactions by emoji, stacked: the ten most used each a series, the rest as "Other". */
    private fun emojiSeries(v: Any?): DaySeries {
        val rows = (v as? List<*>).orEmpty().map { dict(it) }.mapNotNull { r ->
            val day = parseDay(r["d"]) ?: return@mapNotNull null
            val counts = HashMap<String, Int>()
            for ((e, n) in dict(r["counts"])) {
                val k = e as? String ?: continue
                val cnt = int(n); if (cnt <= 0) continue
                val name = if (k.startsWith("custom:") || k.length > 16) "Custom" else k
                counts[name] = (counts[name] ?: 0) + cnt
            }
            day to counts
        }
        val totals = HashMap<String, Int>()
        rows.forEach { (_, m) -> m.forEach { (k, n) -> totals[k] = (totals[k] ?: 0) + n } }
        val ranked = totals.entries.sortedWith(compareBy({ -it.value }, { it.key })).map { it.key }
        if (ranked.isEmpty()) return DaySeries(emptyList(), emptyList())
        val top = ranked.take(10)
        val names = if (ranked.size > top.size) top + "Other" else top
        return DaySeries(rows.map { it.first }, names.map { name ->
            Series(name, rows.map { (_, m) -> if (name == "Other") m.filterKeys { it !in top }.values.sum() else m[name] ?: 0 })
        })
    }

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
            recentPosts = posts(d["recentPosts"]),
        )
    }

    /** A channel's `recentPosts` rows (iOS GroupStatsChannelData.posts); a row that is not a map is skipped. */
    fun posts(v: Any?): List<Post> {
        val out = mutableListOf<Post>()
        (v as? List<*>)?.forEach { item ->
            val m = item as? Map<*, *> ?: return@forEach
            out.add(Post(
                id = m["id"] as? String ?: "post${out.size}",
                isStory = m["kind"] == "story",
                atMs = date(m["at"]),
                preview = m["preview"] as? String ?: "",
                thumb = (m["thumb"] as? String)?.ifEmpty { null },
                views = optInt(m["views"]), reactions = optInt(m["reactions"]),
                shares = optInt(m["shares"]), quotes = optInt(m["quotes"]),
            ))
        }
        return out
    }

    /**
     * A row's first line. A story says it is one, so a channel's stories never read as posts the
     * owner did not make: "Photo Story", "Video Story", or "Story: " and its caption.
     */
    fun postTitle(p: Post): String {
        if (!p.isStory) return p.preview.ifEmpty { "Post" }
        return when (p.preview) {
            "", "📷 Photo" -> "Photo Story"
            "📹 Video" -> "Video Story"
            else -> "Story: " + p.preview
        }
    }

    /**
     * "Oct 28 · 412 views · 12 reactions · 2 quotes · 3 shares": only what the server counted. Quotes
     * are for posts only; a story's shares are reposts, so it says so.
     */
    fun postDetail(p: Post, dayText: String?, locale: Locale = Locale.getDefault()): String {
        val f = number(locale)
        fun count(n: Int, one: String, many: String) = "${f.format(n)} ${if (n == 1) one else many}"
        val parts = mutableListOf<String>()
        if (dayText != null) parts.add(dayText)
        p.views?.let { parts.add(count(it, "view", "views")) }
        p.reactions?.let { parts.add(count(it, "reaction", "reactions")) }
        if (!p.isStory) p.quotes?.let { parts.add(count(it, "quote", "quotes")) }
        p.shares?.let { parts.add(if (p.isStory) count(it, "repost", "reposts") else count(it, "share", "shares")) }
        return parts.joinToString(" · ")
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
