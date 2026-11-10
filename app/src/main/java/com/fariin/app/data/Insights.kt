package com.fariin.app.data

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.time.LocalDate
import java.time.ZoneOffset

/*
 * Insights (iOS InsightsHistory + GlowInsightsLoader): the server's per-day counters under
 * users/{me}/insightsDaily/{yyyy-MM-dd} and per-story records under users/{me}/insightsStories,
 * turned into one period's report. Days are UTC days, as the server writes them.
 */

/** One UTC day of counters (iOS InsightsDay). */
data class InsightsDay(
    val day: String,
    val storyViews: Int = 0,
    val storyReactions: Int = 0,
    val storiesPosted: Int = 0,
    val profileViews: Int = 0,
    val glowersGained: Int = 0,
    val glowersLost: Int = 0,
    val glowingGained: Int = 0,
    val glowingLost: Int = 0,
    /** UTC hour > views in it. */
    val viewHours: Map<Int, Int> = emptyMap(),
)

/** One story's record, kept after the story ends (iOS InsightsStoryRecord). */
data class InsightsStoryRecord(
    val id: String,
    val createdAtMs: Long,
    val isVideo: Boolean,
    val audience: String,
    val views: Int,
    val reactions: Int,
    val ended: Boolean,
    val blurThumb: String,
)

/** iOS InsightsPeriod: the length in days. */
enum class InsightsPeriod(val days: Int) {
    WEEK(7), MONTH(28), QUARTER(90);
    val title: String get() = "Last $days days"
}

/** A figure and the same figure for the period before, when the history reaches that far. */
data class InsightsDelta(val current: Double, val previous: Double?) {
    val change: Double? get() = previous?.let { current - it }
    val percent: Double? get() = previous?.takeIf { it > 0 }?.let { (current - it) / it }
}

data class InsightsPoint(val date: LocalDate, val value: Int)
data class InsightsDayRow(val date: LocalDate, val day: InsightsDay)

/** A live story's numbers on the Stories tab while the history has nothing (iOS StoryInsight). */
data class StoryInsight(val story: PostedStory, val views: Int?, val reactions: Int?)

/**
 * Profile views (iOS ProfileViews): opening somebody else's profile tells the server once
 * (`insightsProfileViewed`), which adds one to that person's day, a count and never a name. The
 * server counts one per person per day; this only stops one run of the app asking again.
 */
object ProfileViews {
    private val told = HashSet<String>()
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    fun record(uid: String, me: String) {
        if (uid.isEmpty() || me.isEmpty() || uid == me) return
        val key = uid + "|" + InsightsCalendar.key(InsightsCalendar.today())
        synchronized(told) { if (!told.add(key)) return }
        scope.launch {
            try {
                FirebaseFunctions.getInstance(AuthRepository.FUNCTIONS_REGION)
                    .getHttpsCallable("insightsProfileViewed").call(mapOf("uid" to uid)).await()
            } catch (_: Exception) {}
        }
    }
}

object InsightsCalendar {
    fun today(nowMs: Long = System.currentTimeMillis()): LocalDate =
        java.time.Instant.ofEpochMilli(nowMs).atZone(ZoneOffset.UTC).toLocalDate()
    fun key(d: LocalDate): String = d.toString()
    fun date(key: String): LocalDate? = runCatching { LocalDate.parse(key) }.getOrNull()
    fun startMs(d: LocalDate): Long = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
}

