package com.fariin.app.ui.thread

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Mic
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.core.content.ContextCompat
import com.fariin.app.media.MediaRules
import com.fariin.app.media.VoicePlayer
import com.fariin.app.media.VoiceRecorder
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
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
import com.fariin.app.data.MessageRules
import androidx.compose.foundation.gestures.detectTapGestures
import kotlinx.coroutines.launch
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
    val synced: Boolean = true,
    val loadingOlder: Boolean = false,
    /** Shown instead of the composer when sending is not possible here. */
    val sendBlocked: String? = null,
    /** The chat id (voice speed is per chat, as on iOS). */
    val cid: String = "",
    /** Group: I hold the admin "Delete messages" right. */
    val adminMayDelete: Boolean = false,
    /** Group members and when each last read (ms), for Message Info. */
    val members: List<String> = emptyList(),
    val memberReads: Map<String, Double> = emptyMap(),
    val myPhoto: String? = null,
    /** My unread count when the chat opened, which places the "Unread Messages" divider. */
    val unreadAtOpen: Int = 0,
    /** A channel: posts drawn as the channel speaking, views, counts-only reactions, follower bar. */
    val channel: com.fariin.app.ui.channels.ChannelThreadUi? = null,
)

/**
 * The chat page, after Kulan-iOS ThreadView + MessageList: my bubbles on the right in the chat
 * colour (system blue by default) with white text, theirs on the left in the received grey;
 * fused runs of the same sender (18pt outer corners, 6pt inside), the time and ticks inside the
 * bubble's last line, day separators as pills in the received colour, and the composer.
 *
 * TODO (iOS has these): reply by swipe, long-press menu, reactions, the jump-to-bottom arrow
 * with its unread count, the unread divider, typing and presence in the header, wallpapers, chat
 * colours, albums beyond a 2 x 2 grid, files, video send, the floating "Today" pill.
 *
 * Media: photo, video and voice bubbles (MediaBubbles.kt), the full-screen viewer, the attach
 * button (the system photo picker, no storage permission) and hold-to-record on the mic.
 */
