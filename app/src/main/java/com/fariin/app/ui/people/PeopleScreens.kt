package com.fariin.app.ui.people

import com.fariin.app.ui.components.glassMenuAnchor
import com.kyant.backdrop.backdrops.layerBackdrop
import com.fariin.app.R
import com.fariin.app.ui.settings.BarPage
import com.fariin.app.ui.components.underBar

import com.fariin.app.data.NewBadgeSpots
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.fariin.app.data.isRestricted
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import com.fariin.app.ui.components.listPress
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
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
        Modifier.fillMaxWidth().let { if (onClick != null) it.listPress(onClick = onClick) else it }.padding(horizontal = 16.dp, vertical = 8.dp),
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
 * New Chat (iOS NewChatView): one card to make things (New Group, New Channel, Invite Friends),
 * "Frequently contacted" (the three latest, once there are six people or more), then everyone I
 * have chatted with, A to Z. The search matches those people on this phone only (owner
 * 2026-10-18, "your chats only"): finding someone new is the Chats page's search. New Contact and
 * Find Channels are not here (owner 2026-09-30 / 2026-10-31 on iOS).
 */
@Composable
fun NewChatScreen(api: PeopleApi, chats: List<Conversation>, onBack: () -> Unit, onOpenChat: (String) -> Unit, onNewGroup: () -> Unit) {
    // iOS NewChatView (owner 2026-11-05, his iPhone beside the Pixel): grey page, white rounded
    // cards, plain outline icons, each person with @username under the name and the verified
    // mark, an A-Z index on the right, the search field at the bottom ("Search by username").
    val c = Fariin.colors
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var q by remember { mutableStateOf("") }
    val me = api.me
    val myHandle = remember(me) { com.fariin.app.data.MyProfileRepository().listen(me) }.collectAsState(initial = null).value?.handle
    // 1:1 people only, not cleared, and not someone whose request I have not answered (iOS).
    val people = remember(chats, me) {
        chats.filter { !it.isGroup && !it.isChannel && !it.isCleared(me) && !(it.startedBy.isNotEmpty() && it.startedBy != me && !it.accepted) }
            .filter { it.displayName(me).isNotBlank() }
    }
    // @handle and verified for each person: the cached profile first, then one read each (iOS reads
    // the on-disk cache only; a missing handle simply has no second line).
    val profiles = remember { androidx.compose.runtime.mutableStateMapOf<String, Person>() }
    LaunchedEffect(people) {
        for (conv in people) {
            val uid = com.fariin.app.crypto.Wire.otherUid(conv.id, me)
            if (uid.isEmpty() || profiles.containsKey(uid)) continue
            (ProfileMemo.get(uid) ?: api.profile(uid)?.also { ProfileMemo.put(uid, it) })?.let { profiles[uid] = it }
        }
    }
    fun uidOf(conv: Conversation) = com.fariin.app.crypto.Wire.otherUid(conv.id, me)
    val frequent = remember(people) { if (people.size >= 6) people.sortedByDescending { it.updatedAtMillis }.take(3) else emptyList() }
    val sections = remember(people, me) {
        people.groupBy { conv -> conv.displayName(me).trim().firstOrNull()?.takeIf { it.isLetter() }?.uppercaseChar()?.toString() ?: "#" }
            .toSortedMap(compareBy<String> { it == "#" }.thenBy { it })
            .mapValues { (_, list) -> list.sortedBy { it.displayName(me).lowercase() } }
    }
    val needle = q.trim().removePrefix("@")
    val matches = if (needle.isEmpty()) emptyList() else people.filter {
        it.displayName(me).contains(needle, ignoreCase = true) || profiles[uidOf(it)]?.handle?.contains(needle, ignoreCase = true) == true
    }.sortedBy { it.displayName(me).lowercase() }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scope = rememberCoroutineScope()

    @Composable
    fun personRow(conv: Conversation, divider: Boolean) {
        val p = profiles[uidOf(conv)]
        Column {
            Row(Modifier.fillMaxWidth().clickable { onOpenChat(conv.id) }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Avatar(conv.displayPhoto(me), 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(conv.displayName(me), fontSize = 17.sp, color = c.label, maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (p?.verified == true) {
                            Spacer(Modifier.width(4.dp))
                            Icon(ImageVector.vectorResource(R.drawable.ic_sf_checkmark_seal_fill), "Verified", tint = c.blue, modifier = Modifier.size(15.dp))
                        }
                    }
                    if (!p?.handle.isNullOrEmpty()) Text("@" + p!!.handle, fontSize = 13.sp, color = c.secondary, maxLines = 1)
                }
            }
            if (divider) HorizontalDivider(thickness = 0.5.dp, color = c.separator, modifier = Modifier.padding(start = 68.dp))
        }
    }
    @Composable
    fun card(content: @Composable () -> Unit) = Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        .clip(RoundedCornerShape(24.dp)).background(c.card)) { content() }
    @Composable
    fun sectionTitle(t: String) = Text(t, fontSize = 15.sp, color = c.secondary,
        modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 18.dp, bottom = 6.dp))

    Box(Modifier.fillMaxSize().background(c.bgSecondary)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // The close circle and the centred title (iOS sheet header).
            Box(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 12.dp)) {
                Box(Modifier.align(Alignment.CenterStart).size(44.dp).floating(CircleShape).clickable(onClickLabel = "Close") { onBack() },
                    contentAlignment = Alignment.Center) { Icon(ImageVector.vectorResource(R.drawable.ic_sf_xmark), null, tint = c.label) }
                Text("New chat", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = c.label, modifier = Modifier.align(Alignment.Center))
            }
            LazyColumn(Modifier.fillMaxSize(), state = listState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 8.dp, bottom = 120.dp)) {
                if (needle.isEmpty()) {
                    item {
                        val channelNav = com.fariin.app.ui.channels.LocalChannelNav.current
                        val actions = listOf(
                            Triple("New group", onNewGroup, ImageVector.vectorResource(com.fariin.app.R.drawable.ic_gs_members)),
                            Triple("New channel", channelNav.create, ImageVector.vectorResource(R.drawable.ic_sf_megaphone)),
                            Triple("Invite friends", { com.fariin.app.ui.settings.shareInvite(ctx, myHandle) }, ImageVector.vectorResource(com.fariin.app.R.drawable.ic_link_share)),
                        )
                        card {
                            actions.forEachIndexed { i, (label, open, icon) ->
                                if (i > 0) HorizontalDivider(thickness = 0.5.dp, color = c.separator, modifier = Modifier.padding(start = 60.dp))
                                Row(Modifier.fillMaxWidth().clickable(onClick = open).padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Icon(icon, null, tint = c.label, modifier = Modifier.size(24.dp))
                                    Spacer(Modifier.width(20.dp))
                                    Text(label, fontSize = 17.sp, color = c.label)
                                }
                            }
                        }
                    }
                    if (frequent.isNotEmpty()) {
                        item { sectionTitle("Frequently contacted") }
                        item { card { frequent.forEachIndexed { i, conv -> personRow(conv, i < frequent.lastIndex) } } }
                    }
                    if (people.isEmpty()) item {
                        com.fariin.app.ui.components.IllustratedEmpty(com.fariin.app.ui.components.LineArt.People,
                            "No chats yet", "Invite a friend to Fariin and start your first chat.",
                            Modifier.padding(top = 24.dp), action = "Invite Friends") { com.fariin.app.ui.settings.shareInvite(ctx, myHandle) }
                    }
                    sections.forEach { (letter, list) ->
                        item(key = "h$letter") { sectionTitle(letter) }
                        item(key = "c$letter") { card { list.forEachIndexed { i, conv -> personRow(conv, i < list.lastIndex) } } }
                    }
                } else {
                    item { sectionTitle("Results") }
                    item {
                        card {
                            if (matches.isEmpty()) Text("No chats match \u201c$needle\u201d", fontSize = 17.sp, color = c.secondary, modifier = Modifier.padding(16.dp))
                            matches.forEachIndexed { i, conv -> personRow(conv, i < matches.lastIndex) }
                        }
                    }
                }
            }
        }
        // A-Z index on the right: a tap jumps to that letter (iOS overlay).
        if (needle.isEmpty() && sections.size > 1) Column(Modifier.align(Alignment.CenterEnd).padding(end = 2.dp)) {
            val letters = sections.keys.toList()
            letters.forEach { l ->
                Text(l, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = c.accent,
                    modifier = Modifier.width(16.dp).clickable {
                        // Item index of that letter's title: actions(1) + frequent block(2 or 0) + 2 per earlier letter.
                        var idx = 1 + (if (frequent.isNotEmpty()) 2 else 0) + (if (people.isEmpty()) 1 else 0)
                        for (k in letters) { if (k == l) break; idx += 2 }
                        scope.launch { listState.animateScrollToItem(idx) }
                    }.padding(vertical = 1.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
        // The search field at the bottom, over the list (iOS toolbar search).
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().imePadding().padding(bottom = 8.dp)) {
            com.fariin.app.ui.components.FloatingSearchField(q, { q = it }, "Search by username",
                Modifier.fillMaxWidth().padding(horizontal = 16.dp))
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
                Icon(ImageVector.vectorResource(R.drawable.ic_sf_xmark), "Close", tint = c.secondary, modifier = Modifier.size(28.dp).clickable(onClick = onDismiss))
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
                                "del" -> Icon(ImageVector.vectorResource(R.drawable.ic_sf_delete_left), "Delete", tint = c.label)
                                "Paste" -> Text("Paste", fontSize = 17.sp, color = c.accent)
                                else -> Text(k, fontSize = 28.sp, color = c.label)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            val enabled = ChatKey.isValid(pin) && !busy
            Box(Modifier.fillMaxWidth().height(52.dp).clip(CircleShape).background(if (enabled) c.accent else c.accent.copy(alpha = 0.35f))
                .clickable(enabled = enabled) {
                    busy = true
                    scope.launch {
                        api.verifyChatKey(p.uid, pin).fold(onSuccess = onOpened, onFailure = { failure = it.message; pin = "" })
                        busy = false
                    }
                }, contentAlignment = Alignment.Center) {
                if (busy) CircularProgressIndicator(color = c.onAccent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                else Text("Enter", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.onAccent)
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
            dismissButton = { TextButton({ pendingDelete = null }) { Text("Cancel", color = c.accent) } }, containerColor = c.card)
    }
    if (failed) AlertDialog(onDismissRequest = { failed = false }, title = { Text("Couldn't do that") },
        text = { Text("Check your connection and try again.") },
        confirmButton = { TextButton({ failed = false }) { Text("OK", color = c.accent) } }, containerColor = c.card)
    val list = requests.filter { it.id !in gone }
    // The white page with the shared frosted top (as Archive and the other pages); the bar was a grey band.
    com.fariin.app.ui.components.GlassBarPage(false, Modifier.background(c.bg), page = c.bg, frosted = true,
        bar = { PageBar("Message Requests", onBack = onBack) }) { top -> Column(Modifier.fillMaxSize()) {
        androidx.compose.foundation.layout.Spacer(Modifier.height(top))
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
                        Pill("Accept", c.accent, c.onAccent) {
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
    // The copy from the last visit draws at once; the fresh read replaces it (no bio popping in).
    var person by remember { mutableStateOf<Person?>(ProfileMemo.get(uid)) }
    var addingToGroup by remember { mutableStateOf(false) }
    var sharingProfile by remember { mutableStateOf(false) }
    var exportJob by remember { mutableStateOf<ChatExportRun.Job?>(null) }
    val shareCtx = androidx.compose.ui.platform.LocalContext.current
    var blocked by remember { mutableStateOf(false) }
    var safety by remember { mutableStateOf<String?>(null) }
    var showSafety by remember { mutableStateOf(false) }
    var ask by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(uid) { api.profile(uid)?.let { person = it; ProfileMemo.put(uid, it) }; blocked = api.isBlocked(uid) }
    // A name I saved for them wins (iOS ContactNames); Edit opens Set nickname.
    val name = com.fariin.app.data.ContactNames.name(uid) ?: person?.shownName ?: conv?.names?.get(uid) ?: ""
    val cid = conv?.id ?: Wire.directCid(me, uid)
    // iOS: Search only on the page opened from this person's chat; it goes back there and opens it.
    val fromChat = androidx.compose.runtime.saveable.rememberSaveable { com.fariin.app.ui.thread.InChatSearchLink.takeOpenedFrom(cid) }
    var nickname by remember { mutableStateOf(false) }
    // Opened from a call on the Calls page: that call's card under the circles (iOS callLogCard).
    val lastCall = remember(uid) { com.fariin.app.data.CallInfoLink.take(uid) }
    // Glow (iOS ContactInfoView): the Glow circle and "Glow Story" in the overflow, never on my own
    // page or a blocked person's; hidden with the feature.
    val glowOn = com.fariin.app.data.AppFeatures.glow && uid != me && !blocked
    val glowDoor = com.fariin.app.ui.glow.rememberGlowDoor(uid, name)
    // The phone's Back closes this page too, back to the profile (owner 2026-11-07: it went to the chat).
    if (nickname) { androidx.activity.compose.BackHandler { nickname = false }
        SetNicknamePage(uid, person?.photoUrl ?: conv?.photos?.get(uid), onBack = { nickname = false }); return }
    // The chat's timer, live (either person may change it; iOS ContactInfoView).
    val live = if (api is com.fariin.app.data.FirestorePeople) liveGroupDoc(cid) else emptyMap()
    val timer = (live["disappearSeconds"] as? Number)?.toInt() ?: conv?.disappearSeconds ?: 0
    // My mute of this chat: the live `mutedBy.<me>` end, or the one just picked until the doc answers.
    var muteOverride by remember(cid) { mutableStateOf<Double?>(null) }
    val liveUntil = if (live.containsKey("mutedBy")) com.fariin.app.data.GroupInfoCircles.mutedUntil(live, me) else conv?.mutedBy?.get(me) ?: 0.0
    androidx.compose.runtime.LaunchedEffect(liveUntil) { muteOverride = null }
    val muteUntil = muteOverride ?: liveUntil
    val muted = muteUntil > System.currentTimeMillis()
    fun setMute(until: Double) {
        muteOverride = until
        if (api !is com.fariin.app.data.FirestorePeople) return
        scope.launch {
            if (!writeContactMute(cid, me, until)) { muteOverride = null; notice = com.fariin.app.data.GroupInfoCircles.MUTE_FAILED }
        }
    }
    var soundsOpen by remember { mutableStateOf(false) }
    if (soundsOpen) { ContactSoundsPage(muteUntil, onMute = { setMute(it) }, onBack = { soundsOpen = false }); return }
    // iOS reachedMeWithMyKey: they opened this chat with my Chat Key (the server's acceptedVia "pin").
    val reachedWithMyKey = uid != me && live["acceptedVia"] == "pin" && live["acceptedBy"] == uid
    var timerOpen by remember { mutableStateOf(false) }
    if (DisappearingEditor(api, cid, isGroup = false, current = timer, open = timerOpen, onClose = { timerOpen = false })) return
    val shared = rememberSharedMedia(cid, me)
    var allMedia by remember { mutableStateOf(false) }
    if (allMedia) { SharedMediaScreen(cid, shared, onBack = { allMedia = false }); return }
    // iOS sharedGroups: groups we are both in, from the live chat list (not on my own page).
    val commonGroups = if (uid != me) com.fariin.app.data.GroupsInCommon.shared(com.fariin.app.data.ChatsNow.list, me, uid) else emptyList()
    var allGroups by remember { mutableStateOf(false) }
    if (allGroups) { GroupsInCommonPage(commonGroups, me, onBack = { allGroups = false }, onOpenChat = onOpenChat); return }
    var restrictOpen by remember { mutableStateOf(false) }
    if (restrictOpen) {
        val myName = remember { mutableStateOf("") }
        LaunchedEffect(Unit) { myName.value = api.profile(me)?.shownName.orEmpty() }
        RestrictedChatPage(cid, me, name.ifEmpty { "They" }, { myName.value }, onBack = { restrictOpen = false }); return
    }
    // Chat Bubble (SPEC 4): my one bubble style for every chat, the Settings picker itself.
    var bubbleOpen by remember { mutableStateOf(false) }
    if (bubbleOpen) { com.fariin.app.ui.settings.BubbleStylePage { bubbleOpen = false }; return }
    // The full Encryption page, as iOS VerifyEncryptionView (the old dialog wrapped the numbers).
    if (showSafety) {
        var myPhotoUrl by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(Unit) { myPhotoUrl = api.profile(me)?.photoUrl }
        VerifyEncryptionPage(cid, name, person?.photoUrl ?: conv?.photos?.get(uid), myPhotoUrl, safety, onClose = { showSafety = false })
        return
    }
    // Block, Unblock, Delete Chat: one bottom sheet that fits its words, the person's photo
    // and name on top (owner 2026-11-07: not a centred dialog), one red action and Cancel; Block and
    // Report is gone from the block sheet, Report is its own row.
    ask?.let { what ->
        val who = name.ifEmpty { "this person" }
        val failed = "Check your connection and try again."
        val (title, body, actions) = when {
            what == "delete" -> Triple("Delete this chat?",
                "Every message in this chat will be removed from this device. The other person keeps their own copy.",
                // The chat list's own Delete for Me (iOS ChatService.deleteForMe), not a second delete.
                listOf(PersonAction("Delete Chat") {
                    val chat = conv ?: com.fariin.app.data.CryptoHub.listedConversation(cid)
                    val ok = chat != null && com.fariin.app.ui.chats.ChatListActions.deleteForMe(me, chat)
                    if (ok) onBack() else notice = failed
                    ok
                }))
            what == "clearMine" -> Triple("Clear your messages?",
                "This deletes the messages you sent in this chat. It can't be undone.",
                listOf(PersonAction("Clear") {
                    val id = conv?.id
                    val ok = id != null && com.fariin.app.ui.chats.ChatListActions.clearMyMessages(me, id)
                    com.fariin.app.ui.components.Snack.show(if (ok) "Your messages were cleared" else "Couldn't clear. Check your connection and try again.")
                    ok
                }))
            what == "unblock" -> Triple("Unblock $who?", "They will be able to message and call you again.",
                listOf(PersonAction("Unblock") {
                    val ok = api.setBlocked(uid, false)
                    if (ok) blocked = false else notice = failed
                    ok
                }))
            else -> Triple("Block $who?",
                "Blocked people can't message or call you. You can unblock them later in Privacy & Security.",
                listOf(PersonAction("Block") {
                    val ok = api.setBlocked(uid, true)
                    if (ok) blocked = true else notice = failed
                    ok
                }))
        }
        PersonConfirmSheet(person?.photoUrl ?: conv?.photos?.get(uid), name, title, body, actions, onDismiss = { ask = null })
    }
    if (glowOn) com.fariin.app.ui.glow.GlowDoorHost(glowDoor)
    notice?.let { n -> AlertDialog(onDismissRequest = { notice = null }, text = { Text(n) },
        confirmButton = { TextButton({ notice = null }) { Text("OK", color = c.accent) } }, containerColor = c.card) }

    // The page runs under its bar, so back, Edit and the menu are glass over it (GlassBarPage).
    val pageScroll = rememberScrollState()
    val underBar by remember { androidx.compose.runtime.derivedStateOf { pageScroll.value > 0 } }
    // iOS ••• "Change Wallpaper", only from the chat (it goes back there and opens its picker);
    // under Glow Story and a divider when Glow is on, as iOS orders the menu.
    val wallpaper: () -> Unit = { com.fariin.app.ui.settings.ChatWallpaperLink.request(cid); onBack() }
    val addToGroup: (() -> Unit)? = if (uid != me) ({ addingToGroup = true }) else null
    val clearMine: (() -> Unit)? = if (uid != me && conv != null) ({ ask = "clearMine" }) else null
    val shareProfile: (() -> Unit)? = if (uid != me) ({ sharingProfile = true }) else null
    // iOS Export Chat: only where a real 1:1 chat exists, never on my own page.
    val chat = conv ?: com.fariin.app.data.CryptoHub.listedConversation(cid)
    val exportChat: (() -> Unit)? = if (ChatExportRun.SHOWN && uid != me && chat != null && !chat.isGroup)
        ({ if (exportJob == null) exportJob = ChatExportRun.Job(chat.id, name.ifEmpty { chat.displayName(me) }, uid) }) else null
    // The ••• menu is the glass menu over the page, grown from its button (as the group's and channel's).
    val moreAnchor = remember { com.fariin.app.ui.components.GlassMenuAnchor() }
    var moreOpen by remember { mutableStateOf(false) }
    val menuBackdrop = com.kyant.backdrop.backdrops.rememberLayerBackdrop()
    val menuGlass = com.fariin.app.perf.rememberPowerState().glass() && android.os.Build.VERSION.SDK_INT >= 33
    Box(Modifier.fillMaxSize()) {
    com.fariin.app.ui.components.GlassBarPage(underBar, Modifier.background(c.bgSecondary)
        .then(if (menuGlass) Modifier.layerBackdrop(menuBackdrop) else Modifier), frosted = true, bar = {
        PageBar("", onBack = onBack) {
            if (uid != me) com.fariin.app.ui.components.FloatingTextAction("Edit") { nickname = true }
            if (glowOn) com.fariin.app.ui.glow.GlowOverflow(glowDoor) { close ->
                if (fromChat || addToGroup != null) androidx.compose.material3.HorizontalDivider(color = c.separator)
                if (shareProfile != null) ShareProfileItem { close(); shareProfile() }
                if (addToGroup != null) AddToGroupItem { close(); addToGroup() }
                if (fromChat) ChangeWallpaperItem { close(); wallpaper() }
                if (exportChat != null) ExportChatItem { close(); exportChat() }
                if (clearMine != null) ClearMineItem { close(); clearMine() }
            }
            else if (fromChat || addToGroup != null) com.fariin.app.ui.components.FloatingIcon(ImageVector.vectorResource(R.drawable.ic_more_ios),
                contentDescription = "More", onClick = { moreOpen = true }, modifier = Modifier.padding(end = 4.dp).glassMenuAnchor(moreAnchor))
        }
    }) { top ->
        exportJob?.let { j -> ChatExportDialog(j) { exportJob = null } }
        if (sharingProfile) com.fariin.app.ui.thread.ForwardSheet(me, onDismiss = { sharingProfile = false }, title = "Share Profile") { targets ->
            sharingProfile = false
            val shownName = person?.shownName ?: conv?.displayName(me) ?: ""
            val photo = person?.photoUrl ?: conv?.photos?.get(uid) ?: ""
            val card = com.fariin.app.data.CardRules.CONTACT + uid + "|" + photo + "|" + shownName
            val crypto = com.fariin.app.data.CryptoHub.get(shareCtx.applicationContext)
            scope.launch {
                var ok = true
                for (cid in targets) {
                    val repo = com.fariin.app.data.ThreadRepository.openOrQuiet(cid, me, crypto, scope)
                    try { repo.sendForwardedText(card, forwarded = false) } catch (_: Exception) { ok = false }
                }
                com.fariin.app.ui.components.Snack.show(if (ok) "Sent" else "Couldn't send. Try again.")
            }
        }
        if (addingToGroup) AddToGroupSheet(api, uid, person?.shownName ?: conv?.displayName(me) ?: "", onDismiss = { addingToGroup = false })
        // First visit: the whole page waits for the profile (at most 1.5 s) and lands in one piece;
        // drawing the photo and buttons first let the bio push in under them (owner 2026-11-04).
        var waited by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { kotlinx.coroutines.delay(1500); waited = true }
        if (person == null && !waited) return@GlassBarPage
        Column(Modifier.fillMaxSize().verticalScroll(pageScroll).padding(top = top), horizontalAlignment = Alignment.CenterHorizontally) {
            Avatar(person?.photoUrl ?: conv?.photos?.get(uid), 100.dp)
            Spacer(Modifier.height(10.dp))
            // The verified seal after the name (iOS VerifiedMark, 20): it was missing on this page.
            val vCtx = androidx.compose.ui.platform.LocalContext.current
            LaunchedEffect(uid) { com.fariin.app.data.VerifiedPeople.warm(vCtx.applicationContext, uid) }
            val vKinds by com.fariin.app.data.VerifiedPeople.kinds.collectAsState()
            val vKind = com.fariin.app.data.VerifiedPeople.badge(vKinds, uid)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = c.label, textAlign = TextAlign.Center)
                if (vKind != null || person?.verified == true) {
                    Spacer(Modifier.width(6.dp))
                    Icon(ImageVector.vectorResource(R.drawable.ic_sf_checkmark_seal_fill), "Verified",
                        tint = if (vKind != null && com.fariin.app.data.VerifiedPeople.isGold(vKind)) androidx.compose.ui.graphics.Color(0xFFE8B21E) else c.blue,
                        modifier = Modifier.size(20.dp))
                }
            }
            person?.handle?.takeIf { it.isNotEmpty() }?.let { Text("@$it", fontSize = 16.sp, color = c.secondary) }
            Spacer(Modifier.height(16.dp))
            // Spread over the width: with Search too, five circles at a fixed 28 dp gap ran past the
            // edge and the last label wrapped letter by letter into a tall empty band.
            // iOS actionsRow: equal shares, inside the page's 16 dp margins.
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                val share = Modifier.weight(1f)
                ActionTile("Message", ImageVector.vectorResource(R.drawable.ic_chat), share) { onOpenChat(cid) }
                if (glowOn) com.fariin.app.ui.glow.GlowActionTile(glowDoor, share)
                // iOS ContactInfoView: Voice and Video circles, never on my own page or a blocked person's.
                if (uid != me && !blocked) {
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    val photo = person?.photoUrl ?: conv?.photos?.get(uid)
                    ActionTile("Voice", ImageVector.vectorResource(com.fariin.app.R.drawable.ic_call_voice), share) {
                        com.fariin.app.ui.call.CallActivity.startDial(ctx, uid, name, photo, video = false)
                    }
                    ActionTile("Video", ImageVector.vectorResource(com.fariin.app.R.drawable.ic_call_video), share) {
                        com.fariin.app.ui.call.CallActivity.startDial(ctx, uid, name, photo, video = true)
                    }
                }
                ActionTile(if (muted) "Unmute" else "Mute",
                    if (muted) ImageVector.vectorResource(com.fariin.app.R.drawable.ic_bell)
                    else ImageVector.vectorResource(com.fariin.app.R.drawable.ic_bell_off), share) {
                    setMute(if (muted) 0.0 else com.fariin.app.data.GroupInfoCircles.FOREVER)
                }
                if (fromChat && uid != me) ActionTile("Search", ImageVector.vectorResource(R.drawable.ic_search_ios), share) {
                    com.fariin.app.ui.thread.InChatSearchLink.searchFromInfo(cid, onBack)
                }
            }
            Spacer(Modifier.height(20.dp))
            lastCall?.let { call -> CallLogCard(call, me) }
            person?.about?.takeIf { it.isNotEmpty() }?.let { bio ->
                SettingsSection { Text(bio, fontSize = 17.sp, color = c.label, modifier = Modifier.fillMaxWidth().padding(16.dp)) }
            }
            person?.links?.let { ProfileLinkChips(it, Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)) }
            if (uid != me) com.fariin.app.data.ContactNames.note(uid)?.let { NotesCard(it) }
            SettingsSection {
                DisappearingRow(timer, short = true, badge = NewBadgeSpots.PROFILE_DISAPPEARING) { timerOpen = true }
                // iOS: Restricted chat, for a chat that exists (the switch lives on it).
                val chatNow = com.fariin.app.data.ChatsNow.find(cid)
                // Each row with its iOS icon, so the three line up (only the first had one).
                if (uid != me && chatNow != null) SettingsRow("Restricted Chat",
                    icon = androidx.compose.ui.res.painterResource(com.fariin.app.R.drawable.ic_restricted_chat),
                    value = if (chatNow.isRestricted) "On" else "Off", badge = NewBadgeSpots.PROFILE_RESTRICTED) { restrictOpen = true }
                // iOS: Sounds & Notifications, "Muted" or "On"; the page picks how long.
                if (uid != me) SettingsRow("Sounds & Notifications",
                    icon = androidx.compose.ui.res.painterResource(com.fariin.app.R.drawable.ic_notifications),
                    value = contactNotifValue(muteUntil), badge = NewBadgeSpots.PROFILE_SOUNDS) { soundsOpen = true }
                // No Chat Bubble row (owner 2026-11-07): bubble styles live in Settings > Appearance and the chat's wallpaper sheet.
                SettingsRow("Verify Encryption", icon = androidx.compose.ui.res.painterResource(com.fariin.app.R.drawable.ic_verify_encryption), last = true,
                    badge = NewBadgeSpots.PROFILE_VERIFY_ENCRYPTION) { scope.launch { safety = api.safetyNumber(uid); showSafety = true } }
            }
            // iOS: after the settings card, and only when the chat has photos or videos.
            SharedMediaCard(cid, shared) { allMedia = true }
            if (uid != me) GroupsInCommonSection(commonGroups, me, onAdd = { addingToGroup = true },
                onSeeAll = { allGroups = true }, onOpenChat = onOpenChat)
            // iOS dangerCard: a blocked person's page is Delete Chat and Unblock and nothing else
            // (no Report while blocked, owner 2026-09-27); Delete Chat only where a chat exists.
            val deletable = conv ?: com.fariin.app.data.CryptoHub.listedConversation(cid)
            SettingsSection {
                if (blocked) {
                    if (deletable != null) SettingsRow("Delete Chat", titleColor = c.red, trailing = Trailing.NONE) { ask = "delete" }
                    SettingsRow("Unblock $name", titleColor = c.red, trailing = Trailing.NONE, last = true) { ask = "unblock" }
                } else {
                    // iOS: told when someone reached me with my Chat Key; read only, never on their side.
                    if (reachedWithMyKey) SettingsRow("Used your Chat Key to reach you",
                        icon = androidx.compose.ui.res.painterResource(com.fariin.app.R.drawable.ic_chat_key),
                        trailing = Trailing.NONE)
                    SettingsRow("Block $name", titleColor = c.red, trailing = Trailing.NONE) { ask = "block" }
                    SettingsRow("Report $name", titleColor = c.red, trailing = Trailing.NONE, last = true) {
                        com.fariin.app.data.ReportFlow.ask(com.fariin.app.data.ReportTarget(com.fariin.app.data.ReportKind.ACCOUNT,
                            name = name, personUid = uid, cid = cid, onSent = { b -> if (b) blocked = true }))
                    }
                }
            }
            Spacer(Modifier.height(30.dp))
        }
    }
    com.fariin.app.ui.components.GlassMenu(moreOpen, moreAnchor, buildList {
        if (shareProfile != null) add(com.fariin.app.ui.components.GlassMenuItem("Share Profile",
            androidx.compose.ui.res.painterResource(R.drawable.ic_link_share)) { shareProfile() })
        if (addToGroup != null) add(com.fariin.app.ui.components.GlassMenuItem("Add to a Group",
            androidx.compose.ui.res.painterResource(R.drawable.ic_add_member)) { addToGroup() })
        if (fromChat) add(com.fariin.app.ui.components.GlassMenuItem("Change Wallpaper",
            androidx.compose.ui.res.painterResource(R.drawable.ic_wallpaper)) { wallpaper() })
        if (exportChat != null) add(com.fariin.app.ui.components.GlassMenuItem("Export Chat",
            androidx.compose.ui.res.painterResource(R.drawable.ic_save_down)) { exportChat() })
        if (clearMine != null) add(com.fariin.app.ui.components.GlassMenuItem("Clear My Messages",
            androidx.compose.ui.res.painterResource(R.drawable.ic_sf_trash), danger = true, divider = true) { clearMine() })
    }, backdrop = if (menuGlass) menuBackdrop else null, onDismiss = { moreOpen = false })
    }
}

/**
 * iOS groupsHeader + groupsInCommonCard, drawn like the group page's members header and card: a
 * grey "N Groups in Common >" line that opens the whole list (no arrow and no tap with none),
 * then "Add to a Group" and the groups, six at most; past six, the first five and See All.
 */
@Composable
private fun GroupsInCommonSection(groups: List<Conversation>, me: String, onAdd: () -> Unit, onSeeAll: () -> Unit,
                                  onOpenChat: (String) -> Unit) {
    val c = Fariin.colors
    val any = groups.isNotEmpty()
    val (shownGroups, more) = com.fariin.app.data.GroupsInCommon.shown(groups)
    val header = com.fariin.app.data.GroupsInCommon.header(groups.size)
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 8.dp).clip(RoundedCornerShape(12.dp))
        .let { if (any) it.clickable(onClickLabel = "See All") { onSeeAll() } else it }.padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(header, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = c.secondary)
        if (any) Icon(ImageVector.vectorResource(R.drawable.ic_sf_chevron_right), null, tint = c.secondary, modifier = Modifier.size(26.dp))
        Spacer(Modifier.weight(1f))
    }
    SettingsSection {
        Row(Modifier.fillMaxWidth().clickable { onAdd() }.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(c.fieldFill), contentAlignment = Alignment.Center) {
                Icon(ImageVector.vectorResource(R.drawable.ic_plus_ios), null, tint = c.label, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(14.dp))
            Text("Add to a Group", fontSize = 17.sp, color = c.label)
        }
        shownGroups.forEach { g -> GroupInCommonRow(g, me, onOpenChat) }
        if (more) SettingsRow("See All", icon = androidx.compose.ui.graphics.vector.rememberVectorPainter(ImageVector.vectorResource(R.drawable.ic_sf_chevron_down)),
            trailing = Trailing.NONE, last = true) { onSeeAll() }
    }
}

