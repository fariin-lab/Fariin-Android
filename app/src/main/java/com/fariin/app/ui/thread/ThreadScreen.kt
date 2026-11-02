package com.fariin.app.ui.thread

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.data.Message
import com.fariin.app.data.Receipts
import com.fariin.app.data.ThreadLogic
import com.fariin.app.data.Tick
import com.fariin.app.ui.components.Avatar
import com.fariin.app.ui.theme.Fariin

/** Everything the chat screen draws; the same for a live chat and the debug demo. */
data class ThreadUi(
    val title: String,
    val subtitle: String = "",
    val photoUrl: String? = null,
    val isGroup: Boolean = false,
    val me: String,
    /** Oldest first. */
    val messages: List<Message> = emptyList(),
    val receipts: Receipts = Receipts(),
    val names: Map<String, String> = emptyMap(),
    val photos: Map<String, String> = emptyMap(),
    val loaded: Boolean = true,
    val loadingOlder: Boolean = false,
    /** Shown instead of the composer when sending is not possible here. */
    val sendBlocked: String? = null,
)

/**
 * The chat page, after Kulan-iOS ThreadView + MessageList: my bubbles on the right in the chat
 * colour (system blue by default) with white text, theirs on the left in the received grey;
 * fused runs of the same sender (18pt outer corners, 6pt inside), the time and ticks inside the
 * bubble's last line, day separators as pills in the received colour, and the composer.
 *
 * TODO (iOS has these): reply by swipe, long-press menu, reactions, the jump-to-bottom arrow
 * with its unread count, the unread divider, typing and presence in the header, wallpapers, chat
 * colours, media bubbles, voice recording, attachments, the floating "Today" pill.
 */
@Composable
fun ThreadScreen(
    ui: ThreadUi,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onLoadOlder: () -> Unit = {},
    onRetry: (Message) -> Unit = {},
) {
    val c = Fariin.colors
    val context = LocalContext.current
    val is24h = remember { DateFormat.is24HourFormat(context) }
    val now = remember(ui.messages) { System.currentTimeMillis().toDouble() }
    val geometry = remember(ui.messages) { ThreadLogic.geometry(ui.messages) }
    // Newest at the bottom: the list is reversed, so index 0 is the newest message.
    val reversed = remember(ui.messages) { ui.messages.indices.reversed().toList() }
    val listState = rememberLazyListState()

    // Near the top of the history: fetch the page before it (iOS loadOlder).
    val nearTop by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 8
        }
    }
    LaunchedEffect(nearTop, ui.messages.size) { if (nearTop && ui.messages.isNotEmpty()) onLoadOlder() }
    // A message I send, or one that lands while I am at the bottom, keeps the newest in view.
    LaunchedEffect(ui.messages.lastOrNull()?.rowId) {
        if (listState.firstVisibleItemIndex <= 1) listState.scrollToItem(0)
    }

    Column(Modifier.fillMaxSize().background(c.bg).imePadding()) {
        Header(ui, onBack)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (!ui.loaded) {
                CircularProgressIndicator(color = c.secondary, strokeWidth = 2.dp,
                    modifier = Modifier.size(24.dp).align(Alignment.Center))
            } else if (ui.messages.isEmpty()) {
                Text("No messages here yet", fontSize = 15.sp, color = c.secondary, modifier = Modifier.align(Alignment.Center))
            }
            LazyColumn(
                Modifier.fillMaxSize(), state = listState, reverseLayout = true,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 8.dp, bottom = 8.dp),
            ) {
                itemsIndexed(reversed, key = { _, i -> ui.messages[i].rowId }) { _, i ->
                    val m = ui.messages[i]
                    val g = geometry[i]
                    Column(Modifier.fillMaxWidth().padding(top = g.gapDp.dp)) {
                        if (g.showDate) DatePill(ThreadLogic.dayLabel(m.createdAtMillis, now))
                        when {
                            m.isSystem || m.isCall -> NoticePill(noticeText(m))
                            else -> MessageRow(ui, m, g, is24h, onRetry)
                        }
                    }
                }
                if (ui.loadingOlder) item(key = "older") {
                    Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = c.secondary, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        if (ui.sendBlocked != null) {
            HorizontalDivider(thickness = 0.5.dp, color = c.separator)
            Text(ui.sendBlocked, fontSize = 15.sp, color = c.secondary,
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp))
        } else {
            Composer(onSend)
        }
    }
}

private fun noticeText(m: Message): String = when {
    m.isCall -> m.text.ifEmpty { "Call" }
    else -> m.text
}

@Composable
private fun Header(ui: ThreadUi, onBack: () -> Unit) {
    val c = Fariin.colors
    Column(Modifier.fillMaxWidth().background(c.bg).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = c.label)
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(ui.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (ui.subtitle.isNotEmpty()) {
                    Text(ui.subtitle, fontSize = 12.sp, color = c.secondary, maxLines = 1)
                }
            }
            Avatar(ui.photoUrl, 36.dp, modifier = Modifier.padding(horizontal = 8.dp))
        }
        HorizontalDivider(thickness = 0.5.dp, color = c.separator)
    }
}

