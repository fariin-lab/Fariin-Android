package com.fariin.app.ui.people

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.data.Conversation
import com.fariin.app.data.FirestorePeople
import com.fariin.app.data.GroupRules
import com.fariin.app.data.PeopleApi
import com.fariin.app.data.Person
import com.fariin.app.data.Usernames
import com.fariin.app.ui.components.Avatar
import com.fariin.app.ui.photo.rememberCirclePhotoPicker
import com.fariin.app.ui.settings.PageBar
import com.fariin.app.ui.settings.SettingsRow
import com.fariin.app.ui.settings.SettingsSection
import com.fariin.app.ui.settings.Trailing
import com.fariin.app.ui.theme.Fariin
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

@Composable
private fun Field(value: String, placeholder: String, onChange: (String) -> Unit) {
    val c = Fariin.colors
    Box(Modifier.fillMaxWidth().padding(16.dp)) {
        if (value.isEmpty()) Text(placeholder, fontSize = 17.sp, color = c.secondary)
        BasicTextField(value, onChange, singleLine = true, textStyle = TextStyle(fontSize = 17.sp, color = c.label),
            cursorBrush = SolidColor(c.blue), modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun TextAction(text: String, enabled: Boolean, onClick: () -> Unit) {
    Text(text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
        color = if (enabled) Fariin.colors.blue else Fariin.colors.secondary,
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp))
}

/**
 * Edit Profile (iOS EditProfileView): the photo with "Set New Photo", the name, and the
 * Username row that opens the username page. The photo goes to `profiles/{me}.jpg`.
 */
@Composable
fun EditProfileScreen(api: PeopleApi, onBack: () -> Unit) {
    val c = Fariin.colors
    val scope = rememberCoroutineScope()
    var me by remember { mutableStateOf<Person?>(null) }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { me = api.profile(api.me); name = me?.name ?: "" }
    // The picked photo goes to the circle cropper first (iOS ProfilePhotoCropper, its Avatar stage).
    val picker = rememberCirclePhotoPicker { jpeg ->
        scope.launch {
            busy = true
            try { api.uploadPhoto(jpeg); me = api.profile(api.me) } catch (_: Exception) { error = "Couldn't upload the photo. Try again." }
            busy = false
        }
    }
    val pick = picker.launch
    if (picker.pending != null) { picker.Host("Avatar", "Shown in chats, stories and groups"); return }
    if (page == "username") { UsernameScreen(api, me?.handle ?: "", onBack = { page = ""; scope.launch { me = api.profile(api.me) } }); return }
    error?.let { e -> AlertDialog(onDismissRequest = { error = null }, text = { Text(e) },
        confirmButton = { TextButton({ error = null }) { Text("OK", color = c.blue) } }, containerColor = c.card) }
    Column(Modifier.fillMaxSize().background(c.bgSecondary)) {
        PageBar("Edit Profile", onBack = onBack, action = {
            TextAction("Done", enabled = name.isNotBlank() && !busy) {
                scope.launch {
                    busy = true
                    try { if (name.trim() != me?.name) api.saveName(name); onBack() } catch (_: Exception) { error = "Couldn't save. Try again." }
                    busy = false
                }
            }
        })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                Avatar(me?.photoUrl, 110.dp)
                if (busy) CircularProgressIndicator(color = c.blue, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
            }
            Text("Set New Photo", fontSize = 17.sp, color = c.blue, modifier = Modifier.clickable(onClick = pick).padding(10.dp))
            Spacer(Modifier.height(8.dp))
            SettingsSection(footer = "Enter your name and add a profile photo.") { Field(name, "Name") { name = it.take(64) } }
            SettingsSection {
                SettingsRow("Username", value = me?.handle?.takeIf { it.isNotEmpty() }?.let { "@$it" } ?: "None", last = true) { page = "username" }
            }
        }
    }
}

private sealed interface NameStatus {
    data object Quiet : NameStatus
    data object Checking : NameStatus
    data class Available(val name: String) : NameStatus
    data object Taken : NameStatus
    data class Problem(val why: String) : NameStatus
}

/**
 * The username page (iOS SettingsView username editor): letters, numbers and _, 5 to 30; checked
 * on the server 350 ms after typing stops; when taken, up to three free names close to it, each
 * already asked. Done claims it on the server (`claimUsername`).
 */
@Composable
fun UsernameScreen(api: PeopleApi, current: String, onBack: () -> Unit) {
    val c = Fariin.colors
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf(current) }
    var status by remember { mutableStateOf<NameStatus>(NameStatus.Quiet) }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var claiming by remember { mutableStateOf(false) }
    val unchanged = draft == current
    LaunchedEffect(draft) {
        suggestions = emptyList()
        if (unchanged) { status = NameStatus.Quiet; return@LaunchedEffect }
        Usernames.shapeProblem(draft)?.let { status = NameStatus.Problem(it); return@LaunchedEffect }
        if (draft.length < Usernames.MIN) { status = NameStatus.Quiet; return@LaunchedEffect }
        status = NameStatus.Checking
        delay(350)
        status = try {
            val (free, reason) = api.checkUsername(draft)
            when { free -> NameStatus.Available(draft); reason == null || reason == "taken" -> NameStatus.Taken; else -> NameStatus.Problem(reason) }
        } catch (_: Exception) { NameStatus.Quiet }
        if (status == NameStatus.Taken) {
            val pool = Usernames.suggestionPool(draft)
            suggestions = coroutineScope {
                pool.map { n -> async { if (runCatching { api.checkUsername(n).first }.getOrDefault(false)) n else null } }.awaitAll()
            }.filterNotNull().take(3)
        }
    }
    Column(Modifier.fillMaxSize().background(c.bgSecondary)) {
        PageBar("Username", onBack = onBack, action = {
            TextAction("Done", enabled = !claiming && (Usernames.isValid(draft) && status !is NameStatus.Taken && status !is NameStatus.Problem)) {
                if (unchanged) { onBack(); return@TextAction }
                claiming = true
                scope.launch {
                    try { api.claimUsername(draft); onBack() } catch (e: Exception) {
                        val msg = e.message ?: ""
                        status = if (msg.contains("taken", true)) NameStatus.Taken else NameStatus.Problem(msg.ifEmpty { "Couldn't save. Try again." })
                    }
                    claiming = false
                }
            }
        })
        SettingsSection { Row(verticalAlignment = Alignment.CenterVertically) {
            Text("@", fontSize = 17.sp, color = c.secondary, modifier = Modifier.padding(start = 16.dp))
            Box(Modifier.weight(1f)) { Field(draft, "Username") { draft = Usernames.sanitize(it) } }
        } }
        Column(Modifier.padding(horizontal = 32.dp)) {
            when (val s = status) {
                NameStatus.Quiet -> {}
                NameStatus.Checking -> Text("Checking username…", fontSize = 13.sp, color = c.secondary)
                is NameStatus.Available -> Text("${s.name} is available.", fontSize = 13.sp, color = androidx.compose.ui.graphics.Color(0xFF34C759))
                NameStatus.Taken -> {
                    Text("This username is taken.", fontSize = 13.sp, color = c.red)
                    if (suggestions.isNotEmpty()) Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Free:", fontSize = 13.sp, color = c.secondary)
                        suggestions.forEach { n -> Text("@$n", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = c.label,
                            modifier = Modifier.clip(CircleShape).background(c.fieldFill).clickable { draft = n }.padding(horizontal = 9.dp, vertical = 4.dp)) }
                    }
                }
                is NameStatus.Problem -> Text(s.why, fontSize = 13.sp, color = c.red)
            }
            Spacer(Modifier.height(6.dp))
            Text("Letters, numbers and _ only. ${Usernames.MIN} to ${Usernames.MAX} characters.", fontSize = 13.sp, color = c.secondary)
            Spacer(Modifier.height(6.dp))
            Text("This link opens a chat with you:", fontSize = 13.sp, color = c.secondary)
            Text(Usernames.profileLink(draft), fontSize = 13.sp, color = c.blue)
        }
    }
}

