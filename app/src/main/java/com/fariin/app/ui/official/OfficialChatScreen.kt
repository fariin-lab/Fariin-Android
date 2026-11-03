package com.fariin.app.ui.official

import android.text.format.DateFormat
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Forward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.fariin.app.R
import com.fariin.app.data.Announcement
import com.fariin.app.data.AnnouncementButton
import com.fariin.app.data.AnnouncementKind
import com.fariin.app.data.OfficialChannel
import com.fariin.app.data.OwnerTransfer
import com.fariin.app.ui.chats.timeLabel
import com.fariin.app.ui.components.FariinTopBar
import com.fariin.app.ui.theme.Fariin
import kotlinx.coroutines.launch

/** The official chat's page (iOS OfficialChatView): announcements, their buttons, no composer. */
@Composable
fun OfficialChatScreen(onBack: () -> Unit) {
    val c = Fariin.colors
    val view by OfficialChannel.view.collectAsState()
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val is24h = DateFormat.is24HourFormat(context)
    var pendingReject by remember { mutableStateOf<String?>(null) }
    var rejecting by remember { mutableStateOf(false) }
    var rejectResult by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<List<String>?>(null) }
    var forwardTexts by remember { mutableStateOf<List<String>?>(null) }
    // Select (iOS startSelection): ticked ids; the page is in selection while any are ticked.
    val picked = remember { androidx.compose.runtime.mutableStateListOf<String>() }
    val selecting = picked.isNotEmpty()
    androidx.activity.compose.BackHandler(selecting) { picked.clear() }
    // A note that leaves the list (deleted, expired) leaves the selection too.
    LaunchedEffect(view.visible) { picked.retainAll(view.visible.map { it.id }.toSet()) }
    fun toggle(id: String) { if (!picked.remove(id)) picked.add(id) }
    /** The ticked notes in list order, oldest first. */
    fun pickedNotes() = view.visible.filter { it.id in picked }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val listState = rememberLazyListState()
    var confirmClear by remember { mutableStateOf(false) }
    // Double tap reacts with Settings > Chats > Quick Reaction, as a chat message does.
    val quick = remember {
        context.getSharedPreferences(com.fariin.app.ui.settings.CHAT_PREFS, android.content.Context.MODE_PRIVATE)
            .getString(com.fariin.app.ui.settings.SettingsRules.QUICK_KEY, null)?.takeIf { it.isNotEmpty() } ?: "❤️"
    }
    // Search in the chat itself, the normal chat's way (iOS): top field, up/down and "n of N" at the
    // bottom, each step scrolls to the match. The position is kept counted from the newest.
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var fromNewest by remember { mutableStateOf(0) }
    val matches = remember(view.visible, query) { if (searching) OfficialChannel.searchMatches(view.visible, query) else emptyList() }
    val matchIndex = if (matches.isEmpty()) 0 else matches.size - 1 - minOf(fromNewest, matches.size - 1)
    val current = matches.getOrNull(matchIndex)
    fun closeSearch() { searching = false; query = ""; fromNewest = 0 }
    androidx.activity.compose.BackHandler(searching && !selecting) { closeSearch() }
    LaunchedEffect(current) {
        val at = view.visible.indexOfFirst { it.id == current }
        if (at >= 0) listState.animateScrollToItem(at)
    }

    // Open = read, and anything that lands while it is open is read too.
    LaunchedEffect(view.visible) { OfficialChannel.markRead() }
    LaunchedEffect(view.visible.size) { if (view.visible.isNotEmpty() && !searching) listState.scrollToItem(view.visible.size - 1) }

    fun tap(b: AnnouncementButton) {
        when (b.action) {
            AnnouncementButton.LINK -> try { uri.openUri(b.value) } catch (_: Exception) {}
            AnnouncementButton.REJECT_OWNERSHIP -> if (!rejecting) pendingReject = b.value
        }
    }

    pendingReject?.let { cid ->
        AlertDialog(onDismissRequest = { pendingReject = null }, title = { Text(OfficialChannel.REJECT_TITLE) },
            text = { Text(OfficialChannel.REJECT_MESSAGE) },
            confirmButton = { TextButton({
                pendingReject = null
                rejecting = true
                scope.launch {
                    rejectResult = try { OfficialChannel.rejectDone(OfficialChannel.rejectOwnership(cid)) }
                        catch (e: OwnerTransfer.Refusal) { e.message ?: OfficialChannel.REJECT_OFFLINE }
                    rejecting = false
                }
            }) { Text("Reject", color = c.red) } },
            dismissButton = { TextButton({ pendingReject = null }) { Text("Cancel", color = c.blue) } }, containerColor = c.card)
    }
    // Forward: the note goes as its words, marked forwarded, like a forwarded chat message (iOS forwardable).
    forwardTexts?.let { texts ->
        val me = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
        if (me == null) forwardTexts = null
        else com.fariin.app.ui.thread.ForwardSheet(me, onDismiss = { forwardTexts = null }) { targets ->
            forwardTexts = null
            picked.clear()
            OfficialChannel.forward(context, me, texts, targets)
        }
    }
    pendingDelete?.let { ids ->
        AlertDialog(onDismissRequest = { pendingDelete = null },
            title = { Text(if (ids.size == 1) "Delete this message?" else "Delete ${ids.size} messages?") },
            confirmButton = { TextButton({ OfficialChannel.hide(ids); pendingDelete = null; picked.clear() }) { Text("Delete for Me", color = c.red) } },
            dismissButton = { TextButton({ pendingDelete = null }) { Text("Cancel", color = c.blue) } }, containerColor = c.card)
    }
    // Delete All in the selection bar clears the whole chat for me (iOS showClearConfirm).
    if (confirmClear) {
        AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("Clear this chat?") },
            confirmButton = { TextButton({ OfficialChannel.clearHistory(); confirmClear = false; picked.clear() }) { Text("Clear", color = c.red) } },
            dismissButton = { TextButton({ confirmClear = false }) { Text("Cancel", color = c.blue) } }, containerColor = c.card)
    }
    rejectResult?.let { text ->
        AlertDialog(onDismissRequest = { rejectResult = null }, text = { Text(text) },
            confirmButton = { TextButton({ rejectResult = null }) { Text("OK", color = c.blue) } }, containerColor = c.card)
    }

    // The grey page with the notes as white rounded cards and no hard lines at the bar edges
    // (iOS OfficialChatView), the back in its floating circle as in a chat.
    Column(Modifier.fillMaxSize().background(c.bgSecondary)) {
        Box(Modifier.fillMaxWidth().background(c.bgSecondary).statusBarsPadding()) {
            // The bell (iOS bellButton): mutes the announcements; security posts still come.
            val muted = view.state.isMutedNow(System.currentTimeMillis().toDouble())
            if (selecting) FariinTopBar("${picked.size} Selected", onBack = { picked.clear() }, container = c.bgSecondary) {
                TextButton({ confirmClear = true }) { Text("Delete All", color = c.red) }
            }
            else if (searching) {
                // Search owns the top while it is open, as in a normal chat.
                val focus = remember { androidx.compose.ui.focus.FocusRequester() }
                LaunchedEffect(Unit) { focus.requestFocus() }
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    com.fariin.app.ui.components.FloatingBack({ closeSearch() }, Modifier.padding(start = 4.dp, end = 8.dp),
                        contentDescription = "Close search")
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) Text("Search", fontSize = 18.sp, color = c.secondary)
                        androidx.compose.foundation.text.BasicTextField(query, { query = it }, singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 18.sp, color = c.label),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(c.label),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { if (fromNewest > 0) fromNewest-- }),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus))
                    }
                    if (query.isNotEmpty()) androidx.compose.material3.IconButton({ query = "" }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear", tint = c.label)
                    }
                }
            }
            else FariinTopBar(OfficialChannel.NAME, onBack = onBack, subtitle = OfficialChannel.SUBTITLE,
                container = c.bgSecondary) {
                // Search and the bell in floating circles, as the chat header's buttons.
                com.fariin.app.ui.components.FloatingIcon(Icons.Filled.Search, "Search",
                    onClick = { searching = true; query = ""; fromNewest = 0 }, modifier = Modifier.padding(end = 8.dp))
                com.fariin.app.ui.components.FloatingIcon(
                    androidx.compose.ui.res.painterResource(if (muted) com.fariin.app.R.drawable.ic_bell_off_outline else com.fariin.app.R.drawable.ic_notifications),
                    if (muted) "Notifications off" else "Notifications on",
                    onClick = { OfficialChannel.setMuted(!muted) }, modifier = Modifier.padding(end = 8.dp))
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(view.visible, key = { it.id }) { a ->
                val now = System.currentTimeMillis().toDouble()
                AnnouncementCard(a, timeLabel(a.sortAt, is24h), a.buttons.filter { it.isUsable(now) }, ::tap,
                    ticked = if (selecting) a.id in picked else null, onToggle = { toggle(a.id) },
                    onCopy = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(a.plainText)) },
                    onForward = { forwardTexts = listOf(a.plainText) },
                    onSelect = { picked.add(a.id) },
                    onDelete = { pendingDelete = listOf(a.id) },
                    mine = view.state.reactions[a.id], onReact = { OfficialChannel.react(a.id, it) },
                    // Not on a picture, as iOS (a picture opens on one tap there).
                    onDoubleTap = if (a.mediaUrl == null) ({ OfficialChannel.react(a.id,
                        com.fariin.app.data.MessageRules.nextReaction(view.state.reactions[a.id], quick)) }) else null,
                    current = a.id == current)
            }
        }
        // The chat's own selection bar: Delete, "N Selected", Forward.
        if (selecting) Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.IconButton({ pendingDelete = pickedNotes().map { it.id } }) {
                Icon(Icons.Outlined.Delete, contentDescription = "Delete", tint = c.red)
            }
            Text("${picked.size} Selected", style = MaterialTheme.typography.titleSmall, color = c.label,
                textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            androidx.compose.material3.IconButton({ forwardTexts = pickedNotes().map { it.plainText } }) {
                Icon(Icons.AutoMirrored.Outlined.Forward, contentDescription = "Forward", tint = c.blue)
            }
        }
        // The normal chat's up/down and "n of N" (iOS searchNavBar); up is older.
        else if (searching) Row(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.IconButton({ fromNewest++ }, enabled = matches.isNotEmpty() && matchIndex > 0) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Previous")
            }
            androidx.compose.material3.IconButton({ fromNewest-- }, enabled = matches.isNotEmpty() && matchIndex < matches.size - 1) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Next")
            }
            Spacer(Modifier.weight(1f))
            if (matches.isNotEmpty() || query.trim().length >= 2)
                Text(if (matches.isEmpty()) "No results" else "${matchIndex + 1} of ${matches.size}",
                    style = MaterialTheme.typography.bodyMedium, color = c.secondary, modifier = Modifier.padding(horizontal = 16.dp))
        }
        // Somebody who blocked this chat before keeps Unblock to get it back (iOS: no Block since 2026-10-31).
        else if (view.state.blocked) TextButton({ OfficialChannel.setBlocked(false) },
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 4.dp)) { Text("Unblock", color = c.blue) }
        else Text(OfficialChannel.CANNOT_REPLY, style = MaterialTheme.typography.bodyMedium, color = c.secondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 16.dp, horizontal = 16.dp))
    }
}

