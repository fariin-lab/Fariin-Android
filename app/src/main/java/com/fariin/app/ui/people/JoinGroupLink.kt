package com.fariin.app.ui.people

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.data.AuthRepository
import com.fariin.app.ui.components.Avatar
import com.fariin.app.ui.components.SheetShape
import com.fariin.app.ui.components.sheetFill
import com.fariin.app.ui.theme.Fariin
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/** A group or channel invite link, https://fariin.com/g/<code> (iOS KulanApp.groupLink). */
object GroupInviteLink {
    private val re = Regex("^https?://(www\\.)?fariin\\.com/g/([A-Za-z0-9_-]{4,64})/?$", RegexOption.IGNORE_CASE)
    fun code(url: String): String? = re.find(url.trim())?.groupValues?.get(2)
}

private class Face(val uid: String, val name: String, val photo: String?)

private class LinkPreview(d: Map<*, *>) {
    val cid = (d["cid"] as? String).orEmpty()
    val title = (d["title"] as? String)?.trim()?.ifEmpty { null } ?: "Group"
    val photo = (d["photoUrl"] as? String)?.ifEmpty { null }
    val count = (d["memberCount"] as? Number)?.toInt() ?: 0
    val isMember = d["isMember"] == true
    val isChannel = d["channel"] == true
    val needsApproval = d["needsApproval"] == true
    val requested = d["requested"] == true
    val description = (d["description"] as? String).orEmpty().trim()
    private fun faces(raw: Any?) = (raw as? List<*>).orEmpty().mapNotNull { m ->
        val x = m as? Map<*, *> ?: return@mapNotNull null
        val uid = x["uid"] as? String ?: return@mapNotNull null
        Face(uid, (x["name"] as? String).orEmpty(), (x["photoUrl"] as? String)?.ifEmpty { null })
    }
    val mutuals = faces(d["mutuals"]).filter { it.name.isNotEmpty() }
    val mutualCount = (d["mutualCount"] as? Number)?.toInt() ?: mutuals.size
    val faces = faces(d["faces"]).ifEmpty { mutuals }
    val membersLine = if (isChannel) "$count ${if (count == 1) "follower" else "followers"}" else "$count ${if (count == 1) "member" else "members"}"
}