@Composable
fun ThreadScreen(
    ui: ThreadUi,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onLoadOlder: () -> Unit = {},
    onRetry: (Message) -> Unit = {},
    onSendPhoto: (Uri) -> Unit = {},
    onSendVoice: (VoiceRecorder.Result) -> Unit = {},
    actions: ThreadActions = ThreadActions(),
    /** The attach sheet's sends (media worker): photos/videos + caption + view-once, and a file. */
    onSendPick: (List<Uri>, String, Boolean) -> Unit = { l, _, _ -> l.firstOrNull()?.let(onSendPhoto) },
    onSendFile: (Uri) -> Unit = {},
) {
    val c = Fariin.colors
    // Long-press menu, delete question, info, reply and edit targets (MessageActions.kt).
    var menuFor by remember { mutableStateOf<Message?>(null) }
    var postMenuFor by remember { mutableStateOf<Message?>(null) }
    var deleteFor by remember { mutableStateOf<Message?>(null) }
    var infoFor by remember { mutableStateOf<Message?>(null) }
    var replyTo by remember { mutableStateOf<Message?>(null) }
    var editing by remember { mutableStateOf<Message?>(null) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var viewing by remember { mutableStateOf<Viewing?>(null) }
    viewing?.let { v -> MediaViewer(v) { viewing = null } }
    // Leaving the chat stops its note (iOS stops playback when the thread goes away).
    DisposableEffect(ui.cid) { onDispose { VoicePlayer.stop() } }
    val context = LocalContext.current
    val is24h = remember { DateFormat.is24HourFormat(context) }
    val now = remember(ui.messages) { System.currentTimeMillis().toDouble() }
    val geometry = remember(ui.messages) { ThreadLogic.geometry(ui.messages) }
    // Newest at the bottom: the list is reversed, so index 0 is the newest message.
    val reversed = remember(ui.messages) { ui.messages.indices.reversed().toList() }
    val listState = rememberLazyListState()
    // Forward picker (media worker: ForwardSheet.kt).
    var forwardFor by remember { mutableStateOf<Message?>(null) }
    forwardFor?.let { m -> ForwardSheet(ui.me, onDismiss = { forwardFor = null }) { targets -> actions.onForwardTo(m, targets) } }
    menuFor?.let { m ->
        MessageMenu(m, ui, onDismiss = { menuFor = null }, actions = actions.copy(onForward = { forwardFor = it }),
            onReply = { replyTo = it; editing = null }, onStartEdit = { editing = it; replyTo = null },
            onCopy = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(it)); actions.onNotice("Copied") },
            onInfo = { infoFor = it }, onDelete = { deleteFor = it })
    }
    val ch = ui.channel
    postMenuFor?.let { m ->
        if (ch != null) com.fariin.app.ui.channels.ChannelPostMenu(ch, m, onDismiss = { postMenuFor = null },
            onCopy = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(it)); actions.onNotice("Copied") },
            onDelete = { deleteFor = it })
    }
    deleteFor?.let { m -> DeleteDialog(m, ui, actions) { deleteFor = null } }
    infoFor?.let { m -> MessageInfoDialog(m, ui, is24h) { infoFor = null } }
    // The divider is placed once, from the unread count at open (iOS anchors it the same way).
    // Counted only on the server's first answer: the phone's saved copy can lack the very messages
    // that are unread, and counting back through it put the divider on old, read rows.
    val dividerId = remember(ui.loaded && ui.synced) {
        if (ui.loaded && ui.synced) MessageRules.unreadDividerId(ui.messages, ui.me, ui.unreadAtOpen) else null
    }
    // At the bottom = the newest row in view (the list is reversed). Read receipts follow it.
    val atBottom by remember { derivedStateOf { listState.firstVisibleItemIndex <= 1 } }
    LaunchedEffect(atBottom) { actions.onAtBottom(atBottom) }
    var seenCount by remember { mutableStateOf(ui.messages.size) }
    LaunchedEffect(atBottom, ui.messages.size) { if (atBottom) seenCount = ui.messages.size }

    // Near the top of the history: fetch the page before it (iOS loadOlder).
    val nearTop by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 8
        }
    }
    LaunchedEffect(nearTop, ui.messages.size) { if (nearTop && ui.messages.isNotEmpty()) onLoadOlder() }
    // A message I send, or one that lands while I am at the bottom, keeps the newest in view.
    // Whether the reader sits at the bottom, settled only when a scroll ends: a row added at the
    // bottom of a reversed, keyed list keeps the old row in place and shifts the index past 1
    // before this effect runs, so reading the index here let new arrivals push the chat upward
    // (the open showed old rows with the unread ones under the arrow; iOS opens at the newest).
    var stick by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { if (!it) stick = listState.firstVisibleItemIndex <= 1 }
    }
    LaunchedEffect(ui.messages.lastOrNull()?.rowId) {
        if (stick || listState.firstVisibleItemIndex <= 1 || ui.messages.lastOrNull()?.authorId == ui.me) listState.scrollToItem(0)
    }

    Column(Modifier.fillMaxSize().background(c.bg).imePadding()) {
        Header(ui, onBack, actions.onTitleClick)
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
                        if (m.rowId == dividerId) UnreadDivider()
                        when {
                            m.isSystem || m.isCall -> NoticePill(if (ch != null) com.fariin.app.channel.ChannelRules.words(noticeText(m)) else noticeText(m))
                            ch != null -> androidx.compose.runtime.CompositionLocalProvider(
                                com.fariin.app.ui.channels.LocalPostViews provides
                                    if (m.deleted) null else com.fariin.app.channel.ChannelRules.shownViews(m.views, m.authorId, ui.me),
                            ) {
                                Column(Modifier.fillMaxWidth().combinedClickableLong { postMenuFor = m }) {
                                    MessageRow(ui, m, g, is24h, onRetry) { viewing = it }
                                    com.fariin.app.ui.channels.ChannelChips(ch, m)
                                }
                            }
                            else -> SwipeRow(enabled = MessageRules.canReply(m), onReply = { replyTo = m; editing = null }) {
                                Column(Modifier.fillMaxWidth().combinedClickableLong { menuFor = m }) {
                                    MessageRow(ui, m, g, is24h, onRetry) { viewing = it }
                                    ReactionChips(m, ui, m.authorId == ui.me) { e ->
                                        actions.onReact(m, MessageRules.nextReaction(m.reactions[ui.me], e))
                                    }
                                }
                            }
                        }
                    }
                }
                if (ui.loadingOlder) item(key = "older") {
                    Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = c.secondary, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    }
                }
            }
            if (!atBottom) {
                JumpArrow((ui.messages.size - seenCount).coerceAtLeast(0), onClick = {
                    scope.launch { listState.animateScrollToItem(0) }
                }, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 12.dp))
            }
        }
        if (ch != null && ch.role != com.fariin.app.channel.ChannelRules.Role.ADMIN) {
            HorizontalDivider(thickness = 0.5.dp, color = c.separator)
            com.fariin.app.ui.channels.ChannelBottomBar(ch, ui.sendBlocked)
        } else if (ui.sendBlocked != null) {
            HorizontalDivider(thickness = 0.5.dp, color = c.separator)
            Text(ui.sendBlocked, fontSize = 15.sp, color = c.secondary,
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp))
        } else {
            replyTo?.let { r ->
                ComposerBar(if (r.authorId == ui.me) "Reply to yourself" else "Reply to ${ui.names[r.authorId] ?: ui.title}",
                    r.bodyText) { replyTo = null }
            }
            editing?.let { e -> ComposerBar("Edit message", e.text) { editing = null } }
            val send: (String) -> Unit = { t ->
                val r = replyTo
                val e = editing
                when {
                    e != null -> { editing = null; if (t.trim() != e.text.trim()) actions.onEdit(e, t) }
                    r != null -> { replyTo = null; actions.onSendReply(t, r) }
                    else -> onSend(t)
                }
            }
            var attachOpen by remember { mutableStateOf(false) }
            if (attachOpen) AttachSheet(allowViewOnce = !ui.isGroup, onDismiss = { attachOpen = false },
                onSendMedia = onSendPick, onSendFile = onSendFile)
            Composer(send, { attachOpen = true }, onSendVoice, prefill = editing?.let { it.id to it.text }, onTyping = actions.onTyping,
                initialDraft = actions.draft, onDraft = actions.onDraft)
        }
    }
}

