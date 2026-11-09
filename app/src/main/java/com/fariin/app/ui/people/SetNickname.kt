package com.fariin.app.ui.people

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.fariin.app.R
import com.fariin.app.ui.settings.BarPage
import com.fariin.app.ui.components.underBar

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.data.ContactCard
import com.fariin.app.data.ContactNames
import com.fariin.app.data.GroupRules
import com.fariin.app.ui.components.Avatar
import com.fariin.app.ui.settings.PageBar
import com.fariin.app.ui.settings.SettingsSection
import com.fariin.app.ui.theme.Fariin

/**
 * Set nickname (iOS SetNicknameView): the photo, First / Last name and a private Note, kept on this
 * phone only (ContactNames). Save keeps it, back leaves it as it was, Delete clears both.
 */
@Composable
fun SetNicknamePage(uid: String, photoUrl: String?, sheet: com.fariin.app.ui.components.SheetMotion? = null, onBack: () -> Unit) {
    val c = Fariin.colors
    val saved = remember(uid) { ContactNames.card(uid) }
    var first by remember(uid) { mutableStateOf(saved.first) }
    var last by remember(uid) { mutableStateOf(saved.last) }
    var note by remember(uid) { mutableStateOf(saved.note) }
    var confirmDelete by remember { mutableStateOf(false) }
    val changed = ContactCard(first, last, note) != saved

    if (confirmDelete) com.fariin.app.ui.components.GlassAlert(onDismissRequest = { confirmDelete = false },
        title = { Text("Delete nickname and note?") },
        text = { Text("This removes the name and note you saved. Their profile name shows again.") },
        confirmButton = { TextButton({ confirmDelete = false; ContactNames.remove(uid); onBack() }) { Text("Delete", color = c.red) } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel", color = c.accent) } }, containerColor = c.card)

    val body: @Composable () -> Unit = {
            Spacer(Modifier.height(8.dp))
            Avatar(photoUrl, 120.dp)
            Spacer(Modifier.height(12.dp))
            SettingsSection {
                NameField(first, "First name") { first = it.take(ContactNames.NAME_LIMIT) }
                HorizontalDivider(thickness = 0.5.dp, color = c.separator, modifier = Modifier.padding(start = 16.dp))
                NameField(last, "Last name") { last = it.take(ContactNames.NAME_LIMIT) }
            }
            // The counter shows only near the cap, so an empty field stays clean (iOS).
            SettingsSection(footer = if (note.length >= ContactNames.NOTE_LIMIT - 20) "Notes are only visible to you.  ${note.length}/${ContactNames.NOTE_LIMIT}"
                else "Notes are only visible to you.") {
                Box(Modifier.fillMaxWidth().padding(16.dp)) {
                    if (note.isEmpty()) Text("Note", fontSize = 17.sp, color = c.secondary)
                    BasicTextField(note, { note = GroupRules.oneParagraph(it, ContactNames.NOTE_LIMIT) }, maxLines = 5,
                        textStyle = TextStyle(fontSize = 17.sp, color = c.label), cursorBrush = SolidColor(c.accent),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Note" })
                }
            }
            if (!saved.isEmpty) SettingsSection {
                Text("Delete", fontSize = 17.sp, color = c.red,
                    modifier = Modifier.fillMaxWidth().clickable { confirmDelete = true }.padding(16.dp))
            }
    }
    // From the profile's Edit: a sheet with X and a tick (iOS .sheet), owner 2026-11-08.
    if (sheet != null) {
        com.fariin.app.ui.components.SheetPage(sheet, "Set nickname", doneEnabled = changed,
            onDone = { ContactNames.setCard(uid, ContactCard(first, last, note)); onBack() }) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) { body() }
        }
        return
    }
    val barScroll = rememberScrollState()
    BarPage("Set nickname", onBack = onBack, under = underBar(barScroll), modifier = Modifier.background(c.bgSecondary).imePadding(), action = {
            com.fariin.app.ui.components.FloatingTextAction("Save", enabled = changed) { ContactNames.setCard(uid, ContactCard(first, last, note)); onBack() }
        }) { top ->
        Column(Modifier.fillMaxSize().verticalScroll(barScroll).padding(top = top), horizontalAlignment = Alignment.CenterHorizontally) { body() }
    }
}

@Composable
private fun NameField(value: String, placeholder: String, onChange: (String) -> Unit) {
    val c = Fariin.colors
    Box(Modifier.fillMaxWidth().padding(16.dp)) {
        if (value.isEmpty()) Text(placeholder, fontSize = 17.sp, color = c.secondary)
        BasicTextField(value, onChange, singleLine = true, textStyle = TextStyle(fontSize = 17.sp, color = c.label),
            cursorBrush = SolidColor(c.accent), keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            // The hint is drawn text, so the field carries its name for screen readers.
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = placeholder })
    }
}

/** The profile's Notes card (iOS notesCard): "Notes", "only visible to you", the note at two lines, tap for all. */
@Composable
fun NotesCard(note: String) {
    val c = Fariin.colors
    var open by remember(note) { mutableStateOf(false) }
    SettingsSection {
        Column(Modifier.fillMaxWidth().clickable { open = !open }.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(ImageVector.vectorResource(R.drawable.ic_sf_text_alignleft), contentDescription = null, tint = c.label, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(14.dp))
                Text("Notes", fontSize = 17.sp, color = c.label, modifier = Modifier.weight(1f))
                Text("only visible to you", fontSize = 14.sp, color = c.secondary)
            }
            Text(note, fontSize = 16.sp, color = c.label, maxLines = if (open) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
