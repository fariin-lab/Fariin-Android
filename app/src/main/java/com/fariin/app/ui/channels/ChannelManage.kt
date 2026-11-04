package com.fariin.app.ui.channels

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.R
import com.fariin.app.channel.ChannelAdmins
import com.fariin.app.channel.ChannelAdmins.Right
import com.fariin.app.channel.ChannelAdmins.Team
import com.fariin.app.channel.ChannelInfo
import com.fariin.app.channel.ChannelRules
import com.fariin.app.channel.ChannelService
import com.fariin.app.ui.components.Avatar
import com.fariin.app.ui.people.SearchField
import com.fariin.app.ui.settings.PageBar
import com.fariin.app.ui.settings.SettingsRow
import com.fariin.app.ui.settings.SettingsSection
import com.fariin.app.ui.settings.Trailing
import com.fariin.app.ui.theme.Fariin
import kotlinx.coroutines.launch

/*
 * Channel management for its owner and admins (Kulan-iOS GroupSettingsView channel parts,
 * ChannelFollowersView, GroupAdminsView, ChannelAdminPicker, AdminRightsView, EditGroupView):
 * Edit Channel, Followers, Administrators, an admin's rights and Dismiss Admin. The Android way:
 * pushed pages with the up arrow and the back gesture, a row's overflow menu where iOS swipes,
 * toasts where iOS shows its capsule. iOS words.
 */

private fun toast(ctx: android.content.Context, text: String) = Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()

/**
 * The admin's management rows on the channel page: Edit Channel, Followers, Administrators. One
 * rounded card like the page's others (the page already has its side gutter).
 */
@Composable
internal fun ChannelManageSection(info: ChannelInfo, team: Team, me: String, shownCount: Int, nav: ChannelManageNav) {
    // iOS channel info (owner 2026-10-18/20): Followers and one Channel Settings row; the rest of the
    // management is on that page. Follow Requests shows here too while someone is waiting.
    val reqs = com.fariin.app.ui.people.rememberJoinRequests(info.cid, allowed = team.can(me, Right.INVITE_USERS))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Fariin.colors.card)) {
        SettingsRow("Followers", icon = painterResource(R.drawable.ic_gs_members), value = "$shownCount") { nav.followers(info.cid) }
        SettingsRow("Channel Settings", icon = painterResource(R.drawable.ic_group_settings), last = reqs.isEmpty()) { nav.settings(info.cid) }
        if (reqs.isNotEmpty()) SettingsRow("Follow Requests", icon = painterResource(R.drawable.ic_gs_join_requests),
            value = com.fariin.app.data.JoinRequests.rowValue(reqs.size), last = true) { nav.requests(info.cid) }
    }
}

/** Channel Settings (iOS GroupSettingsView for a channel): the whole management list on its own page. */
@Composable
fun ChannelSettingsScreen(cid: String, me: String, onBack: () -> Unit, nav: ChannelManageNav) {
    val doc = com.fariin.app.ui.people.liveGroupDoc(cid)
    val c = Fariin.colors
    Column(Modifier.fillMaxSize().background(c.bgSecondary)) {
        PageBar("Channel Settings", onBack)
        if (doc["channel"] != true) return@Column
        val info = ChannelInfo.from(cid, doc)
        val shown = ChannelRules.subscriberCount(com.fariin.app.channel.ChannelFollows.adjusted(cid, info.followerCount), info.users.size)
        Column(Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
            val team = Team.from(doc)
            // Type and Protect Content first, as iOS GroupSettingsView's joining and privacy parts:
            // the type could not be changed after the channel was made (owner 2026-11-04).
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            var protect by androidx.compose.runtime.remember(info.restricted) { androidx.compose.runtime.mutableStateOf(info.restricted) }
            var problem by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
            if (team.isOwner(me)) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.card)) {
                    SettingsRow("Channel Type", icon = painterResource(R.drawable.ic_gs_group_type), value = if (info.isPublic) "Public" else "Private") { nav.type(cid) }
                    com.fariin.app.ui.settings.SwitchRow("Protect Content", protect, last = true) { v ->
                        protect = v; problem = null
                        scope.launch { try { ChannelService.setProtected(cid, v) } catch (_: Exception) { protect = !v; problem = "Couldn't save. Try again." } }
                    }
                }
                Text(problem ?: ChannelRules.protectFooter(protect), fontSize = 13.sp, color = if (problem != null) c.red else c.secondary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                Spacer(Modifier.height(10.dp))
            }
            ChannelManageList(info, team, me, shown, nav)
        }
    }
}

