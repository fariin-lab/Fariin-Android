package com.fariin.app.ui.channels

import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import com.fariin.app.ui.components.linkShake
import androidx.compose.ui.focus.focusRequester
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
    val ctx = androidx.compose.ui.platform.LocalContext.current.applicationContext
    // Explore (iOS explore-fast, owner 2026-11-11): Channels | Groups. Each tab draws its last list
    // (kept on the phone per account) on the first frame; the server's answer replaces it in place.
    var groupsTab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var rows by remember { mutableStateOf(com.fariin.app.channel.ExploreCache.cachedChannels(ctx, me) ?: emptyList()) }
    var groupRows by remember { mutableStateOf(com.fariin.app.channel.ExploreCache.cachedGroups(ctx, me) ?: emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var groupsLoading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var groupsFailed by remember { mutableStateOf(false) }
    // A skeleton only when nothing was kept and the answer is not back within 0.3 s.
    var skeleton by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    val justFollowed = remember { mutableStateListOf<String>() }
    val justJoined = remember { mutableStateListOf<String>() }
    // The Join pill's own states (JoinPill.kt): waiting on the server, requested, refusals so far.
    val joiningNow = remember { mutableStateListOf<String>() }
    val requestedNow = remember { mutableStateListOf<String>() }
    val joinShakes = remember { androidx.compose.runtime.mutableStateMapOf<String, Int>() }
    // A group left from its page (back here, owner 2026-11-11) shows Join again at once.
    val leaving by com.fariin.app.data.LeaveUndo.hidden.collectAsState()
    LaunchedEffect(leaving) { justJoined.removeAll { it in leaving } }
    var groupOpen by remember { mutableStateOf<String?>(null) }
    val entries by ChannelFollows.entries.collectAsState()
    val bias by ChannelFollows.biasFlow.collectAsState()
    var query by remember { mutableStateOf("") }
    // A keyboard left up by the page this came from (the chat search, owner 2026-11-10) goes down.
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    LaunchedEffect(Unit) { focus.clearFocus(force = true); keyboard?.hide() }
    LaunchedEffect(Unit) {
        // The store demo (no server): its own six channels.
        if (com.fariin.app.ui.debug.DemoChats.active) { rows = com.fariin.app.ui.debug.DemoSettings.channels; failed = false; loading = false; return@LaunchedEffect }
        try { rows = com.fariin.app.channel.ExploreCache.fetchChannels(ctx, me); failed = false } catch (_: Exception) { failed = true }
        loading = false
    }
    LaunchedEffect(groupsTab) {
        if (!groupsTab) return@LaunchedEffect
        try { groupRows = com.fariin.app.channel.ExploreCache.fetchGroups(ctx, me); groupsFailed = false } catch (_: Exception) { groupsFailed = true }
        groupsLoading = false
    }
    LaunchedEffect(groupsTab) { skeleton = false; delay(300); skeleton = true }
    toast?.let { t -> LaunchedEffect(t) { delay(2500); toast = null } }
    Box(Modifier.fillMaxSize().background(c.bgSecondary)) {
        com.fariin.app.ui.components.GlassColumnPage(false, page = c.bgSecondary, bar = {
            Box(Modifier.fillMaxWidth().background(c.bgSecondary).statusBarsPadding().height(64.dp).padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center) {
                com.fariin.app.ui.components.FloatingBack(onBack, Modifier.align(Alignment.CenterStart))
                // Design C: the switch is the title, centred, the back button's width free on both sides.
                ExploreSwitch(groupsTab, Modifier.padding(horizontal = 56.dp)) { g -> if (g != groupsTab) { groupsTab = g; query = "" } }
            }
        }) { top ->
            Spacer(Modifier.height(top))
            com.fariin.app.ui.people.SearchField(query, if (groupsTab) "Search groups" else "Search channels") { query = it }
            val q = query.trim().removePrefix("@")
            // Plus the server's public search for what the ranked list does not carry.
            var serverHits by remember { mutableStateOf<List<com.fariin.app.data.PublicGroupHit>>(emptyList()) }
            LaunchedEffect(q, groupsTab) {
                serverHits = emptyList()
                if (q.length < 3) return@LaunchedEffect
                delay(300)   // one ask per pause in typing
                serverHits = runCatching { com.fariin.app.data.PublicGroups.search(q) }.getOrDefault(emptyList())
                    .filter { it.isChannel != groupsTab }
            }
            if (!groupsTab) {
                val local = if (q.isEmpty()) rows else rows.filter { it.title.contains(q, ignoreCase = true) || it.handle.contains(q, ignoreCase = true) }
                val shown = local + serverHits.filter { h -> local.none { it.cid == h.cid } }
                    .map { DiscoveredChannel(it.cid, it.handle, it.title, it.photoUrl, "", it.memberCount, verified = it.verified) }
                when {
                    loading && rows.isEmpty() -> if (skeleton) ExploreSkeleton(squircle = true)
                    failed && rows.isEmpty() -> Note("Couldn't load channels. Check your connection and try again.")
                    rows.isEmpty() && shown.isEmpty() -> Note("No public channels yet. Only public channels show here: to list yours, open it, tap its name, then Channel Settings > Channel Type > Public.")
                    shown.isEmpty() -> Note("No channels match “${query.trim()}”.")
                    else -> LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
                        items(shown, key = { it.cid }) { r ->
                            @Suppress("UNUSED_VARIABLE") val b = bias.size
                            // Followed, just followed, or one I run (owner/admin: in its `users`, no follow entry).
                            val mine = r.cid !in leaving && (entries.containsKey(r.cid) || justFollowed.contains(r.cid) ||
                                com.fariin.app.data.CryptoHub.listedConversation(r.cid)?.users?.contains(me) == true)
                            val pill = when {
                                mine -> JoinPillState.JOINED
                                joiningNow.contains(r.cid) -> JoinPillState.JOINING
                                requestedNow.contains(r.cid) -> JoinPillState.REQUESTED
                                else -> JoinPillState.JOIN
                            }
                            DiscoverRow(r, pill, joinShakes[r.cid] ?: 0, onOpen = { onOpen(r.cid) }) {
                                // The same pill as Groups (iOS): spinner, then Following with its check;
                                // a private channel ends on a grey "Requested", no toast.
                                com.fariin.app.ui.components.SuccessHaptic.soft(ctx)   // a soft tap on Follow (owner 2026-11-13)
                                joiningNow.add(r.cid)
                                scope.launch {
                                    try {
                                        if (ChannelService.follow(r.cid) == "requested") requestedNow.add(r.cid) else justFollowed.add(r.cid)
                                    } catch (e: Exception) {
                                        joinShakes[r.cid] = (joinShakes[r.cid] ?: 0) + 1
                                        toast = joinRefusal(e) ?: ChannelService.errorText(e)
                                    } finally { joiningNow.remove(r.cid) }
                                }
                            }
                        }
                    }
                }
            } else {
                val local = if (q.isEmpty()) groupRows else groupRows.filter { it.title.contains(q, ignoreCase = true) || it.handle.contains(q, ignoreCase = true) }
                val shown = local + serverHits.filter { h -> local.none { it.cid == h.cid } }
                    .map { com.fariin.app.channel.DiscoveredGroup(it.cid, it.handle, it.title, it.photoUrl, it.memberCount, it.verified, "") }
                when {
                    groupsLoading && groupRows.isEmpty() -> if (skeleton) ExploreSkeleton(squircle = false)
                    groupsFailed && groupRows.isEmpty() -> Note("Couldn't load groups. Check your connection and try again.")
                    groupRows.isEmpty() && shown.isEmpty() -> Note("No public groups yet.")
                    shown.isEmpty() -> Note("No groups match “${query.trim()}”.")
                    else -> LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
                        items(shown, key = { it.cid }) { r ->
                            val mine = r.cid !in leaving && (justJoined.contains(r.cid) ||
                                com.fariin.app.data.CryptoHub.listedConversation(r.cid)?.users?.contains(me) == true)
                            val pill = when {
                                mine -> JoinPillState.JOINED
                                joiningNow.contains(r.cid) -> JoinPillState.JOINING
                                requestedNow.contains(r.cid) -> JoinPillState.REQUESTED
                                else -> JoinPillState.JOIN
                            }
                            GroupRow(r, pill, joinShakes[r.cid] ?: 0, onOpen = { onOpen(r.cid) }) {
                                // A spinner in the pill while the server works, then Joined with its
                                // check; a refusal shakes it back to Join with a word (owner 2026-11-11).
                                joiningNow.add(r.cid)
                                scope.launch {
                                    try {
                                        if (com.fariin.app.data.PublicGroups.join(r.cid) == "requested") requestedNow.add(r.cid)
                                        else justJoined.add(r.cid)
                                    } catch (e: Exception) {
                                        joinShakes[r.cid] = (joinShakes[r.cid] ?: 0) + 1
                                        toast = joinRefusal(e) ?: e.message ?: "Couldn't join. Try again."
                                    } finally { joiningNow.remove(r.cid) }
                                }
                            }
                        }
                    }
                }
            }
        }
        groupOpen?.let { gid -> com.fariin.app.ui.people.PublicGroupSheet(gid, onClose = { groupOpen = null },
            onOpenChat = { cid -> groupOpen = null; onOpen(cid) }, onOpenChannel = { cid -> groupOpen = null; onOpen(cid) }) }
        toast?.let {
            Text(it, fontSize = 15.sp, color = c.label, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(24.dp)
                    .clip(RoundedCornerShape(20.dp)).background(c.card).padding(horizontal = 18.dp, vertical = 12.dp))
        }
    }
}

