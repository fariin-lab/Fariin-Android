package com.fariin.app.ui.calls

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.call.CallLinkDefaults
import com.fariin.app.call.CallLinkDraft
import com.fariin.app.call.CallLinkKey
import com.fariin.app.call.CallLinkWire
import com.fariin.app.call.CallLinks
import com.fariin.app.call.LinkPeek
import com.fariin.app.call.SavedCallLink
import com.fariin.app.ui.call.CallActivity
import com.fariin.app.ui.components.FariinTopBar
import com.fariin.app.ui.components.Snack
import com.fariin.app.ui.theme.Fariin
import kotlinx.coroutines.launch

private const val NET_ERROR = "Check your connection and try again."

/** The round link mark that stands for a call link wherever a person's photo would be. */
@Composable
fun CallLinkAvatar(size: Dp) {
    val c = Fariin.colors
    Box(Modifier.size(size).clip(CircleShape).background(c.blue.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.Link, null, tint = c.blue, modifier = Modifier.size(size * 0.48f))
    }
}

fun copyLink(ctx: Context, url: String) {
    val cm = ctx.getSystemService(ClipboardManager::class.java)
    cm?.setPrimaryClip(ClipData.newPlainText("Call link", url))
    // Android 13 and later show their own "Copied" confirmation.
    if (android.os.Build.VERSION.SDK_INT < 33) Snack.show("Link copied")
}

fun shareLink(ctx: Context, url: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
    ctx.startActivity(Intent.createChooser(send, "Share call link").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Which of a link's settings the page edits: a fresh draft and a saved link share the page. */
private class LinkState(roomId: String, key: String, name: String, approval: Boolean?, video: Boolean?, val admin: Boolean) {
    val roomId = roomId
    val key = key
    var name by mutableStateOf(name)
    var approval by mutableStateOf(approval)
    var video by mutableStateOf(video)
    val url: String? get() = CallLinkKey.ofText(key)?.url(video ?: true)
}

/**
 * Create a call link (iOS CreateCallLinkSheet): the link is made on the server the moment the page
 * opens, then Join, Copy link, Share link, Call Name, Admin Approval and Call Type. It goes into my
 * Calls list on close, Join, Copy or Share, as iOS (a link made and thrown away is not kept).
 */
@Composable
fun CreateCallLinkScreen(onClose: () -> Unit) {
    val c = Fariin.colors
    var draft by remember { mutableStateOf<CallLinkDraft?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        failed = false
        draft = try { CallLinks.create() } catch (_: Exception) { failed = true; null }
    }
    val state = remember(draft?.roomId) { draft?.let { LinkState(it.roomId, it.key, it.name, it.approval, it.video, admin = true) } }
    fun persist() { state?.let { CallLinks.persist(CallLinkDraft(it.roomId, it.key, it.name, it.approval ?: true, it.video ?: true)) } }
    val close = { persist(); onClose() }
    BackHandler { close() }
    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
        FariinTopBar("Call Link", onBack = close)
        when {
            state != null -> LinkBody(state, onUsed = { persist() }, onDeleted = null)
            failed -> Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Text("Couldn't create a call link", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = c.label, textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                Text(NET_ERROR, fontSize = 15.sp, color = c.secondary, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = { attempt++ }) { Text("Try again", color = c.blue) }
            }
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = c.blue, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
            }
        }
    }
}

/** A saved link's page (iOS CallLinkDetailsView). Settings and Delete only for its creator. */
@Composable
fun CallLinkDetailsScreen(roomId: String, onBack: () -> Unit) {
    val c = Fariin.colors
    val links by CallLinks.links.collectAsState()
    val link = links.firstOrNull { it.roomId == roomId }
    var closing by remember { mutableStateOf(false) }
    LaunchedEffect(link == null) { if (link == null && !closing) onBack() }
    if (link == null) return
    val state = remember(link.roomId) { LinkState(link.roomId, link.key, link.name, null, null, link.admin) }
    LaunchedEffect(link.roomId) {
        val d = CallLinks.readDoc(link.roomId)
        if (d != null) {
            state.approval = d["restrictions"] == "adminApproval"
            state.video = d["video"] != false
        } else state.video = true
    }
    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
        FariinTopBar("Call Link", onBack = onBack)
        LinkBody(state, onUsed = {}, onDeleted = { closing = true; onBack() }, saved = link)
    }
}

