package com.fariin.app.ui.thread

import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.zIndex
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.filled.Add
import com.fariin.app.ui.components.floating
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
import androidx.activity.compose.BackHandler
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.gestures.scrollBy
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
    /** Group members' tags (iOS roles), shown next to the sender's name. */
    val roles: Map<String, com.fariin.app.data.GroupRole> = emptyMap(),
    val photos: Map<String, String> = emptyMap(),
    val loaded: Boolean = true,
    val synced: Boolean = true,
    val loadingOlder: Boolean = false,
    /** False once the whole history is loaded (iOS repo.canLoadOlder): the group card sits above it then. */
    val canLoadOlder: Boolean = true,
    /** Shown instead of the composer when sending is not possible here. */
    val sendBlocked: String? = null,
    /** A group I am no longer in (iOS notAMember): a plain line instead of the composer, no calls. */
    val notAMember: Boolean = false,
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
    /** Pinned message ids in pin order (conversations/{cid}.pinnedMessageIds), and the pinned
     *  messages fetched by id for pins older than the loaded page. */
    val pinnedIds: List<String> = emptyList(),
    val pinned: Map<String, Message> = emptyMap(),
    /** May I pin and unpin here (a 1:1 always; a group the Pin messages right). */
    val canPin: Boolean = false,
    /** The chat's disappearing timer in seconds (`disappearSeconds`, 0 = off). */
    val disappearSeconds: Int = 0,
    /** A group's own document, for what its members may send (GroupAdmins: memberSend, slow mode). */
    val groupDoc: Map<String, Any?> = emptyMap(),
)

/** The timer in force and since when, for the bubble's timer mark (iOS MessageRowContext.timer). */
internal data class TimerMark(val seconds: Int = 0, val sinceMs: Double? = null)
internal val LocalTimerMark = androidx.compose.runtime.staticCompositionLocalOf { TimerMark() }

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
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val text = remember {
        bubbleText(ctx.getSharedPreferences(com.fariin.app.ui.settings.CHAT_PREFS, android.content.Context.MODE_PRIVATE)
            .getString("chat.textSize", "medium") ?: "medium")
    }
    // iOS openChatWallpaper: "Change Wallpaper" on the info page comes back here and opens this chat's picker.
    var wallpaperOpen by remember { mutableStateOf(false) }
    LaunchedEffect(ui.cid) {
        com.fariin.app.ui.settings.ChatWallpaperLink.requests.collect { want ->
            if (want == ui.cid) { com.fariin.app.ui.settings.ChatWallpaperLink.requests.value = null; wallpaperOpen = true }
        }
    }
    // A group's wallpaper is everyone's: its picker opens only for someone who may change group info.
    val groupShared = ui.isGroup && ui.channel == null
    if (wallpaperOpen && groupShared && ui.groupDoc.isNotEmpty() && !com.fariin.app.data.GroupRules.canEditInfo(ui.me, ui.groupDoc)) wallpaperOpen = false
    Box(Modifier.fillMaxSize()) {
        androidx.compose.runtime.CompositionLocalProvider(LocalBubbleText provides text) {
            androidx.compose.runtime.CompositionLocalProvider(LocalThreadCid provides ui.cid) {
                ThreadContent(ui, onBack, onSend, onLoadOlder, onRetry, onSendPhoto, onSendVoice, actions, onSendPick, onSendFile)
            }
        }
        if (wallpaperOpen) com.fariin.app.ui.settings.ChatWallpaperPicker(ui.cid, shared = groupShared) { wallpaperOpen = false }
    }
}

