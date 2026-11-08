package com.fariin.app.ui.thread

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.data.CryptoHub
import com.fariin.app.media.MediaRules
import com.fariin.app.ui.components.Avatar
import com.fariin.app.ui.components.floating
import com.fariin.app.ui.theme.Fariin
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.fariin.app.R

/**
 * Sharing a group's or channel's link (iOS "Share with", owner 2026-11-07: Android opened the
 * system share page instead): "Share with / Select chats", a search circle on the left and the
 * system share on the right, the chats as a grid of faces, and one capsule at the bottom: Copy Link,
 * or "Send" once chats are picked (the link goes to each as a plain message).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LinkShareSheet(me: String, link: String, text: String = link, onDismiss: () -> Unit) {
    val c = Fariin.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val chosen = remember { mutableStateListOf<String>() }
    val chats = remember { CryptoHub.listedAll().filter { forwardable(it, me) }.sortedByDescending { it.updatedAtMillis } }
    val shown = chats.filter { query.isBlank() || it.displayName(me).contains(query.trim(), ignoreCase = true) }
    fun close(then: () -> Unit = {}) { scope.launch { sheet.hide() }.invokeOnCompletion { onDismiss(); then() } }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet,
        containerColor = com.fariin.app.ui.components.sheetFill, shape = com.fariin.app.ui.components.SheetShape) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).floating(CircleShape).clickable { searching = !searching; if (!searching) query = "" },
                    contentAlignment = Alignment.Center) { Icon(ImageVector.vectorResource(R.drawable.ic_search_ios), "Search", tint = c.label) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Share with", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = c.label)
                    Text(if (chosen.isEmpty()) "Select chats" else "${chosen.size} selected", fontSize = 14.sp, color = c.secondary)
                }
                Box(Modifier.size(44.dp).floating(CircleShape).clickable {
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(android.content.Intent.EXTRA_TEXT, text)
                    close { ctx.startActivity(android.content.Intent.createChooser(send, null).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }, contentAlignment = Alignment.Center) { Icon(ImageVector.vectorResource(R.drawable.ic_sf_share), "More ways to share", tint = c.label) }
            }
            if (searching) Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp).heightIn(min = 44.dp)
                .clip(RoundedCornerShape(22.dp)).background(c.fieldFill).padding(horizontal = 16.dp, vertical = 11.dp)) {
                if (query.isEmpty()) Text("Search", fontSize = 17.sp, color = c.secondary)
                BasicTextField(query, { query = it }, singleLine = true, textStyle = TextStyle(fontSize = 17.sp, color = c.label),
                    cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth())
            }
            if (shown.isEmpty()) Text(if (query.isBlank()) "No chats yet" else "No chats found", fontSize = 15.sp, color = c.secondary,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp))
            else LazyVerticalGrid(GridCells.Fixed(4), Modifier.fillMaxWidth().heightIn(max = 420.dp).padding(horizontal = 8.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(shown, key = { it.id }) { conv ->
                    val on = conv.id in chosen
                    Column(Modifier.clip(RoundedCornerShape(14.dp)).clickable {
                        if (on) chosen.remove(conv.id) else if (chosen.size < MediaRules.FORWARD_CHATS_AT_ONCE) chosen += conv.id
                    }.padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(68.dp)) {
                            Avatar(conv.displayPhoto(me), 64.dp, Modifier.align(Alignment.Center), squircle = conv.isChannel,
                                kind = com.fariin.app.ui.components.AvatarKind.of(conv.isGroup, conv.isChannel))
                            // iOS: a black tick badge (white in dark mode).
                            if (on) Box(Modifier.align(Alignment.BottomEnd).size(24.dp).clip(CircleShape).background(c.label)
                                .border(2.dp, com.fariin.app.ui.components.sheetFill, CircleShape), contentAlignment = Alignment.Center) {
                                Icon(ImageVector.vectorResource(R.drawable.ic_sf_checkmark), null, tint = c.bg, modifier = Modifier.size(14.dp))
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(conv.displayName(me), fontSize = 14.sp, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 4.dp))
                    }
                }
            }
            val sending = chosen.isNotEmpty()
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).height(52.dp).clip(CircleShape)
                .background(c.label)
                .clickable {
                    if (!sending) {
                        (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                            .setPrimaryClip(android.content.ClipData.newPlainText("Link", link))
                        if (android.os.Build.VERSION.SDK_INT < 33) com.fariin.app.ui.components.Snack.show("Link copied")
                        close(); return@clickable
                    }
                    val targets = chosen.toList()
                    val crypto = CryptoHub.get(ctx.applicationContext)
                    close {
                        com.fariin.app.ui.components.Snack.show(if (targets.size > 1) "Sent to ${targets.size} chats" else "Sent")
                    }
                    // Off the sheet's scope, so closing it does not stop the sends.
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
                        for (t in targets) runCatching {
                            com.fariin.app.data.ThreadRepository.openOrQuiet(t, me, crypto, this).sendForwardedText(text, forwarded = false)
                        }
                    }
                }, contentAlignment = Alignment.Center) {
                // iOS: "Send to N" in the same black capsule as Copy Link.
                Text(if (sending) "Send to ${chosen.size}" else "Copy Link", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.bg)
            }
        }
    }
}
