package com.fariin.app.ui.glow

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.fariin.app.data.Glow
import com.fariin.app.data.GlowRules
import com.fariin.app.data.Insights
import com.fariin.app.data.InsightsCalendar
import com.fariin.app.data.InsightsDelta
import com.fariin.app.data.InsightsPeriod
import com.fariin.app.data.InsightsReport
import com.fariin.app.data.InsightsStoryRecord
import com.fariin.app.data.PostedStory
import com.fariin.app.data.StoryInsight
import com.fariin.app.ui.components.FariinMenu
import com.fariin.app.ui.components.FloatingTopBar
import com.fariin.app.ui.theme.Fariin
import com.fariin.app.ui.theme.FariinTheme
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Green = Color(0xFF34C759)

/** The Insights card on My Profile (iOS GlowProfileView.insightsCard), behind the insights switch. */
@Composable
internal fun InsightsCard(glowers: Int, handle: String, card: Color, label: Color, secondary: Color, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(card).clickable(onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Insights", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = label)
        Text(if (!Insights.isUnlocked(glowers, handle)) "Insights are available after reaching ${Insights.GLOWERS_NEEDED} Glowers."
            else "Explore insights from your Stories and Glowers", fontSize = 15.sp, color = secondary, textAlign = TextAlign.Center)
        Text("View Insights", fontSize = 17.sp, color = label, textAlign = TextAlign.Center,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp).fillMaxWidth().clip(CircleShape)
                .background(Color.White.copy(alpha = 0.12f)).padding(vertical = 12.dp))
    }
}

