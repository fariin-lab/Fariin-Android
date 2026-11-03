package com.fariin.app.ui.channels

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.channel.ChannelFollows
import com.fariin.app.channel.ChannelRules
import com.fariin.app.channel.ChannelService
import com.fariin.app.channel.DiscoveredChannel
import androidx.compose.foundation.layout.statusBarsPadding
import com.fariin.app.ui.components.Avatar
import com.fariin.app.ui.settings.PageBar
import com.fariin.app.ui.theme.Fariin
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * FIND CHANNELS (Kulan-iOS DiscoverChannelsView): public channels in the server's ranked order,
 * the official one ("Fariin News", @fariin, verified) first with its "Official" mark, then this
 * country's, then everyone's. A tap opens the channel to look at before following; Follow follows
 * from the list and turns into a grey "Following".
 */
@Composable
fun FindChannelsScreen(me: String, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val c = Fariin.colors
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<DiscoveredChannel>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    val justFollowed = remember { mutableStateListOf<String>() }
    val entries by ChannelFollows.entries.collectAsState()
    val bias by ChannelFollows.biasFlow.collectAsState()
    LaunchedEffect(Unit) {
        try { rows = ChannelService.discover(); failed = false } catch (_: Exception) { failed = true }
        loading = false
    }
    toast?.let { t -> LaunchedEffect(t) { delay(2500); toast = null } }
    Box(Modifier.fillMaxSize().background(c.bgSecondary).statusBarsPadding()) {
        Column(Modifier.fillMaxSize()) {
            com.fariin.app.ui.components.FloatingTopBar("Find Channels", onBack = onBack, container = c.bgSecondary)
            when {
                loading && rows.isEmpty() -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = c.secondary, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                }
                failed && rows.isEmpty() -> Note("Couldn't load channels. Check your connection and try again.")
                rows.isEmpty() -> Note("No public channels yet. Only public channels show here: to list yours, open it, tap its name, then Channel Settings > Channel Type > Public.")
                else -> LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
                    item {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(20.dp)).background(c.card)) {
                            rows.forEachIndexed { i, r ->
                                if (i > 0) HorizontalDivider(thickness = 0.5.dp, color = c.separator, modifier = Modifier.padding(start = 76.dp))
                                @Suppress("UNUSED_VARIABLE") val b = bias.size
                                val mine = entries.containsKey(r.cid) || justFollowed.contains(r.cid)
                                DiscoverRow(r, mine, onOpen = { onOpen(r.cid) }) {
                                    justFollowed.add(r.cid)
                                    scope.launch {
                                        try {
                                            if (ChannelService.follow(r.cid) == "requested") { justFollowed.remove(r.cid); toast = ChannelRules.REQUEST_SENT }
                                        } catch (e: Exception) { justFollowed.remove(r.cid); toast = ChannelService.errorText(e) }
                                    }
                                }
                            }
                        }
                        Text("Public channels anyone can follow. Only their admins post. Active channels from your country come first.",
                            fontSize = 13.sp, color = c.secondary, modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp))
                    }
                }
            }
        }
        toast?.let {
            Text(it, fontSize = 15.sp, color = c.label, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(24.dp)
                    .clip(RoundedCornerShape(20.dp)).background(c.card).padding(horizontal = 18.dp, vertical = 12.dp))
        }
    }
}

@Composable
private fun DiscoverRow(r: DiscoveredChannel, mine: Boolean, onOpen: () -> Unit, onFollow: () -> Unit) {
    val c = Fariin.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).clickable(onClick = onOpen), verticalAlignment = Alignment.CenterVertically) {
            Avatar(r.photoUrl, 48.dp, squircle = true)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (r.verified) {
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Filled.Verified, contentDescription = "Verified", tint = c.blue, modifier = Modifier.size(15.dp))
                    }
                    if (r.official) {
                        Spacer(Modifier.width(4.dp))
                        Text("Official", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = c.secondary, maxLines = 1)
                    }
                }
                Text(ChannelRules.followersLine(ChannelFollows.adjusted(r.cid, r.followerCount)), fontSize = 15.sp, color = c.secondary)
                if (r.description.isNotEmpty()) {
                    Text(r.description, fontSize = 15.sp, color = c.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.height(32.dp).clip(CircleShape).background(if (mine) c.bgSecondary else c.blue)
                .clickable(enabled = !mine, onClick = onFollow).padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) { Text(if (mine) "Following" else "Follow", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (mine) c.secondary else Color.White) }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, fontSize = 15.sp, color = Fariin.colors.secondary, modifier = Modifier.fillMaxWidth().padding(24.dp))
}

/**
 * NEW CHANNEL (Kulan-iOS NewChannelView + GroupTypeView for a channel): the intro ("Start a
 * channel"), the name and description, then the channel's type: Public with its fariin.com/ link,
 * or Private with its invite link and Approve New Followers; Protect Content on both.
 */