/** People I can add: the other person of each 1:1 chat I have (iOS NewGroupView lists contacts). */
internal fun contactsFrom(chats: List<Conversation>, me: String): List<Person> =
    chats.filter { !it.isGroup && (it.startedBy.isEmpty() || it.accepted) }.map {
        val u = it.otherUid(me)
        Person(u, name = it.names[u] ?: "", photoUrl = it.photos[u])
    }.filter { it.uid.isNotEmpty() }.distinctBy { it.uid }

/** A whole page of [PickPeople] with its action in the top bar (a channel's Add Followers). */
@Composable
internal fun PickPeoplePage(title: String, action: String, people: List<Person>, onBack: () -> Unit, onDone: (List<String>) -> Unit) {
    var picked by remember { mutableStateOf(setOf<String>()) }
    androidx.activity.compose.BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(Fariin.colors.bgSecondary)) {
        PageBar(title, onBack = onBack, action = { TextAction(action, picked.isNotEmpty()) { onDone(picked.toList()) } })
        PickPeople(people, picked) { u -> picked = if (u in picked) picked - u else picked + u }
    }
}

@Composable
private fun PickPeople(people: List<Person>, picked: Set<String>, onToggle: (String) -> Unit) {
    val c = Fariin.colors
    var q by remember { mutableStateOf("") }
    val chosen = people.filter { it.uid in picked }
    if (chosen.isNotEmpty()) LazyRow(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        items(chosen, key = { it.uid }) { p ->
            Column(Modifier.width(64.dp).clickable { onToggle(p.uid) }, horizontalAlignment = Alignment.CenterHorizontally) {
                Avatar(p.photoUrl, 48.dp)
                Text(p.shownName, fontSize = 12.sp, color = c.label, maxLines = 1)
            }
        }
    }
    SearchField(q, "Search") { q = it }
    LazyColumn(Modifier.fillMaxSize()) {
        items(people.filter { q.isBlank() || it.shownName.contains(q, true) }, key = { it.uid }) { p ->
            PersonRow(p.photoUrl, p.shownName, "", onClick = { onToggle(p.uid) }) {
                Icon(if (p.uid in picked) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, null,
                    tint = if (p.uid in picked) c.blue else c.tertiary)
            }
        }
    }
}

