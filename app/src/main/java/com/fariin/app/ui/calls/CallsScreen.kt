package com.fariin.app.ui.calls

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SouthWest
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.R
import com.fariin.app.data.CallEntry
import com.fariin.app.data.CallHistory
import com.fariin.app.data.CallRun
import com.fariin.app.data.CallsRepository
import com.fariin.app.data.Conversation
import com.fariin.app.ui.call.CallActivity
import com.fariin.app.ui.call.CallInfoScreen
import com.fariin.app.ui.components.Avatar
import com.fariin.app.ui.theme.Fariin
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The Calls tab, as iOS CallsView: the All / Missed switch at the top, a large "Calls" title,
 * "Search calls", then the history with consecutive same-kind calls folded into "name (3)".
 * Missed calls from someone else are red. A row's tap calls back the same way (video stays
 * video; a group row joins or starts that group's call); (i) opens the call info page.
 *
 * My call links sit among the calls at the time they were made (iOS mergedRows), not under
 * Missed. The floating button opens New Call.
 *
 * TODO: Edit to delete calls, the live row of a call in progress, ad-hoc group calls.
 */
@Composable
fun CallsTab(me: String, conversations: List<Conversation>, demoCalls: List<CallEntry>?) {
    val repo = remember { CallsRepository() }
    var calls by remember { mutableStateOf(demoCalls ?: emptyList()) }
    var loaded by remember { mutableStateOf(demoCalls != null) }
    if (demoCalls == null) {
        // Re-read when a chat moves (a call ends with a new row in that chat).
        val key = conversations.joinToString(",") { it.id + ":" + it.updatedAtMillis }
        LaunchedEffect(me, key) {
            calls = repo.load(me, conversations)
            loaded = true
        }
    }
    CallsScreen(me, calls, loaded)
}

@Composable
private fun CallsScreen(me: String, calls: List<CallEntry>, loaded: Boolean) {
    val c = Fariin.colors
    var missedOnly by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    val now = System.currentTimeMillis()
    val q = search.trim().lowercase()
    val shown = CallHistory.filter(calls, me, missedOnly, now).filter { q.isEmpty() || it.name.lowercase().contains(q) }
    val runs = CallHistory.runs(shown, me, now)
    val ctx = LocalContext.current
    val nav = com.fariin.app.ui.people.LocalPeopleNav.current
    LaunchedEffect(me) { if (me.isNotEmpty() && com.google.firebase.auth.FirebaseAuth.getInstance().currentUser != null) com.fariin.app.call.CallLinks.watch(me) }
    val allLinks by com.fariin.app.call.CallLinks.links.collectAsState()
    val links = if (missedOnly) emptyList() else allLinks.filter { q.isEmpty() || it.title.lowercase().contains(q) }
    val rows: List<Any> = (links.map { it.createdAtMs to (it as Any) } + runs.map { it.latest.dateMs to (it as Any) })
        .sortedByDescending { it.first }.map { it.second }
    var info by remember { mutableStateOf<CallRun?>(null) }
    info?.let { r ->
        CallInfoScreen(r, me, onBack = { info = null }, onCall = { video -> callBack(ctx, r.latest, video) })
        return
    }

    // Android: top app bar, search, All / Missed as filter chips, New Call as a floating button.
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
        com.fariin.app.ui.components.FariinTopBar("Calls")
        SearchField(search, { search = it }, "Search calls")
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((label, value) in listOf("All" to false, "Missed" to true)) {
                androidx.compose.material3.FilterChip(
                    selected = missedOnly == value, onClick = { missedOnly = value },
                    label = { Text(label) },
                    colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                        selectedContainerColor = androidx.compose.material3.MaterialTheme.colorScheme.secondaryContainer),
                )
            }
        }
        when {
            !loaded -> Unit
            calls.isEmpty() && allLinks.isEmpty() -> Empty("No Calls Yet", "Your call history will appear here.")
            rows.isEmpty() && q.isNotEmpty() -> Empty("No Results for \"${search.trim()}\"", "Check the spelling or try a new search.")
            rows.isEmpty() -> Empty("No Missed Calls", "Missed calls will appear here.")
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(rows, key = { r -> if (r is com.fariin.app.call.SavedCallLink) "link:" + r.roomId else (r as CallRun).id }) { r ->
                    if (r is com.fariin.app.call.SavedCallLink) {
                        CallLinkRow(r, callTimeLabel(r.createdAtMs, DateFormat.is24HourFormat(ctx), now),
                            onOpen = { if (com.fariin.app.call.CallManager.ui.value.status.live) CallActivity.startRing(ctx) else nav.joinLink(r.key, true) },
                            onInfo = { nav.callLink(r.roomId) })
                    } else {
                        val run = r as CallRun
                        CallRow(run, me, now, onInfo = { info = run })
                    }
                }
            }
        }
    }
    androidx.compose.material3.FloatingActionButton(
        onClick = { nav.newCall() },
        containerColor = c.blue, contentColor = androidx.compose.ui.graphics.Color.White,
        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
    ) { Icon(painterResource(R.drawable.ic_new_call), contentDescription = "New Call", modifier = Modifier.size(24.dp)) }
    }
}

