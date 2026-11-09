package com.fariin.app.ui.people

import androidx.compose.foundation.combinedClickable
import com.fariin.app.ui.components.glassMenuAnchor
import com.kyant.backdrop.backdrops.layerBackdrop
import com.fariin.app.R
import com.fariin.app.ui.components.sheetDrag
import com.fariin.app.ui.settings.BarPage
import com.fariin.app.ui.components.underBar

import com.fariin.app.data.NewBadgeSpots
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.draw.alpha
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
fun EditProfileScreen(api: PeopleApi, onBack: () -> Unit,
                      /** In the sheet: no status-bar gap above the bar (the sheet already sits under it). */
                      inSheet: Boolean = false,
                      /** The sheet it sits in: a pull down on the bar, or on the page at its top, moves and closes it (iOS). */
                      sheet: com.fariin.app.ui.components.SheetMotion? = null) {
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
    // THE PHOTO WAITS FOR SAVE (iOS Edit Photo sheet, owner 2026-11-08): a new picture or a removal is
    // only held here, shown in the circle, and goes to the server when Edit Profile's Save is pressed.
    var stagedJpeg by remember { mutableStateOf<ByteArray?>(null) }
    var stagedRemove by remember { mutableStateOf(false) }
    // The Edit Photo sheet's own pick, kept until its check mark hands it to the page.
    var photoSheet by remember { mutableStateOf(false) }
    var sheetJpeg by remember { mutableStateOf<ByteArray?>(null) }
    var sheetRemove by remember { mutableStateOf(false) }
    val picker = rememberCirclePhotoPicker { jpeg -> sheetJpeg = jpeg; sheetRemove = false }
    val pick = picker.launch
    // iOS ProfilePhotoSheet Recents: the photos I had before, tap one to use it again.
    fun useAgain(f: java.io.File) {
        if (busy) return
        scope.launch {
            busy = true
            val bytes = runCatching { f.readBytes() }.getOrNull()
            if (bytes != null) { stagedJpeg = bytes; stagedRemove = false }
            busy = false
        }
    }
    if (picker.pending != null) { picker.Host("Avatar", "Shown in chats, stories and groups"); return }
    if (page == "links") { ProfileLinksPage(api.me, links, onChanged = { links = it }, onBack = { page = "" }); return }
    if (page == "username") { UsernameScreen(api, me?.handle ?: "", onBack = { page = ""; scope.launch { me = api.profile(api.me) } }); return }
    error?.let { e -> AlertDialog(onDismissRequest = { error = null }, text = { Text(e) },
        confirmButton = { TextButton({ error = null }) { Text("OK", color = c.accent) } }, containerColor = c.card) }
    Column(Modifier.fillMaxSize().background(c.bgSecondary)) {
        val changed = me != null && (name != (me?.name ?: "").trim() || about.trim() != (me?.about ?: "").trim() ||
            stagedJpeg != null || stagedRemove)
        val close = { if (changed) askDiscard = true else onBack() }
        androidx.activity.compose.BackHandler(onBack = close)
        // The contact page's bottom sheet; Cancel is Keep Editing.
        if (askDiscard) PersonConfirmSheet(me?.photoUrl, me?.name.orEmpty(), "Discard changes?", "Your changes are not saved yet.",
            listOf(PersonAction("Discard Changes", plain = true) { onBack(); true }), onDismiss = { askDiscard = false })
        Box(Modifier.fillMaxWidth().then(if (sheet != null) Modifier.sheetDrag(sheet) else Modifier)
            .then(if (inSheet) Modifier.padding(top = 8.dp) else Modifier.statusBarsPadding()).height(64.dp).padding(horizontal = 12.dp)) {
            Box(Modifier.align(Alignment.CenterStart).size(44.dp).floating(CircleShape).clickable(onClickLabel = "Close") { close() },
                contentAlignment = Alignment.Center) { Icon(ImageVector.vectorResource(R.drawable.ic_sf_xmark), null, tint = c.label) }
            Text("Edit Profile", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = c.label, modifier = Modifier.align(Alignment.Center))
            Box(Modifier.align(Alignment.CenterEnd)) {
            TextAction("Save", enabled = changed && name.isNotBlank() && !busy, busy = saving) {
                scope.launch {
                    busy = true; saving = true
                    try {
                        if (name.trim() != me?.name) api.saveName(name)
                        // The held photo goes now (iOS: nothing changes until Save).
                        stagedJpeg?.let { j ->
                            api.uploadPhoto(j)
                            com.fariin.app.data.ProfilePhotoHistory.record(appCtx, api.me, j); historyGen++
                        }
                        if (stagedRemove && stagedJpeg == null) api.removePhoto()
                        // Without a loaded profile the bio is only empty, not cleared by anyone (iOS).
                        if (me != null && about.trim() != me?.about?.trim()) com.fariin.app.data.ProfileWrites.saveAbout(api.me, about)
                        onBack()
                    } catch (_: Exception) { error = "Couldn't save. Try again." }
                    busy = false; saving = false
                }
            }
            }
        }
        val pageScroll = rememberScrollState()
        Column(Modifier.fillMaxSize().then(if (sheet != null) Modifier.sheetDrag(sheet, atTop = { pageScroll.value == 0 }) else Modifier)
            .verticalScroll(pageScroll), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(8.dp))
            // The circle shows what Save will keep: a held new photo, nothing if removal is held, else mine.
            val heldBmp = remember(stagedJpeg) { stagedJpeg?.let { j -> runCatching { android.graphics.BitmapFactory.decodeByteArray(j, 0, j.size)?.asImageBitmap() }.getOrNull() } }
            Box(Modifier.size(120.dp).clip(CircleShape).clickable { sheetJpeg = stagedJpeg; sheetRemove = stagedRemove; photoSheet = true },
                contentAlignment = Alignment.Center) {
                if (heldBmp != null) androidx.compose.foundation.Image(heldBmp, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Avatar(if (stagedRemove) null else me?.photoUrl, 120.dp)
                if (saving) Box(Modifier.size(120.dp).background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                    com.fariin.app.ui.components.FariinSpinner(size = 30.dp, stroke = 3.dp, color = androidx.compose.ui.graphics.Color.White)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("Edit Photo", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.label,
                modifier = Modifier.clip(CircleShape).background(c.card)
                    .clickable { sheetJpeg = stagedJpeg; sheetRemove = stagedRemove; photoSheet = true }.padding(horizontal = 20.dp, vertical = 8.dp))
            // iOS Edit Photo: its own sheet over Edit Profile (hidden while the cropper is up).
            if (photoSheet && picker.pending == null) EditPhotoSheet(
                current = if (sheetRemove) null else me?.photoUrl, held = sheetJpeg, removed = sheetRemove,
                onCamera = { picker.takePhoto() }, onLibrary = { pick() },
                onRemove = { sheetJpeg = null; sheetRemove = true },
                onDone = { stagedJpeg = sheetJpeg; stagedRemove = sheetRemove && sheetJpeg == null; photoSheet = false },
                onCancel = { photoSheet = false })
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
    BarPage("Username", onBack = onBack, action = {
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
        }, modifier = Modifier.background(c.bgSecondary)) { top ->
        androidx.compose.foundation.layout.Spacer(Modifier.height(top))
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
    // iOS NewGroupView contacts: every 1:1 chat of mine, a chat I started and they have not answered
    // included (was left out); never a message request waiting for me, never one I cleared. The
    // server still applies each person's "who can add me".
    chats.filter { !it.isGroup && !it.isChannel && (it.accepted || it.startedBy.isEmpty() || it.startedBy == me) && !it.isCleared(me) }.map {
        val u = it.otherUid(me)
        Person(u, name = it.names[u] ?: "", photoUrl = it.photos[u])
    }.filter { it.uid.isNotEmpty() }.distinctBy { it.uid }

/** A whole page of [PickPeople] with its action in the top bar (a channel's Add Followers). */
@Composable
internal fun PickPeoplePage(title: String, action: String, people: List<Person>, onBack: () -> Unit, onDone: (List<String>) -> Unit) {
    var picked by remember { mutableStateOf(setOf<String>()) }
    androidx.activity.compose.BackHandler(onBack = onBack)
    BarPage(title, onBack = onBack, action = { TextAction(action, picked.isNotEmpty()) { onDone(picked.toList()) } }, modifier = Modifier.background(Fariin.colors.bgSecondary)) { top ->
        androidx.compose.foundation.layout.Spacer(Modifier.height(top))
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
                    Icon(if (p.uid in picked) ImageVector.vectorResource(R.drawable.ic_sf_checkmark_circle_fill) else ImageVector.vectorResource(R.drawable.ic_sf_circle), null,
                        tint = if (p.uid in picked) c.blue else c.tertiary)
                }
            }
        }
    }
}

/**
 * New Group's member picker (iOS NewGroupView.membersPage): "Members · N" (me included) over the
 * picked faces, the contacts under A to Z letters ("#" last), and a typed name or username asks the
 * server (the same lookup as New Chat's "Search by username"), so people I have not chatted with
 * can be added too. Nobody to list says how to get someone here.
 */
@Composable
private fun NewGroupPicker(api: PeopleApi, people: List<Person>, chosen: List<Person>, maxOthers: Int, onToggle: (Person) -> Unit) {
    val c = Fariin.colors
    var q by remember { mutableStateOf("") }
    var found by remember { mutableStateOf(listOf<Person>()) }
    var searching by remember { mutableStateOf(false) }
    val needle = q.trim().removePrefix("@")
    LaunchedEffect(needle) {
        found = emptyList()
        if (needle.isEmpty()) { searching = false; return@LaunchedEffect }
        searching = true
        delay(300)   // one ask per pause in typing, not per letter
        // iOS search(): searchUsers, then the exact @handle when that finds nobody.
        val r = runCatching { api.search(needle).ifEmpty { if (needle.length >= 3) listOfNotNull(api.byHandle(needle)) else emptyList() } }.getOrDefault(emptyList())
        found = r.filter { it.uid != api.me }
        searching = false
    }
    val picked = chosen.map { it.uid }.toSet()
    if (chosen.isNotEmpty()) {
        // +1 = me; at the cap the header says so, as the next tap does nothing (iOS).
        Text(if (chosen.size >= maxOthers) "Members · ${chosen.size + 1} max" else "Members · ${chosen.size + 1}",
            fontSize = 13.sp, color = c.secondary, modifier = Modifier.padding(start = 32.dp, top = 4.dp))
        LazyRow(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            items(chosen, key = { it.uid }) { p ->
                Column(Modifier.width(64.dp).clickable { onToggle(p) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Avatar(p.photoUrl, 48.dp)
                    Text(p.shownName, fontSize = 12.sp, color = c.label, maxLines = 1)
                }
            }
        }
    }
    SearchField(q, "Name or username") { q = it }
    @Composable
    fun row(p: Person, first: Boolean, last: Boolean) {
        val top = if (first) 20.dp else 0.dp
        val bottom = if (last) 20.dp else 0.dp
        Box(Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(top, top, bottom, bottom)).background(c.card)) {
            PersonRow(p.photoUrl, p.shownName, "", onClick = { onToggle(p) }) {
                Icon(if (p.uid in picked) ImageVector.vectorResource(R.drawable.ic_sf_checkmark_circle_fill) else ImageVector.vectorResource(R.drawable.ic_sf_circle), null,
                    tint = if (p.uid in picked) c.blue else c.tertiary)
            }
        }
    }
    @Composable
    fun card(content: @Composable () -> Unit) = Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth()
        .clip(RoundedCornerShape(20.dp)).background(c.card)) { content() }
    val lettered = remember(people) {
        people.sortedBy { it.shownName.lowercase() }.groupBy { p ->
            p.shownName.trim().take(1).uppercase().takeIf { it.length == 1 && it[0] in 'A'..'Z' } ?: "#"
        }.toSortedMap(compareBy<String> { it == "#" }.thenBy { it })
    }
    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp)) {
        if (needle.isEmpty()) {
            if (lettered.isEmpty()) item {
                card { Text("Chat with someone first to add them here.", fontSize = 17.sp, color = c.secondary, modifier = Modifier.padding(16.dp)) }
            }
            lettered.forEach { (letter, group) ->
                item(key = "h_$letter") { Header(letter) }
                itemsIndexed(group, key = { _, p -> "c_" + p.uid }) { i, p -> row(p, i == 0, i == group.lastIndex) }
            }
        } else {
            // My own contacts by name first, then whoever the server finds by username (iOS "Results").
            val local = people.filter { it.shownName.contains(needle, true) || it.handle.contains(needle, true) }
            val rows = (local + found).distinctBy { it.uid }
            item(key = "h_results") { Header("Results") }
            if (rows.isEmpty()) item(key = "empty") {
                if (searching) card {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        repeat(3) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                com.fariin.app.ui.components.SkeletonCircle(44.dp)
                                Spacer(Modifier.width(12.dp))
                                com.fariin.app.ui.components.SkeletonBlock(140.dp, 14.dp)
                            }
                        }
                    }
                } else card { Text("No one found", fontSize = 17.sp, color = c.secondary, modifier = Modifier.padding(16.dp)) }
            }
            itemsIndexed(rows, key = { _, p -> "r_" + p.uid }) { i, p -> row(p, i == 0, i == rows.lastIndex) }
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
    // In tap order (iOS `order`): a person found by username is not among [people], so the pick keeps the person.
    var chosen by remember { mutableStateOf(listOf<Person>()) }
    val picked = chosen.map { it.uid }.toSet()
    var step by remember { mutableStateOf(1) }
    androidx.activity.compose.BackHandler(enabled = step > 1) { step -= 1 }
    // The group's member switches, chosen before it exists (iOS NewGroupView), with the same defaults.
    var canSend by remember { mutableStateOf(true) }
    var canAdd by remember { mutableStateOf(false) }
    var canCall by remember { mutableStateOf(true) }
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
        // The capsule is the action's own (TextAction floats), so no second circle around it.
        com.fariin.app.ui.components.FloatingTopBar(t, onBack = back, container = c.bgSecondary) { action() }
    }
    // Every step's bar is glass over its page (GlassBarPage); each step lays out below it.
    com.fariin.app.ui.components.GlassColumnPage(false, Modifier.background(c.bgSecondary), bar = {
        Box(Modifier.fillMaxWidth().background(c.bgSecondary).statusBarsPadding()) {
            when (step) {
                1 -> bar("Add Members", onBack) { TextAction("Next", picked.isNotEmpty()) { step = 2 } }
                3 -> com.fariin.app.ui.components.FloatingTopBar("Permissions", onBack = { step = 2 }, container = c.bgSecondary)
                else -> bar("New Group", { step = 1 }) {
                    TextAction("Create", title.isNotBlank() && !busy) {
                        busy = true
                        scope.launch {
                            // At most N groups owned (iOS NewGroupView): the server counts, and says so here.
                            com.fariin.app.data.AppLimits.ownedGroupsFull()?.let { max ->
                                error = com.fariin.app.data.AppLimits.ownedGroupsSentence(max); busy = false; return@launch
                            }
                            try {
                                // iOS NewGroupView `perms`: edit info and share link stay admins' only.
                                val perms = mapOf("onlyAdminsSend" to !canSend, "membersCanAdd" to canAdd,
                                    "membersCanEditInfo" to false, "onlyAdminsCall" to !canCall, "membersCanShareLink" to false)
                                val cid = api.createGroup(title, chosen.map { it.uid }, photo, perms)
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
            }
        }
    }) { top ->
        androidx.compose.foundation.layout.Spacer(Modifier.height(top))
        if (step == 1) {
            // The create rule allows at most group_members_max people, me included (iOS
            // NewGroupView.maxOthersAtCreate): the picker stops one short, the other place is mine.
            val maxOthers = maxOf(1, com.fariin.app.data.AppLimits.groupMembersMax - 1)
            NewGroupPicker(api, people, chosen, maxOthers) { p ->
                chosen = if (p.uid in picked) chosen.filter { it.uid != p.uid } else if (chosen.size < maxOthers) chosen + p else chosen
            }
        } else if (step == 3) {
            // iOS NewGroupView.permissionsPage: Send messages, Add members, Start group calls.
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                SettingsSection(header = "What members can do", footer = "Admins can always do these. Switch one off and only admins can.") {
                    com.fariin.app.ui.channels.SwitchLine("Send messages", canSend) { canSend = it }
                    com.fariin.app.ui.channels.SwitchLine("Add members", canAdd) { canAdd = it }
                    com.fariin.app.ui.channels.SwitchLine("Start group calls", canCall, last = true) { canCall = it }
                }
            }
        } else {
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
                            leadingIcon = { Icon(ImageVector.vectorResource(R.drawable.ic_sf_trash), null, tint = c.red) }, onClick = { slotMenu = false; photo = null })
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
            // iOS: the Permissions row with how many switches are on, and the "change this later" line.
            SettingsSection(footer = "What members can do. You can change this later in the group's settings.") {
                SettingsRow("Permissions", value = "${listOf(canSend, canAdd, canCall).count { it }}/3", last = true) { step = 3 }
            }
            // No "Members · N" heading on this page (iOS, owner 2026-10-20): it counted me but listed
            // only the others, so two over one row read as a mistake.
            if (chosen.isNotEmpty()) SettingsSection {
                chosen.forEach { p -> PersonRow(p.photoUrl, p.shownName, "", onClick = null) }
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
                    onLeft: () -> Unit, onOpenPerson: (String) -> Unit, start: String? = null,
                    onOpenChat: (String) -> Unit = {}) {
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
    var askEnd by remember { mutableStateOf(false) }
    var askDelete by remember { mutableStateOf(false) }
    var askClearAll by remember { mutableStateOf(false) }
    var clearingAll by remember { mutableStateOf(false) }
    if (askClearAll) PersonConfirmSheet((conv["avatarUrl"] as? String)?.ifEmpty { null }, conv["title"] as? String ?: "", "Clear all messages?",
        "Every message in this group is deleted for you and all members. You can't undo this.",
        listOf(PersonAction("Clear") {
            clearingAll = true
            val ok = runCatching {
                val fn = com.google.firebase.functions.FirebaseFunctions.getInstance(com.fariin.app.data.AuthRepository.FUNCTIONS_REGION)
                    .getHttpsCallable("clearGroupMessages").withTimeout(540, java.util.concurrent.TimeUnit.SECONDS)
                val data = mutableMapOf<String, Any>("cid" to cid)
                for (round in 0 until 12) {
                    val d = fn.call(data).await().getData() as? Map<*, *> ?: break
                    if (d["more"] != true) break
                    d["before"]?.let { data["before"] = it }
                }
            }.isSuccess
            clearingAll = false
            com.fariin.app.ui.components.Snack.show(if (ok) "All messages were cleared." else "Couldn't clear the messages. Check your connection and try again.")
            ok
        }), onDismiss = { askClearAll = false })
    val exitScope = rememberCoroutineScope()
    // iOS settingsCard for everyone in the group (owner 2026-11-07 with the iPhone's page): Sounds &
    // Notifications ("On" / "Muted"), and Storage Used with the chat's size on this phone.
    var muteOverride by remember(cid) { mutableStateOf<Double?>(null) }
    val liveUntil = com.fariin.app.data.GroupInfoCircles.mutedUntil(conv, me)
    androidx.compose.runtime.LaunchedEffect(liveUntil) { muteOverride = null }
    val muteUntil = muteOverride ?: liveUntil
    var soundsOpen by remember { mutableStateOf(false) }
    if (soundsOpen) {
        ContactSoundsPage(muteUntil, onMute = { until ->
            muteOverride = until
            scope.launch { if (!writeContactMute(cid, me, until)) muteOverride = null }
        }, onBack = { soundsOpen = false })
        return
    }
    val storageCtx = LocalContext.current.applicationContext
    val hiddenNow by com.fariin.app.data.HiddenMessages.ids.collectAsState()
    val storageBytes by androidx.compose.runtime.produceState<Long?>(null, cid, hiddenNow) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val crypto = com.fariin.app.data.CryptoHub.get(storageCtx)
                val msgs = com.fariin.app.data.SharedMediaSource(cid, me, crypto).local()
                val media = com.fariin.app.media.ChatMedia(storageCtx, cid, crypto)
                ChatStorageRules.items(msgs, media) { it in hiddenNow }.sumOf { it.bytes }
            }.getOrNull()
        }
    }
    // Awaited, so the sheet's button spins until the server answers; then the sheet closes and the note shows.
    suspend fun callable(name: String, done: String): Boolean {
        val ok = runCatching {
            com.google.firebase.functions.FirebaseFunctions.getInstance(com.fariin.app.data.AuthRepository.FUNCTIONS_REGION)
                .getHttpsCallable(name).call(mapOf("cid" to cid)).await()
        }.isSuccess
        com.fariin.app.ui.components.Snack.show(if (ok) done else "Could not do that. Check your connection and try again.")
        return ok
    }
    // End and Delete: the contact page's bottom sheet with the group's photo and name on top.
    val groupPhoto = (conv["avatarUrl"] as? String)?.ifEmpty { null }
    val groupName = conv["title"] as? String ?: ""
    val groupKind = com.fariin.app.ui.components.AvatarKind.of(true, conv["channel"] == true)
    if (askEnd) PersonConfirmSheet(groupPhoto, groupName, "End This Group?",
        "Nobody will be able to send messages or start calls, and invite links stop working. Messages stay. This can't be undone.",
        listOf(PersonAction("End Group") { callable("endGroup", "The group has ended.") }),
        onDismiss = { askEnd = false }, kind = groupKind)
    if (askDelete) PersonConfirmSheet(groupPhoto, groupName, "Delete Group?",
        "This permanently deletes the group and all its messages for everyone. This cannot be undone.",
        listOf(PersonAction("Delete for Everyone") {
            // iOS LeaveGroupUndo .deleteGroup: gone from the list at once, Undo for a few seconds, then the server deletes it.
            // A server failure after the Undo window gives the group back and says so (LeaveUndo.commit).
            val t = conv["title"] as? String ?: "the group"
            com.fariin.app.data.LeaveUndo.start(cid, t, failed = "Couldn't delete \"$t\". Check your connection and try again.") {
                com.google.firebase.functions.FirebaseFunctions.getInstance(com.fariin.app.data.AuthRepository.FUNCTIONS_REGION)
                    .getHttpsCallable("deleteGroup").call(mapOf("cid" to cid)).await()
            }
            com.fariin.app.ui.components.Snack.show("Group deleted.", "Undo", durationMs = com.fariin.app.data.LeaveUndo.WINDOW_MS) {
                com.fariin.app.data.LeaveUndo.undo()
            }
            onLeft()
            true
        }), onDismiss = { askDelete = false }, kind = groupKind)
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
        // iOS AddMembersSheet.add: the button turns into a spinner and stays off while the add runs;
        // the page closes only on success (with a short "Added ..." here); an error, or a note about
        // who could not be added, is said on this page. The note closes the page once anyone joined.
        var addBusy by remember { mutableStateOf(false) }
        var addNote by remember { mutableStateOf<String?>(null) }
        var addError by remember { mutableStateOf<String?>(null) }
        fun done() { adding = false; picked = emptySet() }
        addError?.let { e -> AlertDialog(onDismissRequest = { addError = null }, containerColor = c.card,
            title = { Text("Couldn't add members") }, text = { Text(e) },
            confirmButton = { TextButton({ addError = null }) { Text("OK", color = c.accent) } }) }
        addNote?.let { n -> AlertDialog(onDismissRequest = { addNote = null }, containerColor = c.card, text = { Text(n) },
            confirmButton = { TextButton({ addNote = null; if (picked.any { it in users }) done() }) { Text("OK", color = c.accent) } }) }
        BarPage("Add Members", onBack = { adding = false }, action = {
                TextAction("Add", picked.isNotEmpty() && !addBusy, busy = addBusy) {
                    val names = people.filter { it.uid in picked }.map { it.shownName }
                    addBusy = true
                    scope.launch {
                        try {
                            val note = api.addMembers(cid, picked.toList())
                            if (note == null) {
                                done()
                                com.fariin.app.ui.components.Snack.show(if (names.isEmpty()) "Members added" else "Added ${GroupRules.addedWho(names)}")
                            } else addNote = note
                        } catch (e: Exception) {
                            addError = (e as? com.google.firebase.functions.FirebaseFunctionsException)?.message?.takeIf { it.isNotBlank() }
                                ?: "Check your connection and try again."
                        }
                        addBusy = false
                    }
                }
            }, modifier = Modifier.background(c.bgSecondary)) { top ->
            androidx.compose.foundation.layout.Spacer(Modifier.height(top))
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
    card?.let { u -> MemberCard(cid, me, u, conv, onClose = { card = null }, onProfile = onOpenPerson, open = { pages.add(it) },
        onMessage = { other -> onOpenChat(chats.firstOrNull { !it.isGroup && it.users.contains(other) }?.id
            ?: com.fariin.app.crypto.Wire.directCid(me, other)) }) }
    if (editing && canEdit) {
        // iOS: Edit Group is a sheet over the group page (.sheet), pulled down to close.
        val editMotion = com.fariin.app.ui.components.rememberSheetMotion { editing = false; editAtDescription = false }
        com.fariin.app.ui.components.FariinSheetDialog(editMotion, fill = c.bgSecondary, typing = true) {
            EditGroupScreen(api, cid, conv["title"] as? String ?: "Group", (conv["avatarUrl"] as? String)?.ifEmpty { null },
                onClose = { editMotion.close() }, description = desc, focusDescription = editAtDescription, sheet = editMotion)
        }
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
    var sharingLink by remember { mutableStateOf(false) }
    if (sharingLink && inviteLink != null) com.fariin.app.ui.thread.LinkShareSheet(me, inviteLink, onDismiss = { sharingLink = false })
    var showQr by remember { mutableStateOf(false) }
    var publicQr by remember { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current
    // iOS GroupInfoView exportChat: this group's messages as a text file (ChatExportRun). Not for a channel.
    var exportJob by remember { mutableStateOf<ChatExportRun.Job?>(null) }
    exportJob?.let { j -> ChatExportDialog(j) { exportJob = null } }
    publicQr?.let { link -> com.fariin.app.ui.settings.GroupQrSheet(conv["title"] as? String ?: "Group", link,
        (conv["avatarUrl"] as? String)?.ifEmpty { null }, users.size, onClose = { publicQr = null }) }
    if (showQr && inviteLink != null) com.fariin.app.ui.settings.GroupQrSheet(conv["title"] as? String ?: "Group", inviteLink,
        (conv["avatarUrl"] as? String)?.ifEmpty { null }, users.size, onClose = { showQr = false })
    // The page runs under its bar, so back, Edit and the menu are glass over it (GlassBarPage).
    val pageScroll = rememberScrollState()
    val underBar by remember { androidx.compose.runtime.derivedStateOf { pageScroll.value > 0 } }
    // The More circle's glass menu grows out of the circle over the page's own layer (as the channel's).
    val moreAnchor = remember { com.fariin.app.ui.components.GlassMenuAnchor() }
    var moreOpen by remember { mutableStateOf(false) }
    val menuBackdrop = com.kyant.backdrop.backdrops.rememberLayerBackdrop()
    val menuGlass = com.fariin.app.perf.rememberPowerState().glass() && android.os.Build.VERSION.SDK_INT >= 33
    Box(Modifier.fillMaxSize()) {
    com.fariin.app.ui.components.GlassBarPage(underBar, Modifier.background(c.bgSecondary)
        .then(if (menuGlass) Modifier.layerBackdrop(menuBackdrop) else Modifier), frosted = true, bar = {
        // iOS: "Edit" top right for whoever may change the group's info (owner 2026-10-19), and the
        // ••• menu (moreMenuItems) as the Android three-dot overflow: Share Group and QR Code while the
        // group has a link I may share, and Change Wallpaper (the group's, for everyone) for whoever
        // may change its info: back to the chat, which opens its picker. Save to Photos waits for auto-save.
        // iOS (owner's screenshots 2026-11-08): no title in the bar, only Edit; the menu is the More circle.
        // iOS: the Add Story mark (owner, admins with the right), then Edit.
        PageBar("", onBack = onBack, action = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                com.fariin.app.ui.stories.GroupStoryButton(me, cid, onLeave = onBack)
                if (canEdit) TextAction("Edit", enabled = true) { editing = true }
            }
        })
    }) { top ->
        Column(Modifier.fillMaxSize().verticalScroll(pageScroll).padding(top = top), horizontalAlignment = Alignment.CenterHorizontally) {
            // A story and a photo: the tap asks which (iOS dd1a37ac); else as before.
            val photoChoice = headerPhotoOrStory(cid, conv["title"] as? String ?: "Group", conv["avatarUrl"] as? String, me, chats)
            Avatar(conv["avatarUrl"] as? String, 100.dp, kind = com.fariin.app.ui.components.AvatarKind.of(true, conv["channel"] == true),
                modifier = if (photoChoice != null) Modifier.combinedClickable(onClickLabel = "View story", onClick = photoChoice.tap,
                    onLongClickLabel = "View photo", onLongClick = photoChoice.hold).semantics { contentDescription = "Group photo" }
                else if (canEdit) Modifier.clickable(onClickLabel = "Edit group") { editing = true }.semantics { contentDescription = "Group photo" } else Modifier)
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
                GroupCirclesRow(cid, me, conv, users.size, onSearch = if (fromChat) ({ com.fariin.app.ui.thread.InChatSearchLink.searchFromInfo(cid, onBack) }) else null,
                    onNotice = { notice = it }, moreAnchor = moreAnchor, onMore = { moreOpen = true })
            }
            Spacer(Modifier.height(20.dp))
            // iOS GroupAboutCard: the description and, for a public group, its link with the QR.
            val publicName = (conv["username"] as? String)?.takeIf { it.isNotEmpty() && conv["isPublic"] == true }
            if (desc.isNotEmpty() || publicName != null) SettingsSection {
                if (desc.isNotEmpty()) DescriptionCard(desc)
                if (publicName != null) {
                    if (desc.isNotEmpty()) androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = c.separator, modifier = Modifier.padding(start = 16.dp))
                    Row(Modifier.fillMaxWidth().clickable { publicQr = "https://fariin.com/$publicName" }.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(ImageVector.vectorResource(R.drawable.ic_sf_link), null, tint = c.label, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("fariin.com/$publicName", fontSize = 17.sp, color = c.label)
                            Text("Link", fontSize = 14.sp, color = c.secondary)
                        }
                        Icon(ImageVector.vectorResource(R.drawable.ic_qr), "QR code", tint = c.secondary, modifier = Modifier.size(24.dp))
                    }
                }
            }
            if (iAmMember) SettingsSection {
                // iOS settingsCard (owner 2026-10-18/20): Storage Used, then for the owner and admins one
                // Group Settings row (the management lives on that page) and Join Requests while
                // someone waits; a member's Disappearing Messages row stays here, read-only.
                // No Chat Bubble row (owner 2026-11-07): bubble styles live in Settings > Appearance and the chat's wallpaper sheet.
                SettingsRow("Sounds & Notifications", icon = painterResource(com.fariin.app.R.drawable.ic_notifications),
                    value = contactNotifValue(muteUntil)) { soundsOpen = true }
                SettingsRow("Storage Used", icon = painterResource(com.fariin.app.R.drawable.ic_set_storage), last = !iAmAdmin && timer <= 0 && !showStats,
                    value = storageBytes?.let { if (it > 0) android.text.format.Formatter.formatShortFileSize(storageCtx, it) else "None" },
                    badge = NewBadgeSpots.GROUP_INFO_STORAGE) {
                    pages.add(GroupPage.Storage(conv["title"] as? String ?: "Group"))
                }
                if (!iAmAdmin && timer > 0) DisappearingRow(timer, short = false, last = !showStats,
                    badge = NewBadgeSpots.GROUP_SETTINGS_DISAPPEARING) {
                    notice = com.fariin.app.data.Disappearing.refusal(conv, me)
                }
                // No Bubble Styles row here (iOS 2026-11-08 took it off the group page again).
                if (iAmAdmin) {
                    val waiting = rememberJoinRequests(cid, allowed = team.may(me, com.fariin.app.data.GroupAdmins.Right.INVITE_USERS) && !team.closed)
                    SettingsRow("Group Settings", icon = painterResource(com.fariin.app.R.drawable.ic_group_settings),
                        last = !showStats && waiting.isEmpty(), badge = NewBadgeSpots.GROUP_INFO_SETTINGS) { pages.add(GroupPage.Settings) }
                    if (waiting.isNotEmpty()) SettingsRow("Join Requests", icon = painterResource(com.fariin.app.R.drawable.ic_gs_join_requests),
                        value = com.fariin.app.data.JoinRequests.rowValue(waiting.size), last = !showStats,
                        badge = NewBadgeSpots.GROUP_SETTINGS_REQUESTS) { pages.add(GroupPage.JoinRequests) }
                }
                // Statistics (iOS showStatsRow): owner and admins of a big enough group, last row.
                if (showStats) SettingsRow("Statistics", icon = painterResource(com.fariin.app.R.drawable.ic_statistics), last = true,
                    badge = NewBadgeSpots.GROUP_INFO_STATISTICS) {
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
                Icon(ImageVector.vectorResource(R.drawable.ic_sf_chevron_right), "All members", tint = c.secondary, modifier = Modifier.size(26.dp))
                Spacer(Modifier.weight(1f))
            }
            SettingsSection {
                if (iAmAdmin && team.may(me, com.fariin.app.data.GroupAdmins.Right.INVITE_USERS)) Row(Modifier.fillMaxWidth().clickable { adding = true }
                    .padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(c.fieldFill), contentAlignment = Alignment.Center) {
                        Icon(ImageVector.vectorResource(R.drawable.ic_plus_ios), null, tint = c.label, modifier = Modifier.size(24.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Text("Add Members", fontSize = 17.sp, color = c.label)
                }
                shownMembers.forEach { u ->
                    PersonRow(photos[u]?.ifEmpty { null }, if (u == me) "You" else names[u] ?: "Member", "",
                        // The member's short card for everyone (owner 2026-11-07: only admins got it, the rest went
                        // straight to the profile); it shows only the rows I may use. My own row: admins only.
                        onClick = if (iAmAdmin || u != me) ({ card = u }) else null) {
                        val tag = when { u == owner -> "Owner"; u in admins -> "Admin"; else -> "" }
                        if (tag.isNotEmpty()) Text(tag, fontSize = 14.sp, color = c.secondary)
                    }
                }
                if (more) SettingsRow("See All", icon = rememberVectorPainter(ImageVector.vectorResource(R.drawable.ic_sf_chevron_down)), trailing = Trailing.NONE,
                    last = true) { pages.add(GroupPage.Members) }
            }
            }
            // A group I am no longer in: no Leave (iOS: no Leave, Report, End or Delete for it).
            if (iAmMember) SettingsSection {
                // iOS exit card: Leave Group, and Report Group for a member who is not staff (unless the
                // group turned reports off); the owner's End Group and Delete Group in their own card.
                val canReport = !iAmAdmin && conv["groupNoReport"] != true
                SettingsRow("Leave Group", icon = rememberVectorPainter(ImageVector.vectorResource(R.drawable.ic_channel_leave)), titleColor = c.red, trailing = Trailing.NONE, last = !canReport) { askLeave = true }
                if (canReport) SettingsRow("Report Group", icon = androidx.compose.ui.res.painterResource(com.fariin.app.R.drawable.ic_report), titleColor = c.red, trailing = Trailing.NONE, last = true) {
                    com.fariin.app.data.ReportFlow.ask(com.fariin.app.data.ReportTarget(com.fariin.app.data.ReportKind.GROUP, name = groupName, cid = cid,
                        onLeave = {
                            // Report and Leave, as iOS: leave and block the group so it cannot add me back.
                            com.fariin.app.data.LeaveUndo.start(cid, groupName.ifEmpty { "the group" }) {
                                api.leaveGroup(cid)
                                com.google.firebase.firestore.FirebaseFirestore.getInstance().collection("users").document(me)
                                    .collection("blockedGroups").document(cid).set(mapOf("at" to com.google.firebase.firestore.FieldValue.serverTimestamp())).await()
                            }
                            com.fariin.app.ui.components.Snack.show("You left the group.", "Undo",
                                durationMs = com.fariin.app.data.LeaveUndo.WINDOW_MS) { com.fariin.app.data.LeaveUndo.undo() }
                            onLeft()
                        }))
                }
            }
            if (iAmMember && owner == me) SettingsSection {
                val ended = (conv["mod"] as? Map<*, *>)?.get("state") == "ended"
                if (!ended) SettingsRow("End Group", icon = rememberVectorPainter(ImageVector.vectorResource(R.drawable.ic_sf_xmark_circle)), titleColor = c.red, trailing = Trailing.NONE) { askEnd = true }
                // iOS (owner 2026-11-08): the owner clears every message for everyone; the group stays.
                SettingsRow(if (clearingAll) "Clearing…" else "Clear Messages", icon = rememberVectorPainter(ImageVector.vectorResource(R.drawable.ic_sf_eraser)),
                    titleColor = c.red, trailing = Trailing.NONE) { if (!clearingAll) askClearAll = true }
                SettingsRow("Delete Group", icon = rememberVectorPainter(ImageVector.vectorResource(R.drawable.ic_sf_trash)), titleColor = c.red, trailing = Trailing.NONE, last = true) { askDelete = true }
            }
            // iOS footer: who made the group and when.
            val createdMs = (conv["createdAt"] as? com.google.firebase.Timestamp)?.toDate()?.time?.toDouble()
                ?: (conv["createdAt"] as? Number)?.toDouble()
            val maker = if (owner == me) "you" else names[owner]?.ifEmpty { null }
            if (maker != null || createdMs != null) {
                val is24 = android.text.format.DateFormat.is24HourFormat(storageCtx)
                Text(listOfNotNull(maker?.let { "Created by $it." },
                        createdMs?.let { "Created ${com.fariin.app.data.MessageInfoRules.dateLine(it, is24)}." }).joinToString("\n"),
                    fontSize = 14.sp, color = c.secondary, modifier = Modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, top = 4.dp))
            }
            Spacer(Modifier.height(30.dp))
        }
    }
    run {
        val ctx2 = LocalContext.current
        var saveOn by remember(cid, moreOpen) { mutableStateOf(com.fariin.app.media.AutoSaveMedia.savesMedia(ctx2, cid)) }
        com.fariin.app.ui.components.GlassMenu(moreOpen, moreAnchor, buildList {
            if (canEdit) add(com.fariin.app.ui.components.GlassMenuItem("Change Wallpaper",
                androidx.compose.ui.res.painterResource(R.drawable.ic_wallpaper)) {
                com.fariin.app.ui.settings.ChatWallpaperLink.request(cid); onBack() })
            if (inviteLink != null) {
                add(com.fariin.app.ui.components.GlassMenuItem("Share Group",
                    androidx.compose.ui.res.painterResource(R.drawable.ic_link_share)) { sharingLink = true })
                add(com.fariin.app.ui.components.GlassMenuItem("QR Code",
                    androidx.compose.ui.res.painterResource(R.drawable.ic_qr)) { showQr = true })
            }
            add(com.fariin.app.ui.components.GlassMenuItem("Save to Photos: " + (if (saveOn) "On" else "Off"),
                androidx.compose.ui.res.painterResource(R.drawable.ic_save_down), divider = true,
                    submenu = listOf(true, false).map { on -> com.fariin.app.ui.components.GlassMenuItem(if (on) "On" else "Off", null, checked = saveOn == on) {
                        saveOn = on; com.fariin.app.media.AutoSaveMedia.setChat(ctx2, cid, on) } }))
            if (ChatExportRun.SHOWN && conv["channel"] != true) add(com.fariin.app.ui.components.GlassMenuItem("Export Chat",
                androidx.compose.ui.res.painterResource(R.drawable.ic_save_down)) {
                if (exportJob == null) exportJob = ChatExportRun.Job(cid, conv["title"] as? String ?: "Group") })
        }, backdrop = if (menuGlass) menuBackdrop else null, onDismiss = { moreOpen = false })
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
                            onNotice: (String) -> Unit,
                            /** The More circle (iOS fifth circle): its glass menu is the page's, grown from [moreAnchor]. */
                            moreAnchor: com.fariin.app.ui.components.GlassMenuAnchor? = null, onMore: () -> Unit = {}) {
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
    // On a call in another app too (iOS busyElsewhere with CallKit): the phone's own call state.
    val systemCall = rememberSystemCallActive()
    val ready = !systemCall && circles.callReady(groupCall.cid, oneCall.status.live, cid, ended, mayStart, live)
    val title = conv["title"] as? String ?: "Group"
    var solo by remember { mutableStateOf(false) }
    if (solo) AlertDialog(onDismissRequest = { solo = false }, title = { Text(circles.SOLO_TITLE) }, text = { Text(circles.SOLO_TEXT) },
        confirmButton = { TextButton({ solo = false }) { Text("OK", color = c.accent) } }, containerColor = c.card)
    fun call(video: Boolean) {
        if (systemCall) { com.fariin.app.ui.components.Snack.show("You're already on a call"); return }
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
    // iOS actionsRow: equal shares of the row, inside the page's 16 dp margins.
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        val share = Modifier.weight(1f)
        ActionTile("Voice", ImageVector.vectorResource(com.fariin.app.R.drawable.ic_call_voice), share, dimmed = !ready) { call(video = false) }
        ActionTile("Video", ImageVector.vectorResource(com.fariin.app.R.drawable.ic_call_video), share, dimmed = !ready) { call(video = true) }
        Box(share) {
            ActionTile(if (muted) "Unmute" else "Mute", if (muted) ImageVector.vectorResource(com.fariin.app.R.drawable.ic_bell) else ImageVector.vectorResource(com.fariin.app.R.drawable.ic_bell_off),
                Modifier.fillMaxWidth()) { muteOpen = true }
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
        ActionTile("Search", ImageVector.vectorResource(R.drawable.ic_search_ios), share, dimmed = onSearch == null) { onSearch?.invoke() }
        Box(share.then(if (moreAnchor != null) Modifier.glassMenuAnchor(moreAnchor) else Modifier)) {
            ActionTile("More", ImageVector.vectorResource(R.drawable.ic_more_ios), Modifier.fillMaxWidth(), onClick = onMore)
        }
    }
}

/** True while the phone is on a call (any app): iOS greys Voice and Video then. */
@Composable
private fun rememberSystemCallActive(): Boolean {
    val ctx = LocalContext.current
    val active by androidx.compose.runtime.produceState(false) {
        val am = ctx.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        while (true) {
            value = am.mode == android.media.AudioManager.MODE_IN_CALL || am.mode == android.media.AudioManager.MODE_IN_COMMUNICATION
            kotlinx.coroutines.delay(1500)
        }
    }
    return active
}

/**
 * Edit Group (iOS EditGroupView): the photo with a small minus badge to remove it (after a yes) and
 * the "Set New Photo" pill under it, the name in its card, Save at the top end. Save renames first,
 * then sets or removes the photo; a refusal is said and the page stays. The system back gesture
 * and the up arrow close it without saving.
 */
@Composable
internal fun EditGroupScreen(api: PeopleApi, cid: String, title: String, photoUrl: String?, onClose: () -> Unit,
                             description: String = "", focusDescription: Boolean = false, channel: Boolean = false,
                             /** Shown in the iOS sheet (EditGroupView is a .sheet): X, title and Save in the sheet's own bar. */
                             sheet: com.fariin.app.ui.components.SheetMotion? = null) {
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
    if (picker.pending != null) {
        if (sheet == null) { picker.Host(w("Group photo"), w("Shown in chats and on the group page")); return }
        androidx.compose.ui.window.Dialog(onDismissRequest = {}, properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            com.fariin.app.ui.components.EdgeToEdgeDarkDialogWindow()
            picker.Host(w("Group photo"), w("Shown in chats and on the group page"))
        }
    }
    val descFocus = remember { FocusRequester() }
    LaunchedEffect(focusDescription) { if (focusDescription) { delay(350); runCatching { descFocus.requestFocus() } } }
    // Only marks the photo for removal; Save does the rest, as before.
    if (confirmRemove) PersonConfirmSheet(photoUrl.takeIf { newPhoto == null }, title, w("Remove group photo?"), w("Members will see the group's initial instead. It goes when you tap Save."),
        listOf(PersonAction("Remove") { newPhoto = null; removePhoto = true; true }),
        onDismiss = { confirmRemove = false }, kind = com.fariin.app.ui.components.AvatarKind.of(true, channel))
    val barScroll = rememberScrollState()
    val saveAction: @Composable () -> Unit = {
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
        }
    val body: @Composable () -> Unit = {
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
                    // The profile's own "−" badge: a 30 glass circle, mostly outside the photo (iOS).
                    Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-6).dp).size(30.dp)
                        .floating(CircleShape).clickable { confirmRemove = true },
                    contentAlignment = Alignment.Center,
                ) { Icon(ImageVector.vectorResource(R.drawable.ic_sf_minus), "Remove Photo", tint = c.label, modifier = Modifier.size(18.dp)) }
            }
            Spacer(Modifier.height(12.dp))
            // iOS EditGroupView: "Set New Photo" opens the app's own photo library straight away
            // (photos only), then the circle cropper; Remove is the badge.
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
    if (sheet != null) {
        // The sheet's own bar (as Edit Profile's): X to close, the title, Save; a drag on it pulls the sheet.
        Column(Modifier.fillMaxSize().background(c.bgSecondary)) {
            Box(Modifier.fillMaxWidth().sheetDrag(sheet).padding(top = 8.dp).height(64.dp).padding(horizontal = 12.dp)) {
                Box(Modifier.align(Alignment.CenterStart).size(44.dp).floating(CircleShape).clickable(onClickLabel = "Cancel") { onClose() },
                    contentAlignment = Alignment.Center) { Icon(ImageVector.vectorResource(R.drawable.ic_sf_xmark), null, tint = c.label) }
                Text(if (channel) "Edit Channel" else "Edit Group", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = c.label,
                    modifier = Modifier.align(Alignment.Center))
                Box(Modifier.align(Alignment.CenterEnd)) { saveAction() }
            }
            Column(Modifier.fillMaxSize().verticalScroll(barScroll).imePadding(), horizontalAlignment = Alignment.CenterHorizontally) { body() }
        }
        return
    }
    BarPage(if (channel) "Edit Channel" else "Edit Group", onBack = onClose, action = { saveAction() },
        under = underBar(barScroll), modifier = Modifier.background(c.bgSecondary)) { top ->
        Column(Modifier.fillMaxSize().verticalScroll(barScroll).padding(top = top), horizontalAlignment = Alignment.CenterHorizontally) { body() }
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
            Icon(ImageVector.vectorResource(R.drawable.ic_sf_text_alignleft), null, tint = c.label, modifier = Modifier.size(20.dp))
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

/**
 * A group's or channel's header photo with a story up (iOS dd1a37ac, owner 2026-11-08): a tap asks
 * "Select an action", View profile photo or View story, so the photo can still be seen. Returns
 * that tap, or null when there is no story or no photo (the header's tap then stays as it was).
 * Place it once on the page: it also draws the sheet, the photo and the story it opens.
 */
@Composable
internal fun headerPhotoOrStory(cid: String, name: String, photoUrl: String?, me: String,
                                conversations: List<com.fariin.app.data.Conversation>): HeaderPhotoActions? {
    val feed by com.fariin.app.data.LiveStories.state.collectAsState()
    val hidden by com.fariin.app.data.StoryMutes.set.collectAsState()
    val ring = com.fariin.app.data.StoryRings.forGroup(cid, name, photoUrl, feed, hidden)
    var showPhoto by remember { mutableStateOf(false) }
    var story by remember { mutableStateOf<com.fariin.app.data.StoryGroup?>(null) }
    if (showPhoto && !photoUrl.isNullOrEmpty())
        // As ready-made pages: no bubble on screen to zoom out of, so the plain window fade.
        com.fariin.app.ui.thread.MediaViewer(com.fariin.app.ui.thread.Viewing.Pages(
            listOf(com.fariin.app.data.ViewerRules.Page(photoUrl, null, false, photoUrl, null)), 0)) { showPhoto = false }
    story?.let { g -> com.fariin.app.ui.stories.StoryViewerDialog(me, listOf(g), 0, conversations, onClose = { story = null }) }
    // iOS 5ed8c7786 (owner 2026-11-08): no "Select an action" sheet any more. A tap opens the
    // story, a hold opens the photo.
    if (ring == null) return null
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    return HeaderPhotoActions(tap = { story = ring },
        hold = if (photoUrl.isNullOrEmpty()) null else ({
            haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); showPhoto = true }))
}

/** A group's or channel's header photo while it has a story: tap = the story, hold = the photo. */
internal class HeaderPhotoActions(val tap: () -> Unit, val hold: (() -> Unit)?)