@Composable
fun NewChannelScreen(me: String, onBack: () -> Unit, onDone: (String) -> Unit) {
    var step by remember { mutableStateOf(0) }
    var name by remember { mutableStateOf("") }
    var about by remember { mutableStateOf("") }
    var cid by remember { mutableStateOf<String?>(null) }
    var halfMade by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    when (step) {
        0 -> Intro(onBack) { step = 1 }
        1 -> Details(name, about, creating, error, onName = { name = it.take(ChannelRules.NAME_MAX) },
            onAbout = { about = it.take(ChannelRules.ABOUT_MAX) }, onBack = { if (!creating) step = 0 }) {
            if (creating || name.isBlank()) return@Details
            creating = true; error = null
            scope.launch {
                try {
                    val my = FirebaseFirestore.getInstance().collection("users").document(me).get().await()
                    val id = halfMade ?: ChannelService.createGroupForChannel(name, my.getString("name") ?: "", my.getString("photoUrl")).also { halfMade = it }
                    if (!ChannelService.makeChannelWithRetry(id)) { error = "Could not create the channel. Try again."; creating = false; return@launch }
                    halfMade = null
                    try { ChannelService.setDescription(id, about) } catch (_: Exception) {}
                    try { ChannelService.createPrimaryLink(id) } catch (_: Exception) {}
                    cid = id; creating = false; step = 2
                } catch (_: Exception) { error = "Could not create the channel. Try again."; creating = false }
            }
        }
        else -> cid?.let { TypePage(it, name) { onDone(it) } }
    }
}

@Composable
private fun Intro(onBack: () -> Unit, onNext: () -> Unit) {
    val c = Fariin.colors
    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding()) {
        com.fariin.app.ui.components.FloatingTopBar("", onBack = onBack)
        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            // A made-up channel post, as the iOS intro shows one (ChannelIntroPreview).
            Column(Modifier.width(260.dp).clip(RoundedCornerShape(20.dp)).background(c.bgSecondary).padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(c.blue.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Campaign, null, tint = c.blue, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("Your channel", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.label)
                        Text("12.4K followers", fontSize = 11.sp, color = c.secondary)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("Golden hour on the coast tonight", fontSize = 15.sp, color = c.label)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Visibility, null, tint = c.secondary, modifier = Modifier.size(10.dp))
                    Spacer(Modifier.width(2.dp))
                    Text("9.1K", fontSize = 11.sp, color = c.secondary)
                }
            }
            Spacer(Modifier.height(22.dp))
            Text("Start a channel", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = c.label)
            Spacer(Modifier.height(10.dp))
            Text("Share news, photos and videos with as many people as you like.", fontSize = 17.sp, color = c.secondary, textAlign = TextAlign.Center)
        }
        Text("By creating a channel you agree to the Channel Guidelines.", fontSize = 13.sp, color = c.secondary, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp))
        Spacer(Modifier.height(12.dp))
        BigButton("Create Channel", enabled = true, onClick = onNext)
    }
}

@Composable
private fun BigButton(text: String, enabled: Boolean, busy: Boolean = false, onClick: () -> Unit) {
    val c = Fariin.colors
    Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).height(52.dp).clip(CircleShape)
        .background(if (enabled) c.blue else c.blue.copy(alpha = 0.4f)).clickable(enabled = enabled && !busy, onClick = onClick),
        contentAlignment = Alignment.Center) {
        if (busy) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        else Text(text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
    }
}

@Composable
private fun Field(value: String, placeholder: String, single: Boolean, onChange: (String) -> Unit) {
    val c = Fariin.colors
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        if (value.isEmpty()) Text(placeholder, fontSize = 17.sp, color = c.tertiary)
        BasicTextField(value, onChange, singleLine = single, textStyle = TextStyle(fontSize = 17.sp, color = c.label),
            cursorBrush = SolidColor(c.blue), modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun Details(name: String, about: String, creating: Boolean, error: String?, onName: (String) -> Unit,
                    onAbout: (String) -> Unit, onBack: () -> Unit, onNext: () -> Unit) {
    val c = Fariin.colors
    Column(Modifier.fillMaxSize().background(c.bgSecondary).statusBarsPadding().imePadding().navigationBarsPadding()) {
        com.fariin.app.ui.components.FloatingTopBar("New Channel", onBack = onBack, container = c.bgSecondary)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(16.dp).clip(RoundedCornerShape(20.dp)).background(c.card)) {
                Field(name, "Channel name", true, onName)
                HorizontalDivider(thickness = 0.5.dp, color = c.separator, modifier = Modifier.padding(start = 16.dp))
                Field(about, "Description", false, onAbout)
            }
            Text("Optional. Tell people what your channel is about.", fontSize = 13.sp, color = c.secondary,
                modifier = Modifier.padding(horizontal = 32.dp))
            error?.let { Text(it, fontSize = 14.sp, color = c.red, modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp)) }
        }
        BigButton("Next", enabled = name.isNotBlank(), busy = creating, onClick = onNext)
    }
}

