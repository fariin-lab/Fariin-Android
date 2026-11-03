package com.fariin.app.ui.people

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.R
import com.fariin.app.data.Disappearing
import com.fariin.app.data.PeopleApi
import com.fariin.app.ui.settings.CheckRow
import com.fariin.app.ui.settings.PageBar
import com.fariin.app.ui.settings.SettingsRow
import com.fariin.app.ui.settings.SettingsSection
import com.fariin.app.ui.theme.Fariin
import kotlinx.coroutines.launch

/**
 * The Disappearing Messages page (iOS DisappearingMessagesView): what it does, the presets as
 * radio rows in one rounded card, Custom Time, and a check at the top end that applies the choice.
 * The up arrow and the back gesture leave without a change.
 */
@Composable
fun DisappearingMessagesPage(current: Int, isChannel: Boolean = false, onBack: () -> Unit, onApply: (Int) -> Unit) {
    val c = Fariin.colors
    var selected by remember { mutableStateOf(current) }
    var custom by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    if (custom) CustomTimeDialog(selected, onDismiss = { custom = false }) { selected = it; custom = false }
    Column(Modifier.fillMaxSize().background(c.bgSecondary)) {
        PageBar("Disappearing Messages", onBack = onBack, action = {
            IconButton(onClick = { onApply(selected) }) { Icon(Icons.Filled.Check, contentDescription = "Done", tint = c.blue) }
        })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Text(Disappearing.pageHeader(isChannel), style = MaterialTheme.typography.bodyMedium, color = c.secondary,
                modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 12.dp, bottom = 2.dp))
            SettingsSection {
                for ((label, secs) in Disappearing.presets) CheckRow(label, selected == secs) { selected = secs }
                val isCustom = selected != 0 && !Disappearing.isPreset(selected)
                CheckRow(if (isCustom) "Custom Time (${Disappearing.label(selected)})" else "Custom Time", isCustom, last = true) { custom = true }
            }
        }
    }
}

/** iOS CustomTimePicker as an Android dialog: a number with minus and plus, and the unit. */
@Composable
private fun CustomTimeDialog(seconds: Int, onDismiss: () -> Unit, onSet: (Int) -> Unit) {
    val c = Fariin.colors
    val seed = remember { Disappearing.customSeed(seconds) }
    var unit by remember { mutableStateOf(seed.first) }
    var value by remember { mutableStateOf(seed.second) }
    AlertDialog(onDismissRequest = onDismiss, containerColor = c.card, title = { Text("Custom Time") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { value = (value - 1).coerceIn(unit.range) }, enabled = value > unit.range.first) {
                        Icon(Icons.Filled.Remove, contentDescription = "Less", tint = c.blue)
                    }
                    Text("$value", fontSize = 32.sp, fontWeight = FontWeight.SemiBold, color = c.label,
                        modifier = Modifier.width(72.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    IconButton(onClick = { value = (value + 1).coerceIn(unit.range) }, enabled = value < unit.range.last) {
                        Icon(Icons.Filled.Add, contentDescription = "More", tint = c.blue)
                    }
                }
                Spacer(Modifier.size(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
                    for (u in Disappearing.units.take(3)) UnitChip(u.name, u == unit) { unit = u; value = value.coerceIn(u.range) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
                    for (u in Disappearing.units.drop(3)) UnitChip(u.name, u == unit) { unit = u; value = value.coerceIn(u.range) }
                }
            }
        },
        confirmButton = { TextButton({ onSet(value * unit.seconds) }) { Text("Set", color = c.blue, fontWeight = FontWeight.SemiBold) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel", color = c.blue) } })
}

@Composable
private fun UnitChip(text: String, on: Boolean, onClick: () -> Unit) {
    val c = Fariin.colors
    FilterChip(selected = on, onClick = onClick, label = { Text(text) },
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = c.blue.copy(alpha = 0.16f), selectedLabelColor = c.blue))
}

/** The row on a chat's info page: Fariin's timer icon, the title, the value under it. */
@Composable
internal fun DisappearingRow(seconds: Int, short: Boolean, last: Boolean = false, onClick: () -> Unit) {
    SettingsRow("Disappearing Messages", icon = painterResource(R.drawable.ic_disappearing),
        value = if (seconds <= 0) "Off" else if (short) Disappearing.shortLabel(seconds) else Disappearing.label(seconds),
        last = last, onClick = onClick)
}

/** New Group's timer (iOS NewGroupView): Off / 1 Day / 1 Week / 1 Month, the whole row opens the menu. */
@Composable
internal fun NewGroupTimerSection(seconds: Int, onChange: (Int) -> Unit) {
    val c = Fariin.colors
    var open by remember { mutableStateOf(false) }
    SettingsSection(footer = Disappearing.NEW_GROUP_FOOTER) {
        Box {
            Row(Modifier.fillMaxWidth().clickable { open = true }.heightIn(min = 56.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("Disappearing Messages", style = MaterialTheme.typography.bodyLarge, color = c.label, modifier = Modifier.weight(1f))
                Text(Disappearing.newGroupLabel(seconds), style = MaterialTheme.typography.bodyLarge, color = c.secondary)
                Icon(painterResource(R.drawable.ic_disappearing), null, tint = c.secondary, modifier = Modifier.padding(start = 8.dp).size(18.dp))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, shape = com.fariin.app.ui.components.FariinMenu.shape, border = com.fariin.app.ui.components.FariinMenu.border, containerColor = c.card,
                modifier = Modifier.align(Alignment.TopEnd)) {
                val choices = Disappearing.newGroupChoices.toMutableList()
                // The Settings default, when it is none of these.
                if (choices.none { it.second == seconds }) choices.add(Disappearing.newGroupLabel(seconds) to seconds)
                for ((label, secs) in choices) DropdownMenuItem(
                    text = { Text(label, color = c.label) },
                    trailingIcon = { if (secs == seconds) Icon(Icons.Filled.Check, null, tint = c.blue) },
                    onClick = { open = false; onChange(secs) })
            }
        }
    }
}

/**
 * A chat's timer editor (iOS ContactInfoView / GroupSettingsView): the page while [open], then in a
 * group the "Turn on" question, then the write. Off applies at once; no change writes nothing.
 * Answers true while the page covers the screen, so the caller draws nothing else.
 */
@Composable
internal fun DisappearingEditor(api: PeopleApi, cid: String, isGroup: Boolean, current: Int, open: Boolean, onClose: () -> Unit): Boolean {
    val c = Fariin.colors
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    fun write(s: Int) = scope.launch { if (!api.setDisappear(cid, s)) error = Disappearing.NOT_CHANGED }
    error?.let { e -> AlertDialog(onDismissRequest = { error = null }, text = { Text(e) }, containerColor = c.card,
        confirmButton = { TextButton({ error = null }) { Text("OK", color = c.blue) } }) }
    pending?.let { s -> AlertDialog(onDismissRequest = { pending = null }, containerColor = c.card,
        title = { Text(Disappearing.CONFIRM_TITLE) }, text = { Text(Disappearing.confirmText(s)) },
        confirmButton = { TextButton({ pending = null; write(s) }) { Text("Turn On", color = c.blue) } },
        dismissButton = { TextButton({ pending = null }) { Text("Cancel", color = c.blue) } }) }
    if (!open) return false
    DisappearingMessagesPage(current, onBack = onClose) { s ->
        onClose()
        when {
            s == current -> {}
            isGroup && s > 0 -> pending = s
            else -> write(s)
        }
    }
    return true
}
