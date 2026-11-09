package com.fariin.app.ui.channels

import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.draw.drawWithContent
import com.fariin.app.R
import com.fariin.app.data.NewBadgeSpots
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
    Box(Modifier.fillMaxSize().background(c.bgSecondary)) {
        com.fariin.app.ui.components.FloatingBarPage("Find Channels", onBack, page = c.bgSecondary) { top ->
            androidx.compose.foundation.layout.Spacer(Modifier.height(top))
            when {
                loading && rows.isEmpty() -> com.fariin.app.ui.components.LoadingRow(Modifier.padding(vertical = 14.dp))
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
            Avatar(r.photoUrl, 48.dp, squircle = true, kind = com.fariin.app.ui.components.AvatarKind.CHANNEL)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (r.verified) {
                        Spacer(Modifier.width(4.dp))
                        Icon(ImageVector.vectorResource(R.drawable.ic_sf_checkmark_seal_fill), contentDescription = "Verified", tint = c.blue, modifier = Modifier.size(15.dp))
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
            Modifier.height(32.dp).clip(CircleShape).background(if (mine) c.bgSecondary else c.accent)
                .clickable(enabled = !mine, onClick = onFollow).padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) { Text(if (mine) "Following" else "Follow", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (mine) c.secondary else c.onAccent) }
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
    var photo by remember { mutableStateOf<ByteArray?>(null) }
    val scope = rememberCoroutineScope()
    val appCtx = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val picker = com.fariin.app.ui.photo.rememberCirclePhotoPicker { photo = it }
    if (picker.pending != null) { picker.Host("Channel photo", "Shown in chats and on the channel page"); return }
    when (step) {
        0 -> Intro(onBack) { step = 1 }
        1 -> Details(name, about, creating, error, photo, picker, onRemovePhoto = { photo = null },
            onName = { name = it.take(ChannelRules.NAME_MAX) },
            onAbout = { about = it.take(ChannelRules.ABOUT_MAX) }, onBack = { if (!creating) step = 0 }) {
            if (creating || name.isBlank()) return@Details
            creating = true; error = null
            scope.launch {
                // The same "at most N owned" check a new group asks; a channel counts as one (iOS
                // NewChannelView). Not when the group half is already made: it is counted already.
                if (halfMade == null) com.fariin.app.data.AppLimits.ownedGroupsFull()?.let { max ->
                    error = com.fariin.app.data.AppLimits.ownedChannelsSentence(max); creating = false; return@launch
                }
                try {
                    val my = FirebaseFirestore.getInstance().collection("users").document(me).get().await()
                    val id = halfMade ?: ChannelService.createGroupForChannel(name, my.getString("name") ?: "", my.getString("photoUrl")).also { halfMade = it }
                    if (!ChannelService.makeChannelWithRetry(id)) { error = "Could not create the channel. Try again."; creating = false; return@launch }
                    halfMade = null
                    // The rest is best-effort: the channel stands without it, but the user is told what is missing.
                    val missed = mutableListOf<String>()
                    try { ChannelService.setDescription(id, about) } catch (_: Exception) { if (about.isNotBlank()) missed += "description" }
                    // Reactions start ON with the default set (iOS 2dc585e8, owner 2026-11-07), the page's switch.
                    try { com.fariin.app.data.ChannelReactions.save(id, "some", com.fariin.app.data.ChannelReactions.defaultSet) }
                    catch (e: Exception) { android.util.Log.w("NewChannel", "default reactions not saved", e); missed += "reactions" }
                    try { ChannelService.createPrimaryLink(id) } catch (_: Exception) { missed += "invite link" }
                    photo?.let { ph -> try { com.fariin.app.data.FirestorePeople(me, com.fariin.app.data.CryptoHub.get(appCtx)).setGroupPhoto(id, ph) } catch (_: Exception) { missed += "photo" } }
                    cid = id; creating = false; step = 2
                    if (missed.isNotEmpty()) {
                        val what = when (missed.size) { 1 -> "the ${missed[0]}"; 2 -> "the ${missed[0]} and ${missed[1]}"; else -> "the " + missed.dropLast(1).joinToString(", ") + " and ${missed.last()}" }
                        val them = if (missed.size == 1) "it" else "them"
                        com.fariin.app.ui.components.Snack.show("Channel created, but $what couldn't be saved. Add $them in channel settings.", long = true)
                    }
                } catch (_: Exception) { error = "Could not create the channel. Try again."; creating = false }
            }
        }
        else -> cid?.let { TypePage(it, name) { onDone(it) } }
    }
}

@Composable
private fun Intro(onBack: () -> Unit, onNext: () -> Unit) {
    val c = Fariin.colors
    com.fariin.app.ui.components.FloatingBarPage("", onBack, modifier = Modifier.background(c.bg).navigationBarsPadding()) { top ->
        androidx.compose.foundation.layout.Spacer(Modifier.height(top))
        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            // The channel drawing (owner 2026-11-09: a phone planted in a pot, posts growing out of it,
            // being watered). Straight on the page in both modes: its own white sticker edge outlines it
            // on dark; no card, tint or shadow. Fitted, never cropped, about 300 dp tall.
            ChannelIntroArt(Modifier.weight(1f, fill = false))
            Spacer(Modifier.height(22.dp))
            Text("Start a channel", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = c.label)
            Spacer(Modifier.height(10.dp))
            Text("Plant an idea. Watch it grow.", fontSize = 17.sp, color = c.secondary, textAlign = TextAlign.Center, maxLines = 1)
        }
        // The guidelines link, as the form page and iOS (it opens the help page).
        val introCtx = androidx.compose.ui.platform.LocalContext.current
        Text(androidx.compose.ui.text.buildAnnotatedString {
            append("By creating a channel you agree to the ")
            pushStyle(androidx.compose.ui.text.SpanStyle(color = c.label))
            append("Channel Guidelines")
            pop()
            append(".")
        }, fontSize = 12.sp, color = c.secondary, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).clickable {
                runCatching { introCtx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://help.fariin.com/channels/channel-guidelines"))) }
            })
        // Just above the button (owner 2026-11-09).
        Spacer(Modifier.height(2.dp))
        BigButton("Create Channel", enabled = true, onClick = onNext)
    }
}

/**
 * The channel drawing in two layers (owner 2026-11-09): the table, pot and woman still, the plant of
 * posts swaying gently over them. The sway is one skew of the top layer about the soil line (y 920 of
 * 1657), so the bottom row never moves and there is no seam: a row at y shifts by s * (920 - y) / 920,
 * s a sine of +/-18 image px over 4 s. One GPU transform a frame, only while the page shows; still
 * when the phone's animations are off.
 */
@Composable
private fun ChannelIntroArt(modifier: Modifier = Modifier) {
    val moving = com.fariin.app.ui.thread.systemAnimationsOn()
    val phase = if (moving) {
        val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "sway")
        t.animateFloat(0f, 1f, androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(4000, easing = androidx.compose.animation.core.LinearEasing)), label = "swayPhase")
    } else null
    Box(modifier.heightIn(max = 300.dp).aspectRatio(1329f / 1657f, matchHeightConstraintsFirst = true)) {
        androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.channel_intro_base), null,
            contentScale = androidx.compose.ui.layout.ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
        androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.channel_intro_bouquet), null,
            contentScale = androidx.compose.ui.layout.ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize().drawWithContent {
                val p = phase?.value ?: 0f
                val k = 18f * kotlin.math.sin(p * 2f * kotlin.math.PI.toFloat()) / 920f   // the skew, image px / image px
                val pivotY = size.height * (920f / 1657f)
                drawIntoCanvas { cv ->
                    cv.save()
                    cv.translate(0f, pivotY); cv.skew(-k, 0f); cv.translate(0f, -pivotY)
                    drawContent()
                    cv.restore()
                }
            })
    }
}

