package com.fariin.app.ui.channels

import com.fariin.app.ui.settings.BarPage

import com.fariin.app.data.NewBadgeSpots
import androidx.compose.ui.res.vectorResource
import android.content.Intent
import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ExitToApp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Report
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.tasks.await
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.channel.ChannelFollows
import com.fariin.app.channel.ChannelInfo
import com.fariin.app.channel.ChannelRules
import com.fariin.app.channel.ChannelRules.Action
import com.fariin.app.channel.ChannelRules.Role
import com.fariin.app.channel.ChannelService
import com.fariin.app.ui.components.Avatar
import com.fariin.app.ui.settings.PageBar
import com.fariin.app.ui.theme.Fariin
import com.fariin.app.ui.components.floating
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The channel's page (Kulan-iOS PublicGroupSheet visitor page and GroupInfoView's channel parts):
 * ONE design for a visitor, a follower and an admin. The photo, the name, "1.2k Followers", then
 * the round buttons in one fixed order, Follow (or Leave), Share, Search, Mute: following changes
 * only the first circle, nothing moves. Then the about card, notifications, the admin's settings,
 * and Report / Leave with the "Created ..." line.
 */
@Composable
fun ChannelPageRoute(cid: String, me: String, onBack: () -> Unit, onLeft: () -> Unit, manage: ChannelManageNav = ChannelManageNav()) {
    // iOS GroupInfoView onSearch: Search goes back to the channel and opens its search there; only
    // when this page was opened from that channel, else the circle is dimmed and does nothing.
    val fromChat = androidx.compose.runtime.saveable.rememberSaveable { com.fariin.app.ui.thread.InChatSearchLink.takeOpenedFrom(cid) }
    val doc = com.fariin.app.ui.people.liveGroupDoc(cid)
    val entries by ChannelFollows.entries.collectAsState()
    val bias by ChannelFollows.biasFlow.collectAsState()
    var preview by remember(cid) { mutableStateOf<com.fariin.app.channel.ChannelPreview?>(null) }
    var refused by remember(cid) { mutableStateOf<String?>(null) }
    LaunchedEffect(cid) {
        when (val r = ChannelService.preview(cid)) {
            is ChannelService.PreviewResult.Found -> preview = r.preview
            is ChannelService.PreviewResult.Refused -> refused = r.text
            is ChannelService.PreviewResult.Failed -> if (doc.isEmpty()) refused = r.text
        }
    }
    val base = if (doc["channel"] == true) ChannelInfo.from(cid, doc) else preview?.let { ChannelInfo.fromPreview(it) }
    val c = Fariin.colors
    if (base == null) {
        BarPage("", onBack, modifier = Modifier.background(c.bgSecondary)) { top ->
            androidx.compose.foundation.layout.Spacer(Modifier.height(top))
            Text(refused ?: "", fontSize = 15.sp, color = c.secondary, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(32.dp))
        }
        return
    }
    @Suppress("UNUSED_VARIABLE") val b = bias.size
    // The visitor's preview already carries the admins in its count; the document does not.
    val shown = if (doc["channel"] == true) ChannelRules.subscriberCount(ChannelFollows.adjusted(cid, base.followerCount), base.users.size)
    else ChannelFollows.adjusted(cid, preview?.shownCount ?: base.followerCount)
    ChannelPage(base, shown, ChannelRules.role(base, me, entries.containsKey(cid)), entries[cid]?.isMuted() ?: false, onBack, onLeft,
        team = com.fariin.app.channel.ChannelAdmins.Team.from(doc), me = me, manage = manage,
        onSearch = if (fromChat) ({ com.fariin.app.ui.thread.InChatSearchLink.request(cid); onBack() }) else null)
}