/** One group: its photo, its name, and the member count on the right (iOS memberCountLabel). */
@Composable
private fun GroupInCommonRow(g: Conversation, me: String, onOpenChat: (String) -> Unit) {
    val c = Fariin.colors
    PersonRow(g.displayPhoto(me), g.displayName(me), "", onClick = { onOpenChat(g.id) }) {
        Text(com.fariin.app.data.GroupsInCommon.memberLabel(g), fontSize = 14.sp, color = c.secondary)
    }
}

/** iOS GroupsInCommonSheet: every group we share in one card, A to Z; a tap opens the group. */
@Composable
private fun GroupsInCommonPage(groups: List<Conversation>, me: String, onBack: () -> Unit, onOpenChat: (String) -> Unit) {
    val c = Fariin.colors
    androidx.activity.compose.BackHandler(onBack = onBack)
    val barScroll = rememberScrollState()
    BarPage("Groups in Common", onBack = onBack, under = underBar(barScroll), modifier = Modifier.background(c.bgSecondary)) { top ->
        Column(Modifier.fillMaxSize().verticalScroll(barScroll).padding(top = top)) {
            Spacer(Modifier.height(12.dp))
            SettingsSection { groups.forEach { g -> GroupInCommonRow(g, me, onOpenChat) } }
            Spacer(Modifier.height(30.dp))
        }
    }
}