private fun kindIcon(k: AnnouncementKind): ImageVector = when (k) {
    AnnouncementKind.FEATURE -> Icons.Outlined.AutoAwesome
    AnnouncementKind.RELEASE -> Icons.Outlined.Inventory2
    AnnouncementKind.MAINTENANCE -> Icons.Outlined.Build
    AnnouncementKind.SECURITY -> Icons.Outlined.Security
    AnnouncementKind.NEWS -> Icons.Outlined.Campaign
}

@Composable
private fun AnnouncementCard(a: Announcement, time: String, buttons: List<AnnouncementButton>, onTap: (AnnouncementButton) -> Unit,
                             ticked: Boolean?, onToggle: () -> Unit,
                             onCopy: () -> Unit, onForward: () -> Unit, onSelect: () -> Unit, onDelete: () -> Unit,
                             mine: String?, onReact: (String?) -> Unit, onDoubleTap: (() -> Unit)?, current: Boolean) {
    val c = Fariin.colors
    val accent = if (a.kind == AnnouncementKind.SECURITY) c.red else c.blue
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    // Long press: the reaction bar, then Copy, Forward, Select and Delete (for me), as a chat message (iOS menuActions).
    // While selecting (ticked not null) a tap or long press ticks the card and its buttons rest.
    Row(verticalAlignment = Alignment.CenterVertically) {
    if (ticked != null) {
        Icon(if (ticked) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            contentDescription = if (ticked) "Selected" else "Not selected",
            tint = if (ticked) c.blue else c.tertiary,
            modifier = Modifier.padding(end = 10.dp).size(24.dp).clickable(onClick = onToggle))
    }
    Column(Modifier.weight(1f)) {
    Box(Modifier.fillMaxWidth()) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.card)
        // The search's current match is ringed, so the step lands somewhere the eye can find.
        .then(if (current) Modifier.border(2.dp, c.blue, RoundedCornerShape(20.dp)) else Modifier)
        .combinedClickable(onClick = { if (ticked != null) onToggle() },
            onDoubleClick = if (ticked == null && onDoubleTap != null) onDoubleTap else null, onLongClick = {
            if (ticked != null) onToggle()
            else { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); menu = true } })) {
        if (a.mediaUrl != null) {
            AsyncImage(a.mediaUrl, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp))
        }
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(kindIcon(a.kind), contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(a.kind.label, style = MaterialTheme.typography.labelLarge, color = accent, modifier = Modifier.weight(1f))
                Text(time, style = MaterialTheme.typography.labelMedium, color = c.secondary)
            }
            if (a.title.isNotEmpty()) {
                Spacer(Modifier.size(6.dp))
                Text(a.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = c.label)
            }
            if (a.body.isNotEmpty()) {
                Spacer(Modifier.size(4.dp))
                Text(a.body, style = MaterialTheme.typography.bodyLarge, color = c.label)
            }
        }
        for (b in buttons) {
            HorizontalDivider(color = c.separator)
            Text(b.label, style = MaterialTheme.typography.labelLarge, color = c.blue, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clickable { if (ticked != null) onToggle() else onTap(b) }.padding(vertical = 12.dp, horizontal = 14.dp))
        }
    }
    androidx.compose.material3.DropdownMenu(menu, onDismissRequest = { menu = false }, shape = com.fariin.app.ui.components.FariinMenu.shape, border = com.fariin.app.ui.components.FariinMenu.border, containerColor = c.card) {
        // The chat's quick reaction bar (QuickReaction.bar), mine filled; the same one again takes it back.
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            for (e in com.fariin.app.data.MessageRules.QUICK_REACTIONS) {
                Box(Modifier.size(42.dp).clip(CircleShape)
                    .background(if (mine == e) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .clickable { menu = false; onReact(com.fariin.app.data.MessageRules.nextReaction(mine, e)) },
                    contentAlignment = Alignment.Center) { Text(e, fontSize = 24.sp) }
            }
        }
        HorizontalDivider(color = c.separator)
        androidx.compose.material3.DropdownMenuItem(text = { Text("Copy") }, onClick = { menu = false; onCopy() },
            leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) })
        androidx.compose.material3.DropdownMenuItem(text = { Text("Forward") }, onClick = { menu = false; onForward() },
            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Forward, contentDescription = null) })
        androidx.compose.material3.DropdownMenuItem(text = { Text("Select") }, onClick = { menu = false; onSelect() },
            leadingIcon = { Icon(Icons.Outlined.CheckCircle, contentDescription = null) })
        androidx.compose.material3.DropdownMenuItem(text = { Text("Delete", color = c.red) }, onClick = { menu = false; onDelete() },
            leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = c.red) })
    }
    }
    // My reaction as a chip under the card; a tap takes it back.
    if (mine != null) {
        Box(Modifier.padding(start = 12.dp, top = 2.dp).clip(CircleShape).background(c.blue.copy(alpha = 0.18f))
            .clickable { if (ticked != null) onToggle() else onReact(null) }.padding(horizontal = 7.dp, vertical = 3.dp)) {
            Text(mine, fontSize = 15.sp)
        }
    }
    }
    }
}

