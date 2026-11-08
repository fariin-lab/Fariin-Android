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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
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
import com.fariin.app.ui.components.floating
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.fariin.app.R

/**
 * The forward picker (iOS ForwardPicker): "Forward to", a search field, the chats newest first,
 * a tick on each picked row (up to five, Limits.forwardChatsAtOnce), and Send. A channel I do not
 * run and a request still waiting are not offered (the rules would refuse the send).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ForwardSheet(me: String, onDismiss: () -> Unit, title: String = "Forward to",
                          /** Pick one chat (Quote): a tap hands that chat back and closes; no ticks, no Send. */
                          pickOne: Boolean = false,
                          /** What is being forwarded, shown small beside the message box. */
                          preview: List<com.fariin.app.data.Message> = emptyList(),
                          /** Set: the bottom is the composer (preview, "Add a message…", send arrow) and
                           *  Send hands back the picked chats and the typed note (may be empty). */
                          onSendNote: ((List<String>, String) -> Unit)? = null,
                          onSend: (List<String>) -> Unit = {}) {
    val c = Fariin.colors
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    val chosen = remember { mutableStateListOf<String>() }
    val chats = remember {
        CryptoHub.listedAll().filter { forwardable(it, me) }.sortedByDescending { it.updatedAtMillis }
    }
    val shown = chats.filter { query.isBlank() || it.displayName(me).contains(query.trim(), ignoreCase = true) }
    // Rounded as iOS ForwardPicker: the grey sheet, the search as a floating pill, the chats in one
    // card, Send as a full capsule.
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet,
        containerColor = com.fariin.app.ui.components.sheetFill, shape = com.fariin.app.ui.components.SheetShape) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 10.dp))
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp)).background(c.fieldFill)
                    .padding(horizontal = 16.dp, vertical = 11.dp),
            ) {
                if (query.isEmpty()) Text("Search", fontSize = 17.sp, color = c.secondary)
                BasicTextField(query, { query = it }, singleLine = true, textStyle = TextStyle(fontSize = 17.sp, color = c.label),
                    cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth())
            }
            // A search that finds nothing says so, rather than leaving an empty sheet.
            if (shown.isEmpty()) Text(if (query.isBlank()) "No chats yet" else "No chats found", fontSize = 15.sp, color = c.secondary,
                modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            else LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 520.dp)
                .padding(start = 16.dp, end = 16.dp, top = 12.dp).clip(RoundedCornerShape(20.dp)).background(c.card)) {
                items(shown, key = { it.id }) { conv ->
                    val on = conv.id in chosen
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            if (pickOne) { onSend(listOf(conv.id)); onDismiss(); return@clickable }
                            if (on) chosen.remove(conv.id) else if (chosen.size < MediaRules.FORWARD_CHATS_AT_ONCE) chosen += conv.id
                        }.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(conv.displayPhoto(me), 44.dp, squircle = conv.isChannel, kind = com.fariin.app.ui.components.AvatarKind.of(conv.isGroup, conv.isChannel))
                        Spacer(Modifier.width(12.dp))
                        Text(conv.displayName(me), fontSize = 17.sp, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f))
                        if (!pickOne) Box(
                            Modifier.size(24.dp).clip(CircleShape).background(if (on) c.blue else Color.Transparent)
                                .border(1.5.dp, if (on) c.blue else c.secondary.copy(alpha = 0.5f), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { if (on) Icon(ImageVector.vectorResource(R.drawable.ic_sf_checkmark), null, tint = Color.White, modifier = Modifier.size(16.dp)) }
                    }
                }
            }
            if (pickOne) Spacer(Modifier.height(16.dp))
            else if (onSendNote != null) ForwardComposer(preview, chats.filter { it.id in chosen }, me) { note ->
                onSendNote(chosen.toList(), note); onDismiss()
            }
            else Box(
                Modifier.fillMaxWidth().padding(16.dp).height(50.dp)
                    .floating(CircleShape, fill = if (chosen.isEmpty()) c.secondary.copy(alpha = 0.3f) else c.blue)
                    .clickable(enabled = chosen.isNotEmpty()) { onSend(chosen.toList()); onDismiss() },
                contentAlignment = Alignment.Center,
            ) {
                Text(if (chosen.size > 1) "Send to ${chosen.size} chats" else "Send", color = Color.White,
                    fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Chats a forward can land in: not a channel I only follow (its admins are its members and may post
 *  there, as iOS canPostHere), not a request I sent that waits. */
internal fun forwardable(conv: Conversation, me: String): Boolean {
    if (conv.isChannel && me !in conv.users) return false
    if (conv.users.isNotEmpty() && me !in conv.users) return false
    if (!conv.isGroup && conv.startedBy == me && !conv.accepted) return false
    return true
}

/**
 * The forward sheet's bottom (iOS ForwardPicker.forwardComposer, plus the owner's 2026-11-07 ask to
 * see what is going): what is forwarded, small, then "Add a message…" with the round send arrow.
 * The preview is up to three cards fanned behind each other and a "+N" past three: enough to tell a
 * photo from a voice note without a header eating the list. Picked chats show as faces above once
 * there are two or more. The note goes out as its own message after the forwards, in every chat.
 */
@Composable
private fun ForwardComposer(preview: List<com.fariin.app.data.Message>, picked: List<Conversation>, me: String,
                            onSend: (String) -> Unit) {
    val c = Fariin.colors
    var note by remember { mutableStateOf("") }
    val ready = picked.isNotEmpty()
    Column(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp)) {
        if (picked.size > 1) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)
            .horizontalScroll(androidx.compose.foundation.rememberScrollState()),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy((-8).dp)) {
            picked.forEach { p ->
                Box(Modifier.size(30.dp).clip(CircleShape).background(com.fariin.app.ui.components.sheetFill).padding(2.dp)) {
                    Avatar(p.displayPhoto(me), 26.dp, squircle = p.isChannel,
                        kind = com.fariin.app.ui.components.AvatarKind.of(p.isGroup, p.isChannel))
                }
            }
        }
        if (preview.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            PreviewStack(preview)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (preview.size > 1) "${preview.size} messages" else "Forwarding", fontSize = 13.sp, color = c.secondary)
                Text(forwardSnippet(preview.first()), fontSize = 15.sp, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.Bottom) {
            Box(Modifier.weight(1f).heightIn(min = 42.dp).clip(RoundedCornerShape(21.dp)).background(c.card)
                .border(0.5.dp, c.label.copy(alpha = 0.08f), RoundedCornerShape(21.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp), contentAlignment = Alignment.CenterStart) {
                if (note.isEmpty()) Text("Add a message…", fontSize = 17.sp, color = c.secondary)
                BasicTextField(note, { note = it }, maxLines = 4, textStyle = TextStyle(fontSize = 17.sp, color = c.label),
                    cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(42.dp).clip(CircleShape).background(if (ready) c.blue else c.secondary.copy(alpha = 0.3f))
                .clickable(enabled = ready) { onSend(note.trim()) }
                .sendLabel(if (picked.size > 1) "Send to ${picked.size} chats" else "Send"),
                contentAlignment = Alignment.Center) {
                Icon(ImageVector.vectorResource(R.drawable.ic_sf_arrow_up), null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }
    }
}

private fun Modifier.sendLabel(label: String) =
    this.then(Modifier.semantics { contentDescription = label; role = androidx.compose.ui.semantics.Role.Button })

/** The fanned cards: up to three, the first on top, a "+N" chip when there are more. */
@Composable
private fun PreviewStack(msgs: List<com.fariin.app.data.Message>) {
    val c = Fariin.colors
    val shown = msgs.take(3)
    Box(Modifier.width(44.dp + 6.dp * (shown.size - 1)).height(44.dp)) {
        shown.asReversed().forEachIndexed { i, m ->
            val depth = shown.size - 1 - i
            Box(Modifier.padding(start = 6.dp * depth).size(44.dp)
                .graphicsLayer { alpha = 1f - 0.25f * depth }
                .clip(RoundedCornerShape(8.dp)).background(c.card)
                .border(0.5.dp, c.label.copy(alpha = 0.1f), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                if (hasReplyThumb(m)) ReplyThumb(m, 44.dp)
                else Icon(forwardIcon(m), null, tint = c.secondary, modifier = Modifier.size(22.dp))
            }
        }
        if (msgs.size > 3) Box(Modifier.align(Alignment.BottomEnd).clip(CircleShape).background(c.label.copy(alpha = 0.75f))
            .padding(horizontal = 5.dp, vertical = 1.dp)) {
            Text("+${msgs.size - 3}", fontSize = 11.sp, color = c.bg, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun forwardIcon(m: com.fariin.app.data.Message): ImageVector = when (m.mediaKind) {
    com.fariin.app.data.Message.MediaKind.VOICE -> ImageVector.vectorResource(R.drawable.ic_mic)
    com.fariin.app.data.Message.MediaKind.FILE -> ImageVector.vectorResource(R.drawable.ic_file)
    com.fariin.app.data.Message.MediaKind.VIDEO -> ImageVector.vectorResource(R.drawable.ic_sf_play_fill)
    com.fariin.app.data.Message.MediaKind.PHOTO, com.fariin.app.data.Message.MediaKind.ALBUM -> ImageVector.vectorResource(R.drawable.ic_sf_photo)
    else -> ImageVector.vectorResource(R.drawable.ic_sf_text_alignleft)
}

/** One line for what is forwarded: the chat list's words for media, the text itself otherwise. */
private fun forwardSnippet(m: com.fariin.app.data.Message): String = when {
    m.isSticker -> "Sticker"
    m.isGif -> "GIF"
    m.mediaKind == com.fariin.app.data.Message.MediaKind.ALBUM -> "🖼 ${m.album.size} photos" + m.text.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
    m.mediaKind == com.fariin.app.data.Message.MediaKind.FILE -> "📄 " + (m.fileName ?: "File")
    m.mediaKind == com.fariin.app.data.Message.MediaKind.PHOTO && m.text.isNotBlank() -> "📷 " + m.text
    m.mediaKind == com.fariin.app.data.Message.MediaKind.VIDEO && m.text.isNotBlank() -> "🎥 " + m.text
    else -> com.fariin.app.data.MessageInfoRules.snippet(m).ifBlank { "Message" }
}