/**
 * INSIGHTS (iOS GlowInsightsView): locked until 100 Glowers; then Overview / Stories / Audience over
 * one period (7, 28 or 90 days), each figure against the period before once the history reaches
 * that far. Dark, like the profile it opens from.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun GlowInsightsPage(me: String, handle: String, live: List<PostedStory>, onPeople: (glowing: Boolean) -> Unit,
                     onPosted: () -> Unit, onBack: () -> Unit) = FariinTheme(dark = true) {
    val c = Fariin.colors
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    val glow by Glow.state.collectAsState()
    val glowers = glow.glowers.size
    val unlocked = Insights.isUnlocked(glowers, handle)
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var period by rememberSaveable { mutableStateOf(InsightsPeriod.MONTH) }
    // null = loading, failed / loaded below.
    var history by remember { mutableStateOf<Insights.History?>(null) }
    var historyFailed by remember { mutableStateOf(false) }
    var countries by remember { mutableStateOf<List<Pair<String, Int>>?>(null) }
    var countriesFailed by remember { mutableStateOf(false) }
    var rows by remember { mutableStateOf(live.map { StoryInsight(it, it.views, null) }) }
    var reload by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val receipts = remember { ctx.getSharedPreferences("fariin_stories", android.content.Context.MODE_PRIVATE).getBoolean("storyViewReceipts", true) }
    LaunchedEffect(unlocked, live.map { it.id }, reload) {
        if (!unlocked) return@LaunchedEffect
        rows = Insights.liveRows(me, live, receipts).first
    }
    suspend fun loadHistory() {
        val h = Insights.history(me)
        if (h != null) { history = h; historyFailed = false } else if (history == null) historyFailed = true
    }
    suspend fun loadCountries() {
        val r = Insights.topCountries()
        if (r != null) { countries = r; countriesFailed = false } else if (countries == null) countriesFailed = true
    }
    LaunchedEffect(unlocked, reload) {
        if (!unlocked) return@LaunchedEffect
        loadHistory(); loadCountries(); refreshing = false
    }

    Column(Modifier.fillMaxSize().background(c.bgSecondary).statusBarsPadding()) {
        FloatingTopBar("Insights", onBack = onBack, container = c.bgSecondary)
        if (!unlocked) {
            Column(Modifier.fillMaxSize().padding(horizontal = 40.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Icon(Icons.Filled.Lock, null, tint = c.secondary, modifier = Modifier.size(44.dp))
                Spacer(Modifier.height(12.dp))
                Text("Insights", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = c.label)
                Spacer(Modifier.height(6.dp))
                Text("Insights are available after reaching ${Insights.GLOWERS_NEEDED} Glowers.", fontSize = 15.sp, color = c.secondary,
                    textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                LinearProgressIndicator(progress = { minOf(glowers, Insights.GLOWERS_NEEDED) / Insights.GLOWERS_NEEDED.toFloat() },
                    modifier = Modifier.width(220.dp), color = c.accent, trackColor = c.fieldFill)
                Spacer(Modifier.height(8.dp))
                Text("$glowers of ${Insights.GLOWERS_NEEDED} Glowers", fontSize = 15.sp, color = c.secondary)
            }
            return@Column
        }
        val h = history
        val report = InsightsReport.build(period, InsightsCalendar.today(), h?.days.orEmpty(), h?.stories.orEmpty(), h?.firstDay, glowers)
        PullToRefreshBox(refreshing, onRefresh = { refreshing = true; reload++ }, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                HalvesSwitch(listOf("Overview", "Stories", "Audience"), tab, { tab = it }, Modifier.padding(top = 8.dp))
                PeriodHeader(report, period) { period = it }
                val gate: @Composable (@Composable () -> Unit) -> Unit = { content ->
                    when {
                        h == null && historyFailed -> Card {
                            Text("Could not load your history", color = c.secondary)
                            Text("Try Again", color = c.accent, fontSize = 17.sp, modifier = Modifier.clickable {
                                historyFailed = false; scope.launch { loadHistory() } })
                        }
                        h == null -> Card { CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(vertical = 20.dp), color = c.secondary) }
                        report.hasHistory -> content()
                        else -> Card(footer = "Your history starts with your next story view or Glow. Trends and comparisons appear here as the days add up.") {
                            Text("Nothing has been recorded yet.", color = c.secondary)
                        }
                    }
                }
                when (tab) {
                    0 -> gate {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            FigureCard("Total Glowers", GlowRules.countShort(glowers), totalGlowersDelta(report, glowers), period,
                                report.glowersByDay.map { it.date to it.value })
                            FigureCard("Profile Views", amount(report.profileViews.current), report.profileViews, period,
                                report.recordedDays.map { it.date to maxOf(0, it.day.profileViews) })
                            Text(overviewFooter(report, period), fontSize = 13.sp, color = c.secondary)
                        }
                    }
                    1 -> {
                        gate {
                            Card(footer = comparisonLine(report, period)) {
                                StatGrid(
                                    Stat("Story views", amount(report.views.current), report.views),
                                    Stat("Reactions", amount(report.reactions.current), report.reactions),
                                    Stat("Stories posted", amount(report.posted.current), report.posted),
                                    Stat("Views per story", average(report.viewsPerStory.current), report.viewsPerStory),
                                )
                            }
                        }
                        if (h != null && report.hasHistory) {
                            val records = report.periodStories
                            if (records.isEmpty()) Card {
                                Text("No stories in this period", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label)
                                Text("Stories you post in the last ${period.days} days appear here with their views and reactions.", color = c.secondary)
                            } else Card("Stories") {
                                records.forEachIndexed { i, r ->
                                    if (i > 0) HorizontalDivider(color = c.separator)
                                    Box(Modifier.clickable(onClick = onPosted)) { RecordRow(r, live, rows) }
                                }
                            }
                        } else if (rows.isNotEmpty()) Card("Live stories") {
                            rows.forEachIndexed { i, r ->
                                if (i > 0) HorizontalDivider(color = c.separator)
                                StoryRow(Thumb.Url(r.story.thumbUrl), r.story.isVideo,
                                    "${relative(r.story.createdAtMs)} · ${audienceName(r.story.audience)}", r.views, r.reactions)
                            }
                        }
                    }
                    else -> {
                        gate {
                            Box(Modifier.clickable { onPeople(false) }) {
                                Card(footer = comparisonLine(report, period)) {
                                    StatGrid(
                                        Stat("Total Glowers", GlowRules.countShort(glowers), totalGlowersDelta(report, glowers)),
                                        Stat("Gained", "+" + GlowRules.countShort(report.glowersGained), report.gainedDelta),
                                        Stat("Unglows", GlowRules.countShort(report.glowersLost), report.lostDelta),
                                        Stat("Net", signed((report.glowersGained - report.glowersLost).toDouble()), report.glowersNet),
                                    )
                                }
                            }
                        }
                        Card("Top Countries", footer = "From the approximate country of each Glower's phone. Shown once enough Glowers have one, so nobody can be pointed at.") {
                            val list = countries
                            when {
                                list == null && countriesFailed -> {
                                    Text("Could not load countries", color = c.secondary)
                                    Text("Try Again", color = c.accent, fontSize = 17.sp, modifier = Modifier.clickable {
                                        countriesFailed = false; scope.launch { loadCountries() } })
                                }
                                list == null -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally), color = c.secondary)
                                list.isEmpty() -> Text("Not enough Glowers with a known country yet.", color = c.secondary)
                                else -> list.forEach { (name, p) -> ShareBar(name, p) }
                            }
                        }
                        gate { ActiveTimesCard(report) }
                        Card {
                            Row(Modifier.fillMaxWidth().clickable { onPeople(true) }, verticalAlignment = Alignment.CenterVertically) {
                                Text("Glowing", fontSize = 17.sp, color = c.label, modifier = Modifier.weight(1f))
                                Text(GlowRules.countShort(glow.glowing.size), fontSize = 17.sp, color = c.secondary)
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = c.secondary, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Card(title: String? = null, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = Fariin.colors
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (title != null) Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label)
        content()
        if (!footer.isNullOrEmpty()) Text(footer, fontSize = 13.sp, color = c.secondary)
    }
}

@Composable
private fun PeriodHeader(report: InsightsReport, period: InsightsPeriod, onPick: (InsightsPeriod) -> Unit) {
    val c = Fariin.colors
    var open by remember { mutableStateOf(false) }
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${dayText(report.start)} - ${dayText(report.end)}", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = c.label,
                modifier = Modifier.weight(1f))
            Box {
                Row(Modifier.clip(CircleShape).background(c.fieldFill).clickable { open = true }.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(period.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.label)
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Filled.KeyboardArrowDown, null, tint = c.label, modifier = Modifier.size(16.dp))
                }
                DropdownMenu(open, onDismissRequest = { open = false }, shape = FariinMenu.shape, border = FariinMenu.border, containerColor = c.card) {
                    for (p in InsightsPeriod.entries) DropdownMenuItem(text = { Text(p.title, color = c.label) },
                        trailingIcon = if (p == period) { { Text("✓", color = c.label) } } else null,
                        onClick = { open = false; onPick(p) })
                }
            }
        }
        Text("Insights", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = c.label)
    }
}

@Composable
private fun FigureCard(title: String, value: String, delta: InsightsDelta, period: InsightsPeriod, points: List<Pair<LocalDate, Int>>) {
    val c = Fariin.colors
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.card)) {
        Row(Modifier.fillMaxWidth().padding(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontSize = 15.sp, color = c.secondary)
                Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = c.label)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("vs Previous ${period.days} Days", fontSize = 12.sp, color = c.secondary)
                val (t, col) = percentText(delta, c.secondary)
                Text(t, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = col)
            }
        }
        if (points.size >= 2) {
            HorizontalDivider(color = c.separator)
            DayBars(points, Modifier.padding(16.dp))
        }
    }
}

/** One bar per day with a few dd/MM labels under them (iOS dayBars). */
@Composable
private fun DayBars(points: List<Pair<LocalDate, Int>>, modifier: Modifier) {
    val c = Fariin.colors
    val max = maxOf(1, points.maxOf { it.second })
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(150.dp)) {
            val slot = size.width / points.size
            val bar = maxOf(1f, slot * 0.7f)
            points.forEachIndexed { i, (_, v) ->
                val h = size.height * v / max
                drawRoundRect(c.accent, Offset(i * slot + (slot - bar) / 2, size.height - h), Size(bar, h), CornerRadius(2.dp.toPx()))
            }
        }
        Spacer(Modifier.height(4.dp))
        val stride = maxOf(1, points.size / 3)
        val fmt = DateTimeFormatter.ofPattern("dd/MM", Locale.getDefault())
        Row(Modifier.fillMaxWidth()) {
            points.forEachIndexed { i, (d, _) ->
                Box(Modifier.weight(1f)) {
                    if (i % stride == 0) Text(d.format(fmt), fontSize = 11.sp, color = c.secondary, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

private data class Stat(val label: String, val value: String?, val delta: InsightsDelta?)

@Composable
private fun StatGrid(a: Stat, b: Stat, d: Stat, e: Stat) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { StatCell(a, Modifier.weight(1f)); StatCell(b, Modifier.weight(1f)) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { StatCell(d, Modifier.weight(1f)); StatCell(e, Modifier.weight(1f)) }
    }
}

@Composable
private fun StatCell(s: Stat, modifier: Modifier) {
    val c = Fariin.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(s.value ?: "–", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = c.label)
        Text(s.label, fontSize = 15.sp, color = c.secondary)
        s.delta?.let { deltaText(it, c.secondary, c.red) }?.let { (t, col) -> Text(t, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = col) }
    }
}

@Composable
private fun ShareBar(label: String, percent: Int) {
    val c = Fariin.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$percent%", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = c.label, modifier = Modifier.width(64.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(label, fontSize = 15.sp, color = c.secondary)
            Box(Modifier.fillMaxWidth(maxOf(0.01f, percent / 100f)).height(3.dp).clip(CircleShape).background(c.label))
        }
    }
}

