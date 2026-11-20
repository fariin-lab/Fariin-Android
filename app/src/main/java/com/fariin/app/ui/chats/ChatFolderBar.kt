package com.fariin.app.ui.chats

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
internal fun ChatFolderBar(chats: List<Conversation>, me: String, onEdit: (ChatFolder) -> Unit, onManage: () -> Unit) {
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
    val row = rememberLazyListState()
    LaunchedEffect(selected) { folders.indexOfFirst { it.id == selected }.takeIf { it >= 0 }?.let { row.animateScrollToItem(maxOf(0, it - 1)) } }
    LazyRow(Modifier.fillMaxWidth(), state = row, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(folders, key = { it.id }) { f ->
            val on = f.id == selected
            val s = stats[f.id] ?: FolderStats()
            var menu by remember { mutableStateOf(false) }
            var muteMenu by remember { mutableStateOf(false) }
            Box {
                Row(Modifier.height(34.dp).clip(CircleShape).background(if (on) c.fieldFill else c.card)
                    .combinedClickable(onClick = { ChatFoldersStore.select(f.id) },
                        onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); menu = true })
                    .padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(f.name, fontSize = 15.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (on) c.label else c.secondary, maxLines = 1)
                    if (s.unread > 0) {
                        Spacer(Modifier.width(6.dp))
                        Box(Modifier.defaultMinSize(minWidth = 18.dp, minHeight = 18.dp).clip(CircleShape)
                            .background(if (on) c.accent else c.secondary).padding(horizontal = 5.dp), contentAlignment = Alignment.Center) {
                            Text(if (s.unread > 99) "99+" else "${s.unread}", fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                                color = if (on) c.onAccent else c.bg)
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