@Composable
private fun ThreadContent(
    ui: ThreadUi,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onLoadOlder: () -> Unit,
    onRetry: (Message) -> Unit,
    onSendPhoto: (Uri) -> Unit,
    onSendVoice: (VoiceRecorder.Result) -> Unit,
    actions: ThreadActions,
    onSendPick: (List<Uri>, String, Boolean) -> Unit,
    onSendFile: (Uri) -> Unit,
) {
    val c = Fariin.colors
    // Long-press menu, delete question, info, reply and edit targets (MessageActions.kt).
    var menuFor by remember { mutableStateOf<Message?>(null) }
    // Where each row sits, read only at the long press, and the pressed row's own copy for the
    // lifted message (MessageMenuOverlay); the real row hides while its copy is up.
    val rowCoords = remember { HashMap<String, androidx.compose.ui.layout.LayoutCoordinates>() }
    var menuSource by remember { mutableStateOf<MenuSource?>(null) }
    var menuPreview by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
    var menuLifted by remember { mutableStateOf<String?>(null) }   // the row whose copy is up
    val quickReaction = rememberQuickReaction()
    var postMenuFor by remember { mutableStateOf<Message?>(null) }
    var deleteFor by remember { mutableStateOf<Message?>(null) }
    var infoFor by remember { mutableStateOf<Message?>(null) }
    var replyTo by remember { mutableStateOf<Message?>(null) }
    var editing by remember { mutableStateOf<Message?>(null) }
    var historyFor by remember { mutableStateOf<Message?>(null) }
    // Select mode (iOS uiMode .selection): the ticked rows by rowId; empty = not selecting.
    val selected = remember { androidx.compose.runtime.mutableStateListOf<String>() }
    val selecting = selected.isNotEmpty()
    var bulkDelete by remember { mutableStateOf(false) }
    var bulkForward by remember { mutableStateOf<List<Message>?>(null) }
    BackHandler(enabled = selecting) { selected.clear() }
    fun toggle(m: Message) { if (m.rowId in selected) selected.remove(m.rowId) else selected.add(m.rowId) }
    // The row a jump landed on, lit for a moment (iOS flashAndScroll).
    var flashId by remember { mutableStateOf<String?>(null) }
    var pinIndex by remember { mutableStateOf(0) }
    // Read only inside OpenWhen below: opening the sheet must not redraw the whole thread (a
    // cheap phone took 90-150 ms frames for it).
    val showPins = remember { mutableStateOf(false) }
    val uiNow = androidx.compose.runtime.rememberUpdatedState(ui)
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var viewing by remember { mutableStateOf<Viewing?>(null) }
    viewing?.let { v -> MediaViewer(v, viewerHost(ui, actions, onSendPick)) { viewing = null } }
    // Leaving the chat stops its note (iOS stops playback when the thread goes away).
    DisposableEffect(ui.cid) { onDispose { VoicePlayer.stop() } }
    val context = LocalContext.current
    val is24h = remember { DateFormat.is24HourFormat(context) }
    val now = remember(ui.messages) { System.currentTimeMillis().toDouble() }
    val geometry = remember(ui.messages) { ThreadLogic.geometry(ui.messages) }
    // Newest at the bottom: the list is reversed, so index 0 is the newest message.
    val reversed = remember(ui.messages) { ui.messages.indices.reversed().toList() }
    val listState = rememberLazyListState()
    val peopleNav = com.fariin.app.ui.people.LocalPeopleNav.current
    // Forward picker (media worker: ForwardSheet.kt).
    var forwardFor by remember { mutableStateOf<Message?>(null) }
    forwardFor?.let { m -> ForwardSheet(ui.me, onDismiss = { forwardFor = null }) { targets -> actions.onForwardTo(m, targets) } }
    bulkForward?.let { msgs ->
        ForwardSheet(ui.me, onDismiss = { bulkForward = null }) { targets -> actions.onForwardManyTo(msgs, targets); selected.clear() }
    }
    menuFor?.let { m0 ->
        // The live copy: the long press keeps the row's first lambda, so a stamp, edit or
        // reaction that landed since (an expiresAt, say) would be missing from a held copy.
        val m = ui.messages.firstOrNull { it.rowId == m0.rowId } ?: m0
        MessageMenu(m, ui, onDismiss = { menuFor = null; menuLifted = null }, actions = actions.copy(onForward = { forwardFor = it }),
            onReply = { replyTo = it; editing = null }, onStartEdit = { editing = it; replyTo = null },
            onCopy = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(it)); if (android.os.Build.VERSION.SDK_INT < 33) actions.onNotice("Copied") },
            onInfo = { infoFor = it }, onDelete = { deleteFor = it },
            onSelect = { selected.clear(); selected.add(it.rowId); replyTo = null },
            onEditHistory = { historyFor = it },
            source = menuSource, preview = menuPreview, onLifted = { menuLifted = m.rowId })
    }
    historyFor?.let { m -> EditHistorySheet(ui.messages.firstOrNull { it.id == m.id } ?: m, actions) { historyFor = null } }
    // What the ticks point at, from the live list (iOS liveSelection): a row that went away drops out.
    val picked = ui.messages.filter { it.rowId in selected }
    LaunchedEffect(ui.messages) { selected.retainAll(ui.messages.map { it.rowId }.toSet()) }
    if (bulkDelete) BulkDeleteDialog(picked, ui, onDismiss = { bulkDelete = false }) { plan ->
        actions.onDeleteMany(plan); selected.clear()
    }
    val ch = ui.channel
    postMenuFor?.let { m ->
        if (ch != null) com.fariin.app.ui.channels.ChannelPostMenu(ch, m, onDismiss = { postMenuFor = null },
            onCopy = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(it)); if (android.os.Build.VERSION.SDK_INT < 33) actions.onNotice("Copied") },
            onDelete = { deleteFor = it })
    }
    deleteFor?.let { m -> DeleteDialog(m, ui, actions) { deleteFor = null } }
    infoFor?.let { m -> MessageInfoSheet(m, ui, is24h) { infoFor = null } }
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
    // iOS initialScrollId (owner-approved 2026-07-13): with unread messages the FIRST open lands
    // at the first unread, the divider row at the top of the screen, the unread run below it. Once
    // per open; few unread simply stays at the newest, where the clamp leaves it.
    var landedOnUnread by remember { mutableStateOf(false) }
    LaunchedEffect(dividerId) {
        val id = dividerId ?: return@LaunchedEffect
        if (landedOnUnread) return@LaunchedEffect
        landedOnUnread = true
        val i = ui.messages.indexOfFirst { it.rowId == id }
        if (i < 0) return@LaunchedEffect
        val idx = ui.messages.size - 1 - i   // the list is reversed
        withFrameNanos { }
        listState.scrollToItem(idx)          // the divider row at the bottom edge
        val info = listState.layoutInfo
        val row = info.visibleItemsInfo.firstOrNull { it.index == idx } ?: return@LaunchedEffect
        val usable = info.viewportSize.height - info.beforeContentPadding - info.afterContentPadding
        listState.scrollBy(-(usable - row.size).coerceAtLeast(0).toFloat())   // lift it to the top
        stick = listState.firstVisibleItemIndex <= 1
    }

    // iOS ChatScrollStore: where this chat was being read, for this run of the app only. Unread
    // messages win (above); otherwise a chat left scrolled up opens where it was left, and one left
    // at the newest message opens at the newest. A relaunch starts every chat at the newest.
    var restoredPlace by remember { mutableStateOf(false) }
    LaunchedEffect(ui.messages.size, dividerId) {
        if (restoredPlace || dividerId != null || ui.messages.isEmpty()) return@LaunchedEffect
        val (rowId, offset) = ChatReadingPositions.get(ui.cid) ?: run { restoredPlace = true; return@LaunchedEffect }
        val i = ui.messages.indexOfFirst { it.rowId == rowId }
        if (i < 0) return@LaunchedEffect
        restoredPlace = true
        withFrameNanos { }
        listState.scrollToItem(ui.messages.size - 1 - i, offset)
        stick = false
    }
    DisposableEffect(ui.cid) {
        onDispose {
            val first = listState.layoutInfo.visibleItemsInfo.firstOrNull()
            val key = first?.key as? String
            if (listState.firstVisibleItemIndex <= 1 || key == null) ChatReadingPositions.clear(ui.cid)
            else ChatReadingPositions.save(ui.cid, key, listState.firstVisibleItemScrollOffset)
        }
    }

    val anim = com.fariin.app.ui.components.animationsOn()
    val rowFade = remember(anim) { if (anim) androidx.compose.animation.core.tween<Float>(220, delayMillis = 40) else null }
    val rowPlace = remember(anim) { com.fariin.app.ui.components.Motion.itemOffset }
    // A jump to a pinned message (iOS pinned bar tap): page back until it is loaded, scroll it into
    // view and light it; then the bar moves on to the next pin.
    fun jumpTo(id: String, advance: Boolean) {
        scope.launch {
            val found = actions.onEnsureLoaded(id)
            val list = uiNow.value.messages
            val i = list.indexOfFirst { it.id == id }
            if (!found || i < 0) {
                val gone = list.none { it.id == id } && uiNow.value.pinned[id] == null
                actions.onNotice(if (gone) "Pinned message was deleted" else "Couldn't reach this message.")
                return@launch
            }
            listState.animateScrollToItem(list.size - 1 - i)
            val row = list[i].rowId
            flashId = row
            if (advance) pinIndex = com.fariin.app.data.PinRules.nextIndex(pinIndex, visiblePins(uiNow.value).size)
            kotlinx.coroutines.delay(2000)
            if (flashId == row) flashId = null
        }
    }
    // In-chat search (InChatSearch.kt, iOS uiMode .search): opened from the info pages' Search circle.
    val search = remember(ui.cid) { InChatSearchState() }
    val searchFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    fun goToMatch() {
        val id = search.current?.id ?: return
        // A newer step supersedes this one, so fast taps land one scroll on the last target (iOS).
        val seq = ++search.jumpSeq
        scope.launch {
            val found = actions.onReachMatch(id)
            if (seq != search.jumpSeq || !search.active) return@launch
            val list = uiNow.value.messages
            val i = list.indexOfFirst { it.id == id }
            if (!found || i < 0) { actions.onNotice("That message isn't available"); return@launch }
            listState.animateScrollToItem(list.size - 1 - i)
            val row = list[i].rowId
            flashId = row
            kotlinx.coroutines.delay(2000)
            if (flashId == row) flashId = null
        }
    }
    fun closeSearch() { keyboard?.hide(); search.close(); flashId = null }
    BackHandler(enabled = search.active) { closeSearch() }
    LaunchedEffect(ui.cid) {
        InChatSearchLink.requests.collect { want ->
            if (want != ui.cid) return@collect
            InChatSearchLink.requests.value = null
            selected.clear(); replyTo = null; editing = null
            search.open()
            // The corpus is read while the field takes focus; anything typed meanwhile runs again on it.
            val corpus = actions.onSearchCorpus()
            if (search.active) { search.corpus = corpus; if (search.update(uiNow.value.messages, force = true, navigate = true)) goToMatch() }
        }
    }
    LaunchedEffect(search.query) { if (search.active && search.update(uiNow.value.messages, force = false, navigate = true)) goToMatch() }
    // The chat itself changed (a message came, was edited or deleted): results follow, the list stays put.
    LaunchedEffect(ui.messages) { if (search.active) search.update(ui.messages, force = true, navigate = false) }
    val pins = visiblePins(ui)
    // The header and the pinned capsule float over the top of the chat (iOS): the messages run under
    // them and fade out behind a soft wash, and the list is padded by their height so nothing hides.
    var topBarPx by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var bottomBarPx by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val walled = com.fariin.app.ui.components.hasChatWallpaper(ui.cid)
    Column(Modifier.fillMaxSize().then(com.fariin.app.ui.components.chatWallpaperOf(ui.cid)).imePadding()) {
        OpenWhen(showPins) {
            // Pins found in the loaded page or fetched by id (iOS pinnedPreviews), oldest first.
            val all = com.fariin.app.data.PinRules.sheetOrder(pins) { id -> ui.messages.firstOrNull { it.id == id } ?: ui.pinned[id] }
            PinnedMessagesSheet(all, ui, onDismiss = { showPins.value = false },
                onJump = { id -> showPins.value = false; jumpTo(id, advance = false) },
                onUnpin = { id -> actions.onUnpin(id); pinIndex = 0 },
                bubble = { m -> MessageRow(ui, m, ThreadLogic.RowGeometry(false, true, true, 0), is24h, {}) {} })
        }
        // The sticker sheet's pack grid sends into this chat (StickerInfoSheet).
        androidx.compose.runtime.DisposableEffect(actions) {
            StickerSendHook.send = { st -> actions.onSendSticker(st, null) }
            onDispose { StickerSendHook.send = null }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().zIndex(1f).align(Alignment.TopCenter)
                // Over a wallpaper the page-colour fade read as a pale band: it steps aside then.
                .then(if (walled) com.fariin.app.ui.components.chatWallpaperStripOf(ui.cid) /* the same wallpaper, top-anchored like the chat's: the header reads as part of it and bubbles pass behind */ else Modifier.drawBehind {
                    // The page colour all the way down the header, then a 24 dp fade below it, so a
                    // bubble passing under never shows behind the name or the call buttons (it did
                    // when the fade ran out inside the header).
                    val tail = 24.dp.toPx()
                    val h = size.height + tail
                    drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(
                        0f to c.bg, (size.height / h) to c.bg.copy(alpha = 0.94f), 1f to c.bg.copy(alpha = 0f), endY = h),
                        size = androidx.compose.ui.geometry.Size(size.width, h))
                })
                .onSizeChanged { topBarPx = it.height }) {
                if (search.active) SearchTopBar(search, searchFocus, onClose = { closeSearch() }, onSubmit = {
                    // The keyboard's Search key drops the keyboard and steps on (iOS onSubmit).
                    keyboard?.hide()
                    if (search.matches.isNotEmpty()) { search.index = minOf(search.index + 1, search.matches.size - 1); goToMatch() }
                })
                else if (selecting) SelectTopBar(picked.size) { selected.clear() } else Header(ui, onBack, actions.onTitleClick)
                // Search owns the top: the pinned bar hides while searching (iOS).
                // A group's live call takes the pinned bar's place while it runs (iOS CallBarOrPin).
                if (!search.active) com.fariin.app.ui.call.ThreadLiveCallBar(ui.cid, ui.isGroup && ui.channel == null && !ui.notAMember, ui.title) {
                    PinnedBar(pins, pinIndex, ui, msgFor = { id -> ui.messages.firstOrNull { it.id == id } ?: ui.pinned[id] },
                        onJump = { jumpTo(it, advance = true) },
                        onUnpin = { id -> actions.onUnpin(id); if (pinIndex > 0) pinIndex-- },
                        onSeeAll = { showPins.value = true })
                }
            }
            if (!ui.loaded) {
                // iOS ThreadSkeleton: bubble shapes while the first page comes.
                com.fariin.app.ui.components.ThreadSkeleton()
            } else if (ui.messages.isEmpty() && ui.isGroup && ui.channel == null && !ui.notAMember && ui.groupDoc.isNotEmpty()) {
                // iOS encryptionNotice: an empty group shows its welcome card (GroupWelcomeCard.kt).
                GroupWelcomeCard(ui, onStep = { peopleNav.groupStep(ui.cid, it) }, modifier = Modifier.align(Alignment.Center))
            } else if (ui.messages.isEmpty()) {
                Text("No messages here yet", fontSize = 15.sp, color = c.secondary, modifier = Modifier.align(Alignment.Center))
            }
            val timerMark = TimerMark(ui.disappearSeconds,
                remember(ui.messages, ui.disappearSeconds) { com.fariin.app.data.Disappearing.timerSince(ui.messages) })
            val linkTap = rememberBubbleLinkTap(ui, actions.onNotice)
            androidx.compose.runtime.CompositionLocalProvider(LocalTimerMark provides timerMark, LocalSearchTerm provides search.term,
                LocalBubbleLinkTap provides linkTap) {
            LazyColumn(
                Modifier.fillMaxSize(), state = listState, reverseLayout = true,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(top = with(density) { topBarPx.toDp() } + 8.dp, bottom = with(density) { bottomBarPx.toDp() } + 8.dp),
            ) {
                itemsIndexed(reversed, key = { _, i -> ui.messages[i].rowId }) { _, i ->
                    val m = ui.messages[i]
                    val g = geometry[i]
                    // A message that arrives fades in and the rows above glide up to make room;
                    // with Power Saving > Animations off it just appears.
                    Column(Modifier.animateItem(fadeInSpec = rowFade, placementSpec = rowPlace, fadeOutSpec = null)
                        .fillMaxWidth().padding(top = g.gapDp.dp)) {
                        if (g.showDate) DatePill(ThreadLogic.dayLabel(m.createdAtMillis, now))
                        if (m.rowId == dividerId) UnreadDivider()
                        val pinNote = com.fariin.app.data.PinRules.notice(m.text)
                        val lit by androidx.compose.animation.animateColorAsState(
                            if (m.rowId == flashId || (selecting && m.rowId in selected)) c.blue.copy(alpha = 0.14f) else Color.Transparent,
                            label = "flash")
                        when {
                            pinNote != null && !m.deleted -> Box(Modifier.fillMaxWidth().clickable(enabled = !selecting) { jumpTo(pinNote.messageId, advance = false) }) {
                                NoticePill("${if (m.authorId == ui.me) "You" else ui.names[m.authorId] ?: ui.title} pinned ${pinNote.label}")
                            }
                            m.isSystem && m.disappearSeconds != null -> NoticePill(
                                com.fariin.app.data.Disappearing.noticeLine(
                                    if (m.authorId == ui.me) "You" else ui.names[m.authorId] ?: ui.title, m.disappearSeconds),
                                icon = true)
                            // A call record: the iOS call bubble (CallBubble.kt); tap asks, then calls back.
                            m.isCall && ch == null -> {
                                val theirs = ui.isGroup && m.callerUid != ui.me
                                CallBubble(m, ui.me, is24h, myFill(), receivedFill(),
                                    peerUid = if (ui.isGroup) null else com.fariin.app.crypto.Wire.otherUid(ui.cid, ui.me),
                                    title = ui.title, photoUrl = ui.photoUrl,
                                    callerName = if (theirs) ui.names[m.callerUid ?: m.authorId] ?: "Someone" else null,
                                    callerPhoto = if (theirs) ui.photos[m.callerUid ?: m.authorId] else null)
                            }
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
                            selecting -> Row(
                                Modifier.fillMaxWidth().background(lit),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                SelectTick(m.rowId in selected)
                                // The bubble takes no taps of its own while ticking (a photo would open).
                                Box(Modifier.weight(1f)) {
                                    Column(Modifier.fillMaxWidth()) {
                                        MessageRow(ui, m, g, is24h, {}) {}
                                        ReactionChips(m, ui, m.authorId == ui.me) {}
                                    }
                                    Box(Modifier.matchParentSize().pointerInput(m.rowId) {
                                        awaitEachGesture {
                                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).consume()
                                            var up = false
                                            while (!up) {
                                                val e = awaitPointerEvent(PointerEventPass.Initial)
                                                e.changes.forEach { it.consume() }
                                                up = e.changes.none { it.pressed }
                                            }
                                            toggle(m)
                                        }
                                    })
                                }
                            }
                            else -> SwipeRow(enabled = MessageRules.canReply(m), onReply = { replyTo = m; editing = null }) {
                                Column(Modifier.fillMaxWidth()
                                    .onGloballyPositioned { rowCoords[m.rowId] = it }
                                    .graphicsLayer { alpha = if (menuLifted == m.rowId) 0f else 1f }
                                    .background(lit).combinedClickableLong {
                                        val lc = rowCoords[m.rowId]?.takeIf { it.isAttached }
                                        menuSource = lc?.let { MenuSource(androidx.compose.ui.geometry.Rect(it.positionOnScreen(), it.size.toSize()), m.authorId == ui.me) }
                                        menuPreview = {
                                            Column(Modifier.fillMaxWidth()) {
                                                MessageRow(ui, m, g, is24h, {}) {}
                                                ReactionChips(m, ui, m.authorId == ui.me) {}
                                            }
                                        }
                                        menuFor = m
                                    }
                                    .doubleTapToReact(!m.deleted && m.sendState == null && !m.isSystem, key = m.id + m.reactions[ui.me]) {
                                        actions.onReact(m, MessageRules.nextReaction(m.reactions[ui.me], quickReaction))
                                    }) {
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
                // iOS groupHistoryCard: once the whole history is here, the group's card stays above its
                // oldest message (never between pages); scrolling up to the start shows it.
                if (ui.loaded && ui.isGroup && ui.channel == null && !ui.notAMember && !ui.canLoadOlder &&
                    ui.messages.isNotEmpty() && ui.groupDoc.isNotEmpty()) item(key = "groupWelcome") {
                    Box(Modifier.fillMaxWidth().padding(vertical = 20.dp), contentAlignment = Alignment.Center) {
                        GroupWelcomeCard(ui, onStep = { peopleNav.groupStep(ui.cid, it) })
                    }
                }
            }
            }
            if (search.active && search.asList && search.matches.isNotEmpty()) {
                SearchResultsList(searchRows(ui, search.matches), com.fariin.app.data.ChatSearch.queryTerms(search.term),
                    top = with(density) { topBarPx.toDp() }, bottom = with(density) { bottomBarPx.toDp() }, is24h = is24h) { id ->
                    // A row tapped: that message becomes the current match, the chat comes back and jumps to it.
                    search.matches.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { search.index = it }
                    search.asList = false
                    keyboard?.hide()
                    goToMatch()
                }
            }
            if (!atBottom && !search.active) {
                JumpArrow((ui.messages.size - seenCount).coerceAtLeast(0), onClick = {
                    scope.launch { listState.animateScrollToItem(0) }
                }, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = with(density) { bottomBarPx.toDp() } + 12.dp))
            }
        }
        // The bottom bar floats over the end of the chat too (iOS paints no bar behind its controls):
        // it takes no height of its own, draws over the list's last stretch, and the list is padded by it.
        Column(Modifier.fillMaxWidth().zIndex(1f)
            .layout { m, cs -> val pl = m.measure(cs); layout(pl.width, 0) { pl.place(0, -pl.height) } }
            .onSizeChanged { bottomBarPx = it.height }) {
            if (search.active) {
                SearchNavBar(search, onStep = { d ->
                    search.index = (search.index + d).coerceIn(0, maxOf(0, search.matches.size - 1)); goToMatch()
                }, onList = { search.asList = it })
            } else if (selecting) {
                val canDelete = picked.isNotEmpty() && (!ui.isGroup ||
                    picked.any { it.sendState != null || MessageRules.canDeleteForEveryone(it, ui.me, ui.adminMayDelete) })
                SelectionBar(
                    deleteEnabled = canDelete,
                    forwardEnabled = com.fariin.app.data.SelectRules.forwardable(picked),
                    copyEnabled = picked.any(MessageRules::canCopy),
                    busy = false,
                    onDelete = { bulkDelete = true },
                    onForward = { bulkForward = picked.sortedBy { it.createdAtMillis } },
                    onCopy = {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(com.fariin.app.data.SelectRules.copyText(picked)))
                        if (android.os.Build.VERSION.SDK_INT < 33) actions.onNotice("Copied")
                        selected.clear()
                    },
                )
            } else if (ch != null && ch.role != com.fariin.app.channel.ChannelRules.Role.ADMIN) {
                HorizontalDivider(thickness = 0.5.dp, color = c.separator)
                com.fariin.app.ui.channels.ChannelBottomBar(ch, ui.sendBlocked)
            } else if (ui.receipts.iBlocked && !ui.isGroup) {
                // iOS blockedBar: the action alone, in the text colour.
                HorizontalDivider(thickness = 0.5.dp, color = c.separator)
                Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.TextButton(onClick = actions.onUnblock) {
                        Text("Unblock", fontSize = 17.sp, color = c.label)
                    }
                }
            } else if (ui.notAMember) {
                // iOS removedBar: one centred line where the composer was.
                HorizontalDivider(thickness = 0.5.dp, color = c.separator)
                Text("You're no longer a member of this group", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = c.secondary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp))
            } else if (ui.sendBlocked != null) {
                HorizontalDivider(thickness = 0.5.dp, color = c.separator)
                Text(ui.sendBlocked, fontSize = 15.sp, color = c.secondary,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp))
            } else {
                replyTo?.let { r ->
                    ComposerBar(if (r.authorId == ui.me) "Reply to yourself" else "Reply to ${ui.names[r.authorId] ?: ui.title}",
                        com.fariin.app.data.CardRules.previewWord(r.text) ?: r.bodyText) { replyTo = null }
                }
                editing?.let { e -> ComposerBar("Edit message", e.text) { editing = null } }
                // The box's words, for the link card above it (LinkDraftBar).
                var linkText by remember { mutableStateOf(actions.draft) }
                val send: (String, List<String>) -> Unit = { t, ids ->
                    val r = replyTo
                    val e = editing
                    val tagged = actions.onSendMentioned.takeIf { ids.isNotEmpty() }
                    when {
                        e != null -> { editing = null; if (t.trim() != e.text.trim()) actions.onEdit(e, t) }
                        tagged != null -> { replyTo = null; tagged(t, r, ids) }
                        r != null -> { replyTo = null; actions.onSendReply(t, r) }
                        else -> onSend(t)
                    }
                }
                var attachOpen by remember { mutableStateOf(false) }
                var pollOpen by remember { mutableStateOf(false) }
                var locationOpen by remember { mutableStateOf(false) }
                var contactOpen by remember { mutableStateOf(false) }
                // What a group's members may send (iOS heldBackText / attachMediaOffText / slow mode):
                // said in a snackbar before the send, the rules refuse it anyway.
                val resolver = androidx.compose.ui.platform.LocalContext.current.contentResolver
                val doc = ui.groupDoc
                fun held(kind: com.fariin.app.data.GroupAdmins.SendKind): String? =
                    if (ui.isGroup && doc.isNotEmpty()) com.fariin.app.data.GroupAdmins.heldBack(doc, ui.me, kind) else null
                fun slow(): String? = if (!ui.isGroup || doc.isEmpty()) null else com.fariin.app.data.GroupAdmins.slowWait(doc, ui.me,
                    ui.messages.lastOrNull { it.authorId == ui.me && !it.isSystem }?.createdAtMillis, System.currentTimeMillis().toDouble())
                val mediaOff = if (ui.isGroup && doc.isNotEmpty()) com.fariin.app.data.GroupAdmins.mediaOff(doc, ui.me) else null
                val gatedSend: (String, List<String>) -> Unit = { t, ids ->
                    val links = if (com.fariin.app.data.GroupAdmins.hasLink(t)) held(com.fariin.app.data.GroupAdmins.SendKind.LINKS) else null
                    val stop = slow() ?: links
                    if (stop != null && editing == null) actions.onNotice(stop) else send(t, ids)
                }
                if (attachOpen) AttachSheet(allowViewOnce = !ui.isGroup, onDismiss = { attachOpen = false },
                    onSendMedia = { uris, cap, once ->
                        val kinds = uris.map { if ((resolver.getType(it) ?: "").startsWith("video/")) com.fariin.app.data.GroupAdmins.SendKind.VIDEOS
                            else com.fariin.app.data.GroupAdmins.SendKind.PHOTOS }.distinct()
                        val stop = slow() ?: kinds.firstNotNullOfOrNull { held(it) }
                        if (stop != null) actions.onNotice(stop) else onSendPick(uris, cap, once)
                    },
                    onSendFile = { u -> val stop = slow() ?: held(com.fariin.app.data.GroupAdmins.SendKind.FILES); if (stop != null) actions.onNotice(stop) else onSendFile(u) },
                    mediaOff = mediaOff,
                    // iOS: the Poll tile is in groups only, held back like the other kinds.
                    onPoll = if (ui.isGroup) ({
                        val stop = slow() ?: held(com.fariin.app.data.GroupAdmins.SendKind.POLLS)
                        if (stop != null) actions.onNotice(stop) else pollOpen = true
                    }) else null,
                    // Contact and location cards (CardSheets): sealed text markers, sent like a text.
                    onLocation = {
                        val stop = slow() ?: held(com.fariin.app.data.GroupAdmins.SendKind.LOCATION)
                        if (stop != null) actions.onNotice(stop) else locationOpen = true
                    })
                // No Contact tile: iOS took it off the attach sheet ("No phone numbers anywhere", 46d10cb2).
                // Received cards still draw and open; the picker below waits for a Share-from-profile entry.
                if (pollOpen) PollComposer(onDismiss = { pollOpen = false }) { marker -> onSend(marker) }
                if (locationOpen) LocationSheet(onDismiss = { locationOpen = false }) { lat, lon, label ->
                    onSend(com.fariin.app.data.CardRules.locationText(lat, lon, label))
                }
                if (contactOpen) ContactPickSheet(ui.me, ui.cid, onDismiss = { contactOpen = false }) { card ->
                    onSend(com.fariin.app.data.CardRules.contactText(card.uid, card.name, card.photo))
                }
                // iOS: the link card rides the same slot as the reply bar, never while editing.
                if (editing == null) LinkDraftBar(ui.cid, ui.me, linkText,
                    allowed = held(com.fariin.app.data.GroupAdmins.SendKind.LINKS) == null)
                Composer(gatedSend, { attachOpen = true }, { r ->
                        val stop = slow() ?: held(com.fariin.app.data.GroupAdmins.SendKind.VOICE)
                        if (stop != null) actions.onNotice(stop) else onSendVoice(r)
                    }, prefill = editing?.let { it.id to it.text }, onTyping = actions.onTyping,
                    initialDraft = actions.draft, onDraft = { actions.onDraft(it); linkText = it },
                    // The sticker panel's picks (iOS StickerSend.send / sendGif), held back like the rest.
                    me = ui.me,
                    onSticker = { s ->
                        val stop = slow() ?: held(com.fariin.app.data.GroupAdmins.SendKind.STICKERS)
                        if (stop != null) actions.onNotice(stop) else { val r = replyTo; replyTo = null; actions.onSendSticker(s, r) }
                    },
                    onGif = { gif ->
                        val stop = slow() ?: held(com.fariin.app.data.GroupAdmins.SendKind.GIFS)
                        if (stop != null) actions.onNotice(stop) else { val r = replyTo; replyTo = null; actions.onSendGif(gif, r) }
                    },
                    // iOS mentionPicker: groups only (MentionPicker.kt).
                    mentions = if (ui.isGroup) MentionSource(ui.members, ui.me, ui.names, ui.photos) else null)
            }
        }
    }
}