@Composable
internal fun SearchField(value: String, onChange: (String) -> Unit, prompt: String) {
    val c = Fariin.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp).height(40.dp)
            .clip(RoundedCornerShape(20.dp)).background(c.fieldFill).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = c.secondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(prompt, fontSize = 17.sp, color = c.secondary)
            BasicTextField(
                value, onChange, singleLine = true,
                textStyle = TextStyle(fontSize = 17.sp, color = c.label),
                cursorBrush = SolidColor(c.label),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun Empty(title: String, text: String) {
    val c = Fariin.colors
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 60.dp)) {
            Icon(Icons.Outlined.Phone, contentDescription = null, tint = c.secondary, modifier = Modifier.size(44.dp))
            Spacer(Modifier.height(12.dp))
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = c.label, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(text, fontSize = 15.sp, color = c.secondary, textAlign = TextAlign.Center)
        }
    }
}

/** iOS CallHistoryRow: avatar 46, name (red when I missed it), arrow + direction, time, (i). */
@Composable
private fun CallRow(run: CallRun, me: String, now: Long, onInfo: () -> Unit) {
    val c = Fariin.colors
    val call = run.latest
    val count = run.entries.size
    val ctx = LocalContext.current
    Column {
        Row(
            Modifier.fillMaxWidth().clickable { callBack(ctx, call, call.video) }.padding(start = 16.dp, end = 4.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(call.photoUrl, 46.dp, squircle = false)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (count > 1) "${call.name} ($count)" else call.name,
                    fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    color = if (call.missedIncoming(me, now)) c.red else c.label,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val mine = call.mine(me)
                    Icon(
                        if (mine) Icons.Filled.NorthEast else Icons.Filled.SouthWest,
                        contentDescription = null, tint = c.secondary, modifier = Modifier.size(12.dp),
                    )
                    if (call.video) {
                        Icon(Icons.Filled.Videocam, contentDescription = null, tint = c.secondary, modifier = Modifier.size(14.dp))
                    }
                    Spacer(Modifier.width(4.dp))
                    val dir = CallHistory.directionText(call, me, now)
                    val dur = CallHistory.durationText(call, count, now)
                    Text(if (dur != null) "$dir ($dur)" else dir, fontSize = 14.sp, color = c.secondary, maxLines = 1)
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(callTimeLabel(call.dateMs, DateFormat.is24HourFormat(ctx), now), fontSize = 14.sp, color = c.secondary)
            Box(Modifier.size(44.dp).clickable(onClick = onInfo), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Info, contentDescription = "Info", tint = c.label, modifier = Modifier.size(22.dp))
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = c.separator, modifier = Modifier.padding(start = 74.dp))
    }
}

/**
 * Call back from a row (iOS onCall): a call already on comes forward instead; a group row
 * joins or starts that group's call; a person's row dials them.
 */
private fun callBack(ctx: android.content.Context, call: CallEntry, video: Boolean) {
    if (com.fariin.app.call.CallManager.ui.value.status.live) { CallActivity.startRing(ctx); return }
    val g = call.groupCid
    if (g != null) { CallActivity.startGroup(ctx, g, call.name, video, start = true); return }
    if (call.otherUid.isEmpty()) return
    CallActivity.startDial(ctx, call.otherUid, call.name, call.photoUrl, video)
}

/** Today: the time. Yesterday. This week: the weekday. Older: "Oct 3" (iOS timeLabel). */
internal fun callTimeLabel(ms: Long, is24h: Boolean, nowMs: Long = System.currentTimeMillis()): String {
    val d = Calendar.getInstance().apply { timeInMillis = ms }
    val today = Calendar.getInstance().apply {
        timeInMillis = nowMs; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    val startOfD = (d.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    val days = Math.round((today.timeInMillis - startOfD.timeInMillis) / 86_400_000.0).toInt()
    val loc = Locale.getDefault()
    return when {
        days <= 0 -> SimpleDateFormat(if (is24h) "HH:mm" else "h:mm a", loc).format(Date(ms))
        days == 1 -> "Yesterday"
        days < 7 -> SimpleDateFormat("EEEE", loc).format(Date(ms))
        else -> SimpleDateFormat("MMM d", loc).format(Date(ms))
    }
}
