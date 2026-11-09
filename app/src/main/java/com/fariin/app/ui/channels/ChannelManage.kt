package com.fariin.app.ui.channels

import com.fariin.app.ui.settings.BarPage
import com.fariin.app.ui.components.underBar

import com.fariin.app.data.NewBadgeSpots
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.AlertDialog
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
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Fariin.colors.card)) {
        ChannelManageRows(info, team, me, shownCount, nav, last = true)
    }
}

/** Followers, Channel Settings and (while someone waits) Follow Requests, as bare rows. */
@Composable
internal fun ChannelManageRows(info: ChannelInfo, team: Team, me: String, shownCount: Int, nav: ChannelManageNav, last: Boolean) {
    val reqs = com.fariin.app.ui.people.rememberJoinRequests(info.cid, allowed = team.can(me, Right.INVITE_USERS))
    SettingsRow("Followers", icon = painterResource(R.drawable.ic_gs_members), value = "$shownCount",
        badge = NewBadgeSpots.GROUP_INFO_FOLLOWERS) { nav.followers(info.cid) }
    SettingsRow("Channel Settings", icon = painterResource(R.drawable.ic_group_settings), last = last && reqs.isEmpty(),
        badge = NewBadgeSpots.GROUP_INFO_SETTINGS) { nav.settings(info.cid) }
    if (reqs.isNotEmpty()) SettingsRow("Follow Requests", icon = painterResource(R.drawable.ic_gs_join_requests),
        value = com.fariin.app.data.JoinRequests.rowValue(reqs.size), last = last, badge = NewBadgeSpots.GROUP_SETTINGS_REQUESTS) { nav.requests(info.cid) }
}