@Composable
private fun BigButton(text: String, enabled: Boolean, busy: Boolean = false, onClick: () -> Unit) {
    val c = Fariin.colors
    Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).height(52.dp).clip(CircleShape)
        .background(if (enabled) c.accent else c.accent.copy(alpha = 0.4f)).clickable(enabled = enabled && !busy, onClick = onClick),
        contentAlignment = Alignment.Center) {
        if (busy) com.fariin.app.ui.components.ButtonSpinner(c.onAccent)
        else Text(text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.onAccent)
    }
}

@Composable
private fun Field(value: String, placeholder: String, single: Boolean, onChange: (String) -> Unit) {
    val c = Fariin.colors
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        if (value.isEmpty()) Text(placeholder, fontSize = 17.sp, color = c.tertiary)
        BasicTextField(value, onChange, singleLine = single, textStyle = TextStyle(fontSize = 17.sp, color = c.label),
            cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth())
    }
}

/**
 * iOS NewChannelView's details page (owner screenshot 2026-11-04): Next at the top end; one card
 * with the squircle photo slot (camera) beside Channel Name; Description in its own card; the
 * "Optional" note and the Channel Guidelines line under them.
 */
@Composable
private fun Details(name: String, about: String, creating: Boolean, error: String?,
                    photo: ByteArray?, picker: com.fariin.app.ui.photo.CirclePhotoPicker, onRemovePhoto: () -> Unit,
                    onName: (String) -> Unit, onAbout: (String) -> Unit, onBack: () -> Unit, onNext: () -> Unit) {
    val c = Fariin.colors
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var photoMenu by remember { mutableStateOf(false) }
    val barScroll = rememberScrollState()
    com.fariin.app.ui.components.FloatingBarPage("New Channel", onBack, under = com.fariin.app.ui.components.underBar(barScroll), page = c.bgSecondary,
        modifier = Modifier.imePadding().navigationBarsPadding(), actions = {
        com.fariin.app.ui.components.FloatingTextAction("Next", enabled = name.isNotBlank() && !creating, busy = creating, onClick = onNext)
    }) { top ->
        Column(Modifier.weight(1f).verticalScroll(barScroll).padding(top = top)) {
            Row(Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.card).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                val shape = RoundedCornerShape(16.dp)
                Box {
                    Box(Modifier.size(56.dp).clip(shape).background(c.fieldFill).clickable(onClickLabel = "Set Channel Photo") { photoMenu = true },
                        contentAlignment = Alignment.Center) {
                        val bmp = remember(photo) { photo?.let { com.fariin.app.media.BitmapDecode.fromBytes(it, 200)?.asImageBitmap() } }
                        if (bmp != null) androidx.compose.foundation.Image(bmp, "Channel photo",
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        else Icon(androidx.compose.ui.res.painterResource(com.fariin.app.R.drawable.ic_group_camera), "Set Channel Photo",
                            tint = c.label, modifier = Modifier.size(26.dp))
                    }
                    com.fariin.app.ui.components.GlassDropdown(expanded = photoMenu, onDismissRequest = { photoMenu = false }) {
                        androidx.compose.material3.DropdownMenuItem(text = { Text("Take Photo") }, onClick = { photoMenu = false; picker.takePhoto() })
                        androidx.compose.material3.DropdownMenuItem(text = { Text("Choose Photo") }, onClick = { photoMenu = false; picker.launch() })
                        if (photo != null) androidx.compose.material3.DropdownMenuItem(text = { Text("Remove Photo", color = c.red) },
                            onClick = { photoMenu = false; onRemovePhoto() })
                    }
                }
                Box(Modifier.weight(1f)) { Field(name, "Channel Name", true, onName) }
            }
            Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.card)) {
                Field(about, "Description", false, onAbout)
            }
            Text("Optional. Tell people what your channel is about.", fontSize = 13.sp, color = c.secondary,
                modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 8.dp))
            // The guidelines line, its link in the label colour as iOS (the link opens the help page).
            Text(androidx.compose.ui.text.buildAnnotatedString {
                append("By creating a channel you agree to the ")
                pushStyle(androidx.compose.ui.text.SpanStyle(color = c.label))
                append("Channel Guidelines")
                pop()
                append(".")
            }, fontSize = 13.sp, color = c.secondary,
                modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 6.dp).clickable {
                    runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://help.fariin.com/channels/channel-guidelines"))) }
                })
            error?.let { Text(it, fontSize = 14.sp, color = c.red, modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp)) }
        }
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
        linkNote = "Checking…"
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
                        cursorBrush = SolidColor(c.accent), modifier = Modifier.weight(1f))
                }
                // Asking the server: the ring and the words on one line under the field (iOS).
                if (linkNote == "Checking…") com.fariin.app.ui.components.LoadingLine("Checking link…",
                    Modifier.padding(horizontal = 32.dp, vertical = 6.dp))
                else Foot(linkNote ?: "Pick a link of ${ChannelRules.LINK_MIN} to ${ChannelRules.LINK_MAX} characters: letters, numbers and _.",
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
            ToggleRow("Protect Content", protect, badge = NewBadgeSpots.GROUP_SETTINGS_PROTECT_CONTENT) { v ->
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
        if (selected) Icon(ImageVector.vectorResource(R.drawable.ic_sf_checkmark), contentDescription = "Selected", tint = c.label, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun ToggleRow(title: String, on: Boolean, badge: String? = null, onChange: (Boolean) -> Unit) {
    val c = Fariin.colors
    Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.card)
        .padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (com.fariin.app.ui.components.isNew(badge)) Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 17.sp, color = c.label, modifier = Modifier.weight(1f, fill = false))
            com.fariin.app.ui.components.NewPill(Modifier.padding(start = 8.dp, end = 8.dp))
        } else Text(title, fontSize = 17.sp, color = c.label, modifier = Modifier.weight(1f))
        com.fariin.app.ui.components.FariinSwitch(on, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = com.fariin.app.ui.theme.Fariin.colors.accent, checkedThumbColor = com.fariin.app.ui.theme.Fariin.colors.onAccent))
    }
}