/** iOS visiblePinIds: pins I can see (not hidden for me, not a tombstone), in pin order. */
private fun visiblePins(ui: ThreadUi): List<String> = ui.pinnedIds.filter { id ->
    !com.fariin.app.data.HiddenMessages.isHidden(id) &&
        (ui.messages.firstOrNull { it.id == id } ?: ui.pinned[id])?.deleted != true
}

/** The round tick at the start of a row in select mode. */
@Composable
private fun SelectTick(on: Boolean) {
    val c = Fariin.colors
    Box(Modifier.padding(start = 12.dp).size(24.dp).clip(CircleShape)
        .background(if (on) c.blue else Color.Transparent)
        .border(1.5.dp, if (on) c.blue else c.secondary.copy(alpha = 0.6f), CircleShape),
        contentAlignment = Alignment.Center) {
        if (on) Icon(Icons.Filled.Done, null, tint = Color.White, modifier = Modifier.size(16.dp))
    }
}

private fun noticeText(m: Message): String = when {
    m.isCall -> m.text.ifEmpty { "Call" }
    else -> m.text
}

@Composable
private fun Header(ui: ThreadUi, onBack: () -> Unit, onTitleClick: () -> Unit = {}) {
    val c = Fariin.colors
    Column(Modifier.fillMaxWidth().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            // Back in its own floating circle, the chevron as iOS (ChatHeaderView).
            IconButton(onClick = onBack, modifier = Modifier.padding(start = 4.dp, end = 8.dp).size(44.dp).floating()) {
                Icon(Icons.AutoMirrored.Filled.ArrowBackIos, contentDescription = "Back", tint = c.label,
                    modifier = Modifier.padding(start = 6.dp).size(20.dp))
            }
            // Photo and name beside it; the whole block opens the profile.
            Row(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable(onClick = onTitleClick).padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Avatar(ui.photoUrl, 40.dp, squircle = ui.channel != null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(ui.title, style = MaterialTheme.typography.titleMedium, color = c.label,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        // The timer is on: a small mark beside the name (iOS ChatHeaderView secondary icon).
                        if (ui.disappearSeconds > 0) {
                            Spacer(Modifier.width(4.dp))
                            Icon(androidx.compose.ui.res.painterResource(com.fariin.app.R.drawable.ic_disappearing),
                                contentDescription = "Disappearing messages on", tint = c.secondary, modifier = Modifier.size(16.dp))
                        }
                    }
                    if (ui.subtitle.isNotEmpty()) {
                        Text(ui.subtitle, style = MaterialTheme.typography.bodySmall, color = c.secondary, maxLines = 1)
                    }
                }
            }
            if (ui.channel == null && !ui.notAMember) com.fariin.app.ui.call.ThreadCallButtons(ui.cid, ui.me, ui.isGroup, ui.title, ui.photoUrl)
        }
    }
}

