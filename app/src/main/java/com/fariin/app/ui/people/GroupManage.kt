package com.fariin.app.ui.people

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
}

private fun failText(e: Exception, fallback: String = "Couldn't save. Try again."): String =
    if (e is com.google.firebase.firestore.FirebaseFirestoreException &&
        e.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) "You don't have the right to do that." else fallback

/**
 * Group Info's settings card for the owner and admins (iOS: Group Settings is theirs only): Group
 * Type (the owner), Invite Links (Add members right), Administrators, Permissions.
 */
@Composable
internal fun GroupManageRows(conv: Map<String, Any?>, me: String, open: (GroupPage) -> Unit) {
    val team = Team.from(conv)
    if (!team.isStaff(me)) return
    val isPublic = conv["isPublic"] == true && !(conv["username"] as? String).isNullOrEmpty()
    if (team.isOwner(me) && team.may(me, Right.CHANGE_INFO)) {
        SettingsRow("Group Type", icon = painterResource(R.drawable.ic_gs_group_type), value = if (isPublic) "Public" else "Private") { open(GroupPage.Type) }
    }
    if (team.may(me, Right.INVITE_USERS)) {
        SettingsRow("Invite Links", icon = rememberVectorPainter(Icons.Filled.Link),
            value = if ((conv["inviteCode"] as? String).isNullOrEmpty()) "Off" else "On") { open(GroupPage.Links) }
    }
    SettingsRow("Administrators", icon = painterResource(R.drawable.ic_gs_admins), value = "${team.sortedAdmins().size}") { open(GroupPage.Admins) }
    val (on, of) = GroupAdmins.permissionCount(conv)
    SettingsRow("Permissions", icon = painterResource(R.drawable.ic_gs_permissions), value = "$on/$of") { open(GroupPage.Permissions) }
}

/** Draws the page on top of the stack; back pops it. False when nothing is pushed. */
@Composable
internal fun GroupPages(cid: String, me: String, stack: MutableList<GroupPage>, onOpenPerson: (String) -> Unit): Boolean {
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
    }
    return true
}

@Composable
private fun Loading() = Box(Modifier.fillMaxSize().background(Fariin.colors.bgSecondary))

@Composable
private fun BusyMark() = CircularProgressIndicator(color = Fariin.colors.blue, strokeWidth = 2.dp,
    modifier = Modifier.padding(horizontal = 12.dp).size(22.dp))

@Composable
private fun Problem(text: String?, onDone: () -> Unit) {
    val c = Fariin.colors
    if (text != null) AlertDialog(onDismissRequest = onDone, containerColor = c.card, title = { Text("Could not do that") },
        text = { Text(text) }, confirmButton = { TextButton(onDone) { Text("OK", color = c.blue) } })
}

// ---- Administrators (iOS GroupAdminsView) -----------------------------------------------------