/**
 * What a group or channel invite link opens (iOS JoinGroupSheet): its photo, name and size, the
 * description, people I know who are in it, and Join Group / Request to Join (Follow Channel /
 * Request to Follow for a channel). Already in it: Open. The server answers everything
 * (`groupLinkPreview`, `joinGroupViaInvite`); the member list never comes to the phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JoinGroupSheet(code: String, onClose: () -> Unit, onOpenChat: (String) -> Unit, onOpenChannel: (String) -> Unit) {
    val c = Fariin.colors
    val scope = rememberCoroutineScope()
    val fn = remember { FirebaseFunctions.getInstance(AuthRepository.FUNCTIONS_REGION) }
    var preview by remember { mutableStateOf<LinkPreview?>(null) }
    var refused by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    var joining by remember { mutableStateOf(false) }
    var requestSent by remember { mutableStateOf(false) }
    var joinError by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    fun told(e: Throwable, fallback: String): String =
        if (e is FirebaseFunctionsException && e.code in setOf(FirebaseFunctionsException.Code.INVALID_ARGUMENT,
                FirebaseFunctionsException.Code.PERMISSION_DENIED, FirebaseFunctionsException.Code.NOT_FOUND,
                FirebaseFunctionsException.Code.FAILED_PRECONDITION, FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED))
            e.message ?: fallback else fallback
    LaunchedEffect(code, attempt) {
        runCatching { fn.getHttpsCallable("groupLinkPreview").call(mapOf("code" to code)).await().getData() as? Map<*, *> }
            .onSuccess { preview = LinkPreview(it ?: emptyMap<String, Any>()) }
            .onFailure { e ->
                val fe = e as? FirebaseFunctionsException
                if (fe?.code == FirebaseFunctionsException.Code.NOT_FOUND || fe?.code == FirebaseFunctionsException.Code.PERMISSION_DENIED)
                    refused = fe.message ?: "This link is no longer valid."
                else failed = "Couldn't connect. Check your connection and try again."
            }
    }
    fun join(p: LinkPreview) {
        if (p.isMember && p.cid.isNotEmpty()) { onClose(); if (p.isChannel) onOpenChannel(p.cid) else onOpenChat(p.cid); return }
        joining = true; joinError = null
        scope.launch {
            runCatching { fn.getHttpsCallable("joinGroupViaInvite").call(mapOf("code" to code)).await().getData() as? Map<*, *> }
                .onSuccess { d ->
                    val status = d?.get("status") as? String ?: ""
                    val cid = d?.get("cid") as? String ?: ""
                    when {
                        status == "requested" -> requestSent = true
                        cid.isNotEmpty() -> { onClose(); if (d?.get("channel") == true || p.isChannel) onOpenChannel(cid) else onOpenChat(cid) }
                        else -> joinError = "Couldn't join. Try again."
                    }
                }
                .onFailure { joinError = told(it, "Couldn't join. Check your connection and try again.") }
            joining = false
        }
    }
    ModalBottomSheet(onDismissRequest = onClose, containerColor = sheetFill, shape = SheetShape) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val p = preview
            when {
                p != null -> {
                    Avatar(p.photo, 100.dp, squircle = p.isChannel)
                    Spacer(Modifier.height(14.dp))
                    Text(p.title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = c.label, textAlign = TextAlign.Center, maxLines = 2)
                    Text(p.membersLine, fontSize = 15.sp, color = c.secondary, modifier = Modifier.padding(top = 4.dp))
                    if (p.description.isNotEmpty()) Text(p.description, fontSize = 15.sp, color = c.label, textAlign = TextAlign.Center,
                        maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
                    if (!p.isChannel && p.mutuals.isNotEmpty()) {
                        HorizontalDivider(Modifier.padding(top = 20.dp), color = c.separator)
                        Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box {
                                p.faces.take(3).forEachIndexed { i, f ->
                                    Box(Modifier.offset(x = (i * 20).dp).clip(CircleShape).background(sheetFill).padding(2.dp)) { Avatar(f.photo, 28.dp) }
                                }
                            }
                            Spacer(Modifier.width((minOf(3, p.faces.size) * 20 + 14).dp))
                            val names = p.mutuals.take(2).joinToString(", ") { it.name }
                            val rest = p.mutualCount - minOf(2, p.mutuals.size)
                            Text(if (rest > 0) "$names and $rest more you know are members" else "$names ${if (p.mutualCount == 1) "is a member" else "are members"}",
                                fontSize = 14.sp, color = c.secondary, modifier = Modifier.weight(1f, fill = false))
                        }
                    }
                    val note = joinError ?: if (requestSent || p.requested) "An admin will review your request." else null
                    note?.let { Text(it, fontSize = 14.sp, color = c.secondary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp)) }
                    val sent = requestSent || p.requested
                    val title = when {
                        p.isMember -> if (p.isChannel) "Open Channel" else "Open Group"
                        sent -> "Request Sent"
                        joining -> if (p.needsApproval) "Sending…" else if (p.isChannel) "Following…" else "Joining…"
                        p.isChannel -> if (p.needsApproval) "Request to Follow" else "Follow Channel"
                        else -> if (p.needsApproval) "Request to Join" else "Join Group"
                    }
                    Spacer(Modifier.height(24.dp))
                    CapsuleButton(title, enabled = !joining && !sent) { join(p) }
                }
                refused != null -> {
                    Text("Can't join with this link", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = c.label)
                    Text(refused!!, fontSize = 15.sp, color = c.secondary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp, bottom = 12.dp))
                }
                failed != null -> {
                    Text(failed!!, fontSize = 15.sp, color = c.secondary, textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 16.dp))
                    CapsuleButton("Try Again", enabled = true) { failed = null; attempt++ }
                }
                else -> CircularProgressIndicator(Modifier.padding(32.dp))
            }
        }
    }
}

@Composable
private fun CapsuleButton(title: String, enabled: Boolean, onClick: () -> Unit) {
    val c = Fariin.colors
    Box(Modifier.fillMaxWidth().height(50.dp).clip(CircleShape).background(if (enabled) c.accent else c.accent.copy(alpha = 0.4f))
        .clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(title, color = c.onAccent, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}