/** Channel Settings (iOS GroupSettingsView for a channel): the whole management list on its own page. */
@Composable
fun ChannelSettingsScreen(cid: String, me: String, onBack: () -> Unit, nav: ChannelManageNav) {
    val doc = com.fariin.app.ui.people.liveGroupDoc(cid)
    val c = Fariin.colors
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val api = androidx.compose.runtime.remember(me) { com.fariin.app.data.FirestorePeople(me, com.fariin.app.data.CryptoHub.get(ctx.applicationContext)) }
    var timerOpen by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val timer = (doc["disappearSeconds"] as? Number)?.toInt() ?: 0
    if (com.fariin.app.ui.people.DisappearingEditor(api, cid, isGroup = true, current = timer, open = timerOpen, onClose = { timerOpen = false })) return
    BarPage("Channel Settings", onBack, modifier = Modifier.background(c.bgSecondary)) { top ->
        androidx.compose.foundation.layout.Spacer(Modifier.height(top))
        if (doc["channel"] != true) return@BarPage
        val info = ChannelInfo.from(cid, doc)
        val shown = ChannelRules.subscriberCount(com.fariin.app.channel.ChannelFollows.adjusted(cid, info.followerCount), info.users.size)
        val team = Team.from(doc)
        Column(Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ChannelManageList(info, team, me, shown, nav,
                inviteOn = !(doc["inviteCode"] as? String).isNullOrEmpty(),
                timer = timer, onTimer = { timerOpen = true }, onRecent = { nav.recent(cid) })
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * iOS GroupSettingsView for a channel (owner screenshot 2026-11-04), card by card: Channel Type,
 * Invite Link, Follow Requests | Followers, Administrators, Banned Users | Disappearing Messages,
 * Recent Actions | Reactions, Replies, Quotes (owner's mockup 2026-11-07). Edit is the info page's Edit; Protect Content sits on
 * the Channel Type page.
 */
@Composable
private fun ChannelManageList(info: ChannelInfo, team: Team, me: String, shownCount: Int, nav: ChannelManageNav,
                              inviteOn: Boolean, timer: Int, onTimer: () -> Unit, onRecent: () -> Unit) {
    val c = Fariin.colors
    @Composable
    fun Card(content: @Composable () -> Unit) =
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.card)) { content() }
    val owner = team.isOwner(me)
    val invite = team.can(me, Right.INVITE_USERS)
    if (owner || invite) Card {
        if (owner) SettingsRow("Channel Type", icon = painterResource(R.drawable.ic_gs_group_type),
            value = if (info.isPublic) "Public" else "Private", last = !invite, badge = NewBadgeSpots.GROUP_SETTINGS_TYPE) { nav.type(info.cid) }
        if (invite) {
            val reqs = com.fariin.app.ui.people.rememberJoinRequests(info.cid, allowed = true)
            SettingsRow("Invite Link", icon = androidx.compose.ui.graphics.vector.rememberVectorPainter(ImageVector.vectorResource(com.fariin.app.R.drawable.ic_link_copy)),
                value = if (inviteOn) "On" else "Off", badge = NewBadgeSpots.GROUP_SETTINGS_INVITE_LINK) { nav.links(info.cid) }
            SettingsRow("Follow Requests", icon = painterResource(R.drawable.ic_gs_join_requests),
                value = com.fariin.app.data.JoinRequests.rowValue(reqs.size), last = true,
                badge = NewBadgeSpots.GROUP_SETTINGS_REQUESTS) { nav.requests(info.cid) }
        }
    }
    val canBan = team.can(me, Right.BAN_USERS)
    Card {
        SettingsRow("Followers", icon = painterResource(R.drawable.ic_gs_members), value = "$shownCount",
            badge = NewBadgeSpots.CHANNEL_SETTINGS_FOLLOWERS) { nav.followers(info.cid) }
        SettingsRow("Administrators", icon = painterResource(R.drawable.ic_gs_admins), value = "${team.sorted().size}", last = !canBan,
            badge = NewBadgeSpots.GROUP_SETTINGS_ADMINS) {
            nav.admins(info.cid)
        }
        if (canBan) SettingsRow("Banned Users", icon = painterResource(R.drawable.ic_gs_banned), value = "${info.bannedUids.size}", last = true,
            badge = NewBadgeSpots.GROUP_SETTINGS_BANNED) {
            nav.banned(info.cid)
        }
    }
    Card {
        com.fariin.app.ui.people.DisappearingRow(timer, short = false, badge = NewBadgeSpots.GROUP_SETTINGS_DISAPPEARING, onClick = onTimer)
        SettingsRow("Recent Actions", icon = androidx.compose.ui.graphics.vector.rememberVectorPainter(ImageVector.vectorResource(R.drawable.ic_sf_clock_arrow)), last = true,
            badge = NewBadgeSpots.GROUP_SETTINGS_RECENT_ACTIONS, onClick = onRecent)
    }
    // LAST (owner's mockup 2026-11-07): Reactions (the picks themselves, else Any / Off), Replies,
    // Quotes, in one card with one footer.
    if (team.can(me, Right.CHANGE_INFO)) {
        RepliesSwitch(info) {
            SettingsRow("Reactions", value = if (info.reactMode == "some") info.reactList.take(6).joinToString(" ") + (if (info.reactList.size > 6) " +${info.reactList.size - 6}" else "")
                else com.fariin.app.data.ChannelReactions.value(info.reactMode),
                badge = NewBadgeSpots.CHANNEL_SETTINGS_REACTIONS) { nav.reactions(info.cid) }
        }
    }
}

/**
 * Replies under posts, for the whole channel (iOS GroupSettingsView, owner 2026-11-06). Off is
 * read-only: replies already there stay, no new ones. The new state shows at once and holds while
 * the server answers (the Change info right, checked there); a refusal puts it back.
 */
@Composable
private fun RepliesSwitch(info: ChannelInfo, top: @Composable () -> Unit = {}) {
    val c = Fariin.colors
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var wanted by androidx.compose.runtime.remember(info.repliesOff) { androidx.compose.runtime.mutableStateOf<Boolean?>(null) }
    Column {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.card)) {
            top()
            SwitchLine("Replies", on = wanted ?: !info.repliesOff, badge = NewBadgeSpots.CHANNEL_SETTINGS_REPLIES) { on ->
                wanted = on
                scope.launch {
                    if (!com.fariin.app.data.PostReplies.setOff(info.cid, null, off = !on)) {
                        wanted = null
                        com.fariin.app.ui.components.Snack.show("Couldn't change replies.")
                    }
                }
            }
            // Quotes of this channel's posts (iOS 1280e92d, owner 2026-11-07), the same way as Replies.
            // Off: no new quotes (the server refuses them); quotes already made stay.
            QuotesLine(info)
        }
        Text(QUOTES_FOOTER, fontSize = 13.sp,
            color = c.secondary, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp))
    }
}