@Composable
private fun GroupAdminsPage(cid: String, me: String, onBack: () -> Unit, push: (GroupPage) -> Unit) {
    val c = Fariin.colors
    val doc = liveGroupDoc(cid)
    if (doc.isEmpty()) { Loading(); return }
    val team = Team.from(doc)
    Column(Modifier.fillMaxSize().background(c.bgSecondary)) {
        PageBar("Administrators", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            SettingsSection(footer = GroupAdmins.ADMINS_FOOTER) {
                if (team.may(me, Right.ADD_ADMINS) && team.others().isNotEmpty()) {
                    SettingsRow("Add Admin", icon = rememberVectorPainter(Icons.Filled.PersonAdd), titleColor = c.blue,
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
    Column(Modifier.fillMaxSize().background(c.bgSecondary)) {
        PageBar("Choose Member", onBack)
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
            Icon(Icons.Filled.VerifiedUser, null, tint = c.label, modifier = Modifier.size(15.dp))
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
    Column(Modifier.fillMaxSize().background(c.bgSecondary)) {
        PageBar("Add Admin", onDone, action = {
            if (busy) BusyMark() else Text("Add", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.blue,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                    busy = true
                    scope.launch {
                        try {
                            GroupManage.promote(cid, uid, GroupAdmins.toWrite(on, grantable), team)
                            Snack.show("$name is now an admin"); onDone()
                        } catch (e: Exception) { problem = failText(e) }
                        busy = false
                    }
                }.padding(horizontal = 12.dp, vertical = 8.dp))
        })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
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
    if (confirmDismiss) AlertDialog(onDismissRequest = { confirmDismiss = false }, containerColor = c.card,
        title = { Text("Dismiss ${team.names[uid] ?: "them"} as admin?") },
        confirmButton = { TextButton({
            confirmDismiss = false; saving = true
            scope.launch {
                try { GroupManage.demote(cid, uid); Snack.show("${team.names[uid] ?: "They"} is no longer an admin"); leave() }
                catch (e: Exception) { problem = failText(e) }
                saving = false
            }
        }) { Text("Dismiss Admin", color = c.red) } },
        dismissButton = { TextButton({ confirmDismiss = false }) { Text("Cancel", color = c.blue) } })
    Column(Modifier.fillMaxSize().background(c.bgSecondary)) {
        PageBar(if (editable) "Admin Rights" else if (uid == me) "My Admin Rights" else name, onBack, action = { if (saving) BusyMark() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
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
                    SettingsRow("Dismiss Admin", icon = rememberVectorPainter(Icons.Filled.PersonRemove), titleColor = c.red,
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
    var problem by remember { mutableStateOf<String?>(null) }
    fun run(block: suspend () -> Unit) {
        scope.launch {
            try { block(); onClose() } catch (e: Exception) { problem = failText(e) }
        }
    }
    Problem(problem) { problem = null; onClose() }
    if (confirmPromote) AlertDialog(onDismissRequest = { confirmPromote = false }, containerColor = c.card,
        title = { Text("Make $name an admin?") }, text = { Text(GroupAdmins.MAKE_ADMIN_BODY) },
        confirmButton = { TextButton({
            confirmPromote = false
            run {
                GroupManage.promote(cid, uid, GroupAdmins.startRights(team.grantable(me)).let { GroupAdmins.toWrite(it, team.grantable(me)) }, team)
                Snack.show("$name is now an admin")
            }
        }) { Text("Make Admin", color = c.blue) } },
        dismissButton = { TextButton({ confirmPromote = false }) { Text("Cancel", color = c.blue) } })
    if (confirmDismiss) AlertDialog(onDismissRequest = { confirmDismiss = false }, containerColor = c.card,
        title = { Text("Dismiss $name as admin?") },
        confirmButton = { TextButton({
            confirmDismiss = false
            run { GroupManage.demote(cid, uid); Snack.show("$name is no longer an admin") }
        }) { Text("Dismiss Admin", color = c.red) } },
        dismissButton = { TextButton({ confirmDismiss = false }) { Text("Cancel", color = c.blue) } })
    if (confirmBan) AlertDialog(onDismissRequest = { confirmBan = false }, containerColor = c.card,
        title = { Text("Ban $name?") }, text = { Text(GroupAdmins.BAN_BODY) },
        confirmButton = { TextButton({
            confirmBan = false
            run { GroupManage.removeMember(cid, uid, name); Snack.show("$name was removed from the group.") }
        }) { Text("Ban", color = c.red) } },
        dismissButton = { TextButton({ confirmBan = false }) { Text("Cancel", color = c.blue) } })
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
                    if (manage && !team.isAdmin(uid)) add(Triple("Make Group Admin", rememberVectorPainter(Icons.Filled.VerifiedUser)) { confirmPromote = true })
                    if (manage && team.isAdmin(uid)) add(Triple("Edit Admin Rights", rememberVectorPainter(Icons.Filled.Tune)) { onClose(); open(GroupPage.Rights(uid)) })
                    if (uid == me && team.isStaff(me)) add(Triple("My Admin Rights", rememberVectorPainter(Icons.Filled.VerifiedUser)) { onClose(); open(GroupPage.Rights(uid)) })
                    if (uid != me) add(Triple("Open Profile", rememberVectorPainter(Icons.Filled.Person)) { onClose(); onProfile(uid) })
                }
                val red = buildList<Triple<String, Painter, () -> Unit>> {
                    if (manage && team.isAdmin(uid)) add(Triple("Dismiss Admin", rememberVectorPainter(Icons.Filled.PersonRemove)) { confirmDismiss = true })
                    if (team.canRemove(me, uid)) add(Triple("Ban from Group", rememberVectorPainter(Icons.Filled.Block)) { confirmBan = true })
                }
                rows.forEachIndexed { i, (t, p, a) -> SettingsRow(t, icon = p, trailing = Trailing.NONE, last = i == rows.lastIndex && red.isEmpty(), onClick = a) }
                red.forEachIndexed { i, (t, p, a) -> SettingsRow(t, icon = p, titleColor = c.red, trailing = Trailing.NONE, last = i == red.lastIndex, onClick = a) }
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
    Column(Modifier.fillMaxSize().background(c.bgSecondary)) {
        PageBar("Permissions", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
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
                            Icon(if (mediaOpen) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, "Show the kinds", tint = c.secondary)
                        }
                        Switch(allOn, onCheckedChange = if (canEdit) ({ v -> flip("media.all", v) { GroupManage.setAllMedia(cid, v) } }) else null,
                            enabled = canEdit, colors = SwitchDefaults.colors(checkedTrackColor = c.blue, checkedThumbColor = Color.White))
                    }
                    HorizontalDivider(thickness = 0.5.dp, color = c.separator, modifier = Modifier.padding(start = 16.dp))
                    if (mediaOpen) SendKind.entries.forEach { k ->
                        val on = shown("media.${k.slug}", GroupAdmins.membersMaySend(doc, k))
                        Row(Modifier.fillMaxWidth().clickable(enabled = canEdit) { flip("media.${k.slug}", !on) { GroupManage.setKind(cid, k, !on, doc) } }
                            .heightIn(min = 48.dp).padding(start = 22.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (on) Icons.Filled.CheckCircle else Icons.Outlined.Circle, null, tint = if (on) c.blue else c.secondary, modifier = Modifier.size(24.dp))
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
                        colors = SliderDefaults.colors(thumbColor = c.blue, activeTrackColor = c.blue),
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
                SwitchLine("Protect Content", protect, enabled = canEdit, last = true) { v -> flip("protect", v) { GroupManage.setProtected(cid, v) } }
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
    val showPublic = wantsPublic ?: isPublicNow
    val typed = name ?: current
    val changed = typed != current
    LaunchedEffect(typed, showPublic) {
        ok = false
        if (!showPublic || !changed || typed.isEmpty()) { note = null; return@LaunchedEffect }
        com.fariin.app.channel.ChannelRules.linkProblem(typed)?.let { note = it; return@LaunchedEffect }
        note = "Checking..."
        delay(400)
        val r = com.fariin.app.channel.ChannelService.checkLink(cid, typed)
        note = r ?: "fariin.com/$typed is available."
        ok = r == null
    }
    fun save(value: String) {
        saving = true
        scope.launch {
            try { com.fariin.app.channel.ChannelService.setLink(cid, value); onBack() }
            catch (e: Exception) { problem = com.fariin.app.channel.ChannelService.errorText(e, "Could not save the group link. Try again.") }
            saving = false
        }
    }
    if (confirmPrivate) AlertDialog(onDismissRequest = { confirmPrivate = false }, containerColor = c.card,
        title = { Text("Make this group private?") },
        text = { Text("fariin.com/$current will stop working. The link stays reserved for this group for 30 days, then anyone can take it.") },
        confirmButton = { TextButton({ confirmPrivate = false; save("") }) { Text("Make Private", color = c.red) } },
        dismissButton = { TextButton({ confirmPrivate = false }) { Text("Cancel", color = c.blue) } })
    if (problem != null) AlertDialog(onDismissRequest = { problem = null }, containerColor = c.card, title = { Text("Could not save") },
        text = { Text(problem ?: "") }, confirmButton = { TextButton({ problem = null }) { Text("OK", color = c.blue) } })
    Column(Modifier.fillMaxSize().background(c.bgSecondary).imePadding()) {
        PageBar("Group Type", onBack, action = {
            if (saving) BusyMark() else Text("Save", fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                color = if (showPublic && changed && ok) c.blue else c.secondary,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = showPublic && changed && ok) { save(typed) }
                    .padding(horizontal = 12.dp, vertical = 8.dp))
        })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            SettingsSection(header = "Group Type", footer = if (showPublic) "Anyone can find this group in search and join it without an invite."
                else "People can join only with an invite link, or when an admin adds them.") {
                TypeLine("Public Group", showPublic) { wantsPublic = true }
                TypeLine("Private Group", !showPublic, last = true) { if (isPublicNow) confirmPrivate = true else wantsPublic = false }
            }
            if (showPublic) {
                SettingsSection(header = "Public Link", footer = when {
                    !changed && typed.isNotEmpty() -> "People can open this group with fariin.com/$typed."
                    note != null -> note
                    else -> "Pick a link of ${com.fariin.app.channel.ChannelRules.LINK_MIN} to ${com.fariin.app.channel.ChannelRules.LINK_MAX} characters: letters, numbers and _."
                }) {
                    Row(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("fariin.com/", fontSize = 17.sp, color = c.secondary)
                        Box(Modifier.weight(1f)) {
                            if (typed.isEmpty()) Text("link", fontSize = 17.sp, color = c.secondary)
                            BasicTextField(typed, { name = it.trim() }, singleLine = true, textStyle = TextStyle(fontSize = 17.sp, color = c.label),
                                cursorBrush = SolidColor(c.blue), modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
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
        if (selected) Icon(Icons.Filled.Check, "Selected", tint = c.label, modifier = Modifier.size(20.dp))
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
    LaunchedEffect(cid) { reload() }
    if (problem != null) AlertDialog(onDismissRequest = { problem = null }, containerColor = c.card, title = { Text("Couldn't change the link") },
        text = { Text(problem ?: "") }, confirmButton = { TextButton({ problem = null }) { Text("OK", color = c.blue) } })
    if (confirmReset) AlertDialog(onDismissRequest = { confirmReset = false }, containerColor = c.card, title = { Text("Reset Link") },
        text = { Text("A new link replaces this one, and the current link stops working for everyone who has it.") },
        confirmButton = { TextButton({ confirmReset = false; change("The link was not reset. The old one still works. Try again.") { GroupManage.createPrimary(cid) } }) { Text("Reset Link", color = c.red) } },
        dismissButton = { TextButton({ confirmReset = false }) { Text("Cancel", color = c.blue) } })
    if (confirmDeleteAll) AlertDialog(onDismissRequest = { confirmDeleteAll = false }, containerColor = c.card, title = { Text("Delete All Revoked") },
        text = { Text("Revoked links are removed from this list for good.") },
        confirmButton = { TextButton({ confirmDeleteAll = false; change("The revoked links were not deleted. Try again.") { GroupManage.deleteAllRevoked(cid) } }) { Text("Delete All", color = c.red) } },
        dismissButton = { TextButton({ confirmDeleteAll = false }) { Text("Cancel", color = c.blue) } })
    revokeTarget?.let { l ->
        AlertDialog(onDismissRequest = { revokeTarget = null }, containerColor = c.card, title = { Text("Revoke Link") },
            text = { Text("The link stops working at once. It moves to Revoked Links.") },
            confirmButton = { TextButton({ revokeTarget = null; change("The link was not revoked. Try again.") { GroupManage.revokeLink(cid, l.code) } }) { Text("Revoke", color = c.red) } },
            dismissButton = { TextButton({ revokeTarget = null }) { Text("Cancel", color = c.blue) } })
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
                        SettingsRow("Copy", icon = rememberVectorPainter(Icons.Filled.ContentCopy), trailing = Trailing.NONE) { copy(ctx, l.url); detail = null }
                        SettingsRow("Share", icon = rememberVectorPainter(Icons.Filled.Share), trailing = Trailing.NONE) { share(ctx, l.url); detail = null }
                        SettingsRow("Edit", icon = rememberVectorPainter(Icons.Filled.Edit), trailing = Trailing.NONE) { detail = null; onEdit(l) }
                        SettingsRow("Revoke Link", icon = rememberVectorPainter(Icons.Filled.Block), titleColor = c.red, trailing = Trailing.NONE, last = true) { detail = null; revokeTarget = l }
                    } else {
                        SettingsRow("Delete Link", icon = rememberVectorPainter(Icons.Filled.Delete), titleColor = c.red, trailing = Trailing.NONE, last = true) {
                            detail = null; change("The link was not deleted. Try again.") { GroupManage.deleteLink(cid, l.code) }
                        }
                    }
                }
            }
        }
    }
    Column(Modifier.fillMaxSize().background(c.bgSecondary)) {
        PageBar("Invite Links", onBack, action = { if (working) BusyMark() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            val l = list
            val publicName = if (doc["isPublic"] == true) doc["username"] as? String ?: "" else ""
            when {
                l != null -> {
                    if (publicName.isNotEmpty()) {
                        SettingsSection(header = "Public Link", footer = "Anyone can find this group and join it with this link. Change it under Group Type.") {
                            LinkPill("fariin.com/$publicName") { copy(ctx, "https://fariin.com/$publicName") }
                            LinkButtons({ copy(ctx, "https://fariin.com/$publicName") }, { share(ctx, "https://fariin.com/$publicName") })
                        }
                    } else {
                        val p = l.primary
                        SettingsSection(header = "Invite Link", footer = if (p == null) "This group has no invite link. People can only be added by its members."
                            else "Anyone with Fariin who has this link can join the group.") {
                            if (p != null) {
                                LinkPill(p.shortUrl) { copy(ctx, p.url) }
                                LinkButtons({ copy(ctx, p.url) }, { share(ctx, p.url) })
                                Text(p.statusLine(now).substringBefore(" · "), fontSize = 14.sp, color = c.secondary,
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            } else {
                                SettingsRow(if (working) "Creating..." else "Create Link", icon = rememberVectorPainter(Icons.Filled.Link), titleColor = c.blue,
                                    trailing = Trailing.NONE, last = true) { change("The link was not created. Try again.") { GroupManage.createPrimary(cid) } }
                            }
                        }
                        if (p != null) {
                            SettingsSection {
                                Row(Modifier.fillMaxWidth().clickable(enabled = !working) { confirmReset = true }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Refresh, null, tint = c.label, modifier = Modifier.size(24.dp))
                                    Spacer(Modifier.width(16.dp))
                                    Column {
                                        Text("Reset Link", fontSize = 17.sp, color = c.label)
                                        Text("Makes a new link. The old one stops working.", fontSize = 13.sp, color = c.secondary)
                                    }
                                }
                            }
                            SettingsSection(footer = "When this is on, an admin approves each person before they join with this link.") {
                                SwitchLine("Approve New Members", heldApproval ?: p.requestApproval, enabled = !working, last = true) { on ->
                                    heldApproval = on
                                    change("The setting was not changed. Try again.") { GroupManage.editLink(cid, p.code, mapOf("requestApproval" to on)) }
                                }
                            }
                        }
                    }
                    SettingsSection(header = "More Links", footer = "Make extra links that end after a time or a number of people.") {
                        SettingsRow("Create a New Link", icon = rememberVectorPainter(Icons.Filled.Add), titleColor = c.blue, trailing = Trailing.NONE,
                            last = l.links.isEmpty()) { onEdit(null) }
                        l.links.forEachIndexed { i, x -> LinkRow(x, now, last = i == l.links.lastIndex) { detail = x } }
                    }
                    if (l.revoked.isNotEmpty()) SettingsSection(header = "Revoked Links") {
                        SettingsRow("Delete All Revoked", icon = rememberVectorPainter(Icons.Filled.Delete), titleColor = c.red, trailing = Trailing.NONE) { confirmDeleteAll = true }
                        l.revoked.forEachIndexed { i, x -> LinkRow(x, now, last = i == l.revoked.lastIndex) { detail = x } }
                    }
                }
                loadError != null -> SettingsSection {
                    Note(loadError ?: "")
                    SettingsRow("Try Again", titleColor = c.blue, trailing = Trailing.NONE, last = true) { scope.launch { reload() } }
                }
                else -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { BusyMark() }
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
private fun LinkButtons(onCopy: () -> Unit, onShare: () -> Unit) {
    val c = Fariin.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        for ((label, icon, act) in listOf(Triple("Copy", Icons.Filled.ContentCopy, onCopy), Triple("Share", Icons.Filled.Share, onShare))) {
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
            Icon(Icons.Filled.Link, null, tint = Color.White, modifier = Modifier.size(20.dp))
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
    if (problem != null) AlertDialog(onDismissRequest = { problem = null }, containerColor = c.card, title = { Text("Couldn't save the link") },
        text = { Text(problem ?: "") }, confirmButton = { TextButton({ problem = null }) { Text("OK", color = c.blue) } })
    Column(Modifier.fillMaxSize().background(c.bgSecondary).imePadding()) {
        PageBar(if (link == null) "New Link" else "Edit Link", onDone, action = {
            if (saving) BusyMark() else Text(if (link == null) "Create" else "Save", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.blue,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
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
                }.padding(horizontal = 12.dp, vertical = 8.dp))
        })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            SettingsSection(header = "Name", footer = "Only admins see the name. ${name.length}/32") {
                Box(Modifier.fillMaxWidth().padding(16.dp)) {
                    if (name.isEmpty()) Text("Link name (optional)", fontSize = 17.sp, color = c.secondary)
                    BasicTextField(name, { name = it.take(32) }, singleLine = true, textStyle = TextStyle(fontSize = 17.sp, color = c.label),
                        cursorBrush = SolidColor(c.blue), modifier = Modifier.fillMaxWidth())
                }
            }
            SettingsSection(footer = "An admin approves each person who asks to join with this link.") {
                SwitchLine("Approve New Members", approval, last = true) { approval = it }
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
            colors = SliderDefaults.colors(thumbColor = c.blue, activeTrackColor = c.blue))
    }
}

@Composable
private fun Note(text: String) {
    Text(text, fontSize = 15.sp, color = Fariin.colors.secondary, modifier = Modifier.fillMaxWidth().padding(16.dp))
}
