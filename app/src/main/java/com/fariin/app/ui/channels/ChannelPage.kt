package com.fariin.app.ui.channels

import androidx.compose.foundation.combinedClickable
import com.fariin.app.ui.photo.profilePhotoSource
import com.fariin.app.ui.components.profileMeltBarGround
import com.fariin.app.ui.components.profileMeltSource
import com.fariin.app.ui.components.ProfileDockTitle
import androidx.compose.ui.graphics.graphicsLayer
import com.fariin.app.R
import com.kyant.backdrop.backdrops.layerBackdrop
import com.fariin.app.ui.components.glassMenuAnchor
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
import com.fariin.app.ui.components.GlassActionCircle
import kotlinx.coroutines.tasks.await
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
    ChannelPage(base, shown, ChannelRules.role(base, me, entries.containsKey(cid)), ChannelService.mutedFor(cid, doc, me), onBack, onLeft,
        team = com.fariin.app.channel.ChannelAdmins.Team.from(doc), me = me, manage = manage,
        onSearch = if (fromChat) ({ com.fariin.app.ui.thread.InChatSearchLink.searchFromInfo(cid, onBack) }) else null)
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
    var confirmLeave by remember { mutableStateOf(false) }
    // iOS channel info rows that open a page here: Storage Used and Statistics.
    var sub by remember(info.cid) { mutableStateOf<String?>(null) }
    var shareLink by remember { mutableStateOf<Pair<String, String>?>(null) }
    var moreOpen by remember { mutableStateOf(false) }
    // The More circle's glass menu grows out of the circle (GlassMenu), over the page's own layer.
    val moreAnchor = remember { com.fariin.app.ui.components.GlassMenuAnchor() }
    val muteAnchor = remember { com.fariin.app.ui.components.GlassMenuAnchor() }
    var muteMenu by remember { mutableStateOf(false) }
    val menuBackdrop = com.kyant.backdrop.backdrops.rememberLayerBackdrop()
    val menuGlass = com.fariin.app.perf.rememberPowerState().glass() && android.os.Build.VERSION.SDK_INT >= 33
    var showQr by remember { mutableStateOf(false) }
    val shared = com.fariin.app.ui.people.rememberSharedMedia(info.cid, me)
    var allMedia by remember { mutableStateOf(false) }
    // Storage Used's value (iOS: the size, or "None"): this phone's copy of the posts' files, no network.
    val channelStorage by androidx.compose.runtime.produceState<Long?>(null, info.cid, me) {
        if (me.isEmpty()) return@produceState
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val app = ctx.applicationContext
                val crypto = com.fariin.app.data.CryptoHub.get(app)
                val msgs = com.fariin.app.data.SharedMediaSource(info.cid, me, crypto).local()
                com.fariin.app.ui.people.ChatStorageRules.items(msgs, com.fariin.app.media.ChatMedia(app, info.cid, crypto)) { false }.sumOf { it.bytes }
            }.getOrNull()
        }
    }
    var askDelete by remember { mutableStateOf(false) }
    // iOS: Edit Channel is a sheet over this page.
    var editSheet by remember { mutableStateOf(false) }
    if (editSheet) ChannelEditSheet(info.cid, me, onClosed = { editSheet = false })
    // Its pages slide over it like a pushed page (owner 2026-11-10: "they open raw").
    com.fariin.app.ui.components.InnerPageDepth(if (allMedia || sub == "storage" || sub == "stats" || sub == "sounds") 1 else 0)
    if (allMedia) { com.fariin.app.ui.people.SharedMediaScreen(info.cid, shared, onBack = { allMedia = false }); return }
    when (sub) {
        "storage" -> { com.fariin.app.ui.people.ChatStoragePage(info.cid, me, info.title, pop = { sub = null }); return }
        "stats" -> { com.fariin.app.ui.people.GroupStatsPage(info.cid, info.title, onBack = { sub = null }); return }
        // iOS SoundsNotificationsView for a channel: Mute with its times, not a one-tap toggle (owner 2026-11-08).
        "sounds" -> {
            val follows by com.fariin.app.channel.ChannelFollows.entries.collectAsState()
            val e = follows[info.cid]
            val until = when {
                e == null || !e.isMuted() -> 0.0
                e.mutedUntil <= 0 -> com.fariin.app.data.GroupInfoCircles.muteUntil(null)   // Always
                else -> e.mutedUntil
            }
            com.fariin.app.ui.people.ContactSoundsPage(until, header = if (role == Role.ADMIN) "Posts by other admins" else "Posts",
                onMute = { u -> scope.launch { if (u <= 0) ChannelService.setMuted(info.cid, false) else ChannelService.setMuted(info.cid, true, u) } },
                onBack = { sub = null })
            return
        }
    }
    // Clear Messages (iOS, owner only): every post goes for everyone; the followers stay.
    var askClear by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    if (askClear) com.fariin.app.ui.components.GlassAlert(onDismissRequest = { askClear = false },
        title = { Text("Clear all messages?") },
        text = { Text("All posts in this channel will be deleted for everyone. Your followers stay. You can't undo this.") },
        confirmButton = { androidx.compose.material3.TextButton({
            askClear = false
            clearing = true
            scope.launch {
                val ok = runCatching {
                    val fn = com.google.firebase.functions.FirebaseFunctions.getInstance(com.fariin.app.data.AuthRepository.FUNCTIONS_REGION)
                        .getHttpsCallable("clearGroupMessages").withTimeout(540, java.util.concurrent.TimeUnit.SECONDS)
                    val data = mutableMapOf<String, Any>("cid" to info.cid)
                    for (round in 0 until 12) {
                        val d = fn.call(data).await().getData() as? Map<*, *> ?: break
                        if (d["more"] != true) break
                        d["before"]?.let { data["before"] = it }
                    }
                }.isSuccess
                clearing = false
                com.fariin.app.ui.components.Snack.show(if (ok) "All messages were cleared." else "Couldn't clear the messages. Check your connection and try again.")
            }
        }) { Text("Clear", color = c.red) } },
        dismissButton = { androidx.compose.material3.TextButton({ askClear = false }) { Text("Cancel", color = c.accent) } },
        containerColor = c.card)
    if (askDelete) com.fariin.app.ui.components.GlassAlert(onDismissRequest = { askDelete = false },
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

    // A private channel's link, read once the page opens: Share then shows its sheet at once
    // (owner 2026-11-08: "it comes out a little late", the tap waited on the network).
    val mayInvite = info.username.isEmpty() && role == Role.ADMIN && team?.can(me, com.fariin.app.channel.ChannelAdmins.Right.INVITE_USERS) == true
    var preLink by remember(info.cid) { mutableStateOf<String?>(null) }
    LaunchedEffect(info.cid, mayInvite) { if (mayInvite && preLink == null) preLink = com.fariin.app.data.ChannelAddFollowers.existingLink(info.cid) }
    fun share() {
        // A private channel: its invite link, for an admin who may invite (iOS 1280e92d, More > Share Channel).
        if (mayInvite) {
            preLink?.let { shareLink = it to com.fariin.app.data.ChannelAddFollowers.shareText(info.title, it); return }
            scope.launch {
                val link = com.fariin.app.data.ChannelAddFollowers.link(info.cid, mapOf("username" to info.username, "isPublic" to info.isPublic))
                    ?: run { notice = com.fariin.app.data.ChannelAddFollowers.NO_LINK; return@launch }
                shareLink = link to com.fariin.app.data.ChannelAddFollowers.shareText(info.title, link)
            }
            return
        }
        val text = ChannelRules.shareText(info.title, info.username) ?: run { notice = "This channel has no public link."; return }
        // Our own "Share with" sheet (iOS), not the system share page; that sits in its corner.
        shareLink = "https://fariin.com/${info.username}" to text
    }
    val followCtx = androidx.compose.ui.platform.LocalContext.current
    fun follow() {
        com.fariin.app.ui.components.SuccessHaptic.soft(followCtx)   // a soft tap: it did something (owner 2026-11-13)
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
        if (fromUndo) {
            scope.launch {
                try { ChannelService.unfollow(info.cid); following = false }
                catch (e: Exception) { error = "Couldn't undo. Use Leave Channel on the channel page." }
            }
            return
        }
        // At the tap (iOS 2026-11-08): the page closes at once and the server call runs behind it.
        // If it fails the follow is still there, so the channel stays in the list, and a note says so.
        com.fariin.app.data.LeaveUndo.hideWhile(info.cid, "Couldn't leave the channel. Check your connection and try again.") {
            ChannelService.unfollow(info.cid)
        }
        onLeft()
    }
    fun tap(a: Action, dimmed: Boolean) {
        if (dimmed) { ChannelRules.visitorNotice(a)?.let { notice = it }; if (a == Action.SHARE) notice = "This channel has no public link."; return }
        when (a) {
            Action.FOLLOW -> follow()
            Action.LEAVE -> if (role == Role.VISITOR) leave(fromUndo = true) else confirmLeave = true
            Action.SHARE -> share()
            Action.SEARCH -> onSearch?.invoke()
            // iOS: the times menu ("Mute this chat for..."), not an instant mute (owner 2026-11-08).
            Action.MUTE -> muteMenu = true
            Action.UNMUTE -> scope.launch { ChannelService.setMuted(info.cid, false) }
            Action.MORE -> moreOpen = true
        }
    }

    notice?.let { n ->
        com.fariin.app.ui.components.GlassAlert(onDismissRequest = { notice = null }, text = { Text(n) }, containerColor = c.card,
            confirmButton = { TextButton({ notice = null }) { Text("OK", color = c.accent) } })
    }
    if (showQr) com.fariin.app.ui.settings.ChannelQrSheet(info.title.ifEmpty { "Channel" }, "https://fariin.com/" + info.username,
        info.avatarUrl, shownCount, onClose = { showQr = false })
    shareLink?.let { (url, words) -> if (me.isNotEmpty()) com.fariin.app.ui.thread.LinkShareSheet(me, url, words, onDismiss = { shareLink = null }) }
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
    // No bar over the photo (owner 2026-11-11: the solid bar cut the channel photo in half; the
    // reference app's channel page has only a thin bar once scrolled): the photo melts into the top
    // as the page scrolls, then the bar's ground and the name come in, as the group and contact pages.
    val melt = com.fariin.app.ui.components.rememberProfileMelt(pageScroll)
    // The photo viewer (iOS GroupInfoView for a channel: ProfilePhotoViewer landsSquare + squareCrop
    // + squircle): out of the rounded square, landing on a 1:1 square, never through a circle. The
    // bar's items step aside until it has landed back.
    val photo = com.fariin.app.ui.photo.rememberProfilePhotoState()
    val photoHaptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    Box(Modifier.fillMaxSize().background(c.bgSecondary)) {
        com.fariin.app.ui.components.GlassBarPage(underBar, modifier = if (menuGlass) Modifier.layerBackdrop(menuBackdrop) else Modifier, frosted = true,
            clear = true, enabled = false, bar = {
            Box(Modifier.graphicsLayer { alpha = if (photo.chromeHidden) 0f else 1f }
                .then(Modifier.profileMeltBarGround(melt, c.bgSecondary))) {
            ProfileDockTitle(melt, info.title.ifEmpty { "Channel" }, ChannelRules.pageCountLine(shownCount))
            // Edit at the end of the bar for an admin who may change the channel's info (iOS Group Info's Edit).
            // The ••• menu holds Change Wallpaper for the same people (iOS Group Info's first menu
            // section): back to the chat, which opens the channel's shared picker for every follower.
            androidx.compose.runtime.CompositionLocalProvider(com.fariin.app.ui.components.LocalPlainBarButtons provides true) { PageBar("", onBack, action = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // iOS: the Add Story mark for whoever may post, then Edit; the rest is under More.
                    com.fariin.app.ui.stories.GroupStoryButton(me, info.cid, onLeave = onBack)
                    if (canEdit) com.fariin.app.ui.components.FloatingTextAction("Edit") { editSheet = true }
                }
            }) }
            }
        }) { top ->
            Column(Modifier.fillMaxSize().verticalScroll(pageScroll).padding(top = top).padding(horizontal = 16.dp).navigationBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally) {
                // A story and a photo: the tap asks which (iOS dd1a37ac); else as before.
                // iOS: tap = the story when there is one, else the photo; a hold is always the photo.
                val photoChoice = com.fariin.app.ui.people.headerPhotoOrStory(info.cid, info.title.ifEmpty { "Channel" }, info.avatarUrl, me, emptyList(),
                    onPhoto = if (photo.hasPhoto) ({ photo.show() }) else null)
                Avatar(info.avatarUrl, 132.dp, squircle = true, kind = com.fariin.app.ui.components.AvatarKind.CHANNEL,
                    onResolved = photo::resolved,
                    modifier = Modifier.profilePhotoSource(photo).then(Modifier.profileMeltSource(melt)).then(
                    if (photoChoice != null) Modifier.combinedClickable(onClickLabel = "View story", onClick = photoChoice.tap,
                        onLongClickLabel = "View photo", onLongClick = photoChoice.hold)
                    else if (photo.hasPhoto) Modifier.combinedClickable(onClickLabel = "View photo", onClick = { photo.show() },
                        onLongClickLabel = "View photo", onLongClick = {
                            photoHaptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            photo.show()
                        }).semantics { contentDescription = "Channel photo" }
                    else if (canEdit) Modifier.clickable { editSheet = true } else Modifier))
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(info.title.ifEmpty { "Channel" }, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = c.label,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    if (info.verified) {
                        Spacer(Modifier.width(6.dp))
                        com.fariin.app.ui.components.VerifiedSeal(c.blue, Modifier.size(22.dp), "Verified")
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(ChannelRules.pageCountLine(shownCount), fontSize = 16.sp, color = Color(0xFF8E8E93))
                // No description yet: whoever may edit is offered one, in grey (iOS, as a group's).
                if (info.description.isEmpty() && canEdit) Text("Add channel description...", fontSize = 17.sp, color = c.secondary,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(8.dp))
                        .clickable { editSheet = true }.padding(horizontal = 8.dp, vertical = 4.dp))
                Spacer(Modifier.height(18.dp))
                // iOS followerCircles: equal shares of the row (the page's own 16 dp margins).
                Row(Modifier.fillMaxWidth()) {
                    for (circle in ChannelRules.circles(shownRole, info.username.isNotEmpty(), muted)) {
                        // A follower's Search is dimmed when the page was not opened from the channel (iOS).
                        val dim = circle.dimmed || (circle.action == Action.SEARCH && onSearch == null)
                        if (circle.action == Action.MUTE) Box(Modifier.weight(1f).glassMenuAnchor(muteAnchor)) {
                            ActionCircle(circle.action, circle.label, dim, Modifier.fillMaxWidth()) { tap(circle.action, circle.dimmed) }
                        }
                        else if (circle.action == Action.MORE) Box(Modifier.weight(1f).glassMenuAnchor(moreAnchor)) {
                            ActionCircle(circle.action, circle.label, dim, Modifier.fillMaxWidth()) { tap(circle.action, circle.dimmed) }
                        }
                        else ActionCircle(circle.action, circle.label, dim, Modifier.weight(1f)) { tap(circle.action, circle.dimmed) }
                    }
                }
                Spacer(Modifier.height(20.dp))
                // About: the description and the public link.
                if (info.description.isNotEmpty() || info.username.isNotEmpty()) Card {
                    if (info.description.isNotEmpty()) Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(ImageVector.vectorResource(R.drawable.ic_sf_text_alignleft), null, tint = c.label, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(14.dp))
                            Text("Description", fontSize = 17.sp, color = c.label)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(info.description, fontSize = 17.sp, color = c.label)
                    }
                    if (info.username.isNotEmpty()) {
                        if (info.description.isNotEmpty()) HorizontalDivider(thickness = 0.5.dp, color = c.separator, modifier = Modifier.padding(start = 16.dp))
                        Row(Modifier.fillMaxWidth().clickable { share() }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(ImageVector.vectorResource(R.drawable.ic_sf_link), null, tint = c.label, modifier = Modifier.size(24.dp))   // the chain, as iOS
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text("fariin.com/${info.username}", fontSize = 17.sp, color = c.label)
                                Text("Link", fontSize = 14.sp, color = c.secondary)
                            }
                            // The link opens Share with; the QR mark opens the code (iOS, owner 2026-11-10).
                            Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClickLabel = "QR code") { showQr = true },
                                contentAlignment = Alignment.Center) {
                                Icon(ImageVector.vectorResource(R.drawable.ic_qr), "QR code", tint = c.secondary, modifier = Modifier.size(24.dp))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                Card {
                    val admin = role == Role.ADMIN && team != null
                    com.fariin.app.ui.settings.SettingsRow("Sounds & Notifications",
                        icon = androidx.compose.ui.res.painterResource(if (muted) com.fariin.app.R.drawable.ic_bell_off_outline else com.fariin.app.R.drawable.ic_notifications),
                        value = if (muted && shownRole != Role.VISITOR) "Off" else "On", badge = NewBadgeSpots.GROUP_INFO_SOUNDS) {
                        if (shownRole == Role.VISITOR) notice = "Follow this channel to change its notifications."
                        else sub = "sounds"
                    }
                    com.fariin.app.ui.settings.SettingsRow("Storage Used", icon = androidx.compose.ui.res.painterResource(com.fariin.app.R.drawable.ic_set_storage),
                        value = channelStorage?.let { if (it > 0) android.text.format.Formatter.formatShortFileSize(ctx, it) else "None" },
                        last = !admin, badge = NewBadgeSpots.GROUP_INFO_STORAGE) { sub = "storage" }
                    if (admin) {
                        ChannelManageRows(info, team!!, me, shownCount, manage, last = false)
                        // The same groupStats switch as groups (iOS GroupInfoView).
                        if (com.fariin.app.data.AppFeatures.groupStats) com.fariin.app.ui.settings.SettingsRow("Statistics", icon = androidx.compose.ui.res.painterResource(com.fariin.app.R.drawable.ic_statistics),
                            last = true, badge = NewBadgeSpots.GROUP_INFO_STATISTICS) { sub = "stats" }
                    }
                    // Channel Type and Protect Content live on Channel Settings (as iOS).
                }
                // iOS: All Media under the settings card, when the channel has photos or videos.
                if (me.isNotEmpty()) com.fariin.app.ui.people.SharedMediaCard(info.cid, shared, inset = 0.dp) { allMedia = true }
                Spacer(Modifier.height(20.dp))
                Card {
                    // Leave first, then Report (iOS order), with the Leave Group arrow.
                    if (role == Role.FOLLOWER) FootRow("Leave Channel", ImageVector.vectorResource(R.drawable.ic_channel_leave), c.red) { confirmLeave = true }
                    if (role != Role.ADMIN) {
                        if (role == Role.FOLLOWER && com.fariin.app.ui.settings.SHOW_ROW_LINES) HorizontalDivider(thickness = 0.5.dp, color = c.separator, modifier = Modifier.padding(start = 52.dp))
                        FootRow(if (reported) "Reported" else "Report Channel", ImageVector.vectorResource(com.fariin.app.R.drawable.ic_report),
                            if (reported) c.secondary else c.red) {
                            if (!reported) com.fariin.app.data.ReportFlow.ask(com.fariin.app.data.ReportTarget(com.fariin.app.data.ReportKind.CHANNEL,
                                name = info.title, cid = info.cid, onSent = { reported = true },
                                onLeave = if (role == Role.FOLLOWER) ({ leave(fromUndo = false) }) else null))
                        }
                    }
                    if (role == Role.ADMIN && team?.isOwner(me) == true) {
                        FootRow(if (clearing) "Clearing\u2026" else "Clear Messages", ImageVector.vectorResource(R.drawable.ic_sf_eraser), c.red) { if (!clearing) askClear = true }
                        FootRow("Delete Channel", ImageVector.vectorResource(R.drawable.ic_sf_trash), c.red) { askDelete = true }
                    }
                    else if (role == Role.ADMIN) Text("You run this channel. Only admins can post.", fontSize = 15.sp, color = c.secondary,
                        modifier = Modifier.padding(16.dp))
                }
                if (info.createdAtMillis > 0) {
                    Text((if (team?.isOwner(me) == true) "Created by you.\n" else "") + "Created ${createdLine(ctx, info.createdAtMillis)}.", fontSize = 13.sp, color = c.secondary,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp))
                }
                error?.let { Text(it, fontSize = 13.sp, color = c.red, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) }
                Spacer(Modifier.height(30.dp))   // as the group page; 80 left a big empty band (owner 2026-11-08)
            }
        }
        run {
            val ctx2 = LocalContext.current
            var saveOn by remember(info.cid, moreOpen) { mutableStateOf(com.fariin.app.media.AutoSaveMedia.savesMedia(ctx2, info.cid)) }
            com.fariin.app.ui.components.GlassMenu(moreOpen, moreAnchor, buildList {
                if (canEdit) add(com.fariin.app.ui.components.GlassMenuItem("Change Wallpaper",
                    androidx.compose.ui.res.painterResource(com.fariin.app.R.drawable.ic_wallpaper)) {
                    com.fariin.app.ui.settings.ChatWallpaperLink.request(info.cid); onBack() })
                add(com.fariin.app.ui.components.GlassMenuItem("Share Channel",
                    androidx.compose.ui.res.painterResource(com.fariin.app.R.drawable.ic_share_box)) { share() })
                if (info.username.isNotEmpty()) add(com.fariin.app.ui.components.GlassMenuItem("QR Code",
                    androidx.compose.ui.res.painterResource(com.fariin.app.R.drawable.ic_qr)) { showQr = true })
                add(com.fariin.app.ui.components.GlassMenuItem("Save to Photos: " + (if (saveOn) "On" else "Off"),
                    androidx.compose.ui.res.painterResource(com.fariin.app.R.drawable.ic_save_down), divider = true,
                        submenu = listOf(true, false).map { on -> com.fariin.app.ui.components.GlassMenuItem(if (on) "On" else "Off", null, checked = saveOn == on) {
                            saveOn = on; com.fariin.app.media.AutoSaveMedia.setChat(ctx2, info.cid, on) } }))
            }, backdrop = if (menuGlass) menuBackdrop else null, onDismiss = { moreOpen = false })
            com.fariin.app.ui.components.GlassMenu(muteMenu, muteAnchor,
                com.fariin.app.data.GroupInfoCircles.MUTE_CHOICES.map { (label, hours) ->
                    com.fariin.app.ui.components.GlassMenuItem(label, null) {
                        scope.launch { ChannelService.setMuted(info.cid, true, com.fariin.app.data.GroupInfoCircles.muteUntil(hours)) }
                    }
                }, backdrop = if (menuGlass) menuBackdrop else null, caption = "Mute this chat for…", onDismiss = { muteMenu = false })
        }
        AnimatedVisibility(undo, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(24.dp)) {
            Row(Modifier.floating(CircleShape).clickable { leave(fromUndo = true) }.padding(horizontal = 18.dp, vertical = 12.dp)) {
                Text("Following “${info.title}”", fontSize = 15.sp, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(14.dp))
                Text("Undo", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.accent)
            }
        }
        // Over the page, under the photo viewer: the photo docking into the bar beside back (ProfileDock).
        com.fariin.app.ui.components.ProfileDockOverlay(melt, 132.dp, squircle = true) {
            Avatar(info.avatarUrl, 132.dp, squircle = true, kind = com.fariin.app.ui.components.AvatarKind.CHANNEL)
        }
        // Over everything on the page: the channel's photo, out of its rounded square and back.
        com.fariin.app.ui.photo.ProfilePhotoViewer(photo, info.avatarUrl,
            style = com.fariin.app.ui.photo.ProfilePhotoStyle(landsSquare = true, squareCrop = true, squircle = true))
    }
}