@Composable
private fun ChannelManageList(info: ChannelInfo, team: Team, me: String, shownCount: Int, nav: ChannelManageNav) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Fariin.colors.card)) {
        if (team.can(me, Right.CHANGE_INFO)) {
            SettingsRow("Edit Channel", icon = androidx.compose.ui.graphics.vector.rememberVectorPainter(Icons.Filled.Edit)) { nav.edit(info.cid) }
        }
        // iOS Channel Settings joining section: the invite links (Add users right).
        if (team.can(me, Right.INVITE_USERS)) {
            SettingsRow("Invite Links", icon = androidx.compose.ui.graphics.vector.rememberVectorPainter(Icons.Filled.Link)) { nav.links(info.cid) }
            // iOS GroupSettingsView: Follow Requests under the same right, "None" or how many wait.
            val reqs = com.fariin.app.ui.people.rememberJoinRequests(info.cid, allowed = true)
            SettingsRow("Follow Requests", icon = painterResource(R.drawable.ic_gs_join_requests),
                value = com.fariin.app.data.JoinRequests.rowValue(reqs.size)) { nav.requests(info.cid) }
        }
        SettingsRow("Followers", icon = painterResource(R.drawable.ic_gs_members), value = "$shownCount") { nav.followers(info.cid) }
        val canBan = team.can(me, Right.BAN_USERS)
        val canReact = team.can(me, Right.CHANGE_INFO)
        SettingsRow("Administrators", icon = painterResource(R.drawable.ic_gs_admins), value = "${team.sorted().size}", last = !canBan && !canReact) {
            nav.admins(info.cid)
        }
        if (canBan) SettingsRow("Banned Users", icon = painterResource(R.drawable.ic_gs_banned), value = "${info.bannedUids.size}", last = !canReact) {
            nav.banned(info.cid)
        }
        // What followers may react with (iOS channelPostsSection): the picks themselves, else Any / Off.
        if (canReact) SettingsRow("Reactions", icon = androidx.compose.ui.graphics.vector.rememberVectorPainter(Icons.Outlined.EmojiEmotions),
            value = if (info.reactMode == "some") info.reactList.take(6).joinToString(" ") + (if (info.reactList.size > 6) " +${info.reactList.size - 6}" else "")
            else com.fariin.app.data.ChannelReactions.value(info.reactMode), last = true) { nav.reactions(info.cid) }
    }
}

/** Where the management pages open (set by [channelRoutes]). */
data class ChannelManageNav(
    val edit: (String) -> Unit = {},
    val followers: (String) -> Unit = {},
    val admins: (String) -> Unit = {},
    val links: (String) -> Unit = {},
    val banned: (String) -> Unit = {},
    val reactions: (String) -> Unit = {},
    val requests: (String) -> Unit = {},
    val settings: (String) -> Unit = {},
    val type: (String) -> Unit = {},
)

/** Edit Channel: the group editor in a channel's words and squircle (iOS EditGroupView). */
@Composable
fun ChannelEditRoute(cid: String, me: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val doc = com.fariin.app.ui.people.liveGroupDoc(cid)
    val api = remember(me) { com.fariin.app.data.FirestorePeople(me, com.fariin.app.data.CryptoHub.get(ctx.applicationContext)) }
    if (doc.isEmpty()) { Box(Modifier.fillMaxSize().background(Fariin.colors.bgSecondary)); return }
    // The editor keeps its own copy once open, so a live update never resets what is being typed.
    val start = remember(doc.isNotEmpty()) { ChannelInfo.from(cid, doc) }
    com.fariin.app.ui.people.EditGroupScreen(api, cid, start.title.ifEmpty { "Channel" }, start.avatarUrl, onClose,
        description = start.description, channel = true)
}

