package com.fariin.app.ui.chats

import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import com.fariin.app.ui.components.listPress
import androidx.compose.foundation.interaction.collectIsPressedAsState
import com.fariin.app.data.SearchRecents
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.MarkChatRead
import androidx.compose.material.icons.outlined.MarkChatUnread
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.LocationOn
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.sp
import com.fariin.app.R
import com.fariin.app.data.ChatList
import com.fariin.app.data.Conversation
import com.fariin.app.data.CryptoHub
import com.fariin.app.data.MessageDecryptor
import com.fariin.app.ui.components.Avatar
import com.fariin.app.ui.components.FloatingBack
import com.fariin.app.ui.components.FloatingIcon
import com.fariin.app.ui.components.floating
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.shape.RoundedCornerShape
import com.fariin.app.ui.components.Snack
import com.fariin.app.ui.thread.LocalOpenChat
import com.fariin.app.ui.theme.Fariin
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/*
 * The Chats tab, the Android way (owner 2026-11-02, "Android wins"): a Material 3 top app bar
 * (title or connection state, search, overflow menu holding the iOS chat-list menu), a
 * floating New Chat button bottom right with a small story camera above it, and long-press
 * selection mode with a contextual action bar (pin, mute, archive, delete, more) instead of the
 * iOS context menu. Rows, sections, words and order are iOS ChatsView's.
 */