/**
 * New Group (iOS NewGroupView): pick members, then Next to the name and photo, then Create.
 * The group is made plain (e2ee false) as iOS makes them, with "<me> created the group".
 */
@Composable
fun NewGroupScreen(api: PeopleApi, chats: List<Conversation>, onBack: () -> Unit, onCreated: (String) -> Unit) {
    val c = Fariin.colors
    val scope = rememberCoroutineScope()
    val people = remember(chats) { contactsFrom(chats, api.me) }
    var picked by remember { mutableStateOf(setOf<String>()) }
    var step by remember { mutableStateOf(1) }
    var title by remember { mutableStateOf("") }
    var photo by remember { mutableStateOf<ByteArray?>(null) }
    // The Settings default for new chats starts the choice (iOS NewGroupView).
    var timer by remember { mutableStateOf(com.fariin.app.data.Disappearing.defaultSeconds) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // The picked photo goes to the circle cropper, as on Edit Group.
    val picker = rememberCirclePhotoPicker { photo = it }
    val pick = picker.launch
    if (picker.pending != null) { picker.Host("Group photo", "Shown in chats and on the group page"); return }
    error?.let { e -> AlertDialog(onDismissRequest = { error = null }, text = { Text(e) },
        confirmButton = { TextButton({ error = null }) { Text("OK", color = c.blue) } }, containerColor = c.card) }
    Column(Modifier.fillMaxSize().background(c.bgSecondary)) {
        if (step == 1) {
            PageBar("Add Members", onBack = onBack, action = { TextAction("Next", picked.isNotEmpty()) { step = 2 } })
            Text("${picked.size} of 1024 selected", fontSize = 13.sp, color = c.secondary, modifier = Modifier.padding(horizontal = 16.dp))
            PickPeople(people, picked) { u -> picked = if (u in picked) picked - u else picked + u }
        } else {
            PageBar("New Group", onBack = { step = 1 }, action = {
                TextAction("Create", title.isNotBlank() && !busy) {
                    busy = true
                    scope.launch {
                        try {
                            val cid = api.createGroup(title, picked.toList(), photo)
                            // After "created the group", as iOS: the timer and its notice for everyone.
                            if (timer > 0) api.setDisappear(cid, timer)
                            onCreated(cid)
                        } catch (_: Exception) { error = "Could not create the group. Try again." }
                        busy = false
                    }
                }
            })
            // iOS NewGroupView: the photo and the name side by side in one rounded card.
            SettingsSection {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(64.dp).clip(CircleShape).background(c.fieldFill).clickable(onClick = pick), contentAlignment = Alignment.Center) {
                        val p = photo
                        if (p != null) {
                            val bmp = remember(p) { android.graphics.BitmapFactory.decodeByteArray(p, 0, p.size)?.asImageBitmap() }
                            if (bmp != null) androidx.compose.foundation.Image(bmp, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        } else Text("Photo", fontSize = 13.sp, color = c.blue)
                    }
                    Spacer(Modifier.width(4.dp))
                    Box(Modifier.weight(1f)) { Field(title, "Group Name") { title = it.take(100) } }
                }
            }
            NewGroupTimerSection(timer) { timer = it }
            val members = people.filter { it.uid in picked }
            SettingsSection(header = "Members · ${picked.size + 1}") {   // iOS NewGroupView: me included
                members.forEach { p -> PersonRow(p.photoUrl, p.shownName, "", onClick = null) }
            }
        }
    }
}

