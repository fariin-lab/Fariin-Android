package com.fariin.app.ui.chats

import androidx.compose.material.icons.filled.Edit
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.data.ChatList
import com.fariin.app.data.Conversation
import com.fariin.app.data.MessageDecryptor
import com.fariin.app.data.CryptoHub
import com.fariin.app.ui.thread.LocalOpenChat
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.fariin.app.ui.components.Avatar
import com.fariin.app.ui.theme.Fariin
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The Chats tab: large "Chats" title, then a "Pinned" section and a "Chats" section when anything
 * is pinned (iOS ChatsView.chatSections), each row as iOS ChatRow in MainShell.swift.
 *
 * TODO (iOS has these): search bar, filters (All, Unread, Groups, Channels), swipe actions,
 * long-press menu, archived row, message requests row, typing and recording lines, drafts,
 * reactions preview, photo thumbnails, story rings, verified marks, opening a chat.
 */
@Composable
fun ChatListScreen(
    state: ChatsUiState,
    me: String,
    decryptor: MessageDecryptor,
    onOpen: (Conversation) -> Unit = LocalOpenChat.current,
) {
    val c = Fariin.colors
    val pinned = state.chats.filter { it.isPinned(me) }
    val rest = state.chats.filter { !it.isPinned(me) }
    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
        val people = com.fariin.app.ui.people.LocalPeopleNav.current
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Chats",
                fontSize = 34.sp, fontWeight = FontWeight.Bold, color = c.label,
                modifier = Modifier.weight(1f).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
            )
            androidx.compose.material3.IconButton(onClick = people.newChat) {
                androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Edit, contentDescription = "New Chat", tint = c.blue)
            }
        }
        if (state.requests.isNotEmpty()) {
            androidx.compose.foundation.layout.Row(
                Modifier.clickable(onClick = people.requests).fillMaxSize().height(44.dp).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Message Requests", fontSize = 17.sp, color = c.blue, modifier = Modifier.weight(1f))
                Text("${state.requests.size}", fontSize = 15.sp, color = c.secondary)
            }
        }
        when {
            state.loading && state.chats.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = c.secondary, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
            }
            state.error != null && state.chats.isEmpty() -> Centered(state.error)
            state.chats.isEmpty() -> Centered("No chats yet")
            else -> LazyColumn(Modifier.fillMaxSize()) {
                if (pinned.isNotEmpty()) {
                    item(key = "h-pinned") { SectionHeader("Pinned") }
                    items(pinned, key = { it.id }) { ChatRow(it, me, decryptor, onOpen) }
                    item(key = "h-chats") { SectionHeader("Chats") }
                }
                items(rest, key = { it.id }) { ChatRow(it, me, decryptor, onOpen) }
            }
        }
    }
}

@Composable
private fun Centered(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 15.sp, color = Fariin.colors.secondary)
    }
}

/** iOS: `.headline` (17 semibold) in the label colour, 14 above, 8 below, 16 each side. */
@Composable
private fun SectionHeader(text: String) {
    Text(
        text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Fariin.colors.label,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp),
    )
}