private const val FILTER_ALL = 0
private const val FILTER_UNREAD = 1
private const val FILTER_GROUPS = 2
private const val FILTER_CHANNELS = 3

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    state: ChatsUiState,
    me: String,
    decryptor: MessageDecryptor,
    onOpen: (Conversation) -> Unit = LocalOpenChat.current,
    onSettings: () -> Unit = {},
    onNewStory: () -> Unit = {},
    stories: com.fariin.app.data.StoriesState = com.fariin.app.data.StoriesState(),
    /** False while another tab shows: the list stays alive behind it, its Back handlers off. */
    active: Boolean = true,
) {
    val c = Fariin.colors
    // Rings on the photos (iOS storyRing): a tap on a ringed photo opens that story, not the chat.
    val storyHidden by com.fariin.app.data.StoryMutes.set.collectAsState()
    var storyOpen by remember { mutableStateOf<com.fariin.app.data.StoryGroup?>(null) }
    storyOpen?.let { g ->
        com.fariin.app.ui.stories.StoryViewerDialog(me, listOf(g), 0, state.chats, onClose = { storyOpen = null })
    }
    val people = com.fariin.app.ui.people.LocalPeopleNav.current
    val channels = com.fariin.app.ui.channels.LocalChannelNav.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var filter by rememberSaveable { mutableIntStateOf(FILTER_ALL) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var showArchive by rememberSaveable { mutableStateOf(false) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // A second tap on Chats: out of Archived or search first, then to the top.
    com.fariin.app.ui.shell.OnTabReselect {
        when {
            showArchive -> showArchive = false
            searching -> { searching = false; query = "" }
            else -> listState.animateScrollToItem(0)
        }
    }
    // Archived, search and the main list share one list position: each opens at its top, or the
    // main list came back anchored on the row that topped the archive (newer chats above the screen).
    // Only on a real switch: coming back from a chat re-runs this effect, and that put the list back
    // at the top instead of on the row I left from (owner 2026-11-03).
    var listMode by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(showArchive, searching) {
        val mode = "$showArchive/$searching"
        if (listMode.isNotEmpty() && listMode != mode) listState.scrollToItem(0)
        listMode = mode
    }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmDelete by remember { mutableStateOf(false) }
    // A chat whose Delete was swiped (not the selection): the same question, about this one chat.
    var swipeDelete by remember { mutableStateOf<Conversation?>(null) }
    // A chat whose Mute was swiped: the 1 hour ... Always choices in a sheet.
    var swipeMute by remember { mutableStateOf<Conversation?>(null) }
    swipeMute?.let { conv ->
        MuteForSheet(onDismiss = { swipeMute = null }) { label, until ->
            scope.launch {
                Snack.show(if (ChatListActions.setMute(me, conv, until)) MuteFor.doneWord(label) else "Couldn't save. Check your connection and try again.")
            }
        }
    }
    val selecting = selected.isNotEmpty()
    val online = rememberOnline()
    val link by com.fariin.app.data.ConnectionStatus.state.collectAsState()
    val linkLabel = com.fariin.app.data.ConnectionStatus.label(link)

    // System back peels one layer at a time: selection, then search, then the archive page.
    BackHandler(enabled = active && selecting) { selected = emptySet() }
    BackHandler(enabled = active && !selecting && searching) { searching = false; query = "" }
    BackHandler(enabled = active && !selecting && !searching && showArchive) { showArchive = false }

    val source = if (showArchive) state.archived else state.chats
    val shown = source.asSequence()
        .filter {
            when (filter) {
                FILTER_UNREAD -> !showArchive && it.hasUnreadMark(me)
                FILTER_GROUPS -> !showArchive && it.isGroup
                FILTER_CHANNELS -> !showArchive && it.isChannel
                else -> true
            }
        }
        .filter { query.isBlank() || it.displayName(me).contains(query.trim(), ignoreCase = true) }
        .toList()
    // The search reaches past the list (iOS lookUpPeople, owner 2026-11-03 "not global"): who owns
    // the username typed, asked 0.4 s after the last key, answers kept per query for the session.
    // Somebody already in a chat above is not offered again.
    var peopleHits by remember { mutableStateOf<List<com.fariin.app.data.Person>>(emptyList()) }
    val peopleCache = remember { HashMap<String, List<com.fariin.app.data.Person>>() }
    var peopleAll by remember { mutableStateOf(false) }
    val q = query.trim()
    LaunchedEffect(q, searching) {
        peopleAll = false
        if (!searching || q.trim('@').isEmpty()) { peopleHits = emptyList(); return@LaunchedEffect }
        val key = q.lowercase()
        peopleCache[key]?.let { peopleHits = it; return@LaunchedEffect }
        kotlinx.coroutines.delay(400)
        val found = people.searchPeople(q)
        peopleCache[key] = found
        peopleHits = found
    }
    // Public groups and channels by @name (iOS GroupDirectory.search, groupsOnly), the same pause.
    var groupHits by remember { mutableStateOf<List<com.fariin.app.data.PublicGroupHit>>(emptyList()) }
    var publicGroupOpen by remember { mutableStateOf<String?>(null) }
    publicGroupOpen?.let { gid -> com.fariin.app.ui.people.PublicGroupSheet(gid, onClose = { publicGroupOpen = null },
        onOpenChat = { cid -> searching = false; query = ""; people.chat(cid) }) }
    LaunchedEffect(q, searching) {
        if (!searching || q.trim('@').length < 3) { groupHits = emptyList(); return@LaunchedEffect }
        kotlinx.coroutines.delay(400)
        groupHits = com.fariin.app.data.PublicGroups.search(q)
    }
    val newPeople = run {
        val known = shown.filter { !it.isGroup }.map { it.otherUid(me) }.toSet()
        peopleHits.filter { it.uid != me && it.uid !in known }
    }
    val pinned = if (showArchive) emptyList() else shown.filter { it.isPinned(me) }
    val rest = shown.filter { showArchive || !it.isPinned(me) }
    // The official Fariin chat: its own row, placed among the chats by its newest announcement.
    val official by com.fariin.app.data.OfficialChannel.view.collectAsState()
    // Archived, it lives in the Archived list; pinned, it heads the Pinned section.
    val officialArchived = official.state.archived
    val officialShown = showArchive == officialArchived && official.latest != null &&
        (filter == FILTER_ALL || (filter == FILTER_UNREAD && official.unread > 0)) &&
        (query.isBlank() || com.fariin.app.data.OfficialChannel.NAME.contains(query.trim(), ignoreCase = true))
    val officialAt = official.latest?.sortAt ?: 0.0
    val officialPinned = officialShown && !showArchive && official.state.pinned
    val officialIndex = if (officialShown && !officialPinned) rest.indexOfFirst { it.displayUpdatedAt(me) < officialAt }.let { if (it < 0) rest.size else it } else rest.size
    val selectedConvs = (state.chats + state.archived).filter { it.id in selected }
    val byId = remember(state.chats, state.archived) { (state.chats + state.archived).associateBy { it.id } }
    val appCtx = androidx.compose.ui.platform.LocalContext.current.applicationContext
    var gsUndo by remember { mutableStateOf(false) }
    val screenCtx = androidx.compose.ui.platform.LocalContext.current
    val myProfile = remember(me) { com.fariin.app.data.MyProfileRepository().listen(me) }.collectAsState(initial = null).value
    val myPhoto = myProfile?.photoUrl
    val myHandle = myProfile?.handle
    LaunchedEffect(me) { SearchRecents.start(appCtx, me) }
    // The search page is a page of its own: the tab bar goes while it is up (iOS).
    DisposableEffect(searching) {
        com.fariin.app.ui.shell.TabBarVisibility.hidden = searching
        onDispose { com.fariin.app.ui.shell.TabBarVisibility.hidden = false }
    }

    val toggle: (Conversation) -> Unit = { conv ->
        selected = if (conv.id in selected) selected - conv.id else selected + conv.id
    }
    val act: (String, suspend (Conversation) -> Boolean) -> Unit = { done, write ->
        val list = selectedConvs
        selected = emptySet()
        scope.launch {
            val ok = list.map { write(it) }.all { it }
            Snack.show(if (ok) done else "Couldn't save. Check your connection and try again.")
        }
    }

    val barState = rememberTopAppBarState()
    // The title stays put (owner 2026-11-03, the reference apps): the chats slide under it.
    val scroll = TopAppBarDefaults.pinnedScrollBehavior(barState)
    // Frosted once the list has moved under it, the page colour at rest; the search field scrolls
    // away with the list and a search button takes its place in the bar.
    val haze = remember { dev.chrisbanes.haze.HazeState() }
    val glass = com.fariin.app.perf.rememberPowerState().glass()
    // The glass's lower edge: full strength under the title, then an eased fade to nothing.
    val barFade = remember {
        androidx.compose.ui.graphics.Brush.verticalGradient(
            0f to Color.Black, 0.55f to Color.Black, 0.66f to Color.Black.copy(alpha = 0.85f),
            0.77f to Color.Black.copy(alpha = 0.55f), 0.87f to Color.Black.copy(alpha = 0.25f),
            0.94f to Color.Black.copy(alpha = 0.08f), 1f to Color.Transparent)
    }
    val scrolled by remember { androidx.compose.runtime.derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 } }
    // Owner 2026-11-05: the glass only when something is under the bar, as iOS's scroll edge. It
    // fades in and out (the effect stays attached, so nothing is rebuilt and there is no hitch).
    val glassAlpha by com.fariin.app.ui.components.rememberEdgeAlpha(scrolled || selecting)
    // The bar's search button only once the field in the list has shrunk away (it showed while the
    // field was still two-thirds there: two search buttons at once).
    val searchGone by remember { androidx.compose.runtime.derivedStateOf {
        val info = listState.layoutInfo.visibleItemsInfo
        val field = info.firstOrNull { it.key == "search" }
        if (field == null) info.isNotEmpty() && info.first().index > 0 else field.offset <= -(field.size * 0.9f)
    } }
    // At the very top the bar is always shown: a second tap on Chats scrolls there without a drag,
    // and the bar stayed hidden from the scroll down.
    val atTop by remember { androidx.compose.runtime.derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 } }
    LaunchedEffect(atTop) { if (atTop) barState.heightOffset = 0f }
    // The floating buttons step aside while the list scrolls down and come back on the way up.
    var fabShown by remember { mutableStateOf(true) }
    val fabScroll = remember {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset,
                                     source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (available.y < -6f) fabShown = false else if (available.y > 6f) fabShown = true
                return androidx.compose.ui.geometry.Offset.Zero
            }
        }
    }
    val anim = com.fariin.app.ui.components.animationsOn()
    val rowPlace = remember(anim) { com.fariin.app.ui.components.Motion.itemOffset }
    // The new-account card's height (owner 2026-11-04: the camera and pencil sat on top of it). The
    // FABs rise to sit above the card while it shows and slide back down when it goes.
    var gsHeightPx by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val fabLift by androidx.compose.animation.core.animateDpAsState(
        // Card top = banner height less its 40 dp fade; the FAB already sits 16 dp up; keep 12 dp of air.
        with(density) { (gsHeightPx.toDp() - 44.dp).coerceAtLeast(0.dp) }, label = "fabLift")

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection).nestedScroll(fabScroll),
        containerColor = c.bg,
        // Bottom inset = the floating tab bar (ui/shell), so the FABs sit above it.
        contentWindowInsets = WindowInsets(bottom = com.fariin.app.ui.shell.bottomBarInset()),
        topBar = {
            Column(
                Modifier.then(when {
                    // Owner 2026-11-05 ("make it high end like Settings"): the glass is always on, so
                    // nothing switches at the first scroll, and it reaches a long tail below the bar
                    // where blur and tint fade out together, so its end can not be seen. The tail is
                    // drawn but not measured: the list's top padding stays the bar's own height.
                    glass -> Modifier.layout { m, k ->
                        val tail = 36.dp.roundToPx()
                        val p = m.measure(k.copy(maxHeight = androidx.compose.ui.unit.Constraints.Infinity))
                        val h = (p.height - tail).coerceAtLeast(0)
                        layout(p.width, h) { p.place(0, 0) }
                    }.hazeEffect(haze) {
                        backgroundColor = c.bg; blurRadius = 24.dp; noiseFactor = 0f
                        tints = listOf(dev.chrisbanes.haze.HazeTint(c.bg.copy(alpha = 0.7f)))
                        // Blurred from a third-size copy: the same look under a 24 dp blur, a fraction of the GPU work.
                        inputScale = dev.chrisbanes.haze.HazeInputScale.Fixed(1f / 3)
                        progressive = com.fariin.app.ui.components.edgeStages(0.45f)
                        alpha = glassAlpha
                    }.padding(bottom = 36.dp)
                    scrolled || selecting -> Modifier.background(c.bg.copy(alpha = 0.97f))
                    else -> Modifier.background(c.bg)
                }).statusBarsPadding(),
            ) {
                when {
                    selecting -> SelectionBar(
                        count = selected.size,
                        allPinned = selectedConvs.all { it.isPinned(me) },
                        allMuted = selectedConvs.all { it.isMuted(me, System.currentTimeMillis().toDouble()) },
                        allUnread = selectedConvs.all { it.hasUnreadMark(me) },
                        archive = showArchive,
                        onClose = { selected = emptySet() },
                        onPin = {
                            val on = !selectedConvs.all { it.isPinned(me) }
                            if (!on || !ChatListActions.pinsFull(me, state.chats, selectedConvs.count { !it.isPinned(me) })) {
                                act(if (on) "Pinned" else "Unpinned") { ChatListActions.setPinned(me, it, on) }
                            }
                        },
                        // All muted: Unmute at once. Else the bar opens the 1 hour ... Always menu.
                        onUnmute = { act("Unmuted") { ChatListActions.setMute(me, it, 0.0) } },
                        onMuteFor = { label, until -> act(MuteFor.doneWord(label)) { ChatListActions.setMute(me, it, until) } },
                        onArchive = {
                            val on = !showArchive
                            val list = selectedConvs
                            selected = emptySet()
                            scope.launch {
                                list.forEach { ChatListActions.setArchived(me, it, on) }
                                val n = list.size
                                val what = if (n == 1) "Chat" else "$n chats"
                                Snack.show(if (on) "$what archived" else "$what unarchived", action = "Undo") {
                                    scope.launch { list.forEach { ChatListActions.setArchived(me, it, !on) } }
                                }
                            }
                        },
                        onDelete = { confirmDelete = true },
                        onReadToggle = {
                            val unread = !selectedConvs.all { it.hasUnreadMark(me) }
                            act(if (unread) "Marked as unread" else "Marked as read") { ChatListActions.setUnread(me, it, unread) }
                        },
                        onSelectAll = { selected = shown.map { it.id }.toSet() },
                        single = selectedConvs.singleOrNull()?.let { one ->
                            val person = !one.isGroup && !one.isChannel
                            SingleChatActions(
                                infoLabel = when { one.isChannel -> "Channel Info"; one.isGroup -> "Group Info"; else -> "View Contact" },
                                onInfo = {
                                    selected = emptySet()
                                    if (person) people.contact(one.otherUid(me)) else people.group(one.id)
                                },
                            )
                        },
                    )
                    searching -> SearchBar(query, onQuery = { query = it }, onClose = { searching = false; query = "" })
                    else -> {
                        // The server connection's own words (data/ConnectionStatus, iOS wording),
                        // falling back to the phone's network state.
                        val title = when {
                            showArchive -> "Archive"
                            linkLabel != null -> linkLabel
                            !online -> "Waiting for network"
                            // The brand name at the top left (owner 2026-11-02, Android messengers).
                            else -> "Fariin"
                        }
                        Column {
                        TopAppBar(
                            // The brand at rest: the Fariin mark and the name in bold (owner 2026-11-03, "ours
                            // looks weak" beside the reference apps). Status words stay plain.
                            title = {
                                // No mark beside it (owner 2026-11-03: "remove the black round").
                                if (title == "Fariin") Column {
                                    Text("Fariin", fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp, color = c.label)
                                    // The filter in use, under the title (iOS, owner 2026-10-31): without it a
                                    // filtered list looked like chats had gone. A tap goes back to all chats.
                                    val filterName = when (filter) {
                                        FILTER_UNREAD -> "Unread"; FILTER_GROUPS -> "Groups"; FILTER_CHANNELS -> "Channels"; else -> null
                                    }
                                    if (filterName != null) Text("$filterName  ×", fontSize = 13.sp, color = c.secondary,
                                        modifier = Modifier.clickable(onClickLabel = "Show all chats") { filter = FILTER_ALL })
                                } else Text(title, style = MaterialTheme.typography.titleLarge)
                            },
                            // The bar's buttons float in their own circles, as the chat screen's back does.
                            navigationIcon = {
                                if (showArchive) FloatingBack(onBack = { showArchive = false }, modifier = Modifier.padding(start = 12.dp, end = 4.dp))
                            },
                            actions = {
                                // In the Archive the search stays a button; the list itself has the field below.
                                if (showArchive || searchGone) FloatingIcon(Icons.Outlined.Search, contentDescription = "Search", onClick = { searching = true },
                                    modifier = Modifier.padding(end = if (showArchive) 12.dp else 8.dp))
                                if (!showArchive) OverflowMenu(
                                    filter = filter, onFilter = { filter = it },
                                    requests = state.requests.size,
                                    onRequests = people.requests,
                                    onArchive = { showArchive = true; filter = FILTER_ALL },
                                    onMarkAllRead = {
                                        val list = state.chats.filter { !it.isBlockedByMe(me) && it.hasUnreadMark(me) }
                                        scope.launch {
                                            list.forEach { ChatListActions.setUnread(me, it, false) }
                                            Snack.show(if (list.isEmpty()) "No unread chats" else "All chats marked as read")
                                        }
                                    },
                                    onNewGroup = people.newGroup,
                                    onNewChannel = channels.create,
                                    onFindChannels = channels.find,
                                    onSettings = onSettings,
                                )
                            },
                            windowInsets = WindowInsets(0, 0, 0, 0),
                            scrollBehavior = scroll,
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent,
                                titleContentColor = c.label, actionIconContentColor = c.label, navigationIconContentColor = c.label,
                            ),
                        )
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            AnimatedVisibility(fabShown && !selecting && !searching && !showArchive,
                enter = com.fariin.app.ui.components.Motion.fabIn(), exit = com.fariin.app.ui.components.Motion.fabOut()) {
                Column(Modifier.padding(bottom = fabLift), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    // Two floating circles (iOS glass buttons): the camera on the card fill, New Chat in blue.
                    IconButton(onClick = onNewStory, modifier = Modifier.size(48.dp).floating()) {
                        Icon(fi(R.drawable.ic_story_camera), contentDescription = "New Story", tint = c.label)
                    }
                    IconButton(onClick = people.newChat, modifier = Modifier.size(56.dp).floating(fill = c.accent)) {
                        Icon(Icons.Outlined.Edit, contentDescription = "New Chat", tint = c.onAccent)
                    }
                }
            }
        },
    ) { pad ->
        // The list runs under the title bar (padded by it), everything else sits below it.
        Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            when {
                // iOS ChatListSkeleton: rows the shape of chats while the first page loads.
                state.loading && source.isEmpty() -> Box(Modifier.padding(top = pad.calculateTopPadding())) { com.fariin.app.ui.components.ChatListSkeleton() }
                state.error != null && source.isEmpty() -> Box(Modifier.padding(top = pad.calculateTopPadding())) { Centered(state.error) }
                shown.isEmpty() && !officialShown && newPeople.isEmpty() && groupHits.isEmpty() -> Box(Modifier.padding(top = pad.calculateTopPadding())) { Centered(when {
                    query.isNotBlank() -> "No results"
                    showArchive -> "No archived chats"
                    filter == FILTER_UNREAD -> "No unread chats"
                    filter == FILTER_GROUPS -> "No groups yet"
                    filter == FILTER_CHANNELS -> "No channels yet"
                    else -> "No chats yet"
                }, action = if (filter == FILTER_CHANNELS && query.isBlank()) ("Find Channels" to channels.find) else null) }
                else -> LazyColumn(Modifier.fillMaxSize().then(if (glass) Modifier.hazeSource(haze) else Modifier), state = listState,
                    contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = com.fariin.app.ui.shell.bottomBarInset() + 72.dp)) {
                    // An invisible first row. At the top the list keeps this row in place, so anything new
                    // above the old first chat (a new group, a chat moved up by a message, the Pinned
                    // section after a pin, the Archived row) shows at once instead of landing just above
                    // the screen. Scrolled down, the list keeps the row I am looking at, so nothing jumps.
                    item(key = "top-anchor", contentType = "anchor") { Spacer(Modifier.fillMaxWidth().height(1.dp)) }
                    // The search field heads the list and scrolls away with it, as iOS's does
                    // (owner 2026-11-03): one tap and the keyboard is up.
                    if (!showArchive && !searching) item(key = "search", contentType = "search") {
                        // How much of the field the list has pulled under the bar (0 at rest), read
                        // while drawing only, so the shrink costs no recomposition.
                        SearchCapsule(hiddenPx = {
                            (-(listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "search" }?.offset ?: 0)).toFloat().coerceAtLeast(0f)
                        }) { searching = true }
                    }
                    // A voice message playing on after its chat was left (iOS VoiceNoteBar, under the header).
                    if (!searching) item(key = "voicebar", contentType = "voicebar") {
                        com.fariin.app.ui.thread.VoiceNoteBar(null, me) { cid -> (state.chats + state.archived).firstOrNull { it.id == cid }?.let { if (!selecting) onOpen(it) } }
                    }
                    if (!showArchive && !searching && filter == FILTER_ALL && state.requests.isNotEmpty()) item(key = "requests", contentType = "requests") {
                        ListRow("Message Requests", fi(R.drawable.ic_message_request), trailing = "${state.requests.size}", badge = true,
                            subtitle = state.requests.joinToString(", ") { it.displayName(me) }, onClick = people.requests)
                    }
                    val archivedCount = state.archived.size + if (officialArchived && official.latest != null) 1 else 0
                    if (!showArchive && !searching && filter == FILTER_ALL && archivedCount > 0) {
                        item(key = "archive") {
                            ListRow("Archived", fi(R.drawable.ic_archive), trailing = "$archivedCount") { showArchive = true }
                        }
                    }
                    val row: @Composable (Conversation) -> Unit = { conv ->
                        val ring = com.fariin.app.data.StoryRings.forChat(conv, me, stories, storyHidden)
                        // Android's swipe (owner 2026-11-06): one action, left only, as Google's lists:
                        // archive (unarchive in the Archive), with Undo. Mute, Delete, Pin and Read are
                        // on long-press (select mode), where Android people look for them.
                        SwipeToActRow(conv.id, enabled = !selecting && !searching,
                            label = if (showArchive) "Unarchive" else "Archive",
                            icon = androidx.compose.ui.res.painterResource(R.drawable.ic_archive_fill),
                            fill = c.blue,
                            onAction = {
                                val on = !showArchive
                                // The note at once (offline the write waits for the server, and the note
                                // with its Undo waited with it); a refused write says so (review 2026-11-06).
                                Snack.show(if (on) "Chat archived" else "Chat unarchived", action = "Undo") {
                                    scope.launch { ChatListActions.setArchived(me, conv, !on) }
                                }
                                scope.launch {
                                    if (!ChatListActions.setArchived(me, conv, on)) Snack.show(if (on) "Couldn't archive the chat" else "Couldn't unarchive the chat")
                                }
                            }) {
                        ChatRow(conv, me, decryptor,
                            selected = conv.id in selected,
                            ring = ring?.let { com.fariin.app.data.StoryRings.seenFlags(it) }.orEmpty(),
                            onStoryTap = if (ring == null || selecting) null else ({ storyOpen = ring }),
                            storyKey = ring?.key,
                            onOpen = {
                                if (selecting) toggle(conv)
                                else {
                                    // Opened from the search page: remembered under Recent, and the search
                                    // closes behind it (iOS ChatSearchPage.select).
                                    if (searching) { SearchRecents.recordChat(appCtx, me, conv); searching = false; query = "" }
                                    onOpen(conv)
                                }
                            },
                            onLongPress = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); toggle(conv) })
                        }
                    }
                    val officialItem: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {
                        item(key = com.fariin.app.data.OfficialChannel.CID, contentType = "chat") {
                            Box(Modifier.animateItem(fadeInSpec = null, placementSpec = rowPlace, fadeOutSpec = null)) {
                                com.fariin.app.ui.official.OfficialRow(official, onOpen = { if (!selecting) channels.official() })
                            }
                        }
                    }
                    // THE SEARCH PAGE (iOS ChatSearchPage, owner 2026-11-03: "supposed to be a separate
                    // page"): nothing of the chat list while it is up. Empty: Recent. Typed: Chats,
                    // Groups, Channels, then Global Search.
                    if (searching) {
                        if (q.isEmpty()) {
                            val recents = SearchRecents.entries.toList()
                            if (recents.isNotEmpty()) {
                                item(key = "h-recent") { RecentHeader { SearchRecents.clear(appCtx, me) } }
                                items(recents, key = { "r-" + it.id }, contentType = { "chat" }) { e ->
                                    val conv = if (e.kind == "chat") byId[e.key] else null
                                    if (conv != null) row(conv)
                                    else if (e.kind == "person") {
                                        val p = com.fariin.app.data.Person(e.key, e.title, e.handle, e.photo.ifEmpty { null })
                                        PersonRow(p) {
                                            scope.launch { people.startChat(p)?.let { cid -> SearchRecents.recordPerson(appCtx, me, p, cid); searching = false; query = ""; people.chat(cid) } }
                                        }
                                    }
                                }
                            }
                        } else {
                            val oneToOne = shown.filter { !it.isGroup && !it.isChannel }
                            val groupsHit = shown.filter { it.isGroup && !it.isChannel }
                            val channelsHit = shown.filter { it.isChannel }
                            if (oneToOne.isNotEmpty() || officialShown) {
                                item(key = "h-s-chats") { SectionHeader("Chats") }
                                if (officialShown) officialItem()
                                items(oneToOne, key = { it.id }, contentType = { "chat" }) { row(it) }
                            }
                            if (groupsHit.isNotEmpty()) {
                                item(key = "h-s-groups") { SectionHeader("Groups") }
                                items(groupsHit, key = { it.id }, contentType = { "chat" }) { row(it) }
                            }
                            if (channelsHit.isNotEmpty()) {
                                item(key = "h-s-channels") { SectionHeader("Channels") }
                                items(channelsHit, key = { it.id }, contentType = { "chat" }) { row(it) }
                            }
                        }
                    }
                    if (!searching && (pinned.isNotEmpty() || officialPinned)) {
                        item(key = "h-pinned") { SectionHeader("Pinned") }
                        if (officialPinned) officialItem()
                        items(pinned, key = { it.id }, contentType = { "chat" }) {
                            Box(Modifier.animateItem(fadeInSpec = null, placementSpec = rowPlace, fadeOutSpec = null)) { row(it) }
                        }
                        item(key = "h-chats") { SectionHeader("Chats") }
                    }
                    // A chat that gets a new message glides to its new place instead of jumping.
                    if (!searching) {
                        items(rest.subList(0, officialIndex), key = { it.id }, contentType = { "chat" }) {
                            Box(Modifier.animateItem(fadeInSpec = null, placementSpec = rowPlace, fadeOutSpec = null)) { row(it) }
                        }
                        if (officialShown && !officialPinned) officialItem()
                        items(rest.subList(officialIndex, rest.size), key = { it.id }, contentType = { "chat" }) {
                            Box(Modifier.animateItem(fadeInSpec = null, placementSpec = rowPlace, fadeOutSpec = null)) { row(it) }
                        }
                    }
                    // iOS ChatSearchPage: people under "Global Search", after everything local, the first
                    // three and then Show More. A tap opens the chat, which is what creates it.
                    if (searching && newPeople.isNotEmpty()) {
                        item(key = "h-global") { SectionHeader("Global Search") }
                        items(if (peopleAll) newPeople else newPeople.take(3), key = { "p-" + it.uid }) { p ->
                            PersonRow(p) {
                                scope.launch { people.startChat(p)?.let { cid -> SearchRecents.recordPerson(appCtx, me, p, cid); searching = false; query = ""; people.chat(cid) } }
                            }
                        }
                        if (!peopleAll && newPeople.size > 3) item(key = "p-more") {
                            Text("Show More", color = c.accent, style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.fillMaxWidth().clickable { peopleAll = true }.padding(horizontal = 86.dp, vertical = 12.dp))
                        }
                    }
                    // Public groups and channels I am not in yet, after the people (iOS ChatSearchPage).
                    val newGroups = groupHits.filter { h -> !h.isMember && state.chats.none { it.id == h.cid } }
                    if (searching && newPeople.isEmpty() && newGroups.isNotEmpty()) item(key = "h-global") { SectionHeader("Global Search") }
                    if (searching) items(newGroups, key = { "g-" + it.cid }) { h ->
                        Row(Modifier.fillMaxWidth().clickable {
                            if (h.isChannel) channels.page(h.cid) else publicGroupOpen = h.cid
                        }.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Avatar(h.photoUrl, 54.dp, Modifier.padding(vertical = 9.dp), squircle = h.isChannel)
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(h.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium,
                                    color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(h.subtitle, style = MaterialTheme.typography.bodyMedium, color = c.secondary, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
        // iOS getStartedLayer: Things you can do, for a new account with a short list.
        val gsVersion = GetStartedStore.version.intValue
        val gsShown = !state.loading && state.error == null && !searching && !selecting && !showArchive && filter == FILTER_ALL &&
            GetStartedStore.accountIsNew() && state.chats.count { !it.isChannel } < GetStartedStore.CHAT_LIMIT
        val gsCards = remember(gsVersion, gsShown, myPhoto) { if (gsShown) GetStartedStore.cards(appCtx, me, hasPhoto = !myPhoto.isNullOrEmpty()) else emptyList() }
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = com.fariin.app.ui.shell.bottomBarInset())
            .then(androidx.compose.ui.Modifier.onSizeChanged { gsHeightPx = if (gsCards.isNotEmpty()) it.height else 0 })) {
            if (gsCards.isNotEmpty()) GetStartedBanner(gsCards,
                onTap = { card ->
                    when (card) {
                        GetStartedCard.NEW_GROUP -> people.newGroup()
                        GetStartedCard.INVITE -> com.fariin.app.ui.settings.shareInvite(screenCtx, myHandle)
                        GetStartedCard.ADD_PHOTO -> people.editProfile()
                        GetStartedCard.CHAT_COLOR -> { com.fariin.app.ui.settings.SettingsLink.request(com.fariin.app.ui.settings.PAGE_CHAT_COLOR); onSettings() }
                    }
                },
                onRemove = { GetStartedStore.remove(appCtx, me, it) },
                onHideAll = {
                    GetStartedStore.finish(appCtx, me); gsUndo = true
                    scope.launch { kotlinx.coroutines.delay(5_000); gsUndo = false }
                })
            else if (gsUndo) GetStartedUndo { gsUndo = false; GetStartedStore.reopen(appCtx, me) }
        }
        }
    }

    if (confirmDelete || swipeDelete != null) {
        val targets = swipeDelete?.let { listOf(it) } ?: selectedConvs
        val n = targets.size
        val groups = targets.count { it.isGroup }
        AlertDialog(
            onDismissRequest = { confirmDelete = false; swipeDelete = null },
            title = { Text(if (n == 1) "Delete this chat?" else "Delete $n chats?") },
            text = {
                Text(if (groups > 0) "Groups stay in your list: leave a group from its info page. The other chats are removed from this phone's list."
                else "The chat is removed from your list. The other person keeps their copy.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    val list = targets.filter { !it.isGroup }
                    if (swipeDelete == null) selected = emptySet()
                    swipeDelete = null
                    scope.launch {
                        val ok = list.map { ChatListActions.deleteForMe(me, it) }.all { it }
                        Snack.show(if (ok) (if (list.size == 1) "Chat deleted" else "${list.size} chats deleted") else "Couldn't delete. Try again.")
                    }
                }) { Text("Delete", color = c.red) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false; swipeDelete = null }) { Text("Cancel") } },
        )
    }
}

