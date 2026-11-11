package com.fariin.app.ui.people

import com.fariin.app.ui.components.GlassAlert
import com.fariin.app.R
import com.fariin.app.ui.settings.BarPage
import com.fariin.app.ui.components.underBar

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.data.ChatRestrictions
import com.fariin.app.data.ChatsNow
import com.fariin.app.data.isRestricted
import com.fariin.app.ui.settings.PageBar
import com.fariin.app.ui.settings.SettingsRow
import com.fariin.app.ui.settings.SettingsSection
import com.fariin.app.ui.settings.SwitchRow
import com.fariin.app.ui.settings.Trailing
import com.fariin.app.ui.theme.Fariin
import kotlinx.coroutines.launch

/**
 * Contact info > Restricted Chat (iOS RestrictedChatView): one switch, then what it stops. On is my
 * own switch at once. Off turns my own off; if only theirs is on I can only ask them, and while
 * their request to me is open I can answer it here.
 */
@Composable
fun RestrictedChatPage(cid: String, me: String, them: String, myName: () -> String, onBack: () -> Unit,
                       /** Non-null: drawn as a sheet rising from the bottom, as Disappearing Messages (owner 2026-11-10). */
                       sheet: com.fariin.app.ui.components.SheetMotion? = null) {
    val c = Fariin.colors
    val conv = ChatsNow.findAny(cid)
    var pending by remember { mutableStateOf<Boolean?>(null) }
    var askRequest by remember { mutableStateOf(false) }
    val mine = conv?.let { ChatRestrictions.restrictedByMe(it, me) } == true
    val theirs = conv?.let { ChatRestrictions.restrictedByThem(it, me) } == true
    val isOn = pending ?: (conv?.isRestricted == true)
    LaunchedEffect(conv?.isRestricted, mine) { pending = null }
    val requestBy = conv?.let { ChatRestrictions.pendingRequestBy(it) }
    val requestForMe = requestBy != null && requestBy != me && mine
    val myRequestOpen = requestBy == me
    fun flip(on: Boolean) {
        when {
            on -> { pending = true; ChatRestrictions.scope.launch { ChatRestrictions.setMine(cid, me, myName(), true) } }
            mine -> { pending = if (theirs) null else false; ChatRestrictions.scope.launch { ChatRestrictions.setMine(cid, me, myName(), false) } }
            theirs -> askRequest = true
        }
    }
    val footer = when {
        mine && theirs -> "You and $them both turned this on. Turning yours off keeps it on until $them turns theirs off."
        mine -> "You turned this on. Only you can turn it off. It applies to both of you."
        theirs -> "$them turned this on. You can ask $them to turn it off."
        else -> "When either of you turns this on, it applies to both of you. Only the person who turned it on can turn it off."
    }
    val body: @Composable () -> Unit = {
            SettingsSection(footer = footer) {
                SwitchRow("Restricted Chat", isOn, last = false) { flip(it) }
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("If this is on, people:", fontSize = 15.sp, color = c.secondary)
                    listOf(
                        ImageVector.vectorResource(R.drawable.ic_sf_camera_slash) to "Can't take screenshots or record the screen in this chat",
                        ImageVector.vectorResource(R.drawable.ic_menu_forward) to "Can't forward messages to other chats",
                        ImageVector.vectorResource(R.drawable.ic_sf_doc_on_doc) to "Can't copy text from this chat",
                        ImageVector.vectorResource(R.drawable.ic_save_down) to "Can't save or share photos and videos from this chat",
                        ImageVector.vectorResource(com.fariin.app.R.drawable.ic_album) to "Can't save media from this chat to their gallery automatically",
                    ).forEach { (icon, text) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(icon, null, tint = c.label, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(14.dp))
                            Text(text, fontSize = 15.sp, color = c.label)
                        }
                    }
                }
            }
            if (requestForMe) SettingsSection(header = "$them asked you to turn this off") {
                SettingsRow("Turn Off", trailing = Trailing.NONE, titleColor = c.accent) {
                    ChatRestrictions.scope.launch { ChatRestrictions.answer(cid, me, myName(), accept = true) }
                }
                SettingsRow("Keep On", trailing = Trailing.NONE, last = true) {
                    ChatRestrictions.scope.launch { ChatRestrictions.answer(cid, me, myName(), accept = false) }
                }
            } else if (myRequestOpen) SettingsSection {
                Text("Waiting for $them to answer your request.", fontSize = 15.sp, color = c.secondary, modifier = Modifier.padding(16.dp))
            }
    }
    if (sheet != null) com.fariin.app.ui.components.SheetPage(sheet, "Restricted Chat") { body() }
    else {
        val barScroll = rememberScrollState()
        BarPage("Restricted Chat", onBack = onBack, under = underBar(barScroll), modifier = Modifier.background(c.bgSecondary)) { top ->
            Column(Modifier.fillMaxSize().verticalScroll(barScroll).padding(top = top).padding(top = 8.dp)) { body() }
        }
    }
    if (askRequest) GlassAlert(onDismissRequest = { askRequest = false }, title = { Text("Ask $them to turn it off?") },
        text = { Text("$them turned on Restricted Chat, so only they can turn it off. They'll see your request in the chat.") },
        confirmButton = { TextButton({ askRequest = false; ChatRestrictions.scope.launch { ChatRestrictions.requestOff(cid, me, myName()) } }) {
            Text("Send Request", color = c.accent) } },
        dismissButton = { TextButton({ askRequest = false }) { Text("Cancel", color = c.accent) } }, containerColor = c.card)
}
