package com.fariin.app.ui.chats

import com.fariin.app.data.ChatCard
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.data.ChatFolder
import com.fariin.app.data.ChatFoldersStore
import com.fariin.app.data.Conversation
import com.fariin.app.ui.components.FariinMenu
import com.fariin.app.ui.components.GlassDropdown
import com.fariin.app.ui.components.Snack
import com.fariin.app.ui.theme.Fariin
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import com.fariin.app.ui.components.floating

/** What a chip's badge and menu need, counted once per change of the list or the folders. */
private class FolderStats(var unread: Int = 0, var readable: Int = 0, var total: Int = 0, var muted: Int = 0)

/**
 * Fariin Plus chat folders over the chat list (iOS ChatFolderBar): a chip per folder in the person's
 * order, with how many of its chats are unread (muted ones never counted, the tab badge's rule). A
 * long press: Mark All as Read, Mute All (1 h ... Always) or Unmute All, Edit Folder (Folder Settings
 * on All Chats). Nothing that deletes or renames: that stays on the Chat Folders page.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChatFolderBar(chats: List<Conversation>, me: String, onEdit: (ChatFolder) -> Unit, onManage: () -> Unit,
                           /** The chat list card, drawn under the tabs in the same panel (iOS 2026-11-20). */
                           card: ChatCard? = null) {
    val c = Fariin.colors
    val folders = ChatFoldersStore.folders
    val selected = ChatFoldersStore.selectedId
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val stats = remember(chats, folders, me) {
        val now = System.currentTimeMillis().toDouble()
        val shown = chats.filter { !it.isCleared(me) && !it.isArchived(me) }
        folders.associate { f ->
            val s = FolderStats()
            for (cv in shown) if (f.contains(cv, me, now)) {
                val muted = cv.isMuted(me, now)
                val unread = !cv.isBlockedByMe(me) && cv.hasUnreadMark(me)
                s.total++; if (muted) s.muted++; if (unread) s.readable++; if (unread && !muted) s.unread++
            }
            f.id to s
        }
    }
    fun chatsOf(f: ChatFolder): List<Conversation> {
        val now = System.currentTimeMillis().toDouble()
        return chats.filter { !it.isCleared(me) && !it.isArchived(me) && f.contains(it, me, now) }
    }
    // ONE glass capsule with plain-text tabs (iOS 2026-11-20): the chosen tab is a lens that slides,
    // its two edges on different springs so it stretches a little on the way; drag along the bar to
    // pick. Equal-ish widths when everything fits (the spare room shared), else the bar scrolls.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val bounds = remember { androidx.compose.runtime.mutableStateMapOf<String, Pair<Float, Float>>() }
    val scroll = androidx.compose.foundation.rememberScrollState()
    val heldCard = remember { arrayOf(card) }
    if (card != null) heldCard[0] = card
    androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth()) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        val avail = with(density) { maxWidth.toPx() } - with(density) { 8.dp.toPx() }
        val natural = folders.map { f ->
            val w = measurer.measure(f.name, androidx.compose.ui.text.TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)).size.width
            val badge = (stats[f.id]?.unread ?: 0).let { if (it > 0) with(density) { 24.dp.toPx() } else 0f }
            w + badge + with(density) { 28.dp.toPx() }
        }
        val fits = natural.sum() <= avail
        val widths = if (fits && natural.isNotEmpty()) { val spare = (avail - natural.sum()) / natural.size; natural.map { it + spare } } else natural
        val sel = bounds[selected]
        val left by androidx.compose.animation.core.animateFloatAsState(sel?.first ?: 0f,
            androidx.compose.animation.core.spring(dampingRatio = 0.78f, stiffness = 520f), label = "lensL")
        val right by androidx.compose.animation.core.animateFloatAsState((sel?.first ?: 0f) + (sel?.second ?: 0f),
            androidx.compose.animation.core.spring(dampingRatio = 0.78f, stiffness = 380f), label = "lensR")
        LaunchedEffect(selected, sel) {
            if (!fits && sel != null) {
                val target = (sel.first - avail / 2 + sel.second / 2).toInt().coerceIn(0, scroll.maxValue)
                scroll.animateScrollTo(target)
            }
        }
        fun pickAt(x: Float) {
            val px = x + scroll.value
            bounds.entries.firstOrNull { (_, b) -> px >= b.first && px <= b.first + b.second }?.key?.let { id ->
                if (id != ChatFoldersStore.selectedId) { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); ChatFoldersStore.select(id) }
            }
        }
        // The tabs are their own capsule; the chat list card sits under it on its own surface
        // (iOS 1a45a2f9c, 2026-11-21: no shared panel, no glass on glass).
        val panel = CircleShape
        androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().floating(panel).clip(panel)) {
        Box(Modifier.fillMaxWidth().height(42.dp)
            .pointerInput(fits) {
                if (fits) detectHorizontalDragGestures(onDragStart = { pickAt(it.x) }) { change, _ -> pickAt(change.position.x) }
            }) {
            Box(Modifier.fillMaxWidth().height(42.dp).then(if (fits) Modifier else Modifier.horizontalScroll(scroll)).padding(4.dp)) {
                // The lens under the chosen tab's words.
                if (sel != null) Box(Modifier.offset { androidx.compose.ui.unit.IntOffset(left.toInt(), 0) }
                    .width(with(density) { (right - left).coerceAtLeast(0f).toDp() }).height(34.dp).clip(CircleShape).background(c.fieldFill))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    folders.forEachIndexed { i, f ->
                        val on = f.id == selected
                        val s = stats[f.id] ?: FolderStats()
                        var menu by remember { mutableStateOf(false) }
                        var muteMenu by remember { mutableStateOf(false) }
                        Box(Modifier.onGloballyPositioned { lc -> bounds[f.id] = lc.positionInParent().x to lc.size.width.toFloat() }) {
                            Row(Modifier.width(with(density) { widths[i].toDp() }).height(34.dp).clip(CircleShape)
                                .combinedClickable(onClick = { ChatFoldersStore.select(f.id) },
                                    onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); menu = true }),
                                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                Text(f.name, fontSize = 15.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                                    color = if (on) c.label else c.secondary, maxLines = 1)
                                // The badge fades in and out and its count rolls (iOS pops 9).
                                val held = androidx.compose.runtime.remember { intArrayOf(s.unread) }
                                if (s.unread > 0) held[0] = s.unread
                                com.fariin.app.ui.components.Appear(s.unread > 0, resize = false) {
                                    Row {
                                        Spacer(Modifier.width(6.dp))
                                        Box(Modifier.defaultMinSize(minWidth = 18.dp, minHeight = 18.dp).clip(CircleShape)
                                            .background(c.accent).padding(horizontal = 5.dp), contentAlignment = Alignment.Center) {
                                            com.fariin.app.ui.components.RollingNumber(minOf(held[0], 100)) { n ->
                                                Text(if (n > 99) "99+" else "$n", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = c.onAccent)
                                            }
                                        }
                                    }
                                }
                            }
                            GlassDropdown(menu, onDismissRequest = { menu = false }, shape = FariinMenu.shape, border = FariinMenu.border, containerColor = c.card) {
                                if (s.readable > 0) DropdownMenuItem(text = { Text("Mark All as Read") }, onClick = {
                                    menu = false
                                    val list = chatsOf(f).filter { !it.isBlockedByMe(me) && it.hasUnreadMark(me) }
                                    scope.launch { list.forEach { ChatListActions.setUnread(me, it, false) } }
                                })
                                if (s.total > 0) {
                                    if (s.muted == s.total) DropdownMenuItem(text = { Text("Unmute All") }, onClick = {
                                        menu = false
                                        val now = System.currentTimeMillis().toDouble()
                                        val list = chatsOf(f).filter { it.isMuted(me, now) }
                                        scope.launch { list.forEach { ChatListActions.setMute(me, it, 0.0) }; Snack.show("Unmuted") }
                                    }) else DropdownMenuItem(text = { Text("Mute All") }, onClick = { menu = false; muteMenu = true })
                                }
                                if (f.isAll) DropdownMenuItem(text = { Text("Folder Settings") }, onClick = { menu = false; onManage() })
                                else DropdownMenuItem(text = { Text("Edit Folder") }, onClick = { menu = false; onEdit(f) })
                            }
                            MuteForMenu(muteMenu, onDismiss = { muteMenu = false }) { label, until ->
                                val now = System.currentTimeMillis().toDouble()
                                val list = chatsOf(f).filter { !it.isMuted(me, now) }
                                scope.launch { list.forEach { ChatListActions.setMute(me, it, until) }; Snack.show(MuteFor.doneWord(label)) }
                            }
                        }
                    }
                }
            }
        }
        }
    }
    // The card: radius 20, 16 in from each side, 8 below, fading in and out over 0.32 s (iOS); its
    // room opens and closes with it so the list under it slides, never jumps. All Chats only.
    val cardMotion = androidx.compose.animation.core.tween<Float>(320)
    androidx.compose.animation.AnimatedVisibility(card != null,
        enter = androidx.compose.animation.fadeIn(cardMotion) + androidx.compose.animation.expandVertically(androidx.compose.animation.core.tween(320)),
        exit = androidx.compose.animation.fadeOut(cardMotion) + androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(320))) {
        heldCard[0]?.let { shown ->
            val cardShape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
            ChatCardContent(shown, me, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                .floating(cardShape).clip(cardShape))
        }
    }
    }
}