/** iOS callLogCard: the call's day, then its direction and kind with the time on the right; red for one I missed. */
@Composable
private fun CallLogCard(call: com.fariin.app.data.CallEntry, me: String) {
    val c = Fariin.colors
    val missed = call.missedIncoming(me)
    val date = remember(call.dateMs) { java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(call.dateMs)) }
    val time = remember(call.dateMs) { java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(call.dateMs)) }
    SettingsSection {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(date, fontSize = 15.sp, color = c.secondary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(when {
                    missed -> ImageVector.vectorResource(R.drawable.ic_sf_arrow_down_left)
                    call.mine(me) -> ImageVector.vectorResource(R.drawable.ic_sf_arrow_up_right)
                    else -> ImageVector.vectorResource(R.drawable.ic_sf_arrow_down_left)
                }, contentDescription = null, tint = if (missed) c.red else c.secondary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(com.fariin.app.data.CallInfoLink.line(call, me), fontSize = 15.sp, color = if (missed) c.red else c.label,
                    modifier = Modifier.weight(1f))
                Text(time, fontSize = 15.sp, color = c.secondary)
            }
        }
    }
}

/** iOS ContactInfoView menu: "Share Profile" (this person's card, sent to the chats I pick). */
@Composable
private fun ShareProfileItem(onClick: () -> Unit) {
    val c = Fariin.colors
    androidx.compose.material3.DropdownMenuItem(text = { Text("Share Profile", color = c.label) },
        leadingIcon = { Icon(androidx.compose.ui.res.painterResource(com.fariin.app.R.drawable.ic_link_share), null, tint = c.label,
            modifier = Modifier.size(24.dp)) }, onClick = onClick)
}

