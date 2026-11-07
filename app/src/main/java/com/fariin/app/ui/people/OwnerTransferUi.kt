package com.fariin.app.ui.people

import com.fariin.app.ui.settings.BarPage
import com.fariin.app.ui.components.underBar

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fariin.app.data.JoinRequests
import com.fariin.app.data.OwnerTransfer
import com.fariin.app.ui.channels.PersonLine
import com.fariin.app.ui.components.Snack
import com.fariin.app.ui.settings.PageBar
import com.fariin.app.ui.settings.SettingsSection
import com.fariin.app.ui.theme.Fariin
import kotlinx.coroutines.launch

/*
 * Transfer Ownership, Choose New Owner and Join Requests for groups and channels (Kulan-iOS
 * GroupMemberSheet startTransfer, TransferPasswordAlert, ChooseNewOwnerSheet, GroupRequestsView).
 * The Android way: Material dialogs, a pushed page with the up arrow, snackbars. iOS words.
 */

/**
 * The whole transfer, once a person is picked: the server's check, then (when it can happen) the
 * password dialog, then the hand-over. [onDone] after it is done; [onEnd] whenever the flow closes
 * without it. With [inlineRefusal] the check's refusal goes there instead of a dialog (the Choose
 * New Owner page says it on the page, as iOS). [skipCheck]: the check already passed.
 */
@Composable
internal fun TransferOwnership(cid: String, isChannel: Boolean, heir: String, name: String, leaving: Boolean = false,
                               inlineRefusal: ((String) -> Unit)? = null, onDone: () -> Unit, onEnd: () -> Unit) {
    val c = Fariin.colors
    val scope = rememberCoroutineScope()
    var step by remember(heir) { mutableStateOf("check") }   // check, password, busy, nopassword, refused, failed
    var message by remember(heir) { mutableStateOf("") }
    var password by remember(heir) { mutableStateOf("") }
    LaunchedEffect(heir) {
        try {
            OwnerTransfer.check(cid, heir)
            step = "password"
        } catch (r: OwnerTransfer.Refusal) {
            if (inlineRefusal != null) { inlineRefusal(if (r.needsPassword) OwnerTransfer.CREATE_FIRST else r.message ?: OwnerTransfer.OFFLINE); onEnd() }
            else if (r.needsPassword) step = "nopassword"
            else { message = r.message ?: OwnerTransfer.OFFLINE; step = "refused" }
        }
    }
    fun confirm() {
        val typed = password
        password = ""
        step = "busy"
        scope.launch {
            OwnerTransfer.reauthPassword(typed)?.let { words -> message = words; step = "failed"; return@launch }
            try {
                OwnerTransfer.transfer(cid, heir)
                Snack.show(OwnerTransfer.doneToast(name))
                onDone()
            } catch (r: OwnerTransfer.Refusal) {
                message = r.message ?: OwnerTransfer.OFFLINE; step = "failed"
            }
        }
    }
    when (step) {
        "check", "busy" -> if (inlineRefusal == null || step == "busy") Dialog(onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
            Box(Modifier.size(88.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp)).background(c.card),
                contentAlignment = Alignment.Center) {
                com.fariin.app.ui.components.FariinSpinner(size = 36.dp, stroke = 4.dp)
            }
        }
        "password" -> AlertDialog(onDismissRequest = { password = ""; onEnd() }, containerColor = c.card,
            title = { Text(OwnerTransfer.PASSWORD_TITLE) },
            text = {
                Column {
                    Text(OwnerTransfer.passwordMessage(isChannel, name, leaving))
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(password, { password = it }, singleLine = true, placeholder = { Text("Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.accent, cursorColor = c.accent),
                        modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = { confirm() }, enabled = password.isNotEmpty()) {
                Text("Confirm", color = if (password.isNotEmpty()) c.accent else c.secondary) } },
            dismissButton = { TextButton({ password = ""; onEnd() }) { Text("Cancel", color = c.accent) } })
        // No Create Password page opens from here on Android yet, so iOS's Settings wording with OK only.
        "nopassword" -> AlertDialog(onDismissRequest = onEnd, containerColor = c.card, title = { Text(OwnerTransfer.NO_PASSWORD_TITLE) },
            text = { Text(OwnerTransfer.noPasswordMessage(isChannel, hasAddress = false)) },
            confirmButton = { TextButton(onEnd) { Text("OK", color = c.accent) } })
        "refused" -> AlertDialog(onDismissRequest = onEnd, containerColor = c.card, title = { Text(OwnerTransfer.CANT_TITLE) },
            text = { Text(message) }, confirmButton = { TextButton(onEnd) { Text("OK", color = c.accent) } })
        "failed" -> AlertDialog(onDismissRequest = onEnd, containerColor = c.card, title = { Text(OwnerTransfer.FAILED_TITLE) },
            text = { Text(message) },
            confirmButton = { TextButton({ step = "password" }) { Text("Try Again", color = c.accent) } },
            dismissButton = { TextButton(onEnd) { Text("Cancel", color = c.accent) } })
    }
}

/**
 * The owner's Choose New Owner from the Leave dialog (iOS ChooseNewOwnerSheet): pick a member, the
 * server checks the transfer can happen, the password dialog hands the group over, then
 * [onTransferred] leaves.
 */
@Composable
internal fun ChooseNewOwnerPage(cid: String, me: String, conv: Map<String, Any?>, onBack: () -> Unit, onTransferred: () -> Unit) {
    val c = Fariin.colors
    val isChannel = conv["channel"] == true
    val team = com.fariin.app.data.GroupAdmins.Team.from(conv)
    var query by remember { mutableStateOf("") }
    var failure by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf<Pair<String, String>?>(null) }
    val members = team.users.filter { it != me }.map { it to (team.names[it]?.ifEmpty { null } ?: "Member") }
    val q = query.trim()
    val shown = if (q.isEmpty()) members else members.filter { it.second.contains(q, ignoreCase = true) }
    picked?.let { (uid, name) ->
        TransferOwnership(cid, isChannel, uid, name, leaving = true, inlineRefusal = { failure = it },
            onDone = { picked = null; onTransferred() }, onEnd = { picked = null })
    }
    BarPage(OwnerTransfer.CHOOSE_TITLE, onBack, modifier = Modifier.background(c.bgSecondary)) { top ->
        androidx.compose.foundation.layout.Spacer(Modifier.height(top))
        SearchField(query, "Search") { query = it }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            failure?.let { f -> SettingsSection { Text(f, fontSize = 16.sp, color = c.red, modifier = Modifier.fillMaxWidth().padding(16.dp)) } }
            SettingsSection(footer = OwnerTransfer.chooseFooter(isChannel)) {
                shown.forEach { (uid, name) ->
                    PersonLine(team.photos[uid]?.ifEmpty { null }, name, onClick = { if (picked == null) { failure = null; picked = uid to name } }) {
                        if (picked?.first == uid) com.fariin.app.ui.components.FariinSpinner(size = 20.dp)
                    }
                }
            }
        }
    }
}