/** The selection menu's extra row when exactly one chat is picked: its info page. */
private class SingleChatActions(
    val infoLabel: String,
    val onInfo: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionBar(
    count: Int, allPinned: Boolean, allMuted: Boolean, allUnread: Boolean, archive: Boolean,
    onClose: () -> Unit, onPin: () -> Unit, onUnmute: () -> Unit, onMuteFor: (String, Double) -> Unit,
    onArchive: () -> Unit, onDelete: () -> Unit,
    onReadToggle: () -> Unit, onSelectAll: () -> Unit,
    single: SingleChatActions? = null,
) {
    var more by remember { mutableStateOf(false) }
    var muteOpen by remember { mutableStateOf(false) }
    val c = Fariin.colors
    TopAppBar(
        title = { Text("$count", style = MaterialTheme.typography.titleLarge) },
        navigationIcon = { FloatingIcon(Icons.Filled.Close, contentDescription = "Cancel", onClick = onClose, modifier = Modifier.padding(start = 12.dp, end = 4.dp)) },
        actions = {
          // The actions share one floating capsule, as the chat screen's call buttons do.
          Row(Modifier.padding(end = 12.dp).floating(CircleShape).padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!archive) IconButton(onClick = onPin) {
                Icon(if (allPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin, contentDescription = if (allPinned) "Unpin" else "Pin")
            }
            Box {
                IconButton(onClick = { if (allMuted) onUnmute() else muteOpen = true }) {
                    Icon(if (allMuted) fi(R.drawable.ic_menu_unmute) else fi(R.drawable.ic_menu_mute), contentDescription = if (allMuted) "Unmute" else "Mute")
                }
                MuteForMenu(muteOpen, onDismiss = { muteOpen = false }, onPick = onMuteFor)
            }
            IconButton(onClick = onArchive) {
                Icon(if (archive) Icons.Outlined.Unarchive else fi(R.drawable.ic_archive), contentDescription = if (archive) "Unarchive" else "Archive")
            }
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, contentDescription = "Delete") }
            Box {
                IconButton(onClick = { more = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    DropdownMenuItem(text = { Text(if (allUnread) "Mark as Read" else "Mark as Unread") },
                        leadingIcon = { Icon(if (allUnread) fi(R.drawable.ic_menu_read) else fi(R.drawable.ic_menu_unread), null) },
                        onClick = { more = false; onReadToggle() })
                    DropdownMenuItem(text = { Text("Select All") }, leadingIcon = { Icon(Icons.Outlined.CheckCircle, null) },
                        onClick = { more = false; onSelectAll() })
                    // One chat picked: straight to its info page.
                    single?.let { s ->
                        DropdownMenuItem(text = { Text(s.infoLabel) }, leadingIcon = { Icon(Icons.Outlined.Info, null) },
                            onClick = { more = false; s.onInfo() })
                    }
                }
            }
          }
        },
        windowInsets = WindowInsets(0, 0, 0, 0),
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, titleContentColor = c.label,
            navigationIconContentColor = c.label, actionIconContentColor = c.label),
    )
}

