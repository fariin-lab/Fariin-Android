package com.fariin.app.ui.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.data.AuthRepository
import com.fariin.app.ui.components.Avatar
import com.fariin.app.ui.components.SheetShape
import com.fariin.app.ui.components.sheetFill
import com.fariin.app.ui.theme.Fariin
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/** A channel admin invite link, https://fariin.com/a/<code> (iOS ChannelAdminInvites.link). */
object AdminInviteLink {
    private val re = Regex("^https?://(www\\.)?fariin\\.com/a/([A-Za-z0-9_-]{4,64})/?$", RegexOption.IGNORE_CASE)
    fun code(url: String): String? = re.find(url.trim())?.groupValues?.get(2)
}

private data class InviteInfo(val cid: String, val title: String, val photo: String?, val expiresAt: Long,
                              val state: String, val forMe: Boolean, val fromName: String)

/**
 * The page an admin invite opens (iOS ChannelAdminInviteView): the channel, "Channel admin invite",
 * who invited me and when it runs out, what an admin does, and Accept / Decline. Answered or no
 * longer open, it says so instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminInviteSheet(code: String, onClose: () -> Unit, onOpenChannel: (String) -> Unit) {
    val c = Fariin.colors
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<InviteInfo?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val fn = remember { FirebaseFunctions.getInstance(AuthRepository.FUNCTIONS_REGION) }
    LaunchedEffect(code) {
        runCatching { fn.getHttpsCallable("adminInviteInfo").call(mapOf("code" to code)).await().getData() as? Map<*, *> }
            .onSuccess { d ->
                if (d == null) { problem = "This invite can't be opened."; return@onSuccess }
                info = InviteInfo(d["cid"] as? String ?: "", d["title"] as? String ?: "Channel",
                    (d["photoUrl"] as? String)?.takeIf { it.isNotEmpty() }, (d["expiresAt"] as? Number)?.toLong() ?: 0,
                    d["state"] as? String ?: "open", d["forMe"] == true, d["fromName"] as? String ?: "Someone")
            }
            .onFailure { problem = it.message ?: "This invite can't be opened." }
    }
    fun answer(accept: Boolean) {
        busy = true
        scope.launch {
            val r = runCatching { fn.getHttpsCallable("answerAdminInvite").call(mapOf("code" to code, "accept" to accept)).await().getData() as? Map<*, *> }
            busy = false
            r.onSuccess { d ->
                val cid = d?.get("cid") as? String
                onClose()
                if (accept && cid != null) onOpenChannel(cid)
            }.onFailure { problem = it.message ?: "Couldn't answer. Try again." }
        }
    }
    ModalBottomSheet(onDismissRequest = onClose, containerColor = sheetFill, shape = SheetShape) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val i = info
            when {
                problem != null -> Text(problem!!, fontSize = 16.sp, color = c.secondary, textAlign = TextAlign.Center)
                i == null -> com.fariin.app.ui.components.FariinSpinner(Modifier.padding(32.dp), size = 30.dp, stroke = 3.dp)
                else -> {
                    Avatar(i.photo, 96.dp, squircle = true, kind = com.fariin.app.ui.components.AvatarKind.CHANNEL)
                    Text(i.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = c.label, textAlign = TextAlign.Center)
                    Text("Channel admin invite", fontSize = 16.sp, color = c.secondary)
                    val line = when (i.state) {
                        "accepted" -> "Accepted."
                        "declined" -> "Declined."
                        "revoked" -> "This invite was revoked."
                        "expired" -> "This invite has expired."
                        "gone" -> "This channel no longer exists."
                        else -> {
                            val from = if (i.forMe) "${i.fromName} invited you. " else ""
                            if (i.expiresAt <= 0) from else {
                                val days = maxOf(1, Math.ceil((i.expiresAt - System.currentTimeMillis()) / 86_400_000.0).toInt())
                                from + "Expires in $days day${if (days == 1) "" else "s"}."
                            }
                        }
                    }
                    if (line.isNotEmpty()) Text(line, fontSize = 13.sp, color = c.secondary, textAlign = TextAlign.Center)
                    if (i.state == "open" && i.forMe) {
                        Text("Admins post in the channel and can change its name, photo and settings. Followers never see who the admins are.",
                            fontSize = 13.sp, color = c.secondary, textAlign = TextAlign.Center)
                        Box(Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(25.dp)).background(c.accent)
                            .clickable(enabled = !busy) { answer(true) }, contentAlignment = Alignment.Center) {
                            Text("Accept", color = c.onAccent, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        }
                        TextButton(onClick = { answer(false) }, enabled = !busy) { Text("Decline", color = c.red) }
                    }
                }
            }
        }
    }
}
