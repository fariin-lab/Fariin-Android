package com.fariin.app.ui.thread

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.R
import com.fariin.app.data.CryptoHub
import com.fariin.app.data.FirestorePeople
import com.fariin.app.data.Person
import com.fariin.app.ui.theme.Fariin
import kotlinx.coroutines.launch

/**
 * What sits where the composer was in a 1:1 that is a message request (iOS ThreadView requestBar,
 * awaitingReplyBar, cannotMessageBar). Chosen in ThreadViewModel; null = the normal composer.
 */
data class RequestBar(val kind: Kind, val otherUid: String, val handle: String, val hasKey: Boolean) {
    enum class Kind {
        /** They wrote first and I have not answered: Accept or Delete. */
        INCOMING,
        /** My first message went; nothing more until they reply. */
        AWAITING,
        /** Their messages setting keeps me out (People Who Know My Chat Key). */
        CANNOT,
        /** CANNOT, while the server is still asked whether they have a key: nothing drawn yet. */
        ASKING,
    }
}

@Composable
internal fun RequestBarView(bar: RequestBar, me: String, title: String, onBack: () -> Unit, onKeyAccepted: () -> Unit) {
    val c = Fariin.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var keySheet by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val api = remember(me) { FirestorePeople(me, CryptoHub.get(ctx.applicationContext)) }
    if (keySheet) com.fariin.app.ui.people.ChatKeySheet(api, Person(bar.otherUid, name = title, handle = bar.handle),
        onDismiss = { keySheet = false }, onOpened = { keySheet = false; onKeyAccepted() })
    if (bar.kind == RequestBar.Kind.ASKING) return
    HorizontalDivider(thickness = 0.5.dp, color = c.separator)
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        when (bar.kind) {
            RequestBar.Kind.INCOMING -> {
                // The words open the safety page (iOS "Stay safe").
                Text(buildAnnotatedString {
                    append("Accept message request from $title? ")
                    withStyle(SpanStyle(color = c.accent, fontWeight = FontWeight.SemiBold)) { append("Stay safe") }
                }, fontSize = 15.sp, color = c.secondary, textAlign = TextAlign.Center,
                    modifier = Modifier.clickable { openWebLink(ctx, android.net.Uri.parse("https://fariin.com/safety#help")) })
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Pill("Delete", c.label.copy(alpha = 0.07f), c.red, Modifier.weight(1f)) {
                        if (busy) return@Pill
                        busy = true
                        val cid = com.fariin.app.crypto.Wire.directCid(me, bar.otherUid)
                        onBack()
                        scope.launch { api.declineRequest(cid) }
                    }
                    Pill("Accept", c.accent, c.onAccent, Modifier.weight(1f)) {
                        if (busy) return@Pill
                        busy = true
                        scope.launch {
                            if (!api.acceptRequest(com.fariin.app.crypto.Wire.directCid(me, bar.otherUid)))
                                com.fariin.app.ui.components.Snack.show("Couldn't accept. Check your connection and try again.")
                            busy = false
                        }
                    }
                }
            }
            RequestBar.Kind.AWAITING -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_sf_clock), null, tint = c.label, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Message request sent", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.label)
                }
                Text("You can send another message once $title replies.", fontSize = 13.sp, color = c.secondary,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = 3.dp))
                if (bar.hasKey) Text("Use Chat Key", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.accent,
                    modifier = Modifier.padding(top = 4.dp).clickable { keySheet = true }.padding(4.dp))
            }
            RequestBar.Kind.CANNOT -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_sf_lock_fill), null, tint = c.label, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("You can't message $title", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.label)
                }
                Text(if (bar.hasKey) "They only accept messages from people who know their Chat Key. If you know it, you can message them now."
                    else "They only accept messages from people in their chats.",
                    fontSize = 13.sp, color = c.secondary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 3.dp))
                if (bar.hasKey) {
                    Spacer(Modifier.height(10.dp))
                    Pill("Use Chat Key", c.accent, c.onAccent, Modifier.fillMaxWidth()) { keySheet = true }
                }
            }
            RequestBar.Kind.ASKING -> {}
        }
    }
}

@Composable
private fun Pill(text: String, fill: Color, ink: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.height(46.dp).clip(CircleShape).background(fill).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = ink)
    }
}

/**
 * Which request bar a 1:1 shows, in iOS ThreadView's order: their unanswered request (Accept or
 * Delete), my request waiting for their reply, then their Chat Key setting keeping me out. A Chat
 * Key just accepted brings the composer back at once. [policyRev] only re-reads the admin switches.
 */
internal fun requestBarFor(
    conv: com.fariin.app.data.Conversation?, me: String, otherUid: String, otherPrivacy: Map<String, String>,
    messages: List<com.fariin.app.data.Message>, closed: Boolean, keyAccepted: Boolean, hasKey: Boolean, keyResolved: Boolean,
    @Suppress("UNUSED_PARAMETER") policyRev: Int,
): RequestBar? {
    if (conv == null || conv.isGroup || otherUid.isEmpty() || closed) return null
    val handle = ""   // the key sheet says the name when there is no @handle
    val stance = com.fariin.app.data.Requests.stance(conv, me)
    fun bar(k: RequestBar.Kind) = RequestBar(k, otherUid, handle, hasKey)
    if (stance == com.fariin.app.data.Requests.Stance.INCOMING) return bar(RequestBar.Kind.INCOMING)
    if (keyAccepted) return null
    if (stance == com.fariin.app.data.Requests.Stance.AWAITING_REPLY) return bar(RequestBar.Kind.AWAITING)
    // iOS cannotMessageThem: their "messages" audience is closed, the admin switch enforces the key,
    // and there is no chat between us yet (an accepted request, or a message, counts).
    val policy = com.fariin.app.data.MsgPolicy
    if (!policy.chatKeyRequired(otherUid)) return null
    if ((com.fariin.app.data.Audience.of(otherPrivacy["messages"]) ?: com.fariin.app.data.Audience.EVERYONE) ==
        com.fariin.app.data.Audience.EVERYONE) return null
    val history = if (conv.startedBy.isNotEmpty()) conv.accepted
        else conv.lastMessageCipher.isNotEmpty() || messages.any { !it.isSystem }
    if (history) return null
    return bar(if (keyResolved || !policy.chatKeyVisible) RequestBar.Kind.CANNOT else RequestBar.Kind.ASKING)
}