/** iOS ChatNoticePill: caption semibold, the received bubble's colour, a capsule. */
@Composable
private fun DatePill(text: String) = NoticePill(text, vertical = 8.dp)

@Composable
private fun NoticePill(text: String, vertical: Dp = 4.dp, icon: Boolean = false) {
    val c = Fariin.colors
    Box(Modifier.fillMaxWidth().padding(vertical = vertical), contentAlignment = Alignment.Center) {
        Row(Modifier.clip(CircleShape).background(receivedFill()).padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically) {
            // The timer line leads with the timer mark (iOS NoticeRow symbol "timer").
            if (icon) {
                Icon(androidx.compose.ui.res.painterResource(com.fariin.app.R.drawable.ic_disappearing), null,
                    tint = c.label, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = c.label)
        }
    }
}

/** Theme.received: F2F2F2 light, 26262B dark. */
@Composable
private fun receivedFill(): Color = if (Fariin.colors.dark) Color(0xFF26262B) else Color(0xFFF2F2F2)

/** Theme.defaultBubble: system blue (the default chat colour). */
@Composable
private fun myFill(): Color = com.fariin.app.ui.components.openChatBubbleColor() ?: Fariin.colors.blue

private val senderPalette = listOf(
    Color(0xFF007AFF), Color(0xFFAF52DE), Color(0xFFFF2D55), Color(0xFFFF9500),
    Color(0xFF34C759), Color(0xFF30B0C7), Color(0xFF5856D6), Color(0xFFFF3B30),
)

/** The sender's name in a group bubble, with their tag beside it (iOS role chip: its colour, else the name's). */
@Composable
private fun SenderLine(ui: ThreadUi, uid: String) {
    val tint = senderColor(uid)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(ui.names[uid] ?: "Someone", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = tint, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        ui.roles[uid]?.let { r ->
            val chip = com.fariin.app.data.GroupRole.rgb(r.color)?.let { Color(0xFF000000 or it) } ?: tint
            Spacer(Modifier.width(6.dp))
            Text(r.display, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = chip, maxLines = 1,
                modifier = Modifier.clip(CircleShape).background(chip.copy(alpha = 0.14f)).padding(horizontal = 6.dp, vertical = 1.5.dp))
        }
    }
}

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
        // A story mention (iOS storyMentionRow): a centred card, not a bubble. 1:1 only.
        m.storyMention?.takeIf { !ui.isGroup && !m.deleted }?.let { sm ->
            StoryMentionCard(m, sm, ui.me, ui.title, receivedFill())
            return@Row
        }
        com.fariin.app.data.StoryReplyRules.reactionGlyph(m, showSender)?.let { glyph ->
            StoryReaction(ui, m, glyph, isMe, tick, is24h)
            return@Row
        }
        // Stickers and GIFs (StickerGifBubbles.kt): the name, Forwarded and the quote above them.
        if (m.isSticker || m.isGif) {
            val header: @Composable () -> Unit = {
                Column {
                    if (showSender && g.first) SenderLine(ui, m.authorId)
                    if (m.forwarded) ForwardedTag(m, isMe, ui.channel != null)
                    m.replyTo?.let { ReplyQuote(it, ui, isMe) }
                }
            }
            val fill = if (isMe) myFill() else receivedFill()
            if (m.isSticker) StickerRow(m, isMe, shape, fill, header) { Meta(m, tick, isMe, is24h, onMedia = m.replyTo == null) }
            else GifRow(m, shape, fill, (showSender && g.first) || m.forwarded || m.replyTo != null, header) {
                Meta(m, tick, isMe, is24h, onMedia = true)
            }
            return@Row
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
                SenderLine(ui, m.authorId)
                Spacer(Modifier.height(1.dp))
            }
            if (m.forwarded && !m.deleted) {
                ForwardedTag(m, isMe, ui.channel != null)
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
            // A poll (PollBubble.kt): the card rides sealed in the text.
            m.poll?.let { p ->
                PollBody(m, p, PollContext(ui.cid, ui.me, ui.channel != null, ui.names, ui.photos), isMe,
                    minOf(maxW - 28.dp, 300.dp)) { Meta(m, tick, isMe, is24h) }
                return@Column
            }
            // Contact and location cards (CardBubbles.kt): markers in the sealed text, as iOS.
            if (!m.deleted) {
                com.fariin.app.data.CardRules.location(m.text)?.let { loc ->
                    LocationBody(loc, isMe, minOf(maxW - 28.dp, 260.dp)) { Meta(m, tick, isMe, is24h) }
                    return@Column
                }
                com.fariin.app.data.CardRules.contact(m.text)?.let { card ->
                    ContactBody(card, isMe, ui.me, ui.cid, minOf(maxW - 28.dp, 260.dp)) { Meta(m, tick, isMe, is24h) }
                    return@Column
                }
            }
            val ink = if (isMe) Color.White else c.label
            val body = when {
                m.deleted -> if (isMe && m.deletedBy == null) "You deleted this message" else "This message was deleted"
                else -> m.bodyText
            }
            // The sender's link card over the words (iOS: quote, card, then the text).
            if (!m.deleted) m.linkPreview?.let { LinkPreviewCard(it, isMe) }
            TextWithMeta(
                text = body,
                style = LocalBubbleText.current.copy(color = if (m.deleted) ink.copy(alpha = 0.7f) else ink),
                meta = { Meta(m, tick, isMe, is24h) },
                // iOS BubbleText: a group's @mentions in semibold.
                bold = remember(body, m.mentions, ui.names, m.deleted) {
                    if (m.deleted) emptyList() else com.fariin.app.data.MentionRules.ranges(body, m.mentions, ui.names)
                },
                // iOS BubbleText: links, @usernames, emails and numbers tappable (BubbleLinks.kt).
                linkInk = if (m.deleted) null else if (isMe) Color.White else c.blue,
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
                    SenderLine(ui, m.authorId)
                }
                if (m.forwarded) ForwardedTag(m, isMe, ui.channel != null)
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
                val threadCid = LocalThreadCid.current
                PictureBox(m, url, m.enc, maxW, RectangleShape, captionW, busy, onOpen = {
                    url?.let { onView(Viewing.Photo(it, if (m.localPath != null) null else m.enc, msg = m)) }
                }, overlay = capsule, autoSaveId = if (!isMe && !m.viewOnce && threadCid.isNotEmpty()) "$threadCid/${m.id}" else null)
            }
            Message.MediaKind.VIDEO -> {
                val ready = m.videoUrl != null
                PictureBox(m, m.thumbUrl, m.thumbEnc, maxW, RectangleShape, captionW, busy || !ready, onOpen = {
                    m.videoUrl?.let { onView(Viewing.Video(it, m.enc, msg = m)) }
                }, overlay = { VideoOverlay(m, ready); capsule() })
            }
            else -> {
                val threadCid = LocalThreadCid.current
                AlbumMosaic(m, maxW, onView, capsule, autoSaveId = if (!isMe && !m.viewOnce && threadCid.isNotEmpty()) "$threadCid/${m.id}" else null)
            }
        }
        if (caption != null) {
            Box(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                TextWithMeta(caption, LocalBubbleText.current.copy(color = ink), linkInk = if (isMe) Color.White else c.blue) { Meta(m, tick, isMe, is24h) }
            }
        }
    }
}

