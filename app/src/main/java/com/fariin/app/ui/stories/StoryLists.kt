package com.fariin.app.ui.stories

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.R
import com.fariin.app.data.Conversation
import com.fariin.app.data.Story
import com.fariin.app.data.StoryAudienceChoice
import com.fariin.app.data.StoryListDoc
import com.fariin.app.data.StoryPrivacyRules
import com.fariin.app.data.StoryPrivacyStore
import com.fariin.app.data.StoryRules
import com.fariin.app.ui.components.Avatar
import com.fariin.app.ui.settings.PageBar
import com.fariin.app.ui.settings.PersonRow
import com.fariin.app.ui.settings.SettingsSection
import com.fariin.app.ui.settings.SwitchRow
import com.fariin.app.ui.theme.Fariin

/*
 * Custom story lists (iOS StoryAudienceViews: SelectViewersView, NameStoryView, CustomStoryEmojiHeader,
 * CustomStoryDetailView, CustomStoryEditView, CreateCustomStoryFlow, NewAudienceButton) and the
 * Share Story audience page (iOS ShareStorySheet standardBody), used for a new post and for Edit
 * Viewers. Lists live in users/{me}/storyLists/{UUID} through StoryPrivacyStore.
 */

/** A person a story list can name: one of my accepted 1:1 chats (iOS StoryContact). */
data class StoryPerson(val uid: String, val name: String, val photo: String?)

fun storyPeople(me: String, convs: List<Conversation>): List<StoryPerson> {
    val ids = StoryRules.contacts(me, convs)
    return convs.filter { !it.isGroup && it.otherUid(me) in ids }
        .map { StoryPerson(it.otherUid(me), it.displayName(me), it.displayPhoto(me)) }
        .distinctBy { it.uid }.sortedBy { it.name.lowercase() }
}

/**
 * The chats a story audience is drawn from: the chat list's and every listed 1:1 (an accepted chat
 * with no messages yet is still a chat, as iOS StoryContact.all reads the whole repository).
 */
fun storyConvs(convs: List<Conversation>): List<Conversation> = (convs + com.fariin.app.data.CryptoHub.listedAll()).distinctBy { it.id }

private const val SELECTED_KEY = "storyAudienceSelected"

/** The page's own background, so an overlay covers what is under it. */
@Composable
private fun Page(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Fariin.colors.bgSecondary).navigationBarsPadding()) { content() }
}

@Composable
private fun BarButton(title: String, enabled: Boolean, onClick: () -> Unit) {
    val c = Fariin.colors
    TextButton(onClick = onClick, enabled = enabled) {
        Text(title, color = if (enabled) c.accent else c.secondary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** A custom list's picture: its emoji, or the folder drawing (iOS CustomStoryEmojiHeader / badge). */
@Composable
fun CustomListBadge(emoji: String, size: Dp, onGrey: Boolean = true) {
    Box(Modifier.size(size).clip(CircleShape).background(if (onGrey) Color.Gray else Fariin.colors.card), contentAlignment = Alignment.Center) {
        if (emoji.isNotEmpty()) Text(emoji, fontSize = (size.value * 0.5f).sp)
        else Icon(painterResource(R.drawable.ic_story_folder_fill), contentDescription = null,
            tint = if (onGrey) Color.White else Fariin.colors.secondary, modifier = Modifier.size(size * 0.5f))
    }
}

/** The big circle on top of the create, detail and edit pages, with Change Emoji where it can change. */
@Composable
private fun EmojiHeader(emoji: String, title: String? = null, onChange: (() -> Unit)? = null) {
    val c = Fariin.colors
    Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CustomListBadge(emoji, 112.dp, onGrey = false)
        if (!title.isNullOrEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(title, color = c.label, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 24.dp))
        }
        if (onChange != null) TextButton(onClick = onChange) { Text("Change Emoji", color = c.accent, fontSize = 17.sp, fontWeight = FontWeight.Medium) }
    }
}

private val EMOJIS = listOf(
    "😀", "😂", "😍", "😎", "🤩", "😇", "😜", "🤗", "😴", "🤔",
    "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "💔", "💯", "🔥", "✨",
    "⭐", "🌙", "☀️", "🌈", "🌸", "🌹", "🌻", "🍀", "🌴", "🌊", "⛰️", "🏝️",
    "🏠", "🏫", "💼", "🎓", "📚", "✈️", "🚗", "⚽", "🏀", "🎮", "🎧", "🎵",
    "🎉", "🎁", "🎂", "☕", "🍕", "🍔", "🍰", "🍉", "👪", "👫", "👭", "👬",
    "👶", "🧒", "👩", "👨", "👵", "👴", "🐶", "🐱", "🦁", "🐪", "🐘", "🦋",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmojiSheet(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Fariin.colors.card) {
        LazyVerticalGrid(GridCells.Adaptive(52.dp), Modifier.fillMaxWidth().heightIn(max = 420.dp).padding(horizontal = 12.dp, vertical = 8.dp)) {
            items(EMOJIS) { e ->
                Box(Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).clickable { onPick(e) }, contentAlignment = Alignment.Center) {
                    Text(e, fontSize = 30.sp)
                }
            }
        }
    }
}