/** "Channels | Groups" (design C): a capsule whose chosen half is a lighter pill that slides. */
@Composable
private fun ExploreSwitch(groups: Boolean, modifier: Modifier, onPick: (Boolean) -> Unit) {
    val c = Fariin.colors
    val at by androidx.compose.animation.core.animateFloatAsState(if (groups) 1f else 0f,
        androidx.compose.animation.core.spring(dampingRatio = 0.8f, stiffness = 500f), label = "exploreSwitch")
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.widthIn(max = 260.dp).fillMaxWidth().height(40.dp)
        .clip(CircleShape).background(c.fieldFill).padding(3.dp)) {
        val half = maxWidth / 2
        Box(Modifier.offset(x = half * at).width(half).fillMaxHeight().shadow(2.dp, CircleShape).clip(CircleShape)
            .background(if (c.dark) androidx.compose.ui.graphics.Color.White.copy(alpha = 0.22f) else c.card))
        Row(Modifier.fillMaxSize()) {
            listOf("Channels" to false, "Groups" to true).forEach { (label, g) ->
                Box(Modifier.weight(1f).fillMaxHeight().clip(CircleShape)
                    .clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null) { onPick(g) }
                    .semantics { selected = g == groups; role = androidx.compose.ui.semantics.Role.Tab },
                    contentAlignment = Alignment.Center) {
                    Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (g == groups) c.label else c.secondary, maxLines = 1)
                }
            }
        }
    }
}

