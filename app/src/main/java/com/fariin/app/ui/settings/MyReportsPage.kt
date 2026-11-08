package com.fariin.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.data.MyReport
import com.fariin.app.data.MyReports
import com.fariin.app.data.ReportSubs
import com.fariin.app.ui.components.Avatar
import com.fariin.app.ui.components.FariinSpinner
import com.fariin.app.ui.components.listPress
import com.fariin.app.ui.theme.Fariin
import kotlinx.coroutines.launch

private fun reportDate(ms: Long): String =
    if (ms <= 0L) "" else java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(ms))

/**
 * Settings > Privacy > My Reports (contract v2): the reports I sent, newest first, 30 at a time,
 * each with what it was about, "Reason › Sub-reason", the date and where it stands. A tap shows
 * the whole of one. Only I see these; the review result also arrives in the official Fariin chat.
 */
@Composable
internal fun MyReportsPage(pop: () -> Unit) {
    val c = Fariin.colors
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<MyReport>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<MyReport?>(null) }
    LaunchedEffect(Unit) {
        val page = MyReports.page()
        if (page == null) failed = true else { items = page; more = page.size >= MyReports.PAGE_SIZE }
    }
    open?.let { r ->
        BackHandler { open = null }
        ScrollPage("Report", pop = { open = null }) { ReportDetail(r) }
        return
    }
    ScrollPage("My Reports", pop) {
        val list = items
        when {
            failed && list == null -> SettingsSection {
                Text("Couldn't load your reports. Check your connection and try again.", fontSize = 15.sp, color = c.secondary,
                    modifier = Modifier.padding(16.dp))
            }
            list == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { FariinSpinner(size = 24.dp) }
            list.isEmpty() -> SettingsSection(footer = "When you report something, you can see here what happened to it.") {
                Text("No reports", fontSize = 17.sp, color = c.secondary, modifier = Modifier.padding(16.dp))
            }
            else -> {
                SettingsSection(footer = "Only you can see your reports. The people you report never know it was you.") {
                    list.forEachIndexed { i, r -> ReportRow(r, last = i == list.lastIndex) { open = r } }
                }
                if (more) SettingsSection {
                    Box(Modifier.fillMaxWidth().listPress(onClick = {
                        if (!loadingMore) {
                            loadingMore = true
                            scope.launch {
                                val next = MyReports.page(before = list.lastOrNull()?.createdAtMs)
                                if (next != null) {
                                    items = (list + next).distinctBy { it.id }
                                    more = next.size >= MyReports.PAGE_SIZE
                                } else com.fariin.app.ui.components.Snack.show("Couldn't load more. Try again.")
                                loadingMore = false
                            }
                        }
                    }).heightIn(min = 50.dp), contentAlignment = Alignment.Center) {
                        if (loadingMore) FariinSpinner(size = 20.dp) else Text("Show More", fontSize = 17.sp, color = c.blue)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusPill(raw: String) {
    val s = MyReports.status(raw)
    val green = s == MyReports.Status.ACTION_TAKEN
    val fill = if (green) Color(0xFF34C759).copy(alpha = 0.16f) else Fariin.colors.placeholderFill
    val ink = if (green) Color(0xFF248A3D) else Fariin.colors.secondary
    Text(s.label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = ink,
        modifier = Modifier.clip(CircleShape).background(fill).padding(horizontal = 10.dp, vertical = 4.dp))
}

@Composable
private fun ReportRow(r: MyReport, last: Boolean, onClick: () -> Unit) {
    val c = Fariin.colors
    Column {
        Row(Modifier.fillMaxWidth().listPress(onClick = onClick).heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (r.targetThumb != null) { Avatar(r.targetThumb, 40.dp); Spacer(Modifier.width(12.dp)) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(MyReports.what(r), fontSize = 17.sp, color = c.label, maxLines = 1)
                Text(ReportSubs.label(r.category, r.sub), fontSize = 14.sp, color = c.secondary, maxLines = 1)
                Text(reportDate(r.createdAtMs), fontSize = 13.sp, color = c.tertiary, maxLines = 1)
            }
            Spacer(Modifier.width(8.dp))
            StatusPill(r.status)
        }
        if (!last && SHOW_ROW_LINES) HorizontalDivider(thickness = 0.5.dp, color = c.separator)
    }
}

@Composable
private fun ReportDetail(r: MyReport) {
    val c = Fariin.colors
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (r.targetThumb != null) Avatar(r.targetThumb, 72.dp)
        Text(MyReports.what(r), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = c.label, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp, start = 20.dp, end = 20.dp))
        Box(Modifier.padding(top = 10.dp)) { StatusPill(r.status) }
    }
    SettingsSection {
        SettingsRow("Reason", value = ReportSubs.label(r.category, r.sub), trailing = Trailing.NONE)
        SettingsRow("Sent", value = reportDate(r.createdAtMs), trailing = Trailing.NONE, last = true)
    }
    Text(when (MyReports.status(r.status)) {
        MyReports.Status.ACTION_TAKEN -> "We reviewed it and it broke our rules, so we took action."
        MyReports.Status.NO_VIOLATION -> "We reviewed it and it didn't break our rules."
        MyReports.Status.RECEIVED -> "We have your report and will review it."
    }, fontSize = 13.sp, color = c.secondary, modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 4.dp))
}
