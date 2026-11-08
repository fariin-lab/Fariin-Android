package com.fariin.app.ui.calls

import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.outlined.Search
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.SouthWest
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.R
import com.fariin.app.call.CallPhase
import com.fariin.app.call.CallWire
import com.fariin.app.data.CallEntry
import com.fariin.app.data.CallHistory
import com.fariin.app.data.CallRun
import com.fariin.app.data.CallsRepository
import com.fariin.app.data.Conversation
import com.fariin.app.ui.call.CallActivity
import com.fariin.app.ui.call.CallInfoScreen
import com.fariin.app.ui.components.Avatar
import com.fariin.app.ui.components.floating
import com.fariin.app.ui.components.listPress
import com.fariin.app.ui.theme.Fariin
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The Calls tab, as iOS CallsView (MainShell): a top row with Edit, the All / Missed switch and
 * New Call; the large "Calls" title; "Search calls"; "Create a Call Link"; then the history with
 * consecutive same-kind calls folded into "name (3)". Missed calls from someone else are red. A
 * row's tap calls back the same way (video stays video; a group row joins or starts that group's
 * call); (i) opens the person's profile (a group's opens its chat).
 *
 * The 1:1 call that is up right now has its own row at the top, drawn from the call itself, with a
 * green Return pill in place of the time and the (i) ([CallsLive]).
 *
 * My call links sit among the calls at the time they were made (iOS mergedRows), not under Missed.
 *
 * Edit (or Select in a row's long-press menu) picks rows: a tap then ticks them, the bar says
 * "N Selected" with Delete, and the cross or system back leaves. Swipe a call left for Delete. A
 * deleted call is hidden on this phone only (iOS HiddenCalls), a whole folded run at once; a
 * deleted link goes as iOS CallLinks.delete does.
 *
 * TODO: ad-hoc group calls.
 */
@Composable
fun CallsTab(me: String, conversations: List<Conversation>, demoCalls: List<CallEntry>?, shared: List<CallEntry>? = null,
             error: String? = null, onRetry: () -> Unit = {},
             /** False while another tab is open (MainShell keeps this one alive behind it). */
             active: Boolean = true) {
    // The shell already reads the history for the missed badge: shown as is, not read twice.
    if (demoCalls == null && shared != null) { CallsScreen(me, shared, true, conversations, error, onRetry, active); return }
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
    CallsScreen(me, calls, loaded, conversations, active = active)
}

/**
 * The live row (iOS CallsView.liveEntry, owner 2026-10-14): while a 1:1 call I placed is calling,
 * or any 1:1 call is connected or reconnecting, its row is built from the call's own state, at the
 * top of All, from the moment of dialling. The stored copy of it (the ring row, "ringing" then
 * "ongoing") is left out meanwhile, and takes over again once the call ends. An incoming call that
 * is still ringing has no row yet. That row is the only one with Return.
 */
internal object CallsLive {
    const val ID = "call_live"

    /** What the live row needs from the call screen's state. */
    data class Call(val phase: CallPhase, val isCaller: Boolean, val video: Boolean,
                    val uid: String, val name: String, val photo: String?)

    fun entry(call: Call?, me: String, nowMs: Long): CallEntry? {
        if (call == null || call.uid.isEmpty() || me.isEmpty()) return null
        val up = (call.phase == CallPhase.OUTGOING && call.isCaller) ||
            call.phase == CallPhase.ACTIVE || call.phase == CallPhase.RECONNECTING
        if (!up) return null
        return CallEntry(ID, CallWire.convId(me, call.uid), call.name, call.photo, call.uid,
            callerUid = if (call.isCaller) me else call.uid,
            outcome = if (call.phase == CallPhase.OUTGOING) "ringing" else "ongoing",
            video = call.video, durationSec = 0, dateMs = nowMs)
    }

    /** The stored history with the live call's own unfinished record taken out. */
    fun withoutLive(stored: List<CallEntry>, live: CallEntry?): List<CallEntry> =
        if (live == null) stored
        else stored.filterNot { it.groupCid == null && it.otherUid == live.otherUid && (it.outcome == "ringing" || it.outcome == "ongoing") }

    /** The live call is always a run of its own, first; it is never under Missed. */
    fun runs(stored: List<CallRun>, live: CallEntry?, missedOnly: Boolean): List<CallRun> =
        if (live == null || missedOnly) stored else listOf(CallRun(listOf(live))) + stored

    /** Return shows on this run instead of the time and the (i). */
    fun showsReturn(run: CallRun): Boolean = run.id == ID
}

@Composable
private fun CallsScreen(me: String, calls: List<CallEntry>, loaded: Boolean, groups: List<Conversation> = emptyList(),
                        error: String? = null, onRetry: () -> Unit = {}, active: Boolean = true) {
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
    // The call that is up, read as the few fields the live row needs (not every mute or frame).
    val liveCall by remember {
        com.fariin.app.call.CallManager.ui.map { u ->
            CallsLive.Call(u.status.phase, u.status.isCaller, u.status.video, u.peerUid, u.peerName, u.peerPhoto)
        }.distinctUntilChanged()
    }.collectAsState(initial = null)
    val liveSince = remember(liveCall?.uid) { System.currentTimeMillis() }
    val liveEntry = CallsLive.entry(liveCall, me, liveSince)?.takeIf { q.isEmpty() || it.name.lowercase().contains(q) }
    val stored = CallsLive.withoutLive(calls.filter { it.id !in hidden }, liveEntry)
    val shown = CallHistory.filter(stored, me, missedOnly, now).filter { q.isEmpty() || it.name.lowercase().contains(q) }
    val runs = CallsLive.runs(CallHistory.runs(shown, me, now), liveEntry, missedOnly)
    val nav = com.fariin.app.ui.people.LocalPeopleNav.current
    // The people in the list, read ahead: their page opens with the bio already there, and the
    // verified mark beside their name comes from the same read.
    val warmCtx = androidx.compose.ui.platform.LocalContext.current
    var warmed by remember { mutableIntStateOf(0) }
    LaunchedEffect(runs.size) {
        com.fariin.app.ui.people.ProfileMemo.warm(
            com.fariin.app.data.FirestorePeople(me, com.fariin.app.data.CryptoHub.get(warmCtx.applicationContext)),
            runs.mapNotNull { r -> r.latest.otherUid.takeIf { it.isNotEmpty() && r.latest.groupCid == null } })
        warmed++
    }
    LaunchedEffect(me) { if (me.isNotEmpty() && com.google.firebase.auth.FirebaseAuth.getInstance().currentUser != null) com.fariin.app.call.CallLinks.watch(me) }
    val allLinks by com.fariin.app.call.CallLinks.links.collectAsState()
    val links = if (missedOnly) emptyList() else allLinks.filter { q.isEmpty() || it.title.lowercase().contains(q) }
    val rows: List<Any> = (links.map { it.createdAtMs to (it as Any) } + runs.map {
        // The live row is the newest thing on the page whatever the clock says.
        (if (CallsLive.showsReturn(it)) Long.MAX_VALUE else it.latest.dateMs) to (it as Any)
    }).sortedByDescending { it.first }.map { it.second }
    // iOS LiveGroupCalls: the calls going in my groups, above the history (not under Missed or a search).
    val groupKey = groups.filter { it.isGroup }.joinToString(",") { it.id }
    LaunchedEffect(groupKey, active) { if (active) com.fariin.app.ui.call.LiveGroupCalls.sync(groups) }
    // Up to 40 group-call listeners, only while this tab is shown (battery audit 2026-11-05): the
    // tab stays alive behind the others now, so they stop when it is hidden, not only when it goes.
    androidx.compose.runtime.DisposableEffect(active) { onDispose { com.fariin.app.ui.call.LiveGroupCalls.stopAll() } }
    val liveAll by com.fariin.app.ui.call.LiveGroupCalls.calls.collectAsState()
    val live = if (missedOnly || q.isNotEmpty()) emptyList() else liveAll
    // Select mode (iOS edit mode): run ids (a run's newest call) and "link:<roomId>" for links.
    var selecting by rememberSaveable { mutableStateOf(false) }
    var picked by remember { mutableStateOf(setOf<String>()) }
    var askDelete by remember { mutableStateOf(false) }
    var linkFailed by remember { mutableStateOf(false) }
    // The long-press menu's Voice / Video Call asks first (iOS pendingCall); the row's tap does not.
    var pendingCall by remember { mutableStateOf<Pair<CallEntry, Boolean>?>(null) }
    fun leaveSelect() { selecting = false; picked = emptySet() }
    // Hidden behind another tab, its dialogs (windows of their own) close, as when the tab went.
    LaunchedEffect(active) { if (!active) { askDelete = false; linkFailed = false; pendingCall = null } }
    androidx.activity.compose.BackHandler(enabled = active && selecting) { leaveSelect() }
    fun toggle(id: String) { picked = if (id in picked) picked - id else picked + id }
    fun deletePicked() {
        val ids = mutableSetOf<String>()
        val gone = mutableListOf<com.fariin.app.call.SavedCallLink>()
        for (id in picked) {
            if (id == CallsLive.ID) continue
            if (id.startsWith("link:")) allLinks.firstOrNull { "link:" + it.roomId == id }?.let { gone += it }
            // A run that regrouped under the pick: its newest id is still a real record (iOS).
            else ids += runs.firstOrNull { it.id == id }?.entries?.map { it.id } ?: listOf(id)
        }
        com.fariin.app.data.HiddenCalls.hide(ctx, ids)
        leaveSelect()
        if (gone.isNotEmpty()) scope.launch {
            var failed = false
            for (l in gone) try { com.fariin.app.call.CallLinks.delete(l) } catch (_: Exception) { failed = true }
            if (failed) linkFailed = true
        }
    }
    if (askDelete) {
        val linkCount = picked.count { it.startsWith("link:") }
        androidx.compose.material3.AlertDialog(onDismissRequest = { askDelete = false }, containerColor = c.card,
            title = { Text(com.fariin.app.data.HiddenCalls.deleteTitle(picked.size - linkCount, linkCount)) },
            confirmButton = { androidx.compose.material3.TextButton({ askDelete = false; deletePicked() }) { Text("Delete", color = c.red) } },
            dismissButton = { androidx.compose.material3.TextButton({ askDelete = false }) { Text("Cancel", color = c.accent) } })
    }
    if (linkFailed) androidx.compose.material3.AlertDialog(onDismissRequest = { linkFailed = false }, containerColor = c.card,
        text = { Text("Couldn't delete the call link. Check your connection and try again.") },
        confirmButton = { androidx.compose.material3.TextButton({ linkFailed = false }) { Text("OK", color = c.accent) } })
    pendingCall?.let { (call, video) ->
        androidx.compose.material3.AlertDialog(onDismissRequest = { pendingCall = null }, containerColor = c.card,
            title = { Text(if (video) "Video call" else "Voice call") },
            text = { Text("${if (video) "Video call" else "Call"} ${call.name}?") },
            confirmButton = { androidx.compose.material3.TextButton({ pendingCall = null; callBack(ctx, call, video) }) { Text("Call", color = c.accent) } },
            dismissButton = { androidx.compose.material3.TextButton({ pendingCall = null }) { Text("Cancel", color = c.accent) } })
    }
    var info by remember { mutableStateOf<CallRun?>(null) }
    info?.let { r ->
        CallInfoScreen(r, me, onBack = { info = null }, onCall = { video -> callBack(ctx, r.latest, video) })
        return
    }
    val hasHistory = calls.isNotEmpty() || allLinks.isNotEmpty() || liveEntry != null
    val callUp = liveCall?.let { it.phase != CallPhase.IDLE && it.phase != CallPhase.ENDED } == true
    fun openInfo(run: CallRun) {
        // iOS (owner 2026-10-28): a person's (i) opens their profile, a group call's opens the
        // group's chat; the Calls page has no separate call info page.
        val g = run.latest.groupCid
        if (g != null) nav.chat(g)
        else if (run.latest.otherUid.isNotEmpty()) {
            // The profile shows this call's card (iOS source .calls).
            com.fariin.app.data.CallInfoLink.set(run.latest.otherUid, run.latest)
            nav.contact(run.latest.otherUid)
        } else info = run
    }

    // Only the top row is the bar (glass over the list); the large title and the search field head
    // the list and scroll away with it, as iOS's large title does.
    val underBar by remember { androidx.compose.runtime.derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 } }
    val barScope = androidx.compose.runtime.rememberCoroutineScope()
    val searchGone by remember { androidx.compose.runtime.derivedStateOf {
        val info = listState.layoutInfo.visibleItemsInfo
        val field = info.firstOrNull { it.key == "search" }
        if (field == null) info.isNotEmpty() && info.first().index > 1 else field.offset + field.size * 0.1f <= 0
    } }
    Box(Modifier.fillMaxSize()) {
    com.fariin.app.ui.components.GlassBarPage(underBar, Modifier.background(c.bg), page = c.bg, barEstimate = 48.dp, frosted = true, bar = {
        // Frosted (GlassBarPage), so no background of its own; a bit shorter (owner 2026-11-08).
        Box(Modifier.fillMaxWidth().statusBarsPadding().height(48.dp).padding(horizontal = 12.dp)) {
            if (selecting) {
                com.fariin.app.ui.components.FloatingIcon(Icons.Filled.Close, contentDescription = "Done", onClick = { leaveSelect() },
                    modifier = Modifier.align(Alignment.CenterStart))
                Text(if (picked.isEmpty()) "Select Calls" else "${picked.size} Selected", fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    color = c.label, modifier = Modifier.align(Alignment.Center))
                com.fariin.app.ui.components.FloatingIcon(Icons.Outlined.Delete, contentDescription = "Delete",
                    onClick = { if (picked.isNotEmpty()) askDelete = true }, tint = if (picked.isEmpty()) c.secondary else c.red,
                    modifier = Modifier.align(Alignment.CenterEnd))
            } else {
                if (hasHistory) com.fariin.app.ui.components.FloatingTextAction("Edit", modifier = Modifier.align(Alignment.CenterStart)) { selecting = true }
                CallsSegments(if (missedOnly) 1 else 0, { missedOnly = it == 1 }, Modifier.align(Alignment.Center))
                // The field scrolled up under the bar: a search button takes its place (as the chat list).
                androidx.compose.animation.AnimatedVisibility(searchGone, Modifier.align(Alignment.CenterEnd),
                    enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.scaleIn(initialScale = 0.6f),
                    exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.scaleOut(targetScale = 0.6f)) {
                    com.fariin.app.ui.components.FloatingIcon(Icons.Outlined.Search, contentDescription = "Search calls",
                        onClick = { barScope.launch { listState.animateScrollToItem(0) } })
                }
            }
        }
    }) { top ->
        LazyColumn(Modifier.fillMaxSize(), state = listState,
            contentPadding = PaddingValues(top = top, bottom = com.fariin.app.ui.shell.bottomBarInset() + 24.dp)) {
            item(key = "title", contentType = "title") {
                Text("Calls", fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.2.sp, color = c.label,
                    modifier = Modifier.padding(start = 16.dp, top = 2.dp, bottom = 8.dp))
            }
            item(key = "search", contentType = "search") { SearchField(search, { search = it }, "Search calls", hiddenPx = {
                (-(listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "search" }?.offset ?: 0)).toFloat().coerceAtLeast(0f)
            }) }
            when {
                // iOS CallListSkeleton while the history is read.
                !loaded -> item(key = "skeleton") { com.fariin.app.ui.components.CallListSkeleton() }
                // A failed read is never drawn as an empty history.
                calls.isEmpty() && allLinks.isEmpty() && live.isEmpty() && liveEntry == null && error != null -> item(key = "error") {
                    com.fariin.app.ui.components.CenteredNotice("Couldn't load calls", error, "Retry", onRetry,
                        Modifier.fillMaxWidth().padding(top = 96.dp))
                }
                else -> {
                    // owner 2026-10-23 (iOS): Create a Call Link always first, then the live group calls.
                    if (!selecting) item(key = "create-link", contentType = "create") { CreateLinkRow { nav.newCallLink() } }
                    if (!selecting) items(live, key = { "live:" + it.cid }) { l -> com.fariin.app.ui.call.LiveGroupCallRow(l, groups.firstOrNull { it.id == l.cid }) }
                    items(rows, key = { r -> if (r is com.fariin.app.call.SavedCallLink) "link:" + r.roomId else (r as CallRun).id }) { r ->
                        if (r is com.fariin.app.call.SavedCallLink) {
                            val tag = "link:" + r.roomId
                            var menu by remember { mutableStateOf(false) }
                            Box {
                                PickableRow(selecting, tag in picked, onToggle = { toggle(tag) }) {
                                    CallLinkRow(r, callTimeLabel(r.createdAtMs, DateFormat.is24HourFormat(ctx), now),
                                        onOpen = { if (com.fariin.app.call.CallManager.ui.value.status.live) CallActivity.startRing(ctx) else nav.joinLink(r.key, true) },
                                        onInfo = { nav.callLink(r.roomId) }, onLongClick = { menu = true })
                                }
                                // iOS linkMenu: the same items, in the same order, as a call row.
                                RowMenu(menu, { menu = false }, callUp, chats = null,
                                    onVideo = { nav.joinLink(r.key, true) }, onVoice = { nav.joinLink(r.key, false) },
                                    onSelect = { selecting = true; picked = setOf(tag) },
                                    onDelete = { picked = setOf(tag); askDelete = true }, videoFirst = true)
                            }
                        } else {
                            val run = r as CallRun
                            val isLive = CallsLive.showsReturn(run)
                            var menu by remember { mutableStateOf(false) }
                            val verified = remember(run.latest.otherUid, warmed) {
                                run.latest.groupCid == null && com.fariin.app.ui.people.ProfileMemo.get(run.latest.otherUid)?.verified == true
                            }
                            fun deleteRun() { com.fariin.app.data.HiddenCalls.hide(ctx, run.entries.map { it.id }) }
                            Box {
                                // iOS swipeActions: Delete behind the row (a grouped row hides all its calls).
                                com.fariin.app.ui.chats.SwipeActionsRow(run.id, enabled = !selecting && !isLive, leading = emptyList(),
                                    trailing = listOf(com.fariin.app.ui.chats.SwipeAction("Delete",
                                        androidx.compose.ui.graphics.vector.rememberVectorPainter(Icons.Outlined.Delete), c.red) { deleteRun() })) {
                                    Box(Modifier.background(c.bg)) {
                                        // The live call is not a record: it cannot be picked or deleted.
                                        PickableRow(selecting && !isLive, run.id in picked, onToggle = { toggle(run.id) }) {
                                            CallRow(run, me, now, verified, isLive, onInfo = { openInfo(run) },
                                                onLongClick = { if (!isLive) menu = true })
                                        }
                                        if (selecting && isLive) Box(Modifier.matchParentSize().clickable(
                                            interactionSource = remember { MutableInteractionSource() }, indication = null) {})
                                    }
                                }
                                val call = run.latest
                                RowMenu(menu, { menu = false }, callUp,
                                    chats = { nav.chat(call.groupCid ?: call.cid) },
                                    onVideo = { pendingCall = call to true }, onVoice = { pendingCall = call to false },
                                    onSelect = { selecting = true; picked = setOf(run.id) },
                                    onDelete = { deleteRun() })
                            }
                        }
                    }
                    // The empty states sit under the create row, so it is there before the first call.
                    when {
                        rows.isNotEmpty() || live.isNotEmpty() -> {}
                        calls.isEmpty() && allLinks.isEmpty() && liveEntry == null && q.isEmpty() -> item(key = "empty") {
                            // One next step: the same New Call page as the bar's button.
                            com.fariin.app.ui.components.IllustratedEmpty(com.fariin.app.ui.components.LineArt.Phone,
                                "No Calls Yet", "Your call history will appear here.", Modifier.fillMaxWidth().padding(top = 48.dp),
                                action = "Make a Call") { nav.newCall() }
                        }
                        // Worded as the chat and Settings searches.
                        q.isNotEmpty() -> item(key = "no-results") { Empty("No Results", "There were no results for \"${search.trim()}\".\nTry a new search.", search = true) }
                        else -> item(key = "no-missed") { Empty("No Missed Calls", "Missed calls will appear here.") }
                    }
                }
            }
        }
    }
    // New Call as a floating button at the bottom end, over the tab bar (owner 2026-11-07: the
    // Android messengers put it there, as Chats' New Chat pen), no longer in the top bar.
    androidx.compose.animation.AnimatedVisibility(!selecting, Modifier.align(Alignment.BottomEnd)
        .padding(end = 16.dp, bottom = com.fariin.app.ui.shell.bottomBarInset() + 16.dp),
        enter = com.fariin.app.ui.components.Motion.fabIn(), exit = com.fariin.app.ui.components.Motion.fabOut()) {
        androidx.compose.material3.IconButton(onClick = { nav.newCall() },
            modifier = Modifier.size(56.dp).floating(fill = c.accent)) {
            androidx.compose.material3.Icon(painterResource(R.drawable.ic_new_call), contentDescription = "New Call", tint = c.onAccent)
        }
    }
    }
}