// ---- Followers (iOS ChannelFollowersView) -------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelFollowersScreen(cid: String, me: String, onBack: () -> Unit, chats: () -> List<com.fariin.app.data.Conversation> = { emptyList() }) {
    val c = Fariin.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val doc = com.fariin.app.ui.people.liveGroupDoc(cid)
    val team = Team.from(doc)
    val info = ChannelInfo.from(cid, doc)
    // The count as the page opened, held once (iOS): this page alone adjusts it.
    var countAtOpen by remember(cid) { mutableStateOf<Int?>(null) }
    LaunchedEffect(doc.isNotEmpty()) { if (doc.isNotEmpty() && countAtOpen == null) countAtOpen = info.followerCount }
    var removed by remember(cid) { mutableIntStateOf(0) }
    var rows by remember(cid) { mutableStateOf<List<ChannelService.Follower>>(emptyList()) }
    var more by remember(cid) { mutableStateOf(true) }
    var loading by remember(cid) { mutableStateOf(false) }
    var failed by remember(cid) { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Pair<ChannelService.Follower, Boolean>?>(null) }
    val mayRemove = team.can(me, Right.BAN_USERS)
    // Add Followers / Invite via Link (iOS): admins with "Add users", people from my chats.
    val mayInvite = team.can(me, Right.INVITE_USERS)
    var added by remember(cid) { mutableIntStateOf(0) }
    var picking by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var linkInstead by remember { mutableStateOf<List<String>>(emptyList()) }
    fun shareLink() {
        scope.launch {
            val link = com.fariin.app.data.ChannelAddFollowers.link(cid, doc)
            if (link == null) { toast(ctx, com.fariin.app.data.ChannelAddFollowers.NO_LINK); return@launch }
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                .putExtra(android.content.Intent.EXTRA_TEXT, com.fariin.app.data.ChannelAddFollowers.shareText(info.title, link))
            ctx.startActivity(android.content.Intent.createChooser(send, null))
        }
    }

    suspend fun load() {
        if (loading || !more) return
        loading = true; failed = false
        try {
            val page = ChannelService.followers(cid, rows.lastOrNull()?.uid)
            val seen = rows.map { it.uid }.toSet()
            rows = rows + page.rows.filter { it.uid !in seen }
            more = page.more
        } catch (_: Exception) { failed = true }
        loading = false
    }
    LaunchedEffect(cid) { load() }

    confirm?.let { (f, ban) ->
        AlertDialog(onDismissRequest = { confirm = null }, containerColor = c.card,
            title = { Text(ChannelAdmins.removeTitle(ban)) }, text = { Text(ChannelAdmins.removeBody(ban)) },
            confirmButton = { TextButton({
                confirm = null
                scope.launch {
                    try {
                        ChannelService.removeFollower(cid, f.uid, ban)
                        rows = rows.filter { it.uid != f.uid }
                        removed += 1
                    } catch (e: Exception) { toast(ctx, ChannelService.errorText(e)) }
                }
            }) { Text(if (ban) "Ban" else "Remove", color = c.red) } },
            dismissButton = { TextButton({ confirm = null }) { Text("Cancel", color = c.accent) } })
    }

    val admins = team.sorted()
    val followerRows = rows.filter { it.uid !in admins }
    val shown = ChannelAdmins.shownCount((countAtOpen ?: info.followerCount) + added, removed, admins.size)
    if (picking) {
        val have = (admins + rows.map { it.uid }).toSet()
        val people = remember { com.fariin.app.ui.people.contactsFrom(chats(), me).filter { it.uid !in have } }
        com.fariin.app.ui.people.PickPeoplePage("Add Followers", "Add", people, onBack = { picking = false }) { picked ->
            picking = false
            adding = true
            scope.launch {
                try {
                    val r = com.fariin.app.data.ChannelAddFollowers.add(cid, picked)
                    added += r.added.size
                    com.fariin.app.data.ChannelAddFollowers.toasts(r, picked.size).forEach { toast(ctx, it) }
                    if (r.added.isNotEmpty()) { rows = emptyList(); more = true; load() }
                    val via = com.fariin.app.data.ChannelAddFollowers.viaLink(r)
                    linkInstead = people.filter { it.uid in via }.map { it.shownName }
                } catch (e: Exception) { toast(ctx, ChannelService.errorText(e)) }
                adding = false
            }
        }
        return
    }
    if (linkInstead.isNotEmpty()) AlertDialog(onDismissRequest = { linkInstead = emptyList() }, containerColor = c.card,
        title = { Text("Send Invite Link") }, text = { Text(com.fariin.app.data.ChannelAddFollowers.inviteInstead(linkInstead)) },
        confirmButton = { TextButton({ linkInstead = emptyList(); shareLink() }) { Text("Share Link", color = c.accent) } },
        dismissButton = { TextButton({ linkInstead = emptyList() }) { Text("Cancel", color = c.accent) } })
    Column(Modifier.fillMaxSize().background(c.bgSecondary)) {
        PageBar("Followers", onBack)
        PullToRefreshBox(refreshing, onRefresh = {
            refreshing = true
            scope.launch { rows = emptyList(); more = true; load(); refreshing = false }
        }, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
                if (mayInvite) SettingsSection {
                    SettingsRow(if (adding) "Adding…" else "Add Followers", icon = androidx.compose.ui.graphics.vector.rememberVectorPainter(Icons.Filled.PersonAdd),
                        titleColor = c.accent, trailing = Trailing.NONE) { if (!adding) picking = true }
                    SettingsRow("Invite via Link", icon = androidx.compose.ui.graphics.vector.rememberVectorPainter(Icons.Filled.Link),
                        titleColor = c.accent, trailing = Trailing.NONE, last = true) { shareLink() }
                }
                SettingsSection(header = ChannelRules.followersLine(shown), footer = ChannelAdmins.FOLLOWERS_FOOTER) {
                    admins.forEach { uid ->
                        PersonLine(team.photos[uid], if (uid == me) "You" else team.names[uid] ?: "") {
                            Text(if (uid == team.owner) "Owner" else "Admin", fontSize = 14.sp, color = c.secondary)
                        }
                    }
                    followerRows.forEach { f ->
                        PersonLine(f.photoUrl, f.name) {
                            if (mayRemove) RowMenu(listOf("Remove" to { confirm = f to false }, "Ban" to { confirm = f to true }))
                        }
                        if (f == rows.lastOrNull() && more) LaunchedEffect(f.uid) { load() }
                    }
                    when {
                        loading -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = c.accent, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                        }
                        failed -> Note(ChannelAdmins.FOLLOWERS_FAILED)
                        followerRows.isEmpty() && !more -> Note(ChannelAdmins.NO_FOLLOWERS)
                    }
                }
            }
        }
    }
}