@Composable
private fun ActiveTimesCard(report: InsightsReport) {
    val c = Fariin.colors
    val ctx = LocalContext.current
    val zone = ZoneId.systemDefault()
    val zoneName = java.util.TimeZone.getDefault().getDisplayName(false, java.util.TimeZone.SHORT)
    Card("When your stories are watched", footer = "On your phone's time zone ($zoneName).") {
        if (report.hoursUtc.values.sum() < InsightsReport.BEST_HOUR_MINIMUM) {
            Text("Not enough views in this period yet.", color = c.secondary)
            return@Card
        }
        // Eight three-hour blocks of the local day.
        val midnight = InsightsCalendar.startMs(InsightsCalendar.today())
        val blocks = IntArray(8)
        for ((h, v) in report.hoursUtc) {
            val local = java.time.Instant.ofEpochMilli(midnight + h * 3_600_000L).atZone(zone).hour
            blocks[local / 3] += v
        }
        val labels = listOf("12a", "3a", "6a", "9a", "12p", "3p", "6p", "9p")
        val max = maxOf(1, blocks.max())
        Canvas(Modifier.fillMaxWidth().height(140.dp)) {
            val slot = size.width / 8
            val bar = slot * 0.6f
            blocks.forEachIndexed { i, v ->
                val hgt = size.height * v / max
                drawRoundRect(c.accent, Offset(i * slot + (slot - bar) / 2, size.height - hgt), Size(bar, hgt), CornerRadius(6.dp.toPx()))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            labels.forEach { Text(it, fontSize = 11.sp, color = c.secondary, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)) }
        }
        report.bestHourUtc?.let { h ->
            val t = java.time.Instant.ofEpochMilli(midnight + h * 3_600_000L).atZone(zone)
            val pattern = if (android.text.format.DateFormat.is24HourFormat(ctx)) "HH" else "h a"
            Row {
                Text("Most views around", fontSize = 17.sp, color = c.label, modifier = Modifier.weight(1f))
                Text(t.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault())), fontSize = 17.sp, color = c.secondary)
            }
        }
    }
}

