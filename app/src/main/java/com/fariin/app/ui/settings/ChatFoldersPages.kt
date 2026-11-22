package com.fariin.app.ui.settings

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.R
import com.fariin.app.data.ChatFolder
import com.fariin.app.data.ChatFoldersStore
import com.fariin.app.data.Conversation
import com.fariin.app.ui.components.Avatar
import com.fariin.app.ui.components.GlassAlert
import com.fariin.app.ui.components.cachedVector
import com.fariin.app.ui.theme.Fariin

internal const val PAGE_CHAT_FOLDERS = "chats:folders"

/**
 * Settings > Chats > Chat Folders (Fariin Plus `chatFolders`; iOS ChatFoldersPage): the folders in
 * their order with Create a Folder, the suggestions, and the editor. Locked shows the way to Plus;
 * folders already made are kept. Leaves on its own when Plus goes away for this account.
 */
@Composable
internal fun ChatFoldersPage(pop: () -> Unit) {
    val c = Fariin.colors
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { ChatFoldersStore.refresh(ctx, maxAgeMs = 30_000) }
    LaunchedEffect(ChatFoldersStore.settingsShown) { if (!ChatFoldersStore.settingsShown) pop() }
    var editing by remember { mutableStateOf<ChatFolder?>(null) }
    var creating by remember { mutableStateOf(false) }
    var plusPage by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ChatFolder?>(null) }
    var ordering by remember { mutableStateOf(false) }
    if (creating || editing != null) {
        ChatFolderEditor(editing, onClose = { creating = false; editing = null })
        return
    }
    val on = ChatFoldersStore.featureOn
    val rows = ChatFoldersStore.folders.ifEmpty { listOf(ChatFolder.ALL) }
    ScrollPage("Chat Folders", pop, action = if (on && rows.size > 1) ({
        com.fariin.app.ui.components.FloatingTextAction(if (ordering) "Done" else "Edit") { ordering = !ordering }
    }) else null) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(ImageVector.cachedVector(R.drawable.ic_album), null, tint = PlusBlue, modifier = Modifier.size(44.dp))
            Spacer(Modifier.height(10.dp))
            Text("Sort your chats into folders and switch between them at the top of your chat list.",
                fontSize = 14.sp, color = c.secondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        if (!on) SettingsSection(footer = "Chat folders come with Fariin Plus. Folders you already made are kept and come back with it.") {
            SettingsRow("Fariin Plus", icon = androidx.compose.ui.res.painterResource(R.drawable.ic_plus_badge), iconTint = PlusBlue, last = true) { plusPage = true }
        }
        SettingsSection(header = "Folders", footer = if (on && rows.size > 1) "Tap Edit to put the folders in the order you want on your chat list." else null) {
            // Locked: the row stays and its tap opens Fariin Plus (iOS ChatFoldersView, starred row).
            val locked = !on && com.fariin.app.data.PlusStore.state("chatFolders") == com.fariin.app.data.PlusStore.State.LOCKED
            if (on || locked) SettingsRow("Create a Folder", titleColor = if (locked || ChatFoldersStore.canAddFolder) c.accent else c.secondary,
                trailing = Trailing.NONE) { if (locked) plusPage = true else if (ChatFoldersStore.canAddFolder) creating = true }
            val custom = rows.filter { !it.isAll }
            rows.forEachIndexed { i, f ->
                val last = i == rows.lastIndex
                if (ordering && !f.isAll) {
                    val at = custom.indexOf(f)
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("−", fontSize = 22.sp, color = c.red, modifier = Modifier.clip(CircleShape).clickable { pendingDelete = f }.padding(horizontal = 10.dp))
                        Text(f.name, fontSize = 17.sp, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 6.dp))
                        Text("↑", fontSize = 20.sp, color = if (at > 0) c.label else c.separator,
                            modifier = Modifier.clip(CircleShape).clickable(enabled = at > 0) { ChatFoldersStore.move(at, at - 1) }.padding(10.dp))
                        Text("↓", fontSize = 20.sp, color = if (at < custom.lastIndex) c.label else c.separator,
                            modifier = Modifier.clip(CircleShape).clickable(enabled = at < custom.lastIndex) { ChatFoldersStore.move(at, at + 1) }.padding(10.dp))
                    }
                } else SettingsRow(f.name, subtitle = f.summary, last = last,
                    trailing = if (!f.isAll && on) Trailing.CHEVRON else Trailing.NONE,
                    onClick = if (!f.isAll && on) ({ editing = f }) else null)
            }
        }
        val suggestions = ChatFoldersStore.missingSuggestions.filter { it.id != "groups" || com.fariin.app.data.AppFeatures.groups }
        if (on && suggestions.isNotEmpty() && ChatFoldersStore.canAddFolder) SettingsSection(header = "Suggested") {
            suggestions.forEachIndexed { i, s ->
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name, fontSize = 17.sp, color = c.label)
                        Text(s.summary, fontSize = 13.sp, color = c.secondary)
                    }
                    Text("Add", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.accent,
                        modifier = Modifier.clip(CircleShape).clickable { ChatFoldersStore.addSuggestion(s) }.padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
        }
    }
    if (plusPage) com.fariin.app.ui.settings.PlusGateSheet("chatFolders") { plusPage = false }
    pendingDelete?.let { f ->
        GlassAlert(onDismissRequest = { pendingDelete = null }, title = { Text("Delete Folder?") },
            text = { Text("The chats stay in your chat list. Only the folder “${f.name}” goes.") },
            confirmButton = { TextButton({ ChatFoldersStore.remove(f.id); pendingDelete = null }) { Text("Delete", color = c.red) } },
            dismissButton = { TextButton({ pendingDelete = null }) { Text("Cancel", color = c.accent) } }, containerColor = c.card)
    }
    ChatFoldersStore.saveError?.let { e ->
        GlassAlert(onDismissRequest = { ChatFoldersStore.saveError = null }, title = { Text("Couldn’t Save") }, text = { Text(e) },
            confirmButton = { TextButton({ ChatFoldersStore.saveError = null }) { Text("OK", color = c.accent) } }, containerColor = c.card)
    }
}

