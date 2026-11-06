package com.fariin.app.ui.thread

import com.fariin.app.ui.components.floating
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.R
import com.fariin.app.crypto.Wire
import com.fariin.app.data.Message
import com.fariin.app.data.PinRules
import com.fariin.app.data.SelectRules
import com.fariin.app.ui.theme.Fariin

/**
 * Edit History (iOS EditHistorySheet): the current text with "Current", then the earlier versions
 * newest first with their time; a version this phone cannot open says "Unavailable".
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun EditHistorySheet(m: Message, actions: ThreadActions, onDismiss: () -> Unit) {
    val c = Fariin.colors
    var versions by remember(m.id) { mutableStateOf<List<Pair<String, Double>>?>(null) }
    var failed by remember(m.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(m.id) {
        try { versions = actions.onEditHistory(m) } catch (e: Exception) {
            // A channel's history is for its owner and admins; the server refuses anyone else.
            val refused = (e as? com.google.firebase.functions.FirebaseFunctionsException)?.code ==
                com.google.firebase.functions.FirebaseFunctionsException.Code.PERMISSION_DENIED
            failed = if (refused) "Only the channel's owner and admins can see its edit history."
                else "Couldn't load the edit history. Check your connection and try again."
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(),
        containerColor = com.fariin.app.ui.components.sheetFill, shape = com.fariin.app.ui.components.SheetShape,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp).verticalScroll(rememberScrollState())) {
            Text("Edit History", style = MaterialTheme.typography.titleMedium, color = c.label,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            // The versions in one rounded card on the grey sheet (iOS grouped list).
            com.fariin.app.ui.components.SheetCard {
            HistoryRow(m.text, "Current", last = versions.isNullOrEmpty())
            val v = versions
            when {
                v != null && v.isEmpty() -> Text("No earlier versions", fontSize = 15.sp, color = c.secondary,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp))
                v != null -> v.asReversed().forEachIndexed { i, (text, at) ->
                    val readable = !(text.isEmpty() || text == Wire.NOT_READY || text == Wire.LOCKED)
                    HistoryRow(if (readable) text else "Unavailable", historyTime(at), dim = !readable, last = i == v.lastIndex)
                }
                failed != null -> Text(failed ?: "", fontSize = 15.sp,
                    color = c.secondary, modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp))
                else -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    com.fariin.app.ui.components.FariinSpinner(size = 22.dp)
                }
            }
            }
        }
    }
}

@Composable
private fun HistoryRow(text: String, caption: String, dim: Boolean = false, last: Boolean = false) {
    val c = Fariin.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(text, fontSize = 17.sp, color = if (dim) c.secondary else c.label)
        if (caption.isNotEmpty()) Text(caption, fontSize = 12.sp, color = c.secondary)
    }
    if (!last) HorizontalDivider(Modifier.padding(start = 16.dp), thickness = 0.5.dp, color = c.separator)
}

/** iOS `.formatted(date: .abbreviated, time: .shortened)`; empty when the time is unknown. */
private fun historyTime(ms: Double): String {
    if (ms <= 0) return ""
    return java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
        .format(java.util.Date(ms.toLong()))
}

/**
 * The pinned bar under the header (iOS pinnedBarContent): a short line per pin on the left when
 * there are several (the shown one long and blue), the author and "n/count", the snippet, and the
 * pin button with Unpin for those who may pin. A tap on the text jumps to the message and moves on
 * to the next pin.
 */
