package com.fariin.app.ui.thread

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fariin.app.data.Message
import com.fariin.app.data.MessageRules
import com.fariin.app.data.ThreadLogic
import com.fariin.app.ui.components.Avatar
import com.fariin.app.ui.theme.Fariin
import kotlin.math.roundToInt

/** What the long-press menu, swipe, typing and read-at-bottom call back into (live or demo). */
data class ThreadActions(
    val onReact: (Message, String?) -> Unit = { _, _ -> },
    val onSendReply: (String, Message) -> Unit = { _, _ -> },
    val onEdit: (Message, String) -> Unit = { _, _ -> },
    val onDeleteForEveryone: (Message) -> Unit = {},
    val onDeleteForMe: (Message) -> Unit = {},
    val onTyping: (Boolean) -> Unit = {},
    /** The list reached (true) or left (false) the newest message. */
    val onAtBottom: (Boolean) -> Unit = {},
    val onTitleClick: () -> Unit = {},
    /** Shown in a small notice ("Copied", "Saved to Photos"). */
    val onNotice: (String) -> Unit = {},
    /** Opens the forward picker for a message (ThreadScreen sets it), then sends to the chats picked. */
    val onForward: (Message) -> Unit = {},
    val onForwardTo: (Message, List<String>) -> Unit = { _, _ -> },
    /** Save a photo or video to the phone's gallery. */
    val onSave: (Message) -> Unit = {},
    /** The words left in the box last time (iOS Drafts), and where new ones are kept. */
    val draft: String = "",
    val onDraft: (String) -> Unit = {},
)


/** One row of the menu. */
private data class MenuItem(val title: String, val icon: ImageVector, val destructive: Boolean = false, val run: () -> Unit)

/**
 * The long-press menu, after iOS customMenuActions: the quick reaction bar on top (the six
 * QuickReaction.bar emojis, mine filled), then Reply, Forward, Copy, Edit (inside 15 minutes),
 * Info (my own message in a group), and Delete under a divider. A tombstone offers Delete only.
 */