/** One period's figures (iOS InsightsReport.build). */
data class InsightsReport(
    val period: InsightsPeriod,
    val start: LocalDate,
    val end: LocalDate,
    val firstDay: LocalDate?,
    val views: InsightsDelta,
    val reactions: InsightsDelta,
    val posted: InsightsDelta,
    val profileViews: InsightsDelta,
    val viewsPerStory: InsightsDelta,
    val glowersNet: InsightsDelta,
    val gainedDelta: InsightsDelta,
    val lostDelta: InsightsDelta,
    val glowersGained: Int,
    val glowersLost: Int,
    val glowersByDay: List<InsightsPoint>,
    val bestHourUtc: Int?,
    val recordedDays: List<InsightsDayRow>,
    val hoursUtc: Map<Int, Int>,
    val periodStories: List<InsightsStoryRecord>,
) {
    val coversPeriod: Boolean get() = firstDay?.let { !it.isAfter(start) } ?: false
    val hasHistory: Boolean get() = firstDay != null

    companion object {
        const val BEST_HOUR_MINIMUM = 10

        fun build(period: InsightsPeriod, today: LocalDate, days: List<InsightsDay>, stories: List<InsightsStoryRecord>,
                  firstDay: String?, glowersNow: Int): InsightsReport {
            val length = period.days
            val end = today
            val start = end.minusDays((length - 1).toLong())
            val previousStart = start.minusDays(length.toLong())
            val afterEnd = end.plusDays(1)
            val first = firstDay?.let(InsightsCalendar::date)
            val compared = first?.let { !it.isAfter(previousStart) } ?: false
            val byKey = days.associateBy { it.day }
            fun window(from: LocalDate) = (0 until length).map { o ->
                val d = from.plusDays(o.toLong()); InsightsDayRow(d, byKey[InsightsCalendar.key(d)] ?: InsightsDay(InsightsCalendar.key(d)))
            }
            val current = window(start)
            val previous = window(previousStart)
            fun total(rows: List<InsightsDayRow>, f: (InsightsDay) -> Int) = rows.sumOf { f(it.day) }
            fun delta(now: Double, before: Double) = InsightsDelta(now, if (compared) before else null)
            fun compare(f: (InsightsDay) -> Int) = delta(maxOf(0, total(current, f)).toDouble(), maxOf(0, total(previous, f)).toDouble())
            val startMs = InsightsCalendar.startMs(start)
            val prevMs = InsightsCalendar.startMs(previousStart)
            val afterMs = InsightsCalendar.startMs(afterEnd)
            val currentStories = stories.filter { it.createdAtMs in startMs until afterMs }
            val previousStories = stories.filter { it.createdAtMs in prevMs until startMs }
            fun average(rows: List<InsightsStoryRecord>) = if (rows.isEmpty()) 0.0 else rows.sumOf { it.views }.toDouble() / rows.size
            val gained = total(current) { it.glowersGained }
            val lost = total(current) { it.glowersLost }
            val previousNet = total(previous) { it.glowersGained } - total(previous) { it.glowersLost }
            val recorded = current.filter { r -> first?.let { !r.date.isBefore(it) } ?: false }
            // Glowers on each day, counted back from today's number.
            var running = glowersNow
            val points = mutableListOf<InsightsPoint>()
            for (r in current.reversed()) {
                points.add(InsightsPoint(r.date, maxOf(0, running)))
                running = running - r.day.glowersGained + r.day.glowersLost
            }
            val glowersByDay = points.reversed().filter { p -> first?.let { !p.date.isBefore(it) } ?: false }
            val hours = HashMap<Int, Int>()
            for (r in current) for ((h, n) in r.day.viewHours) hours[h] = (hours[h] ?: 0) + n
            val best = if (hours.values.sum() >= BEST_HOUR_MINIMUM)
                hours.entries.sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key }).firstOrNull()?.key else null
            return InsightsReport(
                period, start, end, first,
                views = compare { it.storyViews },
                reactions = compare { it.storyReactions },
                posted = compare { it.storiesPosted },
                profileViews = compare { it.profileViews },
                viewsPerStory = delta(average(currentStories), average(previousStories)),
                glowersNet = delta((gained - lost).toDouble(), previousNet.toDouble()),
                gainedDelta = compare { it.glowersGained },
                lostDelta = compare { it.glowersLost },
                glowersGained = gained,
                glowersLost = lost,
                glowersByDay = glowersByDay,
                bestHourUtc = best,
                recordedDays = recorded,
                hoursUtc = hours,
                periodStories = currentStories.sortedByDescending { it.createdAtMs },
            )
        }
    }
}

object Insights {
    /** iOS GlowInsightsView.glowersNeeded / alwaysOpen (both of the admin's names). */
    const val GLOWERS_NEEDED = 100
    private val alwaysOpen = setOf("realwarya", "v")
    fun isUnlocked(glowers: Int, handle: String): Boolean = handle.lowercase() in alwaysOpen || glowers >= GLOWERS_NEEDED

    /** Twice the longest period, so it can be compared with the one before. */
    private val reach = InsightsPeriod.entries.maxOf { it.days } * 2
    private const val STORY_LIMIT = 500L

    data class History(val days: List<InsightsDay>, val stories: List<InsightsStoryRecord>, val firstDay: String?)

