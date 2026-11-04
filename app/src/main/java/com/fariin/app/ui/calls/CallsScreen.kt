package com.fariin.app.ui.calls

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import com.fariin.app.ui.components.listPress
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Delete
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
import androidx.compose.foundation.border
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
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
import kotlinx.coroutines.launch
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
 * Long-press a row to select (iOS Edit / Select): a tap then ticks rows, the top bar says
 * "N Selected" with Delete, and system back leaves. A deleted call is hidden on this phone only
 * (iOS HiddenCalls), a whole folded run at once; a deleted link goes as iOS CallLinks.delete does.
 *
 * TODO: the live row of a call in progress, ad-hoc group calls.
 */
@Composable
fun CallsTab(me: String, conversations: List<Conversation>, demoCalls: List<CallEntry>?, shared: List<CallEntry>? = null) {
    // The shell already reads the history for the missed badge: shown as is, not read twice.
    if (demoCalls == null && shared != null) { CallsScreen(me, shared, true, conversations); return }
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
    CallsScreen(me, calls, loaded, conversations)
}

@Composable
private fun CallsScreen(me: String, calls: List<CallEntry>, loaded: Boolean, groups: List<Conversation> = emptyList()) {
    val c = Fariin.colors
    var missedOnly by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // A second tap on Calls: to the top.
    com.fariin.app.ui.shell.OnTabReselect { listState.animateScrollToItem(0) }
    val now = System.currentTimeMillis()
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val q = search.trim().lowercase()
    LaunchedEffect(Unit) { com.fariin.app.data.HiddenCalls.load(ctx) }
    val hidden by com.fariin.app.data.HiddenCalls.ids.collectAsState()
    val shown = CallHistory.filter(calls.filter { it.id !in hidden }, me, missedOnly, now).filter { q.isEmpty() || it.name.lowercase().contains(q) }
    val runs = CallHistory.runs(shown, me, now)
    val nav = com.fariin.app.ui.people.LocalPeopleNav.current
    LaunchedEffect(me) { if (me.isNotEmpty() && com.google.firebase.auth.FirebaseAuth.getInstance().currentUser != null) com.fariin.app.call.CallLinks.watch(me) }
    val allLinks by com.fariin.app.call.CallLinks.links.collectAsState()
    val links = if (missedOnly) emptyList() else allLinks.filter { q.isEmpty() || it.title.lowercase().contains(q) }
    val rows: List<Any> = (links.map { it.createdAtMs to (it as Any) } + runs.map { it.latest.dateMs to (it as Any) })
        .sortedByDescending { it.first }.map { it.second }
    // iOS LiveGroupCalls: the calls going in my groups, above the history (not under Missed or a search).
    val groupKey = groups.filter { it.isGroup }.joinToString(",") { it.id }
    LaunchedEffect(groupKey) { com.fariin.app.ui.call.LiveGroupCalls.sync(groups) }
    val liveAll by com.fariin.app.ui.call.LiveGroupCalls.calls.collectAsState()
    val live = if (missedOnly || q.isNotEmpty()) emptyList() else liveAll
    // Select mode: run ids (a run's newest call) and "link:<roomId>" for links, as iOS tags them.
    var picked by remember { mutableStateOf(setOf<String>()) }
    var askDelete by remember { mutableStateOf(false) }
    var linkFailed by remember { mutableStateOf(false) }
    val selecting = picked.isNotEmpty()
    androidx.activity.compose.BackHandler(enabled = selecting) { picked = emptySet() }
    fun toggle(id: String) { picked = if (id in picked) picked - id else picked + id }
    fun deletePicked() {
        val ids = mutableSetOf<String>()
        val gone = mutableListOf<com.fariin.app.call.SavedCallLink>()
        for (id in picked) {
            if (id.startsWith("link:")) allLinks.firstOrNull { "link:" + it.roomId == id }?.let { gone += it }
            // A run that regrouped under the pick: its newest id is still a real record (iOS).
            else ids += runs.firstOrNull { it.id == id }?.entries?.map { it.id } ?: listOf(id)
        }
        com.fariin.app.data.HiddenCalls.hide(ctx, ids)
        picked = emptySet()
        if (gone.isNotEmpty()) scope.launch {
            var failed = false
            for (l in gone) try { com.fariin.app.call.CallLinks.delete(l) } catch (_: Exception) { failed = true }
            if (failed) linkFailed = true
        }
    }
    if (askDelete) {
        val links = picked.count { it.startsWith("link:") }
        androidx.compose.material3.AlertDialog(onDismissRequest = { askDelete = false }, containerColor = c.card,
            title = { Text(com.fariin.app.data.HiddenCalls.deleteTitle(picked.size - links, links)) },
            confirmButton = { androidx.compose.material3.TextButton({ askDelete = false; deletePicked() }) { Text("Delete", color = c.red) } },
            dismissButton = { androidx.compose.material3.TextButton({ askDelete = false }) { Text("Cancel", color = c.accent) } })
    }
    if (linkFailed) androidx.compose.material3.AlertDialog(onDismissRequest = { linkFailed = false }, containerColor = c.card,
        text = { Text("Couldn't delete the call link. Check your connection and try again.") },
        confirmButton = { androidx.compose.material3.TextButton({ linkFailed = false }) { Text("OK", color = c.accent) } })
    var info by remember { mutableStateOf<CallRun?>(null) }
    info?.let { r ->
        CallInfoScreen(r, me, onBack = { info = null }, onCall = { video -> callBack(ctx, r.latest, video) })
        return
    }

    // The bar, the search capsule, All / Missed as a floating segmented pill (iOS MainShell Calls'
    // picker), New Call as a floating blue circle.
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
        if (selecting) com.fariin.app.ui.components.FloatingTopBar("${picked.size} Selected", onBack = { picked = emptySet() }) {
            com.fariin.app.ui.components.FloatingIcon(Icons.Outlined.Delete, contentDescription = "Delete", onClick = { askDelete = true }, tint = c.red)
        } else com.fariin.app.ui.components.FloatingTopBar("Calls")
        SearchField(search, { search = it }, "Search calls")
        com.fariin.app.ui.components.FloatingSegments(listOf("All", "Missed"), if (missedOnly) 1 else 0,
            onSelect = { missedOnly = it == 1 }, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp))
        when {
            // iOS CallListSkeleton while the history is read.
            !loaded -> com.fariin.app.ui.components.CallListSkeleton()
            calls.isEmpty() && allLinks.isEmpty() && live.isEmpty() -> Empty("No Calls Yet", "Your call history will appear here.")
            rows.isEmpty() && q.isNotEmpty() -> Empty("No Results for \"${search.trim()}\"", "Check the spelling or try a new search.")
            rows.isEmpty() && live.isEmpty() -> Empty("No Missed Calls", "Missed calls will appear here.")
            else -> LazyColumn(Modifier.fillMaxSize(), state = listState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = com.fariin.app.ui.shell.bottomBarInset() + 72.dp)) {
                if (!selecting) items(live, key = { "live:" + it.cid }) { l -> com.fariin.app.ui.call.LiveGroupCallRow(l, groups.firstOrNull { it.id == l.cid }) }

                items(rows, key = { r -> if (r is com.fariin.app.call.SavedCallLink) "link:" + r.roomId else (r as CallRun).id }) { r ->
                    val tag = if (r is com.fariin.app.call.SavedCallLink) "link:" + r.roomId else (r as CallRun).id
                    PickableRow(selecting, tag in picked, onToggle = { toggle(tag) }) {
                        if (r is com.fariin.app.call.SavedCallLink) {
                            CallLinkRow(r, callTimeLabel(r.createdAtMs, DateFormat.is24HourFormat(ctx), now),
                                onOpen = { if (com.fariin.app.call.CallManager.ui.value.status.live) CallActivity.startRing(ctx) else nav.joinLink(r.key, true) },
                                onInfo = { nav.callLink(r.roomId) }, onLongClick = { picked = setOf(tag) })
                        } else {
                            val run = r as CallRun
                            CallRow(run, me, now, onInfo = { info = run }, onLongClick = { picked = setOf(tag) })
                        }
                    }
                }
            }
        }
    }
    if (!selecting) com.fariin.app.ui.components.FloatingIcon(painterResource(R.drawable.ic_new_call), contentDescription = "New Call",
        onClick = { nav.newCall() }, tint = c.onAccent, fill = c.accent,
        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 16.dp + com.fariin.app.ui.shell.bottomBarInset()).size(58.dp))
    }
}