/** The resting search field, a grey capsule; a tap opens the live one. */
@Composable
private fun SearchCapsule(hiddenPx: () -> Float, onOpen: () -> Unit) {
    val c = Fariin.colors
    // iOS search field on scroll (owner's iPhone video 2026-11-05): it does not slide away under the
    // bar, it shrinks in place. The word and the glass fade within the first few points, then the
    // grey capsule gets thinner from the bottom until it is gone; scrolling back grows it again.
    val fill = c.fieldFill
    Box(
        Modifier.fillMaxWidth().height(54.dp)
            .graphicsLayer { translationY = hiddenPx() }   // the top edge stays put while the list moves
            .padding(start = 16.dp, end = 16.dp, top = 2.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().height(44.dp)
                .drawBehind {
                    val h = (size.height - hiddenPx()).coerceAtLeast(0f)
                    if (h > 0.5f) drawRoundRect(fill, size = androidx.compose.ui.geometry.Size(size.width, h),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(minOf(22.dp.toPx(), h / 2f)))
                }
                .clickable(onClickLabel = "Search", onClick = onOpen, indication = null,
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() })
                .padding(horizontal = 14.dp)
                .graphicsLayer { alpha = (1f - hiddenPx() / 10.dp.toPx()).coerceIn(0f, 1f) },
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Search, contentDescription = null, tint = c.secondary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Search", fontSize = 17.sp, color = c.secondary, maxLines = 1)
            }
        }
    }
}