/** iOS ChatNoticePill: caption semibold, the received bubble's colour, a capsule. */
@Composable
private fun DatePill(text: String) = NoticePill(text, vertical = 8.dp)

@Composable
private fun NoticePill(text: String, vertical: Dp = 4.dp) {
    val c = Fariin.colors
    Box(Modifier.fillMaxWidth().padding(vertical = vertical), contentAlignment = Alignment.Center) {
        Text(
            text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = c.label,
            modifier = Modifier.clip(CircleShape).background(receivedFill()).padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
}

/** Theme.received: F2F2F2 light, 26262B dark. */
@Composable
private fun receivedFill(): Color = if (Fariin.colors.dark) Color(0xFF26262B) else Color(0xFFF2F2F2)

/** Theme.defaultBubble: system blue (the default chat colour). */
@Composable
private fun myFill(): Color = Fariin.colors.blue

private val senderPalette = listOf(
    Color(0xFF007AFF), Color(0xFFAF52DE), Color(0xFFFF2D55), Color(0xFFFF9500),
    Color(0xFF34C759), Color(0xFF30B0C7), Color(0xFF5856D6), Color(0xFFFF3B30),
)

/** BubblePalette.senderColor: the sum of the uid's characters into a fixed palette. */
private fun senderColor(uid: String): Color = senderPalette[uid.sumOf { it.code } % senderPalette.size]

@Composable
private fun MessageRow(ui: ThreadUi, m: Message, g: ThreadLogic.RowGeometry, is24h: Boolean, onRetry: (Message) -> Unit) {
    val c = Fariin.colors
    val isMe = m.authorId == ui.me
    val maxW = (LocalConfiguration.current.screenWidthDp * 0.72f).dp
    val big = 18.dp
    val small = 6.dp
    val shape = if (isMe) RoundedCornerShape(topStart = big, topEnd = if (g.first) big else small,
        bottomEnd = if (g.last) big else small, bottomStart = big)
    else RoundedCornerShape(topStart = if (g.first) big else small, topEnd = big,
        bottomEnd = big, bottomStart = if (g.last) big else small)
    val showSender = ui.isGroup && !isMe
    val tick = ThreadLogic.tick(m, ui.me, ui.receipts)
    val failed = tick == Tick.FAILED

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (showSender) {
            if (g.last) Avatar(ui.photos[m.authorId], 34.dp) else Spacer(Modifier.width(34.dp))
            Spacer(Modifier.width(6.dp))
        }
        if (failed) {
            Box(
                Modifier.padding(end = 8.dp).size(24.dp).clip(CircleShape).background(c.red).clickable { onRetry(m) },
                contentAlignment = Alignment.Center,
            ) { Text("!", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
        }
        Column(
            Modifier.widthIn(max = maxW).clip(shape).background(if (isMe) myFill() else receivedFill())
                .padding(horizontal = 14.dp, vertical = 7.dp),
        ) {
            if (showSender && g.first) {
                Text(ui.names[m.authorId] ?: "Someone", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    color = senderColor(m.authorId), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(1.dp))
            }
            if (m.forwarded && !m.deleted) {
                Text("Forwarded", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    color = if (isMe) Color.White.copy(alpha = 0.7f) else c.secondary)
            }
            m.replyTo?.takeIf { !m.deleted }?.let { ReplyQuote(it, ui, isMe) }
            val ink = if (isMe) Color.White else c.label
            val body = when {
                m.deleted -> if (isMe && m.deletedBy == null) "You deleted this message" else "This message was deleted"
                else -> m.bodyText
            }
            TextWithMeta(
                text = body,
                style = TextStyle(fontSize = 17.sp, color = if (m.deleted) ink.copy(alpha = 0.7f) else ink, lineHeight = 22.sp),
                meta = { Meta(m, tick, isMe, is24h) },
            )
        }
    }
}

@Composable
private fun ReplyQuote(r: Message.ReplyRef, ui: ThreadUi, isMe: Boolean) {
    val c = Fariin.colors
    val bar = if (isMe) Color.White else senderColor(r.authorId)
    val name = if (r.authorId == ui.me) "You" else ui.names[r.authorId] ?: ui.title
    Row(
        Modifier.padding(top = 3.dp, bottom = 5.dp).clip(RoundedCornerShape(6.dp))
            .background(if (isMe) Color.White.copy(alpha = 0.18f) else bar.copy(alpha = 0.12f)),
    ) {
        Box(Modifier.width(3.dp).height(38.dp).background(bar))
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (isMe) Color.White else bar, maxLines = 1)
            Text(r.text, fontSize = 12.sp, color = if (isMe) Color.White.copy(alpha = 0.85f) else c.label,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Time, "edited", and for my own bubble the tick: faded for sent and delivered, solid for read. */
@Composable
private fun Meta(m: Message, tick: Tick, isMe: Boolean, is24h: Boolean) {
    val metaColor = if (isMe) Color.White.copy(alpha = 0.7f) else Fariin.colors.secondary
    Row(verticalAlignment = Alignment.CenterVertically) {
        val time = ThreadLogic.timeLabel(m.clientTs?.takeIf { isMe && !m.hasServerTime } ?: m.createdAtMillis, is24h)
        Text((if (m.edited && !m.deleted) "edited " else "") + time, fontSize = 11.sp, color = metaColor, maxLines = 1)
        if (isMe && !m.deleted) {
            val icon = when (tick) {
                Tick.SENDING -> Icons.Filled.Schedule
                Tick.SENT -> Icons.Filled.Done
                Tick.DELIVERED, Tick.READ -> Icons.Filled.DoneAll
                else -> null
            }
            if (icon != null) {
                Spacer(Modifier.width(3.dp))
                Icon(icon, contentDescription = tickDescription(tick),
                    tint = Color.White.copy(alpha = if (tick == Tick.READ) 1f else 0.55f), modifier = Modifier.size(14.dp))
            }
        }
    }
}

private fun tickDescription(t: Tick) = when (t) {
    Tick.SENDING -> "Sending"
    Tick.SENT -> "Sent"
    Tick.DELIVERED -> "Delivered"
    Tick.READ -> "Read"
    else -> null
}

/**
 * The text with the footer tucked into its last line when it fits, else on a line of its own
 * (iOS MessageRowLayout: `metaInlineGap` 5, `metaGap` under a full last line).
 */
@Composable
private fun TextWithMeta(text: String, style: TextStyle, meta: @Composable () -> Unit) {
    val layoutRef = remember { arrayOfNulls<TextLayoutResult>(1) }
    Layout(content = {
        Text(text, style = style, onTextLayout = { layoutRef[0] = it })
        meta()
    }) { measurables, constraints ->
        val gap = 5.dp.roundToPx()
        val t = measurables[0].measure(constraints.copy(minWidth = 0))
        val mt = measurables[1].measure(constraints.copy(minWidth = 0))
        val tl = layoutRef[0]
        val lastLine = if (tl == null || text.isEmpty()) -1 else tl.lineCount - 1
        val lastW = if (lastLine >= 0) (tl!!.getLineRight(lastLine) - tl.getLineLeft(lastLine)).toInt() else 0
        val rtlish = lastLine >= 0 && tl!!.getParagraphDirection(tl.getLineStart(lastLine)) == androidx.compose.ui.text.style.ResolvedTextDirection.Rtl
        val inline = text.isEmpty() || (!rtlish && lastW + gap + mt.width <= constraints.maxWidth)
        val w: Int
        val h: Int
        if (text.isEmpty()) {
            w = mt.width; h = mt.height
        } else if (inline) {
            w = maxOf(t.width, lastW + gap + mt.width); h = maxOf(t.height, mt.height)
        } else {
            w = maxOf(t.width, mt.width); h = t.height + mt.height
        }
        layout(w.coerceIn(constraints.minWidth, constraints.maxWidth), h) {
            if (text.isNotEmpty()) t.place(0, 0)
            // Inline, the footer sits on the last line's baseline, as on iOS.
            val metaBase = mt[FirstBaseline].takeIf { it != AlignmentLine.Unspecified } ?: mt.height
            val metaY = when {
                text.isEmpty() -> 0
                inline && tl != null -> (tl.getLineBaseline(lastLine).toInt() - metaBase).coerceIn(0, maxOf(0, h - mt.height))
                inline -> h - mt.height
                else -> t.height
            }
            mt.place(w - mt.width, metaY)
        }
    }
}

@Composable
private fun Composer(onSend: (String) -> Unit) {
    val c = Fariin.colors
    var draft by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().background(c.bg).navigationBarsPadding()) {
        HorizontalDivider(thickness = 0.5.dp, color = c.separator)
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.Bottom) {
            Box(
                Modifier.weight(1f).heightIn(min = 36.dp).clip(RoundedCornerShape(18.dp))
                    .border(0.5.dp, c.separator, RoundedCornerShape(18.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (draft.isEmpty()) Text("Message", fontSize = 17.sp, color = c.secondary)
                BasicTextField(
                    value = draft, onValueChange = { draft = it },
                    textStyle = TextStyle(fontSize = 17.sp, color = c.label),
                    cursorBrush = SolidColor(c.blue),
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (draft.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(c.blue).clickable {
                        val t = draft
                        draft = ""
                        onSend(t)
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.ArrowUpward, contentDescription = "Send", tint = Color.White, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}
