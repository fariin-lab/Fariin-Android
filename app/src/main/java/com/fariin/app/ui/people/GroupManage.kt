package com.fariin.app.ui.people

import com.fariin.app.ui.settings.BarPage
import com.fariin.app.ui.components.underBar

import com.fariin.app.data.NewBadgeSpots
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.fariin.app.ui.components.listPress
import androidx.compose.foundation.border
import com.fariin.app.ui.components.floating
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.R
import com.fariin.app.data.GroupAdmins
import com.fariin.app.data.GroupAdmins.Right
import com.fariin.app.data.GroupAdmins.SendKind
import com.fariin.app.data.GroupAdmins.Team
import com.fariin.app.data.GroupLink
import com.fariin.app.data.GroupLinkList
import com.fariin.app.data.GroupManage
import com.fariin.app.ui.channels.PersonLine
import com.fariin.app.ui.channels.SwitchLine
import com.fariin.app.ui.components.Avatar
import com.fariin.app.ui.components.Snack
import com.fariin.app.ui.settings.PageBar
import com.fariin.app.ui.settings.SettingsRow
import com.fariin.app.ui.settings.SettingsSection
import com.fariin.app.ui.settings.Trailing
import com.fariin.app.ui.theme.Fariin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/*
 * The group's management pages for its owner and admins (Kulan-iOS GroupSettingsView joining /
 * people / permissions sections, GroupAdminsView, AddAdminPage, the member card GroupMemberSheet,
 * GroupTypeView, GroupLinksPage, GroupLinkEditPage). The Android way: pushed pages with the up
 * arrow and the back gesture, the member card as a bottom sheet, snackbars where iOS shows its
 * capsule. iOS words.
 */

/** A page pushed over Group Info. */
sealed interface GroupPage {
    data object Admins : GroupPage
    data object Choose : GroupPage
    data class AddAdmin(val uid: String) : GroupPage
    data class Rights(val uid: String) : GroupPage
    data object Permissions : GroupPage
    data object Type : GroupPage
    data object Links : GroupPage
    data class LinkEdit(val link: GroupLink?) : GroupPage
    data object Members : GroupPage
    data object Banned : GroupPage
    data object JoinRequests : GroupPage
    /** The owner's Choose Another Owner from the Leave dialog. */
    data object ChooseOwner : GroupPage
    /** iOS Group Settings › Recent Actions: the server's admin log (RecentActions.kt). */
    data object RecentActions : GroupPage
    /** iOS GroupInfoView › Statistics: the `groupStats` page (GroupStatsPage.kt). */
    data class Stats(val title: String) : GroupPage
    /** iOS GroupInfoView › Storage Used: this chat's files on this phone (ChatStorage.kt). */
    data class Storage(val title: String) : GroupPage
    /** iOS GroupSettingsView: the group's management on its own page, behind one row of the info page. */
    data object Settings : GroupPage