/** iOS StoryTick: a filled tick when on, an empty ring when not. */
@Composable
private fun Tick(on: Boolean) {
    val c = Fariin.colors
    Icon(if (on) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, contentDescription = if (on) "Selected" else null,
        tint = if (on) c.blue else c.secondary)
}

/**
 * iOS SelectViewersView: the people by first letter, a tick each, the picked ones as chips on top,
 * "Name or username" search, and the action in the bar (off while nothing is ticked when
 * [requiresSelection]).
 */
@Composable
fun SelectViewersPage(
    title: String,
    actionTitle: String,
    people: List<StoryPerson>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onAction: () -> Unit,
    onCancel: () -> Unit,
    alreadyIn: Set<String> = emptySet(),
    requiresSelection: Boolean = true,
) {
    val c = Fariin.colors
    var search by remember { mutableStateOf("") }
    BackHandler { onCancel() }
    val pool = people.filter { it.uid !in alreadyIn }
    val q = search.trim().lowercase()
    val visible = if (q.isEmpty()) pool else pool.filter { it.name.lowercase().contains(q) }
    val sections = StoryPrivacyRules.letterSections(visible) { it.name }
    Page {
        Column(Modifier.fillMaxSize()) {
            PageBar(title, onBack = onCancel, action = { BarButton(actionTitle, !requiresSelection || selected.isNotEmpty(), onAction) })
            val chosen = people.filter { it.uid in selected }
            if (chosen.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                chosen.forEach { p ->
                    Row(Modifier.clip(CircleShape).background(c.card).clickable { onToggle(p.uid) }.padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Avatar(p.photo, 26.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(p.name.ifEmpty { "Fariin user" }, color = c.label, fontSize = 14.sp, maxLines = 1)
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Filled.Close, contentDescription = "Remove", tint = c.secondary, modifier = Modifier.size(14.dp))
                    }
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = com.fariin.app.ui.shell.bottomBarInset())) {
                for ((letter, group) in sections) {
                    SettingsSection(header = letter) {
                        for ((i, p) in group.withIndex()) {
                            PersonRow(p.name.ifEmpty { "Fariin user" }, p.photo, last = i == group.lastIndex,
                                trailing = { Tick(p.uid in selected) }) { onToggle(p.uid) }
                        }
                    }
                }
                if (visible.isEmpty()) SettingsSection {
                    Text(if (pool.isEmpty()) "You have no chats yet. Start a chat with someone and they can be added here." else "No one matches that.",
                        color = c.secondary, fontSize = 16.sp, modifier = Modifier.padding(16.dp))
                }
                Spacer(Modifier.height(12.dp))
            }
            // iOS keeps the search field at the bottom.
            Box(
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(22.dp)).background(c.fieldFill).padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (search.isEmpty()) Text("Name or username", color = c.secondary, fontSize = 16.sp)
                BasicTextField(search, { search = it.take(60) }, singleLine = true,
                    textStyle = TextStyle(color = c.label, fontSize = 16.sp), cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** The name field of the create and edit pages, clamped at 25 as it is typed. */
@Composable
private fun NameField(name: String, onName: (String) -> Unit, focus: FocusRequester) {
    val c = Fariin.colors
    SettingsSection(footer = StoryPrivacyRules.nameFooter(name)) {
        Box(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
            if (name.isEmpty()) Text("Story Name (Required)", color = c.secondary, fontSize = 17.sp)
            BasicTextField(name, { onName(StoryPrivacyRules.clampName(it)) }, singleLine = true,
                textStyle = TextStyle(color = c.label, fontSize = 17.sp), cursorBrush = SolidColor(c.accent),
                modifier = Modifier.fillMaxWidth().focusRequester(focus))
        }
    }
}

/** iOS NameStoryView: the emoji, the name, Allow Replies, and the viewers; Create in the bar. */
@Composable
private fun NameStoryPage(
    viewers: List<StoryPerson>,
    onBack: () -> Unit,
    onCreate: (name: String, allowReplies: Boolean, emoji: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var replies by remember { mutableStateOf(true) }
    var emoji by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    BackHandler { onBack() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Page {
        Column(Modifier.fillMaxSize()) {
            PageBar("Choose name & emoji", onBack = onBack, action = {
                BarButton("Create", name.trim().isNotEmpty()) { onCreate(name, replies, emoji) }
            })
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = com.fariin.app.ui.shell.bottomBarInset())) {
                EmojiHeader(emoji, onChange = { picking = true })
                NameField(name, { name = it }, focus)
                SettingsSection(header = "Replies", footer = "Let people who can view your story reply.") {
                    SwitchRow("Allow Replies", replies, last = true) { replies = it }
                }
                SettingsSection(header = "Viewers") {
                    for ((i, p) in viewers.withIndex()) PersonRow(p.name.ifEmpty { "Fariin user" }, p.photo, last = i == viewers.lastIndex, onClick = null)
                }
            }
        }
    }
    if (picking) EmojiSheet(onPick = { emoji = it; picking = false }, onDismiss = { picking = false })
}

/**
 * iOS CreateCustomStoryFlow: Select Viewers > Choose name & emoji > Create. The new list is handed
 * back so the Share Story page can tick it at once.
 */
@Composable
fun CreateCustomStoryFlow(people: List<StoryPerson>, onCreated: (StoryListDoc) -> Unit, onCancel: () -> Unit) {
    var selected by remember { mutableStateOf(setOf<String>()) }
    var naming by remember { mutableStateOf(false) }
    if (!naming) {
        SelectViewersPage("Select Viewers", "Next", people, selected,
            onToggle = { selected = if (it in selected) selected - it else selected + it },
            onAction = { naming = true }, onCancel = onCancel)
    } else {
        NameStoryPage(people.filter { it.uid in selected }, onBack = { naming = false }) { name, replies, emoji ->
            onCreated(StoryPrivacyStore.createCustom(name, selected.sorted(), replies, emoji))
        }
    }
}

/**
 * iOS CustomStoryDetailView: the emoji and name, Edit in the bar, Replies, Delete Custom Story, and
 * Who Can View This Story with Add Viewers and a remove on each viewer.
 */
@Composable
fun CustomStoryDetailPage(id: String, people: List<StoryPerson>, onBack: () -> Unit) {
    val c = Fariin.colors
    val lists by StoryPrivacyStore.custom.collectAsState()
    val a = lists.firstOrNull { it.id == id }
    var editing by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var addSel by remember { mutableStateOf(setOf<String>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    if (a == null) {
        // Deleted elsewhere while it was open: nothing to manage.
        LaunchedEffect(Unit) { onBack() }
        return
    }
    if (editing) { CustomStoryEditPage(a) { editing = false }; return }
    if (adding) {
        SelectViewersPage("Add Viewers", "Add", people, addSel,
            onToggle = { addSel = if (it in addSel) addSel - it else addSel + it },
            onAction = { StoryPrivacyStore.updateCustom(a.copy(members = a.members + (addSel - a.members.toSet()))); adding = false },
            onCancel = { adding = false }, alreadyIn = a.members.toSet())
        return
    }
    BackHandler { onBack() }
    val byId = people.associateBy { it.uid }
    val viewers = a.members.mapNotNull { byId[it] }
    Page {
        Column(Modifier.fillMaxSize()) {
            PageBar("", onBack = onBack, action = { BarButton("Edit", true) { editing = true } })
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = com.fariin.app.ui.shell.bottomBarInset())) {
                EmojiHeader(a.emoji, title = a.name)
                SettingsSection(header = "Replies", footer = "Let people who can view your story reply.") {
                    SwitchRow("Allow Replies", a.allowReplies, last = true) { v -> StoryPrivacyStore.updateCustom(a.copy(allowReplies = v)) }
                }
                SettingsSection {
                    Text("Delete Custom Story", color = c.red, fontSize = 17.sp,
                        modifier = Modifier.fillMaxWidth().clickable { confirmDelete = true }.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 16.dp))
                }
                SettingsSection(header = "Who Can View This Story",
                    footer = "Choose which of your chats can view your story. Changes won't affect stories you've already sent.") {
                    Row(
                        Modifier.fillMaxWidth().clickable { addSel = emptySet(); adding = true }.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(36.dp).clip(CircleShape).background(c.fieldFill), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Add, contentDescription = null, tint = c.label, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Text("Add Viewers", fontSize = 17.sp, color = c.label)
                    }
                    if (viewers.isNotEmpty()) HorizontalDivider(thickness = 0.5.dp, color = c.separator, modifier = Modifier.padding(start = 64.dp))
                    for ((i, p) in viewers.withIndex()) {
                        PersonRow(p.name.ifEmpty { "Fariin user" }, p.photo, last = i == viewers.lastIndex, trailing = {
                            IconButton(onClick = { StoryPrivacyStore.updateCustom(a.copy(members = a.members - p.uid)) }) {
                                Icon(Icons.Filled.Close, contentDescription = "Remove ${p.name}", tint = c.secondary)
                            }
                        }, onClick = null)
                    }
                }
            }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete \"${a.name}\"?") },
        text = { Text("The list goes. Stories you already sent to it are not affected and still expire on their own.") },
        confirmButton = { TextButton({ confirmDelete = false; StoryPrivacyStore.deleteCustom(a.id); onBack() }) { Text("Delete", color = c.red) } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } },
    )
}

/** iOS CustomStoryEditView: emoji and name as a draft; Done saves (off while empty or unchanged), Back leaves. */
@Composable
private fun CustomStoryEditPage(a: StoryListDoc, onBack: () -> Unit) {
    var name by remember { mutableStateOf(a.name) }
    var emoji by remember { mutableStateOf(a.emoji) }
    var picking by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val trimmed = name.trim().take(StoryPrivacyRules.NAME_LIMIT)
    val changed = trimmed != a.name || emoji != a.emoji
    BackHandler { onBack() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Page {
        Column(Modifier.fillMaxSize()) {
            PageBar("Edit", onBack = onBack, action = {
                BarButton("Done", trimmed.isNotEmpty() && changed) { StoryPrivacyStore.updateCustom(a.copy(name = trimmed, emoji = emoji)); onBack() }
            })
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = com.fariin.app.ui.shell.bottomBarInset())) {
                EmojiHeader(emoji, onChange = { picking = true })
                NameField(name, { name = it }, focus)
            }
        }
    }
    if (picking) EmojiSheet(onPick = { emoji = it; picking = false }, onDismiss = { picking = false })
}

