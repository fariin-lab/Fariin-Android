package com.fariin.app.ui.chats

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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.CheckCircle
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.data.ChatList
import com.fariin.app.data.Conversation
import com.fariin.app.data.CryptoHub
import com.fariin.app.data.MessageDecryptor
import com.fariin.app.ui.components.Avatar
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
) {
    val c = Fariin.colors
    val people = com.fariin.app.ui.people.LocalPeopleNav.current
    val channels = com.fariin.app.ui.channels.LocalChannelNav.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var filter by rememberSaveable { mutableIntStateOf(FILTER_ALL) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var showArchive by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val selecting = selected.isNotEmpty()
    val online = rememberOnline()
    val link by com.fariin.app.data.ConnectionStatus.state.collectAsState()
    val linkLabel = com.fariin.app.data.ConnectionStatus.label(link)

    // System back peels one layer at a time: selection, then search, then the archive page.
    BackHandler(enabled = selecting) { selected = emptySet() }
    BackHandler(enabled = !selecting && searching) { searching = false; query = "" }
    BackHandler(enabled = !selecting && !searching && showArchive) { showArchive = false }

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
    val pinned = if (showArchive) emptyList() else shown.filter { it.isPinned(me) }
    val rest = shown.filter { showArchive || !it.isPinned(me) }
    val selectedConvs = (state.chats + state.archived).filter { it.id in selected }

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
    val scroll = TopAppBarDefaults.enterAlwaysScrollBehavior(barState)

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = c.bg,
        // Bottom inset = the floating tab bar (ui/shell), so the FABs sit above it.
        contentWindowInsets = WindowInsets(bottom = com.fariin.app.ui.shell.bottomBarInset()),
        topBar = {
            Column(Modifier.background(if (selecting) MaterialTheme.colorScheme.surfaceContainerHigh else c.bg).statusBarsPadding()) {
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
                        onMute = {
                            val on = !selectedConvs.all { it.isMuted(me, System.currentTimeMillis().toDouble()) }
                            act(if (on) "Muted" else "Unmuted") { ChatListActions.setMuted(me, it, on) }
                        },
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
                        TopAppBar(
                            title = { Text(title, style = MaterialTheme.typography.titleLarge) },
                            navigationIcon = {
                                if (showArchive) IconButton(onClick = { showArchive = false }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                }
                            },
                            actions = {
                                IconButton(onClick = { searching = true }) { Icon(Icons.Outlined.Search, contentDescription = "Search") }
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
                                containerColor = c.bg, scrolledContainerColor = c.bg,
                                titleContentColor = c.label, actionIconContentColor = c.label, navigationIconContentColor = c.label,
                            ),
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            AnimatedVisibility(!selecting && !searching && !showArchive, enter = scaleIn(), exit = scaleOut()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SmallFloatingActionButton(
                        onClick = onNewStory,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = c.label,
                    ) { Icon(Icons.Outlined.PhotoCamera, contentDescription = "New Story") }
                    FloatingActionButton(
                        onClick = people.newChat,
                        containerColor = c.blue,
                        contentColor = Color.White,
                    ) { Icon(Icons.Outlined.Edit, contentDescription = "New Chat") }
                }
            }
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(top = pad.calculateTopPadding())) {
            if (!showArchive && !searching && filter == FILTER_ALL && state.requests.isNotEmpty()) {
                ListRow("Message Requests", Icons.Outlined.Inbox, trailing = "${state.requests.size}", onClick = people.requests)
            }
            when {
                state.loading && source.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = c.secondary, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                }
                state.error != null && source.isEmpty() -> Centered(state.error)
                shown.isEmpty() -> Centered(when {
                    query.isNotBlank() -> "No results"
                    showArchive -> "No archived chats"
                    filter == FILTER_UNREAD -> "No unread chats"
                    filter == FILTER_GROUPS -> "No groups yet"
                    filter == FILTER_CHANNELS -> "No channels yet"
                    else -> "No chats yet"
                }, action = if (filter == FILTER_CHANNELS && query.isBlank()) ("Find Channels" to channels.find) else null)
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = com.fariin.app.ui.shell.bottomBarInset() + 72.dp)) {
                    if (!showArchive && !searching && filter == FILTER_ALL && state.archived.isNotEmpty()) {
                        item(key = "archive") {
                            ListRow("Archived", Icons.Outlined.Archive, trailing = "${state.archived.size}") { showArchive = true }
                        }
                    }
                    val row: @Composable (Conversation) -> Unit = { conv ->
                        ChatRow(conv, me, decryptor,
                            selected = conv.id in selected,
                            onOpen = { if (selecting) toggle(conv) else onOpen(conv) },
                            onLongPress = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); toggle(conv) })
                    }
                    if (pinned.isNotEmpty()) {
                        item(key = "h-pinned") { SectionHeader("Pinned") }
                        items(pinned, key = { it.id }, contentType = { "chat" }) { row(it) }
                        item(key = "h-chats") { SectionHeader("Chats") }
                    }
                    items(rest, key = { it.id }, contentType = { "chat" }) { row(it) }
                }
            }
        }
    }

    if (confirmDelete) {
        val n = selected.size
        val groups = selectedConvs.count { it.isGroup }
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(if (n == 1) "Delete this chat?" else "Delete $n chats?") },
            text = {
                Text(if (groups > 0) "Groups stay in your list: leave a group from its info page. The other chats are removed from this phone's list."
                else "The chat is removed from your list. The other person keeps their copy.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    val list = selectedConvs.filter { !it.isGroup }
                    selected = emptySet()
                    scope.launch {
                        val ok = list.map { ChatListActions.deleteForMe(me, it) }.all { it }
                        Snack.show(if (ok) (if (list.size == 1) "Chat deleted" else "${list.size} chats deleted") else "Couldn't delete. Try again.")
                    }
                }) { Text("Delete", color = c.red) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionBar(
    count: Int, allPinned: Boolean, allMuted: Boolean, allUnread: Boolean, archive: Boolean,
    onClose: () -> Unit, onPin: () -> Unit, onMute: () -> Unit, onArchive: () -> Unit, onDelete: () -> Unit,
    onReadToggle: () -> Unit, onSelectAll: () -> Unit,
) {
    var more by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text("$count", style = MaterialTheme.typography.titleLarge) },
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Cancel") } },
        actions = {
            if (!archive) IconButton(onClick = onPin) {
                Icon(if (allPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin, contentDescription = if (allPinned) "Unpin" else "Pin")
            }
            IconButton(onClick = onMute) {
                Icon(if (allMuted) Icons.Outlined.Notifications else Icons.Outlined.NotificationsOff, contentDescription = if (allMuted) "Unmute" else "Mute")
            }
            IconButton(onClick = onArchive) {
                Icon(if (archive) Icons.Outlined.Unarchive else Icons.Outlined.Archive, contentDescription = if (archive) "Unarchive" else "Archive")
            }
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, contentDescription = "Delete") }
            Box {
                IconButton(onClick = { more = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    DropdownMenuItem(text = { Text(if (allUnread) "Mark as Read" else "Mark as Unread") },
                        leadingIcon = { Icon(if (allUnread) Icons.Outlined.MarkChatRead else Icons.Outlined.MarkChatUnread, null) },
                        onClick = { more = false; onReadToggle() })
                    DropdownMenuItem(text = { Text("Select All") }, leadingIcon = { Icon(Icons.Outlined.CheckCircle, null) },
                        onClick = { more = false; onSelectAll() })
                }
            }
        },
        windowInsets = WindowInsets(0, 0, 0, 0),
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    )
}

