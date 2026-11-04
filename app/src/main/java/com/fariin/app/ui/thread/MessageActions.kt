package com.fariin.app.ui.thread

import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.automirrored.outlined.Forward
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.History
import com.fariin.app.ui.components.floating
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
    /** A text with group @mentions (uids), optionally a reply; null falls back to the plain sends. */
    val onSendMentioned: ((String, Message?, List<String>) -> Unit)? = null,
    val onEdit: (Message, String) -> Unit = { _, _ -> },
    val onDeleteForEveryone: (Message) -> Unit = {},
    val onDeleteForMe: (Message) -> Unit = {},
    val onTyping: (Boolean) -> Unit = {},
    /** The list reached (true) or left (false) the newest message. */
    val onAtBottom: (Boolean) -> Unit = {},
    val onTitleClick: () -> Unit = {},
    /** The header menu's Mute / Unmute. */
    val onToggleMute: () -> Unit = {},
    val onUnblock: () -> Unit = {},
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
    /** Pin / unpin (iOS togglePin, ChatService.unpin). */
    val onPin: (Message) -> Unit = {},
    val onUnpin: (String) -> Unit = {},
    /** Pages older history until the message is loaded (iOS ensureLoaded); false when out of reach. */
    val onEnsureLoaded: suspend (String) -> Boolean = { false },
    /** In-chat search: the newest messages opened on this phone (iOS MessageSearch.loadChat), and
     *  paging far enough to reach any match it can return (iOS ensureLoaded maxPages 18). */
    val onSearchCorpus: suspend () -> List<com.fariin.app.data.ChatSearch.Item> = { emptyList() },
    val onReachMatch: suspend (String) -> Boolean = { false },
    /** Earlier versions of an edited message, oldest first (text, at ms). Throws when unreadable. */
    val onEditHistory: suspend (Message) -> List<Pair<String, Double>> = { emptyList() },
    /** Select mode: the delete plan run at once, and several messages forwarded to the chats picked. */
    val onDeleteMany: (com.fariin.app.data.SelectRules.Plan) -> Unit = {},
    val onForwardManyTo: (List<Message>, List<String>) -> Unit = { _, _ -> },
    /** The sticker panel: a built-in sticker or a GIF, with the reply it answers (or null). */
    val onSendSticker: (com.fariin.app.data.StickerRef, Message?) -> Unit = { _, _ -> },
    val onSendGif: (com.fariin.app.data.StickerGifRules.Gif, Message?) -> Unit = { _, _ -> },
)


/** One row of the menu. */
private data class MenuItem(val title: String, val icon: ImageVector?, val destructive: Boolean = false,
                            val iconRes: Int = 0, val countdownTo: Double? = null, val run: () -> Unit)

/**
 * The long-press menu, after iOS customMenuActions: the quick reaction bar on top (the six
 * QuickReaction.bar emojis, mine filled), then Reply, Forward, Copy, Edit (inside 15 minutes),
 * Info (my own message in a group), and Delete under a divider. A tombstone offers Delete only.
 */