@Composable
private fun SearchBar(query: String, onQuery: (String) -> Unit, onClose: () -> Unit) {
    val c = Fariin.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    // The floating back circle and the search capsule beside it (iOS `.searchable` field).
    Row(Modifier.fillMaxWidth().background(c.bg).height(64.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        FloatingBack(onBack = onClose)
        Spacer(Modifier.width(10.dp))
        com.fariin.app.ui.components.FloatingSearchField(query, onQuery, "Search", Modifier.weight(1f).focusRequester(focus), fill = c.fieldFill)
    }
}

/** The iOS chat-list menu (filters, Message Requests, Archive, Mark All Read), plus the compose sheet's
 *  New Group / New Channel / Find Channels and a Settings shortcut, as an Android overflow menu. */
@Composable
private fun OverflowMenu(
    filter: Int, onFilter: (Int) -> Unit, requests: Int, onRequests: () -> Unit, onArchive: () -> Unit,
    onMarkAllRead: () -> Unit, onNewGroup: () -> Unit, onNewChannel: () -> Unit, onFindChannels: () -> Unit, onSettings: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        FloatingIcon(Icons.Filled.MoreVert, contentDescription = "More", onClick = { open = true }, modifier = Modifier.padding(end = 12.dp))
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val item: @Composable (String, ImageVector, Boolean, () -> Unit) -> Unit = { text, icon, checked, onClick ->
                DropdownMenuItem(
                    text = { Text(text) },
                    leadingIcon = { Icon(icon, null) },
                    trailingIcon = if (checked) ({ Icon(Icons.Filled.Check, contentDescription = "Selected") }) else null,
                    onClick = { open = false; onClick() },
                )
            }
            item("All", fi(R.drawable.ic_menu_read), filter == FILTER_ALL) { onFilter(FILTER_ALL) }
            item("Unread", fi(R.drawable.ic_menu_unread), filter == FILTER_UNREAD) { onFilter(FILTER_UNREAD) }
            item("Groups", fi(R.drawable.ic_gs_group_type), filter == FILTER_GROUPS) { onFilter(FILTER_GROUPS) }
            item("Channels", Icons.Outlined.Campaign, filter == FILTER_CHANNELS) { onFilter(FILTER_CHANNELS) }
            HorizontalDivider()
            if (requests > 0) item("Message Requests ($requests)", fi(R.drawable.ic_message_request), false, onRequests)
            item("Archive", fi(R.drawable.ic_archive), false, onArchive)
            // New Group / New Channel / Find Channels live under the pen, Settings is a tab: the menu
            // keeps iOS's own set (owner 2026-11-03, "a lot of things in there").
            HorizontalDivider()
            item("Mark All Read", Icons.Outlined.CheckCircle, false, onMarkAllRead)
        }
    }
}