private const val QUOTES_FOOTER =
    "Replies off: followers can read replies already posted but can't add new ones. Quotes off: nobody can quote your posts."

/** The Quotes switch: shows the new state at once with a spinner; a refusal puts it back and says why. */
@Composable
private fun QuotesLine(info: ChannelInfo) {
    val c = Fariin.colors
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var wanted by androidx.compose.runtime.remember(info.quotesOff) { androidx.compose.runtime.mutableStateOf<Boolean?>(null) }
    var busy by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    SwitchLine("Quotes", on = wanted ?: !info.quotesOff, enabled = !busy, busy = busy, last = true, badge = NewBadgeSpots.CHANNEL_SETTINGS_QUOTES) { on ->
        wanted = on; busy = true
        scope.launch {
            try {
                com.fariin.app.data.PostReplies.setQuotesOff(info.cid, off = !on)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                wanted = null
                com.fariin.app.ui.components.Snack.show(com.fariin.app.channel.ChannelService.errorText(e, "Couldn't change quotes. Try again."))
            } finally { busy = false }
        }
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
    val recent: (String) -> Unit = {},
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

/** Edit Channel as the iOS sheet over the channel page (EditGroupView is a .sheet), pulled down to close. */
@Composable
fun ChannelEditSheet(cid: String, me: String, onClosed: () -> Unit) {
    val ctx = LocalContext.current
    val doc = com.fariin.app.ui.people.liveGroupDoc(cid)
    val api = remember(me) { com.fariin.app.data.FirestorePeople(me, com.fariin.app.data.CryptoHub.get(ctx.applicationContext)) }
    val motion = com.fariin.app.ui.components.rememberSheetMotion(onClosed)
    com.fariin.app.ui.components.FariinSheetDialog(motion, fill = Fariin.colors.bgSecondary, typing = true) {
        if (doc.isEmpty()) { Box(Modifier.fillMaxSize().background(Fariin.colors.bgSecondary)); return@FariinSheetDialog }
        val start = remember(doc.isNotEmpty()) { ChannelInfo.from(cid, doc) }
        com.fariin.app.ui.people.EditGroupScreen(api, cid, start.title.ifEmpty { "Channel" }, start.avatarUrl, onClose = { motion.close() },
            description = start.description, channel = true, sheet = motion)
    }
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
            }) { Text(if (ban) "Ban" else "Remove", color = if (ban) banOrange() else c.red) } },
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
    BarPage("Followers", onBack, modifier = Modifier.background(c.bgSecondary)) { top ->
        androidx.compose.foundation.layout.Spacer(Modifier.height(top))
        PullToRefreshBox(refreshing, onRefresh = {
            refreshing = true
            scope.launch { rows = emptyList(); more = true; load(); refreshing = false }
        }, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
                if (mayInvite) SettingsSection {
                    SettingsRow(if (adding) "Adding…" else "Add Followers", icon = androidx.compose.ui.graphics.vector.rememberVectorPainter(ImageVector.vectorResource(com.fariin.app.R.drawable.ic_sf_person_plus)),
                        trailing = Trailing.NONE,
                        accessory = if (adding) ({ com.fariin.app.ui.components.FariinSpinner(size = 20.dp) }) else null) { if (!adding) picking = true }
                    SettingsRow("Invite via Link", icon = androidx.compose.ui.graphics.vector.rememberVectorPainter(ImageVector.vectorResource(com.fariin.app.R.drawable.ic_sf_link)),
                        trailing = Trailing.NONE, last = true) { shareLink() }
                }
                SettingsSection(header = ChannelRules.followersLine(shown), footer = ChannelAdmins.FOLLOWERS_FOOTER) {
                    admins.forEach { uid ->
                        PersonLine(team.photos[uid], if (uid == me) "You" else team.names[uid] ?: "") {
                            Text(if (uid == team.owner) "Owner" else "Admin", fontSize = 14.sp, color = c.secondary)
                        }
                    }
                    followerRows.forEach { f ->
                        PersonLine(f.photoUrl, f.name, username = f.username) {
                            if (mayRemove) RowMenu(listOf("Remove" to { confirm = f to false }, "Ban" to { confirm = f to true }))
                        }
                        if (f == rows.lastOrNull() && more) LaunchedEffect(f.uid) { load() }
                    }
                    when {
                        // A grey row where the next follower will be, not a spinner (iOS skeleton, owner 2026-11-08).
                        loading -> Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            com.fariin.app.ui.components.SkeletonCircle(40.dp)
                            Spacer(Modifier.width(12.dp))
                            com.fariin.app.ui.components.SkeletonBlock(170.dp, height = 14.dp, radius = 7.dp)
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
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf(false) }
    // Admin invites still waiting (iOS ChannelPendingAdminsSection + Loader): only for those who may add admins.
    val mayAdd = team.can(me, Right.ADD_ADMINS)
    var pending by remember(cid) { mutableStateOf<List<ChannelService.PendingAdmin>>(emptyList()) }
    var revoking by remember { mutableStateOf<ChannelService.PendingAdmin?>(null) }
    suspend fun reload() { runCatching { ChannelService.pendingAdmins(cid) }.onSuccess { pending = it } }
    LaunchedEffect(cid, mayAdd) { if (mayAdd) reload() }
    revoking?.let { p ->
        AlertDialog(onDismissRequest = { revoking = null }, containerColor = c.card,
            title = { Text("Revoke admin invite for ${p.name}?") },
            text = { Text("If you revoke it, ${p.name} can no longer use it to become an admin of this channel.") },
            confirmButton = { TextButton({
                revoking = null
                scope.launch {
                    try { ChannelService.revokeAdminInvite(p.code) }
                    catch (e: Exception) { toast(ctx, ChannelService.errorText(e)) }
                    reload()
                }
            }) { Text("Revoke", color = c.red) } },
            dismissButton = { TextButton({ revoking = null }) { Text("Cancel", color = c.accent) } })
    }
    if (picking) {
        ChannelAdminPicker(cid, exclude = team.sorted().toSet() + pending.map { it.uid }, onClose = { picking = false })
        return
    }
    val barScroll = rememberScrollState()
    BarPage("Administrators", onBack, under = underBar(barScroll), modifier = Modifier.background(c.bgSecondary)) { top ->
        Column(Modifier.fillMaxSize().verticalScroll(barScroll).padding(top = top).navigationBarsPadding()) {
            SettingsSection(footer = ChannelAdmins.ADMINS_FOOTER) {
                if (team.can(me, Right.ADD_ADMINS)) {
                    SettingsRow("Add Admin", icon = androidx.compose.ui.graphics.vector.rememberVectorPainter(ImageVector.vectorResource(com.fariin.app.R.drawable.ic_add_member)),
                        titleColor = c.accent, trailing = Trailing.NONE) { picking = true }
                }
                team.sorted().forEach { uid ->
                    // Tap an admin for what they can do (and Dismiss Admin, where I may).
                    PersonLine(team.photos[uid], if (uid == me) "You" else team.names[uid] ?: "Admin", onClick = { onOpen(uid) }) {
                        Text(if (uid == team.owner) "Owner" else "Admin", fontSize = 14.sp, color = c.secondary)
                    }
                }
            }
            // Waiting to Accept (iOS ChannelPendingAdminsSection): tap a row to revoke its invite.
            if (mayAdd && pending.isNotEmpty()) SettingsSection(header = "Waiting to Accept") {
                pending.forEach { p ->
                    PersonLine(p.photoUrl, p.name, onClick = { revoking = p }) {
                        Text("Invited as admin", fontSize = 14.sp, color = c.secondary)
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
    BarPage("Choose Follower", onClose, action = {
            if (busy) com.fariin.app.ui.components.BarBusyPill("Adding…")
        }, modifier = Modifier.background(c.bgSecondary)) { top ->
        androidx.compose.foundation.layout.Spacer(Modifier.height(top))
        SearchField(query, "Search") { query = it }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            SettingsSection {
                shown.forEach { f ->
                    PersonLine(f.photoUrl, f.name, onClick = { if (!busy) asking = f })
                    if (f.uid == rows.lastOrNull()?.uid && more) LaunchedEffect(f.uid) { load() }
                }
                when {
                    loading -> com.fariin.app.ui.components.LoadingRow()
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

    val barScroll = rememberScrollState()
    BarPage(if (editable) "Admin Rights" else if (uid == me) "My Admin Rights" else name, onBack, action = {
            if (saving) com.fariin.app.ui.components.BarBusyPill("Saving…")
        }, under = underBar(barScroll), modifier = Modifier.background(c.bgSecondary)) { top ->
        Column(Modifier.fillMaxSize().verticalScroll(barScroll).padding(top = top).navigationBarsPadding(),
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
                    SettingsRow("Dismiss Admin", icon = androidx.compose.ui.graphics.vector.rememberVectorPainter(ImageVector.vectorResource(R.drawable.ic_sf_person_minus)),
                        titleColor = c.red, trailing = Trailing.NONE, last = true) { if (!saving) confirmDismiss = true }
                }
            }
        }
    }
}

// ---- Small pieces -----------------------------------------------------------------------------

@Composable
internal fun PersonLine(photo: String?, name: String, onClick: (() -> Unit)? = null, username: String = "", trailing: @Composable () -> Unit = {}) {
    val c = Fariin.colors
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(photo, 40.dp)
        Spacer(Modifier.width(12.dp))
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text(name, fontSize = 17.sp, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // The follower's @username under the name (iOS, server listChannelFollowers `username`).
            if (username.isNotEmpty()) Text("@$username", fontSize = 14.sp, color = c.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

/** Ban in orange, Remove in red (owner 2026-11-07), on the Followers row menu and its confirm button. */
@Composable
private fun banOrange(): androidx.compose.ui.graphics.Color =
    if (Fariin.colors.dark) androidx.compose.ui.graphics.Color(0xFFFF9F0A) else androidx.compose.ui.graphics.Color(0xFFFF9500)

@Composable
private fun RowMenu(items: List<Pair<String, () -> Unit>>) {
    val c = Fariin.colors
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton({ open = true }) { Icon(ImageVector.vectorResource(R.drawable.ic_more_ios), "More", tint = c.secondary) }
        DropdownMenu(open, onDismissRequest = { open = false }, shape = com.fariin.app.ui.components.FariinMenu.shape, border = com.fariin.app.ui.components.FariinMenu.border, containerColor = c.card) {
            items.forEach { (label, act) ->
                DropdownMenuItem(text = { Text(label, color = if (label == "Ban") banOrange() else c.red) }, onClick = { open = false; act() })
            }
        }
    }
}

@Composable
internal fun SwitchLine(title: String, on: Boolean, bold: Boolean = false, enabled: Boolean = true, last: Boolean = false,
                        /** The row's NewBadgeSpots id: the green "New" pill after the title while the panel has it on. */
                        badge: String? = null,
                        /** A save under way: a small spinner before the switch. */
                        busy: Boolean = false, onChange: (Boolean) -> Unit) {
    val c = Fariin.colors
    // The whole row flips the switch (Android settings rows).
    Row(Modifier.fillMaxWidth().toggleable(on, enabled = enabled, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = onChange)
        .heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (com.fariin.app.ui.components.isNew(badge)) Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 17.sp, color = c.label, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.weight(1f, fill = false))
            com.fariin.app.ui.components.NewPill(Modifier.padding(start = 8.dp, end = 8.dp))
        } else Text(title, fontSize = 17.sp, color = c.label, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
        if (busy) androidx.compose.material3.CircularProgressIndicator(Modifier.padding(end = 12.dp).size(18.dp), color = c.secondary, strokeWidth = 2.dp)
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