/** iOS ContactInfoView menu: "Clear My Messages" (every message I sent in this chat, for both of us). */
@Composable
private fun ClearMineItem(onClick: () -> Unit) {
    val c = Fariin.colors
    androidx.compose.material3.DropdownMenuItem(text = { Text("Clear My Messages", color = c.red) },
        leadingIcon = { Icon(ImageVector.vectorResource(R.drawable.ic_sf_trash), null, tint = c.red) }, onClick = onClick)
}

/** iOS ContactInfoView menu: "Export Chat" (ChatExportRun), above Clear My Messages as iOS orders it. */
@Composable
internal fun ExportChatItem(onClick: () -> Unit) {
    val c = Fariin.colors
    androidx.compose.material3.DropdownMenuItem(text = { Text("Export Chat", color = c.label) },
        leadingIcon = { Icon(ImageVector.vectorResource(R.drawable.ic_save_down), null, tint = c.label) }, onClick = onClick)
}

/** iOS ContactInfoView menu: "Add to a Group" (AddToGroup.kt). */
@Composable
private fun AddToGroupItem(onClick: () -> Unit) {
    val c = Fariin.colors
    androidx.compose.material3.DropdownMenuItem(text = { Text("Add to a Group", color = c.label) },
        leadingIcon = { Icon(ImageVector.vectorResource(R.drawable.ic_add_member), null, tint = c.label) }, onClick = onClick)
}

