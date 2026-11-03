package com.fariin.app.ui.people

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fariin.app.crypto.SafetyNumber
import com.fariin.app.crypto.Wire
import com.fariin.app.data.ChatKey
import com.fariin.app.data.Conversation
import com.fariin.app.data.PeopleApi
import com.fariin.app.data.Person
import com.fariin.app.data.Requests
import com.fariin.app.ui.components.Avatar
import com.fariin.app.ui.components.floating
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import com.fariin.app.ui.settings.PageBar
import com.fariin.app.ui.settings.SettingsRow
import com.fariin.app.ui.settings.SettingsSection
import com.fariin.app.ui.settings.Trailing
import com.fariin.app.ui.theme.Fariin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun PersonRow(photo: String?, name: String, sub: String, onClick: (() -> Unit)?, trailing: @Composable () -> Unit = {}) {
    val c = Fariin.colors
    Row(
        Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(photo, 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 17.sp, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub.isNotEmpty()) Text(sub, fontSize = 14.sp, color = c.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

@Composable
internal fun SearchField(value: String, placeholder: String, onChange: (String) -> Unit) {
    // The floating search capsule the tab pages share (was a flat grey box).
    com.fariin.app.ui.components.FloatingSearchField(value, onChange, placeholder,
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
}

/**
 * New Chat (iOS NewChatView + ChatSearchPage): New Group on top, my chats filtered by name as I
 * type, and under them "Global search" from the server (searchUsernames; under three letters
 * the exact @username). A person who asks for a Chat Key is asked for it first.
 */
@Composable
fun NewChatScreen(api: PeopleApi, chats: List<Conversation>, onBack: () -> Unit, onOpenChat: (String) -> Unit, onNewGroup: () -> Unit) {
    val c = Fariin.colors
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<Person>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var keyFor by remember { mutableStateOf<Person?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(q) {
        found = emptyList()
        if (q.trim().removePrefix("@").isEmpty()) { searching = false; return@LaunchedEffect }
        searching = true
        delay(350)
        found = api.search(q)
        searching = false
    }
    val me = api.me
    val local = chats.filter { !it.isGroup && q.isNotBlank() && it.displayName(me).contains(q.trim().removePrefix("@"), ignoreCase = true) }
    fun start(p: Person) = scope.launch {
        val cid = Wire.directCid(me, p.uid)
        val friend = chats.any { it.id == cid && (it.startedBy.isEmpty() || it.accepted) }
        if (p.privacy["messages"] == "contacts" && !friend && api.chatKeyExists(p.uid) == true) { keyFor = p; return@launch }
        try { onOpenChat(api.openChat(p)) } catch (_: Exception) { error = "Couldn't open the chat. Check your connection and try again." }
    }
    keyFor?.let { p -> ChatKeySheet(api, p, onDismiss = { keyFor = null }) { cid -> keyFor = null; onOpenChat(cid) } }
    error?.let { e -> AlertDialog(onDismissRequest = { error = null }, text = { Text(e) },
        confirmButton = { TextButton({ error = null }) { Text("OK", color = c.blue) } }, containerColor = c.card) }

    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
        com.fariin.app.ui.components.FloatingTopBar("New Chat", onBack = onBack)
        SearchField(q, "Search by name or @username") { q = it }
        LazyColumn(Modifier.fillMaxSize()) {
            if (q.isBlank()) {
                item {
                    // New Group and the channels (iOS NewChatView: New channel, and Find Channels on the
                    // same page) share one floating rounded card, an inset hairline between them.
                    val channelNav = com.fariin.app.ui.channels.LocalChannelNav.current
                    val actions = listOf(
                        Triple("New Group", onNewGroup, Icons.Filled.Groups),
                        Triple("New Channel", channelNav.create, Icons.Filled.Campaign),
                        Triple("Find Channels", channelNav.find, Icons.Filled.Explore),
                    )
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                        .floating(RoundedCornerShape(20.dp))) {
                        actions.forEachIndexed { i, (label, open, icon) ->
                            if (i > 0) HorizontalDivider(thickness = 0.5.dp, color = c.separator, modifier = Modifier.padding(start = 64.dp))
                            Row(Modifier.fillMaxWidth().clickable(onClick = open).padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(40.dp).clip(CircleShape).background(c.blue.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                                    Icon(icon, null, tint = c.blue)
                                }
                                Spacer(Modifier.width(12.dp))
                                Text(label, fontSize = 17.sp, color = c.blue)
                            }
                        }
                    }
                    Text("Find people by their @username.", fontSize = 13.sp, color = c.secondary,
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp))
                }
            }
            if (local.isNotEmpty()) item { Header("Chats") }
            items(local, key = { "l" + it.id }) { conv ->
                PersonRow(conv.displayPhoto(me), conv.displayName(me), "", onClick = { onOpenChat(conv.id) })
            }
            if (q.isNotBlank()) item { Header("Global search") }
            if (searching) item {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = c.secondary, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                }
            }
            items(found, key = { "g" + it.uid }) { p ->
                PersonRow(p.photoUrl, p.shownName, if (p.handle.isNotEmpty()) "@${p.handle}" else "", onClick = { start(p) })
            }
            if (!searching && q.isNotBlank() && found.isEmpty()) item {
                Text("No one found with that username.", fontSize = 15.sp, color = c.secondary, modifier = Modifier.padding(16.dp))
            }
        }
    }
}

@Composable
internal fun Header(text: String) {
    Text(text.uppercase(), fontSize = 13.sp, color = Fariin.colors.secondary, modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp))
}