private sealed interface Thumb {
    data class Url(val url: String) : Thumb
    data class Blur(val base64: String) : Thumb
    data object Missing : Thumb
}

@Composable
private fun RecordRow(r: InsightsStoryRecord, live: List<PostedStory>, rows: List<StoryInsight>) {
    val liveStory = live.firstOrNull { it.id == r.id }
    val liveCount = rows.firstOrNull { it.story.id == r.id }
    val thumb = when {
        liveStory != null -> Thumb.Url(liveStory.thumbUrl)
        r.blurThumb.isEmpty() -> Thumb.Missing
        else -> Thumb.Blur(r.blurThumb)
    }
    val state = if (liveStory != null) "Live" else localDayText(r.createdAtMs)
    StoryRow(thumb, r.isVideo, "$state · ${audienceName(r.audience)}",
        maxOf(r.views, liveCount?.views ?: 0), maxOf(r.reactions, liveCount?.reactions ?: 0))
}

@Composable
private fun StoryRow(thumb: Thumb, isVideo: Boolean, detail: String, views: Int?, reactions: Int?) {
    val c = Fariin.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp, (40 / 0.655f).dp).clip(RoundedCornerShape(8.dp)).background(c.secondary.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center) {
            when (thumb) {
                is Thumb.Url -> AsyncImage(thumb.url, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                is Thumb.Blur -> {
                    val bmp = remember(thumb.base64) {
                        runCatching { Base64.decode(thumb.base64, Base64.DEFAULT).let { BitmapFactory.decodeByteArray(it, 0, it.size) } }.getOrNull()
                    }
                    if (bmp != null) Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    else Icon(if (isVideo) Icons.Filled.Videocam else Icons.Filled.Photo, null, tint = c.secondary, modifier = Modifier.size(14.dp))
                }
                Thumb.Missing -> Icon(if (isVideo) Icons.Filled.Videocam else Icons.Filled.Photo, null, tint = c.secondary, modifier = Modifier.size(14.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(if (isVideo) "Video story" else "Photo story", fontSize = 17.sp, color = c.label)
            Text(detail, fontSize = 15.sp, color = c.secondary, maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Visibility, null, tint = c.label, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(4.dp))
                Text(views?.let(GlowRules::countShort) ?: "–", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.label)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Favorite, null, tint = c.secondary, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(4.dp))
                Text(reactions?.let(GlowRules::countShort) ?: "–", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.secondary)
            }
        }
    }
}