/** iOS's All / Missed picker: a glass capsule, the chosen part a grey pill (iOS segmented control). */
@Composable
private fun CallsSegments(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = Fariin.colors
    Row(modifier.height(36.dp).floating(CircleShape).padding(3.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf("All", "Missed").forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.height(30.dp).width(70.dp).clip(CircleShape)
                    .background(if (on) c.label.copy(alpha = if (c.dark) 0.22f else 0.10f) else Color.Transparent)
                    .clickable { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, fontSize = 15.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, color = c.label, maxLines = 1)
            }
        }
    }
}

/** iOS createLinkRow: the link glyph in a 46 frame (where a photo goes) and the words, no card. */
@Composable
private fun CreateLinkRow(onClick: () -> Unit) {
    val c = Fariin.colors
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Link, contentDescription = null, tint = c.label, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text("Create a Call Link", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label)
    }
}

/**
 * iOS's long-press menu on a call row: Voice Call, Video Call, Chats, Select, then Delete in red.
 * A link row has Video Call first and no Chats ([videoFirst], [chats] null). Calling is off while a
 * call is already up.
 */
@Composable
private fun RowMenu(open: Boolean, onDismiss: () -> Unit, callUp: Boolean, chats: (() -> Unit)?,
                    onVideo: () -> Unit, onVoice: () -> Unit, onSelect: () -> Unit, onDelete: () -> Unit, videoFirst: Boolean = false) {
    val c = Fariin.colors
    DropdownMenu(expanded = open, onDismissRequest = onDismiss, containerColor = c.card) {
        val item: @Composable (String, androidx.compose.ui.graphics.painter.Painter, Color, Boolean, () -> Unit) -> Unit = { text, icon, ink, enabled, run ->
            DropdownMenuItem(text = { Text(text, color = if (enabled) ink else c.secondary) },
                leadingIcon = { Icon(icon, null, tint = if (enabled) ink else c.secondary) },
                enabled = enabled, onClick = { onDismiss(); run() })
        }
        val voice = androidx.compose.ui.graphics.vector.rememberVectorPainter(Icons.Outlined.Phone)
        val video = androidx.compose.ui.graphics.vector.rememberVectorPainter(Icons.Outlined.Videocam)
        if (videoFirst) { item("Video Call", video, c.label, !callUp, onVideo); item("Voice Call", voice, c.label, !callUp, onVoice) }
        else { item("Voice Call", voice, c.label, !callUp, onVoice); item("Video Call", video, c.label, !callUp, onVideo) }
        if (chats != null) item("Chats", painterResource(R.drawable.ic_menu_chat), c.label, true, chats)
        item("Select", androidx.compose.ui.graphics.vector.rememberVectorPainter(Icons.Outlined.CheckCircle), c.label, true, onSelect)
        HorizontalDivider(color = c.separator)
        item("Delete", androidx.compose.ui.graphics.vector.rememberVectorPainter(Icons.Outlined.Delete), c.red, true, onDelete)
    }
}

