package com.fariin.app.ui.channels

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
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
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
fun ChannelPageRoute(cid: String, me: String, onBack: () -> Unit, onLeft: () -> Unit) {
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
        Column(Modifier.fillMaxSize().background(c.bg)) {
            PageBar("", onBack)
            Text(refused ?: "", fontSize = 15.sp, color = c.secondary, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(32.dp))
        }
        return
    }
    @Suppress("UNUSED_VARIABLE") val b = bias.size
    // The visitor's preview already carries the admins in its count; the document does not.
    val shown = if (doc["channel"] == true) ChannelRules.subscriberCount(ChannelFollows.adjusted(cid, base.followerCount), base.users.size)
    else ChannelFollows.adjusted(cid, preview?.shownCount ?: base.followerCount)
    ChannelPage(base, shown, ChannelRules.role(base, me, entries.containsKey(cid)), entries[cid]?.isMuted() ?: false, onBack, onLeft)
}

@Composable
fun ChannelPage(info: ChannelInfo, shownCount: Int, role: Role, muted: Boolean, onBack: () -> Unit, onLeft: () -> Unit) {
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
    var reported by remember { mutableStateOf(false) }
    LaunchedEffect(role) { if (role != Role.VISITOR) following = false }
    LaunchedEffect(undo) { if (undo) { delay(5000); undo = false } }

    fun share() {
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
            Action.SEARCH -> notice = "Search in channels is coming soon on Android."
            Action.MUTE -> scope.launch { ChannelService.setMuted(info.cid, true, 0.0) }
            Action.UNMUTE -> scope.launch { ChannelService.setMuted(info.cid, false) }
        }
    }

    notice?.let { n ->
        AlertDialog(onDismissRequest = { notice = null }, text = { Text(n) }, containerColor = c.card,
            confirmButton = { TextButton({ notice = null }) { Text("OK", color = c.blue) } })
    }
    if (confirmReport) AlertDialog(onDismissRequest = { confirmReport = false }, containerColor = c.card,
        title = { Text("Report this channel?") }, text = { Text("Fariin's moderators will review it.") },
        confirmButton = { TextButton({ confirmReport = false; scope.launch { reported = ChannelService.report(info.cid) } }) { Text("Report", color = c.red) } },
        dismissButton = { TextButton({ confirmReport = false }) { Text("Cancel", color = c.blue) } })
    if (confirmLeave) AlertDialog(onDismissRequest = { confirmLeave = false }, containerColor = c.card,
        title = { Text("Leave ${info.title}?") }, text = { Text("You will stop getting its posts.") },
        confirmButton = { TextButton({ confirmLeave = false; leave(fromUndo = false) }) { Text("Leave Channel", color = c.red) } },
        dismissButton = { TextButton({ confirmLeave = false }) { Text("Cancel", color = c.blue) } })

    Box(Modifier.fillMaxSize().background(c.bg)) {
        Column(Modifier.fillMaxSize()) {
            PageBar("", onBack)
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Avatar(info.avatarUrl, 132.dp, squircle = true)
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
                        ActionCircle(iconFor(circle.action), circle.label, circle.dimmed) { tap(circle.action, circle.dimmed) }
                    }
                }
                Spacer(Modifier.height(20.dp))
                // About: the description and the public link.
                if (info.description.isNotEmpty() || info.username.isNotEmpty()) Card {
                    if (info.description.isNotEmpty()) {
                        Text(info.description, fontSize = 16.sp, color = c.label, modifier = Modifier.padding(16.dp))
                    }
                    if (info.username.isNotEmpty()) {
                        if (info.description.isNotEmpty()) HorizontalDivider(thickness = 0.5.dp, color = c.separator, modifier = Modifier.padding(start = 16.dp))
                        Column(Modifier.fillMaxWidth().clickable { share() }.padding(16.dp)) {
                            Text("fariin.com/${info.username}", fontSize = 16.sp, color = c.blue)
                            Text("Link", fontSize = 13.sp, color = c.secondary)
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                Card {
                    Row(Modifier.fillMaxWidth().clickable {
                        if (shownRole == Role.VISITOR) notice = "Follow this channel to change its notifications."
                        else tap(if (muted) Action.UNMUTE else Action.MUTE, false)
                    }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (muted) Icons.Filled.NotificationsOff else Icons.Filled.Notifications, null, tint = c.label, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(14.dp))
                        Text("Sounds & Notifications", fontSize = 17.sp, color = c.label, modifier = Modifier.weight(1f))
                        Text(if (muted && shownRole != Role.VISITOR) "Off" else "On", fontSize = 17.sp, color = c.secondary)
                    }
                }
                if (role == Role.ADMIN) {
                    Spacer(Modifier.height(20.dp))
                    AdminCard(info)
                }
                Spacer(Modifier.height(20.dp))
                Card {
                    if (role != Role.ADMIN) FootRow(if (reported) "Reported" else "Report Channel", Icons.Filled.Report,
                        if (reported) c.secondary else c.red) { if (!reported) confirmReport = true }
                    if (role == Role.FOLLOWER) {
                        HorizontalDivider(thickness = 0.5.dp, color = c.separator, modifier = Modifier.padding(start = 52.dp))
                        FootRow("Leave Channel", Icons.AutoMirrored.Filled.ExitToApp, c.red) { confirmLeave = true }
                    }
                    if (role == Role.ADMIN) Text("You run this channel. Only admins can post.", fontSize = 15.sp, color = c.secondary,
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
            Row(Modifier.clip(CircleShape).background(c.card).clickable { leave(fromUndo = true) }.padding(horizontal = 18.dp, vertical = 12.dp)) {
                Text("Following “${info.title}”", fontSize = 15.sp, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(14.dp))
                Text("Undo", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.blue)
            }
        }
    }
}

private fun iconFor(a: Action): ImageVector = when (a) {
    Action.FOLLOW -> Icons.Filled.Add
    Action.LEAVE -> Icons.AutoMirrored.Filled.ExitToApp
    Action.SHARE -> Icons.Filled.Share
    Action.SEARCH -> Icons.Filled.Search
    Action.MUTE -> Icons.Filled.NotificationsOff
    Action.UNMUTE -> Icons.Filled.Notifications
}

@Composable
private fun ActionCircle(icon: ImageVector, label: String, dimmed: Boolean, onClick: () -> Unit) {
    val c = Fariin.colors
    Column(Modifier.width(72.dp).alpha(if (dimmed) 0.45f else 1f).clickable(onClick = onClick).semantics { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(52.dp).clip(CircleShape).background(c.card), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = c.label, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 13.sp, color = c.label, maxLines = 1)
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Fariin.colors.card)) { content() }
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
                Switch(protect, onCheckedChange = { v ->
                    protect = v; problem = null
                    scope.launch { try { ChannelService.setProtected(info.cid, v) } catch (e: Exception) { protect = !v; problem = "Couldn't save. Try again." } }
                }, colors = SwitchDefaults.colors(checkedTrackColor = com.fariin.app.ui.theme.Fariin.colors.blue, checkedThumbColor = Color.White))
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
