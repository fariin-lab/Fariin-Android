package com.fariin.app.ui.people

import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.data.AuthRepository
import com.fariin.app.data.GroupStats
import com.fariin.app.ui.components.Avatar
import com.fariin.app.ui.components.FariinTopBar
import com.fariin.app.ui.components.SkeletonBlock
import com.fariin.app.ui.components.SkeletonCircle
import com.fariin.app.ui.theme.Fariin
import coil3.compose.AsyncImage
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max

/*
 * Group Statistics (Kulan-iOS GroupStatsView, the group page): pushed from Group Info's Statistics
 * row for the owner and admins of a big enough group. Overview of four figures against the week
 * before, day charts for 30 days (touch and drag picks a day), the busiest hours and weekdays in the
 * phone's time zone, the members' languages and three top-10 lists. One `groupStats` read, counts only.
 * Charts are drawn on a Canvas with the iOS chart sizes and colours.
 */

/** The iOS chart colours (Color.chartBlue and the system teal / orange / purple ...), light and dark. */
private object ChartColors {
    val blue = Color(0xFF2392FE)
    fun teal(dark: Boolean) = if (dark) Color(0xFF40C8E0) else Color(0xFF30B0C7)
    fun orange(dark: Boolean) = if (dark) Color(0xFFFF9F0A) else Color(0xFFFF9500)
    fun purple(dark: Boolean) = if (dark) Color(0xFFBF5AF2) else Color(0xFFAF52DE)
    fun green(dark: Boolean) = if (dark) Color(0xFF30D158) else Color(0xFF34C759)
    fun red(dark: Boolean) = if (dark) Color(0xFFFF453A) else Color(0xFFFF3B30)
    /** GroupStatsLanguage.palette: chartBlue, teal, orange, purple, pink, green, yellow, indigo, mint, brown, gray. */
    fun palette(dark: Boolean) = listOf(blue, teal(dark), orange(dark), purple(dark),
        if (dark) Color(0xFFFF375F) else Color(0xFFFF2D55), green(dark), if (dark) Color(0xFFFFD60A) else Color(0xFFFFCC00),
        if (dark) Color(0xFF5E5CE6) else Color(0xFF5856D6), if (dark) Color(0xFF63E6E2) else Color(0xFF00C7BE),
        if (dark) Color(0xFFAC8E68) else Color(0xFFA2845E), Color(0xFF8E8E93))
}

private val StatsCardShape = RoundedCornerShape(22.dp)

@Composable
internal fun GroupStatsPage(cid: String, title: String, onBack: () -> Unit) {
    val c = Fariin.colors
    val scope = rememberCoroutineScope()
    var stats by remember(cid) { mutableStateOf<GroupStats.Data?>(null) }
    var failure by remember(cid) { mutableStateOf<String?>(null) }
    var retry by remember(cid) { mutableStateOf(true) }
    val isChannel = remember(cid) { com.fariin.app.data.ChatsNow.find(cid)?.isChannel == true }
    suspend fun load() {
        try {
            val r = FirebaseFunctions.getInstance(AuthRepository.FUNCTIONS_REGION).getHttpsCallable("groupStats")
                .call(mapOf("cid" to cid)).await()
            stats = GroupStats.parse(r.getData() as? Map<*, *> ?: emptyMap<String, Any>())
            failure = null
        } catch (e: Exception) {
            val code = (e as? FirebaseFunctionsException)?.code?.name
            failure = GroupStats.failure(code, isChannel)
            retry = GroupStats.canRetry(code)
        }
    }
    LaunchedEffect(cid) { if (stats == null) load() }
    com.fariin.app.ui.components.GlassColumnPage(false, Modifier.background(c.bgSecondary), bar = {
        Box(Modifier.fillMaxWidth().background(c.bgSecondary).statusBarsPadding()) {
            FariinTopBar("Statistics", onBack = onBack, subtitle = title, container = c.bgSecondary)
        }
    }) { top ->
        androidx.compose.foundation.layout.Spacer(Modifier.height(top))
        val s = stats
        val f = failure
        when {
            s != null -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) { StatsBody(s) }
            f != null -> Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(f, fontSize = 17.sp, color = c.secondary, textAlign = TextAlign.Center)
                if (retry) Text("Retry", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.onAccent,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(c.accent)
                        .clickable { failure = null; scope.launch { load() } }.padding(horizontal = 14.dp, vertical = 7.dp))
            }
            // The page's own shape while it loads, not a spinner.
            else -> StatsSkeleton()
        }
    }
}