/**
 * Message Requests and Archived above the chats, drawn as a chat row (owner 2026-11-03: the
 * floating card there "makes the UI ugly"): the 54 round icon where a photo goes, the name line,
 * the line under it, and the count where a chat's unread badge sits (blue for requests).
 */
@Composable
private fun ListRow(title: String, icon: ImageVector, trailing: String?, subtitle: String? = null, badge: Boolean = false, onClick: () -> Unit) {
    val c = Fariin.colors
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.padding(vertical = 9.dp).size(54.dp).clip(CircleShape).background(c.fieldFill), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = c.secondary, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, color = c.label, maxLines = 1)
            if (!subtitle.isNullOrEmpty()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = c.secondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            if (badge) Box(
                Modifier.height(20.dp).defaultMinSize(minWidth = 20.dp).clip(CircleShape).background(c.accent).padding(horizontal = 5.dp),
                contentAlignment = Alignment.Center,
            ) { Text(trailing, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = c.onAccent, maxLines = 1) }
            else Text(trailing, style = MaterialTheme.typography.labelLarge, color = c.secondary)
        }
    }
}

@Composable
private fun Centered(text: String, action: Pair<String, () -> Unit>? = null) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = Fariin.colors.secondary)
        // The action as a floating capsule, not a bare text link.
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            Box(Modifier.height(44.dp).floating(CircleShape).clickable(onClick = action.second).padding(horizontal = 22.dp),
                contentAlignment = Alignment.Center) {
                Text(action.first, style = MaterialTheme.typography.labelLarge, color = Fariin.colors.accent)
            }
        }
    }
}