/** One two-line item of the + New menu (iOS UIAction title + subtitle). */
@Composable
private fun NewItem(title: String, subtitle: String, icon: Painter, enabled: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Column {
                Text(title, fontSize = 16.sp)
                Text(subtitle, fontSize = 13.sp, color = Fariin.colors.secondary)
            }
        },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp)) },
        enabled = enabled, onClick = onClick,
    )
}

/**
 * iOS NewAudienceButton: the "+ New" pill and its menu, the same on Share Story and Settings: New
 * Custom Story (dimmed at 5 lists), New Group Story and One-Time Story (dimmed where they cannot be used).
 */
@Composable
fun NewAudienceButton(canAddCustom: Boolean, onCustom: () -> Unit, onOneTime: (() -> Unit)? = null, onGroup: (() -> Unit)? = null) {
    val c = Fariin.colors
    var open by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.clip(CircleShape).background(c.card).clickable { open = true }.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = c.label, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text("New", color = c.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            NewItem("New Custom Story", "Visible only to specific people.", painterResource(R.drawable.ic_story_folder), canAddCustom) { open = false; onCustom() }
            NewItem("New Group Story", "Share to the members of a group you run.", rememberVectorPainter(Icons.Outlined.Groups), onGroup != null) { open = false; onGroup?.invoke() }
            NewItem("One-Time Story", "Can only be viewed once by each recipient.", rememberVectorPainter(Icons.Outlined.LocalFireDepartment), onOneTime != null) { open = false; onOneTime?.invoke() }
        }
    }
}