@Composable
private fun StatsBody(s: GroupStats.Data) {
    val c = Fariin.colors
    val dark = c.dark
    val locale = Locale.getDefault()
    val monthDay = remember(locale) { DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "MMMd"), locale) }
    val dayText: (LocalDate) -> String = { it.format(monthDay) }
    OverviewCard(s, DateFormat.getBestDateTimePattern(locale, "MMMd"))
    val blue = ChartColors.blue
    fun sr(name: String, v: List<Int>) = GroupStats.Series(name, v)
    DayChart("Growth", true, listOf(sr("Members", s.growth)), listOf(blue), s.days, dayText)
    DayChart("Members", false, listOf(sr("Joined", s.joined), sr("Left", s.left)), listOf(blue, ChartColors.orange(dark)), s.days, dayText)
    DayChart("New Members by Source", false,
        listOf(sr("Link", s.srcLink), sr("Public", s.srcPublic), sr("Added", s.srcAdded), sr("Request", s.srcRequest)),
        listOf(blue, ChartColors.teal(dark), ChartColors.orange(dark), ChartColors.purple(dark)), s.days, dayText)
    DayChart("Messages", false, listOf(sr("Messages", s.messages)), listOf(blue), s.days, dayText)
    DayChart("Actions", false, listOf(sr("Deleted", s.deleted), sr("Removed", s.banned)), listOf(blue, ChartColors.orange(dark)), s.days, dayText)
    BinsCard("Top Hours", s.hours, setOf("0", "6", "12", "18"), GroupStats.busiestHour(s.hours))
    BinsCard("Weekdays", s.weekdays, s.weekdays.map { it.label }.toSet(), GroupStats.busiestDay(s.weekdays))
    if (s.languages.isNotEmpty()) LanguagesCard(s.languages)
    if (s.topPosters.isNotEmpty()) PeopleCard("Top Members", s.topPosters)
    if (s.topAdmins.isNotEmpty()) PeopleCard("Top Admins", s.topAdmins)
    if (s.topInviters.isNotEmpty()) PeopleCard("Top Inviters", s.topInviters)
    if (s.recentPosts.isNotEmpty()) PostsCard("Recent Posts", s.recentPosts, dayText)
    s.sinceMs?.let { ms ->
        val since = java.time.Instant.ofEpochMilli(ms.toLong()).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        Text("Counting started ${since.format(DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "MMMdyyyy"), locale))}",
            fontSize = 13.sp, color = c.secondary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
    }
}

/** A rounded card with a title, the page's one container (iOS GroupStatsCard). */
@Composable
private fun StatsCard(title: String, trailing: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = Fariin.colors
    Column(Modifier.fillMaxWidth().clip(StatsCardShape).background(c.card).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label, modifier = Modifier.weight(1f))
            if (trailing != null) Text(trailing, fontSize = 13.sp, color = c.secondary, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
        }
        content()
    }
}

@Composable
private fun OverviewCard(s: GroupStats.Data, pattern: String) {
    StatsCard("Overview", GroupStats.period(s.nowMs, pattern = pattern)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Tile("Members", s.members, Modifier.weight(1f)); Tile("Messages", s.messagesMetric, Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Tile("Viewing Members", s.viewers, Modifier.weight(1f)); Tile("Posting Members", s.posters, Modifier.weight(1f))
        }
    }
}