/** Section label, Material style: small, in the brand blue, 16 from the edge. */
/** A person found by username (iOS newPersonRow): photo, name, the @handle in blue. */
@Composable
private fun PersonRow(p: com.fariin.app.data.Person, onClick: () -> Unit) {
    val c = Fariin.colors
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(p.photoUrl, 54.dp, Modifier.padding(vertical = 9.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name.ifEmpty { p.handle }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium,
                color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (p.handle.isNotEmpty()) Text("@" + p.handle, style = MaterialTheme.typography.bodyMedium, color = c.secondary, maxLines = 1)
        }
    }
}

/** iOS SearchSectionHeader: "Recent" with a Clear on the right. */
@Composable
private fun RecentHeader(onClear: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Recent", style = MaterialTheme.typography.titleSmall, color = Fariin.colors.accent, modifier = Modifier.weight(1f))
        TextButton(onClick = onClear) { Text("Clear", color = Fariin.colors.accent) }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = Fariin.colors.accent,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatRow(
    conv: Conversation, me: String, decryptor: MessageDecryptor,
    selected: Boolean = false,
    onOpen: () -> Unit,
    onLongPress: () -> Unit = {},
    ring: List<Boolean> = emptyList(),
    onStoryTap: (() -> Unit)? = null,
    /** The story group this photo opens: the viewer zooms out of it and back into it (hero). */
    storyKey: String? = null,
) {
    val c = Fariin.colors
    val context = LocalContext.current
    val unread = if (conv.isBlockedByMe(me)) 0 else conv.unread(me)
    val now = System.currentTimeMillis().toDouble()
    val muted = conv.isMuted(me, now)
    val keys by CryptoHub.keysEpoch.collectAsState()
    // Battery/lag audit 2026-11-06: no box is opened during composition. A line opened before
    // shows at once; a new one opens on a background thread, the old words staying until then.
    val photoLabel: (String) -> String = { raw ->
        // iOS photoPreviewLabel: with the little picture beside it the emoji goes ("Photo", not "📷 Photo").
        if (!conv.lastImageUrl.isNullOrEmpty()) listOf("📷 ", "🎥 ", "🎬 ").firstOrNull { raw.startsWith(it) }?.let { raw.removePrefix(it) } ?: raw else raw
    }
    val cachedPreview = remember(conv, me, keys) { ChatList.cachedPreviewLine(conv, me, decryptor) { null }?.let(photoLabel) }
    val openedPreview by androidx.compose.runtime.produceState<String?>(null, conv, me, keys) {
        if (cachedPreview == null) value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            photoLabel(ChatList.previewLine(conv, me, decryptor) { null })
        }
    }
    val preview = cachedPreview ?: openedPreview ?: ""
    // iOS: a chat with unsent words in its box says "Draft:" (red) and the words, not the last line.
    // Only this chat's draft: reading the whole map redrew every row whenever any chat's draft
    // changed, which leaving a chat does (it saves or clears that chat's draft).
    val drafts = com.fariin.app.local.Drafts.map.collectAsState()
    val draftState = remember(conv.id) { derivedStateOf { drafts.value[conv.id] } }
    val draft = draftState.value
    // iOS previewRow: a small grey glyph before a pin notice, a contact or a location.
    val glyph = if (draft.isNullOrEmpty()) ChatList.previewIcon(preview) else null
    val line = if (glyph != null) androidx.compose.ui.text.buildAnnotatedString {
        appendInlineContent("glyph", "[icon]"); append(" "); append(preview)
    } else if (draft.isNullOrEmpty()) androidx.compose.ui.text.AnnotatedString(preview) else
        androidx.compose.ui.text.buildAnnotatedString {
            pushStyle(androidx.compose.ui.text.SpanStyle(color = c.red)); append("Draft: "); pop()
            append(draft.replace('\n', ' '))
        }
    val time = remember(conv.displayUpdatedAt(me)) { timeLabel(conv.displayUpdatedAt(me), DateFormat.is24HourFormat(context)) }

    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f) else Color.Transparent)
            // The tap's grey before the chat opens (ui/components/ListPress).
            .listPress(onLongClick = onLongPress, onClick = onOpen)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.padding(vertical = 9.dp)) {
            // The reference app's Android row: a 54 photo, 9 above and below (72 tall). With a story up
            // the photo is 48 inside a 56 ring overhanging the slot by one all round (iOS's 2 gap).
            val view = androidx.compose.ui.platform.LocalView.current
            val half = with(androidx.compose.ui.platform.LocalDensity.current) { (if (conv.isChannel) 48.dp * 0.3f else 24.dp).toPx() }
            if (storyKey != null) androidx.compose.runtime.DisposableEffect(storyKey) {
                onDispose { com.fariin.app.ui.stories.StoryOrigins.rects.remove(storyKey); com.fariin.app.ui.stories.StoryOrigins.radii.remove(storyKey) }
            }
            Box(Modifier.size(54.dp).then(if (onStoryTap != null) Modifier.clickable(onClickLabel = "View story", onClick = onStoryTap).semantics { contentDescription = "Story" } else Modifier)
                .then(if (storyKey == null) Modifier else Modifier.onGloballyPositioned { lc ->
                    val loc = IntArray(2); view.getLocationOnScreen(loc)
                    val p = lc.positionInWindow()
                    // The 48 photo inside the 54 slot, in screen pixels (the viewer is its own window).
                    val inset = (lc.size.width - lc.size.width * 48f / 54f) / 2
                    com.fariin.app.ui.stories.StoryOrigins.rects[storyKey] = androidx.compose.ui.geometry.Rect(
                        p.x + loc[0] + inset, p.y + loc[1] + inset, p.x + loc[0] + lc.size.width - inset, p.y + loc[1] + lc.size.height - inset)
                    com.fariin.app.ui.stories.StoryOrigins.radii[storyKey] = half
                }.graphicsLayer { alpha = if (com.fariin.app.ui.stories.StoryOrigins.hiddenKey == storyKey) 0f else 1f }),
                contentAlignment = Alignment.Center) {
                Avatar(conv.displayPhoto(me), if (ring.isEmpty()) 54.dp else 48.dp, squircle = conv.isChannel)
                if (ring.isNotEmpty()) com.fariin.app.ui.stories.StoryRing(ring, Modifier.requiredSize(56.dp), squircle = conv.isChannel)
            }
            // Disappearing messages on: the timer badge on the photo's lower right, hanging 5 past
            // it (iOS DisappearingAvatarBadge: 24 glass disc, grey timer).
            if (conv.disappearSeconds > 0) {
                Box(
                    Modifier.align(Alignment.BottomEnd).offset(5.dp, 5.dp).size(24.dp).floating(CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.ic_disappearing), contentDescription = "Disappearing messages on",
                        tint = Color(0xFF8E8E93), modifier = Modifier.size(15.dp))
                }
            }
            // Selection mode: a tick badge on the photo, as Android messengers mark chosen rows.
            if (selected) {
                Box(
                    Modifier.align(Alignment.BottomEnd).size(22.dp).clip(CircleShape).background(c.bg).padding(2.dp)
                        .clip(CircleShape).background(c.blue),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Check, contentDescription = "Selected", tint = Color.White, modifier = Modifier.size(14.dp)) }
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f).padding(top = 4.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        conv.displayName(me),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (unread > 0) FontWeight.Bold else FontWeight.Medium,
                        color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    // The blue verified tick beside a verified channel's name (iOS chat list).
                    if (conv.verified) {
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Filled.Verified, contentDescription = "Verified",
                            tint = c.blue, modifier = Modifier.size(16.dp))
                    }
                    if (muted) {
                        Spacer(Modifier.width(6.dp))
                        Icon(ImageVector.vectorResource(com.fariin.app.R.drawable.ic_bell_off), contentDescription = "Muted",
                            tint = c.tertiary, modifier = Modifier.size(15.dp))
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(time, style = MaterialTheme.typography.labelMedium, color = if (unread > 0) c.accent else c.secondary, maxLines = 1)
            }
            Row(verticalAlignment = Alignment.Top) {
                // The last photo or video poster beside the words (iOS lastImageUrl, media worker).
                com.fariin.app.ui.thread.ChatListThumb(conv, draft.isNullOrEmpty())
                // Two lines tall always, so every row is the same height (iOS, the hidden " \n ").
                Text(
                    line, style = MaterialTheme.typography.bodyMedium, color = c.secondary,
                    inlineContent = if (glyph == null) emptyMap() else mapOf("glyph" to androidx.compose.foundation.text.InlineTextContent(
                        androidx.compose.ui.text.Placeholder(14.sp, 14.sp, androidx.compose.ui.text.PlaceholderVerticalAlign.TextCenter)) {
                        Icon(when (glyph) {
                            ChatList.PreviewIcon.PIN -> Icons.Filled.PushPin
                            ChatList.PreviewIcon.CONTACT -> Icons.Filled.Person
                            ChatList.PreviewIcon.LOCATION -> Icons.Filled.LocationOn
                        }, contentDescription = null, tint = c.secondary, modifier = Modifier.fillMaxSize())
                    }),
                    maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 1.dp),
                ) {
                    if (conv.isPinned(me) && unread == 0) {
                        Icon(Icons.Filled.PushPin, contentDescription = "Pinned", tint = c.tertiary, modifier = Modifier.size(16.dp))
                    }
                    // Never beside a call: it is a record, not a message with a sent / read state (iOS).
                    if (conv.lastIsMine(me) && !conv.isChannel && !(preview.startsWith("↗ ") || preview.startsWith("📞 ") || preview.startsWith("📹 "))) Ticks(conv, me)
                    if (conv.hasUnreadReply(me)) {
                        Box(Modifier.size(20.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
                            Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = "Someone replied to you",
                                tint = c.onAccent, modifier = Modifier.size(12.dp))
                        }
                    }
                    if (unread > 0) {
                        Box(
                            Modifier.height(20.dp).defaultMinSize(minWidth = 20.dp).clip(CircleShape)
                                .background(if (muted) c.secondary else c.accent).padding(horizontal = 5.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(if (unread > 99) "99+" else "$unread", fontSize = 12.sp,
                                fontWeight = FontWeight.Bold, color = c.onAccent, maxLines = 1)
                        }
                    } else if (conv.manuallyUnread(me)) {
                        Box(Modifier.size(12.dp).clip(CircleShape).background(c.accent))
                    }
                }
            }
        }
    }
}

