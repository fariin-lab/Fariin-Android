package com.fariin.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.data.CryptoHub
import com.fariin.app.data.StoryPrivacyRules
import com.fariin.app.data.StoryPrivacyStore
import com.fariin.app.data.StoryRules
import com.fariin.app.ui.theme.Fariin
import com.google.firebase.auth.FirebaseAuth

/*
 * Settings > Stories (iOS StorySettingsView, EveryonePrivacyView, MyFriendsPrivacyView,
 * StoryHiddenListPage, StoryPeoplePicker). Who sees my stories lives in users/{me}/storyLists:
 * `hiddenFrom` for Everyone, `myFriends` for My Chats (all / except / only + Allow Replies).
 */

internal const val PAGE_STORY_EVERYONE = "storiesSettings:everyone"
internal const val PAGE_STORY_MYCHATS = "storiesSettings:mychats"
/** A custom list's page: this prefix + the list id (iOS CustomStoryDetailView). */
internal const val PAGE_STORY_CUSTOM = "storiesSettings:custom:"

/** A person a story list can name: uid, name, photo. */
private data class StoryPerson(val uid: String, val name: String, val photo: String?)

/** My accepted 1:1 chats, as iOS StoryContact.all (requests and blocks either way left out). */
private fun storyPeople(): List<StoryPerson> {
    val me = FirebaseAuth.getInstance().currentUser?.uid ?: return emptyList()
    val convs = CryptoHub.listedAll()
    val ids = StoryRules.contacts(me, convs)
    return convs.filter { !it.isGroup && it.otherUid(me) in ids }
        .map { StoryPerson(it.otherUid(me), it.displayName(me), it.displayPhoto(me)) }
        .distinctBy { it.uid }.sortedBy { it.name.lowercase() }
}

/** The two audiences of the Stories page, between View Receipts and the page itself. */
@Composable
internal fun StoryAudienceRows(push: (String) -> Unit) {
    val me = FirebaseAuth.getInstance().currentUser?.uid ?: ""
    LaunchedEffect(me) { StoryPrivacyStore.start(me) }
    val myFriends by StoryPrivacyStore.myFriends.collectAsState()
    val custom by StoryPrivacyStore.custom.collectAsState()
    var creating by remember { mutableStateOf(false) }
    // iOS StorySettingsView: the same rows as Share Story, + New in the header (no One-Time or Group here).
    SettingsSection(header = "Stories", footer = "Stories disappear after 24 hours. Choose who can see them.",
        headerAction = { com.fariin.app.ui.stories.NewAudienceButton(StoryPrivacyStore.canAddCustom, onCustom = { creating = true }) }) {
        // iOS StorySettingsView: my photo on Everyone, the orange people circle on My Chats.
        val myPhoto = remember(me) { com.fariin.app.data.MyProfileRepository().listen(me) }.collectAsState(initial = null).value?.photoUrl
        AudienceBadgeRow({
            if (!myPhoto.isNullOrEmpty()) com.fariin.app.ui.components.Avatar(myPhoto, 40.dp)
            else AudienceCircle(Fariin.colors.blue, androidx.compose.material.icons.Icons.Filled.Public)
        }, "Everyone", StoryPrivacyRules.everyoneSubtitle(), last = false) { push(PAGE_STORY_EVERYONE) }
        AudienceBadgeRow({ AudienceCircle(androidx.compose.ui.graphics.Color(0xFFFF9500), androidx.compose.material.icons.Icons.Filled.People) },
            "My Chats", StoryPrivacyRules.myChatsSubtitle(myFriends), last = custom.isEmpty()) { push(PAGE_STORY_MYCHATS) }
        for ((i, l) in custom.withIndex()) {
            Row(
                Modifier.fillMaxWidth().clickable { push(PAGE_STORY_CUSTOM + l.id) }.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                com.fariin.app.ui.stories.CustomListBadge(l.emoji, 36.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(l.name, style = MaterialTheme.typography.bodyLarge, color = Fariin.colors.label, maxLines = 1)
                    Text(StoryPrivacyRules.customSubtitle(l), style = MaterialTheme.typography.bodyMedium, color = Fariin.colors.secondary, maxLines = 1)
                }
            }
            if (i != custom.lastIndex) HorizontalDivider(thickness = 0.5.dp, color = Fariin.colors.separator, modifier = Modifier.padding(start = 64.dp))
        }
    }
    if (creating) androidx.compose.ui.window.Dialog(
        onDismissRequest = { creating = false },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize()) {
            val people = remember { com.fariin.app.ui.stories.storyPeople(me, CryptoHub.listedAll()) }
            com.fariin.app.ui.stories.CreateCustomStoryFlow(people, onCreated = { creating = false }, onCancel = { creating = false })
        }
    }
}

