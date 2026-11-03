package com.fariin.app.ui.official

import android.text.format.DateFormat
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val listState = rememberLazyListState()

    // Open = read, and anything that lands while it is open is read too.
    LaunchedEffect(view.visible) { OfficialChannel.markRead() }
    LaunchedEffect(view.visible.size) { if (view.visible.isNotEmpty()) listState.scrollToItem(view.visible.size - 1) }

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
    pendingDelete?.let { id ->
        AlertDialog(onDismissRequest = { pendingDelete = null }, title = { Text("Delete this message?") },
            confirmButton = { TextButton({ OfficialChannel.hide(listOf(id)); pendingDelete = null }) { Text("Delete for Me", color = c.red) } },
            dismissButton = { TextButton({ pendingDelete = null }) { Text("Cancel", color = c.blue) } }, containerColor = c.card)
    }
    rejectResult?.let { text ->
        AlertDialog(onDismissRequest = { rejectResult = null }, text = { Text(text) },
            confirmButton = { TextButton({ rejectResult = null }) { Text("OK", color = c.blue) } }, containerColor = c.card)
    }

    Column(Modifier.fillMaxSize().background(c.bg)) {
        Box(Modifier.fillMaxWidth().background(c.bg).statusBarsPadding()) {
            FariinTopBar(OfficialChannel.NAME, onBack = onBack, subtitle = OfficialChannel.SUBTITLE)
        }
        HorizontalDivider(color = c.separator)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(view.visible, key = { it.id }) { a ->
                val now = System.currentTimeMillis().toDouble()
                AnnouncementCard(a, timeLabel(a.sortAt, is24h), a.buttons.filter { it.isUsable(now) }, ::tap,
                    onCopy = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(listOf(a.title, a.body).filter { it.isNotEmpty() }.joinToString("\n"))) },
                    onDelete = { pendingDelete = a.id })
            }
        }
        HorizontalDivider(color = c.separator)
        Text(OfficialChannel.CANNOT_REPLY, style = MaterialTheme.typography.bodyMedium, color = c.secondary,
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
                             onCopy: () -> Unit, onDelete: () -> Unit) {
    val c = Fariin.colors
    val accent = if (a.kind == AnnouncementKind.SECURITY) c.red else c.blue
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    // Long press: Copy and Delete (for me), as a chat message (iOS menuActions; Forward and Select later).
    Box {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.card)
        .combinedClickable(onClick = {}, onLongClick = {
            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); menu = true })) {
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
                modifier = Modifier.fillMaxWidth().clickable { onTap(b) }.padding(vertical = 12.dp, horizontal = 14.dp))
        }
    }
    androidx.compose.material3.DropdownMenu(menu, onDismissRequest = { menu = false }, containerColor = c.card) {
        androidx.compose.material3.DropdownMenuItem(text = { Text("Copy") }, onClick = { menu = false; onCopy() },
            leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) })
        androidx.compose.material3.DropdownMenuItem(text = { Text("Delete", color = c.red) }, onClick = { menu = false; onDelete() },
            leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = c.red) })
    }
    }
}

/** The Fariin mark in a blue circle (iOS official-mark). */
@Composable
fun OfficialAvatar(size: Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(Fariin.colors.blue), contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.splash_mark), contentDescription = null, modifier = Modifier.size(size * 0.62f))
    }
}

/** The official chat's chat list row: name with the verified mark, the newest post, its time and unread. */
@Composable
fun OfficialRow(view: OfficialChannel.View, onOpen: () -> Unit) {
    val c = Fariin.colors
    val context = LocalContext.current
    val latest = view.latest ?: return
    val unread = view.unread
    val muted = view.state.muted
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.padding(vertical = 10.dp)) { OfficialAvatar(56.dp) }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f).padding(top = 7.dp, bottom = 9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(OfficialChannel.NAME, style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (unread > 0) FontWeight.Bold else FontWeight.Medium, color = c.label, maxLines = 1)
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Filled.Verified, contentDescription = "Official", tint = c.blue, modifier = Modifier.size(17.dp))
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
}