/** The circle for [a], with the glyph iOS draws for it (followerCircles). */
@Composable
private fun ActionCircle(a: Action, label: String, dimmed: Boolean, modifier: Modifier, onClick: () -> Unit) {
    // The owner's own drawings where iOS uses them ("ic_channel_leave", the bells); the SF Symbols
    // (plus, square.and.arrow.up, magnifyingglass) as the matching outline icons.
    // iOS icons only (owner 2026-11-08): our iOS drawings, or our own in their style.
    val own = when (a) {
        Action.LEAVE -> com.fariin.app.R.drawable.ic_channel_leave
        Action.MUTE -> com.fariin.app.R.drawable.ic_bell_off
        Action.UNMUTE -> com.fariin.app.R.drawable.ic_bell
        Action.FOLLOW -> com.fariin.app.R.drawable.ic_plus_ios
        Action.SHARE -> com.fariin.app.R.drawable.ic_share_box   // owner 2026-11-10
        Action.MORE -> com.fariin.app.R.drawable.ic_more_ios
        Action.SEARCH -> com.fariin.app.R.drawable.ic_search_ios
    }
    GlassActionCircle(label, androidx.compose.ui.res.painterResource(own), modifier, dimmed = dimmed, onClick = onClick)
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
    // iOS: "Oct 30, 2026 at 12:19 PM", as a group's footer.
    return com.fariin.app.data.MessageInfoRules.dateLine(ms, DateFormat.is24HourFormat(ctx))
}