@Composable
private fun SearchBar(query: String, onQuery: (String) -> Unit, onClose: () -> Unit) {
    val c = Fariin.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = c.label) }
        TextField(
            value = query, onValueChange = onQuery, singleLine = true,
            placeholder = { Text("Search") },
            modifier = Modifier.weight(1f).focusRequester(focus),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            ),
        )
        if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Filled.Close, contentDescription = "Clear", tint = c.secondary) }
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
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val item: @Composable (String, ImageVector, Boolean, () -> Unit) -> Unit = { text, icon, checked, onClick ->
                DropdownMenuItem(
                    text = { Text(text) },
                    leadingIcon = { Icon(icon, null) },
                    trailingIcon = if (checked) ({ Icon(Icons.Filled.Check, contentDescription = "Selected") }) else null,
                    onClick = { open = false; onClick() },
                )
            }
            item("All", Icons.Outlined.MarkChatRead, filter == FILTER_ALL) { onFilter(FILTER_ALL) }
            item("Unread", Icons.Outlined.MarkChatUnread, filter == FILTER_UNREAD) { onFilter(FILTER_UNREAD) }
            item("Groups", Icons.Outlined.Group, filter == FILTER_GROUPS) { onFilter(FILTER_GROUPS) }
            item("Channels", Icons.Outlined.Campaign, filter == FILTER_CHANNELS) { onFilter(FILTER_CHANNELS) }
            HorizontalDivider()
            if (requests > 0) item("Message Requests ($requests)", Icons.Outlined.Inbox, false, onRequests)
            item("Archive", Icons.Outlined.Archive, false, onArchive)
            item("New Group", Icons.Outlined.Group, false, onNewGroup)
            item("New Channel", Icons.Outlined.Campaign, false, onNewChannel)
            item("Find Channels", Icons.Outlined.Explore, false, onFindChannels)
            item("Settings", Icons.Outlined.Settings, false, onSettings)
            HorizontalDivider()
            item("Mark All Read", Icons.Outlined.CheckCircle, false, onMarkAllRead)
        }
    }
}