/** An audience row: its badge, title and grey line, and the tick (iOS StoryAudienceRow). */
@Composable
private fun AudienceRow(badge: @Composable () -> Unit, title: String, subtitle: String, on: Boolean, last: Boolean, onClick: () -> Unit) {
    val c = Fariin.colors
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 60.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        badge()
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = c.label, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = c.secondary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Tick(on)
    }
    if (!last) HorizontalDivider(thickness = 0.5.dp, color = c.separator, modifier = Modifier.padding(start = 68.dp))
}

@Composable
private fun CircleBadge(color: Color, icon: Painter) {
    Box(Modifier.size(40.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

/**
 * The Share Story page (iOS ShareStorySheet): "Who can see your story" with + New, Everyone, My
 * Chats and my custom lists (lists tick together), the One-Time row while one is being made, Block
 * screenshots, then Post Story. With [editing] it is Edit Viewers: no + New, no Block screenshots,
 * the story's audience ticked, and the button says Update.
 */
@Composable
fun ShareStoryAudiencePage(
    me: String,
    conversations: List<Conversation>,
    myPhoto: String? = null,
    editing: Story? = null,
    busy: Boolean = false,
    onBack: () -> Unit,
    onPost: (choice: StoryAudienceChoice, oneTimePeople: Set<String>, lists: List<StoryListDoc>, captureProtected: Boolean) -> Unit,
) {
    val c = Fariin.colors
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("fariin_stories", android.content.Context.MODE_PRIVATE) }
    LaunchedEffect(me) { StoryPrivacyStore.start(me) }
    val myFriends by StoryPrivacyStore.myFriends.collectAsState()
    val glowersList by StoryPrivacyStore.glowers.collectAsState()
    val glow by com.fariin.app.data.Glow.state.collectAsState()
    // Glowers is offered only with Glow on (iOS StoryAudienceStore.all).
    val glowOn = com.fariin.app.data.AppFeatures.glow
    val custom by StoryPrivacyStore.custom.collectAsState()
    val links by StoryPrivacyStore.links.collectAsState()
    val people = remember(conversations) { storyPeople(me, storyConvs(conversations)) }
    // What is ticked: Everyone, My Chats, or one or more lists (iOS chosenId + multiCustom).
    var choice by remember {
        mutableStateOf(
            when {
                editing?.audienceLabel == "everyone" -> StoryAudienceChoice.EVERYONE
                // A Glowers story reopens on Glowers (iOS seedFromStory), never moved to My Chats by Update.
                editing?.audienceLabel == "glowers" -> StoryAudienceChoice.GLOWERS
                editing?.audienceLabel == "custom" -> StoryAudienceChoice.CUSTOM
                editing != null -> StoryAudienceChoice.MY_CHATS
                else -> when (val saved = runCatching { prefs.getString(SELECTED_KEY, null) }.getOrNull()) {
                    "everyone" -> StoryAudienceChoice.EVERYONE
                    StoryPrivacyRules.GLOWERS_ID -> if (com.fariin.app.data.AppFeatures.glow) StoryAudienceChoice.GLOWERS else StoryAudienceChoice.MY_CHATS
                    null, "myFriends" -> StoryAudienceChoice.MY_CHATS
                    else -> if (StoryPrivacyStore.custom.value.any { it.id == saved }) StoryAudienceChoice.CUSTOM else StoryAudienceChoice.MY_CHATS
                }
            },
        )
    }
    var ticked by remember {
        mutableStateOf(
            when {
                editing?.audienceLabel == "custom" -> StoryPrivacyStore.links.value[editing.id]?.listIds?.toSet() ?: emptySet()
                editing == null -> runCatching { prefs.getString(SELECTED_KEY, null) }.getOrNull()
                    ?.takeIf { id -> StoryPrivacyStore.custom.value.any { it.id == id } }?.let { setOf(it) } ?: emptySet()
                else -> emptySet()
            },
        )
    }
    var oneTime by remember { mutableStateOf(setOf<String>()) }
    var blockShots by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var oneTimePicking by remember { mutableStateOf(false) }
    // A ticked list deleted elsewhere drops out; the last one gone falls back to My Chats.
    val liveTicked = ticked.filter { id -> custom.any { it.id == id } }.toSet()
    if (choice == StoryAudienceChoice.CUSTOM && liveTicked.isEmpty() && custom.isNotEmpty() && ticked.isNotEmpty()) {
        LaunchedEffect(Unit) { choice = StoryAudienceChoice.MY_CHATS; ticked = emptySet() }
    }
    val oneTimeActive = choice == StoryAudienceChoice.ONE_TIME && oneTime.isNotEmpty()

    if (creating) {
        CreateCustomStoryFlow(people, onCreated = { l ->
            oneTime = emptySet()
            ticked = if (choice == StoryAudienceChoice.CUSTOM) liveTicked + l.id else setOf(l.id)
            choice = StoryAudienceChoice.CUSTOM
            creating = false
        }, onCancel = { creating = false })
        return
    }
    if (oneTimePicking) {
        var draft by remember { mutableStateOf(oneTime) }
        SelectViewersPage("One-Time Story", "Done", people, draft,
            onToggle = { draft = if (it in draft) draft - it else draft + it },
            onAction = { oneTime = draft; choice = StoryAudienceChoice.ONE_TIME; ticked = emptySet(); oneTimePicking = false },
            onCancel = { if (choice == StoryAudienceChoice.ONE_TIME) { oneTime = emptySet(); choice = StoryAudienceChoice.MY_CHATS }; oneTimePicking = false })
        return
    }
    BackHandler { onBack() }

    fun tapBuiltIn(ch: StoryAudienceChoice) { oneTime = emptySet(); ticked = emptySet(); choice = ch }
    fun tapList(id: String) {
        oneTime = emptySet()
        val now = StoryPrivacyRules.toggleList(if (choice == StoryAudienceChoice.CUSTOM) liveTicked else emptySet(), id)
        ticked = now
        choice = if (now.isEmpty()) StoryAudienceChoice.MY_CHATS else StoryAudienceChoice.CUSTOM
    }
    val chosenLists = custom.filter { it.id in liveTicked }
    val ready = when (choice) {
        StoryAudienceChoice.ONE_TIME -> oneTime.isNotEmpty()
        StoryAudienceChoice.CUSTOM -> chosenLists.isNotEmpty()
        else -> true
    } && !busy

    Page {
        Column(Modifier.fillMaxSize()) {
            PageBar("Share Story", onBack = onBack)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = com.fariin.app.ui.shell.bottomBarInset())) {
                val footer = when {
                    oneTimeActive -> StoryAudienceChoice.ONE_TIME.footer
                    choice == StoryAudienceChoice.CUSTOM && chosenLists.size > 1 -> "Only the people in these lists can watch it."
                    choice == StoryAudienceChoice.EVERYONE -> StoryAudienceChoice.EVERYONE.footer
                    else -> "Only the people in this list can watch it."
                }
                SettingsSection(header = "Who can see your story", footer = footer,
                    headerAction = if (editing == null) {
                        { NewAudienceButton(StoryPrivacyStore.canAddCustom, onCustom = { creating = true }, onOneTime = { oneTimePicking = true }) }
                    } else null) {
                    val rows = 2 + custom.size + (if (oneTimeActive) 1 else 0) + (if (glowOn) 1 else 0)
                    var n = 0
                    if (oneTimeActive) {
                        n++
                        AudienceRow({ CircleBadge(Color(0xFFFF9500), rememberVectorPainter(Icons.Filled.LocalFireDepartment)) }, "One-Time Story",
                            if (oneTime.size == 1) "1 person · viewed once" else "${oneTime.size} people · viewed once",
                            on = true, last = n == rows) { oneTimePicking = true }
                    }
                    n++
                    AudienceRow({ if (myPhoto != null) Avatar(myPhoto, 40.dp) else CircleBadge(c.blue, rememberVectorPainter(Icons.Filled.Public)) },
                        "Everyone", StoryPrivacyRules.everyoneSubtitle(), on = choice == StoryAudienceChoice.EVERYONE, last = n == rows) {
                        tapBuiltIn(StoryAudienceChoice.EVERYONE)
                    }
                    n++
                    AudienceRow({ CircleBadge(Color(0xFFFF9500), rememberVectorPainter(Icons.Filled.People)) },
                        "My Chats", StoryPrivacyRules.myChatsSubtitle(myFriends), on = choice == StoryAudienceChoice.MY_CHATS, last = n == rows) {
                        tapBuiltIn(StoryAudienceChoice.MY_CHATS)
                    }
                    if (glowOn) {
                        n++
                        // iOS StoryAudienceBadge: the Glow mark on the label colour, drawn in the page colour.
                        AudienceRow({
                            Box(Modifier.size(40.dp).clip(CircleShape).background(c.label), contentAlignment = Alignment.Center) {
                                com.fariin.app.ui.glow.GlowMark(23.dp, tint = c.bg)
                            }
                        }, "Glowers", StoryPrivacyRules.glowersSubtitle(glowersList, glow.relationship),
                            on = choice == StoryAudienceChoice.GLOWERS, last = n == rows) {
                            tapBuiltIn(StoryAudienceChoice.GLOWERS)
                        }
                    }
                    for (l in custom) {
                        n++
                        AudienceRow({ CustomListBadge(l.emoji, 40.dp) }, l.name, StoryPrivacyRules.customSubtitle(l),
                            on = choice == StoryAudienceChoice.CUSTOM && l.id in liveTicked, last = n == rows) { tapList(l.id) }
                    }
                }
                if (editing == null) {
                    val lockOn = choice == StoryAudienceChoice.ONE_TIME
                    SettingsSection(footer = StoryPrivacyRules.captureFooter(choice)) {
                        SwitchRow("Block screenshots", StoryPrivacyRules.captureProtected(choice, blockShots), enabled = !lockOn, last = true) {
                            if (!lockOn) blockShots = it
                        }
                    }
                }
            }
            Text(
                if (editing == null) "Post Story" else "Update", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(if (ready) c.accent else c.accent.copy(alpha = 0.4f))
                    .clickable(enabled = ready) {
                        // Remembered between posts, as iOS storyAudienceSelected.
                        if (editing == null) runCatching {
                            prefs.edit().putString(SELECTED_KEY, when (choice) {
                                StoryAudienceChoice.EVERYONE -> "everyone"
                                StoryAudienceChoice.GLOWERS -> StoryPrivacyRules.GLOWERS_ID
                                StoryAudienceChoice.CUSTOM -> chosenLists.firstOrNull()?.id ?: "myFriends"
                                else -> "myFriends"
                            }).apply()
                        }
                        onPost(choice, if (choice == StoryAudienceChoice.ONE_TIME) oneTime else emptySet(), chosenLists,
                            StoryPrivacyRules.captureProtected(choice, blockShots))
                    }
                    .padding(vertical = 14.dp),
            )
        }
    }
}
