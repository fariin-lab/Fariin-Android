package com.fariin.app.ui.call

import com.fariin.app.ui.components.GlassAlert
import androidx.compose.ui.res.vectorResource
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.call.CallAdminAction
import com.fariin.app.call.CallPerson
import com.fariin.app.call.GroupCall
import com.fariin.app.call.GroupCallAdmin
import com.fariin.app.call.GroupCallUi
import com.fariin.app.call.GroupMember
import com.fariin.app.ui.components.Avatar

private val Danger = Color(0xFFFF453A)
private val DialogFill = Color(0xFF2C2C2E)

/** A host action waiting for its confirmation (iOS removeTarget / blockTarget / confirmEnd). */
sealed interface HostConfirm {
    data class Remove(val m: GroupMember) : HostConfirm
    data class Block(val m: GroupMember) : HostConfirm
    data object End : HostConfirm
}

/** The confirmations and the server's refusal, as the iOS sheet's alerts say them. */
@Composable
fun HostDialogs(g: GroupCallUi, confirm: HostConfirm?, onDone: () -> Unit) {
    when (confirm) {
        is HostConfirm.Remove -> HostAlert("Remove ${confirm.m.name} from the call?", GroupCallAdmin.removeMessage(g.linkRoom != null),
            "Remove", onDone) { GroupCall.act(CallAdminAction.REMOVE, confirm.m.id) }
        is HostConfirm.Block -> HostAlert("Block ${confirm.m.name}?", "They won't be able to join this call again.", "Block", onDone) {
            GroupCall.act(CallAdminAction.BLOCK, confirm.m.id)
        }
        HostConfirm.End -> HostAlert("End the call for everyone?", "Everyone in the call will be disconnected.", "End Call", onDone) {
            GroupCall.act(CallAdminAction.END)
        }
        null -> {}
    }
    g.adminError?.let { text ->
        GlassAlert(onDismissRequest = { GroupCall.clearAdminError() }, containerColor = DialogFill,
            title = { Text(text, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
            confirmButton = { TextButton(onClick = { GroupCall.clearAdminError() }) { Text("OK", color = Color(0xFF0A84FF)) } })
    }
}

@Composable
private fun HostAlert(title: String, message: String, action: String, onDone: () -> Unit, run: () -> Unit) {
    GlassAlert(onDismissRequest = onDone, containerColor = DialogFill,
        title = { Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
        text = { Text(message, color = Color.White.copy(alpha = 0.75f), fontSize = 15.sp) },
        confirmButton = { TextButton(onClick = { onDone(); run() }) { Text(action, color = Danger, fontWeight = FontWeight.SemiBold) } },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel", color = Color(0xFF0A84FF)) } })
}

/** The person's mic as a host sees it right now: a tap still on its way counts as done. */
fun shownBlocked(g: GroupCallUi, m: GroupMember): Boolean = g.pendingMute[m.id] ?: GroupCallAdmin.adminMuted(m)

/**
 * The actions on a person I may act on (iOS actionsMenu and the tile's long-press menu): Mute while
 * an admin has not muted them, Allow to Speak once one has, Remove, and on a link call I own
 * Remove and Block. [onPin] adds Pin / Unpin first (the tile's menu only).
 */
@Composable
fun HostMenuItems(g: GroupCallUi, m: GroupMember, canBlock: Boolean, onConfirm: (HostConfirm) -> Unit, close: () -> Unit,
                  pinned: Boolean? = null, onPin: (() -> Unit)? = null) {
    val me = g.members.firstOrNull { it.local }?.id
    if (onPin != null && pinned != null) {
        DropdownMenuItem(text = { Text(if (pinned) "Unpin" else "Pin", color = Color.White) },
            leadingIcon = { Icon(androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.fariin.app.R.drawable.ic_sf_pin), null, tint = Color.White) }, onClick = { close(); onPin() })
    }
    if (!GroupCallAdmin.mayModerate(me, g.myRole, m)) return
    if (!shownBlocked(g, m)) {
        DropdownMenuItem(text = { Text("Mute", color = Color.White) }, leadingIcon = { Icon(androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.fariin.app.R.drawable.ic_mic_off), null, tint = Color.White) },
            onClick = { close(); GroupCall.tapMic(m.id, mute = true) })
    } else {
        DropdownMenuItem(text = { Text("Allow to Speak", color = Color.White) }, leadingIcon = { Icon(androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.fariin.app.R.drawable.ic_mic), null, tint = Color.White) },
            onClick = { close(); GroupCall.tapMic(m.id, mute = false) })
    }
    DropdownMenuItem(text = { Text("Remove from call", color = Danger) }, leadingIcon = { Icon(androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.fariin.app.R.drawable.ic_sf_person_minus), null, tint = Danger) },
        onClick = { close(); onConfirm(HostConfirm.Remove(m)) })
    if (canBlock) {
        DropdownMenuItem(text = { Text("Remove and Block", color = Danger) }, leadingIcon = { Icon(androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.fariin.app.R.drawable.ic_block), null, tint = Danger) },
            onClick = { close(); onConfirm(HostConfirm.Block(m)) })
    }
}

/** What a tile's long press needs from the stage (iOS CallTileMenu). */
class TileMenu(val g: GroupCallUi, val pinnedId: String?, val canPin: Boolean, val onPin: (String) -> Unit, val onConfirm: (HostConfirm) -> Unit)