// ---- Administrators (iOS GroupAdminsView, channel) ----------------------------------------------

@Composable
fun ChannelAdminsScreen(cid: String, me: String, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val c = Fariin.colors
    val doc = com.fariin.app.ui.people.liveGroupDoc(cid)
    val team = Team.from(doc)
    var picking by remember { mutableStateOf(false) }
    if (picking) {
        ChannelAdminPicker(cid, exclude = team.sorted().toSet(), onClose = { picking = false })
        return
    }
    Column(Modifier.fillMaxSize().background(c.bgSecondary)) {
        PageBar("Administrators", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            SettingsSection(footer = ChannelAdmins.ADMINS_FOOTER) {
                if (team.can(me, Right.ADD_ADMINS)) {
                    SettingsRow("Add Admin", icon = androidx.compose.ui.graphics.vector.rememberVectorPainter(Icons.Filled.PersonAdd),
                        titleColor = c.accent, trailing = Trailing.NONE) { picking = true }
                }
                team.sorted().forEach { uid ->
                    // Tap an admin for what they can do (and Dismiss Admin, where I may).
                    PersonLine(team.photos[uid], if (uid == me) "You" else team.names[uid] ?: "Admin", onClick = { onOpen(uid) }) {
                        Text(if (uid == team.owner) "Owner" else "Admin", fontSize = 14.sp, color = c.secondary)
                    }
                }
            }
        }
    }
}