/**
 * "Enter Chat Key" (iOS ChatPinEntrySheet): the digit boxes, a keypad with Paste, and Enter.
 * The key goes to `verifyChatPin`; the chat it opens comes back.
 */
@Composable
fun ChatKeySheet(api: PeopleApi, p: Person, onDismiss: () -> Unit, onOpened: (String) -> Unit) {
    val c = Fariin.colors
    val scope = rememberCoroutineScope()
    val clip = LocalClipboardManager.current
    var pin by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Icon(Icons.Filled.Close, "Close", tint = c.secondary, modifier = Modifier.size(28.dp).clickable(onClick = onDismiss))
            }
            Spacer(Modifier.height(12.dp))
            Text("Enter Chat Key", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = c.label)
            Spacer(Modifier.height(8.dp))
            Text("Enter ${if (p.handle.isNotEmpty()) "@" + p.handle else p.shownName}’s Chat Key to contact them.",
                fontSize = 16.sp, color = c.secondary, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (i in 0 until ChatKey.MAX) {
                    Box(Modifier.size(width = 40.dp, height = 50.dp).clip(RoundedCornerShape(10.dp)).background(c.card),
                        contentAlignment = Alignment.Center) {
                        Text(pin.getOrNull(i)?.toString() ?: "", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = c.label)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(failure ?: "${ChatKey.MIN} to ${ChatKey.MAX} digits", fontSize = 14.sp, color = if (failure != null) c.red else c.secondary)
            Spacer(Modifier.weight(1f))
            val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "Paste", "0", "del")
            keys.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    row.forEach { k ->
                        Box(Modifier.size(width = 96.dp, height = 58.dp).clip(RoundedCornerShape(12.dp))
                            .clickable(enabled = !busy) {
                                failure = null
                                pin = when (k) {
                                    "del" -> pin.dropLast(1)
                                    "Paste" -> ChatKey.fromPaste(clip.getText()?.text ?: "").ifEmpty { pin }
                                    else -> if (pin.length < ChatKey.MAX) pin + k else pin
                                }
                            }, contentAlignment = Alignment.Center) {
                            when (k) {
                                "del" -> Icon(Icons.AutoMirrored.Filled.Backspace, "Delete", tint = c.label)
                                "Paste" -> Text("Paste", fontSize = 17.sp, color = c.blue)
                                else -> Text(k, fontSize = 28.sp, color = c.label)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            val enabled = ChatKey.isValid(pin) && !busy
            Box(Modifier.fillMaxWidth().height(52.dp).clip(CircleShape).background(if (enabled) c.blue else c.blue.copy(alpha = 0.35f))
                .clickable(enabled = enabled) {
                    busy = true
                    scope.launch {
                        api.verifyChatKey(p.uid, pin).fold(onSuccess = onOpened, onFailure = { failure = it.message; pin = "" })
                        busy = false
                    }
                }, contentAlignment = Alignment.Center) {
                if (busy) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                else Text("Enter", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            }
        }
    }
}

/**
 * Message Requests (iOS MessageRequestsView): first messages from people I have not chatted with.
 * Each row: Accept, Delete (they can't send another request for 7 days; not a block), Block.
 */
@Composable
fun MessageRequestsScreen(api: PeopleApi, requests: List<Conversation>, onBack: () -> Unit, onOpenChat: (String) -> Unit) {
    val c = Fariin.colors
    val scope = rememberCoroutineScope()
    val me = api.me
    var pendingDelete by remember { mutableStateOf<Conversation?>(null) }
    var failed by remember { mutableStateOf(false) }
    var gone by remember { mutableStateOf(setOf<String>()) }
    pendingDelete?.let { conv ->
        AlertDialog(onDismissRequest = { pendingDelete = null }, title = { Text("Delete this request?") },
            text = { Text("The conversation is deleted and they can’t send you another request for ${Requests.DECLINE_COOLDOWN_DAYS} days. This does not block them.") },
            confirmButton = { TextButton({
                pendingDelete = null
                scope.launch { if (api.declineRequest(conv.id)) gone = gone + conv.id else failed = true }
            }) { Text("Delete", color = c.red) } },
            dismissButton = { TextButton({ pendingDelete = null }) { Text("Cancel", color = c.blue) } }, containerColor = c.card)
    }
    if (failed) AlertDialog(onDismissRequest = { failed = false }, title = { Text("Couldn't do that") },
        text = { Text("Check your connection and try again.") },
        confirmButton = { TextButton({ failed = false }) { Text("OK", color = c.blue) } }, containerColor = c.card)
    val list = requests.filter { it.id !in gone }
    Column(Modifier.fillMaxSize().background(c.bg)) {
        PageBar("Message Requests", onBack = onBack)
        if (list.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("No message requests", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = c.label)
                Spacer(Modifier.height(6.dp))
                Text("Messages from people you haven't chatted with yet will appear here.", fontSize = 15.sp, color = c.secondary, textAlign = TextAlign.Center)
            }
            return@Column
        }
        Text("Messages from people you haven’t chatted with before arrive here as requests. To reply, accept the request.",
            fontSize = 13.sp, color = c.secondary, modifier = Modifier.padding(16.dp))
        LazyColumn {
            items(list, key = { it.id }) { conv ->
                val other = conv.otherUid(me)
                Column {
                    PersonRow(conv.displayPhoto(me), conv.displayName(me), "Wants to message you", onClick = { onOpenChat(conv.id) })
                    Row(Modifier.fillMaxWidth().padding(start = 72.dp, end = 16.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("Accept", c.blue, Color.White) {
                            scope.launch { if (api.acceptRequest(conv.id)) { gone = gone + conv.id; onOpenChat(conv.id) } else failed = true }
                        }
                        Pill("Delete", c.fieldFill, c.red) { pendingDelete = conv }
                        Pill("Block", c.fieldFill, c.red) {
                            scope.launch { if (api.setBlocked(other, true)) gone = gone + conv.id else failed = true }
                        }
                    }
                    HorizontalDivider(thickness = 0.5.dp, color = c.separator, modifier = Modifier.padding(start = 72.dp))
                }
            }
        }
    }
}

@Composable
internal fun Pill(text: String, bg: Color, ink: Color, onClick: () -> Unit) {
    Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = ink,
        modifier = Modifier.clip(CircleShape).background(bg).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 7.dp))
}

/**
 * A person's page (iOS ContactInfoView): photo, name, @username and bio, the Message / Mute
 * buttons, then Verify Encryption (the safety number), the All Media card, Block, Report.
 */
@Composable
fun ContactInfoScreen(api: PeopleApi, uid: String, conv: Conversation?, onBack: () -> Unit, onOpenChat: (String) -> Unit) {
    val c = Fariin.colors
    val scope = rememberCoroutineScope()
    val me = api.me
    var person by remember { mutableStateOf<Person?>(null) }
    var blocked by remember { mutableStateOf(false) }
    var muted by remember(conv) { mutableStateOf(conv?.isMuted(me, System.currentTimeMillis().toDouble()) == true) }
    var safety by remember { mutableStateOf<String?>(null) }
    var showSafety by remember { mutableStateOf(false) }
    var ask by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(uid) { person = api.profile(uid); blocked = api.isBlocked(uid) }
    // A name I saved for them wins (iOS ContactNames); Edit opens Set nickname.
    val name = com.fariin.app.data.ContactNames.name(uid) ?: person?.shownName ?: conv?.names?.get(uid) ?: ""
    val cid = conv?.id ?: Wire.directCid(me, uid)
    // iOS: Search only on the page opened from this person's chat; it goes back there and opens it.
    val fromChat = androidx.compose.runtime.saveable.rememberSaveable { com.fariin.app.ui.thread.InChatSearchLink.takeOpenedFrom(cid) }
    var nickname by remember { mutableStateOf(false) }
    if (nickname) { SetNicknamePage(uid, person?.photoUrl ?: conv?.photos?.get(uid), onBack = { nickname = false }); return }
    // The chat's timer, live (either person may change it; iOS ContactInfoView).
    val live = if (api is com.fariin.app.data.FirestorePeople) liveGroupDoc(cid) else emptyMap()
    val timer = (live["disappearSeconds"] as? Number)?.toInt() ?: conv?.disappearSeconds ?: 0
    var timerOpen by remember { mutableStateOf(false) }
    if (DisappearingEditor(api, cid, isGroup = false, current = timer, open = timerOpen, onClose = { timerOpen = false })) return
    val shared = rememberSharedMedia(cid, me)
    var allMedia by remember { mutableStateOf(false) }
    if (allMedia) { SharedMediaScreen(cid, shared, onBack = { allMedia = false }); return }
    if (showSafety) AlertDialog(onDismissRequest = { showSafety = false }, title = { Text("Verify Encryption") },
        text = {
            Column {
                Text("Compare these numbers with $name, in person or on a call. If they match, your chat is end-to-end encrypted with the right person.",
                    fontSize = 15.sp, color = c.secondary)
                Spacer(Modifier.height(14.dp))
                val s = safety
                if (s == null) Text("Their key isn't available yet. Try again when they have opened Fariin.", fontSize = 15.sp, color = c.label)
                else SafetyNumber.grouped(s).chunked(4).forEach { row ->
                    Text(row.joinToString("   "), fontSize = 18.sp, color = c.label, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                }
            }
        },
        confirmButton = { TextButton({ showSafety = false }) { Text("Done", color = c.blue) } }, containerColor = c.card)
    ask?.let { what ->
        val block = what == "block"
        AlertDialog(onDismissRequest = { ask = null },
            title = { Text(if (block) (if (blocked) "Unblock $name?" else "Block $name?") else "Report $name?") },
            text = { Text(if (block) (if (blocked) "They will be able to message and call you again." else "Blocked people can't message or call you.")
                else "Fariin can't read your messages, so the report says who and why, never what was written.") },
            confirmButton = { TextButton({
                ask = null
                scope.launch {
                    if (block) { if (api.setBlocked(uid, !blocked)) blocked = !blocked else notice = "Check your connection and try again." }
                    else notice = if (api.report(uid, cid, "spam")) "Thanks. Your report was sent." else "Check your connection and try again."
                }
            }) { Text(if (block) (if (blocked) "Unblock" else "Block") else "Report", color = c.red) } },
            dismissButton = { TextButton({ ask = null }) { Text("Cancel", color = c.blue) } }, containerColor = c.card)
    }
    notice?.let { n -> AlertDialog(onDismissRequest = { notice = null }, text = { Text(n) },
        confirmButton = { TextButton({ notice = null }) { Text("OK", color = c.blue) } }, containerColor = c.card) }

    Column(Modifier.fillMaxSize().background(c.bgSecondary)) {
        PageBar("", onBack = onBack) {
            if (uid != me) com.fariin.app.ui.components.FloatingTextAction("Edit") { nickname = true }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Avatar(person?.photoUrl ?: conv?.photos?.get(uid), 100.dp)
            Spacer(Modifier.height(10.dp))
            Text(name, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = c.label, textAlign = TextAlign.Center)
            person?.handle?.takeIf { it.isNotEmpty() }?.let { Text("@$it", fontSize = 16.sp, color = c.secondary) }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                ActionTile("Message", androidx.compose.material.icons.Icons.Outlined.ChatBubbleOutline) { onOpenChat(cid) }
                ActionTile(if (muted) "Unmute" else "Mute",
                    if (muted) androidx.compose.material.icons.Icons.Outlined.Notifications
                    else androidx.compose.material.icons.Icons.Outlined.NotificationsOff) {
                    scope.launch { if (api.setMuted(cid, !muted)) muted = !muted else notice = "Check your connection and try again." }
                }
                if (fromChat && uid != me) ActionTile("Search", androidx.compose.material.icons.Icons.Filled.Search) {
                    com.fariin.app.ui.thread.InChatSearchLink.request(cid); onBack()
                }
            }
            Spacer(Modifier.height(20.dp))
            person?.about?.takeIf { it.isNotEmpty() }?.let { bio ->
                SettingsSection { Text(bio, fontSize = 17.sp, color = c.label, modifier = Modifier.fillMaxWidth().padding(16.dp)) }
            }
            if (uid != me) com.fariin.app.data.ContactNames.note(uid)?.let { NotesCard(it) }
            SettingsSection {
                DisappearingRow(timer, short = true) { timerOpen = true }
                SettingsRow("Verify Encryption", last = true) { scope.launch { safety = api.safetyNumber(uid); showSafety = true } }
            }
            // iOS: after the settings card, and only when the chat has photos or videos.
            SharedMediaCard(cid, shared) { allMedia = true }
            SettingsSection {
                SettingsRow(if (blocked) "Unblock $name" else "Block $name", titleColor = c.red, trailing = Trailing.NONE) { ask = "block" }
                SettingsRow("Report $name", titleColor = c.red, trailing = Trailing.NONE, last = true) { ask = "report" }
            }
            Spacer(Modifier.height(30.dp))
        }
    }
}

@Composable
internal fun ActionTile(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    // iOS glassActions: an icon-only floating circle with its name under it.
    val c = Fariin.colors
    Column(modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(52.dp).floating(), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = c.label, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(text, fontSize = 13.sp, color = c.label)
    }
}