internal val LocalTileMenu = compositionLocalOf<TileMenu?> { null }

/** The tile's long-press menu offers something: Pin, or a host action. */
fun TileMenu.offers(m: GroupMember): Boolean {
    val me = g.members.firstOrNull { it.local }?.id
    return !m.local && (canPin || GroupCallAdmin.mayModerate(me, g.myRole, m))
}

fun copyLink(context: Context, url: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("Call link", url))
}

fun shareLink(context: Context, url: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
    context.startActivity(Intent.createChooser(send, "Share Link").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/**
 * Add People (iOS AddPeopleSheet): a group chat's call lists the group's members and rings the
 * people picked (`groupCallRingAgain`); a call link lists my 1:1 chats and rings them into the call
 * (`linkCallInvite`). Anyone here, rung a moment ago or gone is shown with why, and cannot be picked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupAddPeopleSheet(g: GroupCallUi, onClose: () -> Unit) {
    // A multi-person call with no group chat lists my 1:1 chats too, and adds them to its own list.
    val isLink = g.linkRoom != null || com.fariin.app.call.GroupCallWire.isAdhoc(g.cid)
    val me = g.members.firstOrNull { it.local }?.id ?: ""
    var linkPeople by remember { mutableStateOf<List<CallPerson>?>(null) }
    var joined by remember { mutableStateOf(emptySet<String>()) }
    if (isLink) LaunchedEffect(Unit) { linkPeople = GroupCall.linkCandidates() }
    else DisposableEffect(g.cid) {
        val reg = GroupCall.watchJoined { joined = it }
        onDispose { reg?.remove() }
    }
    val here = g.members.map { it.id }.toSet()
    val people = (if (isLink) linkPeople.orEmpty() else g.groupPeople).filter { it.uid != me && it.uid.isNotEmpty() }
    val notes = if (isLink) here.associateWith { "In this call" }
        else GroupCallAdmin.groupAddNotes(here, joined, g.removedHere, me, g.rangAt, System.currentTimeMillis())
    var query by remember { mutableStateOf("") }
    val picked = remember { mutableStateListOf<String>() }
    val shown = people.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
    val action = if (isLink) "Add to call" else "Ring"
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.Black, contentColor = Color.White, scrimColor = Color.Black.copy(alpha = 0.4f)) {
        Column(Modifier.fillMaxSize().padding(bottom = 24.dp)) {
            Box(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 12.dp)) {
                Box(Modifier.align(Alignment.CenterStart).size(36.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f))
                    .clickable(onClick = onClose).semantics { contentDescription = "Close" }, contentAlignment = Alignment.Center) {
                    Icon(androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.fariin.app.R.drawable.ic_sf_xmark), null, tint = Color.White, modifier = Modifier.size(18.dp))
                }
                Text("Add People", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.Center))
                val enabled = picked.isNotEmpty()
                Text(if (picked.size > 1) "$action (${picked.size})" else action, color = Color(0xFF0A84FF), fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.CenterEnd).alpha(if (enabled) 1f else 0.4f).clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = enabled) {
                            val chosen = people.filter { it.uid in picked }
                            if (isLink) GroupCall.invitePeople(chosen) else GroupCall.ringPeople(chosen)
                            onClose()
                        }.padding(horizontal = 6.dp, vertical = 4.dp))
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("Search", color = Color.White.copy(alpha = 0.5f)) },
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                    focusedContainerColor = Color(0xFF1C1C1E), unfocusedContainerColor = Color(0xFF1C1C1E),
                    focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent, cursorColor = Color.White),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            Spacer(Modifier.height(12.dp))
            when {
                isLink && linkPeople == null -> Text("Loading…", color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(horizontal = 32.dp))
                people.isEmpty() ->
                    Text(if (isLink) "No chats to add people from." else "Everyone in the group is already here.",
                        color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(horizontal = 32.dp))
                else -> Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Color(0xFF1C1C1E))) {
                    LazyColumn {
                        items(shown, key = { it.uid }) { p ->
                            val note = notes[p.uid]
                            val on = p.uid in picked
                            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                .then(if (note == null) Modifier.clickable { if (on) picked.remove(p.uid) else picked.add(p.uid) } else Modifier)
                                .padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Avatar(p.photo, 40.dp, Modifier.alpha(if (note == null) 1f else 0.5f))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(p.name, color = Color.White.copy(alpha = if (note == null) 1f else 0.5f), fontSize = 17.sp,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    note?.let { Text(it, color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp) }
                                }
                                if (note == null) Icon(if (on) androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.fariin.app.R.drawable.ic_sf_checkmark_circle_fill) else androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.fariin.app.R.drawable.ic_sf_circle),
                                    if (on) "Selected" else "Not selected",
                                    tint = if (on) Color(0xFF0A84FF) else Color.White.copy(alpha = 0.35f), modifier = Modifier.size(24.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One plain row of the people sheet (iOS plainRow): the icon in a 36 frame, the words, both white. */
@Composable
fun SheetPlainRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(52.dp).clickable(onClick = onClick).padding(horizontal = 16.dp).semantics { contentDescription = title },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Start) {
        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp)) }
        Spacer(Modifier.width(12.dp))
        Text(title, color = Color.White, fontSize = 17.sp)
    }
}