/** Choose Follower (iOS ChannelAdminPicker): a follower, a search, and a yes makes them an admin at once. */
@Composable
private fun ChannelAdminPicker(cid: String, exclude: Set<String>, onClose: () -> Unit) {
    val c = Fariin.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onClose)
    var rows by remember { mutableStateOf<List<ChannelService.Follower>>(emptyList()) }
    var more by remember { mutableStateOf(true) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var asking by remember { mutableStateOf<ChannelService.Follower?>(null) }
    var busy by remember { mutableStateOf(false) }
    suspend fun load() {
        if (!more || loading) return
        loading = true
        try {
            val page = ChannelService.followers(cid, rows.lastOrNull()?.uid)
            val have = rows.map { it.uid }.toSet()
            rows = rows + page.rows.filter { it.uid !in have }
            more = page.more; failed = false
        } catch (_: Exception) { failed = true; more = false }
        loading = false
    }
    LaunchedEffect(cid) { load() }
    asking?.let { f ->
        AlertDialog(onDismissRequest = { asking = null }, containerColor = c.card,
            title = { Text("Make ${f.name} an admin?") }, text = { Text(ChannelAdmins.MAKE_ADMIN_BODY) },
            confirmButton = { TextButton({
                asking = null; busy = true
                scope.launch {
                    try { ChannelService.makeAdmin(cid, f.uid); toast(ctx, "${f.name} is now an admin."); onClose() }
                    catch (e: Exception) { toast(ctx, ChannelService.errorText(e)) }
                    busy = false
                }
            }) { Text("Make Admin", color = c.accent) } },
            dismissButton = { TextButton({ asking = null }) { Text("Cancel", color = c.accent) } })
    }
    val q = query.trim()
    val list = rows.filter { it.uid !in exclude }
    val shown = if (q.isEmpty()) list else list.filter { it.name.contains(q, ignoreCase = true) }
    Column(Modifier.fillMaxSize().background(c.bgSecondary)) {
        PageBar("Choose Follower", onClose, action = {
            if (busy) CircularProgressIndicator(color = c.accent, strokeWidth = 2.dp, modifier = Modifier.padding(horizontal = 12.dp).size(22.dp))
        })
        SearchField(query, "Search") { query = it }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            SettingsSection {
                shown.forEach { f ->
                    PersonLine(f.photoUrl, f.name, onClick = { if (!busy) asking = f })
                    if (f.uid == rows.lastOrNull()?.uid && more) LaunchedEffect(f.uid) { load() }
                }
                when {
                    loading -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = c.accent, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                    }
                    shown.isEmpty() -> Note(if (failed) ChannelAdmins.PICK_FAILED else if (q.isEmpty()) ChannelAdmins.PICK_EMPTY else ChannelAdmins.PICK_NONE)
                }
            }
        }
    }
}

// ---- One admin: rights and Dismiss (iOS AdminRightsView + the member card's Dismiss Admin) -----