@Composable
internal fun SearchField(value: String, onChange: (String) -> Unit, prompt: String,
                         /** How much of the field the list has pulled under the bar (0 at rest), read while drawing. */
                         hiddenPx: () -> Float = { 0f }) {
    // THE CHAT LIST'S OWN SEARCH (owner 2026-11-08: "not the same as the chat list"): at rest the very
    // same capsule, shrinking in place on scroll exactly as there; a tap turns it into the live field
    // with the keyboard up. A field with words in it, or in use, stays the live one.
    var live by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    if (!live && value.isEmpty()) {
        com.fariin.app.ui.chats.SearchCapsule(hiddenPx, onOpen = { live = true }, prompt = prompt)
        return
    }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(live) { if (live) runCatching { focus.requestFocus() } }
    com.fariin.app.ui.components.FloatingSearchField(value, onChange, prompt,
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 8.dp)
            .focusRequester(focus)
            .onFocusChanged { if (!it.isFocused && value.isEmpty()) live = false })
}

@Composable
private fun Empty(title: String, text: String, search: Boolean = false) {
    val c = Fariin.colors
    // Under the search field, clear of the keyboard: with a search typed the words used to sit
    // behind it and only the phone mark showed, cut off.
    Box(Modifier.fillMaxWidth().imePadding().padding(horizontal = 32.dp).padding(top = 72.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (!search) {
                Icon(Icons.Outlined.Phone, contentDescription = null, tint = c.secondary, modifier = Modifier.size(44.dp))
                Spacer(Modifier.height(12.dp))
            }
            Text(title, fontSize = if (search) 17.sp else 22.sp, fontWeight = if (search) FontWeight.SemiBold else FontWeight.Bold,
                color = c.label, textAlign = TextAlign.Center)
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
    Box(Modifier.background(if (picked) c.blue.copy(alpha = 0.12f) else Color.Transparent)) {
        content()
        // A small tick on the photo's lower right, as the chat list marks a chosen row: the photo,
        // the time and the (i) all stay in view (owner 2026-11-03: the box covered the photo).
        val under = if (picked) c.blue.copy(alpha = 0.12f).compositeOver(c.bg) else c.bg
        if (selecting) Box(Modifier.matchParentSize().clickable(onClick = onToggle)) {
            Box(Modifier.align(Alignment.CenterStart).padding(start = 46.dp, top = 30.dp).size(22.dp).clip(CircleShape)
                .background(under).padding(2.dp).clip(CircleShape)
                .background(if (picked) c.blue else c.bg)
                .then(if (picked) Modifier else Modifier.border(1.5.dp, c.secondary, CircleShape)),
                contentAlignment = Alignment.Center) {
                if (picked) Icon(Icons.Filled.Check, contentDescription = "Selected", tint = Color.White, modifier = Modifier.size(14.dp))
            }
        }
    }
}

/** iOS systemGreen, the Return pill's tint. */
private val ReturnGreen = Color(0xFF34C759)

/**
 * iOS CallHistoryRow: avatar 46, name (red when I missed it) with the verified mark, arrow +
 * direction + length, video glyph, then the time and (i). The live call shows Return instead.
 */
@Composable
private fun CallRow(run: CallRun, me: String, now: Long, verified: Boolean, live: Boolean, onInfo: () -> Unit, onLongClick: () -> Unit) {
    val c = Fariin.colors
    val call = run.latest
    val count = run.entries.size
    val ctx = LocalContext.current
    Row(
        Modifier.fillMaxWidth().listPress(onLongClick = onLongClick) { callBack(ctx, call, call.video) }
            .padding(start = 16.dp, end = if (live) 16.dp else 4.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(call.photoUrl, 46.dp, squircle = false, kind = com.fariin.app.ui.components.AvatarKind.of(call.groupCid != null, false))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (count > 1) "${call.name} ($count)" else call.name,
                    fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    color = if (call.missedIncoming(me, now)) c.red else c.label,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                if (verified) {
                    Spacer(Modifier.width(5.dp))
                    Icon(Icons.Filled.Verified, contentDescription = "Verified", tint = c.blue, modifier = Modifier.size(15.dp))
                }
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (call.mine(me)) Icons.Filled.NorthEast else Icons.Filled.SouthWest,
                    contentDescription = null, tint = c.secondary, modifier = Modifier.size(12.dp))
                if (call.video) {
                    Spacer(Modifier.width(2.dp))
                    Icon(ImageVector.vectorResource(R.drawable.ic_call_video), contentDescription = "Video", tint = c.secondary, modifier = Modifier.size(14.dp))
                }
                Spacer(Modifier.width(4.dp))
                // One Text: "Outgoing (4 min)" never breaks between the two.
                val dir = CallHistory.directionText(call, me, now)
                val dur = CallHistory.durationText(call, count, now)
                Text(if (dur != null) "$dir ($dur)" else dir, fontSize = 14.sp, color = c.secondary, maxLines = 1)
            }
        }
        Spacer(Modifier.width(8.dp))
        if (live) {
            // iOS Return (owner 2026-10-13): green pill, filled phone (or camera) 4 from a bold "Return";
            // it does what the row's tap does, bringing the call forward.
            Row(Modifier.height(34.dp).clip(CircleShape).background(ReturnGreen)
                .clickable { callBack(ctx, call, call.video) }.padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(if (call.video) Icons.Filled.Videocam else Icons.Filled.Phone, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                Text("Return", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
            }
        } else {
            Text(callTimeLabel(call.dateMs, DateFormat.is24HourFormat(ctx), now), fontSize = 14.sp, color = c.secondary)
            Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onInfo), contentAlignment = Alignment.Center) {
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