@Composable
private fun ChangeWallpaperItem(onClick: () -> Unit) {
    val c = Fariin.colors
    androidx.compose.material3.DropdownMenuItem(text = { Text("Change Wallpaper", color = c.label) },
        leadingIcon = { Icon(ImageVector.vectorResource(com.fariin.app.R.drawable.ic_wallpaper), null, tint = c.label) }, onClick = onClick)
}

@Composable
internal fun ActionTile(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier,
                        dimmed: Boolean = false, onClick: () -> Unit) {
    // iOS glassActions / PosterActionIcon: the glass circle with its name under it.
    // Fariin's own drawings, as iOS (filled bubble, phone, camera and the owner's bells); the stock
    // outline glyphs read as old (owner 2026-11-04).
    val own = when (text) {
        "Message" -> com.fariin.app.R.drawable.ic_chat
        "Voice" -> com.fariin.app.R.drawable.ic_tab_calls
        "Video" -> com.fariin.app.R.drawable.ic_video_fill
        "Mute" -> com.fariin.app.R.drawable.ic_bell_off
        "Unmute" -> com.fariin.app.R.drawable.ic_bell
        else -> null
    }
    if (own != null) com.fariin.app.ui.components.GlassActionCircle(text, androidx.compose.ui.res.painterResource(own), modifier,
        dimmed = dimmed, onClick = onClick)
    else com.fariin.app.ui.components.GlassActionCircle(text, icon, modifier, dimmed = dimmed, onClick = onClick)
}