@Composable
fun ChannelAdminScreen(cid: String, me: String, uid: String, onBack: () -> Unit) {
    val c = Fariin.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val doc = com.fariin.app.ui.people.liveGroupDoc(cid)
    val team = Team.from(doc)
    val name = if (uid == me) "You" else team.names[uid] ?: "Admin"
    val editable = team.canEditRights(me, uid)
    // The owner sees every right; an admin with Add admins only what they hold (they cannot give more).
    val shown = if (editable) team.grantable(me) else Right.entries
    var draft by remember(uid) { mutableStateOf<Set<String>?>(null) }
    val held = draft ?: team.rightsOf(uid).map { it.slug }.toSet()
    var saving by remember { mutableStateOf(false) }
    var confirmDismiss by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var transferring by remember { mutableStateOf(false) }
    val info = ChannelInfo.from(cid, doc)
    if (transferring) com.fariin.app.ui.people.TransferOwnership(cid, isChannel = true, heir = uid, name = name,
        onDone = { transferring = false }, onEnd = { transferring = false })
    // Once dismissed (here or elsewhere) the page has nothing left to show. Leave once only.
    var left by remember { mutableStateOf(false) }
    fun leave() { if (!left) { left = true; onBack() } }
    LaunchedEffect(doc, uid) { if (doc.isNotEmpty() && !team.isAdmin(uid)) leave() }

    fun save(next: Set<String>) {
        val old = held
        draft = next; saving = true
        scope.launch {
            try { ChannelService.setAdminRights(cid, uid, ChannelAdmins.toWrite(next, shown)); draft = null }
            catch (e: Exception) { draft = old; problem = e.message?.takeIf { it.isNotBlank() } ?: "Couldn't save. Try again." }
            saving = false
        }
    }
    problem?.let { p ->
        AlertDialog(onDismissRequest = { problem = null }, containerColor = c.card, title = { Text("Could not do that") },
            text = { Text(p) }, confirmButton = { TextButton({ problem = null }) { Text("OK", color = c.accent) } })
    }
    if (confirmDismiss) AlertDialog(onDismissRequest = { confirmDismiss = false }, containerColor = c.card,
        title = { Text("Dismiss $name as admin?") },
        confirmButton = { TextButton({
            confirmDismiss = false; saving = true
            scope.launch {
                try { ChannelService.dismissAdmin(cid, uid); toast(ctx, "$name is no longer an admin"); leave() }
                catch (e: Exception) { problem = ChannelService.errorText(e) }
                saving = false
            }
        }) { Text("Dismiss Admin", color = c.red) } },
        dismissButton = { TextButton({ confirmDismiss = false }) { Text("Cancel", color = c.accent) } })

    Column(Modifier.fillMaxSize().background(c.bgSecondary)) {
        PageBar(if (editable) "Admin Rights" else if (uid == me) "My Admin Rights" else name, onBack, action = {
            if (saving) CircularProgressIndicator(color = c.accent, strokeWidth = 2.dp, modifier = Modifier.padding(horizontal = 12.dp).size(22.dp))
        })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Avatar(team.photos[uid], 80.dp, modifier = Modifier.padding(top = 8.dp))
            Text(name, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp))
            Text(if (uid == team.owner) "Owner" else "Admin", fontSize = 15.sp, color = c.secondary)
            if (uid == team.owner) {
                SettingsSection {
                    Note("You are the owner of this channel, so you can do everything in it, including adding admins and transferring ownership."
                        .let { if (uid == me) it else "$name is the owner of this channel and can do everything in it." })
                }
            } else {
                if (editable) SettingsSection {
                    SwitchLine("All rights", ChannelAdmins.allOn(held, shown), bold = true, enabled = !saving, last = true) { on ->
                        save(if (on) held + shown.map { it.slug } else held - shown.map { it.slug }.toSet())
                    }
                }
                SettingsSection(header = if (uid == me) "What you can do" else "What $name can do", footer = if (editable) ChannelAdmins.rightsFooter(name) else null) {
                    shown.forEachIndexed { i, r ->
                        SwitchLine(r.label, r.slug in held, enabled = editable && !saving, last = i == shown.lastIndex) { on ->
                            save(if (on) held + r.slug else held - r.slug)
                        }
                    }
                }
                // iOS member card: the owner hands the channel to another admin (the server checks it is a member).
                if (com.fariin.app.data.OwnerTransfer.canTransfer(me, team.owner, uid, info.users)) SettingsSection {
                    SettingsRow(com.fariin.app.data.OwnerTransfer.ROW, icon = painterResource(R.drawable.ic_crown), trailing = Trailing.NONE,
                        last = true) { if (!saving) transferring = true }
                }
                if (team.canDismiss(me, uid)) SettingsSection {
                    SettingsRow("Dismiss Admin", icon = androidx.compose.ui.graphics.vector.rememberVectorPainter(Icons.Filled.PersonRemove),
                        titleColor = c.red, trailing = Trailing.NONE, last = true) { if (!saving) confirmDismiss = true }
                }
            }
        }
    }
}

// ---- Small pieces -----------------------------------------------------------------------------

@Composable
internal fun PersonLine(photo: String?, name: String, onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    val c = Fariin.colors
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(photo, 40.dp)
        Spacer(Modifier.width(12.dp))
        Text(name, fontSize = 17.sp, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun RowMenu(items: List<Pair<String, () -> Unit>>) {
    val c = Fariin.colors
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton({ open = true }) { Icon(Icons.Filled.MoreVert, "More", tint = c.secondary) }
        DropdownMenu(open, onDismissRequest = { open = false }, shape = com.fariin.app.ui.components.FariinMenu.shape, border = com.fariin.app.ui.components.FariinMenu.border, containerColor = c.card) {
            items.forEach { (label, act) ->
                DropdownMenuItem(text = { Text(label, color = c.red) }, onClick = { open = false; act() })
            }
        }
    }
}

@Composable
internal fun SwitchLine(title: String, on: Boolean, bold: Boolean = false, enabled: Boolean = true, last: Boolean = false, onChange: (Boolean) -> Unit) {
    val c = Fariin.colors
    // The whole row flips the switch (Android settings rows).
    Row(Modifier.fillMaxWidth().toggleable(on, enabled = enabled, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = onChange)
        .heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 17.sp, color = c.label, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
        com.fariin.app.ui.components.FariinSwitch(on, onCheckedChange = null, enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = com.fariin.app.ui.theme.Fariin.colors.onAccent))
    }
    if (!last) HorizontalDivider(thickness = 0.5.dp, color = c.separator, modifier = Modifier.padding(start = 16.dp))
}

@Composable
private fun Note(text: String) {
    Text(text, fontSize = 15.sp, color = Fariin.colors.secondary, textAlign = TextAlign.Start,
        modifier = Modifier.fillMaxWidth().padding(16.dp))
}