/**
 * Create or edit a folder (iOS ChatFolderEditor): the name, Included Chats (types and single chats),
 * Exceptions, the two switches, Delete Folder. Save only after a change; Back with changes asks first.
 * [prefill]: chats already picked for a new folder (New Folder from a chat).
 */
@Composable
internal fun ChatFolderEditor(original: ChatFolder?, prefill: List<String> = emptyList(), onClose: () -> Unit) {
    val c = Fariin.colors
    val me = com.fariin.app.Fb.auth.currentUser?.uid.orEmpty()
    val initial = remember { original?.normalized() ?: ChatFolder(ChatFolder.newId(), "", "custom", include = prefill.take(ChatFolder.CHATS_MAX)) }
    var draft by remember { mutableStateOf(initial) }
    var picking by remember { mutableStateOf<String?>(null) }   // "include" | "exclude"
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val clean = ChatFolder.cleanName(draft.name)
    val dirty = draft != initial
    val canSave = clean.isNotEmpty() && (draft.types.isNotEmpty() || draft.include.isNotEmpty()) && ChatFoldersStore.featureOn &&
        (original == null || dirty)
    val close = { if (dirty) confirmDiscard = true else onClose() }
    androidx.activity.compose.BackHandler(onBack = close)
    picking?.let { target ->
        ChatFolderChatPicker(if (target == "include") "Add Chats" else "Exclude Chats", showTypes = target == "include",
            types = draft.types.toSet(), chats = if (target == "include") draft.include else draft.exclude,
            onCancel = { picking = null }) { types, chats ->
            draft = if (target == "include") draft.copy(types = ChatFolder.PICKABLE.filter { it in types }, include = chats, exclude = draft.exclude - chats.toSet())
                else draft.copy(exclude = chats, include = draft.include - chats.toSet())
            picking = null
        }
        return
    }
    ScrollPage(if (original == null) "New Folder" else "Edit Folder", close, action = {
        com.fariin.app.ui.components.FloatingTextAction("Save", enabled = canSave) {
            ChatFoldersStore.upsert(draft.copy(name = clean, kind = "custom")); onClose()
        }
    }) {
        SettingsSection(header = "Folder Name") {
            Box(Modifier.fillMaxWidth().padding(16.dp)) {
                if (draft.name.isEmpty()) Text("Folder Name", fontSize = 17.sp, color = c.secondary)
                BasicTextField(draft.name, { v -> draft = draft.copy(name = v.replace('\n', ' ').take(ChatFolder.NAME_RAW_MAX)) },
                    singleLine = true, textStyle = TextStyle(fontSize = 17.sp, color = c.label), cursorBrush = SolidColor(c.accent),
                    modifier = Modifier.fillMaxWidth())
            }
        }
        SettingsSection(header = "Included Chats", footer = "Choose types of chats, or single chats, that show in this folder.") {
            SettingsRow("Add Chats", titleColor = c.accent, trailing = Trailing.NONE, last = draft.types.isEmpty() && draft.include.isEmpty()) { picking = "include" }
            draft.types.forEachIndexed { i, t ->
                RemovableRow(ChatFolder.typeName(t), null, false, last = i == draft.types.lastIndex && draft.include.isEmpty()) {
                    draft = draft.copy(types = draft.types - t)
                }
            }
            draft.include.forEachIndexed { i, cid ->
                val conv = com.fariin.app.data.CryptoHub.listedConversation(cid)
                RemovableRow(conv?.displayName(me) ?: "Chat", conv?.displayPhoto(me), conv?.isChannel == true, last = i == draft.include.lastIndex) {
                    draft = draft.copy(include = draft.include - cid)
                }
            }
        }
        SettingsSection(header = "Exceptions", footer = "Chats here never show in this folder.") {
            SettingsRow("Exclude Chats", titleColor = c.accent, trailing = Trailing.NONE, last = draft.exclude.isEmpty()) { picking = "exclude" }
            draft.exclude.forEachIndexed { i, cid ->
                val conv = com.fariin.app.data.CryptoHub.listedConversation(cid)
                RemovableRow(conv?.displayName(me) ?: "Chat", conv?.displayPhoto(me), conv?.isChannel == true, last = i == draft.exclude.lastIndex) {
                    draft = draft.copy(exclude = draft.exclude - cid)
                }
            }
        }
        SettingsSection(footer = "These apply to every chat in this folder, including chats you added one by one.") {
            SwitchRow("Only Show Unread Chats", draft.unreadOnly) { draft = draft.copy(unreadOnly = it) }
            SwitchRow("Include Muted Chats", draft.includeMuted, last = true) { draft = draft.copy(includeMuted = it) }
        }
        if (original != null) SettingsSection {
            SettingsRow("Delete Folder", titleColor = c.red, trailing = Trailing.NONE, last = true) { confirmDelete = true }
        }
    }
    if (confirmDelete) GlassAlert(onDismissRequest = { confirmDelete = false }, title = { Text("Delete Folder?") },
        text = { Text("The chats stay in your chat list. Only the folder goes.") },
        confirmButton = { TextButton({ confirmDelete = false; original?.let { ChatFoldersStore.remove(it.id) }; onClose() }) { Text("Delete", color = c.red) } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel", color = c.accent) } }, containerColor = c.card)
    if (confirmDiscard) GlassAlert(onDismissRequest = { confirmDiscard = false }, title = { Text("Discard your changes?") },
        text = { Text("What you changed in this folder is not saved yet.") },
        confirmButton = { TextButton({ confirmDiscard = false; onClose() }) { Text("Discard", color = c.red) } },
        dismissButton = { TextButton({ confirmDiscard = false }) { Text("Keep Editing", color = c.accent) } }, containerColor = c.card)
}

@Composable
private fun RemovableRow(title: String, photo: String?, channel: Boolean, last: Boolean, onRemove: () -> Unit) {
    val c = Fariin.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (photo != null || channel) { Avatar(photo, 30.dp, squircle = channel); Spacer(Modifier.width(12.dp)) }
        Text(title, fontSize = 17.sp, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text("Remove", fontSize = 14.sp, color = c.red, modifier = Modifier.clip(CircleShape).clickable(onClick = onRemove).padding(horizontal = 10.dp, vertical = 6.dp))
    }
    if (!last) Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(0.5.dp).background(c.separator))
}

/** Chat types and chats to tick (iOS ChatFolderChatPicker): every chat in the list, newest first. */
@Composable
internal fun ChatFolderChatPicker(title: String, showTypes: Boolean, types: Set<String>, chats: List<String>,
                                  onCancel: () -> Unit, onDone: (Set<String>, List<String>) -> Unit) {
    val c = Fariin.colors
    val me = com.fariin.app.Fb.auth.currentUser?.uid.orEmpty()
    var pickedTypes by remember { mutableStateOf(types) }
    var picked by remember { mutableStateOf(chats) }
    var query by remember { mutableStateOf("") }
    var full by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(onBack = onCancel)
    val candidates = remember(query) {
        com.fariin.app.data.CryptoHub.listedAll()
            .filter { !it.isCleared(me) && (com.fariin.app.data.AppFeatures.groups || !it.isGroup || it.isChannel) }
            .filter { query.isBlank() || it.displayName(me).contains(query.trim(), ignoreCase = true) }
            .sortedByDescending { it.listSortDate(me) }
    }
    Column(Modifier.fillMaxSize().background(c.bgSecondary).statusBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp)) {
            // The round X, not a "Cancel" word, in a top bar (iOS 2026-11-20).
            com.fariin.app.ui.components.FloatingIcon(androidx.compose.ui.graphics.vector.ImageVector.cachedVector(com.fariin.app.R.drawable.ic_sf_xmark), "Cancel",
                onClick = onCancel, circle = true, modifier = Modifier.align(Alignment.CenterStart))
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label, modifier = Modifier.align(Alignment.Center))
            Text("Done", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.accent,
                modifier = Modifier.align(Alignment.CenterEnd).clip(CircleShape).clickable { onDone(pickedTypes, picked) }.padding(10.dp))
        }
        com.fariin.app.ui.components.FloatingSearchField(query, { query = it }, "Search", Modifier.fillMaxWidth().padding(horizontal = 16.dp), fill = c.fieldFill)
        LazyColumn(Modifier.fillMaxSize()) {
            if (showTypes && query.isBlank()) {
                item { Text("Chat Types", fontSize = 13.sp, color = c.secondary, modifier = Modifier.padding(start = 32.dp, top = 16.dp, bottom = 6.dp)) }
                items(ChatFolder.PICKABLE.filter { it != "groups" || com.fariin.app.data.AppFeatures.groups }) { t ->
                    PickRow(ChatFolder.typeName(t), null, false, t in pickedTypes) {
                        pickedTypes = if (t in pickedTypes) pickedTypes - t else pickedTypes + t
                    }
                }
            }
            item { Text("Chats", fontSize = 13.sp, color = c.secondary, modifier = Modifier.padding(start = 32.dp, top = 16.dp, bottom = 6.dp)) }
            items(candidates, key = { it.id }) { conv: Conversation ->
                PickRow(conv.displayName(me), conv.displayPhoto(me), conv.isChannel, conv.id in picked, avatar = true) {
                    picked = when {
                        conv.id in picked -> picked - conv.id
                        picked.size >= ChatFolder.CHATS_MAX -> { full = true; picked }
                        else -> picked + conv.id
                    }
                }
            }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }
    if (full) GlassAlert(onDismissRequest = { full = false }, title = { Text("Folder Is Full") },
        text = { Text("A folder can hold up to ${ChatFolder.CHATS_MAX} chats picked one by one.") },
        confirmButton = { TextButton({ full = false }) { Text("OK", color = c.accent) } }, containerColor = c.card)
}

@Composable
private fun PickRow(title: String, photo: String?, channel: Boolean, on: Boolean, avatar: Boolean = false, onTap: () -> Unit) {
    val c = Fariin.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
        .background(c.card).clickable(onClick = onTap).heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (avatar) { Avatar(photo, 40.dp, squircle = channel); Spacer(Modifier.width(12.dp)) }
        Text(title, fontSize = 17.sp, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Icon(ImageVector.cachedVector(if (on) R.drawable.ic_sf_checkmark_circle_fill else R.drawable.ic_sf_circle), if (on) "Selected" else null,
            tint = if (on) c.accent else c.secondary, modifier = Modifier.size(24.dp))
    }
    Spacer(Modifier.height(4.dp))
}