@Composable
internal fun PinnedBar(
    ids: List<String>, index: Int, ui: ThreadUi, msgFor: (String) -> Message?,
    onJump: (String) -> Unit, onUnpin: (String) -> Unit, onSeeAll: () -> Unit = {},
) {
    if (ids.isEmpty()) return
    val c = Fariin.colors
    val idx = index.coerceIn(0, ids.size - 1)
    val pid = ids[idx]
    val msg = msgFor(pid)
    // A channel's posts speak as the channel, so its pinned bar names the channel, never "You" or an admin.
    val author = msg?.let { if (ui.channel != null) ui.title else if (it.authorId == ui.me) "You" else ui.names[it.authorId] ?: ui.title } ?: "Pinned Message"
    var menu by remember { mutableStateOf(false) }
    // A floating rounded card under the header, not a flat strip (iOS pinned banner, owner 2026-11-03).
    // A floating capsule over the chat (iOS pinned banner): a picture of the pinned photo at the start,
    // the pin in its own grey circle at the end. No bar behind it; the messages pass underneath.
    Box(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 6.dp)) {
        Row(Modifier.fillMaxWidth().height(60.dp).floating(CircleShape).padding(start = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            val thumb = msg?.let { rememberPlaceholder(it.thumb, it.blurhash) }
            if (thumb != null) {
                androidx.compose.foundation.Image(thumb, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)))
                Spacer(Modifier.width(10.dp))
            } else Spacer(Modifier.width(12.dp))
            if (ids.size > 1) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    ids.indices.forEach { i ->
                        Box(Modifier.width(2.5.dp).height(if (i == idx) 16.dp else 7.dp).clip(CircleShape)
                            .background(if (i == idx) c.blue else c.secondary.copy(alpha = 0.4f)))
                    }
                }
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f).fillMaxHeight().clickable { onJump(pid) }, verticalArrangement = Arrangement.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(author, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.label, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (ids.size > 1) {
                        Spacer(Modifier.width(5.dp))
                        Text("${idx + 1}/${ids.size}", fontSize = 11.sp, color = c.secondary)
                    }
                }
                Text(PinRules.snippet(msg), fontSize = 14.sp, color = c.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(44.dp).clip(CircleShape).background(c.secondary.copy(alpha = 0.12f))) {
                    Icon(painterResource(R.drawable.ic_pin_bar), "Pinned message", tint = c.label, modifier = Modifier.size(22.dp))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, shape = com.fariin.app.ui.components.FariinMenu.shape, border = com.fariin.app.ui.components.FariinMenu.border, containerColor = com.fariin.app.ui.components.FariinMenu.container) {
                    if (ui.canPin) DropdownMenuItem(
                        text = { Text("Unpin") },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_pin_menu), null, Modifier.size(22.dp)) },
                        onClick = { menu = false; onUnpin(pid) },
                    )
                    DropdownMenuItem(
                        text = { Text("See All") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.List, null, Modifier.size(22.dp)) },
                        onClick = { menu = false; onSeeAll() },
                    )
                }
            }
        }
    }
}

/**
 * See All (iOS PinnedMessagesSheet): "Pinned Messages" over the chat's name, every pin oldest
 * first under its day, each drawn as its bubble with an arrow; a tap jumps to it. Those who may
 * pin get Unpin on a long press of a row (Android) and Unpin All at the bottom (iOS).
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
internal fun PinnedMessagesSheet(
    pinned: List<Message>, ui: ThreadUi, onDismiss: () -> Unit,
    onJump: (String) -> Unit, onUnpin: (String) -> Unit,
    bubble: @Composable (Message) -> Unit,
) {
    val c = Fariin.colors
    val now = remember { System.currentTimeMillis().toDouble() }
    var rowMenu by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.bg, shape = com.fariin.app.ui.components.SheetShape,
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f)) {
            Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Pinned Messages", style = MaterialTheme.typography.titleMedium, color = c.label)
                Text(ui.title, fontSize = 14.sp, color = c.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (pinned.isEmpty()) {
                Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(painterResource(R.drawable.ic_pin_bar), null, tint = c.secondary, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("No pinned messages", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label)
                    Text("Pin a message from its menu.", fontSize = 14.sp, color = c.secondary)
                }
            } else {
                androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    pinned.forEachIndexed { i, m ->
                        val day = com.fariin.app.data.ThreadLogic.dayLabel(m.createdAtMillis, now)
                        if (i == 0 || day != com.fariin.app.data.ThreadLogic.dayLabel(pinned[i - 1].createdAtMillis, now)) {
                            item(key = "day-${m.id}") {
                                Text(day, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = c.secondary,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            }
                        }
                        item(key = m.id) {
                            Box {
                                Row(
                                    Modifier.fillMaxWidth()
                                        .combinedClickable(
                                            onClick = { onJump(m.id) },
                                            onLongClick = if (ui.canPin) ({ rowMenu = m.id }) else null,
                                        )
                                        .padding(vertical = 5.dp, horizontal = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    // The bubble takes no taps of its own here (a photo would open).
                                    Box(Modifier.weight(1f)) {
                                        bubble(m)
                                        Box(Modifier.matchParentSize().combinedClickable(
                                            onClick = { onJump(m.id) },
                                            onLongClick = if (ui.canPin) ({ rowMenu = m.id }) else null,
                                        ))
                                    }
                                    IconButton(onClick = { onJump(m.id) }) {
                                        Icon(Icons.AutoMirrored.Outlined.ArrowForward, "Go to Message", tint = c.secondary)
                                    }
                                }
                                DropdownMenu(expanded = rowMenu == m.id, onDismissRequest = { rowMenu = null }, shape = com.fariin.app.ui.components.FariinMenu.shape, border = com.fariin.app.ui.components.FariinMenu.border, containerColor = com.fariin.app.ui.components.FariinMenu.container) {
                                    DropdownMenuItem(
                                        text = { Text("Unpin") },
                                        leadingIcon = { Icon(painterResource(R.drawable.ic_pin_menu), null, Modifier.size(22.dp)) },
                                        onClick = { rowMenu = null; onUnpin(m.id) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (ui.canPin && pinned.isNotEmpty()) {
                Box(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 10.dp), contentAlignment = Alignment.Center) {
                    // A floating capsule, as the chat's other controls.
                    Box(Modifier.height(48.dp).floating(androidx.compose.foundation.shape.CircleShape).clickable {
                        pinned.forEach { onUnpin(it.id) }
                        onDismiss()
                    }.padding(horizontal = 28.dp), contentAlignment = Alignment.Center) {
                        Text("Unpin All", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.accent)
                    }
                }
            }
        }
    }
}

/** Select mode's contextual top bar (Material 3): close, then "N Selected". */
@Composable
internal fun SelectTopBar(count: Int, onClose: () -> Unit) {
    val c = Fariin.colors
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)
            .windowInsetsPadding(WindowInsets.statusBars).height(64.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Cancel", tint = c.label) }
        Spacer(Modifier.width(8.dp))
        Text("$count Selected", style = MaterialTheme.typography.titleLarge, color = c.label)
    }
}