@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
internal fun MessageMenu(
    m: Message, ui: ThreadUi, onDismiss: () -> Unit, actions: ThreadActions,
    onReply: (Message) -> Unit, onStartEdit: (Message) -> Unit, onCopy: (String) -> Unit,
    onInfo: (Message) -> Unit, onDelete: (Message) -> Unit,
    onSelect: (Message) -> Unit = {}, onEditHistory: (Message) -> Unit = {},
    source: MenuSource? = null, preview: (@Composable () -> Unit)? = null, onLifted: () -> Unit = {},
) {
    val c = Fariin.colors
    val now = System.currentTimeMillis().toDouble()
    val items = buildList {
        // Restricted chat (iOS): no Forward, Save or Copy for anyone in it.
        val restricted = com.fariin.app.data.ChatRestrictions.isOn(ui.cid)
        if (!m.deleted) {
            if (MessageRules.canReply(m)) add(MenuItem("Reply", Icons.AutoMirrored.Outlined.Reply) { onReply(m) })
            if (!restricted && MessageRules.canForward(m)) add(MenuItem("Forward", Icons.AutoMirrored.Outlined.Forward) {
                if (!com.fariin.app.data.ChatRestrictions.isOn(ui.cid)) actions.onForward(m) })
            if (!restricted && canSaveToGallery(m)) add(MenuItem("Save", Icons.Outlined.Download) {
                if (!com.fariin.app.data.ChatRestrictions.isOn(ui.cid)) actions.onSave(m) })
            // A contact or location card is no text to copy or edit (iOS keeps markers off text surfaces).
            val card = com.fariin.app.data.CardRules.isCard(m.text)
            if (!restricted && MessageRules.canCopy(m) && !card) add(MenuItem("Copy", Icons.Outlined.ContentCopy) {
                if (!com.fariin.app.data.ChatRestrictions.isOn(ui.cid)) onCopy(m.text) })
            if (MessageRules.canEdit(m, ui.me, now) && !card) add(MenuItem("Edit", Icons.Outlined.Edit) { onStartEdit(m) })
            if (m.edited) add(MenuItem("Edit History", Icons.Outlined.History) { onEditHistory(m) })
        }
        // iOS: a photo, video, album or GIF still going up can be stopped from its menu, not only its ring.
        if (m.sendState == Message.SendState.SENDING && (m.mediaKind != null || m.isGif)) uploadCancelFor(m)?.let { cancel ->
            add(MenuItem("Cancel Sending", Icons.Outlined.Cancel, destructive = true) { cancel() })
        }
        if (com.fariin.app.data.SelectRules.selectable(m)) add(MenuItem("Select", Icons.Outlined.CheckCircle) { onSelect(m) })
        if (!m.deleted) {
            if (MessageRules.canInfo(m, ui.me, ui.isGroup)) add(MenuItem("Info", Icons.Outlined.Info) { onInfo(m) })
            if (ui.canPin && com.fariin.app.data.PinRules.offers(m)) {
                val pinnedNow = m.id in ui.pinnedIds
                if (pinnedNow) add(MenuItem("Unpin", null, iconRes = com.fariin.app.R.drawable.ic_pin_menu) { actions.onUnpin(m.id) })
                else add(MenuItem("Pin", null, iconRes = com.fariin.app.R.drawable.ic_pin_menu) { actions.onPin(m) })
            }
        }
        // On a disappearing message Delete carries a live countdown from the server's stamp only
        // (iOS deleteOrTimerRow); someone who cannot delete gets the countdown on a row of its own.
        val expires = m.expiresAtMillis?.takeIf { !m.deleted }
        if (MessageRules.canDeleteHere(m, ui.me, ui.isGroup, ui.adminMayDelete)) {
            add(MenuItem("Delete", Icons.Outlined.Delete, destructive = true, countdownTo = expires) { onDelete(m) })
        } else if (expires != null) {
            add(MenuItem("Disappearing", null, iconRes = com.fariin.app.R.drawable.ic_disappearing, countdownTo = expires) {})
        }
    }
    // One ticker for the menu, only while it is open and only when a row counts down.
    val counting = items.any { it.countdownTo != null }
    val clock = androidx.compose.runtime.remember { androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis()) }
    if (counting) androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            clock.longValue = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000 - clock.longValue % 1000)
        }
    }
    // The message is on screen: the iOS menu over it, in place (MessageMenuOverlay).
    if (source != null && preview != null) {
        val rows = items.map { it ->
            MenuRow(
                it.title,
                if (it.icon != null) androidx.compose.ui.graphics.vector.rememberVectorPainter(it.icon)
                else androidx.compose.ui.res.painterResource(it.iconRes),
                it.destructive,
                it.countdownTo?.let { until -> com.fariin.app.data.Disappearing.countdown(until - clock.longValue) },
                it.run,
            )
        }
        val mine = m.reactions[ui.me]
        MessageMenuOverlay(source, rows, reactable = !m.deleted && m.sendState == null && !m.isSystem, mine = mine,
            onReact = { e -> actions.onReact(m, MessageRules.nextReaction(mine, e)) },
            onDismiss = onDismiss, preview = preview, onLifted = onLifted)
        return
    }
    // Android: a modal bottom sheet (reaction capsule on top, the message, then the actions in one
    // rounded card, as the iOS menu's grouped list) in place of the iOS popover beside the bubble.
    val sheet = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = sheet,
        containerColor = com.fariin.app.ui.components.sheetFill,
        shape = com.fariin.app.ui.components.SheetShape,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            val reactable = !m.deleted && m.sendState == null && !m.isSystem
            if (reactable) {
                val mine = m.reactions[ui.me]
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                        .floating(RoundedCornerShape(28.dp)).padding(horizontal = 6.dp, vertical = 4.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (e in MessageRules.QUICK_REACTIONS) {
                        Box(
                            Modifier.size(48.dp).clip(CircleShape)
                                .background(if (mine == e) androidx.compose.material3.MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                                .clickable { actions.onReact(m, MessageRules.nextReaction(mine, e)); onDismiss() },
                            contentAlignment = Alignment.Center,
                        ) { Text(e, fontSize = 28.sp) }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            // A one-line preview of the message being acted on.
            Text(
                if (m.deleted) "This message was deleted" else com.fariin.app.data.CardRules.previewWord(m.text) ?: m.bodyText.ifEmpty { "Message" },
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, color = c.secondary, maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 10.dp),
            )
            com.fariin.app.ui.components.SheetCard {
                items.forEachIndexed { i, it ->
                    val tint = if (it.destructive) c.red else c.label
                    val until = it.countdownTo
                    com.fariin.app.ui.components.SheetRow(
                        title = it.title,
                        icon = if (it.icon != null) androidx.compose.ui.graphics.vector.rememberVectorPainter(it.icon)
                            else androidx.compose.ui.res.painterResource(it.iconRes),
                        tint = tint,
                        supporting = if (until == null) null else {
                            // Whole-width digits, so the line does not shiver as the seconds change.
                            { Text(com.fariin.app.data.Disappearing.countdown(until - clock.longValue), color = tint,
                                fontSize = 13.sp, style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"), maxLines = 1) }
                        },
                        last = i == items.lastIndex,
                        onClick = { onDismiss(); it.run() },
                    )
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
internal fun DeleteDialog(m: Message, ui: ThreadUi, actions: ThreadActions,
                          title: String = "Delete this message?", onDismiss: () -> Unit) {
    val c = Fariin.colors
    val everyone = MessageRules.canDeleteForEveryone(m, ui.me, ui.adminMayDelete)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (everyone) TextButton(onClick = { onDismiss(); actions.onDeleteForEveryone(m) }) {
                    Text("Delete for Everyone", color = c.red)
                }
                if (!ui.isGroup) TextButton(onClick = { onDismiss(); actions.onDeleteForMe(m) }) { Text("Delete for Me", color = c.red) }
                else if (m.sendState != null) TextButton(onClick = { onDismiss(); actions.onDeleteForMe(m) }) { Text("Delete", color = c.red) }
                TextButton(onClick = onDismiss) { Text("Cancel", color = c.accent) }
            }
        },
        containerColor = c.card,
    )
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
        val anim = com.fariin.app.ui.components.animationsOn()
        chips.forEach { chip ->
            // Reaction pop: a chip that changes (my tap, someone else's) springs from small back to
            // full size; chips already there when the chat opens stay still.
            val scale = remember(chip.emoji) { androidx.compose.animation.core.Animatable(1f) }
            val seen = remember(chip.emoji) { intArrayOf(-1) }
            LaunchedEffect(chip.emoji, chip.count, chip.mine) {
                val key = chip.count * 2 + if (chip.mine) 1 else 0
                if (seen[0] != -1 && seen[0] != key && anim) {
                    scale.snapTo(0.7f)
                    scale.animateTo(1f, com.fariin.app.ui.components.Motion.pop())
                }
                seen[0] = key
            }
            Row(
                Modifier.padding(end = 4.dp).graphicsLayer { scaleX = scale.value; scaleY = scale.value }.clip(CircleShape)
                    .background(if (chip.mine) c.blue.copy(alpha = 0.18f) else if (c.dark) Color(0xFF26262B) else Color(0xFFF2F2F2))
                    .border(1.dp, c.bg, CircleShape)
                    .clickable { onTap(chip.emoji) }
                    .padding(horizontal = 7.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ReactionGlyph(chip.emoji, 15.sp, 20.dp)
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