/** The Fariin mark as its photo (iOS official-mark). */
@Composable
fun OfficialAvatar(size: Dp) {
    // The whole picture is the photo, white bubble on black, cut to a circle (iOS official-mark).
    Image(painterResource(R.drawable.official_mark), contentDescription = null, contentScale = ContentScale.Crop,
        modifier = Modifier.size(size).clip(CircleShape))
}

/**
 * The official chat's chat list row: name with the verified mark, the newest post, its time and unread.
 * A long press offers Mark as Read/Unread, Pin and Archive (iOS markUnread, setPinned, setArchived).
 */
@Composable
fun OfficialRow(view: OfficialChannel.View, onOpen: () -> Unit) {
    val c = Fariin.colors
    val context = LocalContext.current
    val latest = view.latest ?: return
    val unread = view.unread
    val muted = view.state.isMutedNow(System.currentTimeMillis().toDouble())
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    Box {
    Row(Modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = {
            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); menu = true })
        .padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.padding(vertical = 10.dp)) { OfficialAvatar(56.dp) }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f).padding(top = 7.dp, bottom = 9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(OfficialChannel.NAME, style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (unread > 0) FontWeight.Bold else FontWeight.Medium, color = c.label, maxLines = 1)
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Filled.Verified, contentDescription = "Official", tint = c.blue, modifier = Modifier.size(17.dp))
                    if (muted) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Filled.NotificationsOff, contentDescription = "Muted", tint = c.tertiary, modifier = Modifier.size(15.dp))
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(timeLabel(latest.sortAt, DateFormat.is24HourFormat(context)), style = MaterialTheme.typography.labelMedium,
                    color = if (unread > 0) c.blue else c.secondary, maxLines = 1)
            }
            Row(verticalAlignment = Alignment.Top) {
                Text(latest.preview, style = MaterialTheme.typography.bodyMedium, color = c.secondary,
                    maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                if (unread > 0) {
                    Box(Modifier.padding(top = 1.dp).heightIn(min = 20.dp).clip(CircleShape)
                        .background(if (muted) c.secondary else c.blue).padding(horizontal = 6.dp, vertical = 2.dp),
                        contentAlignment = Alignment.Center) {
                        Text(if (unread > 99) "99+" else "$unread", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
                    }
                }
            }
        }
    }
    androidx.compose.material3.DropdownMenu(menu, onDismissRequest = { menu = false }, shape = com.fariin.app.ui.components.FariinMenu.shape, border = com.fariin.app.ui.components.FariinMenu.border, containerColor = Fariin.colors.card,
        offset = androidx.compose.ui.unit.DpOffset(88.dp, 0.dp)) {
        val pinned = view.state.pinned
        val archived = view.state.archived
        androidx.compose.material3.DropdownMenuItem(text = { Text(if (unread > 0) "Mark as Read" else "Mark as Unread") },
            onClick = { menu = false; if (unread > 0) OfficialChannel.markRead() else OfficialChannel.markUnread() })
        androidx.compose.material3.DropdownMenuItem(text = { Text(if (pinned) "Unpin" else "Pin") },
            onClick = { menu = false; OfficialChannel.setPinned(!pinned) })
        androidx.compose.material3.DropdownMenuItem(text = { Text(if (archived) "Unarchive" else "Archive") },
            onClick = { menu = false; OfficialChannel.setArchived(!archived) })
    }
    }
}