/** The bar that stands in for the composer while selecting: Delete, Forward, Copy. */
@Composable
internal fun SelectionBar(
    deleteEnabled: Boolean, forwardEnabled: Boolean, copyEnabled: Boolean, busy: Boolean,
    onDelete: () -> Unit, onForward: () -> Unit, onCopy: () -> Unit,
) {
    val c = Fariin.colors
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)
            .windowInsetsPadding(WindowInsets.navigationBars).height(64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SelectAction("Delete", Icons.Filled.Delete, deleteEnabled && !busy, c.red, onDelete)
        SelectAction("Forward", Icons.AutoMirrored.Filled.Forward, forwardEnabled && !busy, c.label, onForward)
        SelectAction("Copy", Icons.Filled.ContentCopy, copyEnabled && !busy, c.label, onCopy)
    }
}

@Composable
private fun RowScope.SelectAction(title: String, icon: ImageVector, enabled: Boolean, tint: Color, onClick: () -> Unit) {
    val ink = if (enabled) tint else Fariin.colors.secondary.copy(alpha = 0.5f)
    Column(
        Modifier.weight(1f).fillMaxHeight().clickable(enabled = enabled, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = ink, modifier = Modifier.size(24.dp))
        Text(title, fontSize = 12.sp, color = ink)
    }
}

/**
 * The select-mode delete question (iOS bulk delete alert): "Delete N messages?" with Delete for
 * Everyone when any pick may go for everyone, Delete for Me in a 1:1, and for a mixed pick a line
 * saying what happens to the rest.
 */
@Composable
internal fun BulkDeleteDialog(picked: List<Message>, ui: ThreadUi, onDismiss: () -> Unit, onPlan: (SelectRules.Plan) -> Unit) {
    val c = Fariin.colors
    val split = SelectRules.split(picked, ui.me, ui.adminMayDelete)
    val hasMine = split.everyone > 0
    fun go(everyone: Boolean) {
        onDismiss()
        onPlan(SelectRules.plan(picked, everyone, ui.me, ui.isGroup, ui.adminMayDelete))
    }
    val n = picked.size
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete $n message${if (n == 1) "" else "s"}?") },
        text = if (hasMine && split.onlyMe > 0) {
            {
                Text(
                    if (ui.isGroup) "Delete for Everyone removes ${split.everyone} of these for everyone. You can't delete the other ${split.onlyMe}."
                    else "Delete for Everyone removes ${split.everyone} of these for everyone. The other ${split.onlyMe} will only be removed from your side.",
                )
            }
        } else null,
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (hasMine) TextButton(onClick = { go(true) }) { Text("Delete for Everyone", color = c.red) }
                if (!ui.isGroup) TextButton(onClick = { go(false) }) { Text("Delete for Me", color = c.red) }
                TextButton(onClick = onDismiss) { Text("Cancel", color = c.accent) }
            }
        },
        containerColor = c.card,
    )
}
