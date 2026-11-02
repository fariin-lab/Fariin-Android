package com.fariin.app.ui.thread

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.data.Conversation
import com.fariin.app.data.CryptoHub
import com.fariin.app.media.MediaRules
import com.fariin.app.ui.components.Avatar
import com.fariin.app.ui.theme.Fariin

/**
 * The forward picker (iOS ForwardPicker): "Forward to", a search field, the chats newest first,
 * a tick on each picked row (up to five, Limits.forwardChatsAtOnce), and Send. A channel I do not
 * run and a request still waiting are not offered (the rules would refuse the send).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ForwardSheet(me: String, onDismiss: () -> Unit, onSend: (List<String>) -> Unit) {
    val c = Fariin.colors
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    val chosen = remember { mutableStateListOf<String>() }
    val chats = remember {
        CryptoHub.listedAll().filter { forwardable(it, me) }.sortedByDescending { it.updatedAtMillis }
    }
    val shown = chats.filter { query.isBlank() || it.displayName(me).contains(query.trim(), ignoreCase = true) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = c.bg) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Text("Forward to", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 10.dp))
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 36.dp).clip(RoundedCornerShape(10.dp))
                    .background(c.secondary.copy(alpha = 0.12f)).padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                if (query.isEmpty()) Text("Search", fontSize = 17.sp, color = c.secondary)
                BasicTextField(query, { query = it }, singleLine = true, textStyle = TextStyle(fontSize = 17.sp, color = c.label),
                    cursorBrush = SolidColor(c.blue), modifier = Modifier.fillMaxWidth())
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 520.dp).padding(top = 8.dp)) {
                items(shown, key = { it.id }) { conv ->
                    val on = conv.id in chosen
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            if (on) chosen.remove(conv.id) else if (chosen.size < MediaRules.FORWARD_CHATS_AT_ONCE) chosen += conv.id
                        }.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(conv.displayPhoto(me), 44.dp, squircle = conv.isChannel)
                        Spacer(Modifier.width(12.dp))
                        Text(conv.displayName(me), fontSize = 17.sp, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f))
                        Box(
                            Modifier.size(24.dp).clip(CircleShape).background(if (on) c.blue else Color.Transparent)
                                .border(1.5.dp, if (on) c.blue else c.secondary.copy(alpha = 0.5f), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { if (on) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(16.dp)) }
                    }
                }
            }
            Box(
                Modifier.fillMaxWidth().padding(16.dp).height(48.dp).clip(RoundedCornerShape(14.dp))
                    .background(if (chosen.isEmpty()) c.secondary.copy(alpha = 0.3f) else c.blue)
                    .clickable(enabled = chosen.isNotEmpty()) { onSend(chosen.toList()); onDismiss() },
                contentAlignment = Alignment.Center,
            ) {
                Text(if (chosen.size > 1) "Send to ${chosen.size} chats" else "Send", color = Color.White,
                    fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Chats a forward can land in: not a channel I only follow, not a request I sent that waits. */
internal fun forwardable(conv: Conversation, me: String): Boolean {
    if (conv.isChannel) return false
    if (conv.users.isNotEmpty() && me !in conv.users) return false
    if (!conv.isGroup && conv.startedBy == me && !conv.accepted) return false
    return true
}