/** A one-line Material list row (Message Requests, Archived) above the chats. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListRow(title: String, icon: ImageVector, trailing: String?, onClick: () -> Unit) {
    val c = Fariin.colors
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick).height(56.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(56.dp), contentAlignment = Alignment.Center) { Icon(icon, null, tint = c.secondary) }
        Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, color = c.label, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.labelLarge, color = c.blue)
    }
}

@Composable
private fun Centered(text: String, action: Pair<String, () -> Unit>? = null) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = Fariin.colors.secondary)
        if (action != null) TextButton(onClick = action.second) { Text(action.first) }
    }
}

/** Section label, Material style: small, in the brand blue, 16 from the edge. */
@Composable
private fun SectionHeader(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = Fariin.colors.blue,
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
) {
    val c = Fariin.colors
    val context = LocalContext.current
    val unread = if (conv.isBlockedByMe(me)) 0 else conv.unread(me)
    val now = System.currentTimeMillis().toDouble()
    val muted = conv.isMuted(me, now)
    val keys by CryptoHub.keysEpoch.collectAsState()
    val preview = remember(conv, me, keys) { ChatList.previewLine(conv, me, decryptor) { null } }
    // iOS: a chat with unsent words in its box says "Draft:" (red) and the words, not the last line.
    val drafts by com.fariin.app.local.Drafts.map.collectAsState()
    val draft = drafts[conv.id]
    val line = if (draft.isNullOrEmpty()) androidx.compose.ui.text.AnnotatedString(preview) else
        androidx.compose.ui.text.buildAnnotatedString {
            pushStyle(androidx.compose.ui.text.SpanStyle(color = c.red)); append("Draft: "); pop()
            append(draft.replace('\n', ' '))
        }
    val time = remember(conv.displayUpdatedAt(me)) { timeLabel(conv.displayUpdatedAt(me), DateFormat.is24HourFormat(context)) }

    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f) else Color.Transparent)
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.padding(vertical = 10.dp)) {
            Avatar(conv.displayPhoto(me), 56.dp, squircle = conv.isChannel)
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
        Column(Modifier.weight(1f).padding(top = 7.dp, bottom = 9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        conv.displayName(me),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (unread > 0) FontWeight.Bold else FontWeight.Medium,
                        color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (muted) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Filled.NotificationsOff, contentDescription = "Muted",
                            tint = c.tertiary, modifier = Modifier.size(15.dp))
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(time, style = MaterialTheme.typography.labelMedium, color = if (unread > 0) c.blue else c.secondary, maxLines = 1)
            }
            Row(verticalAlignment = Alignment.Top) {
                // The last photo or video poster beside the words (iOS lastImageUrl, media worker).
                com.fariin.app.ui.thread.ChatListThumb(conv, draft.isNullOrEmpty())
                // Two lines tall always, so every row is the same height (iOS, the hidden " \n ").
                Text(
                    line, style = MaterialTheme.typography.bodyMedium, color = c.secondary,
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
                    if (conv.lastIsMine(me) && !conv.isChannel) Ticks(conv, me)
                    if (conv.hasUnreadReply(me)) {
                        Box(Modifier.size(20.dp).clip(CircleShape).background(c.blue), contentAlignment = Alignment.Center) {
                            Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = "Someone replied to you",
                                tint = Color.White, modifier = Modifier.size(12.dp))
                        }
                    }
                    if (unread > 0) {
                        Box(
                            Modifier.height(20.dp).defaultMinSize(minWidth = 20.dp).clip(CircleShape)
                                .background(if (muted) c.secondary else c.blue).padding(horizontal = 5.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(if (unread > 99) "99+" else "$unread", fontSize = 12.sp,
                                fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
                        }
                    } else if (conv.manuallyUnread(me)) {
                        Box(Modifier.size(12.dp).clip(CircleShape).background(c.blue))
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
    val read = conv.lastReadByOther(me)
    val delivered = read || conv.lastDeliveredToOther(me)
    Icon(
        if (delivered) Icons.Filled.DoneAll else Icons.Filled.Done,
        contentDescription = if (read) "Read" else if (delivered) "Delivered" else "Sent",
        tint = if (read) c.accent else c.secondary,
        modifier = Modifier.size(16.dp),
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