@Composable
fun ChannelPage(info: ChannelInfo, shownCount: Int, role: Role, muted: Boolean, onBack: () -> Unit, onLeft: () -> Unit,
                team: com.fariin.app.channel.ChannelAdmins.Team? = null, me: String = "", manage: ChannelManageNav = ChannelManageNav(),
                onSearch: (() -> Unit)? = null) {
    val canEdit = role == Role.ADMIN && team?.can(me, com.fariin.app.channel.ChannelAdmins.Right.CHANGE_INFO) == true
    val c = Fariin.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var following by remember(info.cid) { mutableStateOf(false) }
    val shownRole = if (role == Role.VISITOR && following) Role.FOLLOWER else role
    var notice by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var undo by remember { mutableStateOf(false) }
    var confirmReport by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    // iOS channel info rows that open a page here: Storage Used and Statistics.
    var sub by remember(info.cid) { mutableStateOf<String?>(null) }
    var askDelete by remember { mutableStateOf(false) }
    when (sub) {
        "storage" -> { com.fariin.app.ui.people.ChatStoragePage(info.cid, me, info.title, pop = { sub = null }); return }
        "stats" -> { com.fariin.app.ui.people.GroupStatsPage(info.cid, info.title, onBack = { sub = null }); return }
    }
    if (askDelete) androidx.compose.material3.AlertDialog(onDismissRequest = { askDelete = false },
        title = { Text("Delete Channel?") },
        text = { Text("This deletes the channel and all its posts for every follower.") },
        confirmButton = { androidx.compose.material3.TextButton({
            askDelete = false
            // As Delete Group: gone at once, Undo for a few seconds, then the server deletes it.
            com.fariin.app.data.LeaveUndo.start(info.cid, info.title.ifEmpty { "the channel" }) {
                com.google.firebase.functions.FirebaseFunctions.getInstance(com.fariin.app.data.AuthRepository.FUNCTIONS_REGION)
                    .getHttpsCallable("deleteGroup").call(mapOf("cid" to info.cid)).await()
            }
            com.fariin.app.ui.components.Snack.show("Channel deleted.", "Undo", durationMs = com.fariin.app.data.LeaveUndo.WINDOW_MS) {
                com.fariin.app.data.LeaveUndo.undo()
            }
            onLeft()
        }) { Text("Delete Channel", color = c.red) } },
        dismissButton = { androidx.compose.material3.TextButton({ askDelete = false }) { Text("Cancel", color = c.accent) } },
        containerColor = c.card)
    var reported by remember { mutableStateOf(false) }
    LaunchedEffect(role) { if (role != Role.VISITOR) following = false }
    LaunchedEffect(undo) { if (undo) { delay(5000); undo = false } }

    fun share() {
        // A private channel: its invite link, for an admin who may invite (iOS 1280e92d, More > Share Channel).
        if (info.username.isEmpty() && role == Role.ADMIN && team?.can(me, com.fariin.app.channel.ChannelAdmins.Right.INVITE_USERS) == true) {
            scope.launch {
                val link = com.fariin.app.data.ChannelAddFollowers.link(info.cid, mapOf("username" to info.username, "isPublic" to info.isPublic))
                    ?: run { notice = com.fariin.app.data.ChannelAddFollowers.NO_LINK; return@launch }
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, com.fariin.app.data.ChannelAddFollowers.shareText(info.title, link)), "Share")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return
        }
        val text = ChannelRules.shareText(info.title, info.username) ?: run { notice = "This channel has no public link."; return }
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    fun follow() {
        error = null; following = true
        scope.launch {
            try {
                if (ChannelService.follow(info.cid) == "requested") { following = false; notice = ChannelRules.REQUEST_SENT }
                else undo = true
            } catch (e: Exception) { following = false; error = ChannelService.errorText(e) }
        }
    }
    fun leave(fromUndo: Boolean) {
        undo = false
        scope.launch {
            try { ChannelService.unfollow(info.cid); following = false; if (!fromUndo) onLeft() }
            catch (e: Exception) { error = if (fromUndo) "Couldn't undo. Use Leave Channel on the channel page." else ChannelService.errorText(e) }
        }
    }
    fun tap(a: Action, dimmed: Boolean) {
        if (dimmed) { ChannelRules.visitorNotice(a)?.let { notice = it }; if (a == Action.SHARE) notice = "This channel has no public link."; return }
        when (a) {
            Action.FOLLOW -> follow()
            Action.LEAVE -> if (role == Role.VISITOR) leave(fromUndo = true) else confirmLeave = true
            Action.SHARE -> share()
            Action.SEARCH -> onSearch?.invoke()
            Action.MUTE -> scope.launch { ChannelService.setMuted(info.cid, true, 0.0) }
            Action.UNMUTE -> scope.launch { ChannelService.setMuted(info.cid, false) }
        }
    }

    notice?.let { n ->
        AlertDialog(onDismissRequest = { notice = null }, text = { Text(n) }, containerColor = c.card,
            confirmButton = { TextButton({ notice = null }) { Text("OK", color = c.accent) } })
    }
    if (confirmReport) AlertDialog(onDismissRequest = { confirmReport = false }, containerColor = c.card,
        title = { Text("Report this channel?") }, text = { Text("Fariin's moderators will review it.") },
        confirmButton = { TextButton({ confirmReport = false; scope.launch { reported = ChannelService.report(info.cid) } }) { Text("Report", color = c.red) } },
        dismissButton = { TextButton({ confirmReport = false }) { Text("Cancel", color = c.accent) } })
    if (confirmLeave) com.fariin.app.ui.components.CarefulSheet(onDismiss = { confirmLeave = false }, steps = listOf(
        com.fariin.app.ui.components.CarefulStep(
            title = "Leave ${info.title.ifEmpty { "this channel" }}?",
            body = if (muted) "You will stop getting its posts." else "You will stop getting its posts. To keep them without alerts, mute it instead.",
            buttons = listOfNotNull(
                if (!muted) com.fariin.app.ui.components.CarefulButton("Mute Instead", com.fariin.app.ui.components.CarefulKind.SOFT) {
                    scope.launch {
                        val ok = runCatching { ChannelService.setMuted(info.cid, true, 0.0) }.getOrDefault(false)
                        com.fariin.app.ui.components.Snack.show(if (ok) "Muted. You still follow this channel." else com.fariin.app.data.GroupInfoCircles.MUTE_FAILED)
                    }
                } else null,
                com.fariin.app.ui.components.CarefulButton("Leave Channel", com.fariin.app.ui.components.CarefulKind.DESTRUCTIVE) { leave(fromUndo = false) },
            ),
        )))

    // The grey page with white cards and floating action circles (iOS ChannelInfo on grouped background).
    // The page runs under its bar, so back and Edit are glass over it (GlassBarPage).
    val pageScroll = rememberScrollState()
    val underBar by androidx.compose.runtime.remember { androidx.compose.runtime.derivedStateOf { pageScroll.value > 0 } }
    Box(Modifier.fillMaxSize().background(c.bgSecondary)) {
        com.fariin.app.ui.components.GlassBarPage(underBar, bar = {
            // Edit at the end of the bar for an admin who may change the channel's info (iOS Group Info's Edit).
            // The ••• menu holds Change Wallpaper for the same people (iOS Group Info's first menu
            // section): back to the chat, which opens the channel's shared picker for every follower.
            PageBar("", onBack, action = if (canEdit) ({
                Row(verticalAlignment = Alignment.CenterVertically) {
                    com.fariin.app.ui.components.FloatingTextAction("Edit") { manage.edit(info.cid) }
                    ChannelInfoMenu { com.fariin.app.ui.settings.ChatWallpaperLink.request(info.cid); onBack() }
                }
            }) else null)
        }) { top ->
            Column(Modifier.fillMaxSize().verticalScroll(pageScroll).padding(top = top).padding(horizontal = 16.dp).navigationBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Avatar(info.avatarUrl, 132.dp, squircle = true, kind = com.fariin.app.ui.components.AvatarKind.CHANNEL, modifier = if (canEdit) Modifier.clickable { manage.edit(info.cid) } else Modifier)
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(info.title.ifEmpty { "Channel" }, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = c.label,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    if (info.verified) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Filled.Verified, contentDescription = "Verified", tint = c.blue, modifier = Modifier.size(22.dp))
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(ChannelRules.pageCountLine(shownCount), fontSize = 16.sp, color = Color(0xFF8E8E93))
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    for (circle in ChannelRules.circles(shownRole, info.username.isNotEmpty(), muted)) {
                        // A follower's Search is dimmed when the page was not opened from the channel (iOS).
                        val dim = circle.dimmed || (circle.action == Action.SEARCH && onSearch == null)
                        ActionCircle(iconFor(circle.action), circle.label, dim) { tap(circle.action, circle.dimmed) }
                    }
                }
                Spacer(Modifier.height(20.dp))
                // About: the description and the public link.
                if (info.description.isNotEmpty() || info.username.isNotEmpty()) Card {
                    if (info.description.isNotEmpty()) Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.AutoMirrored.Filled.Notes, null, tint = c.label, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(14.dp))
                            Text("Description", fontSize = 17.sp, color = c.label)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(info.description, fontSize = 17.sp, color = c.label)
                    }
                    if (info.username.isNotEmpty()) {
                        if (info.description.isNotEmpty()) HorizontalDivider(thickness = 0.5.dp, color = c.separator, modifier = Modifier.padding(start = 16.dp))
                        Row(Modifier.fillMaxWidth().clickable { share() }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(ImageVector.vectorResource(com.fariin.app.R.drawable.ic_link_copy), null, tint = c.label, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text("fariin.com/${info.username}", fontSize = 17.sp, color = c.label)
                                Text("Link", fontSize = 14.sp, color = c.secondary)
                            }
                            Icon(Icons.Filled.QrCode2, "QR code", tint = c.secondary, modifier = Modifier.size(24.dp))
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                Card {
                    val admin = role == Role.ADMIN && team != null
                    com.fariin.app.ui.settings.SettingsRow("Sounds & Notifications",
                        icon = androidx.compose.ui.res.painterResource(if (muted) com.fariin.app.R.drawable.ic_bell_off else com.fariin.app.R.drawable.ic_bell),
                        value = if (muted && shownRole != Role.VISITOR) "Off" else "On", badge = NewBadgeSpots.GROUP_INFO_SOUNDS) {
                        if (shownRole == Role.VISITOR) notice = "Follow this channel to change its notifications."
                        else tap(if (muted) Action.UNMUTE else Action.MUTE, false)
                    }
                    com.fariin.app.ui.settings.SettingsRow("Storage Used", icon = androidx.compose.ui.res.painterResource(com.fariin.app.R.drawable.ic_set_storage),
                        last = !admin, badge = NewBadgeSpots.GROUP_INFO_STORAGE) { sub = "storage" }
                    if (admin) {
                        ChannelManageRows(info, team!!, me, shownCount, manage, last = false)
                        com.fariin.app.ui.settings.SettingsRow("Statistics", icon = androidx.compose.ui.res.painterResource(com.fariin.app.R.drawable.ic_statistics),
                            last = true, badge = NewBadgeSpots.GROUP_INFO_STATISTICS) { sub = "stats" }
                    }
                    // Channel Type and Protect Content live on Channel Settings (as iOS).
                }
                Spacer(Modifier.height(20.dp))
                Card {
                    if (role != Role.ADMIN) FootRow(if (reported) "Reported" else "Report Channel", ImageVector.vectorResource(com.fariin.app.R.drawable.ic_report),
                        if (reported) c.secondary else c.red) { if (!reported) confirmReport = true }
                    if (role == Role.FOLLOWER) {
                        if (com.fariin.app.ui.settings.SHOW_ROW_LINES) HorizontalDivider(thickness = 0.5.dp, color = c.separator, modifier = Modifier.padding(start = 52.dp))
                        FootRow("Leave Channel", ImageVector.vectorResource(com.fariin.app.R.drawable.ic_channel_leave), c.red) { confirmLeave = true }
                    }
                    if (role == Role.ADMIN && team?.isOwner(me) == true) FootRow("Delete Channel", Icons.Outlined.Delete, c.red) { askDelete = true }
                    else if (role == Role.ADMIN) Text("You run this channel. Only admins can post.", fontSize = 15.sp, color = c.secondary,
                        modifier = Modifier.padding(16.dp))
                }
                if (info.createdAtMillis > 0) {
                    Text("Created ${createdLine(ctx, info.createdAtMillis)}.", fontSize = 13.sp, color = c.secondary,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp))
                }
                error?.let { Text(it, fontSize = 13.sp, color = c.red, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) }
                Spacer(Modifier.height(80.dp))
            }
        }
        AnimatedVisibility(undo, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(24.dp)) {
            Row(Modifier.floating(CircleShape).clickable { leave(fromUndo = true) }.padding(horizontal = 18.dp, vertical = 12.dp)) {
                Text("Following “${info.title}”", fontSize = 15.sp, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(14.dp))
                Text("Undo", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.accent)
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun iconFor(a: Action): ImageVector = when (a) {
    Action.FOLLOW -> Icons.Filled.Add
    Action.LEAVE -> Icons.AutoMirrored.Outlined.ExitToApp
    Action.SHARE -> Icons.Outlined.Share
    Action.SEARCH -> Icons.Filled.Search
    Action.MUTE -> ImageVector.vectorResource(com.fariin.app.R.drawable.ic_bell_off)
    Action.UNMUTE -> ImageVector.vectorResource(com.fariin.app.R.drawable.ic_bell)
}

@Composable
private fun ActionCircle(icon: ImageVector, label: String, dimmed: Boolean, onClick: () -> Unit) {
    val c = Fariin.colors
    Column(Modifier.width(72.dp).alpha(if (dimmed) 0.45f else 1f).clickable(onClick = onClick).semantics { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(52.dp).floating(), contentAlignment = Alignment.Center) {
            // The owner's bells for Mute / Unmute, as iOS (ic_bell_off mutes, ic_bell unmutes).
            val own = when (label) {
                "Mute" -> com.fariin.app.R.drawable.ic_bell_off; "Unmute" -> com.fariin.app.R.drawable.ic_bell
                "Leave" -> com.fariin.app.R.drawable.ic_channel_leave; "Share" -> com.fariin.app.R.drawable.ic_link_share
                else -> null
            }
            if (own != null) Icon(androidx.compose.ui.res.painterResource(own), null, tint = c.label, modifier = Modifier.size(24.dp))
            else Icon(icon, null, tint = c.label, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 13.sp, color = c.label, maxLines = 1)
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Fariin.colors.card)) { content() }
}

@Composable
private fun FootRow(title: String, icon: ImageVector, tint: Color, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(title, fontSize = 17.sp, color = tint)
    }
}

/** The admin's part of the page: Channel Type (read only here) and Protect Content. */
@Composable
private fun AdminCard(info: ChannelInfo) {
    val c = Fariin.colors
    val scope = rememberCoroutineScope()
    var protect by remember(info.restricted) { mutableStateOf(info.restricted) }
    var problem by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth()) {
        Text("CHANNEL SETTINGS", fontSize = 13.sp, color = c.secondary, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp))
        Card {
            Row(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Channel Type", fontSize = 17.sp, color = c.label, modifier = Modifier.weight(1f))
                Text(if (info.isPublic) "Public" else "Private", fontSize = 17.sp, color = c.secondary)
            }
            HorizontalDivider(thickness = 0.5.dp, color = c.separator, modifier = Modifier.padding(start = 16.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Protect Content", fontSize = 17.sp, color = c.label, modifier = Modifier.weight(1f))
                com.fariin.app.ui.components.FariinSwitch(protect, onCheckedChange = { v ->
                    protect = v; problem = null
                    scope.launch { try { ChannelService.setProtected(info.cid, v) } catch (e: Exception) { protect = !v; problem = "Couldn't save. Try again." } }
                }, colors = SwitchDefaults.colors(checkedTrackColor = com.fariin.app.ui.theme.Fariin.colors.accent, checkedThumbColor = com.fariin.app.ui.theme.Fariin.colors.onAccent))
            }
        }
        Text(problem ?: ChannelRules.protectFooter(protect), fontSize = 13.sp, color = if (problem != null) c.red else c.secondary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
    }
}

private fun createdLine(ctx: android.content.Context, ms: Double): String {
    val day = java.text.SimpleDateFormat("d MMMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(ms.toLong()))
    val time = DateFormat.getTimeFormat(ctx).format(java.util.Date(ms.toLong()))
    return "on $day at $time"
}

/** The channel page's three-dot menu, shown only to whoever may change its info: Change Wallpaper. */
@Composable
private fun ChannelInfoMenu(onWallpaper: () -> Unit) {
    val c = Fariin.colors
    var open by remember { mutableStateOf(false) }
    Box {
        com.fariin.app.ui.components.FloatingIcon(androidx.compose.material.icons.Icons.Filled.MoreVert, contentDescription = "More",
            onClick = { open = true }, modifier = Modifier.padding(end = 4.dp))
        androidx.compose.material3.DropdownMenu(open, onDismissRequest = { open = false }, shape = com.fariin.app.ui.components.FariinMenu.shape,
            border = com.fariin.app.ui.components.FariinMenu.border, containerColor = c.card) {
            androidx.compose.material3.DropdownMenuItem(text = { Text("Change Wallpaper", color = c.label) },
                leadingIcon = { Icon(ImageVector.vectorResource(com.fariin.app.R.drawable.ic_wallpaper), null, tint = c.label) },
                onClick = { open = false; onWallpaper() })
        }
    }
}