private fun totalGlowersDelta(r: InsightsReport, glowers: Int) =
    InsightsDelta(glowers.toDouble(), if (r.coversPeriod) r.glowersByDay.firstOrNull()?.value?.toDouble() else null)

private fun overviewFooter(r: InsightsReport, p: InsightsPeriod): String {
    val first = r.firstDay ?: return ""
    val since = "Your history starts on ${dayText(first, year = true)}."
    return when {
        r.views.previous != null -> "Each figure is compared with the ${p.days} days before."
        r.coversPeriod -> "$since Comparison with the ${p.days} days before appears once the history is that long."
        else -> "$since These figures count from that day."
    }
}

private fun comparisonLine(r: InsightsReport, p: InsightsPeriod) =
    if (r.views.previous != null) "Compared with the previous ${p.days} days." else overviewFooter(r, p)

/** A UTC day as its own date, "3 Nov" (or "3 Nov 2026"). */
private fun dayText(d: LocalDate, year: Boolean = false): String =
    d.format(DateTimeFormatter.ofPattern(if (year) "d MMM yyyy" else "d MMM", Locale.getDefault()))

private fun localDayText(ms: Long): String =
    java.time.Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate().format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))

private fun relative(ms: Long): String =
    android.text.format.DateUtils.getRelativeTimeSpanString(ms, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS).toString()

private fun audienceName(label: String) = when (label) {
    "everyone" -> "Everyone"
    "glowers" -> "Glowers"
    "group" -> "Group Stories"
    "custom" -> "Custom"
    else -> "My Chats"
}

private fun average(v: Double): String = if (v < 10) String.format(Locale.getDefault(), "%.1f", v) else GlowRules.countShort(Math.round(v).toInt())
private fun amount(v: Double): String = if (v == Math.rint(v)) GlowRules.countShort(v.toInt()) else average(v)

private fun signed(v: Double): String {
    val n = Math.round(v).toInt()
    if (n == 0) return "0"
    return (if (n > 0) "+" else "−") + GlowRules.countShort(kotlin.math.abs(n))
}

private fun percentText(d: InsightsDelta, secondary: Color): Pair<String, Color> {
    val p = d.percent ?: run {
        val ch = d.change
        return if (ch != null && ch != 0.0) signed(ch) to (if (ch > 0) Green else secondary) else "–" to secondary
    }
    if (kotlin.math.abs(p) < 0.0005) return "0.0%" to secondary
    return String.format(Locale.getDefault(), "%+.1f%%", p * 100) to (if (p > 0) Green else secondary)
}

private fun deltaText(d: InsightsDelta, secondary: Color, red: Color): Pair<String, Color>? {
    val ch = d.change ?: return null
    if (ch == 0.0) return "No change" to secondary
    var t = (if (ch > 0) "+" else "−") + amount(kotlin.math.abs(ch))
    d.percent?.let { t += String.format(Locale.getDefault(), " (%.0f%%)", kotlin.math.abs(it) * 100) }
    return t to (if (ch > 0) Green else red)
}