/** The More circle's menu (iOS, the owner's page): Change Wallpaper, Share Channel, QR Code, then Save to Photos. */
@Composable
private fun ChannelMoreMenu(open: Boolean, onDismiss: () -> Unit, onWallpaper: (() -> Unit)?, onShare: () -> Unit,
                            onQr: (() -> Unit)?, saveCid: String) {
    val c = Fariin.colors
    val ctx = LocalContext.current
    var saveOn by remember(saveCid, open) { mutableStateOf(com.fariin.app.media.AutoSaveMedia.savesMedia(ctx, saveCid)) }
    com.fariin.app.ui.components.GlassDropdown(open, onDismissRequest = onDismiss, shape = com.fariin.app.ui.components.FariinMenu.shape,
        border = com.fariin.app.ui.components.FariinMenu.border, containerColor = c.card) {
        if (onWallpaper != null) androidx.compose.material3.DropdownMenuItem(text = { Text("Change Wallpaper", color = c.label) },
            leadingIcon = { Icon(ImageVector.vectorResource(com.fariin.app.R.drawable.ic_wallpaper), null, tint = c.label) },
            onClick = { onDismiss(); onWallpaper() })
        androidx.compose.material3.DropdownMenuItem(text = { Text("Share Channel", color = c.label) },
            leadingIcon = { Icon(ImageVector.vectorResource(R.drawable.ic_link_share), null, tint = c.label) },
            onClick = { onDismiss(); onShare() })
        if (onQr != null) androidx.compose.material3.DropdownMenuItem(text = { Text("QR Code", color = c.label) },
            leadingIcon = { Icon(ImageVector.vectorResource(com.fariin.app.R.drawable.ic_qr), null, tint = c.label) },
            onClick = { onDismiss(); onQr() })
        androidx.compose.material3.HorizontalDivider(color = c.separator)
        androidx.compose.material3.DropdownMenuItem(text = { Text("Save to Photos: " + (if (saveOn) "On" else "Off"), color = c.label) },
            leadingIcon = { Icon(ImageVector.vectorResource(R.drawable.ic_save_down), null, tint = c.label) },
            onClick = { saveOn = !saveOn; com.fariin.app.media.AutoSaveMedia.setChat(ctx, saveCid, saveOn); onDismiss() })
    }
}
