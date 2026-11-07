package com.fariin.app.ui.people

import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PhotoCamera
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.draw.alpha
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Report
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.filled.Add
import androidx.compose.foundation.layout.offset
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
import com.fariin.app.ui.components.floating
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
import kotlinx.coroutines.tasks.await
import androidx.compose.ui.res.painterResource
import kotlinx.coroutines.Dispatchers

@Composable
private fun Field(value: String, placeholder: String, onChange: (String) -> Unit) {
    val c = Fariin.colors
    Box(Modifier.fillMaxWidth().padding(16.dp)) {
        if (value.isEmpty()) Text(placeholder, fontSize = 17.sp, color = c.secondary)
        BasicTextField(value, onChange, singleLine = true, textStyle = TextStyle(fontSize = 17.sp, color = c.label),
            cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun TextAction(text: String, enabled: Boolean, busy: Boolean = false, onClick: () -> Unit) =
    com.fariin.app.ui.components.FloatingTextAction(text, enabled = enabled, busy = busy, onClick = onClick)

/**
 * Edit Profile (iOS EditProfileView): the photo with "Set New Photo", the name, and the
 * Username row that opens the username page. The photo goes to `profiles/{me}.jpg`.
 */
@Composable
fun EditProfileScreen(api: PeopleApi, onBack: () -> Unit) {
    val c = Fariin.colors
    val scope = rememberCoroutineScope()
    var me by remember { mutableStateOf<Person?>(null) }
    var first by remember { mutableStateOf("") }
    var last by remember { mutableStateOf("") }
    val name = "$first $last".trim()
    var about by remember { mutableStateOf("") }
    var askDiscard by remember { mutableStateOf(false) }
    var links by remember { mutableStateOf<List<com.fariin.app.data.ProfileLink>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val appCtx = androidx.compose.ui.platform.LocalContext.current.applicationContext
    var historyGen by remember { mutableStateOf(0) }
    val history = remember(historyGen) { com.fariin.app.data.ProfilePhotoHistory.files(appCtx, api.me).drop(1) }
    LaunchedEffect(Unit) {
        me = api.profile(api.me)
        val parts = (me?.name ?: "").split(" ", limit = 2)
        first = parts.getOrElse(0) { "" }; last = parts.getOrElse(1) { "" }
        about = me?.about ?: ""; links = me?.links.orEmpty()
    }
    // The picked photo goes to the circle cropper first (iOS ProfilePhotoCropper, its Avatar stage).
    val picker = rememberCirclePhotoPicker { jpeg ->
        scope.launch {
            busy = true
            try {
                api.uploadPhoto(jpeg); me = api.profile(api.me)
                com.fariin.app.data.ProfilePhotoHistory.record(appCtx, api.me, jpeg); historyGen++
            } catch (_: Exception) { error = "Couldn't upload the photo. Try again." }
            busy = false
        }
    }
    val pick = picker.launch
    // iOS ProfilePhotoSheet Recents: the photos I had before, tap one to use it again.
    fun useAgain(f: java.io.File) {
        if (busy) return
        scope.launch {
            busy = true
            val bytes = runCatching { f.readBytes() }.getOrNull()
            try { if (bytes != null) { api.uploadPhoto(bytes); me = api.profile(api.me); f.setLastModified(System.currentTimeMillis())
                com.fariin.app.data.ProfilePhotoHistory.record(appCtx, api.me, bytes); f.delete(); historyGen++ } }
            catch (_: Exception) { error = "Couldn't upload the photo. Try again." }
            busy = false
        }
    }
    if (picker.pending != null) { picker.Host("Avatar", "Shown in chats, stories and groups"); return }
    if (page == "links") { ProfileLinksPage(api.me, links, onChanged = { links = it }, onBack = { page = "" }); return }
    if (page == "username") { UsernameScreen(api, me?.handle ?: "", onBack = { page = ""; scope.launch { me = api.profile(api.me) } }); return }
    error?.let { e -> AlertDialog(onDismissRequest = { error = null }, text = { Text(e) },
        confirmButton = { TextButton({ error = null }) { Text("OK", color = c.accent) } }, containerColor = c.card) }
    Column(Modifier.fillMaxSize().background(c.bgSecondary)) {
        val changed = me != null && (name != (me?.name ?: "").trim() || about.trim() != (me?.about ?: "").trim())
        val close = { if (changed) askDiscard = true else onBack() }
        androidx.activity.compose.BackHandler(onBack = close)
        if (askDiscard) AlertDialog(onDismissRequest = { askDiscard = false }, containerColor = c.card,
            title = { Text("Discard changes?") }, text = { Text("Your changes are not saved yet.") },
            dismissButton = { TextButton({ askDiscard = false }) { Text("Keep Editing", color = c.accent) } },
            confirmButton = { TextButton({ askDiscard = false; onBack() }) { Text("Discard Changes", color = c.red) } })
        Box(Modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(horizontal = 12.dp)) {
            Box(Modifier.align(Alignment.CenterStart).size(44.dp).floating(CircleShape).clickable(onClickLabel = "Close") { close() },
                contentAlignment = Alignment.Center) { Icon(androidx.compose.material.icons.Icons.Filled.Close, null, tint = c.label) }
            Text("Edit Profile", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = c.label, modifier = Modifier.align(Alignment.Center))
            Box(Modifier.align(Alignment.CenterEnd)) {
            TextAction("Save", enabled = changed && name.isNotBlank() && !busy, busy = saving) {
                scope.launch {
                    busy = true; saving = true
                    try {
                        if (name.trim() != me?.name) api.saveName(name)
                        // Without a loaded profile the bio is only empty, not cleared by anyone (iOS).
                        if (me != null && about.trim() != me?.about?.trim()) com.fariin.app.data.ProfileWrites.saveAbout(api.me, about)
                        onBack()
                    } catch (_: Exception) { error = "Couldn't save. Try again." }
                    busy = false; saving = false
                }
            }
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            var photoMenu by remember { mutableStateOf(false) }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.clip(CircleShape).clickable { photoMenu = true }, contentAlignment = Alignment.Center) {
                Avatar(me?.photoUrl, 120.dp)
                // The photo is going up: the ring over the photo, white on a dimmed circle (iOS).
                if (busy && !saving) Box(Modifier.size(120.dp).background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                    com.fariin.app.ui.components.FariinSpinner(size = 30.dp, stroke = 3.dp, color = androidx.compose.ui.graphics.Color.White)
                }
            }
            // iOS ProfilePhotoSheet: Camera, Photo Library, and Remove while there is a photo.
            var askRemove by remember { mutableStateOf(false) }
            Spacer(Modifier.height(12.dp))
            Box {
                Text("Edit Photo", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.label,
                    modifier = Modifier.clip(CircleShape).background(c.card).clickable { photoMenu = true }.padding(horizontal = 20.dp, vertical = 8.dp))
                androidx.compose.material3.DropdownMenu(expanded = photoMenu, onDismissRequest = { photoMenu = false }) {
                    androidx.compose.material3.DropdownMenuItem(text = { Text("Take Photo") },
                        leadingIcon = { Icon(ImageVector.vectorResource(com.fariin.app.R.drawable.ic_camera), null) }, onClick = { photoMenu = false; picker.takePhoto() })
                    androidx.compose.material3.DropdownMenuItem(text = { Text("Choose Photo") },
                        leadingIcon = { Icon(ImageVector.vectorResource(com.fariin.app.R.drawable.ic_album), null) }, onClick = { photoMenu = false; pick() })
                    if (!me?.photoUrl.isNullOrEmpty()) androidx.compose.material3.DropdownMenuItem(text = { Text("Remove Photo", color = c.red) },
                        leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = c.red) }, onClick = { photoMenu = false; askRemove = true })
                }
            }
            if (askRemove) AlertDialog(onDismissRequest = { askRemove = false }, containerColor = c.card,
                title = { Text("Remove your photo?") },
                text = { Text("People will see your initial instead.") },
                dismissButton = { TextButton({ askRemove = false }) { Text("Cancel", color = c.accent) } },
                confirmButton = { TextButton({
                    askRemove = false
                    scope.launch {
                        busy = true
                        try { api.removePhoto(); me = api.profile(api.me) } catch (_: Exception) { error = "Couldn't remove the photo. Try again." }
                        busy = false
                    }
                }) { Text("Remove", color = c.red) } })
            if (history.isNotEmpty()) {
                Text("Recents", fontSize = 13.sp, color = c.secondary, modifier = Modifier.fillMaxWidth().padding(start = 20.dp, top = 4.dp, bottom = 6.dp))
                androidx.compose.foundation.lazy.LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(history.size) { i ->
                        val f = history[i]
                        val bmp = com.fariin.app.ui.thread.rememberBitmap(f, 160)
                        Box(Modifier.size(64.dp).clip(CircleShape).background(c.card).clickable { useAgain(f) }) {
                            if (bmp != null) androidx.compose.foundation.Image(bmp, "Use this photo again",
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Spacer(Modifier.height(8.dp))
            SettingsSection {
                Field(first, "First name") { first = it.take(40) }
                Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(0.5.dp).background(c.separator))
                Field(last, "Last name") { last = it.take(40) }
            }
            // iOS Bio: one paragraph, 140 characters, the count shown for the last 20.
            val left = com.fariin.app.data.ProfileWrites.BIO_CHARS - about.length
            SettingsSection(header = "Bio") {
                Row(verticalAlignment = Alignment.Bottom) {
                    Box(Modifier.weight(1f).padding(16.dp)) {
                        if (about.isEmpty()) Text("A few words about you", fontSize = 17.sp, color = c.secondary)
                        BasicTextField(about, { about = com.fariin.app.data.ProfileWrites.oneParagraph(it) },
                            textStyle = TextStyle(fontSize = 17.sp, color = c.label), cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth())
                    }
                    if (left <= 20) Text("$left", fontSize = 13.sp, color = if (left == 0) c.red else c.secondary,
                        modifier = Modifier.padding(end = 16.dp, bottom = 16.dp))
                }
            }
            val linksOn = com.fariin.app.data.AppFeatures.value("profileLinks", false)
            SettingsSection {
                SettingsRow("Username", value = me?.handle?.takeIf { it.isNotEmpty() }?.let { "@$it" } ?: "None", last = !linksOn) { page = "username" }
                if (linksOn) SettingsRow("Links", value = if (links.isEmpty()) "Add" else "${links.size}", last = true) { page = "links" }
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
            TextAction("Done", enabled = !claiming && (Usernames.isValid(draft) && status !is NameStatus.Taken && status !is NameStatus.Problem), busy = claiming) {
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
                NameStatus.Checking -> com.fariin.app.ui.components.LoadingLine("Checking username…")
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
    // The people sit in one rounded card on the grey page, as a settings section does: the first
    // row takes the top corners, the last the bottom ones.
    val shown = people.filter { q.isBlank() || it.shownName.contains(q, true) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp)) {
        itemsIndexed(shown, key = { _, p -> p.uid }) { i, p ->
            val top = if (i == 0) 20.dp else 0.dp
            val bottom = if (i == shown.lastIndex) 20.dp else 0.dp
            Box(Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(top, top, bottom, bottom)).background(c.card)) {
                PersonRow(p.photoUrl, p.shownName, "", onClick = { onToggle(p.uid) }) {
                    Icon(if (p.uid in picked) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, null,
                        tint = if (p.uid in picked) c.blue else c.tertiary)
                }
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
    val gsCtx = androidx.compose.ui.platform.LocalContext.current
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
        confirmButton = { TextButton({ error = null }) { Text("OK", color = c.accent) } }, containerColor = c.card) }
    // The bar floats: back in its circle, Next / Create in a capsule (iOS NewGroupView's glass bar buttons).
    val bar: @Composable (String, () -> Unit, @Composable () -> Unit) -> Unit = { t, back, action ->
        com.fariin.app.ui.components.FloatingTopBar(t, onBack = back, container = c.bgSecondary) {
            Box(Modifier.floating(CircleShape)) { action() }
        }
    }
    Column(Modifier.fillMaxSize().background(c.bgSecondary).statusBarsPadding()) {
        if (step == 1) {
            bar("Add Members", onBack) { TextAction("Next", picked.isNotEmpty()) { step = 2 } }
            Text("${picked.size} of 1024 selected", fontSize = 13.sp, color = c.secondary, modifier = Modifier.padding(horizontal = 16.dp))
            PickPeople(people, picked) { u -> picked = if (u in picked) picked - u else picked + u }
        } else {
            bar("New Group", { step = 1 }) {
                TextAction("Create", title.isNotBlank() && !busy) {
                    busy = true
                    scope.launch {
                        try {
                            val cid = api.createGroup(title, picked.toList(), photo)
                            // After "created the group", as iOS: the timer and its notice for everyone.
                            if (timer > 0) api.setDisappear(cid, timer)
                            // "New group" on the new-account card is done.
                            com.fariin.app.ui.chats.GetStartedStore.markDone(gsCtx, com.fariin.app.ui.chats.GetStartedCard.NEW_GROUP)
                            onCreated(cid)
                        } catch (_: Exception) { error = "Could not create the group. Try again." }
                        busy = false
                    }
                }
            }
            // iOS NewGroupView: the photo and the name side by side in one rounded card.
            SettingsSection {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    // iOS: a tap offers Take Photo / Choose Photo (and Remove once one is set).
                    var slotMenu by remember { mutableStateOf(false) }
                    androidx.compose.material3.DropdownMenu(expanded = slotMenu, onDismissRequest = { slotMenu = false }) {
                        androidx.compose.material3.DropdownMenuItem(text = { Text("Take Photo") },
                            leadingIcon = { Icon(ImageVector.vectorResource(com.fariin.app.R.drawable.ic_camera), null) }, onClick = { slotMenu = false; picker.takePhoto() })
                        androidx.compose.material3.DropdownMenuItem(text = { Text("Choose Photo") },
                            leadingIcon = { Icon(ImageVector.vectorResource(com.fariin.app.R.drawable.ic_album), null) }, onClick = { slotMenu = false; pick() })
                        if (photo != null) androidx.compose.material3.DropdownMenuItem(text = { Text("Remove Photo", color = c.red) },
                            leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = c.red) }, onClick = { slotMenu = false; photo = null })
                    }
                    Box(Modifier.size(64.dp).clip(CircleShape).background(c.accent.copy(alpha = 0.12f)).clickable(onClickLabel = "Set Group Photo") { slotMenu = true }, contentAlignment = Alignment.Center) {
                        val p = photo
                        if (p != null) {
                            val bmp = remember(p) { android.graphics.BitmapFactory.decodeByteArray(p, 0, p.size)?.asImageBitmap() }
                            if (bmp != null) androidx.compose.foundation.Image(bmp, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        } else Icon(painterResource(com.fariin.app.R.drawable.ic_group_camera), contentDescription = "Set Group Photo",
                            tint = c.accent, modifier = Modifier.size(28.dp))   // iOS: the owner's camera drawing (2026-10-18)
                    }
                    Spacer(Modifier.width(4.dp))
                    Box(Modifier.weight(1f)) { Field(title, "Group Name") { title = it.take(100) } }
                }
            }
            NewGroupTimerSection(timer) { timer = it }
            val members = people.filter { it.uid in picked }
            // No "Members · N" heading on this page (iOS, owner 2026-10-20): it counted me but listed
            // only the others, so two over one row read as a mistake.
            SettingsSection {
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
                    onLeft: () -> Unit, onOpenPerson: (String) -> Unit, start: String? = null) {
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
    // Saveable, as the page stack below: a trip to a member's profile and back keeps where I was.
    var editing by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(start == "edit") }
    var editAtDescription by remember { mutableStateOf(false) }
    val desc = GroupRules.description(conv)
    var adding by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(start == "add") }
    var picked by remember { mutableStateOf(setOf<String>()) }
    var askLeave by remember { mutableStateOf(false) }
    var askReport by remember { mutableStateOf(false) }
    var askEnd by remember { mutableStateOf(false) }
    var askDelete by remember { mutableStateOf(false) }
    val exitScope = rememberCoroutineScope()
    fun callable(name: String, done: String) {
        exitScope.launch {
            val ok = runCatching {
                com.google.firebase.functions.FirebaseFunctions.getInstance(com.fariin.app.data.AuthRepository.FUNCTIONS_REGION)
                    .getHttpsCallable(name).call(mapOf("cid" to cid)).await()
            }.isSuccess
            com.fariin.app.ui.components.Snack.show(if (ok) done else "Could not do that. Check your connection and try again.")
        }
    }
    if (askReport) AlertDialog(onDismissRequest = { askReport = false }, title = { Text("Report Spam?") },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton({
                    askReport = false
                    // iOS: report, then leave and block the group so it cannot add me back.
                    exitScope.launch { api.report("", cid, "group") }
                    com.fariin.app.data.LeaveUndo.start(cid, conv["title"] as? String ?: "the group") {
                        api.leaveGroup(cid)
                        com.google.firebase.firestore.FirebaseFirestore.getInstance().collection("users").document(me)
                            .collection("blockedGroups").document(cid).set(mapOf("at" to com.google.firebase.firestore.FieldValue.serverTimestamp())).await()
                    }
                    onLeft()
                }) { Text("Report and Leave", color = c.red) }
                TextButton({ askReport = false; exitScope.launch { api.report("", cid, "group") }
                    com.fariin.app.ui.components.Snack.show("Thanks. Fariin's moderators will review this group.") }) { Text("Report", color = c.accent) }
                TextButton({ askReport = false }) { Text("Cancel", color = c.accent) }
            }
        })
    if (askEnd) AlertDialog(onDismissRequest = { askEnd = false }, title = { Text("End This Group?") },
        text = { Text("Nobody will be able to send messages or start calls, and invite links stop working. Messages stay. This can't be undone.") },
        confirmButton = { TextButton({ askEnd = false; callable("endGroup", "The group has ended.") }) { Text("End Group", color = c.red) } },
        dismissButton = { TextButton({ askEnd = false }) { Text("Cancel", color = c.accent) } })
    if (askDelete) AlertDialog(onDismissRequest = { askDelete = false }, title = { Text("Delete Group?") },
        text = { Text("This permanently deletes the group and all its messages for everyone. This cannot be undone.") },
        confirmButton = { TextButton({
            askDelete = false
            // iOS LeaveGroupUndo .deleteGroup: gone from the list at once, Undo for a few seconds, then the server deletes it.
            com.fariin.app.data.LeaveUndo.start(cid, conv["title"] as? String ?: "the group") {
                com.google.firebase.functions.FirebaseFunctions.getInstance(com.fariin.app.data.AuthRepository.FUNCTIONS_REGION)
                    .getHttpsCallable("deleteGroup").call(mapOf("cid" to cid)).await()
            }
            com.fariin.app.ui.components.Snack.show("Group deleted.", "Undo", durationMs = com.fariin.app.data.LeaveUndo.WINDOW_MS) {
                com.fariin.app.data.LeaveUndo.undo()
            }
            onLeft()
        }) { Text("Delete for Everyone", color = c.red) } },
        dismissButton = { TextButton({ askDelete = false }) { Text("Cancel", color = c.accent) } })
    var notice by remember { mutableStateOf<String?>(null) }
    var timerOpen by remember { mutableStateOf(false) }
    val timer = (conv["disappearSeconds"] as? Number)?.toInt() ?: 0
    // The management pages pushed over this one (Administrators, Permissions, Group Type, Invite Links).
    val pages = androidx.compose.runtime.saveable.rememberSaveable(cid, saver = androidx.compose.runtime.saveable.Saver(
        save = { s: androidx.compose.runtime.snapshots.SnapshotStateList<GroupPage> -> ArrayList(GroupPage.save(s)) },
        restore = { w: ArrayList<String> -> androidx.compose.runtime.mutableStateListOf<GroupPage>().apply { addAll(GroupPage.restore(w)) } },
    )) { androidx.compose.runtime.mutableStateListOf<GroupPage>().apply { if (start == "link") add(GroupPage.Links) } }
    // Opened at one step from the chat's welcome card (iOS: a sheet over the chat): closing THAT
    // step goes back to the chat, not to this page. Only that step: once this page itself is on
    // screen, every page pushed from it backs out to it (GroupStepBack).
    var startOpen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(start != null) }
    val closing = startOpen && GroupStepBack.closesToChat(start, adding, editing, pages)
    LaunchedEffect(closing) { if (closing) onBack() }
    var card by remember { mutableStateOf<String?>(null) }
    val team = com.fariin.app.data.GroupAdmins.Team.from(conv)
    // A Fariin admin sees Statistics at any size (iOS AdminStore: the rules let me read my own admins doc).
    val appAdmin by androidx.compose.runtime.produceState(false, me) {
        value = me.isNotEmpty() && runCatching {
            com.google.firebase.firestore.FirebaseFirestore.getInstance().collection("admins").document(me).get().await().exists()
        }.getOrDefault(false)
    }
    val showStats = iAmMember && com.fariin.app.data.GroupStats.showRow(conv, me, appAdmin)
    // iOS: the Search circle goes back to the group's chat and opens its search; only from that chat.
    val fromChat = androidx.compose.runtime.saveable.rememberSaveable { com.fariin.app.ui.thread.InChatSearchLink.takeOpenedFrom(cid) }
    notice?.let { n -> AlertDialog(onDismissRequest = { notice = null }, text = { Text(n) },
        confirmButton = { TextButton({ notice = null }) { Text("OK", color = c.accent) } }, containerColor = c.card) }
    if (askLeave) {
        val plan = GroupRules.leavePlan(me, "", conv)
        val alreadyMuted = com.fariin.app.data.GroupInfoCircles.mutedUntil(conv, me) > System.currentTimeMillis()
        com.fariin.app.ui.components.CarefulSheet(onDismiss = { askLeave = false }, steps = listOf(com.fariin.app.ui.components.CarefulStep(
            title = "Leave this group?",
            body = if (owner == me) GroupRules.ownerLeaveLine(plan.heir?.let { names[it] ?: "a member" })
                else "You won't get messages from this group any more.",
            buttons = listOfNotNull(
                // The softer way: stay in the group, stop the alerts (Mute Always, the same mutedBy key the Mute circle writes).
                if (!alreadyMuted) com.fariin.app.ui.components.CarefulButton("Mute Instead", com.fariin.app.ui.components.CarefulKind.SOFT) {
                    exitScope.launch {
                        val ok = runCatching {
                            com.google.firebase.firestore.FirebaseFirestore.getInstance().collection("conversations").document(cid)
                                .set(mapOf("mutedBy" to mapOf(me to com.fariin.app.ui.chats.MuteFor.until(null))),
                                    com.google.firebase.firestore.SetOptions.merge()).await()
                        }.isSuccess
                        com.fariin.app.ui.components.Snack.show(if (ok) "Muted. You stay in the group." else com.fariin.app.data.GroupInfoCircles.MUTE_FAILED)
                    }
                } else null,
                // iOS: the owner may pick the next owner instead (Choose Another Owner).
                if (com.fariin.app.data.OwnerTransfer.offersChooseAnother(me, owner, users))
                    com.fariin.app.ui.components.CarefulButton(com.fariin.app.data.OwnerTransfer.CHOOSE_ANOTHER, com.fariin.app.ui.components.CarefulKind.SOFT) {
                        pages.add(GroupPage.ChooseOwner)
                    } else null,
                com.fariin.app.ui.components.CarefulButton("Leave", com.fariin.app.ui.components.CarefulKind.DESTRUCTIVE) {
                    // iOS LeaveGroupUndo: out of the list and the screen at once, Undo for 5 s, then the real leave.
                    com.fariin.app.data.LeaveUndo.start(cid, conv["title"] as? String ?: "the group") { api.leaveGroup(cid) }
                    com.fariin.app.ui.components.Snack.show("You left the group.", "Undo", durationMs = com.fariin.app.data.LeaveUndo.WINDOW_MS) {
                        com.fariin.app.data.LeaveUndo.undo()
                    }
                    onLeft()
                },
            ),
        )))
    }
    if (adding) {
        val people = remember(chats) { contactsFrom(chats, me).filter { it.uid !in users } }
        // System Back closes Add Members to the page it was opened from (Group Info or Members). It had
        // no handler of its own, so Back left the whole route and landed in the chat.
        BackHandler { adding = false }
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
    // Chat Bubble (SPEC 4): my one bubble style for every chat, the Settings picker itself.
    var bubbleOpen by remember { mutableStateOf(false) }
    if (bubbleOpen) { com.fariin.app.ui.settings.BubbleStylePage { bubbleOpen = false }; return }
    if (GroupPages(cid, me, pages, onOpenPerson, onAddMembers = { adding = true }, onTimer = {
            if (com.fariin.app.data.Disappearing.canChangeInGroup(conv, me)) timerOpen = true
            else notice = com.fariin.app.data.Disappearing.refusal(conv, me)
        }, onHandedOver = {
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
    if (startOpen && start == "edit" && editing) {
        // The welcome card's Edit before the group's doc has said I may edit: nothing yet (drawing this
        // page here is what let a later page back out to the chat). Read and refused: back to the chat.
        if (conv.isNotEmpty()) LaunchedEffect(Unit) { editing = false }
        Box(Modifier.fillMaxSize().background(c.bgSecondary))
        return
    }
    // The step from the welcome card has closed and the route is leaving: not this page for a frame.
    if (closing) { Box(Modifier.fillMaxSize().background(c.bgSecondary)); return }
    // On Group Info itself: from here on, Back from any page returns here.
    if (startOpen) androidx.compose.runtime.SideEffect { startOpen = false }
    // iOS loadShareCode: the server's primary link code; null until it answers (or fails) keeps the doc's old code.
    val serverCode by androidx.compose.runtime.produceState<String?>(null, cid, iAmMember) {
        if (iAmMember) value = runCatching { com.fariin.app.data.GroupManage.shareCode(cid) }.getOrNull()
    }
    val inviteLink = if (iAmMember) com.fariin.app.data.GroupShareLink.of(me, conv, serverCode) else null
    var showQr by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    // iOS GroupInfoView exportChat: this group's messages as a text file (ChatExportRun). Not for a channel.
    var exportJob by remember { mutableStateOf<ChatExportRun.Job?>(null) }
    exportJob?.let { j -> ChatExportDialog(j) { exportJob = null } }
    if (showQr && inviteLink != null) com.fariin.app.ui.settings.GroupQrSheet(conv["title"] as? String ?: "Group", inviteLink,
        (conv["avatarUrl"] as? String)?.ifEmpty { null }, users.size, onClose = { showQr = false })
    // The page runs under its bar, so back, Edit and the menu are glass over it (GlassBarPage).
    val pageScroll = rememberScrollState()
    val underBar by remember { androidx.compose.runtime.derivedStateOf { pageScroll.value > 0 } }
    com.fariin.app.ui.components.GlassBarPage(underBar, Modifier.background(c.bgSecondary), bar = {
        // iOS: "Edit" top right for whoever may change the group's info (owner 2026-10-19), and the
        // ••• menu (moreMenuItems) as the Android three-dot overflow: Share Group and QR Code while the
        // group has a link I may share, and Change Wallpaper (the group's, for everyone) for whoever
        // may change its info: back to the chat, which opens its picker. Save to Photos waits for auto-save.
        PageBar("Group Info", onBack = onBack, action = ({
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (canEdit) TextAction("Edit", enabled = true) { editing = true }
                GroupInfoMenu(onWallpaper = if (canEdit) ({ com.fariin.app.ui.settings.ChatWallpaperLink.request(cid); onBack() }) else null,
                    onShare = if (inviteLink != null) ({
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(android.content.Intent.EXTRA_TEXT, inviteLink)
                    ctx.startActivity(android.content.Intent.createChooser(send, null))
                }) else null, onQr = { showQr = true }, saveCid = cid,
                    onExport = if (!ChatExportRun.SHOWN || conv["channel"] == true) null else ({
                        if (exportJob == null) exportJob = ChatExportRun.Job(cid, conv["title"] as? String ?: "Group")
                    }))
            }
        }))
    }) { top ->
        Column(Modifier.fillMaxSize().verticalScroll(pageScroll).padding(top = top), horizontalAlignment = Alignment.CenterHorizontally) {
            Avatar(conv["avatarUrl"] as? String, 100.dp, kind = com.fariin.app.ui.components.AvatarKind.of(true, conv["channel"] == true),
                modifier = if (canEdit) Modifier.clickable(onClickLabel = "Edit group") { editing = true }.semantics { contentDescription = "Group photo" } else Modifier)
            Spacer(Modifier.height(10.dp))
            Text(conv["title"] as? String ?: "Group", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = c.label, textAlign = TextAlign.Center)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                val faces = users.take(3)
                if (conv["hideMembers"] != true && faces.isNotEmpty()) {
                    Box(Modifier.size(width = (22 + 14 * (faces.size - 1)).dp, height = 24.dp)) {
                        faces.forEachIndexed { i, u ->
                            Box(Modifier.offset(x = (14 * i).dp).size(24.dp).clip(CircleShape).background(c.bgSecondary).padding(1.5.dp)) {
                                Avatar(photos[u]?.ifEmpty { null }, 21.dp)
                            }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Text("${users.size} Member${if (users.size == 1) "" else "s"}", fontSize = 17.sp, color = c.secondary)
            }
            // No description yet: whoever may edit is offered one, in grey; it opens Edit Group (iOS).
            if (desc.isEmpty() && canEdit) Text("Add group description...", fontSize = 17.sp, color = c.secondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(8.dp))
                    .clickable { editAtDescription = true; editing = true }.padding(horizontal = 8.dp, vertical = 4.dp))
            if (iAmMember) {
                Spacer(Modifier.height(16.dp))
                GroupCirclesRow(cid, me, conv, users.size, onSearch = if (fromChat) ({ com.fariin.app.ui.thread.InChatSearchLink.request(cid); onBack() }) else null,
                    onNotice = { notice = it })
            }
            Spacer(Modifier.height(20.dp))
            if (desc.isNotEmpty()) SettingsSection { DescriptionCard(desc) }
            if (iAmMember) SettingsSection {
                // iOS settingsCard (owner 2026-10-18/20): Storage Used, then for the owner and admins one
                // Group Settings row (the management lives on that page) and Join Requests while
                // someone waits; a member's Disappearing Messages row stays here, read-only.
                // Not in a channel (its posts are never styled) nor when the owner or an admin turned styles off.
                if (com.fariin.app.data.BubbleStyles.SHOWN && conv["channel"] != true && conv["bubbleStylesOff"] != true)
                    SettingsRow("Chat Bubble", icon = androidx.compose.ui.res.painterResource(com.fariin.app.R.drawable.ic_chat)) { bubbleOpen = true }
                SettingsRow("Storage Used", icon = painterResource(com.fariin.app.R.drawable.ic_set_storage), last = !iAmAdmin && timer <= 0 && !showStats) {
                    pages.add(GroupPage.Storage(conv["title"] as? String ?: "Group"))
                }
                if (!iAmAdmin && timer > 0) DisappearingRow(timer, short = false, last = !showStats) {
                    notice = com.fariin.app.data.Disappearing.refusal(conv, me)
                }
                if (iAmAdmin) {
                    val waiting = rememberJoinRequests(cid, allowed = team.may(me, com.fariin.app.data.GroupAdmins.Right.INVITE_USERS) && !team.closed)
                    SettingsRow("Group Settings", icon = painterResource(com.fariin.app.R.drawable.ic_group_settings),
                        last = !showStats && waiting.isEmpty()) { pages.add(GroupPage.Settings) }
                    if (waiting.isNotEmpty()) SettingsRow("Join Requests", icon = painterResource(com.fariin.app.R.drawable.ic_gs_join_requests),
                        value = com.fariin.app.data.JoinRequests.rowValue(waiting.size), last = !showStats) { pages.add(GroupPage.JoinRequests) }
                }
                // Statistics (iOS showStatsRow): owner and admins of a big enough group, last row.
                if (showStats) SettingsRow("Statistics", icon = painterResource(com.fariin.app.R.drawable.ic_statistics), last = true) {
                    pages.add(GroupPage.Stats(conv["title"] as? String ?: "Group"))
                }
            }
            // iOS: All Media between the settings and the members, for a member, when there is some.
            if (iAmMember) SharedMediaCard(cid, shared) { allMedia = true }
            // iOS hideMembers (set by Fariin staff only): no list, faces or labels, only the count.
            val membersHidden = conv["hideMembers"] == true
            if (membersHidden) Text("${users.size} Member${if (users.size == 1) "" else "s"}",
                style = androidx.compose.material3.MaterialTheme.typography.titleSmall, color = c.secondary,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 14.dp))
            // iOS membersSection: "N Members" opens the whole list; six shown, past six the first five and See All.
            if (!membersHidden) {
            val g = com.fariin.app.data.GroupMembers.Group.from(conv)
            val (shownMembers, more) = com.fariin.app.data.GroupMembers.infoShown(com.fariin.app.data.GroupMembers.infoOrder(g, me))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 8.dp).clip(RoundedCornerShape(12.dp))
                .clickable { pages.add(GroupPage.Members) }.padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("${users.size} Member${if (users.size == 1) "" else "s"}", fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                    color = c.secondary)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "All members", tint = c.secondary, modifier = Modifier.size(26.dp))
                Spacer(Modifier.weight(1f))
            }
            SettingsSection {
                if (iAmAdmin && team.may(me, com.fariin.app.data.GroupAdmins.Right.INVITE_USERS)) Row(Modifier.fillMaxWidth().clickable { adding = true }
                    .padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(c.fieldFill), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Add, null, tint = c.label, modifier = Modifier.size(24.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Text("Add Members", fontSize = 17.sp, color = c.label)
                }
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
            }
            // A group I am no longer in: no Leave (iOS: no Leave, Report, End or Delete for it).
            if (iAmMember) SettingsSection {
                // iOS exit card: Leave Group, and Report Spam for a member who is not staff (unless the
                // group turned reports off); the owner's End Group and Delete Group in their own card.
                val canReport = !iAmAdmin && conv["groupNoReport"] != true
                SettingsRow("Leave Group", icon = rememberVectorPainter(Icons.AutoMirrored.Outlined.Logout), titleColor = c.red, trailing = Trailing.NONE, last = !canReport) { askLeave = true }
                if (canReport) SettingsRow("Report Spam", icon = rememberVectorPainter(Icons.Outlined.Report), titleColor = c.red, trailing = Trailing.NONE, last = true) { askReport = true }
            }
            if (iAmMember && owner == me) SettingsSection {
                val ended = (conv["mod"] as? Map<*, *>)?.get("state") == "ended"
                if (!ended) SettingsRow("End Group", icon = rememberVectorPainter(Icons.Outlined.Cancel), titleColor = c.red, trailing = Trailing.NONE) { askEnd = true }
                SettingsRow("Delete Group", icon = rememberVectorPainter(Icons.Outlined.Delete), titleColor = c.red, trailing = Trailing.NONE, last = true) { askDelete = true }
            }
            Spacer(Modifier.height(30.dp))
        }
    }
}

/**
 * The circles under the group's name (iOS GroupInfoView actionsRow): Voice, Video, Mute, Search.
 * Calls join the group's running call or start one (CallActivity.startGroup); a circle I may not
 * use stays, greyed, and its tap says why. Mute opens the iOS times menu (or Unmute under the
 * end time). Search works only when the page was opened from the group's chat (it goes back
 * there and opens the chat's search). iOS's fifth circle, More, is the bar's three-dot menu here.
 */
@Composable
private fun GroupCirclesRow(cid: String, me: String, conv: Map<String, Any?>, memberCount: Int, onSearch: (() -> Unit)?,
                            onNotice: (String) -> Unit) {
    val c = Fariin.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val circles = com.fariin.app.data.GroupInfoCircles
    var live by remember(cid) { mutableStateOf(false) }
    val signedIn = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser != null
    if (signedIn) androidx.compose.runtime.DisposableEffect(cid) {
        val reg = com.fariin.app.call.GroupCall.watchActive(cid) { live = it }
        onDispose { reg.remove() }
    }
    val groupCall by com.fariin.app.call.GroupCall.ui.collectAsState()
    val oneCall by com.fariin.app.call.CallManager.ui.collectAsState()
    val ended = (conv["mod"] as? Map<*, *>)?.get("state") == "ended"
    val mayStart = com.fariin.app.call.GroupCallWire.mayStart(conv, me)
    val ready = circles.callReady(groupCall.cid, oneCall.status.live, cid, ended, mayStart, live)
    val title = conv["title"] as? String ?: "Group"
    var solo by remember { mutableStateOf(false) }
    if (solo) AlertDialog(onDismissRequest = { solo = false }, title = { Text(circles.SOLO_TITLE) }, text = { Text(circles.SOLO_TEXT) },
        confirmButton = { TextButton({ solo = false }) { Text("OK", color = c.accent) } }, containerColor = c.card)
    fun call(video: Boolean) {
        when (val t = circles.callTap(groupCall.cid, oneCall.status.live, cid, ended, mayStart, live, memberCount)) {
            com.fariin.app.data.GroupInfoCircles.CallTap.Restore -> com.fariin.app.ui.call.CallActivity.startGroup(ctx, cid, title, video)
            com.fariin.app.data.GroupInfoCircles.CallTap.Solo -> solo = true
            is com.fariin.app.data.GroupInfoCircles.CallTap.Refuse -> com.fariin.app.ui.components.Snack.show(t.notice)
            is com.fariin.app.data.GroupInfoCircles.CallTap.Go -> com.fariin.app.ui.call.CallActivity.startGroup(ctx, cid, title, video, start = t.start)
        }
    }
    // The doc's value, or mine at once after a tap until the doc catches up.
    var heldUntil by remember(cid) { mutableStateOf<Double?>(null) }
    val until = heldUntil ?: circles.mutedUntil(conv, me)
    val now = System.currentTimeMillis()
    val muted = until > now
    var muteOpen by remember { mutableStateOf(false) }
    fun setMuted(value: Double) {
        heldUntil = value
        scope.launch {
            val ok = runCatching {
                com.google.firebase.firestore.FirebaseFirestore.getInstance().collection("conversations").document(cid)
                    .set(mapOf("mutedBy" to mapOf(me to value)), com.google.firebase.firestore.SetOptions.merge()).await()
            }.isSuccess
            heldUntil = null
            if (!ok) onNotice(circles.MUTE_FAILED)
        }
    }
    val off = Modifier.alpha(0.45f)
    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        ActionTile("Voice", ImageVector.vectorResource(com.fariin.app.R.drawable.ic_call_voice), if (ready) Modifier else off) { call(video = false) }
        ActionTile("Video", ImageVector.vectorResource(com.fariin.app.R.drawable.ic_call_video), if (ready) Modifier else off) { call(video = true) }
        Box {
            ActionTile(if (muted) "Unmute" else "Mute", if (muted) ImageVector.vectorResource(com.fariin.app.R.drawable.ic_bell) else ImageVector.vectorResource(com.fariin.app.R.drawable.ic_bell_off)) { muteOpen = true }
            androidx.compose.material3.DropdownMenu(muteOpen, onDismissRequest = { muteOpen = false }, shape = com.fariin.app.ui.components.FariinMenu.shape,
                border = com.fariin.app.ui.components.FariinMenu.border, containerColor = c.card) {
                Text(if (muted) circles.mutedLabel(until, now, android.text.format.DateFormat.is24HourFormat(ctx)) else circles.MUTE_HEADER,
                    fontSize = 13.sp, color = c.secondary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                if (muted) androidx.compose.material3.DropdownMenuItem(text = { Text("Unmute", color = c.label) },
                    onClick = { muteOpen = false; setMuted(0.0) })
                else circles.MUTE_CHOICES.forEach { (label, hours) ->
                    androidx.compose.material3.DropdownMenuItem(text = { Text(label, color = c.label) },
                        onClick = { muteOpen = false; setMuted(circles.muteUntil(hours)) })
                }
            }
        }
        ActionTile("Search", Icons.Filled.Search, if (onSearch != null) Modifier else off) { onSearch?.invoke() }
    }
}

/** The group page's three-dot menu (iOS GroupInfoView moreMenuItems, menuGroupItems). */
@Composable
private fun GroupInfoMenu(onWallpaper: (() -> Unit)?, onShare: (() -> Unit)?, onQr: () -> Unit, saveCid: String? = null,
                          onExport: (() -> Unit)? = null) {
    if (onWallpaper == null && onShare == null && saveCid == null && onExport == null) return
    val c = Fariin.colors
    var open by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    var saveOn by remember(saveCid, open) { mutableStateOf(saveCid?.let { com.fariin.app.media.AutoSaveMedia.savesMedia(ctx, it) } ?: false) }
    Box {
        com.fariin.app.ui.components.FloatingIcon(androidx.compose.material.icons.Icons.Filled.MoreVert, contentDescription = "More",
            onClick = { open = true }, modifier = Modifier.padding(end = 4.dp))
        androidx.compose.material3.DropdownMenu(open, onDismissRequest = { open = false }, shape = com.fariin.app.ui.components.FariinMenu.shape,
            border = com.fariin.app.ui.components.FariinMenu.border, containerColor = c.card) {
            if (onWallpaper != null) androidx.compose.material3.DropdownMenuItem(text = { Text("Change Wallpaper", color = c.label) },
                leadingIcon = { Icon(ImageVector.vectorResource(com.fariin.app.R.drawable.ic_wallpaper), null, tint = c.label) },
                onClick = { open = false; onWallpaper() })
            if (onShare != null) {
                androidx.compose.material3.DropdownMenuItem(text = { Text("Share Group", color = c.label) },
                    leadingIcon = { Icon(androidx.compose.ui.res.painterResource(com.fariin.app.R.drawable.ic_link_share), null, tint = c.label, modifier = Modifier.size(24.dp)) },
                    onClick = { open = false; onShare() })
                androidx.compose.material3.DropdownMenuItem(text = { Text("QR Code", color = c.label) },
                    leadingIcon = { Icon(ImageVector.vectorResource(com.fariin.app.R.drawable.ic_qr), null, tint = c.label) },
                    onClick = { open = false; onQr() })
            }
            // iOS menuMediaItems: this group's own Save to Photos, over the Settings switch.
            if (saveCid != null) androidx.compose.material3.DropdownMenuItem(
                text = { Text("Save to Photos: ${if (saveOn) "On" else "Off"}", color = c.label) },
                leadingIcon = { Icon(androidx.compose.material.icons.Icons.Outlined.Download, null, tint = c.label) },
                onClick = { saveOn = !saveOn; com.fariin.app.media.AutoSaveMedia.setChat(ctx, saveCid, saveOn); open = false })
            if (onExport != null) ExportChatItem { open = false; onExport() }
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
        dismissButton = { TextButton({ confirmRemove = false }) { Text("Cancel", color = c.accent) } }, containerColor = c.card)
    Column(Modifier.fillMaxSize().background(c.bgSecondary)) {
        PageBar(if (channel) "Edit Channel" else "Edit Group", onBack = onClose, action = {
            TextAction("Save", enabled = trimmed.isNotEmpty() && changed && !saving, busy = saving) {
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
                        removePhoto -> Avatar(null, 120.dp, squircle = channel, kind = com.fariin.app.ui.components.AvatarKind.of(true, channel))
                        else -> Avatar(photoUrl, 120.dp, squircle = channel, kind = com.fariin.app.ui.components.AvatarKind.of(true, channel))
                    }
                }
                if (hasPhoto) Box(
                    Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-6).dp).size(30.dp).clip(CircleShape)
                        .background(c.card).clickable { confirmRemove = true },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Remove, "Remove Photo", tint = c.label, modifier = Modifier.size(18.dp)) }
            }
            Spacer(Modifier.height(12.dp))
            // Take Photo or Choose Photo (iOS NewGroup / EditGroup photo dialog); Remove is the badge.
            var groupPhotoMenu by remember { mutableStateOf(false) }
            Box {
                Text("Set New Photo", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.label,
                    modifier = Modifier.clip(CircleShape).background(c.card).clickable { groupPhotoMenu = true }.padding(horizontal = 20.dp, vertical = 9.dp))
                androidx.compose.material3.DropdownMenu(expanded = groupPhotoMenu, onDismissRequest = { groupPhotoMenu = false }) {
                    androidx.compose.material3.DropdownMenuItem(text = { Text("Take Photo") },
                        leadingIcon = { Icon(ImageVector.vectorResource(com.fariin.app.R.drawable.ic_camera), null) }, onClick = { groupPhotoMenu = false; picker.takePhoto() })
                    androidx.compose.material3.DropdownMenuItem(text = { Text("Choose Photo") },
                        leadingIcon = { Icon(ImageVector.vectorResource(com.fariin.app.R.drawable.ic_album), null) }, onClick = { groupPhotoMenu = false; pick() })
                }
            }
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
        if (folds && !expanded) Text("more", fontSize = 17.sp, color = c.accent, modifier = Modifier.align(Alignment.End))
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
                cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth().focusRequester(focus))
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

/**
 * Back from Group Info opened at one step of the chat's welcome card (Add Members, Group Link,
 * Edit): closing THAT step returns to the chat; nothing else does. The step is still up while its
 * own page is: Add Members, Edit Group, or the Invite Links page it opened at the bottom of the stack.
 */
internal object GroupStepBack {
    fun stepOpen(start: String?, adding: Boolean, editing: Boolean, pages: List<GroupPage>): Boolean = when (start) {
        "add" -> adding
        "edit" -> editing
        "link" -> pages.firstOrNull() == GroupPage.Links
        else -> false
    }

    /** The route should leave for the chat now (only while the step is what this page was opened at). */
    fun closesToChat(start: String?, adding: Boolean, editing: Boolean, pages: List<GroupPage>): Boolean =
        start in STEPS && !stepOpen(start, adding, editing, pages)

    private val STEPS = setOf("add", "edit", "link")
}
