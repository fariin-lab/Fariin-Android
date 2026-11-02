package com.fariin.app.ui.people

import android.net.Uri
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.data.Conversation
import com.fariin.app.data.GroupRules
import com.fariin.app.data.PeopleApi
import com.fariin.app.data.Person
import com.fariin.app.data.Usernames
import com.fariin.app.ui.components.Avatar
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

@Composable
private fun rememberPhotoPicker(onBytes: (ByteArray) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        uri?.let { u -> ctx.contentResolver.openInputStream(u)?.use { it.readBytes() }?.let(onBytes) }
    }
    return { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
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
    val pick = rememberPhotoPicker { bytes ->
        scope.launch {
            busy = true
            try { api.uploadPhoto(bytes); me = api.profile(api.me) } catch (_: Exception) { error = "Couldn't upload the photo. Try again." }
            busy = false
        }
    }
    if (page == "username") { UsernameScreen(api, me?.handle ?: "", onBack = { page = ""; scope.launch { me = api.profile(api.me) } }); return }
    error?.let { e -> AlertDialog(onDismissRequest = { error = null }, text = { Text(e) },
        confirmButton = { TextButton({ error = null }) { Text("OK", color = c.blue) } }, containerColor = c.card) }
    Column(Modifier.fillMaxSize().background(c.bg)) {
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
    Column(Modifier.fillMaxSize().background(c.bg)) {
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
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val pick = rememberPhotoPicker { photo = it }
    error?.let { e -> AlertDialog(onDismissRequest = { error = null }, text = { Text(e) },
        confirmButton = { TextButton({ error = null }) { Text("OK", color = c.blue) } }, containerColor = c.card) }
    Column(Modifier.fillMaxSize().background(c.bg)) {
        if (step == 1) {
            PageBar("Add Members", onBack = onBack, action = { TextAction("Next", picked.isNotEmpty()) { step = 2 } })
            Text("${picked.size} of 1024 selected", fontSize = 13.sp, color = c.secondary, modifier = Modifier.padding(horizontal = 16.dp))
            PickPeople(people, picked) { u -> picked = if (u in picked) picked - u else picked + u }
        } else {
            PageBar("New Group", onBack = { step = 1 }, action = {
                TextAction("Create", title.isNotBlank() && !busy) {
                    busy = true
                    scope.launch {
                        try { onCreated(api.createGroup(title, picked.toList(), photo)) } catch (_: Exception) { error = "Could not create the group. Try again." }
                        busy = false
                    }
                }
            })
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(64.dp).clip(CircleShape).background(c.fieldFill).clickable(onClick = pick), contentAlignment = Alignment.Center) {
                    val p = photo
                    if (p != null) {
                        val bmp = remember(p) { android.graphics.BitmapFactory.decodeByteArray(p, 0, p.size)?.asImageBitmap() }
                        if (bmp != null) androidx.compose.foundation.Image(bmp, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    } else Text("Photo", fontSize = 13.sp, color = c.blue)
                }
                Spacer(Modifier.width(12.dp))
                Box(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(c.card)) { Field(title, "Group Name") { title = it.take(100) } }
            }
            Header("${picked.size} members")
            people.filter { it.uid in picked }.forEach { p -> PersonRow(p.photoUrl, p.shownName, "", onClick = null) }
        }
    }
}

/**
 * Group Info (iOS GroupInfoView): photo, name, "Group · N members"; Add Members (admins), the
 * member list with Owner / Admin tags, the settings iOS has (coming soon here), Leave Group with
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
    val iAmAdmin = me in admins || me == owner
    var adding by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf(setOf<String>()) }
    var askLeave by remember { mutableStateOf(false) }
    var soon by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    notice?.let { n -> AlertDialog(onDismissRequest = { notice = null }, text = { Text(n) },
        confirmButton = { TextButton({ notice = null }) { Text("OK", color = c.blue) } }, containerColor = c.card) }
    soon?.let { s -> AlertDialog(onDismissRequest = { soon = null }, title = { Text(s) }, text = { Text("Coming soon on Android.") },
        confirmButton = { TextButton({ soon = null }) { Text("OK", color = c.blue) } }, containerColor = c.card) }
    if (askLeave) {
        val plan = GroupRules.leavePlan(me, "", conv)
        AlertDialog(onDismissRequest = { askLeave = false }, title = { Text("Leave this group?") },
            text = { if (owner == me) Text(GroupRules.ownerLeaveLine(plan.heir?.let { names[it] ?: "a member" })) else Text("You won't get its messages any more.") },
            confirmButton = { TextButton({
                askLeave = false
                scope.launch { try { api.leaveGroup(cid); onLeft() } catch (_: Exception) { notice = "Couldn't leave the group. Try again." } }
            }) { Text("Leave Group", color = c.red) } },
            dismissButton = { TextButton({ askLeave = false }) { Text("Cancel", color = c.blue) } }, containerColor = c.card)
    }
    if (adding) {
        val people = remember(chats) { contactsFrom(chats, me).filter { it.uid !in users } }
        Column(Modifier.fillMaxSize().background(c.bg)) {
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
    Column(Modifier.fillMaxSize().background(c.bg)) {
        PageBar("Group Info", onBack = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Avatar(conv["avatarUrl"] as? String, 100.dp)
            Spacer(Modifier.height(10.dp))
            Text(conv["title"] as? String ?: "Group", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = c.label, textAlign = TextAlign.Center)
            Text("Group · ${users.size} members", fontSize = 15.sp, color = c.secondary)
            Spacer(Modifier.height(20.dp))
            SettingsSection {
                for (s in listOf("Group Type", "Permissions", "Administrators", "Invite Links", "Disappearing Messages")) {
                    SettingsRow(s, last = s == "Disappearing Messages") { soon = s }
                }
            }
            SettingsSection(header = "${users.size} members") {
                if (iAmAdmin) SettingsRow("Add Members", titleColor = c.blue, trailing = Trailing.NONE) { adding = true }
                users.sortedWith(compareBy({ it != owner }, { it !in admins }, { names[it] ?: "" })).forEach { u ->
                    PersonRow(photos[u]?.ifEmpty { null }, if (u == me) "You" else names[u] ?: "Member", "",
                        onClick = if (u == me) null else ({ onOpenPerson(u) })) {
                        val tag = when { u == owner -> "Owner"; u in admins -> "Admin"; else -> "" }
                        if (tag.isNotEmpty()) Text(tag, fontSize = 14.sp, color = c.secondary)
                    }
                }
            }
            SettingsSection {
                SettingsRow("Leave Group", titleColor = c.red, trailing = Trailing.NONE, last = true) { askLeave = true }
            }
            Spacer(Modifier.height(30.dp))
        }
    }
}