/** Grey rows in the real rows' shape while nothing is kept yet. */
@Composable
private fun ExploreSkeleton(squircle: Boolean) {
    Column(Modifier.padding(top = 4.dp)) {
        repeat(6) { i ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                com.fariin.app.ui.components.SkeletonBlock(52.dp, 52.dp, radius = if (squircle) 16.dp else 26.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    com.fariin.app.ui.components.SkeletonBlock(listOf(130, 100, 160, 115)[i % 4].dp, 14.dp)
                    Spacer(Modifier.height(8.dp))
                    com.fariin.app.ui.components.SkeletonBlock(80.dp, 11.dp)
                }
            }
        }
    }
}

/** A public group in Explore: round photo, name, "N members", Join (grey "Joined" once in). */
@Composable
private fun GroupRow(r: com.fariin.app.channel.DiscoveredGroup, pill: JoinPillState, shake: Int, onOpen: () -> Unit, onJoin: () -> Unit) {
    val c = Fariin.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).clickable(onClick = onOpen), verticalAlignment = Alignment.CenterVertically) {
            Avatar(r.photoUrl, 52.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (r.verified) {
                        Spacer(Modifier.width(4.dp))
                        com.fariin.app.ui.components.VerifiedSeal(c.blue, Modifier.size(15.dp), "Verified")
                    }
                }
                Text(if (r.memberCount == 1) "1 member" else "${r.memberCount} members", fontSize = 15.sp, color = c.secondary)
            }
        }
        Spacer(Modifier.width(8.dp))
        JoinPill(pill, shake, onClick = onJoin)
    }
}

/** No connection (offline, server unreachable, too slow): one plain line (iOS). Else null. */
private fun joinRefusal(e: Exception): String? {
    val code = (e as? com.google.firebase.functions.FirebaseFunctionsException)?.code
    val offline = e is java.io.IOException || e.cause is java.io.IOException ||
        code == com.google.firebase.functions.FirebaseFunctionsException.Code.UNAVAILABLE ||
        code == com.google.firebase.functions.FirebaseFunctionsException.Code.DEADLINE_EXCEEDED
    return if (offline) "No connection. Try again." else null
}