/** One Overview figure: the number, its change against the week before, and its name. */
@Composable
private fun Tile(label: String, m: GroupStats.Metric, modifier: Modifier) {
    val c = Fariin.colors
    val ch = GroupStats.change(m.value.toDouble(), m.prev?.toDouble())
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(GroupStats.number().format(m.value), fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = c.label,
            style = TextStyle(fontFeatureSettings = "tnum"))
        if (ch != null) Text(ch.first, fontSize = 12.sp, fontWeight = FontWeight.Medium,
            color = when (ch.second) { 1 -> ChartColors.green(c.dark); -1 -> ChartColors.red(c.dark); else -> c.secondary })
        Text(label, fontSize = 13.sp, color = c.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Thirty days as a line or stacked bars. Touch and drag picks a day and the caption shows its values. */
@Composable
private fun DayChart(title: String, line: Boolean, series: List<GroupStats.Series>, colors: List<Color>, days: List<LocalDate>,
                     dayText: (LocalDate) -> String) {
    val c = Fariin.colors
    var selected by remember(days) { mutableStateOf<Int?>(null) }
    val measurer = rememberTextMeasurer()
    val axisStyle = TextStyle(fontSize = 10.sp, color = c.secondary)
    val n = days.size
    StatsCard(title) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(GroupStats.caption(series, days, selected, line, dayText), fontSize = 13.sp, color = c.secondary, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            val chartHeight = if (series.size > 1) 200.dp else 180.dp
            val legend = series.size > 1
            Column {
                Canvas(Modifier.fillMaxWidth().height(if (legend) chartHeight - 24.dp else chartHeight)
                    .semantics { contentDescription = "$title chart" }
                    .pointerInput(n) {
                        detectTapGestures(onPress = { p ->
                            selected = pick(p.x, size.width - 36.dp.toPx(), n)
                            tryAwaitRelease(); selected = null
                        })
                    }
                    .pointerInput(n) {
                        detectDragGesturesAfterLongPress(onDragEnd = { selected = null }, onDragCancel = { selected = null }) { ch, _ ->
                            selected = pick(ch.position.x, size.width - 36.dp.toPx(), n)
                        }
                    }) {
                    if (n == 0) return@Canvas
                    val yAxisW = 36.dp.toPx()
                    val xAxisH = 18.dp.toPx()
                    val w = size.width - yAxisW
                    val h = size.height - xAxisH
                    val totals = (0 until n).map { i -> series.sumOf { it.values.getOrElse(i) { 0 } } }
                    val all = series.flatMap { it.values }
                    val lo = if (line) (all.minOrNull() ?: 0) else 0
                    val hi = if (line) (all.maxOrNull() ?: 0) else (totals.maxOrNull() ?: 0)
                    val (yMin, yMax, step) = niceScale(lo, hi, line)
                    fun y(v: Double) = (h - (v - yMin) / (yMax - yMin) * h).toFloat()
                    // Grid and the value labels at the trailing side.
                    var t = yMin
                    while (t <= yMax + 1e-6) {
                        drawLine(c.separator, Offset(0f, y(t)), Offset(w, y(t)), 0.5.dp.toPx())
                        val lay = measurer.measure(GroupStats.number().format(t.toLong()), axisStyle)
                        drawText(lay, topLeft = Offset(w + 6.dp.toPx(), y(t) - lay.size.height / 2f))
                        t += step
                    }
                    val slot = w / n
                    // A day line every 7 days, from the last day back, with its date.
                    for (i in (n - 1) downTo 0 step 7) {
                        val x = slot * i + slot / 2
                        drawLine(c.separator, Offset(x, 0f), Offset(x, h), 0.5.dp.toPx())
                        val lay = measurer.measure(dayText(days[i]), axisStyle)
                        drawText(lay, topLeft = Offset((x - lay.size.width / 2f).coerceIn(0f, w - lay.size.width), h + 4.dp.toPx()))
                    }
                    selected?.let { i ->
                        val x = slot * i + slot / 2
                        drawRect(c.secondary.copy(alpha = 0.35f), Offset(x - 0.5.dp.toPx(), 0f), Size(1.dp.toPx(), h))
                    }
                    if (line) series.forEachIndexed { si, s ->
                        val pts = (0 until n).map { i -> Offset(slot * i + slot / 2, y(s.values.getOrElse(i) { 0 }.toDouble())) }
                        drawPath(smooth(pts), colors.getOrElse(si) { ChartColors.blue }, style = Stroke(2.dp.toPx()))
                    } else for (i in 0 until n) {
                        var base = 0.0
                        val bw = slot * 0.7f
                        series.forEachIndexed { si, s ->
                            val v = s.values.getOrElse(i) { 0 }
                            if (v > 0) {
                                val top = y(base + v); val bottom = y(base)
                                drawRoundRect(colors.getOrElse(si) { ChartColors.blue }, Offset(slot * i + (slot - bw) / 2, top),
                                    Size(bw, bottom - top), CornerRadius(2.dp.toPx()))
                                base += v
                            }
                        }
                    }
                }
                if (legend) Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    series.forEachIndexed { i, s ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(colors.getOrElse(i) { ChartColors.blue }))
                            Spacer(Modifier.width(4.dp))
                            Text(s.name, fontSize = 12.sp, color = c.secondary)
                        }
                    }
                }
            }
        }
    }
}