/**
 * Group Info (iOS GroupInfoView): photo, name, "Group · N members"; Add Members (admins), the
 * member list with Owner / Admin tags (an admin taps one for the member card), the owner's and
 * admins' settings (Group Type, Invite Links, Administrators, Permissions; GroupManage.kt), Leave Group with
 * the owner hand-over line.
 */
@Composable
fun GroupInfoScreen(api: PeopleApi, conv: Map<String, Any?>, cid: String, chats: List<Conversation>, onBack: () -> Unit,
                    onLeft: () -> Unit, onOpenPerson: (String) -> Unit) {
    val c = Fariin.colors
    val scope = rememberCoroutineScope()
    val me = api.me
    val users = (conv["users"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
    val admins = (conv["admins"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
    val owner = conv["createdBy"] as? String ?: ""
    val names = (conv["names"] as? Map<*, *>)?.mapNotNull { (k, v) -> (k as? String)?.let { it to (v as? String ?: "") } }?.toMap() ?: emptyMap()
    val photos = (conv["photos"] as? Map<*, *>)?.mapNotNull { (k, v) -> (k as? String)?.let { it to (v as? String ?: "") } }?.toMap() ?: emptyMap()
    // Not known yet counts as a member, so the page never flashes the left-group rows (iOS iAmMember).
    val iAmMember = users.isEmpty() || me in users
    val iAmAdmin = iAmMember && (me in admins || me == owner)
    val canEdit = iAmMember && GroupRules.canEditInfo(me, conv)
    var editing by remember { mutableStateOf(false) }
    var editAtDescription by remember { mutableStateOf(false) }
    val desc = GroupRules.description(conv)
    var adding by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf(setOf<String>()) }
    var askLeave by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var timerOpen by remember { mutableStateOf(false) }
    val timer = (conv["disappearSeconds"] as? Number)?.toInt() ?: 0
    // The management pages pushed over this one (Administrators, Permissions, Group Type, Invite Links).
    val pages = remember(cid) { androidx.compose.runtime.mutableStateListOf<GroupPage>() }
    var card by remember { mutableStateOf<String?>(null) }
    val team = com.fariin.app.data.GroupAdmins.Team.from(conv)
    notice?.let { n -> AlertDialog(onDismissRequest = { notice = null }, text = { Text(n) },
        confirmButton = { TextButton({ notice = null }) { Text("OK", color = c.blue) } }, containerColor = c.card) }
    if (askLeave) {
        val plan = GroupRules.leavePlan(me, "", conv)
        AlertDialog(onDismissRequest = { askLeave = false }, title = { Text("Leave Group?") },
            text = { if (owner == me) Text(GroupRules.ownerLeaveLine(plan.heir?.let { names[it] ?: "a member" })) else Text("You won't get messages from this group any more.") },
            confirmButton = {
                // iOS: the owner may pick the next owner instead (Choose Another Owner), stacked as Android does three choices.
                Column(horizontalAlignment = Alignment.End) {
                    if (com.fariin.app.data.OwnerTransfer.offersChooseAnother(me, owner, users)) {
                        TextButton({ askLeave = false; pages.add(GroupPage.ChooseOwner) }) {
                            Text(com.fariin.app.data.OwnerTransfer.CHOOSE_ANOTHER, color = c.blue)
                        }
                    }
                    TextButton({
                        askLeave = false
                        // iOS LeaveGroupUndo: out of the list and the screen at once, Undo for 5 s, then the real leave.
                        com.fariin.app.data.LeaveUndo.start(cid, conv["title"] as? String ?: "the group") { api.leaveGroup(cid) }
                        com.fariin.app.ui.components.Snack.show("You left the group.", "Undo", durationMs = com.fariin.app.data.LeaveUndo.WINDOW_MS) {
                            com.fariin.app.data.LeaveUndo.undo()
                        }
                        onLeft()
                    }) { Text("Leave", color = c.red) }
                    TextButton({ askLeave = false }) { Text("Cancel", color = c.blue) }
                }
            }, containerColor = c.card)
    }
    if (adding) {
        val people = remember(chats) { contactsFrom(chats, me).filter { it.uid !in users } }
        Column(Modifier.fillMaxSize().background(c.bgSecondary)) {
            PageBar("Add Members", onBack = { adding = false }, action = {
                TextAction("Add", picked.isNotEmpty()) {
                    scope.launch {
                        try { api.addMembers(cid, picked.toList())?.let { notice = it }; adding = false; picked = emptySet() }
                        catch (_: Exception) { notice = "Couldn't add them. Try again." }
                    }
                }
            })
            PickPeople(people, picked) { u -> picked = if (u in picked) picked - u else picked + u }
        }
        return
    }
    if (DisappearingEditor(api, cid, isGroup = true, current = timer, open = timerOpen, onClose = { timerOpen = false })) return
    if (GroupPages(cid, me, pages, onOpenPerson, onAddMembers = { adding = true }, onHandedOver = {
            // No Undo after a hand-over (iOS): the transfer is done, so the leave runs now.
            com.fariin.app.data.LeaveUndo.start(cid, conv["title"] as? String ?: "the group") { api.leaveGroup(cid) }
            com.fariin.app.data.LeaveUndo.commit()
            onLeft()
        })) return
    val shared = rememberSharedMedia(cid, me)
    var allMedia by remember { mutableStateOf(false) }
    if (allMedia) { SharedMediaScreen(cid, shared, onBack = { allMedia = false }); return }
    card?.let { u -> MemberCard(cid, me, u, conv, onClose = { card = null }, onProfile = onOpenPerson, open = { pages.add(it) }) }
    if (editing && canEdit) {
        EditGroupScreen(api, cid, conv["title"] as? String ?: "Group", (conv["avatarUrl"] as? String)?.ifEmpty { null },
            onClose = { editing = false; editAtDescription = false }, description = desc, focusDescription = editAtDescription)
        return
    }
    Column(Modifier.fillMaxSize().background(c.bgSecondary)) {
        // iOS: "Edit" top right for whoever may change the group's info (owner 2026-10-19).
        PageBar("Group Info", onBack = onBack, action = if (canEdit) ({ TextAction("Edit", enabled = true) { editing = true } }) else null)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Avatar(conv["avatarUrl"] as? String, 100.dp,
                modifier = if (canEdit) Modifier.clickable { editing = true } else Modifier)
            Spacer(Modifier.height(10.dp))
            Text(conv["title"] as? String ?: "Group", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = c.label, textAlign = TextAlign.Center)
            Text("Group · ${com.fariin.app.data.GroupRules.memberCount(users.size)}", fontSize = 15.sp, color = c.secondary)
            // No description yet: whoever may edit is offered one, in grey; it opens Edit Group (iOS).
            if (desc.isEmpty() && canEdit) Text("Add group description...", fontSize = 17.sp, color = c.secondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(8.dp))
                    .clickable { editAtDescription = true; editing = true }.padding(horizontal = 8.dp, vertical = 4.dp))
            Spacer(Modifier.height(20.dp))
            if (desc.isNotEmpty()) SettingsSection { DescriptionCard(desc) }
            if (iAmMember) SettingsSection {
                // The owner's and admins' settings (iOS Group Settings); members do not see them.
                GroupManageRows(conv, me, cid) { pages.add(it) }
                // Who may change it: the owner and admins with Change info (iOS GroupSettingsView).
                DisappearingRow(timer, short = false, last = true) {
                    if (com.fariin.app.data.Disappearing.canChangeInGroup(conv, me)) timerOpen = true
                    else notice = com.fariin.app.data.Disappearing.refusal(conv, me)
                }
            }
            // iOS: All Media between the settings and the members, for a member, when there is some.
            if (iAmMember) SharedMediaCard(cid, shared) { allMedia = true }
            // iOS membersSection: "N Members" opens the whole list; six shown, past six the first five and See All.
            val g = com.fariin.app.data.GroupMembers.Group.from(conv)
            val (shownMembers, more) = com.fariin.app.data.GroupMembers.infoShown(com.fariin.app.data.GroupMembers.infoOrder(g, me))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 8.dp).clip(RoundedCornerShape(12.dp))
                .clickable { pages.add(GroupPage.Members) }.padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("${users.size} Member${if (users.size == 1) "" else "s"}", style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                    color = c.blue, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.Search, "Search members", tint = c.blue, modifier = Modifier.size(20.dp))
            }
            SettingsSection {
                if (iAmAdmin && team.may(me, com.fariin.app.data.GroupAdmins.Right.INVITE_USERS)) SettingsRow("Add Members", titleColor = c.blue, trailing = Trailing.NONE) { adding = true }
                shownMembers.forEach { u ->
                    PersonRow(photos[u]?.ifEmpty { null }, if (u == me) "You" else names[u] ?: "Member", "",
                        // An admin opens the member card (Make Group Admin, Ban from Group...); others the profile.
                        onClick = if (iAmAdmin) ({ card = u }) else if (u == me) null else ({ onOpenPerson(u) })) {
                        val tag = when { u == owner -> "Owner"; u in admins -> "Admin"; else -> "" }
                        if (tag.isNotEmpty()) Text(tag, fontSize = 14.sp, color = c.secondary)
                    }
                }
                if (more) SettingsRow("See All", icon = rememberVectorPainter(Icons.Filled.KeyboardArrowDown), trailing = Trailing.NONE,
                    last = true) { pages.add(GroupPage.Members) }
            }
            // A group I am no longer in: no Leave (iOS: no Leave, Report, End or Delete for it).
            if (iAmMember) SettingsSection {
                SettingsRow("Leave Group", titleColor = c.red, trailing = Trailing.NONE, last = true) { askLeave = true }
            }
            Spacer(Modifier.height(30.dp))
        }
    }
}