@Composable
private fun DiscoverRow(r: DiscoveredChannel, pill: JoinPillState, shake: Int, onOpen: () -> Unit, onFollow: () -> Unit) {
    val c = Fariin.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).clickable(onClick = onOpen), verticalAlignment = Alignment.CenterVertically) {
            // A channel's face is its rounded square everywhere (owner 2026-11-10).
            Avatar(r.photoUrl, 52.dp, squircle = true, kind = com.fariin.app.ui.components.AvatarKind.CHANNEL)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (r.verified) {
                        Spacer(Modifier.width(4.dp))
                        com.fariin.app.ui.components.VerifiedSeal(c.blue, Modifier.size(15.dp), "Verified")
                    }
                    if (r.official) {
                        Spacer(Modifier.width(4.dp))
                        Text("Official", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = c.secondary, maxLines = 1)
                    }
                }
                Text(ChannelRules.followersLine(ChannelFollows.adjusted(r.cid, r.followerCount)), fontSize = 15.sp, color = c.secondary)
            }
        }
        Spacer(Modifier.width(8.dp))
        // Solid action capsule (black light / white dark); once following, a grey capsule, not tappable.
        JoinPill(pill, shake, channel = true, onClick = onFollow)
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
    // The phone's Back on the form goes to the intro, as the bar's arrow does (owner 2026-11-09: it
    // left the whole flow and skipped the drawing).
    androidx.activity.compose.BackHandler(enabled = step == 1) { if (!creating) step = 0 }
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
    // Dark mode: the art without the white sticker edge (owner 2026-11-09). Picked by the app's own
    // Light/Dark choice, not a night resource folder, which would follow the phone instead.
    val dark = com.fariin.app.data.AppearancePrefs.isDark()
    Box(modifier.heightIn(max = 300.dp).aspectRatio(1329f / 1657f, matchHeightConstraintsFirst = true)) {
        androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(if (dark) R.drawable.channel_intro_base_dark else R.drawable.channel_intro_base), null,
            contentScale = androidx.compose.ui.layout.ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
        androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(if (dark) R.drawable.channel_intro_bouquet_dark else R.drawable.channel_intro_bouquet), null,
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
private fun BigButton(text: String, enabled: Boolean, busy: Boolean = false, tappableWhenOff: Boolean = false, onClick: () -> Unit) {
    val c = Fariin.colors
    Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).height(52.dp).clip(CircleShape)
        .background(if (enabled) c.accent else c.accent.copy(alpha = 0.4f)).clickable(enabled = (enabled || tappableWhenOff) && !busy, onClick = onClick),
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
    // Owner 2026-11-09 (iOS GroupTypeView): Public pre-fills a link and brings the keyboard up; a
    // Done tapped too early shakes the field, buzzes and says, in red, that a link is needed.
    val linkFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    var askedForLink by remember { mutableStateOf(false) }
    var nudges by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
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
                TypeRow("Public Channel", public) {
                    public = true
                    if (link.isEmpty()) {
                        link = ChannelRules.suggestLink(title, "channel")
                        scope.launch { delay(350); runCatching { linkFocus.requestFocus() } }
                    }
                }
                HorizontalDivider(thickness = 0.5.dp, color = c.separator, modifier = Modifier.padding(start = 16.dp))
                TypeRow("Private Channel", !public) { public = false }
            }
            Foot(ChannelRules.typeFooter(public))
            if (public) {
                SectionTitle("PUBLIC LINK")
                Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth().linkShake(nudges).clip(RoundedCornerShape(20.dp)).background(c.card).padding(16.dp)) {
                    // The label colour: grey read as a placeholder, not the start of the link.
                    Text("fariin.com/", fontSize = 17.sp, color = c.label)
                    Box(Modifier.weight(1f)) {
                        if (link.isEmpty()) Text("link", fontSize = 17.sp, color = c.secondary)
                        BasicTextField(link, { link = it.trim() }, singleLine = true, textStyle = TextStyle(fontSize = 17.sp, color = c.label),
                            cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth().focusRequester(linkFocus))
                    }
                }
                // The check as one ring on a line under the field (iOS HandleCheckRing).
                val ln = linkNote
                if (ln != null && link.isNotEmpty()) com.fariin.app.ui.components.CheckLine(
                    when { ln == "Checking…" -> com.fariin.app.ui.components.CheckPhase.Checking
                        linkOk -> com.fariin.app.ui.components.CheckPhase.Free else -> com.fariin.app.ui.components.CheckPhase.Taken },
                    if (ln == "Checking…") "Checking link…" else ln, Modifier.padding(horizontal = 32.dp, vertical = 6.dp))
                else if (link.isEmpty()) Foot("A public channel needs a link. Pick one of ${ChannelRules.LINK_MIN} to ${ChannelRules.LINK_MAX} characters: letters, numbers and _.",
                    if (askedForLink) c.red else null)
                else Foot(linkNote ?: "Pick a link of ${ChannelRules.LINK_MIN} to ${ChannelRules.LINK_MAX} characters: letters, numbers and _.",
                    if (linkOk) Color(0xFF34C759) else if (askedForLink && linkNote != null) c.red else null)
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
        // Public needs a good link (iOS): before that, Done explains instead of quietly finishing private.
        BigButton("Done", enabled = !public || linkOk, busy = saving, tappableWhenOff = public) {
            if (!public) { onDone(); return@BigButton }
            if (!linkOk) {
                askedForLink = true; nudges++
                haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                runCatching { linkFocus.requestFocus() }
                return@BigButton
            }
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