/**
 * A reaction from the story's react bar (iOS MessageRowLayout, "a story reaction sits on the
 * card's corner"): no bubble; "You replied to their story" over the 80 x 140 story card with the
 * grey bar on the outside, the emoji big on the card's bottom corner away from the bar, and the
 * time just under the card.
 */
@Composable
private fun StoryReaction(ui: ThreadUi, m: Message, glyph: String, isMe: Boolean, tick: Tick, is24h: Boolean) {
    val c = Fariin.colors
    val r = m.replyTo ?: return
    val caption = com.fariin.app.data.StoryReplyRules.caption(isMe, r.authorId == ui.me, ui.names[m.authorId] ?: ui.title)
    // Card 80 wide, bar 5, gap 9 (iOS); the emoji's box (56) centred 6 in from the card's inner
    // edge, so it hangs 22 past the card; the time 12 under the card.
    val glyphBox = 56.dp
    val overhang = 22.dp
    val glyphSize = with(androidx.compose.ui.platform.LocalDensity.current) { 46.dp.toSp() }
    Column(horizontalAlignment = if (isMe) Alignment.End else Alignment.Start) {
        Text(caption, fontSize = 12.sp, color = c.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp))
        Spacer(Modifier.height(5.dp))
        Box(Modifier.width(94.dp + overhang).height(168.dp)) {
            val card: @Composable () -> Unit = {
                coil3.compose.AsyncImage(r.storyThumb, contentDescription = "Story",
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.width(80.dp).height(140.dp).clip(RoundedCornerShape(14.dp))
                        .background(c.secondary.copy(alpha = 0.15f)))
            }
            val bar: @Composable () -> Unit = {
                Box(Modifier.width(5.dp).height(140.dp).clip(RoundedCornerShape(2.5.dp)).background(c.label.copy(alpha = 0.35f)))
            }
            Row(Modifier.align(if (isMe) Alignment.TopEnd else Alignment.TopStart)) {
                if (isMe) { card(); Spacer(Modifier.width(9.dp)); bar() } else { bar(); Spacer(Modifier.width(9.dp)); card() }
            }
            // The card's inner corner: left for mine (x = overhang), right for theirs (x = 14 + 80).
            val glyphX = if (isMe) overhang + 6.dp - glyphBox / 2 else 94.dp - 6.dp - glyphBox / 2
            Box(Modifier.offset(x = glyphX, y = 140.dp - glyphBox * 0.62f).size(glyphBox), contentAlignment = Alignment.Center) {
                Text(glyph, fontSize = glyphSize, lineHeight = glyphSize, maxLines = 1, softWrap = false)
            }
            Box(Modifier.align(if (isMe) Alignment.TopEnd else Alignment.TopStart)
                .offset(x = if (isMe) -(14.dp) else 14.dp, y = 152.dp)) {
                Meta(m, tick, isMe, is24h, plain = true)
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
            Text(if (r.isStatus) "Story" else com.fariin.app.data.CardRules.previewWord(r.text) ?: r.text, fontSize = 12.sp, color = if (isMe) Color.White.copy(alpha = 0.85f) else c.label,
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
private fun Meta(m: Message, tick: Tick, isMe: Boolean, is24h: Boolean, onMedia: Boolean = false, plain: Boolean = false) {
    // [plain]: no bubble under it (a story reaction), so the page's own secondary colour.
    val metaColor = if (plain) Fariin.colors.secondary
        else if (isMe || onMedia) Color.White.copy(alpha = if (onMedia) 0.95f else 0.7f) else Fariin.colors.secondary
    Row(verticalAlignment = Alignment.CenterVertically) {
        com.fariin.app.ui.channels.PostViews(metaColor)
        // On a timer: the timer mark before the time (iOS footer), from the first frame.
        val mark = LocalTimerMark.current
        if (com.fariin.app.data.Disappearing.shownExpiry(m, mark.seconds, mark.sinceMs, System.currentTimeMillis().toDouble()) != null) {
            Icon(androidx.compose.ui.res.painterResource(com.fariin.app.R.drawable.ic_disappearing), contentDescription = "Disappearing",
                tint = metaColor, modifier = Modifier.size(11.dp))
            Spacer(Modifier.width(3.dp))
        }
        val time = ThreadLogic.timeLabel(m.clientTs?.takeIf { isMe && !m.hasServerTime } ?: m.createdAtMillis, is24h)
        Text((if (m.edited && !m.deleted) "edited " else "") + time, fontSize = 11.sp, color = metaColor, maxLines = 1)
        if (isMe && !m.deleted) {
            // Sent one drawn check, delivered and read two (iOS BubbleTicks), at their drawn size.
            val drawn = when (tick) {
                Tick.SENT -> com.fariin.app.R.drawable.ic_tick_single
                Tick.DELIVERED, Tick.READ -> com.fariin.app.R.drawable.ic_tick_double
                else -> null
            }
            if (tick == Tick.SENDING || drawn != null) {
                Spacer(Modifier.width(3.dp))
                val tint = if (plain) (if (tick == Tick.READ) Fariin.colors.blue else metaColor)
                    else Color.White.copy(alpha = if (tick == Tick.READ) 1f else 0.55f)
                if (drawn != null) Icon(androidx.compose.ui.res.painterResource(drawn), contentDescription = tickDescription(tick), tint = tint)
                else Icon(Icons.Filled.Schedule, contentDescription = tickDescription(tick), tint = tint, modifier = Modifier.size(14.dp))
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
private fun TextWithMeta(text: String, style: TextStyle, bold: List<IntRange> = emptyList(), linkInk: Color? = null, meta: @Composable () -> Unit) {
    val layoutRef = remember { arrayOfNulls<TextLayoutResult>(1) }
    Layout(content = {
        // In-chat search: the matched terms marked in yellow, never the whole bubble (iOS BubbleText).
        val term = LocalSearchTerm.current
        // Outside the list (the long-press menu's copy) the links are drawn but take no taps.
        val tap = LocalBubbleLinkTap.current ?: NoLinkTap
        Text(remember(text, term, bold, linkInk, tap) {
            val marked = mentionMarked(searchMarked(text, term), bold)
            if (linkInk == null) marked else linkMarked(marked, bold, linkInk, tap)
        }, style = style, onTextLayout = { layoutRef[0] = it })
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
    onSend: (String, List<String>) -> Unit, onAttach: () -> Unit, onSendVoice: (VoiceRecorder.Result) -> Unit,
    prefill: Pair<String, String>? = null, onTyping: (Boolean) -> Unit = {},
    initialDraft: String = "", onDraft: (String) -> Unit = {},
    me: String = "", onSticker: (com.fariin.app.data.StickerRef) -> Unit = {},
    onGif: (com.fariin.app.data.StickerGifRules.Gif) -> Unit = {},
    mentions: MentionSource? = null,
) {
    val c = Fariin.colors
    val context = LocalContext.current
    var draft by rememberSaveable { mutableStateOf(initialDraft) }
    // The words typed outside an edit: what the box gets back when an edit ends (iOS restores the
    // pre-edit draft, it was never sent).
    var kept by rememberSaveable { mutableStateOf(initialDraft) }
    var wasEditing by remember { mutableStateOf(false) }
    // iOS mentionMap: the members picked from the @ list, by name; resolved against the text at send.
    var picks by remember { mutableStateOf(emptyMap<String, List<String>>()) }
    // Edit puts the message's words in the field (keyed by message id, so it happens once).
    LaunchedEffect(prefill?.first) {
        if (prefill != null) { draft = prefill.second; wasEditing = true; picks = emptyMap() }
        else if (wasEditing) { draft = kept; wasEditing = false }
    }
    // The box's text with its cursor. Words put in from outside (an edit, the draft coming back,
    // a clear after send) get the cursor at their end; before, an edit started with the cursor in
    // front of the old words.
    var boxValue by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(draft, androidx.compose.ui.text.TextRange(draft.length))) }
    if (boxValue.text != draft) boxValue = androidx.compose.ui.text.input.TextFieldValue(draft, androidx.compose.ui.text.TextRange(draft.length))
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

    // As iOS ChatComposerView: a floating "+" (the attach sheet), the floating message pill with the
    // emoji button inside, and a floating round button that is the mic until there are words, then Send.
    var emojiOpen by rememberSaveable { mutableStateOf(false) }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val field = MaterialTheme.colorScheme.surfaceContainerHigh
    // A soft wash, not a bar: clear at the top, the page colour behind the controls (iOS composer).
    val walled = com.fariin.app.data.ChatLookStore.openCid.value?.let { com.fariin.app.ui.components.hasChatWallpaper(it) } == true
    Column(Modifier.fillMaxWidth().then(if (walled) Modifier else Modifier.background(androidx.compose.ui.graphics.Brush.verticalGradient(0f to c.bg.copy(alpha = 0f), 0.45f to c.bg.copy(alpha = 0.92f), 1f to c.bg))).navigationBarsPadding()) {
        // iOS mentionPicker, above the bar; hidden while editing (an edit tags nobody).
        if (mentions != null && prefill == null && !recording) MentionPicker(mentions, boxValue) { v, uid, name ->
            boxValue = v; draft = v.text
            picks = com.fariin.app.data.MentionRules.addPick(picks, name, uid)
            haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.Bottom) {
            if (recording) {
                Row(Modifier.weight(1f).height(48.dp).floating(RoundedCornerShape(24.dp)).padding(start = 16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
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
                IconButton(onClick = onAttach, modifier = Modifier.size(48.dp).floating()) {
                    Icon(Icons.Filled.Add, contentDescription = "Attach", tint = c.label, modifier = Modifier.size(26.dp))
                }
                Spacer(Modifier.width(8.dp))
                Row(
                    Modifier.weight(1f).heightIn(min = 48.dp).floating(RoundedCornerShape(24.dp)),
                    verticalAlignment = Alignment.Bottom,
                ) {
                Box(
                    Modifier.weight(1f).heightIn(min = 48.dp).padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (draft.isEmpty()) Text("Message", style = MaterialTheme.typography.bodyLarge, color = c.secondary)
                    BasicTextField(
                        value = boxValue, onValueChange = {
                            val now = System.currentTimeMillis()
                            if (now - lastKey > 10_000 || draft.isBlank()) lastKey = now
                            boxValue = it
                            draft = it.text
                        },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.label),
                        cursorBrush = SolidColor(c.blue),
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) emojiOpen = false },
                    )
                }
                IconButton(onClick = {
                    emojiOpen = !emojiOpen
                    if (emojiOpen) keyboard?.hide() else keyboard?.show()
                }) {
                    // iOS gifButton: the GIF mark opens the sticker panel, the keyboard mark brings the keys
                    // back; dimmer than the mic (label at 55%).
                    Icon(androidx.compose.ui.res.painterResource(if (emojiOpen) com.fariin.app.R.drawable.ic_keyboard else com.fariin.app.R.drawable.ic_gif),
                        contentDescription = if (emojiOpen) "Keyboard" else "GIF", tint = c.label.copy(alpha = 0.55f),
                        modifier = Modifier.size(24.dp))
                }
                }
            }
            Spacer(Modifier.width(8.dp))
            if (draft.isNotBlank() && !recording) {
                Box(
                    Modifier.size(48.dp).floating(fill = c.blue).clickable {
                        val t = draft
                        val ids = mentions?.let { com.fariin.app.data.MentionRules.forSend(t.trim(), picks, it.members) } ?: emptyList()
                        picks = emptyMap()
                        draft = ""
                        onSend(t, ids)
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.ArrowUpward, contentDescription = "Send", tint = Color.White, modifier = Modifier.size(24.dp))
                }
            } else {
                Box(
                    Modifier.size(48.dp).floating(fill = if (recording) c.blue else null)
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                val downAt = down.uptimeMillis
                                var upAt = downAt
                                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                                    PackageManager.PERMISSION_GRANTED
                                if (!granted) {
                                    askMic.launch(Manifest.permission.RECORD_AUDIO)
                                    return@awaitEachGesture
                                }
                                VoicePlayer.stop()
                                if (!recorder.start()) return@awaitEachGesture
                                haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                recording = true; slide = 0f
                                var cancelled = false
                                while (true) {
                                    val e = awaitPointerEvent()
                                    val ch = e.changes.firstOrNull { it.id == down.id } ?: break
                                    slide = ch.position.x - down.position.x
                                    upAt = ch.uptimeMillis
                                    ch.consume()
                                    if (slide < -cancelAt) { cancelled = true; break }
                                    if (!ch.pressed) break
                                }
                                recording = false; slide = 0f
                                // A tap, not a hold: nothing is sent and the iOS hint says how (ChatComposerView
                                // holdHintPill, "Hold to record, release to send").
                                val tapped = upAt - downAt < 300
                                if (cancelled || tapped) recorder.cancel() else recorder.finish { it?.let(onSendVoice) }
                                if (tapped && !cancelled) com.fariin.app.ui.components.Snack.show("Hold to record, release to send")
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Mic, contentDescription = "Hold to record a voice message",
                        tint = if (recording) Color.White else c.label, modifier = Modifier.size(26.dp))
                }
            }
        }
        if (emojiOpen && !recording) {
            BackHandler { emojiOpen = false }
            StickerPanel(me, onSticker = onSticker, onGif = onGif)
        }
    }
}

/** The emoji panel that replaces the keyboard (Android messengers keep one under the field). */
@Composable
private fun EmojiPanel(onPick: (String) -> Unit) {
    val list = remember { EMOJI.split(' ').filter { it.isNotBlank() } }
    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
        columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(48.dp),
        modifier = Modifier.fillMaxWidth().height(280.dp).background(MaterialTheme.colorScheme.surfaceContainerLow),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
    ) {
        items(list.size) { i ->
            Box(Modifier.size(48.dp).clip(CircleShape).clickable { onPick(list[i]) }, contentAlignment = Alignment.Center) {
                Text(list[i], fontSize = 28.sp)
            }
        }
    }
}

private const val EMOJI = "😀 😃 😄 😁 😆 😅 😂 🤣 😊 😇 🙂 🙃 😉 😌 😍 🥰 😘 😗 😙 😚 😋 😛 😝 😜 🤪 🤨 🧐 🤓 😎 🥳 😏 😒 😞 😔 😟 😕 🙁 😣 😖 😫 😩 🥺 😢 😭 😤 😠 😡 🤯 😳 🥵 🥶 😱 😨 😰 😥 😓 🤗 🤔 🤭 🤫 🤥 😶 😐 😑 😬 🙄 😯 😦 😧 😮 😲 🥱 😴 🤤 😪 😵 🤐 🥴 🤢 🤮 🤧 😷 🤒 🤕 👍 👎 👌 ✌️ 🤞 🤝 🙏 👏 🙌 👋 💪 ✋ 👀 ❤️ 🧡 💛 💚 💙 💜 🖤 🤍 💔 💯 🔥 ✨ 🎉 🎂 🎁 🌹 🌸 ☀️ 🌙 ⭐ ⚡ ☕ 🍵 🍕 🍰 🍎 🏠 🚗 ✈️ 📞 📷 🎵 ⚽ 📚 ✅ ❌ ❓ ❗"

/** Settings > Chats > Message Text Size: the words in a bubble (Medium is the old 17 sp). */
internal val LocalBubbleText = androidx.compose.runtime.staticCompositionLocalOf { bubbleText("medium") }

internal fun bubbleText(size: String): TextStyle = when (size) {
    "small" -> TextStyle(fontSize = 15.sp, lineHeight = 20.sp)
    "large" -> TextStyle(fontSize = 20.sp, lineHeight = 25.sp)
    else -> TextStyle(fontSize = 17.sp, lineHeight = 22.sp)
}

/** Settings > Chats > Quick Reaction: what a double tap on a message sends (iOS QuickReaction). */
@Composable
private fun rememberQuickReaction(): String {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    return remember {
        ctx.getSharedPreferences(com.fariin.app.ui.settings.CHAT_PREFS, android.content.Context.MODE_PRIVATE)
            .getString(com.fariin.app.ui.settings.SettingsRules.QUICK_KEY, null)?.takeIf { it.isNotEmpty() } ?: "❤️"
    }
}

/** Double tap reacts. The first tap is left alone, so a photo or voice bubble still opens or plays at
 *  once; only a second tap close behind is taken (and eaten, so it does not open the photo again). */
private fun Modifier.doubleTapToReact(enabled: Boolean, key: Any, onDouble: () -> Unit): Modifier =
    if (!enabled) this else this.pointerInput(key) {
        awaitEachGesture {
            val first = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val e = awaitPointerEvent(PointerEventPass.Initial)
                    val ch = e.changes.firstOrNull { it.id == first.id } ?: return@withTimeoutOrNull null
                    if (!ch.pressed) return@withTimeoutOrNull ch
                }
                @Suppress("UNREACHABLE_CODE") null
            } ?: return@awaitEachGesture
            if ((up.position - first.position).getDistance() > viewConfiguration.touchSlop) return@awaitEachGesture
            val second = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            } ?: return@awaitEachGesture
            if ((second.position - first.position).getDistance() > viewConfiguration.touchSlop * 4) return@awaitEachGesture
            second.consume()
            while (true) {
                val e = awaitPointerEvent(PointerEventPass.Initial)
                val ch = e.changes.firstOrNull { it.id == second.id } ?: break
                ch.consume()
                if (!ch.pressed) { onDouble(); break }
            }
        }
    }

/** Long press only (a tap still reaches the photo and voice bubbles inside). Watched in the
 *  Initial pass, so a photo or video that takes taps itself still opens the menu on a long press
 *  (iOS), and the press is then eaten so its release does not also open the viewer. */
private fun Modifier.combinedClickableLong(onLong: () -> Unit): Modifier =
    this.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val slop = viewConfiguration.touchSlop
            val long = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val e = awaitPointerEvent(PointerEventPass.Initial)
                    val ch = e.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
                    if (!ch.pressed || ch.isConsumed || (ch.position - down.position).getDistance() > slop) return@withTimeoutOrNull false
                }
                @Suppress("UNREACHABLE_CODE") false
            }
            if (long != null) return@awaitEachGesture
            onLong()
            while (true) {
                val e = awaitPointerEvent(PointerEventPass.Initial)
                e.changes.forEach { it.consume() }
                if (e.changes.none { it.pressed }) break
            }
        }
    }

/** Shows [content] while [open] is on. The flag is read here, so flipping it redraws only this. */
@Composable
private fun OpenWhen(open: androidx.compose.runtime.State<Boolean>, content: @Composable () -> Unit) {
    if (open.value) content()
}

/** The open chat's id, for bubbles that need it (Save to Photos' once-per-message ledger). */
internal val LocalThreadCid = androidx.compose.runtime.staticCompositionLocalOf { "" }

/** iOS ChatScrollStore: each chat's reading place (the row at the bottom edge and its offset), for this run only. */
internal object ChatReadingPositions {
    private val byCid = HashMap<String, Pair<String, Int>>()
    fun save(cid: String, rowId: String, offset: Int) { byCid[cid] = rowId to offset }
    fun clear(cid: String) { byCid.remove(cid) }
    fun get(cid: String): Pair<String, Int>? = byCid[cid]
}