/**
 * Edit Group (iOS EditGroupView): the photo with a small minus badge to remove it (after a yes) and
 * the "Set New Photo" pill under it, the name in its card, Save at the top end. Save renames first,
 * then sets or removes the photo; a refusal is said and the page stays. The system back gesture
 * and the up arrow close it without saving.
 */
@Composable
internal fun EditGroupScreen(api: PeopleApi, cid: String, title: String, photoUrl: String?, onClose: () -> Unit,
                             description: String = "", focusDescription: Boolean = false, channel: Boolean = false) {
    // A channel's words and its squircle photo (iOS EditGroupView with ChannelWords.forChat).
    fun w(s: String) = if (channel) com.fariin.app.channel.ChannelRules.words(s) else s
    val shape = if (channel) RoundedCornerShape(36.dp) else CircleShape
    val c = Fariin.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(title) }
    var newPhoto by remember { mutableStateOf<ByteArray?>(null) }
    var removePhoto by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // What a Save already wrote, so a retry after a failed photo step does not rename twice (iOS).
    var savedName by remember { mutableStateOf<String?>(null) }
    var desc by remember { mutableStateOf(GroupRules.oneParagraph(description)) }
    var savedDesc by remember { mutableStateOf<String?>(null) }
    val trimmed = GroupRules.cleanTitle(name)
    val trimmedDesc = GroupRules.cleanDesc(desc)
    val changed = newPhoto != null || removePhoto || trimmed != title || trimmedDesc != description.trim()
    val hasPhoto = newPhoto != null || (!removePhoto && !photoUrl.isNullOrEmpty())
    BackHandler(onBack = onClose)
    // The picked photo goes to the circle cropper (iOS: the same move-and-scale a profile photo uses).
    val picker = rememberCirclePhotoPicker { jpeg -> newPhoto = jpeg; removePhoto = false }
    val pick = picker.launch
    if (picker.pending != null) { picker.Host(w("Group photo"), w("Shown in chats and on the group page")); return }
    val descFocus = remember { FocusRequester() }
    LaunchedEffect(focusDescription) { if (focusDescription) { delay(350); runCatching { descFocus.requestFocus() } } }
    if (confirmRemove) AlertDialog(onDismissRequest = { confirmRemove = false }, title = { Text(w("Remove group photo?")) },
        confirmButton = { TextButton({ confirmRemove = false; newPhoto = null; removePhoto = true }) { Text("Remove", color = c.red) } },
        dismissButton = { TextButton({ confirmRemove = false }) { Text("Cancel", color = c.blue) } }, containerColor = c.card)
    Column(Modifier.fillMaxSize().background(c.bgSecondary)) {
        PageBar(if (channel) "Edit Channel" else "Edit Group", onBack = onClose, action = {
            if (saving) CircularProgressIndicator(color = c.blue, strokeWidth = 2.dp, modifier = Modifier.padding(horizontal = 12.dp).size(22.dp))
            else TextAction("Save", enabled = trimmed.isNotEmpty() && changed) {
                saving = true; error = null
                scope.launch {
                    try {
                        if (trimmed != (savedName ?: title)) { api.renameGroup(cid, trimmed); savedName = trimmed }
                        if (trimmedDesc != (savedDesc ?: description.trim())) { api.setGroupDescription(cid, trimmedDesc); savedDesc = trimmedDesc }
                        val p = newPhoto
                        if (p != null) { api.setGroupPhoto(cid, p); forgetImage(ctx, photoUrl) }
                        else if (removePhoto && !photoUrl.isNullOrEmpty()) api.removeGroupPhoto(cid)
                        saving = false
                        onClose()
                    } catch (e: Exception) {
                        saving = false
                        error = e.message?.takeIf { it.isNotBlank() } ?: "Couldn't save. Try again."
                    }
                }
            }
        })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(8.dp))
            Box {
                Box(Modifier.size(120.dp).clip(shape).clickable(onClick = pick), contentAlignment = Alignment.Center) {
                    val p = newPhoto
                    val bmp = remember(p) { p?.let { com.fariin.app.media.BitmapDecode.fromBytes(it, 360)?.asImageBitmap() } }
                    when {
                        bmp != null -> androidx.compose.foundation.Image(bmp, w("Group photo"), contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        removePhoto -> Avatar(null, 120.dp, squircle = channel)
                        else -> Avatar(photoUrl, 120.dp, squircle = channel)
                    }
                }
                if (hasPhoto) Box(
                    Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-6).dp).size(30.dp).clip(CircleShape)
                        .background(c.card).clickable { confirmRemove = true },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Remove, "Remove Photo", tint = c.label, modifier = Modifier.size(18.dp)) }
            }
            Spacer(Modifier.height(12.dp))
            Text("Set New Photo", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.label,
                modifier = Modifier.clip(CircleShape).background(c.card).clickable(onClick = pick).padding(horizontal = 20.dp, vertical = 9.dp))
            Spacer(Modifier.height(8.dp))
            // Name and description in ONE card, a line between them (iOS EditGroupView).
            SettingsSection(footer = w("Optional. Shown on the group's info page.")) {
                Field(name, w("Group Name")) { name = it.take(100) }
                Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(0.5.dp).background(c.separator))
                DescriptionField(desc, descFocus) { desc = GroupRules.oneParagraph(it) }
            }
            error?.let { Text(it, fontSize = 15.sp, color = c.red, modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp)) }
        }
    }
}