@Composable
private fun LinkBody(s: LinkState, onUsed: () -> Unit, onDeleted: (() -> Unit)?, saved: SavedCallLink? = null) {
    val c = Fariin.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    var askDelete by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val title = s.name.ifEmpty { CallLinkDefaults.NAME }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            CallLinkAvatar(80.dp)
            Spacer(Modifier.height(12.dp))
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(s.url ?: "", fontSize = 14.sp, color = c.secondary, maxLines = 2, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    onUsed()
                    s.url?.let { CallActivity.startLink(ctx, s.key, s.video ?: true) }
                },
                enabled = s.url != null,
                colors = ButtonDefaults.buttonColors(containerColor = c.blue, contentColor = Color.White),
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { Text("Join Call", fontSize = 16.sp) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RoundAction(Icons.Filled.ContentCopy, "Copy link", Modifier.weight(1f)) { s.url?.let { onUsed(); copyLink(ctx, it) } }
            RoundAction(Icons.Filled.Share, "Share link", Modifier.weight(1f)) { s.url?.let { onUsed(); shareLink(ctx, it) } }
        }
        Spacer(Modifier.height(16.dp))
        if (s.admin) {
            HorizontalDivider(thickness = 0.5.dp, color = c.separator)
            SettingRow("Call Name", s.name.ifEmpty { "None" }) { editing = true }
            SwitchRow("Admin Approval", "New people wait until you let them in", s.approval) { on ->
                val before = s.approval
                s.approval = on
                scope.launch {
                    try { CallLinks.setApproval(s.roomId, on) } catch (_: Exception) { s.approval = before; error = "Couldn't change setting" }
                }
            }
            Text("Call Type", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = c.blue,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
            for ((label, video) in listOf("Video" to true, "Voice" to false)) {
                Row(Modifier.fillMaxWidth().clickable(enabled = s.video != null && s.video != video) {
                    val before = s.video
                    s.video = video
                    scope.launch {
                        try { CallLinks.setVideo(s.roomId, video) } catch (_: Exception) { s.video = before; error = "Couldn't change setting" }
                    }
                }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = s.video == video, onClick = null, modifier = Modifier.padding(8.dp))
                    Text(label, fontSize = 17.sp, color = c.label)
                }
            }
        }
        if (saved != null && onDeleted != null) {
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(thickness = 0.5.dp, color = c.separator)
            Row(Modifier.fillMaxWidth().clickable { askDelete = true }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Delete, null, tint = c.red)
                Spacer(Modifier.width(16.dp))
                Text("Delete Call Link", fontSize = 17.sp, color = c.red)
            }
        }
    }

    if (editing) NameDialog(s.name, onDismiss = { editing = false }) { name ->
        val key = CallLinkKey.ofText(s.key) ?: return@NameDialog
        scope.launch {
            try { s.name = CallLinks.rename(s.roomId, key, name); editing = false }
            catch (_: Exception) { editing = false; error = "Couldn't save the name" }
        }
    }
    if (askDelete && saved != null) AlertDialog(
        onDismissRequest = { askDelete = false },
        title = { Text("Delete this call link?") },
        text = { Text(if (saved.admin) "No one will be able to join with it any more." else "It will be removed from your calls.") },
        confirmButton = { TextButton({
            askDelete = false
            scope.launch {
                try { CallLinks.delete(saved); onDeleted?.invoke() } catch (_: Exception) { error = "Couldn't delete the call link" }
            }
        }) { Text("Delete", color = c.red) } },
        dismissButton = { TextButton({ askDelete = false }) { Text("Cancel", color = c.blue) } },
        containerColor = c.card,
    )
    error?.let { e -> AlertDialog(onDismissRequest = { error = null }, title = { Text(e) }, text = { Text(NET_ERROR) },
        confirmButton = { TextButton({ error = null }) { Text("OK", color = c.blue) } }, containerColor = c.card) }
}

@Composable
private fun RoundAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    val c = Fariin.colors
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(c.card).clickable(onClick = onClick).padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = c.blue)
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 14.sp, color = c.label)
    }
}

@Composable
private fun SettingRow(title: String, value: String, onClick: () -> Unit) {
    val c = Fariin.colors
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title, fontSize = 17.sp, color = c.label)
        Text(value, fontSize = 14.sp, color = c.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SwitchRow(title: String, sub: String, on: Boolean?, onChange: (Boolean) -> Unit) {
    val c = Fariin.colors
    Row(Modifier.fillMaxWidth().clickable(enabled = on != null) { onChange(!(on ?: false)) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 17.sp, color = c.label)
            Text(sub, fontSize = 14.sp, color = c.secondary)
        }
        com.fariin.app.ui.components.FariinSwitch(checked = on ?: false, onCheckedChange = if (on == null) null else onChange, enabled = on != null)
    }
}

@Composable
private fun NameDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val c = Fariin.colors
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Call Name") },
        text = {
            OutlinedTextField(text, { text = CallLinkDefaults.clamp(it) }, singleLine = true,
                supportingText = { Text("${text.codePointCount(0, text.length)}/${CallLinkDefaults.MAX_NAME_LENGTH}") })
        },
        confirmButton = { TextButton({ onSave(text) }) { Text("Save", color = c.blue) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel", color = c.blue) } },
        containerColor = c.card,
    )
}

/**
 * A tapped link (or a link row in the Calls tab): the link's pre-join screen.
 */
@Composable
fun JoinLinkScreen(keyText: String, videoFromPath: Boolean, onBack: () -> Unit) {
    // The pre-join screen is iOS CallLobbyView now, in the call's own activity (CallLobbyScreen):
    // the lobby and the call are one screen in two states, as on iOS. This route only hands over.
    val ctx = LocalContext.current
    LaunchedEffect(keyText) { CallActivity.startLobby(ctx, keyText, videoFromPath); onBack() }
    Box(Modifier.fillMaxSize().background(Color.Black))
}

/** A saved link's row in the Calls tab (iOS linkRow): tap joins via the join screen, (i) its page. */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun CallLinkRow(link: SavedCallLink, timeLabel: String, onOpen: () -> Unit, onInfo: () -> Unit, onLongClick: (() -> Unit)? = null) {
    val c = Fariin.colors
    Column {
        Row(Modifier.fillMaxWidth().combinedClickable(onLongClick = onLongClick, onClick = onOpen).padding(start = 16.dp, end = 4.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically) {
            CallLinkAvatar(46.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(link.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Link, null, tint = c.secondary, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Call Link", fontSize = 14.sp, color = c.secondary, maxLines = 1)
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(timeLabel, fontSize = 14.sp, color = c.secondary)
            IconButton(onClick = onInfo) {
                Icon(Icons.Outlined.Info, contentDescription = "Link details", tint = c.label, modifier = Modifier.size(22.dp))
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = c.separator, modifier = Modifier.padding(start = 74.dp))
    }
}