@Composable
internal fun MessageMenu(
    m: Message, ui: ThreadUi, onDismiss: () -> Unit, actions: ThreadActions,
    onReply: (Message) -> Unit, onStartEdit: (Message) -> Unit, onCopy: (String) -> Unit,
    onInfo: (Message) -> Unit, onDelete: (Message) -> Unit,
) {
    val c = Fariin.colors
    val now = System.currentTimeMillis().toDouble()
    val items = buildList {
        if (!m.deleted) {
            if (MessageRules.canReply(m)) add(MenuItem("Reply", Icons.AutoMirrored.Filled.Reply) { onReply(m) })
            if (MessageRules.canForward(m)) add(MenuItem("Forward", Icons.AutoMirrored.Filled.Forward) { actions.onForward(m) })
            if (canSaveToGallery(m)) add(MenuItem("Save", Icons.Filled.Download) { actions.onSave(m) })
            if (MessageRules.canCopy(m)) add(MenuItem("Copy", Icons.Filled.ContentCopy) { onCopy(m.text) })
            if (MessageRules.canEdit(m, ui.me, now)) add(MenuItem("Edit", Icons.Filled.Edit) { onStartEdit(m) })
            if (MessageRules.canInfo(m, ui.me, ui.isGroup)) add(MenuItem("Info", Icons.Filled.Info) { onInfo(m) })
        }
        if (MessageRules.canDeleteHere(m, ui.me, ui.isGroup, ui.adminMayDelete)) {
            add(MenuItem("Delete", Icons.Filled.Delete, destructive = true) { onDelete(m) })
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().clickable(interactionSource = null, indication = null, onClick = onDismiss),
            contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = if (m.authorId == ui.me) Alignment.End else Alignment.Start,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                val reactable = !m.deleted && m.sendState == null && !m.isSystem
                if (reactable) {
                    val mine = m.reactions[ui.me]
                    Row(
                        Modifier.shadow(8.dp, CircleShape).clip(CircleShape).background(c.card).padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        for (e in MessageRules.QUICK_REACTIONS) {
                            Box(
                                Modifier.size(44.dp).clip(CircleShape)
                                    .background(if (mine == e) c.secondary.copy(alpha = 0.22f) else Color.Transparent)
                                    .clickable { actions.onReact(m, MessageRules.nextReaction(mine, e)); onDismiss() },
                                contentAlignment = Alignment.Center,
                            ) { Text(e, fontSize = 26.sp) }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
                // A short preview of the message being acted on.
                Text(
                    if (m.deleted) "This message was deleted" else m.bodyText.ifEmpty { "Message" },
                    fontSize = 15.sp, color = if (m.authorId == ui.me) Color.White else c.label, maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(18.dp))
                        .background(if (m.authorId == ui.me) c.blue else if (c.dark) Color(0xFF26262B) else Color(0xFFF2F2F2))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
                Spacer(Modifier.height(10.dp))
                Column(Modifier.width(250.dp).shadow(8.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).background(c.card)) {
                    items.forEachIndexed { i, it ->
                        if (i > 0) HorizontalDivider(thickness = if (it.destructive) 6.dp else 0.5.dp,
                            color = if (it.destructive) c.bgSecondary else c.separator)
                        Row(
                            Modifier.fillMaxWidth().clickable { onDismiss(); it.run() }.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(it.title, fontSize = 17.sp, color = if (it.destructive) c.red else c.label, modifier = Modifier.weight(1f))
                            Icon(it.icon, null, tint = if (it.destructive) c.red else c.label, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}

/**
 * The delete question, as iOS: "Delete this message?" with Delete for Everyone (when allowed),
 * Delete for Me in a 1:1, plain Delete for an unsent one of mine in a group.
 */
@Composable
internal fun DeleteDialog(m: Message, ui: ThreadUi, actions: ThreadActions, onDismiss: () -> Unit) {
    val c = Fariin.colors
    val everyone = MessageRules.canDeleteForEveryone(m, ui.me, ui.adminMayDelete)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete this message?") },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (everyone) TextButton(onClick = { onDismiss(); actions.onDeleteForEveryone(m) }) {
                    Text("Delete for Everyone", color = c.red)
                }
                if (!ui.isGroup) TextButton(onClick = { onDismiss(); actions.onDeleteForMe(m) }) { Text("Delete for Me", color = c.red) }
                else if (m.sendState != null) TextButton(onClick = { onDismiss(); actions.onDeleteForMe(m) }) { Text("Delete", color = c.red) }
                TextButton(onClick = onDismiss) { Text("Cancel", color = c.blue) }
            }
        },
        containerColor = c.card,
    )
}

/** Message Info (iOS MessageInfoView, groups): who read it and who has not yet. */
@Composable
internal fun MessageInfoDialog(m: Message, ui: ThreadUi, is24h: Boolean, onDismiss: () -> Unit) {
    val c = Fariin.colors
    val others = ui.members.filter { it != ui.me }
    val read = others.filter { (ui.memberReads[it] ?: 0.0) >= m.createdAtMillis }
    val waiting = others - read.toSet()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Message Info") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Read by", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.secondary)
                if (read.isEmpty()) Text("Nobody yet", fontSize = 15.sp, color = c.secondary)
                read.forEach { u ->
                    InfoRow(ui.photos[u], ui.names[u] ?: "Member",
                        ThreadLogic.timeLabel(ui.memberReads[u] ?: 0.0, is24h))
                }
                Spacer(Modifier.height(12.dp))
                Text("Not read yet", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.secondary)
                waiting.forEach { u -> InfoRow(ui.photos[u], ui.names[u] ?: "Member", "") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done", color = c.blue) } },
        containerColor = c.card,
    )
}

@Composable
private fun InfoRow(photo: String?, name: String, trailing: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(photo, 32.dp)
        Spacer(Modifier.width(10.dp))
        Text(name, fontSize = 16.sp, color = Fariin.colors.label, modifier = Modifier.weight(1f), maxLines = 1)
        Text(trailing, fontSize = 13.sp, color = Fariin.colors.secondary)
    }
}

/** Reactions under the bubble: a pill per emoji, up to three faces, else the count; mine tinted. */
@Composable
internal fun ReactionChips(m: Message, ui: ThreadUi, isMe: Boolean, onTap: (String) -> Unit) {
    val chips = remember(m.reactions, ui.me) { MessageRules.reactionChips(m.reactions, ui.me) }
    if (chips.isEmpty()) return
    val c = Fariin.colors
    Row(
        Modifier.fillMaxWidth().padding(start = if (ui.isGroup && !isMe) 52.dp else 12.dp, end = 12.dp, top = 2.dp),
        horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start,
    ) {
        chips.forEach { chip ->
            Row(
                Modifier.padding(end = 4.dp).clip(CircleShape)
                    .background(if (chip.mine) c.blue.copy(alpha = 0.18f) else if (c.dark) Color(0xFF26262B) else Color(0xFFF2F2F2))
                    .border(1.dp, c.bg, CircleShape)
                    .clickable { onTap(chip.emoji) }
                    .padding(horizontal = 7.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(chip.emoji, fontSize = 15.sp)
                if (chip.faces.isEmpty()) {
                    Spacer(Modifier.width(3.dp))
                    Text("${chip.count}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.label)
                } else if (chip.count > 1 || ui.isGroup) {
                    Spacer(Modifier.width(3.dp))
                    Box {
                        chip.faces.forEachIndexed { i, uid ->
                            Avatar(if (uid == ui.me) ui.myPhoto else ui.photos[uid], 18.dp,
                                modifier = Modifier.offset(x = (i * 11).dp).border(1.dp, c.bg, CircleShape))
                        }
                        Spacer(Modifier.width((18 + (chip.faces.size - 1) * 11).dp))
                    }
                }
            }
        }
    }
}

/** Swipe a bubble right to reply (iOS: past 60 pt, the reply arrow shows and the quote starts). */
internal fun Modifier.swipeToReply(enabled: Boolean, onReply: () -> Unit, offset: (Float) -> Unit): Modifier =
    if (!enabled) this else pointerInput(Unit) {
        var dx = 0f
        val trigger = 60.dp.toPx()
        detectHorizontalDragGestures(
            onDragEnd = { if (dx > trigger) onReply(); dx = 0f; offset(0f) },
            onDragCancel = { dx = 0f; offset(0f) },
        ) { change, amount ->
            dx = (dx + amount).coerceIn(0f, trigger * 1.4f)
            if (dx > 0f) change.consume()
            offset(dx)
        }
    }

/** A swipe-able row: the content follows the finger, the reply arrow fades in behind it. */
@Composable
internal fun SwipeRow(enabled: Boolean, onReply: () -> Unit, content: @Composable () -> Unit) {
    var dx by remember { mutableFloatStateOf(0f) }
    val trigger = with(LocalDensity.current) { 60.dp.toPx() }
    Box(Modifier.fillMaxWidth().swipeToReply(enabled, onReply) { dx = it }) {
        if (dx > 4f) Icon(Icons.AutoMirrored.Filled.Reply, null, tint = Fariin.colors.secondary,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp).size(22.dp)
                .graphicsLayer { alpha = (dx / trigger).coerceIn(0f, 1f) })
        Box(Modifier.offset { IntOffset(dx.roundToInt(), 0) }) { content() }
    }
}

/** The bar over the composer while replying or editing (iOS ReplyBar / edit bar). */
@Composable
internal fun ComposerBar(title: String, text: String, onClose: () -> Unit) {
    val c = Fariin.colors
    Row(Modifier.fillMaxWidth().background(c.bg).padding(start = 14.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(3.dp).height(34.dp).background(c.blue))
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.blue, maxLines = 1)
            Text(text, fontSize = 14.sp, color = c.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Cancel", tint = c.secondary) }
    }
}

/** The jump-to-bottom arrow with the count of new messages below the reader (iOS jump button). */
@Composable
internal fun JumpArrow(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Fariin.colors
    Box(modifier) {
        Box(
            Modifier.size(40.dp).shadow(4.dp, CircleShape).clip(CircleShape).background(c.card).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.KeyboardArrowDown, "Jump to the newest message", tint = c.secondary, modifier = Modifier.size(28.dp)) }
        if (count > 0) Text(
            if (count > 99) "99+" else "$count", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
            modifier = Modifier.align(Alignment.TopCenter).offset(y = (-8).dp).clip(CircleShape).background(c.blue)
                .padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

/** "Unread Messages", the iOS divider above the first message I have not read. */
@Composable
internal fun UnreadDivider() {
    val c = Fariin.colors
    Text(
        "Unread Messages", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.secondary,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).background(c.secondary.copy(alpha = 0.10f))
            .padding(vertical = 5.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

/** iOS: a photo, a video or an album can be saved; a view-once photo never. */
internal fun canSaveToGallery(m: Message): Boolean = !m.deleted && !m.viewOnce && m.sendState == null && when (m.mediaKind) {
    Message.MediaKind.PHOTO -> m.imageUrl != null
    Message.MediaKind.VIDEO -> m.videoUrl != null
    Message.MediaKind.ALBUM -> true
    else -> false
}