/** GroupTypeView for a new channel: Public / Private, the link, Approve New Followers, Protect Content. */
@Composable
private fun TypePage(cid: String, title: String, onDone: () -> Unit) {
    val c = Fariin.colors
    val scope = rememberCoroutineScope()
    var public by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf("") }
    var linkNote by remember { mutableStateOf<String?>(null) }
    var linkOk by remember { mutableStateOf(false) }
    var primary by remember { mutableStateOf<ChannelService.Link?>(null) }
    var approve by remember { mutableStateOf(false) }
    var protect by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(cid) { primary = try { ChannelService.primaryLink(cid) } catch (_: Exception) { null }; approve = primary?.requestApproval ?: false }
    LaunchedEffect(link, public) {
        linkOk = false
        if (!public || link.isEmpty()) { linkNote = null; return@LaunchedEffect }
        ChannelRules.linkProblem(link)?.let { linkNote = it; return@LaunchedEffect }
        linkNote = "Checking..."
        delay(400)
        val r = ChannelService.checkLink(cid, link)
        linkNote = r ?: "fariin.com/$link is available."
        linkOk = r == null
    }
    Column(Modifier.fillMaxSize().background(c.bgSecondary).statusBarsPadding().imePadding().navigationBarsPadding()) {
        com.fariin.app.ui.components.FloatingTopBar("Channel Type", container = c.bgSecondary)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            SectionTitle("CHANNEL TYPE")
            Column(Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(20.dp)).background(c.card)) {
                TypeRow("Public Channel", public) { public = true }
                HorizontalDivider(thickness = 0.5.dp, color = c.separator, modifier = Modifier.padding(start = 16.dp))
                TypeRow("Private Channel", !public) { public = false }
            }
            Foot(ChannelRules.typeFooter(public))
            if (public) {
                SectionTitle("PUBLIC LINK")
                Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.card).padding(16.dp)) {
                    Text("fariin.com/", fontSize = 17.sp, color = c.secondary)
                    BasicTextField(link, { link = it.trim() }, singleLine = true, textStyle = TextStyle(fontSize = 17.sp, color = c.label),
                        cursorBrush = SolidColor(c.blue), modifier = Modifier.weight(1f))
                }
                Foot(linkNote ?: "Pick a link of ${ChannelRules.LINK_MIN} to ${ChannelRules.LINK_MAX} characters: letters, numbers and _.",
                    if (linkOk) Color(0xFF34C759) else null)
            } else {
                SectionTitle("INVITE LINK")
                Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.card).padding(16.dp)) {
                    Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label)
                    Text(primary?.let { "fariin.com/g/${it.code}" } ?: "This channel has no invite link on.", fontSize = 15.sp, color = c.secondary)
                }
                Foot("People can follow your channel with this link. You can reset it any time.")
                if (primary != null) {
                    ToggleRow("Approve New Followers", approve) { v ->
                        approve = v
                        scope.launch {
                            try { ChannelService.setApproval(cid, primary!!.code, v)?.let { primary = it; approve = it.requestApproval } }
                            catch (_: Exception) { approve = !v; problem = "Couldn't change that. Try again." }
                        }
                    }
                    Foot("Turn this on and people who open the link ask first; an admin approves them.")
                }
            }
            SectionTitle("SAVING AND COPYING")
            ToggleRow("Protect Content", protect) { v ->
                protect = v
                scope.launch { try { ChannelService.setProtected(cid, v) } catch (_: Exception) { protect = !v; problem = "Couldn't save. Try again." } }
            }
            Foot(ChannelRules.protectFooter(protect))
            problem?.let { Foot(it, c.red) }
        }
        BigButton("Done", enabled = !public || link.isEmpty() || linkOk, busy = saving) {
            if (!public || link.isEmpty()) { onDone(); return@BigButton }
            saving = true
            scope.launch {
                try { ChannelService.setLink(cid, link); onDone() }
                catch (e: Exception) { problem = ChannelService.errorText(e, "Couldn't save the link. Try again."); saving = false }
            }
        }
    }
}

@Composable
private fun SectionTitle(t: String) =
    Text(t, fontSize = 13.sp, color = Fariin.colors.secondary, modifier = Modifier.padding(start = 32.dp, top = 18.dp, bottom = 6.dp))

@Composable
private fun Foot(t: String, color: Color? = null) =
    Text(t, fontSize = 13.sp, color = color ?: Fariin.colors.secondary, modifier = Modifier.padding(horizontal = 32.dp, vertical = 6.dp))

@Composable
private fun TypeRow(title: String, selected: Boolean, onClick: () -> Unit) {
    val c = Fariin.colors
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 17.sp, color = c.label, modifier = Modifier.weight(1f))
        if (selected) Icon(Icons.Filled.Check, contentDescription = "Selected", tint = c.label, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun ToggleRow(title: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val c = Fariin.colors
    Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.card)
        .padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 17.sp, color = c.label, modifier = Modifier.weight(1f))
        Switch(on, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = com.fariin.app.ui.theme.Fariin.colors.blue, checkedThumbColor = Color.White))
    }
}