    /**
     * The stack as words, so it outlives a trip to another screen (a member's profile and back):
     * without this, Back from there landed on Group Info instead of the page the person was on.
     * An open link editor and Choose Another Owner are moments, not places: the saved stack stops
     * just below them.
     */
    companion object {
        fun save(stack: List<GroupPage>): List<String> {
            val out = mutableListOf<String>()
            for (p in stack) out += when (p) {
                Admins -> "admins"; Choose -> "choose"; is AddAdmin -> "addAdmin:" + p.uid; is Rights -> "rights:" + p.uid
                Permissions -> "permissions"; Type -> "type"; Links -> "links"; Members -> "members"; Banned -> "banned"
                JoinRequests -> "joinRequests"; RecentActions -> "recentActions"; Settings -> "settings"
                is Stats -> "stats:" + p.title; is Storage -> "storage:" + p.title
                is LinkEdit, ChooseOwner -> return out
            }
            return out
        }

        fun restore(words: List<String>): List<GroupPage> = words.mapNotNull { w ->
            val (kind, arg) = w.split(":", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            when (kind) {
                "admins" -> Admins; "choose" -> Choose; "addAdmin" -> AddAdmin(arg); "rights" -> Rights(arg)
                "permissions" -> Permissions; "type" -> Type; "links" -> Links; "members" -> Members; "banned" -> Banned
                "joinRequests" -> JoinRequests; "recentActions" -> RecentActions; "settings" -> Settings
                "stats" -> Stats(arg); "storage" -> Storage(arg)
                else -> null
            }
        }
    }
}

private fun failText(e: Exception, fallback: String = "Couldn't save. Try again."): String =
    if (e is com.google.firebase.firestore.FirebaseFirestoreException &&
        e.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) "You don't have the right to do that." else fallback

/**
 * Group Info's settings card for the owner and admins (iOS: Group Settings is theirs only): Group
 * Type (the owner), Invite Links (Add members right), Administrators, Permissions.
 */
@Composable
internal fun GroupManageRows(conv: Map<String, Any?>, me: String, cid: String = "", open: (GroupPage) -> Unit) {
    val team = Team.from(conv)
    if (!team.isStaff(me)) return
    val isPublic = conv["isPublic"] == true && !(conv["username"] as? String).isNullOrEmpty()
    val showType = team.isOwner(me) && team.may(me, Right.CHANGE_INFO)
    if (showType || team.may(me, Right.INVITE_USERS)) com.fariin.app.ui.settings.SettingsSection {
    if (showType) {
        SettingsRow("Group Type", icon = painterResource(R.drawable.ic_gs_group_type), value = if (isPublic) "Public" else "Private",
            badge = NewBadgeSpots.GROUP_SETTINGS_TYPE) { open(GroupPage.Type) }
    }
    if (team.may(me, Right.INVITE_USERS)) {
        SettingsRow("Invite Link", icon = rememberVectorPainter(ImageVector.vectorResource(com.fariin.app.R.drawable.ic_link_copy)),
            value = if ((conv["inviteCode"] as? String).isNullOrEmpty()) "Off" else "On", badge = NewBadgeSpots.GROUP_SETTINGS_INVITE_LINK) { open(GroupPage.Links) }
        // iOS GroupSettingsView joining section: the people waiting through a link with Approve New Members.
        val reqs = rememberJoinRequests(cid, allowed = cid.isNotEmpty() && !team.closed)
        if (cid.isNotEmpty()) SettingsRow("Join Requests", icon = painterResource(R.drawable.ic_gs_join_requests),
            value = com.fariin.app.data.JoinRequests.rowValue(reqs.size), last = true,
            badge = NewBadgeSpots.GROUP_SETTINGS_REQUESTS) { open(GroupPage.JoinRequests) }
    }
    }
    // iOS GroupSettingsView people section: Members, Administrators, Banned Users (Remove members right).
    com.fariin.app.ui.settings.SettingsSection {
    SettingsRow("Members", icon = painterResource(R.drawable.ic_gs_members), value = "${team.users.size}",
        badge = NewBadgeSpots.GROUP_SETTINGS_MEMBERS) { open(GroupPage.Members) }
    SettingsRow("Administrators", icon = painterResource(R.drawable.ic_gs_admins), value = "${team.sortedAdmins().size}",
        badge = NewBadgeSpots.GROUP_SETTINGS_ADMINS) { open(GroupPage.Admins) }
    if (team.may(me, Right.BAN_USERS)) {
        val banned = (conv["bannedUids"] as? List<*>)?.size ?: 0
        SettingsRow("Banned Users", icon = painterResource(R.drawable.ic_gs_banned), value = "$banned", last = true,
            badge = NewBadgeSpots.GROUP_SETTINGS_BANNED) { open(GroupPage.Banned) }
    }
    }
    val (on, of) = GroupAdmins.permissionCount(conv)
    com.fariin.app.ui.settings.SettingsSection {
    SettingsRow("Permissions", icon = painterResource(R.drawable.ic_gs_permissions), value = "$on/$of", last = true,
        badge = NewBadgeSpots.GROUP_SETTINGS_PERMISSIONS) { open(GroupPage.Permissions) }
    }
}

/**
 * Group Settings (iOS GroupSettingsView, owner 2026-10-18): who-can-do-what and the group's
 * management on their own page, so the info page is not a wall of admin rows. Chat (Disappearing
 * Messages, Recent Actions), then joining, people and permissions ([GroupManageRows]).
 */
@Composable
private fun GroupSettingsPage(cid: String, me: String, onBack: () -> Unit, open: (GroupPage) -> Unit, onTimer: () -> Unit) {
    val c = Fariin.colors
    val doc = liveGroupDoc(cid)
    BarPage("Group Settings", onBack, modifier = Modifier.background(c.bgSecondary)) { top ->
        androidx.compose.foundation.layout.Spacer(Modifier.height(top))
        if (doc.isEmpty()) { Loading(); return@BarPage }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            GroupManageRows(doc, me, cid, open)
            // Bubble Styles: back here, off the group page (iOS 2026-11-08).
            if (bubbleStylesSwitchShown(me, doc)) SettingsSection { BubbleStylesSwitchRow(cid, me, doc, last = true) }
            SettingsSection {
                DisappearingRow((doc["disappearSeconds"] as? Number)?.toInt() ?: 0, short = false,
                    badge = NewBadgeSpots.GROUP_SETTINGS_DISAPPEARING, onClick = onTimer)
                SettingsRow("Recent Actions", icon = rememberVectorPainter(ImageVector.vectorResource(R.drawable.ic_sf_clock_arrow)), last = true,
                    badge = NewBadgeSpots.GROUP_SETTINGS_RECENT_ACTIONS) { open(GroupPage.RecentActions) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Bubble Styles on or off for the whole group (server `setGroupBubbleStyles`, which writes the
 * server-only `bubbleStylesOff`). Only the owner, or an admin with the Change Info right, sees it:
 * the server refuses anyone else, members-may-edit-info included. While the write runs the switch
 * shows the new position and stays still; the group's doc then confirms it, or it springs back
 * with the reason.
 */
@Composable
internal fun BubbleStylesSwitchRow(cid: String, me: String, doc: Map<String, Any?>, last: Boolean) {
    if (!bubbleStylesSwitchShown(me, doc)) return
    val scope = rememberCoroutineScope()
    val off = doc["bubbleStylesOff"] == true
    var pending by remember { mutableStateOf<Boolean?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // The doc caught up with the write (or a slow listener: let the switch go after a while).
    LaunchedEffect(off, pending) {
        val p = pending ?: return@LaunchedEffect
        if (off == !p) pending = null else { delay(8_000); pending = null }
    }
    val on = pending ?: !off
    fun flip(to: Boolean) {
        if (pending != null) return
        pending = to
        scope.launch {
            val r = runCatching {
                com.google.firebase.functions.FirebaseFunctions.getInstance(com.fariin.app.data.AuthRepository.FUNCTIONS_REGION)
                    .getHttpsCallable("setGroupBubbleStyles").call(mapOf("cid" to cid, "off" to !to)).await()
            }
            r.exceptionOrNull()?.let { e ->
                pending = null
                error = (e as? com.google.firebase.functions.FirebaseFunctionsException)?.message?.takeIf { it.isNotBlank() }
                    ?: "Check your connection and try again."
            } ?: Snack.show(if (to) "Bubble styles are on in this group." else "Bubble styles are off in this group.")
        }
    }
    SettingsRow("Bubble Styles", icon = androidx.compose.ui.res.painterResource(com.fariin.app.R.drawable.ic_chat),
        trailing = Trailing.NONE, last = last,
        accessory = { androidx.compose.material3.Switch(checked = on, enabled = pending == null, onCheckedChange = { flip(it) },
            colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = Fariin.colors.accent,
                checkedThumbColor = Fariin.colors.onAccent, checkedBorderColor = Fariin.colors.accent)) }) { flip(!on) }
    Problem(error) { error = null }
}

/** Only the owner, or an admin with the Change Info right (the server refuses anyone else). */
internal fun bubbleStylesSwitchShown(me: String, doc: Map<String, Any?>): Boolean =
    com.fariin.app.data.BubbleStyles.SHOWN && doc["channel"] != true && Team.from(doc).may(me, Right.CHANGE_INFO)

/** Draws the page on top of the stack; back pops it. False when nothing is pushed. */
@Composable
internal fun GroupPages(cid: String, me: String, stack: MutableList<GroupPage>, onOpenPerson: (String) -> Unit,
                        onAddMembers: () -> Unit = {}, onHandedOver: () -> Unit = {}, onTimer: () -> Unit = {}): Boolean {
    val top = stack.lastOrNull() ?: return false
    val pop: () -> Unit = { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
    val push: (GroupPage) -> Unit = { stack.add(it) }
    BackHandler(onBack = pop)
    when (top) {
        GroupPage.Admins -> GroupAdminsPage(cid, me, pop, push)
        GroupPage.Choose -> ChooseMemberPage(cid, me, pop) { uid -> pop(); push(GroupPage.AddAdmin(uid)) }
        is GroupPage.AddAdmin -> AddAdminPage(cid, me, top.uid, pop)
        is GroupPage.Rights -> AdminRightsPage(cid, me, top.uid, pop)
        GroupPage.Permissions -> PermissionsPage(cid, me, pop)
        GroupPage.Type -> GroupTypePage(cid, me, pop)
        GroupPage.Links -> GroupLinksPage(cid, pop) { push(GroupPage.LinkEdit(it)) }
        is GroupPage.LinkEdit -> LinkEditPage(cid, top.link, pop)
        GroupPage.Members -> GroupMembersPage(cid, me, pop, push, onOpenPerson, onAddMembers)
        GroupPage.Banned -> BannedUsersPage(cid, pop)
        GroupPage.JoinRequests -> JoinRequestsPage(cid, pop)
        GroupPage.RecentActions -> RecentActionsPage(cid, me, pop)
        GroupPage.Settings -> GroupSettingsPage(cid, me, pop, push, onTimer)
        is GroupPage.Stats -> GroupStatsPage(cid, top.title, pop)
        is GroupPage.Storage -> ChatStoragePage(cid, me, top.title, pop)
        GroupPage.ChooseOwner -> {
            val doc = liveGroupDoc(cid)
            if (doc.isEmpty()) Loading() else ChooseNewOwnerPage(cid, me, doc, pop) { stack.clear(); onHandedOver() }
        }
    }
    return true
}

/**
 * One of these pages on its own, as a channel route opens it (Invite Links, Banned Users): its own
 * stack, and [onExit] when the last page is closed.
 */
@Composable
internal fun GroupPagesHost(cid: String, me: String, start: GroupPage, onExit: () -> Unit) {
    val stack = remember(cid) { androidx.compose.runtime.mutableStateListOf(start) }
    LaunchedEffect(stack.isEmpty()) { if (stack.isEmpty()) onExit() }
    if (!GroupPages(cid, me, stack, onOpenPerson = {})) Loading()
}

@Composable
private fun Loading() = Box(Modifier.fillMaxSize().background(Fariin.colors.bgSecondary))

@Composable
private fun BusyMark() = com.fariin.app.ui.components.BarBusyPill()

@Composable
private fun Problem(text: String?, onDone: () -> Unit) {
    val c = Fariin.colors
    if (text != null) AlertDialog(onDismissRequest = onDone, containerColor = c.card, title = { Text("Could not do that") },
        text = { Text(text) }, confirmButton = { TextButton(onDone) { Text("OK", color = c.accent) } })
}

// ---- Administrators (iOS GroupAdminsView) -----------------------------------------------------

@Composable
private fun GroupAdminsPage(cid: String, me: String, onBack: () -> Unit, push: (GroupPage) -> Unit) {
    val c = Fariin.colors
    val doc = liveGroupDoc(cid)
    if (doc.isEmpty()) { Loading(); return }
    val team = Team.from(doc)
    val barScroll = rememberScrollState()
    BarPage("Administrators", onBack, under = underBar(barScroll), modifier = Modifier.background(c.bgSecondary)) { top ->
        Column(Modifier.fillMaxSize().verticalScroll(barScroll).padding(top = top).navigationBarsPadding()) {
            SettingsSection(footer = GroupAdmins.ADMINS_FOOTER) {
                if (team.may(me, Right.ADD_ADMINS) && team.others().isNotEmpty()) {
                    SettingsRow("Add Admin", icon = rememberVectorPainter(ImageVector.vectorResource(com.fariin.app.R.drawable.ic_add_member)), titleColor = c.accent,
                        trailing = Trailing.NONE) { push(GroupPage.Choose) }
                }
                team.sortedAdmins().forEach { uid ->
                    PersonLine(team.photos[uid]?.ifEmpty { null }, if (uid == me) "You" else team.names[uid] ?: "Admin",
                        onClick = { push(GroupPage.Rights(uid)) }) {
                        Text(if (team.isOwner(uid)) "Owner" else "Admin", fontSize = 14.sp, color = c.secondary)
                    }
                }
            }
        }
    }
}

/** Choose Member: the members who are not admins, with a search; one opens Add Admin. */
@Composable
private fun ChooseMemberPage(cid: String, me: String, onBack: () -> Unit, onPick: (String) -> Unit) {
    val c = Fariin.colors
    val doc = liveGroupDoc(cid)
    var query by remember { mutableStateOf("") }
    if (doc.isEmpty()) { Loading(); return }
    val team = Team.from(doc)
    val q = query.trim()
    val list = team.others().filter { it != me && (q.isEmpty() || (team.names[it] ?: "").contains(q, ignoreCase = true)) }
    BarPage("Choose Member", onBack, modifier = Modifier.background(c.bgSecondary)) { top ->
        androidx.compose.foundation.layout.Spacer(Modifier.height(top))
        SearchField(query, "Search") { query = it }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            SettingsSection {
                list.forEach { uid -> PersonLine(team.photos[uid]?.ifEmpty { null }, team.names[uid] ?: "Member", onClick = { onPick(uid) }) }
                if (list.isEmpty()) Note(if (q.isEmpty()) "Everyone in the group is an admin." else "No member by that name.")
            }
        }
    }
}

/** The person on top of an admin page: photo, name, the Owner / Admin chip. */
@Composable
private fun AdminHeader(photo: String?, name: String, chip: String) {
    val c = Fariin.colors
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Avatar(photo, 80.dp, modifier = Modifier.padding(top = 8.dp))
        Text(name, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp))
        Row(Modifier.padding(top = 6.dp).clip(CircleShape).background(c.card).padding(horizontal = 10.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(ImageVector.vectorResource(R.drawable.ic_sf_checkmark_shield), null, tint = c.label, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(4.dp))
            Text(chip, fontSize = 15.sp, color = c.label)
        }
    }
}

/** iOS AddAdminPage: a switch for each right I may give (the defaults on), Add in the top bar. */
@Composable
private fun AddAdminPage(cid: String, me: String, uid: String, onDone: () -> Unit) {
    val c = Fariin.colors
    val scope = rememberCoroutineScope()
    val doc = liveGroupDoc(cid)
    var rights by remember { mutableStateOf<Set<String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    if (doc.isEmpty()) { Loading(); return }
    val team = Team.from(doc)
    val grantable = team.grantable(me)
    val on = rights ?: GroupAdmins.startRights(grantable)
    val name = team.names[uid] ?: "Member"
    Problem(problem) { problem = null }
    val barScroll = rememberScrollState()
    BarPage("Add Admin", onDone, action = {
            if (busy) BusyMark() else Text("Add", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.accent,
                modifier = Modifier.padding(end = 4.dp).height(44.dp).floating(CircleShape).clickable {
                    busy = true
                    scope.launch {
                        try {
                            GroupManage.promote(cid, uid, GroupAdmins.toWrite(on, grantable), team)
                            Snack.show("$name is now an admin"); onDone()
                        } catch (e: Exception) { problem = failText(e) }
                        busy = false
                    }
                }.padding(horizontal = 18.dp, vertical = 11.dp))
        }, under = underBar(barScroll), modifier = Modifier.background(c.bgSecondary)) { top ->
        Column(Modifier.fillMaxSize().verticalScroll(barScroll).padding(top = top).navigationBarsPadding()) {
            AdminHeader(team.photos[uid]?.ifEmpty { null }, name, "Admin")
            SettingsSection(header = "What this admin can do",
                footer = if (Right.ADD_ADMINS in grantable && Right.ADD_ADMINS.slug !in on) GroupAdmins.NO_ADD_ADMINS else null) {
                grantable.forEachIndexed { i, r ->
                    SwitchLine(r.label, r.slug in on, enabled = !busy, last = i == grantable.lastIndex) { v ->
                        rights = if (v) on + r.slug else on - r.slug
                    }
                }
            }
        }
    }
}

/**
 * One admin (the member card's Edit Admin Rights / My Admin Rights, iOS AdminRightsView): All
 * rights and one switch per right, each saved as flipped; Dismiss Admin where I may.
 */
@Composable
private fun AdminRightsPage(cid: String, me: String, uid: String, onBack: () -> Unit) {
    val c = Fariin.colors
    val scope = rememberCoroutineScope()
    val doc = liveGroupDoc(cid)
    var draft by remember(uid) { mutableStateOf<Set<String>?>(null) }
    var saving by remember { mutableStateOf(false) }
    var confirmDismiss by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var left by remember { mutableStateOf(false) }
    if (doc.isEmpty()) { Loading(); return }
    val team = Team.from(doc)
    fun leave() { if (!left) { left = true; onBack() } }
    LaunchedEffect(doc, uid) { if (!team.isStaff(uid)) leave() }
    val name = if (uid == me) "You" else team.names[uid] ?: "Admin"
    val editable = team.canManage(me, uid)
    val shown = if (editable) team.grantable(me) else Right.entries
    val held = draft ?: team.rightsOf(uid).map { it.slug }.toSet()
    fun save(next: Set<String>) {
        val old = held
        draft = next; saving = true
        scope.launch {
            try { GroupManage.setRights(cid, uid, GroupAdmins.toWrite(next, shown)); draft = null }
            catch (e: Exception) { draft = old; problem = failText(e) }
            saving = false
        }
    }
    Problem(problem) { problem = null }
    // The contact page's bottom sheet, the admin's photo and name on top.
    if (confirmDismiss) PersonConfirmSheet(team.photos[uid]?.ifEmpty { null }, team.names[uid].orEmpty(),
        "Dismiss ${team.names[uid] ?: "them"} as admin?", GroupAdmins.DISMISS_BODY,
        listOf(PersonAction("Dismiss Admin") {
            saving = true
            val ok = try { GroupManage.demote(cid, uid); Snack.show("${team.names[uid] ?: "They"} is no longer an admin"); true }
                catch (e: Exception) { problem = failText(e); false }
            saving = false
            if (ok) leave()
            ok
        }), onDismiss = { confirmDismiss = false })
    val barScroll = rememberScrollState()
    BarPage(if (editable) "Admin Rights" else if (uid == me) "My Admin Rights" else name, onBack, action = { if (saving) com.fariin.app.ui.components.BarBusyPill("Saving…") }, under = underBar(barScroll), modifier = Modifier.background(c.bgSecondary)) { top ->
        Column(Modifier.fillMaxSize().verticalScroll(barScroll).padding(top = top).navigationBarsPadding()) {
            AdminHeader(team.photos[uid]?.ifEmpty { null }, name, if (team.isOwner(uid)) "Owner" else "Admin")
            if (team.isOwner(uid)) {
                SettingsSection { Note(if (uid == me) GroupAdmins.OWNER_NOTE else "$name is the owner of this group and can do everything in it.") }
            } else {
                if (editable) SettingsSection {
                    SwitchLine("All rights", GroupAdmins.allOn(held, shown), bold = true, enabled = !saving, last = true) { on ->
                        save(if (on) held + shown.map { it.slug } else held - shown.map { it.slug }.toSet())
                    }
                }
                SettingsSection(header = if (uid == me) "What you can do" else "What $name can do",
                    footer = if (editable) GroupAdmins.rightsFooter(name) else if (uid == me) GroupAdmins.NOT_YOURS else null) {
                    shown.forEachIndexed { i, r ->
                        SwitchLine(r.label, r.slug in held, enabled = editable && !saving, last = i == shown.lastIndex) { on ->
                            save(if (on) held + r.slug else held - r.slug)
                        }
                    }
                }
                if (editable) SettingsSection {
                    SettingsRow("Dismiss Admin", icon = rememberVectorPainter(ImageVector.vectorResource(R.drawable.ic_sf_person_minus)), titleColor = c.red,
                        trailing = Trailing.NONE, last = true) { if (!saving) confirmDismiss = true }
                }
            }
        }
    }
}

// ---- The member card (iOS GroupMemberSheet short card) ---------------------------------------

/**
 * A member's card from Group Info: photo, name, @-less; then only the rows I may use: Make Group
 * Admin, Edit Admin Rights, My Admin Rights, Open Profile, Dismiss Admin, Ban from Group.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MemberCard(cid: String, me: String, uid: String, conv: Map<String, Any?>, onClose: () -> Unit,
                        onProfile: (String) -> Unit, open: (GroupPage) -> Unit) {
    val c = Fariin.colors
    val scope = rememberCoroutineScope()
    val team = Team.from(conv)
    val name = team.names[uid] ?: "Member"
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var confirmPromote by remember { mutableStateOf(false) }
    var confirmDismiss by remember { mutableStateOf(false) }
    var confirmBan by remember { mutableStateOf(false) }
    var transferring by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var tagging by remember { mutableStateOf(false) }
    if (tagging) TagEditorSheet(cid, me, uid, name, com.fariin.app.data.GroupRole.all(conv)[uid], onDone = { tagging = false })
    // iOS: the password dialog is the confirm; after a hand-over the card closes (I am no longer its owner).
    if (transferring) TransferOwnership(cid, conv["channel"] == true, uid, name, onDone = { transferring = false; onClose() },
        onEnd = { transferring = false })
    fun run(block: suspend () -> Unit) {
        scope.launch {
            try { block(); onClose() } catch (e: Exception) { problem = failText(e) }
        }
    }
    Problem(problem) { problem = null; onClose() }
    // The confirms: the contact page's bottom sheet, the member's photo and name on top. The button
    // spins while the work runs; the card closes once it worked, a failure says why (as before).
    suspend fun runNow(block: suspend () -> Unit): Boolean =
        try { block(); onClose(); true } catch (e: Exception) { problem = failText(e); false }
    val face = team.photos[uid]?.ifEmpty { null }
    if (confirmPromote) PersonConfirmSheet(face, name, "Make $name an admin?", GroupAdmins.MAKE_ADMIN_BODY,
        listOf(PersonAction("Make Admin", plain = true) {
            runNow {
                GroupManage.promote(cid, uid, GroupAdmins.startRights(team.grantable(me)).let { GroupAdmins.toWrite(it, team.grantable(me)) }, team)
                Snack.show("$name is now an admin")
            }
        }), onDismiss = { confirmPromote = false })
    if (confirmDismiss) PersonConfirmSheet(face, name, "Dismiss $name as admin?", GroupAdmins.DISMISS_BODY,
        listOf(PersonAction("Dismiss Admin") { runNow { GroupManage.demote(cid, uid); Snack.show("$name is no longer an admin") } }),
        onDismiss = { confirmDismiss = false })
    if (confirmBan) PersonConfirmSheet(face, name, "Ban $name?", GroupAdmins.BAN_BODY,
        listOf(PersonAction("Ban") { runNow { GroupManage.removeMember(cid, uid, name); Snack.show("$name was removed from the group.") } }),
        onDismiss = { confirmBan = false })
    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheet, containerColor = c.bgSecondary) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Avatar(team.photos[uid]?.ifEmpty { null }, 96.dp)
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (uid == me) "You" else name, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = c.label, maxLines = 1)
                if (team.isStaff(uid)) {
                    Spacer(Modifier.width(8.dp))
                    Text(if (team.isOwner(uid)) "Owner" else "Admin", fontSize = 14.sp, color = c.label,
                        modifier = Modifier.clip(CircleShape).background(c.card).padding(horizontal = 10.dp, vertical = 3.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            val manage = team.canManage(me, uid)
            SettingsSection {
                val rows = buildList<Triple<String, Painter, () -> Unit>> {
                    if (manage && !team.isAdmin(uid)) add(Triple("Make Group Admin", rememberVectorPainter(ImageVector.vectorResource(R.drawable.ic_sf_checkmark_shield))) { confirmPromote = true })
                    if (manage && team.isAdmin(uid)) add(Triple("Edit Admin Rights", rememberVectorPainter(ImageVector.vectorResource(R.drawable.ic_sf_slider_horizontal3))) { onClose(); open(GroupPage.Rights(uid)) })
                    if (uid == me && team.isStaff(me)) add(Triple("My Admin Rights", rememberVectorPainter(ImageVector.vectorResource(R.drawable.ic_sf_checkmark_shield))) { onClose(); open(GroupPage.Rights(uid)) })
                    if (!team.closed && com.fariin.app.data.OwnerTransfer.canTransfer(me, team.owner, uid, team.users))
                        add(Triple(com.fariin.app.data.OwnerTransfer.ROW, painterResource(R.drawable.ic_crown)) { transferring = true })
                    // iOS member sheet's Tag (roles): the owner, or an admin who may change info.
                    if (com.fariin.app.data.GroupRole.canSet(me, uid, conv))
                        add(Triple(if (com.fariin.app.data.GroupRole.all(conv)[uid] == null) "Set Tag" else "Edit Tag",
                            rememberVectorPainter(ImageVector.vectorResource(com.fariin.app.R.drawable.ic_sticker))) { tagging = true })
                    if (uid != me) add(Triple("Open Profile", rememberVectorPainter(ImageVector.vectorResource(R.drawable.ic_sf_person_fill))) { onClose(); onProfile(uid) })
                    // iOS member card: only the lift for a restricted member (owner 2026-10-30); the owner's
                    // 2026-10-19 "no Mute row" still holds, so a restriction starts from the admin delete sheet.
                    if (!team.closed && uid != me && team.can(me, Right.BAN_USERS) &&
                        com.fariin.app.data.MemberRestrict.hasRestriction(conv, uid, System.currentTimeMillis().toDouble()))
                        add(Triple(com.fariin.app.data.MemberRestrict.LIFT_ROW,
                            painterResource(R.drawable.ic_speaker_on)) {
                            run { GroupManage.unmuteMember(cid, uid, name); Snack.show("Restrictions lifted for $name") }
                        })
                }
                val red = buildList<Triple<String, Painter, () -> Unit>> {
                    if (manage && team.isAdmin(uid)) add(Triple("Dismiss Admin", rememberVectorPainter(ImageVector.vectorResource(R.drawable.ic_sf_person_minus))) { confirmDismiss = true })
                    if (team.canRemove(me, uid)) add(Triple("Ban from Group", rememberVectorPainter(ImageVector.vectorResource(com.fariin.app.R.drawable.ic_block))) { confirmBan = true })
                }
                rows.forEachIndexed { i, (t, p, a) -> SettingsRow(t, icon = p, trailing = Trailing.NONE, last = i == rows.lastIndex && red.isEmpty(), onClick = a) }
                red.forEachIndexed { i, (t, p, a) -> SettingsRow(t, icon = p, titleColor = c.red, trailing = Trailing.NONE, last = i == red.lastIndex, onClick = a) }
            }
        }
    }
}

// ---- Members (iOS GroupMembersSheet) ----------------------------------------------------------

/**
 * Members: the search field, a sort menu (By Name / Admins First), the members under A to Z
 * headers with Owner / Admin at the end of the row, then Add Members and Invite Via Group Link
 * for whoever may. An admin taps a member for the member card; others open the profile.
 * The letters down the side jump to their section (iOS listSectionIndexVisibility). Whoever may
 * remove a member swipes the row left (iOS GroupMembersListView: never me or the owner, an admin
 * only by the owner), or long-presses to select and removes from the top bar.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun GroupMembersPage(cid: String, me: String, onBack: () -> Unit, push: (GroupPage) -> Unit,
                             onOpenPerson: (String) -> Unit, onAddMembers: () -> Unit) {
    val c = Fariin.colors
    val scope = rememberCoroutineScope()
    val doc = liveGroupDoc(cid)
    var query by remember { mutableStateOf("") }
    var adminsFirst by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }
    var card by remember { mutableStateOf<String?>(null) }
    var picked by remember(cid) { mutableStateOf(setOf<String>()) }
    var pendingRemove by remember { mutableStateOf<List<String>?>(null) }
    var removeError by remember { mutableStateOf<String?>(null) }
    // The members' @usernames, read once the first search starts (iOS ProfileStore.handles).
    val handles = remember(cid) { mutableStateMapOf<String, String>() }
    var handlesAsked by remember { mutableStateOf(false) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    BackHandler(enabled = picked.isNotEmpty()) { picked = emptySet() }
    if (doc.isEmpty()) { Loading(); return }
    val team = Team.from(doc)
    val g = com.fariin.app.data.GroupMembers.Group.from(doc)
    val searching = query.isNotBlank()
    LaunchedEffect(searching) {
        if (!searching || handlesAsked) return@LaunchedEffect
        handlesAsked = true
        val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
        for (uid in g.users.take(200)) {
            val h = runCatching { db.collection("users").document(uid).get().await().getString("handle") }.getOrNull()
            if (!h.isNullOrEmpty()) handles[uid] = h
        }
    }
    val closed = team.closed
    val canAdd = !closed && (team.may(me, Right.INVITE_USERS) || (doc["membersCanAdd"] == true && me in team.users))
    val canInvite = !closed && team.may(me, Right.INVITE_USERS)
    val list = com.fariin.app.data.GroupMembers.search(g, me, query, handles, adminsFirst)
    val sections = com.fariin.app.data.GroupMembers.sections(list, { com.fariin.app.data.GroupMembers.shownName(g, it, me) }, adminsFirst)
    // A member who left (or became untouchable) while picked drops out of the pick.
    val stillPicked = picked.filter { team.canRemove(me, it) }.toSet()
    if (stillPicked != picked) picked = stillPicked
    val selecting = picked.isNotEmpty()
    fun nameOf(uid: String) = team.names[uid]?.ifEmpty { null } ?: "this member"
    card?.let { u -> MemberCard(cid, me, u, doc, onClose = { card = null }, onProfile = onOpenPerson, open = push) }
    pendingRemove?.let { uids ->
        val one = uids.size == 1
        // The contact page's bottom sheet: one member shows their photo and name, several the group's.
        PersonConfirmSheet(
            if (one) team.photos[uids[0]]?.ifEmpty { null } else (doc["avatarUrl"] as? String)?.ifEmpty { null },
            if (one) team.names[uids[0]].orEmpty() else doc["title"] as? String ?: "",
            if (one) "Remove ${nameOf(uids[0])} from the group?" else "Remove ${uids.size} members from the group?",
            "They can't join again with a link until an admin adds them back.",
            listOf(PersonAction("Remove") {
                picked = emptySet()
                var done = 0
                for (uid in uids) {
                    try { GroupManage.removeMember(cid, uid, team.names[uid] ?: "User"); done++ }
                    catch (e: Exception) { removeError = failText(e, "Couldn't remove. Try again."); break }
                }
                if (done == 1 && one) Snack.show("${team.names[uids[0]] ?: "User"} was removed from the group.")
                else if (done > 0) Snack.show("$done member${if (done == 1) " was" else "s were"} removed from the group.")
                done > 0
            }), onDismiss = { pendingRemove = null },
            kind = if (one) com.fariin.app.ui.components.AvatarKind.PERSON else com.fariin.app.ui.components.AvatarKind.of(true, doc["channel"] == true))
    }
    removeError?.let { e ->
        AlertDialog(onDismissRequest = { removeError = null }, containerColor = c.card, title = { Text("Could not remove") }, text = { Text(e) },
            confirmButton = { TextButton({ removeError = null }) { Text("OK", color = c.accent) } })
    }
    @Composable
    fun memberRow(uid: String) {
                                val removable = team.canRemove(me, uid)
                                val open: (() -> Unit)? = if (team.isStaff(me) || uid != me) ({ card = uid }) else null   // the card for everyone, as Group Info
                                val on = uid in picked
                                MemberSwipeRow(enabled = removable && !selecting, onSwiped = { pendingRemove = listOf(uid) }) {
                                    Box(Modifier.fillMaxWidth().background(if (on) c.blue.copy(alpha = 0.12f) else c.card)
                                        // The shared row press: a sideways swipe is not a tap.
                                        .listPress(onLongClick = if (removable && !selecting) ({ picked = setOf(uid) }) else null) {
                                            if (selecting) { if (removable) picked = if (on) picked - uid else picked + uid }
                                            else open?.invoke()
                                        }) {
                                        PersonLine(team.photos[uid]?.ifEmpty { null }, com.fariin.app.data.GroupMembers.shownName(g, uid, me)) {
                                            com.fariin.app.data.GroupMembers.rank(g, uid)?.let { Text(it, fontSize = 14.sp, color = c.secondary) }
                                            if (selecting && removable) {
                                                Spacer(Modifier.width(8.dp))
                                                Icon(if (on) ImageVector.vectorResource(R.drawable.ic_sf_checkmark_circle_fill) else ImageVector.vectorResource(R.drawable.ic_sf_circle), null,
                                                    tint = if (on) c.blue else c.secondary, modifier = Modifier.size(22.dp))
                                            }
                                        }
                                    }
                                }
    }
    BarPage(if (selecting) "${picked.size} Selected" else "Members", onBack = if (selecting) ({ picked = emptySet() }) else onBack,
        modifier = Modifier.background(c.bgSecondary), action = {
        if (selecting) {
            androidx.compose.material3.IconButton({ pendingRemove = picked.toList() }, modifier = com.fariin.app.ui.components.barGlass()) {
                Icon(ImageVector.vectorResource(R.drawable.ic_sf_person_minus), "Remove", tint = c.red)
            }
        } else {
            Box {
                androidx.compose.material3.IconButton({ sortOpen = true }, modifier = com.fariin.app.ui.components.barGlass()) {
                    Icon(ImageVector.vectorResource(R.drawable.ic_sf_line3_decrease), "Sort", tint = c.label)
                }
                androidx.compose.material3.DropdownMenu(sortOpen, onDismissRequest = { sortOpen = false }, shape = com.fariin.app.ui.components.FariinMenu.shape, border = com.fariin.app.ui.components.FariinMenu.border, containerColor = c.card) {
                    listOf("By Name" to false, "Admins First" to true).forEach { (label, v) ->
                        androidx.compose.material3.DropdownMenuItem(text = { Text(label, color = c.label) },
                            trailingIcon = { if (adminsFirst == v) Icon(ImageVector.vectorResource(R.drawable.ic_sf_checkmark), null, tint = c.accent) },
                            onClick = { adminsFirst = v; sortOpen = false })
                    }
                }
            }
        }
    }) { top ->
        androidx.compose.foundation.layout.Spacer(Modifier.height(top))
        SearchField(query, "Search") { query = it }
        Box(Modifier.fillMaxSize()) {
            androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize().imePadding(), state = listState,
                contentPadding = WindowInsets.navigationBars.asPaddingValues()) {
                // Add Members first, as iOS GroupMembersView (its own section above everyone).
                if (query.isEmpty() && (canAdd || canInvite) && !selecting) item(key = "add") {
                    SettingsSection {
                        if (canAdd) SettingsRow("Add Members", icon = rememberVectorPainter(ImageVector.vectorResource(com.fariin.app.R.drawable.ic_add_member)), titleColor = c.accent,
                            trailing = Trailing.NONE, last = !canInvite, onClick = onAddMembers)
                        if (canInvite) SettingsRow("Invite Via Group Link", icon = rememberVectorPainter(ImageVector.vectorResource(com.fariin.app.R.drawable.ic_link_copy)), titleColor = c.accent,
                            trailing = Trailing.NONE, last = true) { push(GroupPage.Links) }
                    }
                }
                if (list.isEmpty()) item(key = "none") { SettingsSection { Note("No members found.") } }
                // iOS (owner 2026-10-18): whoever holds a role, together at the top, so an admin sees
                // them all in one place.
                val withRoles = if (query.isEmpty()) list.filter { (doc["roles"] as? Map<*, *>)?.get(it) != null } else emptyList()
                if (withRoles.isNotEmpty()) item(key = "roles") {
                    SettingsSection(header = "Members with roles", footer = "Only admins can give a member a role.") {
                        withRoles.forEach { uid -> memberRow(uid) }
                    }
                }
                sections.forEach { (letter, uids) ->
                    item(key = "sec:$letter") {
                        SettingsSection(header = letter.ifEmpty { null }) {
                            uids.forEach { uid -> memberRow(uid) }
                        }
                    }
                }
                item(key = "end") { Spacer(Modifier.height(24.dp)) }
            }
            val letters = sections.map { it.first }.filter { it.isNotEmpty() }
            if (letters.isNotEmpty()) LetterIndex(letters, Modifier.align(Alignment.CenterEnd)) { l ->
                // The list's items: "none" never shows beside sections, so section i is item i.
                val i = sections.indexOfFirst { it.first == l }
                if (i >= 0) scope.launch { listState.scrollToItem(i) }
            }
        }
    }
}

/** Swipe a member left to remove them (iOS swipeActions, no full swipe): the row snaps back and the question opens. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemberSwipeRow(enabled: Boolean, onSwiped: () -> Unit, content: @Composable () -> Unit) {
    if (!enabled) { content(); return }
    val c = Fariin.colors
    val state = androidx.compose.material3.rememberSwipeToDismissBoxState(confirmValueChange = { v ->
        if (v == androidx.compose.material3.SwipeToDismissBoxValue.EndToStart) onSwiped()
        false
    })
    androidx.compose.material3.SwipeToDismissBox(state, enableDismissFromStartToEnd = false, backgroundContent = {
        // Only while the row moves: at rest the red would show at the card's rounded corners.
        if (state.dismissDirection == androidx.compose.material3.SwipeToDismissBoxValue.EndToStart)
        Box(Modifier.fillMaxSize().background(c.red).padding(end = 20.dp), contentAlignment = Alignment.CenterEnd) {
            Text("Remove", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }) { content() }
}

/** The A to Z strip down the list's edge (iOS section index): a tap or a drag jumps to that letter. */
@Composable
private fun LetterIndex(letters: List<String>, modifier: Modifier, onPick: (String) -> Unit) {
    val c = Fariin.colors
    var height by remember { mutableStateOf(1) }
    fun at(y: Float) = letters[(y / height * letters.size).toInt().coerceIn(0, letters.lastIndex)]
    Column(modifier.padding(end = 2.dp)
        .onSizeChanged { height = it.height.coerceAtLeast(1) }
        .pointerInput(letters) { detectTapGestures { onPick(at(it.y)) } }
        .pointerInput(letters) { detectVerticalDragGestures { change, _ -> onPick(at(change.position.y)) } },
        horizontalAlignment = Alignment.CenterHorizontally) {
        letters.forEach {
            Text(it, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = c.accent,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
        }
    }
}

// ---- Banned Users (iOS GroupRemovedUsersView) -------------------------------------------------

/** The people taken out of the group (`bannedUids`), each with Unban. Unban does not add them back. */
@Composable
private fun BannedUsersPage(cid: String, onBack: () -> Unit) {
    val c = Fariin.colors
    val scope = rememberCoroutineScope()
    val doc = liveGroupDoc(cid)
    var busy by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    // Names the group doc does not hold, from their own profiles.
    val fetched = remember(cid) { mutableStateMapOf<String, Pair<String, String?>>() }
    if (doc.isEmpty()) { Loading(); return }
    val team = Team.from(doc)
    val banned = (doc["bannedUids"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
    LaunchedEffect(banned) {
        val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
        for (uid in banned) if (team.names[uid].isNullOrEmpty() && fetched[uid] == null) {
            runCatching { db.collection("users").document(uid).get().await() }.getOrNull()?.let { d ->
                fetched[uid] = (d.getString("name") ?: "User") to d.getString("photoUrl")
            }
        }
    }
    Problem(problem) { problem = null }
    val barScroll = rememberScrollState()
    BarPage("Banned Users", onBack, under = underBar(barScroll), modifier = Modifier.background(c.bgSecondary)) { top ->
        Column(Modifier.fillMaxSize().verticalScroll(barScroll).padding(top = top).navigationBarsPadding()) {
            SettingsSection {
                if (banned.isEmpty()) Note(if (doc["channel"] == true) "Nobody is banned from this channel." else com.fariin.app.data.GroupMembers.NOBODY_BANNED)
                banned.forEach { uid ->
                    val name = team.names[uid]?.ifEmpty { null } ?: fetched[uid]?.first ?: "User"
                    val photo = team.photos[uid]?.ifEmpty { null } ?: fetched[uid]?.second
                    PersonLine(photo, name) {
                        if (busy == uid) com.fariin.app.ui.components.FariinSpinner(Modifier.padding(horizontal = 12.dp), size = 20.dp)
                        else TextButton(enabled = busy == null, onClick = {
                            busy = uid
                            scope.launch {
                                try { GroupManage.unban(cid, uid) } catch (e: Exception) { problem = failText(e, "Couldn't unban. Try again.") }
                                busy = null
                            }
                        }) { Text("Unban", color = c.accent, fontSize = 16.sp) }
                    }
                }
            }
        }
    }
}

// ---- Permissions (iOS GroupSettingsView permissionsPage) -------------------------------------

@Composable
private fun PermissionsPage(cid: String, me: String, onBack: () -> Unit) {
    val c = Fariin.colors
    val scope = rememberCoroutineScope()
    val doc = liveGroupDoc(cid)
    val inFlight = remember { mutableStateMapOf<String, Boolean>() }
    var mediaOpen by remember { mutableStateOf(false) }
    var slowDraft by remember { mutableStateOf<Int?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    if (doc.isEmpty()) { Loading(); return }
    val team = Team.from(doc)
    val canEdit = team.may(me, Right.CHANGE_INFO)
    // The switch holds the tap while its write is out; refused, it goes back and says why (iOS `bool`).
    fun flip(key: String, value: Boolean, write: suspend () -> Unit) {
        if (inFlight.containsKey(key)) return
        inFlight[key] = value
        scope.launch {
            try { write(); delay(300) } catch (e: Exception) { problem = failText(e) }
            inFlight.remove(key)
        }
    }
    fun shown(key: String, live: Boolean) = inFlight[key] ?: live
    Problem(problem) { problem = null }
    val barScroll = rememberScrollState()
    BarPage("Permissions", onBack, under = underBar(barScroll), modifier = Modifier.background(c.bgSecondary)) { top ->
        Column(Modifier.fillMaxSize().verticalScroll(barScroll).padding(top = top).navigationBarsPadding()) {
            SettingsSection(header = "What can members do?", footer = "Turned off, only admins can do it.") {
                val sendOn = shown("send", doc["onlyAdminsSend"] != true)
                SwitchLine("Send messages", sendOn, enabled = canEdit) { v -> flip("send", v) { GroupManage.setOnlyAdminsSend(cid, !v) } }
                if (sendOn) {
                    val allOn = shown("media.all", SendKind.entries.any { GroupAdmins.membersMaySend(doc, it) })
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable { mediaOpen = !mediaOpen }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("Send media", fontSize = 17.sp, color = c.label)
                            Spacer(Modifier.width(6.dp))
                            Text("${GroupAdmins.mediaOnCount(doc)}/${SendKind.entries.size}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.label)
                            Icon(if (mediaOpen) ImageVector.vectorResource(R.drawable.ic_sf_chevron_up) else ImageVector.vectorResource(R.drawable.ic_sf_chevron_down), "Show the kinds", tint = c.secondary)
                        }
                        com.fariin.app.ui.components.FariinSwitch(allOn, onCheckedChange = if (canEdit) ({ v -> flip("media.all", v) { GroupManage.setAllMedia(cid, v) } }) else null,
                            enabled = canEdit, colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = com.fariin.app.ui.theme.Fariin.colors.onAccent))
                    }
                    HorizontalDivider(thickness = 0.5.dp, color = c.separator, modifier = Modifier.padding(start = 16.dp))
                    if (mediaOpen) SendKind.entries.forEach { k ->
                        val on = shown("media.${k.slug}", GroupAdmins.membersMaySend(doc, k))
                        Row(Modifier.fillMaxWidth().clickable(enabled = canEdit) { flip("media.${k.slug}", !on) { GroupManage.setKind(cid, k, !on, doc) } }
                            .heightIn(min = 48.dp).padding(start = 22.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (on) ImageVector.vectorResource(R.drawable.ic_sf_checkmark_circle_fill) else ImageVector.vectorResource(R.drawable.ic_sf_circle), null, tint = if (on) c.blue else c.secondary, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(14.dp))
                            Text(k.label, fontSize = 17.sp, color = c.label)
                        }
                    }
                }
                SwitchLine("Add members", shown("add", doc["membersCanAdd"] == true), enabled = canEdit) { v ->
                    flip("add", v) { GroupManage.setPermission(cid, "membersCanAdd", v) }
                }
                SwitchLine("Change group info", shown("info", doc["membersCanEditInfo"] == true), enabled = canEdit) { v ->
                    flip("info", v) { GroupManage.setPermission(cid, "membersCanEditInfo", v) }
                }
                SwitchLine("Start calls", shown("calls", doc["onlyAdminsCall"] != true), enabled = canEdit, last = true) { v ->
                    flip("calls", v) { GroupManage.setPermission(cid, "onlyAdminsCall", !v) }
                }
            }
            // Slow mode: a step slider, written when let go (iOS).
            val current = GroupAdmins.slowSeconds(doc)
            val index = slowDraft ?: GroupAdmins.slowIndex(current)
            SettingsSection(header = "Slow mode", footer = "How long each member must wait before sending their next message.") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        GroupAdmins.slowSteps.forEachIndexed { i, (_, label) ->
                            Text(label, fontSize = 12.sp, color = if (i == index) c.label else c.secondary,
                                modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                    }
                    Slider(index.toFloat(), onValueChange = { slowDraft = kotlin.math.round(it).toInt() }, enabled = canEdit,
                        valueRange = 0f..(GroupAdmins.slowSteps.size - 1).toFloat(), steps = GroupAdmins.slowSteps.size - 2,
                        colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent),
                        onValueChangeFinished = {
                            val d = slowDraft ?: return@Slider
                            val secs = GroupAdmins.slowSteps[d].first
                            if (secs == current) { slowDraft = null; return@Slider }
                            scope.launch {
                                try { GroupManage.setSlowMode(cid, secs); delay(500) } catch (e: Exception) { problem = failText(e) }
                                slowDraft = null
                            }
                        })
                }
            }
            val protect = shown("protect", (doc["restrictedBy"] as? Map<*, *>)?.isNotEmpty() == true)
            SettingsSection(header = "Content", footer = "Stops forwarding, copying, saving and screenshots for everyone.") {
                SwitchLine("Protect Content", protect, enabled = canEdit, last = true,
                    badge = NewBadgeSpots.GROUP_SETTINGS_PROTECT_CONTENT) { v -> flip("protect", v) { GroupManage.setProtected(cid, v) } }
            }
        }
    }
}

// ---- Group Type (iOS GroupTypeView, group) ---------------------------------------------------

@Composable
private fun GroupTypePage(cid: String, me: String, onBack: () -> Unit) {
    val c = Fariin.colors
    val scope = rememberCoroutineScope()
    val doc = liveGroupDoc(cid)
    var wantsPublic by remember { mutableStateOf<Boolean?>(null) }
    var name by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var ok by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var confirmPrivate by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    if (doc.isEmpty()) { Loading(); return }
    val current = if (doc["isPublic"] == true) doc["username"] as? String ?: "" else ""
    val isPublicNow = current.isNotEmpty()
    // A channel's own words on the same page (iOS GroupTypeView with ChannelWords).
    val ch = doc["channel"] == true
    val noun = if (ch) "channel" else "group"
    val Noun = if (ch) "Channel" else "Group"
    val showPublic = wantsPublic ?: isPublicNow
    val typed = name ?: current
    val changed = typed != current
    LaunchedEffect(typed, showPublic) {
        ok = false
        if (!showPublic || !changed || typed.isEmpty()) { note = null; return@LaunchedEffect }
        com.fariin.app.channel.ChannelRules.linkProblem(typed)?.let { note = it; return@LaunchedEffect }
        note = "Checking…"
        delay(400)
        val r = com.fariin.app.channel.ChannelService.checkLink(cid, typed)
        note = r ?: "fariin.com/$typed is available."
        ok = r == null
    }
    fun save(value: String) {
        saving = true
        scope.launch {
            try { com.fariin.app.channel.ChannelService.setLink(cid, value); onBack() }
            catch (e: Exception) { problem = com.fariin.app.channel.ChannelService.errorText(e, "Could not save the link. Try again.") }
            saving = false
        }
    }
    // The contact page's bottom sheet with the group's photo and name; the same save("") work, awaited
    // so the button spins.
    if (confirmPrivate) PersonConfirmSheet((doc["avatarUrl"] as? String)?.ifEmpty { null }, doc["title"] as? String ?: "",
        "Make this $noun private?",
        "fariin.com/$current will stop working. The link stays reserved for this $noun for 30 days, then anyone can take it.",
        listOf(PersonAction("Make Private") {
            saving = true
            val done = try { com.fariin.app.channel.ChannelService.setLink(cid, ""); true }
                catch (e: Exception) { problem = com.fariin.app.channel.ChannelService.errorText(e, "Could not save the link. Try again."); false }
            saving = false
            if (done) { Snack.show("The $noun is now private"); onBack() }
            done
        }), onDismiss = { confirmPrivate = false }, kind = com.fariin.app.ui.components.AvatarKind.of(true, ch))
    if (problem != null) AlertDialog(onDismissRequest = { problem = null }, containerColor = c.card, title = { Text("Could not save") },
        text = { Text(problem ?: "") }, confirmButton = { TextButton({ problem = null }) { Text("OK", color = c.accent) } })
    val barScroll = rememberScrollState()
    BarPage("$Noun Type", onBack, action = {
            if (saving) BusyMark() else Text("Save", fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                color = if (showPublic && changed && ok) c.accent else c.secondary,
                modifier = Modifier.padding(end = 4.dp).height(44.dp).floating(CircleShape).clickable(enabled = showPublic && changed && ok) { save(typed) }
                    .padding(horizontal = 18.dp, vertical = 11.dp))
        }, under = underBar(barScroll), modifier = Modifier.background(c.bgSecondary).imePadding()) { top ->
        Column(Modifier.fillMaxSize().verticalScroll(barScroll).padding(top = top).navigationBarsPadding()) {
            SettingsSection(header = "$Noun Type", footer = if (showPublic) (if (ch) "Anyone can find this channel in search and follow it." else "Anyone can find this group in search and join it without an invite.")
                else if (ch) "People can follow only with an invite link." else "People can join only with an invite link, or when an admin adds them.") {
                TypeLine("Public $Noun", showPublic) { wantsPublic = true }
                TypeLine("Private $Noun", !showPublic, last = true) { if (isPublicNow) confirmPrivate = true else wantsPublic = false }
            }
            // A channel's Protect Content lives here, under its type (iOS channel type page).
            if (ch) {
                val info = com.fariin.app.channel.ChannelInfo.from(cid, doc)
                var protect by remember(info.restricted) { mutableStateOf(info.restricted) }
                var protectProblem by remember { mutableStateOf<String?>(null) }
                SettingsSection(header = "Saving and Copying",
                    footer = protectProblem ?: com.fariin.app.channel.ChannelRules.protectFooter(protect)) {
                    com.fariin.app.ui.settings.SwitchRow("Protect Content", protect, last = true,
                        badge = NewBadgeSpots.GROUP_SETTINGS_PROTECT_CONTENT) { v ->
                        protect = v; protectProblem = null
                        scope.launch {
                            try { com.fariin.app.channel.ChannelService.setProtected(cid, v) }
                            catch (_: Exception) { protect = !v; protectProblem = "Couldn't save. Try again." }
                        }
                    }
                }
            }
            if (showPublic) {
                SettingsSection(header = "Public Link", footer = when {
                    !changed && typed.isNotEmpty() -> "People can open this $noun with fariin.com/$typed."
                    note == "Checking…" -> null
                    note != null -> note
                    else -> "Pick a link of ${com.fariin.app.channel.ChannelRules.LINK_MIN} to ${com.fariin.app.channel.ChannelRules.LINK_MAX} characters: letters, numbers and _."
                }) {
                    Row(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("fariin.com/", fontSize = 17.sp, color = c.secondary)
                        Box(Modifier.weight(1f)) {
                            if (typed.isEmpty()) Text("link", fontSize = 17.sp, color = c.secondary)
                            BasicTextField(typed, { name = it.trim() }, singleLine = true, textStyle = TextStyle(fontSize = 17.sp, color = c.label),
                                cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
                // Asking the server: the ring and the words on one line under the field (iOS).
                if (note == "Checking…") com.fariin.app.ui.components.LoadingLine("Checking link…",
                    Modifier.padding(start = 32.dp, end = 32.dp, bottom = 6.dp))
            }
        }
    }
}

@Composable
private fun TypeLine(title: String, selected: Boolean, last: Boolean = false, onClick: () -> Unit) {
    val c = Fariin.colors
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 56.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 17.sp, color = c.label, modifier = Modifier.weight(1f))
        if (selected) Icon(ImageVector.vectorResource(R.drawable.ic_sf_checkmark), "Selected", tint = c.label, modifier = Modifier.size(20.dp))
    }
    if (!last) HorizontalDivider(thickness = 0.5.dp, color = c.separator, modifier = Modifier.padding(start = 16.dp))
}

// ---- Invite Links (iOS GroupLinksPage) -------------------------------------------------------

private fun copy(ctx: Context, text: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Invite link", text))
    if (android.os.Build.VERSION.SDK_INT < 33) Snack.show("Link copied")
}

private fun share(ctx: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    ctx.startActivity(Intent.createChooser(send, null))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupLinksPage(cid: String, onBack: () -> Unit, onEdit: (GroupLink?) -> Unit) {
    val c = Fariin.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val doc = liveGroupDoc(cid)
    var list by remember { mutableStateOf<GroupLinkList?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var revokeTarget by remember { mutableStateOf<GroupLink?>(null) }
    var detail by remember { mutableStateOf<GroupLink?>(null) }
    var heldApproval by remember { mutableStateOf<Boolean?>(null) }
    var triedAuto by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis().toDouble()
    // A channel shares this page in its own words (iOS ChannelWords.forChat).
    val w: (String) -> String = { com.fariin.app.data.ChannelWords.forChat(doc["channel"] == true, it) }
    suspend fun reload() {
        try {
            val fresh = GroupManage.listLinks(cid)
            list = fresh; loadError = null
            val public = doc["isPublic"] == true && !(doc["username"] as? String).isNullOrEmpty()
            if (fresh.primary == null && !triedAuto && !public) {
                triedAuto = true
                try { GroupManage.createPrimary(cid); list = GroupManage.listLinks(cid) }
                catch (e: Exception) { problem = GroupManage.errorText(e, "The link was not created. Try again.") }
            }
        } catch (e: Exception) {
            if (list == null) loadError = GroupManage.errorText(e, "The links could not be loaded. Try again.")
            else problem = GroupManage.errorText(e, "The links could not be refreshed. Try again.")
        }
    }
    fun change(fallback: String, work: suspend () -> Unit) {
        if (working) return
        working = true
        scope.launch {
            try { work() } catch (e: Exception) { problem = GroupManage.errorText(e, fallback) }
            reload(); working = false; heldApproval = null
        }
    }
    // Only once the group is known: an empty doc read as "not public" and made a link for a public one.
    LaunchedEffect(cid, doc.isNotEmpty()) { if (doc.isNotEmpty() && list == null) reload() }
    // iOS "Send in Fariin": the link goes into the chats I pick, as a plain text message.
    var sendingLink by remember { mutableStateOf<String?>(null) }
    val myUid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
    sendingLink?.let { url ->
        if (myUid != null) com.fariin.app.ui.thread.ForwardSheet(myUid, onDismiss = { sendingLink = null }, title = "Share with") { targets ->
            sendingLink = null
            val crypto = com.fariin.app.data.CryptoHub.get(ctx.applicationContext)
            scope.launch {
                var ok = true
                for (t in targets) try {
                    com.fariin.app.data.ThreadRepository.openOrQuiet(t, myUid, crypto, scope).sendForwardedText(url, forwarded = false)
                } catch (_: Exception) { ok = false }
                Snack.show(if (ok) "Sent" else "Couldn't send. Try again.")
            }
        }
    }
    if (problem != null) AlertDialog(onDismissRequest = { problem = null }, containerColor = c.card, title = { Text("Couldn't change the link") },
        text = { Text(problem ?: "") }, confirmButton = { TextButton({ problem = null }) { Text("OK", color = c.accent) } })
    // The confirms: the contact page's bottom sheet with the group's photo and name. Same work as
    // change(), awaited so the button spins; a short note once it worked, the old message if not.
    suspend fun changeNow(fallback: String, done: String, work: suspend () -> Unit): Boolean {
        if (working) return false
        working = true
        val ok = try { work(); true } catch (e: Exception) { problem = GroupManage.errorText(e, fallback); false }
        reload(); working = false; heldApproval = null
        if (ok) Snack.show(done)
        return ok
    }
    val linkFace = (doc["avatarUrl"] as? String)?.ifEmpty { null }
    val linkName = doc["title"] as? String ?: ""
    val linkKind = com.fariin.app.ui.components.AvatarKind.of(true, doc["channel"] == true)
    if (confirmReset) PersonConfirmSheet(linkFace, linkName, "Reset Link",
        "A new link replaces this one, and the current link stops working for everyone who has it.",
        listOf(PersonAction("Reset Link") {
            changeNow("The link was not reset. The old one still works. Try again.", "Link reset") { GroupManage.createPrimary(cid) }
        }), onDismiss = { confirmReset = false }, kind = linkKind)
    if (confirmDeleteAll) PersonConfirmSheet(linkFace, linkName, "Delete All Revoked",
        "Revoked links are removed from this list for good.",
        listOf(PersonAction("Delete All") {
            changeNow("The revoked links were not deleted. Try again.", "Revoked links deleted") { GroupManage.deleteAllRevoked(cid) }
        }), onDismiss = { confirmDeleteAll = false }, kind = linkKind)
    revokeTarget?.let { l ->
        PersonConfirmSheet(linkFace, linkName, "Revoke Link", "The link stops working at once. It moves to Revoked Links.",
            listOf(PersonAction("Revoke") {
                changeNow("The link was not revoked. Try again.", "Link revoked") { GroupManage.revokeLink(cid, l.code) }
            }), onDismiss = { revokeTarget = null }, kind = linkKind)
    }
    detail?.let { l ->
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { detail = null }, sheetState = sheet, containerColor = c.bgSecondary) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
                Text(l.name.ifEmpty { "Invite Link" }, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = c.label,
                    modifier = Modifier.padding(horizontal = 32.dp))
                Text(l.statusLine(now), fontSize = 15.sp, color = c.secondary, modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp))
                SettingsSection {
                    val revoked = l.revoked
                    if (!revoked) {
                        SettingsRow("Copy", icon = rememberVectorPainter(ImageVector.vectorResource(R.drawable.ic_sf_doc_on_doc)), trailing = Trailing.NONE) { copy(ctx, l.url); detail = null }
                        SettingsRow("Send in Fariin", icon = rememberVectorPainter(ImageVector.vectorResource(R.drawable.ic_menu_forward)), trailing = Trailing.NONE) { detail = null; sendingLink = l.url }
                        SettingsRow("Share", icon = rememberVectorPainter(ImageVector.vectorResource(R.drawable.ic_link_share)), trailing = Trailing.NONE) { share(ctx, l.url); detail = null }
                        SettingsRow("Edit", icon = rememberVectorPainter(ImageVector.vectorResource(R.drawable.ic_sf_pencil)), trailing = Trailing.NONE) { detail = null; onEdit(l) }
                        SettingsRow("Revoke Link", icon = rememberVectorPainter(ImageVector.vectorResource(com.fariin.app.R.drawable.ic_block)), titleColor = c.red, trailing = Trailing.NONE, last = true) { detail = null; revokeTarget = l }
                    } else {
                        SettingsRow("Delete Link", icon = rememberVectorPainter(ImageVector.vectorResource(R.drawable.ic_sf_trash)), titleColor = c.red, trailing = Trailing.NONE, last = true) {
                            detail = null; change("The link was not deleted. Try again.") { GroupManage.deleteLink(cid, l.code) }
                        }
                    }
                }
            }
        }
    }
    val barScroll = rememberScrollState()
    BarPage("Invite Links", onBack, action = { if (working) com.fariin.app.ui.components.BarBusyPill("Saving…") }, under = underBar(barScroll), modifier = Modifier.background(c.bgSecondary)) { top ->
        Column(Modifier.fillMaxSize().verticalScroll(barScroll).padding(top = top).navigationBarsPadding()) {
            val l = list
            val publicName = if (doc["isPublic"] == true) doc["username"] as? String ?: "" else ""
            when {
                l != null -> {
                    if (publicName.isNotEmpty()) {
                        SettingsSection(header = "Public Link", footer = w("Anyone can find this group and join it with this link. Change it under Group Type.")) {
                            LinkPill("fariin.com/$publicName") { copy(ctx, "https://fariin.com/$publicName") }
                            LinkButtons({ copy(ctx, "https://fariin.com/$publicName") }, { sendingLink = "https://fariin.com/$publicName" }, { share(ctx, "https://fariin.com/$publicName") })
                        }
                    } else {
                        val p = l.primary
                        SettingsSection(header = "Invite Link", footer = w(if (p == null) "This group has no invite link. People can only be added by its members."
                            else "Anyone with Fariin who has this link can join the group.")) {
                            if (p != null) {
                                LinkPill(p.shortUrl) { copy(ctx, p.url) }
                                LinkButtons({ copy(ctx, p.url) }, { sendingLink = p.url }, { share(ctx, p.url) })
                                Text(p.statusLine(now).substringBefore(" · "), fontSize = 14.sp, color = c.secondary,
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            } else {
                                SettingsRow(if (working) "Creating…" else "Create Link", icon = rememberVectorPainter(ImageVector.vectorResource(com.fariin.app.R.drawable.ic_link_copy)), titleColor = c.accent,
                                    trailing = Trailing.NONE, last = true,
                                    accessory = if (working) ({ com.fariin.app.ui.components.FariinSpinner(size = 20.dp) }) else null) { change("The link was not created. Try again.") { GroupManage.createPrimary(cid) } }
                            }
                        }
                        if (p != null) {
                            SettingsSection {
                                Row(Modifier.fillMaxWidth().clickable(enabled = !working) { confirmReset = true }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(ImageVector.vectorResource(R.drawable.ic_sf_arrow_clockwise), null, tint = c.label, modifier = Modifier.size(24.dp))
                                    Spacer(Modifier.width(16.dp))
                                    Column {
                                        Text("Reset Link", fontSize = 17.sp, color = c.label)
                                        Text("Makes a new link. The old one stops working.", fontSize = 13.sp, color = c.secondary)
                                    }
                                }
                            }
                            SettingsSection(footer = "When this is on, an admin approves each person before they join with this link.") {
                                SwitchLine(w("Approve New Members"), heldApproval ?: p.requestApproval, enabled = !working, last = true) { on ->
                                    heldApproval = on
                                    change("The setting was not changed. Try again.") { GroupManage.editLink(cid, p.code, mapOf("requestApproval" to on)) }
                                }
                            }
                        }
                    }
                    SettingsSection(header = "More Links", footer = "Make extra links that end after a time or a number of people.") {
                        SettingsRow("Create a New Link", icon = rememberVectorPainter(ImageVector.vectorResource(R.drawable.ic_plus_ios)), titleColor = c.accent, trailing = Trailing.NONE,
                            last = l.links.isEmpty()) { onEdit(null) }
                        l.links.forEachIndexed { i, x -> LinkRow(x, now, last = i == l.links.lastIndex) { detail = x } }
                    }
                    if (l.revoked.isNotEmpty()) SettingsSection(header = "Revoked Links") {
                        SettingsRow("Delete All Revoked", icon = rememberVectorPainter(ImageVector.vectorResource(R.drawable.ic_sf_trash)), titleColor = c.red, trailing = Trailing.NONE) { confirmDeleteAll = true }
                        l.revoked.forEachIndexed { i, x -> LinkRow(x, now, last = i == l.revoked.lastIndex) { detail = x } }
                    }
                }
                loadError != null -> SettingsSection {
                    Note(loadError ?: "")
                    SettingsRow("Try Again", titleColor = c.accent, trailing = Trailing.NONE, last = true) { scope.launch { reload() } }
                }
                else -> com.fariin.app.ui.components.LoadingRow(Modifier.padding(vertical = 14.dp))
            }
        }
    }
}

@Composable
private fun LinkPill(text: String, onCopy: () -> Unit) {
    val c = Fariin.colors
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp).clip(CircleShape).background(c.bgSecondary)
        .clickable(onClick = onCopy).heightIn(min = 52.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Text(text, fontSize = 17.sp, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(8.dp))
        Text("Tap to copy", fontSize = 12.sp, color = c.secondary)
    }
}

@Composable
private fun LinkButtons(onCopy: () -> Unit, onSend: () -> Unit, onShare: () -> Unit) {
    val c = Fariin.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        // iOS order: Copy, Send (into chats here), Share (out of the app).
        for ((label, icon, act) in listOf(Triple("Copy", ImageVector.vectorResource(R.drawable.ic_sf_doc_on_doc), onCopy),
            Triple("Send", ImageVector.vectorResource(R.drawable.ic_menu_forward), onSend), Triple("Share", ImageVector.vectorResource(R.drawable.ic_link_share), onShare))) {
            Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = act).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(54.dp).clip(CircleShape).background(c.bgSecondary), contentAlignment = Alignment.Center) {
                    Icon(icon, label, tint = c.label, modifier = Modifier.size(24.dp))
                }
                Text(label, fontSize = 13.sp, color = c.label, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/** GroupLinkRow: a coloured link circle, the name or address, the status line. */
@Composable
private fun LinkRow(l: GroupLink, now: Double, last: Boolean, onClick: () -> Unit) {
    val c = Fariin.colors
    val tint = when (l.state(now)) {
        GroupLink.State.REVOKED -> Color(0xFF8E8E93)
        GroupLink.State.EXPIRED, GroupLink.State.USED_UP -> c.red
        GroupLink.State.ACTIVE -> if (l.isPrimary) Color(0xFF2392FE)
            else if ((l.expireAt > 0 && l.expireAt - now < 86_400_000) || (l.usageLimit > 0 && (l.usageLimit - l.usedCount) <= l.usageLimit * 0.2)) Color(0xFFFF9500)
            else Color(0xFF34C759)
    }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 60.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(tint), contentAlignment = Alignment.Center) {
            Icon(ImageVector.vectorResource(com.fariin.app.R.drawable.ic_link_copy), null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(l.title, fontSize = 17.sp, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(l.statusLine(now), fontSize = 14.sp, color = c.secondary, maxLines = 1)
        }
    }
    if (!last) HorizontalDivider(thickness = 0.5.dp, color = c.separator, modifier = Modifier.padding(start = 68.dp))
}

/** iOS GroupLinkEditPage: name, Approve New Members, Time Limit, Usage Limit; Create / Save. */
@Composable
private fun LinkEditPage(cid: String, link: GroupLink?, onDone: () -> Unit) {
    val c = Fariin.colors
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(link?.name ?: "") }
    var approval by remember { mutableStateOf(link?.requestApproval ?: false) }
    val keptExpiry = (link?.expireAt ?: 0.0).takeIf { it > 0 }
    val keptUses = (link?.usageLimit ?: 0).takeIf { it > 0 }
    // Steps: None, 1h, 1d, 1w (+ Kept when the link has one).
    val timeOpts = listOf("None" to 0.0, "1h" to 3_600_000.0, "1d" to 86_400_000.0, "1w" to 604_800_000.0) + (if (keptExpiry != null) listOf("Kept" to -1.0) else emptyList())
    val useOpts = listOf("None" to 0, "1" to 1, "10" to 10, "100" to 100) + (if (keptUses != null) listOf("Kept" to -1) else emptyList())
    var time by remember { mutableStateOf(if (keptExpiry != null) timeOpts.lastIndex else 0) }
    var uses by remember { mutableStateOf(if (keptUses != null) useOpts.lastIndex else 0) }
    var saving by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val isChannel = liveGroupDoc(cid)["channel"] == true
    if (problem != null) AlertDialog(onDismissRequest = { problem = null }, containerColor = c.card, title = { Text("Couldn't save the link") },
        text = { Text(problem ?: "") }, confirmButton = { TextButton({ problem = null }) { Text("OK", color = c.accent) } })
    val barScroll = rememberScrollState()
    BarPage(if (link == null) "New Link" else "Edit Link", onDone, action = {
            if (saving) BusyMark() else Text(if (link == null) "Create" else "Save", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.accent,
                modifier = Modifier.padding(end = 4.dp).height(44.dp).floating(CircleShape).clickable {
                    saving = true
                    val f = hashMapOf<String, Any?>("name" to name.trim(), "requestApproval" to approval)
                    val t = timeOpts[time].second
                    if (t >= 0) f["expireAt"] = if (t == 0.0) 0.0 else System.currentTimeMillis() + t
                    val u = if (approval) 0 else useOpts[uses].second
                    if (u >= 0) f["usageLimit"] = u
                    scope.launch {
                        try {
                            if (link == null) GroupManage.createLink(cid, f) else GroupManage.editLink(cid, link.code, f)
                            onDone()
                        } catch (e: Exception) { problem = GroupManage.errorText(e, "The link was not saved. Try again.") }
                        saving = false
                    }
                }.padding(horizontal = 18.dp, vertical = 11.dp))
        }, under = underBar(barScroll), modifier = Modifier.background(c.bgSecondary).imePadding()) { top ->
        Column(Modifier.fillMaxSize().verticalScroll(barScroll).padding(top = top).navigationBarsPadding()) {
            SettingsSection(header = "Name", footer = "Only admins see the name. ${name.length}/32") {
                Box(Modifier.fillMaxWidth().padding(16.dp)) {
                    if (name.isEmpty()) Text("Link name (optional)", fontSize = 17.sp, color = c.secondary)
                    BasicTextField(name, { name = it.take(32) }, singleLine = true, textStyle = TextStyle(fontSize = 17.sp, color = c.label),
                        cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth())
                }
            }
            SettingsSection(footer = "An admin approves each person who asks to join with this link.") {
                SwitchLine(com.fariin.app.data.ChannelWords.forChat(isChannel, "Approve New Members"), approval, last = true) { approval = it }
            }
            val tFooter = when (timeOpts[time].first) {
                "None" -> "The link works until you turn it off."
                "1h" -> "The link stops working after 1 hour."
                "1d" -> "The link stops working after 1 day."
                "1w" -> "The link stops working after 1 week."
                else -> "The link keeps its time limit."
            }
            SettingsSection(header = "Time Limit", footer = tFooter) { StepSlider(timeOpts.map { it.first }, time) { time = it } }
            if (!approval) {
                val n = useOpts[uses].second
                SettingsSection(header = "Usage Limit", footer = GroupLink.usesFooter(if (n < 0) keptUses ?: 0 else n)) {
                    StepSlider(useOpts.map { it.first }, uses) { uses = it }
                }
            }
        }
    }
}

@Composable
private fun StepSlider(labels: List<String>, index: Int, onPick: (Int) -> Unit) {
    val c = Fariin.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { i, l ->
                Text(l, fontSize = 12.sp, color = if (i == index) c.label else c.secondary, modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
        Slider(index.toFloat(), onValueChange = { onPick(kotlin.math.round(it).toInt().coerceIn(0, labels.lastIndex)) },
            valueRange = 0f..maxOf(1, labels.lastIndex).toFloat(), steps = maxOf(0, labels.size - 2),
            colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent))
    }
}

@Composable
private fun Note(text: String) {
    Text(text, fontSize = 15.sp, color = Fariin.colors.secondary, modifier = Modifier.fillMaxWidth().padding(16.dp))
}

/**
 * A member's tag (iOS member sheet "Tag"): a few words with a count, one of eight colours, Remove Tag,
 * and Save. Everyone in the group sees it beside the name.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TagEditorSheet(cid: String, me: String, uid: String, name: String, current: com.fariin.app.data.GroupRole?, onDone: () -> Unit) {
    val c = Fariin.colors
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(current?.text ?: "") }
    var color by remember { mutableStateOf(current?.color) }
    var busy by remember { mutableStateOf(false) }
    val clean = com.fariin.app.data.GroupRole.clean(text)
    val changed = clean != (current?.text ?: "") || (clean.isNotEmpty() && color != current?.color)
    fun write(words: String) {
        busy = true
        scope.launch {
            val ok = runCatching { com.fariin.app.data.GroupRole.set(cid, me, uid, words, current?.emoji, color) }.isSuccess
            busy = false
            if (ok) onDone() else com.fariin.app.ui.components.Snack.show("Could not save the tag. Try again.")
        }
    }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDone,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = com.fariin.app.ui.components.sheetFill, shape = com.fariin.app.ui.components.SheetShape) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (uid == me) "My Tag" else "Tag", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label, modifier = Modifier.weight(1f))
                TextButton(onClick = { write(clean) }, enabled = changed && !busy) { Text("Save", fontWeight = FontWeight.SemiBold) }
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.card).padding(horizontal = 14.dp)) {
                Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(ImageVector.vectorResource(com.fariin.app.R.drawable.ic_sticker), null, tint = c.secondary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    current?.emoji?.let { Text(it, fontSize = 17.sp); Spacer(Modifier.width(6.dp)) }
                    androidx.compose.foundation.text.BasicTextField(text,
                        { v -> text = com.fariin.app.data.GroupRole.fit(v, com.fariin.app.data.GroupRole.MAX_TEXT) },
                        singleLine = true, enabled = !busy,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 17.sp, color = c.label),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent), modifier = Modifier.weight(1f),
                        decorationBox = { inner -> Box { if (text.isEmpty()) Text("Host, Teacher…", fontSize = 17.sp, color = c.secondary); inner() } })
                    Text("${text.length}/${com.fariin.app.data.GroupRole.MAX_TEXT}", fontSize = 13.sp, color = c.secondary)
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    com.fariin.app.data.GroupRole.colors.forEach { (n, rgb) ->
                        val on = (color ?: "grey") == n
                        val tint = Color(0xFF000000 or rgb)
                        Box(Modifier.weight(1f).height(44.dp).clickable(enabled = !busy) { color = if (n == "grey") null else n },
                            contentAlignment = Alignment.Center) {
                            Box(Modifier.size(32.dp).then(if (on) Modifier.border(2.dp, tint, CircleShape) else Modifier).padding(3.dp)
                                .clip(CircleShape).background(tint))
                        }
                    }
                }
            }
            Text("A short word next to the name in this group, like Host or Teacher. Everyone in the group sees it.",
                fontSize = 13.sp, color = c.secondary, modifier = Modifier.padding(horizontal = 4.dp))
            if (current != null) SettingsSection {
                SettingsRow("Remove Tag", titleColor = c.red, trailing = Trailing.NONE, last = true) { if (!busy) write("") }
            }
        }
    }
}