@Composable
fun ChatRow(conv: Conversation, me: String, decryptor: MessageDecryptor, onOpen: (Conversation) -> Unit) {
    val c = Fariin.colors
    val context = LocalContext.current
    val unread = if (conv.isBlockedByMe(me)) 0 else conv.unread(me)
    val now = System.currentTimeMillis().toDouble()
    val muted = conv.isMuted(me, now)
    val keys by CryptoHub.keysEpoch.collectAsState()
    val preview = remember(conv, me, keys) { ChatList.previewLine(conv, me, decryptor) { null } }
    // iOS: a chat with unsent words in its box says "Draft:" (red) and the words, not the last line.
    val drafts by com.fariin.app.local.Drafts.map.collectAsState()
    val draft = drafts[conv.id]
    val line = if (draft.isNullOrEmpty()) androidx.compose.ui.text.AnnotatedString(preview) else
        androidx.compose.ui.text.buildAnnotatedString {
            pushStyle(androidx.compose.ui.text.SpanStyle(color = c.red)); append("Draft: "); pop()
            append(draft.replace('\n', ' '))
        }
    val time = remember(conv.displayUpdatedAt(me)) { timeLabel(conv.displayUpdatedAt(me), DateFormat.is24HourFormat(context)) }

    Column(Modifier.fillMaxWidth().clickable { onOpen(conv) }) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(
                conv.displayPhoto(me), 56.dp,
                squircle = conv.isChannel,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).padding(top = 7.dp, bottom = 9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            conv.displayName(me),
                            fontSize = 17.sp,
                            fontWeight = if (unread > 0) FontWeight.Bold else FontWeight.SemiBold,
                            color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (muted) {
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.Filled.NotificationsOff, contentDescription = "Muted",
                                tint = c.tertiary, modifier = Modifier.size(15.dp))
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(time, fontSize = 15.sp, color = if (unread > 0) c.accent else c.secondary, maxLines = 1)
                }
                Row(verticalAlignment = Alignment.Top) {
                    // Two lines tall always, so every row is the same height (iOS, the hidden " \n ").
                    Text(
                        line, fontSize = 15.sp, color = c.secondary,

                        maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 1.dp),
                    ) {
                        if (conv.lastIsMine(me) && !conv.isChannel) Ticks(conv, me)
                        if (conv.hasUnreadReply(me)) {
                            Box(Modifier.size(20.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
                                Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = "Someone replied to you",
                                    tint = c.onAccent, modifier = Modifier.size(12.dp))
                            }
                        }
                        if (unread > 0) {
                            Box(
                                Modifier.height(20.dp).defaultMinSize(minWidth = 20.dp).clip(CircleShape)
                                    .background(c.accent).padding(horizontal = 5.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(if (unread > 99) "99+" else "$unread", fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold, color = c.onAccent, maxLines = 1)
                            }
                        } else if (conv.manuallyUnread(me)) {
                            Box(Modifier.size(12.dp).clip(CircleShape).background(c.accent))
                        }
                    }
                }
            }
        }
        // Inset to the text, as the iOS list draws its lines.
        HorizontalDivider(Modifier.padding(start = 84.dp), thickness = 0.5.dp, color = c.separator)
    }
}

/** One tick sent, two delivered, two in the accent colour read. TODO: the exact iOS tick art. */
@Composable
private fun Ticks(conv: Conversation, me: String) {
    val c = Fariin.colors
    val read = conv.lastReadByOther(me)
    val delivered = read || conv.lastDeliveredToOther(me)
    Icon(
        if (delivered) Icons.Filled.DoneAll else Icons.Filled.Done,
        contentDescription = if (read) "Read" else if (delivered) "Delivered" else "Sent",
        tint = if (read) c.accent else c.secondary,
        modifier = Modifier.size(16.dp),
    )
}

/** iOS ChatRow.timeStr: time today, "Yesterday", weekday this week, then "Mar 4" / "Mar 4, 2025". */
fun timeLabel(ms: Double, is24h: Boolean, nowMs: Long = System.currentTimeMillis()): String {
    if (ms <= 0) return ""
    val d = Date(ms.toLong())
    val then = Calendar.getInstance().apply { time = d }
    val now = Calendar.getInstance().apply { timeInMillis = nowMs }
    val startOfToday = (now.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    val dayMs = 24L * 60 * 60 * 1000
    val loc = Locale.getDefault()
    return when {
        d.time >= startOfToday.timeInMillis ->
            SimpleDateFormat(if (is24h) "HH:mm" else "h:mm a", loc).format(d)
        d.time >= startOfToday.timeInMillis - dayMs -> "Yesterday"
        nowMs - d.time < 7 * dayMs -> SimpleDateFormat("EEE", loc).format(d)
        then.get(Calendar.YEAR) != now.get(Calendar.YEAR) -> SimpleDateFormat("MMM d, yyyy", loc).format(d)
        else -> SimpleDateFormat("MMM d", loc).format(d)
    }
}