/**
 * New Contact (iOS NewContactView): First and Last Name in one card, the @username under it, and a
 * tick that finds them, keeps the name on this phone only (ContactNames) and opens the chat.
 * Fariin has no phone numbers, so the username is how a contact is found.
 */
@Composable
private fun NewContactPage(api: PeopleApi, onBack: () -> Unit, onOpenChat: (String) -> Unit) {
    val c = Fariin.colors
    val scope = rememberCoroutineScope()
    var first by remember { mutableStateOf("") }
    var last by remember { mutableStateOf("") }
    var handle by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var notFound by remember { mutableStateOf(false) }
    val clean = handle.trim().trimStart('@').lowercase().filter { it.isLetterOrDigit() || it == '_' || it == '.' }
    val canSave = clean.isNotEmpty() && !working
    fun add() {
        if (!canSave) return
        working = true
        scope.launch {
            val p = runCatching { api.byHandle(clean) }.getOrNull()
            if (p == null || p.uid == api.me) { working = false; notFound = true; return@launch }
            com.fariin.app.data.ContactNames.setCard(p.uid, com.fariin.app.data.ContactCard(first.trim(), last.trim()))
            val cid = runCatching { api.openChat(p) }.getOrNull()
            working = false
            if (cid != null) onOpenChat(cid) else notFound = true
        }
    }
    androidx.activity.compose.BackHandler { onBack() }
    com.fariin.app.ui.settings.BarPage("New Contact", onBack = onBack, modifier = Modifier.background(c.bgSecondary), action = {
            androidx.compose.material3.IconButton(onClick = { add() }, enabled = canSave, modifier = com.fariin.app.ui.components.barGlass()) {
                if (working) androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(ImageVector.vectorResource(R.drawable.ic_sf_checkmark), "Add contact", tint = if (canSave) c.accent else c.secondary)
            }
        }) { top ->
        androidx.compose.foundation.layout.Spacer(Modifier.height(top))
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.card)) {
                PlainField(first, "First Name") { first = it }
                HorizontalDivider(thickness = 0.5.dp, color = c.separator, modifier = Modifier.padding(start = 18.dp))
                PlainField(last, "Last Name") { last = it }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.card).padding(start = 18.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("@", color = c.secondary, fontSize = 17.sp)
                    PlainField(handle, "username", start = 2.dp) { handle = it }
                }
                Text("Fariin finds people by @username: there are no phone numbers. The name you enter is how they'll show in your chats.",
                    fontSize = 13.sp, color = c.secondary, modifier = Modifier.padding(horizontal = 4.dp))
            }
        }
    }
    if (notFound) androidx.compose.material3.AlertDialog(onDismissRequest = { notFound = false },
        text = { Text("Sorry, this user doesn't seem to exist.") },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { notFound = false }) { Text("OK") } })
}

@Composable
private fun PlainField(value: String, hint: String, start: androidx.compose.ui.unit.Dp = 18.dp, onChange: (String) -> Unit) {
    val c = Fariin.colors
    androidx.compose.foundation.text.BasicTextField(value, onChange, singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 17.sp, color = c.label),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent),
        modifier = Modifier.fillMaxWidth().height(52.dp).padding(start = start, end = 18.dp),
        decorationBox = { inner ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(hint, fontSize = 17.sp, color = c.secondary)
                inner()
            }
        })
}

/** People's profiles as last read this session, so a page opens complete (bio included). */
internal object ProfileMemo {
    private val map = java.util.concurrent.ConcurrentHashMap<String, Person>()
    private val asked = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    /** Read these people's profiles ahead (once each), so their page opens complete. */
    suspend fun warm(api: PeopleApi, uids: List<String>) {
        for (u in uids.distinct().take(20)) if (u.isNotEmpty() && asked.add(u) && !map.containsKey(u))
            runCatching { api.profile(u) }.getOrNull()?.let { map[u] = it }
    }
    fun get(uid: String): Person? = map[uid]
    fun put(uid: String, p: Person) { map[uid] = p }
}