/**
 * Group Info's Description card (iOS GroupAboutCard): the title with its icon, then the text,
 * left-aligned; past three lines it stops with a blue "more" that opens the rest in place.
 */
@Composable
private fun DescriptionCard(desc: String) {
    val c = Fariin.colors
    var expanded by remember(desc) { mutableStateOf(false) }
    var folds by remember(desc) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable(enabled = folds && !expanded) { expanded = true }
        .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.Notes, null, tint = c.label, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text("Description", fontSize = 17.sp, color = c.label)
        }
        Spacer(Modifier.height(8.dp))
        Text(desc, fontSize = 17.sp, color = c.label, maxLines = if (expanded) Int.MAX_VALUE else 3,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) folds = it.hasVisualOverflow })
        if (folds && !expanded) Text("more", fontSize = 17.sp, color = c.blue, modifier = Modifier.align(Alignment.End))
    }
}

/**
 * The Description field (iOS: the bio's rules): one paragraph, up to five lines, and "used/limit"
 * under it at the end, red at the limit.
 */
@Composable
private fun DescriptionField(value: String, focus: FocusRequester, onChange: (String) -> Unit) {
    val c = Fariin.colors
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp), horizontalAlignment = Alignment.End) {
        Box(Modifier.fillMaxWidth()) {
            if (value.isEmpty()) Text("Description", fontSize = 17.sp, color = c.secondary)
            BasicTextField(value, onChange, maxLines = 5, textStyle = TextStyle(fontSize = 17.sp, color = c.label),
                cursorBrush = SolidColor(c.blue), modifier = Modifier.fillMaxWidth().focusRequester(focus))
        }
        val left = GroupRules.DESC_MAX - value.length
        Text("${value.length}/${GroupRules.DESC_MAX}", fontSize = 13.sp, color = if (left <= 0) c.red else c.secondary,
            modifier = Modifier.padding(top = 6.dp))
    }
}

/** Drops a picture from the image caches, so a replaced group photo at the same link is fetched again. */
private fun forgetImage(ctx: android.content.Context, url: String?) {
    if (url.isNullOrEmpty()) return
    runCatching {
        val loader = coil3.SingletonImageLoader.get(ctx)
        loader.memoryCache?.let { m -> m.keys.filter { it.key == url }.forEach { m.remove(it) } }
        loader.diskCache?.remove(url)
    }
}