/** A Stories audience row with its round badge (iOS StorySettingsView). */
@Composable
private fun AudienceBadgeRow(badge: @Composable () -> Unit, title: String, sub: String, last: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        badge()
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Fariin.colors.label, maxLines = 1)
            Text(sub, style = MaterialTheme.typography.bodyMedium, color = Fariin.colors.secondary, maxLines = 1)
        }
    }
    if (!last && SHOW_ROW_LINES) HorizontalDivider(thickness = 0.5.dp, color = Fariin.colors.separator, modifier = Modifier.padding(start = 68.dp))
}

@Composable
private fun AudienceCircle(color: androidx.compose.ui.graphics.Color, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Box(Modifier.size(40.dp).clip(androidx.compose.foundation.shape.CircleShape).background(color), contentAlignment = Alignment.Center) {
        androidx.compose.material3.Icon(icon, null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(22.dp))
    }
}

/** iOS CustomStoryDetailView, from Settings > Stories. */
@Composable
internal fun StoryCustomPage(id: String, pop: () -> Unit) {
    val me = FirebaseAuth.getInstance().currentUser?.uid ?: ""
    LaunchedEffect(me) { StoryPrivacyStore.start(me) }
    val people = remember { com.fariin.app.ui.stories.storyPeople(me, CryptoHub.listedAll()) }
    com.fariin.app.ui.stories.CustomStoryDetailPage(id, people, pop)
}

/** iOS EveryonePrivacyView over StoryHiddenListPage: the people my Everyone stories leave out. */
@Composable
internal fun StoryEveryonePage(pop: () -> Unit) {
    val me = FirebaseAuth.getInstance().currentUser?.uid ?: ""
    LaunchedEffect(me) { StoryPrivacyStore.start(me) }
    val hidden by StoryPrivacyStore.hidden.collectAsState()
    val people = remember { storyPeople() }
    var adding by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(setOf<String>()) }
    if (hidden.isEmpty() || adding) {
        BackHandler(enabled = adding) { adding = false; draft = emptySet() }
        StoryPeoplePicker(
            title = if (adding) "Add People" else "Everyone",
            selectedHeader = if (adding) "Selected" else "Hidden Users",
            unselectedHeader = "Everyone",
            people = people.filter { it.uid !in hidden },
            selected = draft, onToggle = { draft = if (it in draft) draft - it else draft + it },
            saveTitle = "Add", canSave = draft.isNotEmpty(),
            onBack = { if (adding) { adding = false; draft = emptySet() } else pop() },
            onSave = { StoryPrivacyStore.setHidden(draft, true); draft = emptySet(); adding = false },
        )
        return
    }
    val byId = people.associateBy { it.uid }
    val list = hidden.map { byId[it] ?: StoryPerson(it, "", null) }.sortedBy { it.name.lowercase() }
    ScrollPage("Everyone", pop) {
        SettingsSection(header = "Hidden Users") {
            Row(
                Modifier.fillMaxWidth().clickable { draft = emptySet(); adding = true }.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(Fariin.colors.fieldFill), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = Fariin.colors.label, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Text("Add People", fontSize = 17.sp, color = Fariin.colors.label)
            }
            HorizontalDivider(thickness = 0.5.dp, color = Fariin.colors.separator, modifier = Modifier.padding(start = 64.dp))
            for ((i, p) in list.withIndex()) {
                PersonRow(p.name.ifEmpty { "Fariin user" }, p.photo, last = i == list.lastIndex, trailing = {
                    IconButton(onClick = { StoryPrivacyStore.setHidden(setOf(p.uid), false) }) {
                        Icon(Icons.Filled.Close, contentDescription = "Remove ${p.name}", tint = Fariin.colors.secondary)
                    }
                }, onClick = null)
            }
        }
    }
}