/** One tick sent, two delivered, two in the accent colour read. TODO: the exact iOS tick art. */
@Composable
private fun Ticks(conv: Conversation, me: String) {
    val c = Fariin.colors
    if (ChatListRules.lastSendPending(conv, me) { com.fariin.app.local.SendQueue.pending(me, conv.id).isNotEmpty() }) {
        // Still on this phone (sent offline): the clock, as the chat's own bubble shows.
        Icon(Icons.Filled.Schedule, contentDescription = "Sending", tint = c.secondary, modifier = Modifier.size(15.dp))
        return
    }
    val read = conv.lastReadByOther(me)
    val delivered = read || conv.lastDeliveredToOther(me)
    // The bubbles' own drawn marks (iOS BubbleTicks), at their drawn size, not stretched.
    Icon(
        androidx.compose.ui.res.painterResource(if (delivered) R.drawable.ic_tick_double else R.drawable.ic_tick_single),
        contentDescription = if (read) "Read" else if (delivered) "Delivered" else "Sent",
        tint = if (read) c.blue else c.secondary,   // iOS: read is blue (0A84FF) in the list
    )
}

/** iOS ChatRow.timeStr: time today, "Yesterday", weekday this week, then "Mar 4" / "Mar 4, 2025". */
fun timeLabel(ms: Double, is24h: Boolean, nowMs: Long = System.currentTimeMillis()): String {
    if (ms <= 0) return ""
    val d = Date(ms.toLong())
    val then = Calendar.getInstance().apply { time = d }
    val now = Calendar.getInstance().apply { timeInMillis = nowMs }
    val startOfToday = (now.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    val dayMs = 24L * 60 * 60 * 1000
    val loc = Locale.getDefault()
    return when {
        d.time >= startOfToday.timeInMillis ->
            SimpleDateFormat(if (is24h) "HH:mm" else "h:mm a", loc).format(d)
        d.time >= startOfToday.timeInMillis - dayMs -> "Yesterday"
        nowMs - d.time < 7 * dayMs -> SimpleDateFormat("EEE", loc).format(d)
        then.get(Calendar.YEAR) != now.get(Calendar.YEAR) -> SimpleDateFormat("MMM d, yyyy", loc).format(d)
        else -> SimpleDateFormat("MMM d", loc).format(d)
    }
}

/** Pure rules for the chat list row, kept here for the tests. */
object ChatListRules {
    /**
     * My newest message has not reached the server: Firestore still holds this phone's write to
     * the chat doc AND the send queue still has a text for this chat. The queue check keeps an
     * offline "mark read" (also a local write) from turning a read tick into a clock.
     */
    fun lastSendPending(conv: Conversation, me: String, queued: () -> Boolean): Boolean =
        conv.lastIsMine(me) && !conv.isChannel && conv.localPending && queued()
}

/** Fariin's own drawings (converted from the iOS assets) where iOS has one; stock icons elsewhere. */
@Composable
private fun fi(@androidx.annotation.DrawableRes id: Int): ImageVector = ImageVector.vectorResource(id)