    /** iOS InsightsHistoryLoader.load; null when it could not be read. */
    suspend fun history(me: String): History? {
        if (me.isEmpty()) return null
        val account = FirebaseFirestore.getInstance().collection("users").document(me)
        val from = InsightsCalendar.today().minusDays((reach - 1).toLong())
        return try {
            val daySnap = account.collection("insightsDaily").whereGreaterThanOrEqualTo("day", InsightsCalendar.key(from))
                .orderBy("day").get().await()
            val firstSnap = account.collection("insightsDaily").orderBy("day").limit(1).get().await()
            val storySnap = account.collection("insightsStories")
                .whereGreaterThanOrEqualTo("createdAt", Timestamp(java.util.Date(InsightsCalendar.startMs(from))))
                .orderBy("createdAt", Query.Direction.DESCENDING).limit(STORY_LIMIT).get().await()
            History(daySnap.documents.map { day(it.id, it.data ?: emptyMap()) }, storySnap.documents.map { story(it.id, it.data ?: emptyMap()) },
                firstSnap.documents.firstOrNull()?.id)
        } catch (_: Exception) { null }
    }

    private fun n(v: Any?): Int = (v as? Number)?.toInt() ?: 0

    fun day(id: String, d: Map<String, Any?>): InsightsDay = InsightsDay(
        id, n(d["storyViews"]), n(d["storyReactions"]), n(d["storiesPosted"]), n(d["profileViews"]),
        n(d["glowersGained"]), n(d["glowersLost"]), n(d["glowingGained"]), n(d["glowingLost"]),
        (d["viewHours"] as? Map<*, *>).orEmpty().mapNotNull { (k, v) -> (k as? String)?.toIntOrNull()?.takeIf { it in 0..23 }?.let { it to n(v) } }.toMap(),
    )

    private fun story(id: String, d: Map<String, Any?>) = InsightsStoryRecord(
        id, (d["createdAt"] as? Timestamp)?.toDate()?.time ?: System.currentTimeMillis(), d["type"] == "video",
        d["audience"] as? String ?: "friends", maxOf(0, n(d["views"])), maxOf(0, n(d["reactions"])),
        d["ended"] as? Boolean ?: false, d["blurThumb"] as? String ?: "",
    )

    /** iOS InsightsCountriesLoader: the `insightsTopCountries` callable; null when it failed. */
    suspend fun topCountries(): List<Pair<String, Int>>? = try {
        val data = FirebaseFunctions.getInstance("me-central1").getHttpsCallable("insightsTopCountries").call().await().getData() as? Map<*, *>
        (data?.get("countries") as? List<*>).orEmpty().mapNotNull { r ->
            val m = r as? Map<*, *> ?: return@mapNotNull null
            val name = m["country"] as? String ?: return@mapNotNull null
            val p = (m["percent"] as? Number)?.toInt() ?: return@mapNotNull null
            name to p
        }
    } catch (_: Exception) { null }

    /**
     * iOS GlowInsightsLoader.load: each live story's views and reactions, ranked, and how many
     * different people watched (null unless every list was read). Skipped with view receipts off.
     */
    suspend fun liveRows(me: String, stories: List<PostedStory>, receiptsOn: Boolean): Pair<List<StoryInsight>, Int?> = coroutineScope {
        if (stories.isEmpty() || !receiptsOn) return@coroutineScope ranked(stories.map { StoryInsight(it, it.views, null) }) to null
        val service = StoryService(me)
        val who: (String) -> Pair<String, String?> = { "" to null }
        val fetched = stories.map { s ->
            async {
                val summary = service.viewSummary(s.id, who)
                val list = service.fetchViewers(s.id, who)
                val counted = list?.let { StoryViewSummary.counted(it) }
                Triple(summary?.count ?: counted?.count, summary?.reactionCount ?: counted?.reactionCount, list?.map { it.uid })
            }
        }.awaitAll()
        val people = HashSet<String>()
        var everyListRead = true
        val rows = stories.mapIndexed { i, s ->
            val (v, r, ids) = fetched[i]
            if (ids != null) people.addAll(ids) else everyListRead = false
            StoryInsight(s, v ?: s.views, r)
        }
        ranked(rows) to (if (everyListRead) people.size else null)
    }

    private fun ranked(rows: List<StoryInsight>) = rows.sortedWith(
        compareByDescending<StoryInsight> { it.views ?: -1 }.thenByDescending { it.reactions ?: -1 }.thenByDescending { it.story.createdAtMs })
}