/** The live join requests, for whoever may answer them (owner, admin with Add members). */
@Composable
internal fun rememberJoinRequests(cid: String, allowed: Boolean): List<JoinRequests.Request> {
    var list by remember(cid) { mutableStateOf<List<JoinRequests.Request>>(emptyList()) }
    DisposableEffect(cid, allowed) {
        val reg = if (allowed) JoinRequests.listen(cid) { list = it } else null
        onDispose { reg?.remove() }
    }
    return if (allowed) list else emptyList()
}

/**
 * Join Requests (iOS GroupRequestsView): who is waiting, Decline / Approve. An answered row goes at
 * once and the server catches up; a refusal brings it back and says why.
 */
@Composable
internal fun JoinRequestsPage(cid: String, onBack: () -> Unit) {
    val c = Fariin.colors
    val scope = rememberCoroutineScope()
    val doc = liveGroupDoc(cid)
    val isChannel = doc["channel"] == true
    val all = rememberJoinRequests(cid, allowed = true)
    var answered by remember(cid) { mutableStateOf(setOf<String>()) }
    var busy by remember(cid) { mutableStateOf(setOf<String>()) }
    var problem by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(all) { answered = JoinRequests.keepAnswered(all, answered) }
    val shown = JoinRequests.shown(all, answered)
    fun answer(uid: String, approve: Boolean) {
        if (uid in busy) return
        busy = busy + uid
        answered = answered + uid
        scope.launch {
            try { if (approve) JoinRequests.approve(cid, uid) else JoinRequests.deny(cid, uid) }
            catch (e: Exception) { answered = answered - uid; problem = JoinRequests.errorText(e) }
            busy = busy - uid
        }
    }
    problem?.let { p -> AlertDialog(onDismissRequest = { problem = null }, containerColor = c.card, title = { Text("Could not do that") },
        text = { Text(p) }, confirmButton = { TextButton({ problem = null }) { Text("OK", color = c.accent) } }) }
    val barScroll = rememberScrollState()
    BarPage(JoinRequests.title(isChannel), onBack, under = underBar(barScroll), modifier = Modifier.background(c.bgSecondary)) { top ->
        Column(Modifier.fillMaxSize().verticalScroll(barScroll).padding(top = top).navigationBarsPadding()) {
            if (shown.isEmpty()) {
                SettingsSection(footer = JoinRequests.emptyFooter(isChannel, doc["title"] as? String ?: "")) {
                    Text(JoinRequests.empty(isChannel), fontSize = 17.sp, color = c.secondary,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp))
                }
            } else {
                SettingsSection(header = JoinRequests.header(isChannel)) {
                    shown.forEach { r ->
                        PersonLine(r.photo, r.name) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Pill("Decline", filled = false, enabled = r.uid !in busy) { answer(r.uid, approve = false) }
                                Pill("Approve", filled = true, enabled = r.uid !in busy) { answer(r.uid, approve = true) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Pill(text: String, filled: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val c = Fariin.colors
    Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (filled) c.onAccent else c.label,
        modifier = Modifier.clip(CircleShape).background(if (filled) c.accent else c.fieldFill)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp))
}