/** The day under the finger; [plotWidth] is the chart without its value labels. */
private fun pick(x: Float, plotWidth: Float, n: Int): Int? {
    if (n == 0 || plotWidth <= 0f) return null
    return ((x / plotWidth) * n).toInt().coerceIn(0, n - 1)
}

/** Round bounds and a step for 3 to 5 grid lines; a line chart does not have to start at zero. */
private fun niceScale(lo: Int, hi: Int, line: Boolean): Triple<Double, Double, Double> {
    var min = lo.toDouble(); var max = hi.toDouble()
    if (max <= min) { max = min + if (line) 2.0 else 4.0; if (!line) min = 0.0 }
    val raw = (max - min) / 4
    val mag = Math.pow(10.0, kotlin.math.floor(kotlin.math.log10(raw)))
    val step = listOf(1.0, 2.0, 5.0, 10.0).map { it * mag }.first { it >= raw }
    val start = kotlin.math.floor(min / step) * step
    val end = kotlin.math.ceil(max / step) * step
    return Triple(if (line) start else 0.0, max(end, start + step), step)
}

/** A smooth line through the points that never overshoots them (horizontal tangents at each point). */
private fun smooth(p: List<Offset>): Path = Path().apply {
    if (p.isEmpty()) return@apply
    moveTo(p[0].x, p[0].y)
    for (i in 1 until p.size) {
        val a = p[i - 1]; val b = p[i]; val mx = (a.x + b.x) / 2
        cubicTo(mx, a.y, mx, b.y, b.x, b.y)
    }
}

/** Bars over fixed labels: the 24 hours or the 7 weekdays, in local time (iOS GroupStatsBinsCard). */
@Composable
private fun BinsCard(title: String, bins: List<GroupStats.Bin>, axis: Set<String>, caption: String) {
    val c = Fariin.colors
    val measurer = rememberTextMeasurer()
    val axisStyle = TextStyle(fontSize = 10.sp, color = c.secondary)
    StatsCard(title) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(caption, fontSize = 13.sp, color = c.secondary)
            Canvas(Modifier.fillMaxWidth().height(160.dp).semantics { contentDescription = "$title chart" }) {
                if (bins.isEmpty()) return@Canvas
                val yAxisW = 36.dp.toPx(); val xAxisH = 18.dp.toPx()
                val w = size.width - yAxisW; val h = size.height - xAxisH
                val (_, yMax, step) = niceScale(0, bins.maxOf { it.value }, false)
                fun y(v: Double) = (h - v / yMax * h).toFloat()
                var t = 0.0
                while (t <= yMax + 1e-6) {
                    drawLine(c.separator, Offset(0f, y(t)), Offset(w, y(t)), 0.5.dp.toPx())
                    val lay = measurer.measure(GroupStats.number().format(t.toLong()), axisStyle)
                    drawText(lay, topLeft = Offset(w + 6.dp.toPx(), y(t) - lay.size.height / 2f))
                    t += step
                }
                val slot = w / bins.size; val bw = slot * 0.7f
                bins.forEachIndexed { i, b ->
                    val x = slot * i + (slot - bw) / 2
                    if (b.value > 0) {
                        val top = y(b.value.toDouble())
                        drawRoundRect(Brush.verticalGradient(listOf(ChartColors.blue, ChartColors.blue.copy(alpha = 0.75f)), top, h),
                            Offset(x, top), Size(bw, h - top), CornerRadius(2.dp.toPx()))
                    }
                    if (b.label in axis) {
                        val lay = measurer.measure(b.label, axisStyle)
                        drawText(lay, topLeft = Offset((x + bw / 2 - lay.size.width / 2f).coerceIn(0f, w - lay.size.width), h + 4.dp.toPx()))
                    }
                }
            }
        }
    }
}