/** iOS MyFriendsPrivacyView: Who Can View This Story (all / except / only) and Allow Replies. */
@Composable
internal fun StoryMyChatsPage(pop: () -> Unit) {
    val me = FirebaseAuth.getInstance().currentUser?.uid ?: ""
    LaunchedEffect(me) { StoryPrivacyStore.start(me) }
    val a by StoryPrivacyStore.myFriends.collectAsState()
    val people = remember { storyPeople() }
    var picking by remember { mutableStateOf<String?>(null) }   // MODE_EXCEPT or MODE_ONLY
    var draft by remember { mutableStateOf(setOf<String>()) }
    picking?.let { target ->
        BackHandler { picking = null }
        val except = target == StoryPrivacyRules.MODE_EXCEPT
        // Saving nobody over the list already in use turns it back to all (iOS requireAtLeastOne).
        val keepsOld = a.mode == target && a.members.isNotEmpty()
        StoryPeoplePicker(
            title = if (except) "All Except" else "Only Share With",
            selectedHeader = if (except) "Excluded" else "Selected",
            unselectedHeader = if (except) "Not Excluded" else "Not Selected",
            people = people, selected = draft, onToggle = { draft = if (it in draft) draft - it else draft + it },
            saveTitle = "Save", canSave = draft.isNotEmpty() || keepsOld,
            onBack = { picking = null },
            onSave = {
                if (draft.isNotEmpty()) StoryPrivacyStore.updateMyFriends(a.copy(mode = target, members = draft.toList()))
                else if (a.mode == target) StoryPrivacyStore.updateMyFriends(a.copy(mode = StoryPrivacyRules.MODE_ALL, members = emptyList()))
                picking = null
            },
        )
        return
    }
    ScrollPage("My Chats", pop) {
        SettingsSection(header = "Who Can View This Story",
            footer = "Choose which of your chats can view your story. Changes won't affect stories you've already sent.") {
            val rows = listOf(
                StoryPrivacyRules.MODE_ALL to "All chats you accepted",
                StoryPrivacyRules.MODE_EXCEPT to "All Except…",
                StoryPrivacyRules.MODE_ONLY to "Only Share With…",
            )
            for ((i, r) in rows.withIndex()) {
                val (m, title) = r
                ModeRow(title, StoryPrivacyRules.modeDetail(a, m, people.size), selected = a.mode == m,
                    edit = a.mode == m && m != StoryPrivacyRules.MODE_ALL, last = i == rows.lastIndex) {
                    if (m == StoryPrivacyRules.MODE_ALL) StoryPrivacyStore.updateMyFriends(a.copy(mode = m, members = emptyList()))
                    else { draft = if (a.mode == m) a.members.toSet() else emptySet(); picking = m }
                }
            }
        }
        SettingsSection(header = "Replies", footer = "Let people who can view your story reply.") {
            SwitchRow("Allow Replies", a.allowReplies, last = true) { v -> StoryPrivacyStore.updateMyFriends(a.copy(allowReplies = v)) }
        }
    }
}

@Composable
private fun ModeRow(title: String, detail: String?, selected: Boolean, edit: Boolean, last: Boolean, onClick: () -> Unit) {
    val c = Fariin.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 56.dp).padding(start = 4.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.RadioButton(selected = selected, onClick = onClick,
            colors = androidx.compose.material3.RadioButtonDefaults.colors(selectedColor = c.accent))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = c.label)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodyMedium, color = c.secondary)
        }
        if (edit) Text("Edit", color = c.accent, fontSize = 15.sp)
    }
    if (!last) HorizontalDivider(thickness = 0.5.dp, color = c.separator)
}

/**
 * iOS StoryPeoplePicker: a search field ("Name or username"), the picked people first, then the
 * rest; the save button in the bar works once something is picked.
 */
@Composable
private fun StoryPeoplePicker(
    title: String,
    selectedHeader: String,
    unselectedHeader: String,
    people: List<StoryPerson>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    saveTitle: String,
    canSave: Boolean,
    onBack: () -> Unit,
    onSave: () -> Unit,
) {
    val c = Fariin.colors
    var search by remember { mutableStateOf("") }
    val q = search.trim().lowercase()
    val shown = if (q.isEmpty()) people else people.filter { it.name.lowercase().contains(q) }
    val picked = shown.filter { it.uid in selected }
    val rest = shown.filter { it.uid !in selected }
    Column(Modifier.fillMaxSize()) {
        PageBar(title, onBack = onBack, action = {
            TextButton(onClick = onSave, enabled = canSave) { Text(saveTitle, color = if (canSave) c.accent else c.secondary, fontSize = 16.sp) }
        })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
            Box(
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(22.dp)).background(c.fieldFill).padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (search.isEmpty()) Text("Name or username", color = c.secondary, fontSize = 16.sp)
                BasicTextField(search, { search = it.take(60) }, singleLine = true,
                    textStyle = TextStyle(color = c.label, fontSize = 16.sp), cursorBrush = SolidColor(c.accent),
                    modifier = Modifier.fillMaxWidth())
            }
            if (people.isEmpty()) {
                Text("No chats yet.", color = c.secondary, modifier = Modifier.padding(20.dp))
            }
            if (picked.isNotEmpty()) SettingsSection(header = selectedHeader) {
                for ((i, p) in picked.withIndex()) PersonRow(p.name.ifEmpty { "Fariin user" }, p.photo, last = i == picked.lastIndex, selected = true) { onToggle(p.uid) }
            }
            if (rest.isNotEmpty()) SettingsSection(header = unselectedHeader) {
                for ((i, p) in rest.withIndex()) PersonRow(p.name.ifEmpty { "Fariin user" }, p.photo, last = i == rest.lastIndex) { onToggle(p.uid) }
            }
            Spacer(Modifier.padding(bottom = com.fariin.app.ui.shell.bottomBarInset()))
        }
    }
}