private fun noticeText(m: Message): String = when {
    m.isCall -> m.text.ifEmpty { "Call" }
    else -> m.text
}

@Composable
private fun Header(ui: ThreadUi, onBack: () -> Unit, onTitleClick: () -> Unit = {}) {
    val c = Fariin.colors
    Column(Modifier.fillMaxWidth().background(c.bg).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = c.label)
            }
            Column(Modifier.weight(1f).clickable(onClick = onTitleClick), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(ui.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (ui.subtitle.isNotEmpty()) {
                    Text(ui.subtitle, fontSize = 12.sp, color = c.secondary, maxLines = 1)
                }
            }
            if (ui.channel == null) com.fariin.app.ui.call.ThreadCallButtons(ui.cid, ui.me, ui.isGroup, ui.title, ui.photoUrl)
            Avatar(ui.photoUrl, 36.dp, modifier = Modifier.padding(horizontal = 8.dp).clickable(onClick = onTitleClick),
                squircle = ui.channel != null)
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
private fun MessageRow(ui: ThreadUi, m: Message, g: ThreadLogic.RowGeometry, is24h: Boolean, onRetry: (Message) -> Unit,
                       onView: (Viewing) -> Unit) {
    val c = Fariin.colors
    // In a channel nobody's post is "mine" on screen: every post is the channel speaking.
    val isMe = m.authorId == ui.me && ui.channel == null
    val maxW = (LocalConfiguration.current.screenWidthDp * 0.72f).dp
    val big = 18.dp
    val small = 6.dp
    val shape = if (isMe) RoundedCornerShape(topStart = big, topEnd = if (g.first) big else small,
        bottomEnd = if (g.last) big else small, bottomStart = big)
    else RoundedCornerShape(topStart = if (g.first) big else small, topEnd = big,
        bottomEnd = big, bottomStart = if (g.last) big else small)
    val showSender = ui.isGroup && !isMe && ui.channel == null
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
        val kind = m.mediaKind
        if (kind == Message.MediaKind.PHOTO || kind == Message.MediaKind.VIDEO || kind == Message.MediaKind.ALBUM) {
            MediaRow(ui, m, g, is24h, shape, maxW, tick, isMe, showSender, kind, onView)
            ui.channel?.let { com.fariin.app.ui.channels.PostShareButton(it, m) }
            return@Row
        }
        Column(
            Modifier.widthIn(max = maxW).clip(shape).background(if (isMe) myFill() else receivedFill())
                .padding(horizontal = if (kind == Message.MediaKind.VOICE) 13.dp else 14.dp, vertical = 7.dp),
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
            if (kind == Message.MediaKind.VOICE) {
                VoiceBody(m, ui.cid, isMe, maxW * 0.89f - 26.dp) { Meta(m, tick, isMe, is24h) }
                return@Column
            }
            if (kind == Message.MediaKind.FILE) {
                FileBody(m, isMe, minOf(maxW - 28.dp, 260.dp)) { Meta(m, tick, isMe, is24h) }
                return@Column
            }
            if (kind == Message.MediaKind.VIEW_ONCE) {
                ViewOnceBody(m, ui.cid, isMe, onView) { Meta(m, tick, isMe, is24h) }
                return@Column
            }
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
        ui.channel?.let { com.fariin.app.ui.channels.PostShareButton(it, m) }
    }
}

/**
 * A photo, video or album: the picture fills the bubble; the sender's name, "Forwarded" and the
 * reply quote sit above it, the caption under it with the time; with no caption the time floats
 * on the picture in a dark capsule (iOS).
 */
@Composable
private fun MediaRow(
    ui: ThreadUi, m: Message, g: ThreadLogic.RowGeometry, is24h: Boolean, shape: androidx.compose.ui.graphics.Shape,
    maxW: Dp, tick: Tick, isMe: Boolean, showSender: Boolean, kind: Message.MediaKind,
    onView: (Viewing) -> Unit,
) {
    val c = Fariin.colors
    val caption = m.text.takeIf { it.isNotEmpty() }
    val header = (showSender && g.first) || m.forwarded || m.replyTo != null
    val ink = if (isMe) Color.White else c.label
    // A rough caption width, only so a short picture does not squeeze a caption (iOS minW).
    val captionW = caption?.let { it.length.coerceAtMost(40) * 8.5f } ?: 0f
    val busy = m.sendState == Message.SendState.SENDING
    Column(Modifier.widthIn(max = maxW).clip(shape).background(if (isMe) myFill() else receivedFill())) {
        if (header) {
            Column(Modifier.padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 4.dp)) {
                if (showSender && g.first) {
                    Text(ui.names[m.authorId] ?: "Someone", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        color = senderColor(m.authorId), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (m.forwarded) Text("Forwarded", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    color = if (isMe) Color.White.copy(alpha = 0.7f) else c.secondary)
                m.replyTo?.let { ReplyQuote(it, ui, isMe) }
            }
        }
        val capsule: @Composable () -> Unit = {
            if (caption == null) Box(Modifier.fillMaxSize().padding(6.dp), contentAlignment = Alignment.BottomEnd) {
                MetaCapsule { Meta(m, tick, isMe, is24h, onMedia = true) }
            }
        }
        when (kind) {
            Message.MediaKind.PHOTO -> {
                val url = m.localPath ?: m.imageUrl
                PictureBox(m, url, m.enc, maxW, RectangleShape, captionW, busy, onOpen = {
                    url?.let { onView(Viewing.Photo(it, if (m.localPath != null) null else m.enc)) }
                }, overlay = capsule)
            }
            Message.MediaKind.VIDEO -> {
                val ready = m.videoUrl != null
                PictureBox(m, m.thumbUrl, m.thumbEnc, maxW, RectangleShape, captionW, busy || !ready, onOpen = {
                    m.videoUrl?.let { onView(Viewing.Video(it, m.enc)) }
                }, overlay = { VideoOverlay(m, ready); capsule() })
            }
            else -> AlbumMosaic(m, maxW, onView, capsule)
        }
        if (caption != null) {
            Box(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                TextWithMeta(caption, TextStyle(fontSize = 17.sp, color = ink, lineHeight = 22.sp)) { Meta(m, tick, isMe, is24h) }
            }
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
            // A story reply quotes the story itself (iOS status quote): "Story" and its picture.
            Text(if (r.isStatus) "Story" else r.text, fontSize = 12.sp, color = if (isMe) Color.White.copy(alpha = 0.85f) else c.label,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (r.isStatus && !r.storyThumb.isNullOrEmpty()) {
            coil3.compose.AsyncImage(r.storyThumb, contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.padding(end = 4.dp, top = 3.dp, bottom = 3.dp).width(24.dp).height(32.dp).clip(RoundedCornerShape(4.dp)))
        }
    }
}

/** Time, "edited", and for my own bubble the tick: faded for sent and delivered, solid for read. */
@Composable
private fun Meta(m: Message, tick: Tick, isMe: Boolean, is24h: Boolean, onMedia: Boolean = false) {
    val metaColor = if (isMe || onMedia) Color.White.copy(alpha = if (onMedia) 0.95f else 0.7f) else Fariin.colors.secondary
    Row(verticalAlignment = Alignment.CenterVertically) {
        com.fariin.app.ui.channels.PostViews(metaColor)
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

/**
 * The composer, in the iOS order: attach on the left, the field, and on the right the send arrow
 * when there are words, else the mic. Hold the mic to record, slide left to cancel, let go to
 * send (iOS hold-to-record). The microphone permission is asked on the first hold.
 */
@Composable
private fun Composer(
    onSend: (String) -> Unit, onAttach: () -> Unit, onSendVoice: (VoiceRecorder.Result) -> Unit,
    prefill: Pair<String, String>? = null, onTyping: (Boolean) -> Unit = {},
    initialDraft: String = "", onDraft: (String) -> Unit = {},
) {
    val c = Fariin.colors
    val context = LocalContext.current
    var draft by rememberSaveable { mutableStateOf(initialDraft) }
    // The words typed outside an edit: what the box gets back when an edit ends (iOS restores the
    // pre-edit draft, it was never sent).
    var kept by rememberSaveable { mutableStateOf(initialDraft) }
    var wasEditing by remember { mutableStateOf(false) }
    // Edit puts the message's words in the field (keyed by message id, so it happens once).
    LaunchedEffect(prefill?.first) {
        if (prefill != null) { draft = prefill.second; wasEditing = true }
        else if (wasEditing) { draft = kept; wasEditing = false }
    }
    // Every change outside an edit is the chat's draft (sent = cleared below).
    LaunchedEffect(draft) { if (prefill == null && !wasEditing) { kept = draft; onDraft(draft) } }

    // iOS typing: on while there are words (refreshed every 10 s so the 15 s expiry stays armed),
    // off when the field empties, after 5 s without a keystroke, or when the composer goes.
    var lastKey by remember { mutableStateOf(0L) }
    LaunchedEffect(draft.isNotBlank(), lastKey) {
        if (draft.isBlank()) { onTyping(false); return@LaunchedEffect }
        onTyping(true)
        kotlinx.coroutines.delay(5_000)
        onTyping(false)
    }
    DisposableEffect(Unit) { onDispose { onTyping(false) } }
    val recorder = remember { VoiceRecorder(context.applicationContext) }
    DisposableEffect(Unit) { onDispose { recorder.cancel() } }
    var recording by remember { mutableStateOf(false) }
    var slide by remember { mutableStateOf(0f) }
    val elapsed by recorder.elapsed.collectAsState()
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val cancelAt = with(androidx.compose.ui.platform.LocalDensity.current) { 120.dp.toPx() }

    Column(Modifier.fillMaxWidth().background(c.bg).navigationBarsPadding()) {
        HorizontalDivider(thickness = 0.5.dp, color = c.separator)
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 7.dp), verticalAlignment = Alignment.Bottom) {
            if (recording) {
                Row(Modifier.weight(1f).height(36.dp).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(c.red))
                    Spacer(Modifier.width(8.dp))
                    Text(MediaRules.durationLabel(kotlin.math.floor(elapsed)), style = TextStyle(fontSize = 17.sp, color = c.label,
                        fontFeatureSettings = "tnum"))
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null, tint = c.secondary, modifier = Modifier.size(20.dp))
                    Text("Slide to cancel", fontSize = 15.sp, color = c.secondary,
                        modifier = Modifier.graphicsLayer { translationX = slide.coerceAtMost(0f) / 2 })
                    Spacer(Modifier.width(12.dp))
                }
            } else {
                IconButton(onClick = onAttach, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.AttachFile, contentDescription = "Attach", tint = c.secondary, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.width(4.dp))
                Box(
                    Modifier.weight(1f).heightIn(min = 36.dp).clip(RoundedCornerShape(18.dp))
                        .border(0.5.dp, c.separator, RoundedCornerShape(18.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (draft.isEmpty()) Text("Message", fontSize = 17.sp, color = c.secondary)
                    BasicTextField(
                        value = draft, onValueChange = {
                            val now = System.currentTimeMillis()
                            if (now - lastKey > 10_000 || draft.isBlank()) lastKey = now
                            draft = it
                        },
                        textStyle = TextStyle(fontSize = 17.sp, color = c.label),
                        cursorBrush = SolidColor(c.blue),
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            if (draft.isNotBlank() && !recording) {
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
            } else {
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(if (recording) c.blue else Color.Transparent)
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                                    PackageManager.PERMISSION_GRANTED
                                if (!granted) {
                                    askMic.launch(Manifest.permission.RECORD_AUDIO)
                                    return@awaitEachGesture
                                }
                                VoicePlayer.stop()
                                if (!recorder.start()) return@awaitEachGesture
                                recording = true; slide = 0f
                                var cancelled = false
                                while (true) {
                                    val e = awaitPointerEvent()
                                    val ch = e.changes.firstOrNull { it.id == down.id } ?: break
                                    slide = ch.position.x - down.position.x
                                    ch.consume()
                                    if (slide < -cancelAt) { cancelled = true; break }
                                    if (!ch.pressed) break
                                }
                                recording = false; slide = 0f
                                if (cancelled) recorder.cancel() else recorder.finish()?.let(onSendVoice)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Mic, contentDescription = "Hold to record a voice message",
                        tint = if (recording) Color.White else c.secondary, modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}

/** Long press only (a tap still reaches the photo and voice bubbles inside). */
private fun Modifier.combinedClickableLong(onLong: () -> Unit): Modifier =
    this.pointerInput(Unit) { detectTapGestures(onLongPress = { onLong() }) }