/** The members' phone languages as a ring, with the legend beside it (iOS GroupStatsLanguagesCard). */
@Composable
private fun LanguagesCard(langs: List<GroupStats.Language>) {
    val c = Fariin.colors
    val palette = ChartColors.palette(c.dark)
    StatsCard("Languages") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Canvas(Modifier.size(130.dp).semantics { contentDescription = "Languages chart" }) {
                val total = max(1, langs.sumOf { it.count }).toFloat()
                val ring = size.minDimension / 2 * (1 - 0.62f)
                val inset = Offset(ring / 2, ring / 2)
                val arc = Size(size.width - ring, size.height - ring)
                var start = -90f
                langs.forEach { l ->
                    val sweep = l.count / total * 360f
                    val gap = if (langs.size > 1) 1.5f else 0f
                    drawArc(palette[l.index % palette.size], start + gap / 2, (sweep - gap).coerceAtLeast(0.1f), false, inset, arc,
                        style = Stroke(ring))
                    start += sweep
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                langs.forEach { l ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(palette[l.index % palette.size]))
                        Spacer(Modifier.width(8.dp))
                        Text(GroupStats.languageName(l.code), fontSize = 15.sp, color = c.label, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text("${GroupStats.percent(l.count, langs)}%", fontSize = 15.sp, color = c.secondary,
                            style = TextStyle(fontFeatureSettings = "tnum"), modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }
        }
    }
}

/** A top-10 list: face, name, and what they did (iOS GroupStatsPeopleCard). */
@Composable
private fun PeopleCard(title: String, people: List<GroupStats.Person>) {
    val c = Fariin.colors
    StatsCard(title) {
        Column {
            people.forEachIndexed { i, p ->
                if (i > 0) HorizontalDivider(thickness = 0.5.dp, color = c.separator, modifier = Modifier.padding(start = 48.dp))
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Avatar(p.photoUrl, 36.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(p.name.ifEmpty { "Member" }, fontSize = 17.sp, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(p.detail, fontSize = 13.sp, color = c.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/**
 * A channel's Recent Posts (iOS GroupStatsPostsCard): the people list's rows, with the post's picture
 * (or a plain mark) where the face would be, and what it got.
 */
@Composable
private fun PostsCard(title: String, posts: List<GroupStats.Post>, dayText: (LocalDate) -> String) {
    val c = Fariin.colors
    val zone = java.time.ZoneId.systemDefault()
    StatsCard(title) {
        Column {
            posts.forEachIndexed { i, p ->
                if (i > 0) HorizontalDivider(thickness = 0.5.dp, color = c.separator, modifier = Modifier.padding(start = 48.dp))
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val shape = RoundedCornerShape(8.dp)
                    Box(Modifier.size(36.dp).clip(shape).background(c.separator.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                        if (p.thumb != null) AsyncImage(p.thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        else Text(if (p.isStory) "◌" else "≡", fontSize = 15.sp, color = c.secondary)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(GroupStats.postTitle(p), fontSize = 17.sp, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val day = p.atMs?.let { dayText(java.time.Instant.ofEpochMilli(it.toLong()).atZone(zone).toLocalDate()) }
                        Text(GroupStats.postDetail(p, day), fontSize = 13.sp, color = c.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/**
 * The loading page (iOS GroupStatsSkeleton): the overview's four tiles, a chart card and a list card
 * in the real cards' frames, so nothing jumps when the figures land.
 */
@Composable
private fun StatsSkeleton() {
    val c = Fariin.colors
    @Composable fun card(content: @Composable ColumnScope.() -> Unit) =
        Column(Modifier.fillMaxWidth().clip(StatsCardShape).background(c.card).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SkeletonBlock(110.dp, 16.dp); content()
        }
    @Composable fun tile(m: Modifier) = Column(m, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SkeletonBlock(64.dp, 22.dp); SkeletonBlock(96.dp, 11.dp)
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp).semantics { contentDescription = "Loading statistics" },
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        card {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { tile(Modifier.weight(1f)); tile(Modifier.weight(1f)) }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { tile(Modifier.weight(1f)); tile(Modifier.weight(1f)) }
        }
        card { Box(Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(12.dp)).background(c.placeholderFill)) }
        card {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                repeat(4) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SkeletonCircle(32.dp); SkeletonBlock(140.dp, 12.dp); Spacer(Modifier.weight(1f)); SkeletonBlock(36.dp, 12.dp)
                    }
                }
            }
        }
    }
}