@Composable
internal fun SearchField(value: String, onChange: (String) -> Unit, prompt: String) {
    // The floating search capsule every tab page shares.
    com.fariin.app.ui.components.FloatingSearchField(value, onChange, prompt,
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 10.dp))
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

/**
 * A row in select mode (iOS edit mode): the row's own buttons stop answering, a tap ticks it,
 * and a picked row is tinted with a tick at its end.
 */
@Composable
private fun PickableRow(selecting: Boolean, picked: Boolean, onToggle: () -> Unit, content: @Composable () -> Unit) {
    val c = Fariin.colors
    Box(Modifier.background(if (picked) c.blue.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent)) {
        content()
        // A small tick on the photo's lower right, as the chat list marks a chosen row: the photo,
        // the time and the (i) all stay in view (owner 2026-11-03: the box covered the photo).
        val under = if (picked) c.blue.copy(alpha = 0.12f).compositeOver(c.bg) else c.bg
        if (selecting) Box(Modifier.matchParentSize().clickable(onClick = onToggle)) {
            Box(Modifier.align(Alignment.CenterStart).padding(start = 46.dp, top = 30.dp).size(22.dp).clip(androidx.compose.foundation.shape.CircleShape)
                .background(under).padding(2.dp).clip(androidx.compose.foundation.shape.CircleShape)
                .background(if (picked) c.blue else c.bg)
                .then(if (picked) Modifier else Modifier.border(1.5.dp, c.secondary, androidx.compose.foundation.shape.CircleShape)),
                contentAlignment = Alignment.Center) {
                if (picked) Icon(Icons.Filled.Check, contentDescription = "Selected", tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(14.dp))
            }
        }
    }
}

/** iOS CallHistoryRow: avatar 46, name (red when I missed it), arrow + direction, time, (i). */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun CallRow(run: CallRun, me: String, now: Long, onInfo: () -> Unit, onLongClick: () -> Unit) {
    val c = Fariin.colors
    val call = run.latest
    val count = run.entries.size
    val ctx = LocalContext.current
    Column {
        Row(
            Modifier.fillMaxWidth().listPress(onLongClick = onLongClick) { callBack(ctx, call, call.video) }.padding(start = 16.dp, end = 4.dp, top = 9.dp, bottom = 9.dp),
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
            Box(Modifier.size(44.dp).clip(androidx.compose.foundation.shape.CircleShape).clickable(onClick = onInfo), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Info, contentDescription = "Info", tint = c.label, modifier = Modifier.size(22.dp))
            }
        }
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
