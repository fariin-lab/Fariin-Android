package com.fariin.app.ui.people

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.data.ProfileLink
import com.fariin.app.data.ProfileWrites
import com.fariin.app.ui.settings.PageBar
import com.fariin.app.ui.settings.SettingsSection
import com.fariin.app.ui.theme.Fariin
import kotlinx.coroutines.launch

/**
 * Edit Profile > Links (iOS ProfileLinksView): Add Link while under the ceiling, then each link
 * (title over its address) opening its editor. Saves as it goes, not with Edit Profile's Done; a
 * refused save says so and puts the list back as it was.
 */
@Composable
fun ProfileLinksPage(me: String, initial: List<ProfileLink>, onChanged: (List<ProfileLink>) -> Unit, onBack: () -> Unit) {
    val c = Fariin.colors
    val scope = rememberCoroutineScope()
    var links by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Pair<Int, ProfileLink?>?>(null) }   // index (-1 = new), link
    fun save(next: List<ProfileLink>) {
        val before = links
        links = next
        scope.launch {
            try { ProfileWrites.saveLinks(me, next); error = null; onChanged(next) }
            catch (e: Exception) { links = before; error = "Your links could not be saved. Check your connection and try again." }
        }
    }
    editing?.let { (i, link) ->
        ProfileLinkEditPage(link,
            onSave = { saved -> editing = null; save(if (i < 0) links + saved else links.toMutableList().also { it[i] = saved }) },
            onDelete = if (i >= 0) ({ editing = null; save(links.filterIndexed { j, _ -> j != i }) }) else null,
            onBack = { editing = null })
        return
    }
    val atCeiling = links.size >= ProfileLink.MAX_PER_USER
    Column(Modifier.fillMaxSize().background(c.bgSecondary)) {
        PageBar("Links", onBack = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
            SettingsSection(footer = if (atCeiling) "You can have up to ${ProfileLink.MAX_PER_USER} links on your profile." else null) {
                if (!atCeiling) LinkRow(Icons.Filled.Add, "Add Link", null, last = links.isEmpty()) { editing = -1 to null }
                links.forEachIndexed { i, l -> LinkRow(ImageVector.vectorResource(com.fariin.app.R.drawable.ic_link_copy), l.title, l.displayUrl, last = i == links.lastIndex) { editing = i to l } }
            }
            error?.let { Text(it, fontSize = 13.sp, color = c.red, modifier = Modifier.padding(horizontal = 32.dp)) }
        }
    }
}

@Composable
private fun LinkRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String?, last: Boolean, onClick: () -> Unit) {
    val c = Fariin.colors
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = c.label, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 17.sp, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!sub.isNullOrEmpty()) Text(sub, fontSize = 15.sp, color = c.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    if (!last) HorizontalDivider(thickness = 0.5.dp, color = c.separator, modifier = Modifier.padding(start = 52.dp))
}

/** iOS ProfileLinkEditView: URL, then Title ("the name shown on your profile"), Save, Delete Link. */
@Composable
private fun ProfileLinkEditPage(existing: ProfileLink?, onSave: (ProfileLink) -> Unit, onDelete: (() -> Unit)?, onBack: () -> Unit) {
    val c = Fariin.colors
    var url by remember { mutableStateOf(existing?.url.orEmpty()) }
    var title by remember { mutableStateOf(existing?.title.orEmpty()) }
    var confirm by remember { mutableStateOf(false) }
    val candidate = run {
        val t = title.trim(); val u = url.trim()
        if (t.isEmpty() || u.isEmpty()) null else ProfileLink(t.take(ProfileLink.MAX_TITLE), u.take(ProfileLink.MAX_URL)).takeIf { it.openUrl != null }
    }
    androidx.activity.compose.BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(c.bgSecondary)) {
        PageBar(if (existing == null) "Add Link" else "Edit Link", onBack = onBack, action = {
            com.fariin.app.ui.components.FloatingTextAction("Save", enabled = candidate != null && candidate != existing) { candidate?.let(onSave) }
        })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
            SettingsSection(header = "URL",
                footer = if (url.isNotBlank() && title.isNotBlank() && candidate == null) "That does not look like a web address." else null) {
                PlainLine(url, "https://example.com", KeyboardType.Uri) { url = it.take(ProfileLink.MAX_URL) }
            }
            SettingsSection(header = "Title", footer = "This is the name shown on your profile.") {
                PlainLine(title, "My website", KeyboardType.Text) { title = it.take(ProfileLink.MAX_TITLE) }
            }
            if (onDelete != null) SettingsSection {
                Box(Modifier.fillMaxWidth().clickable { confirm = true }.padding(16.dp), contentAlignment = Alignment.Center) {
                    Text("Delete Link", fontSize = 17.sp, color = c.red)
                }
            }
        }
    }
    if (confirm && onDelete != null) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Delete this link?") },
        confirmButton = { TextButton({ confirm = false; onDelete() }) { Text("Delete Link", color = c.red) } },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancel", color = c.accent) } }, containerColor = c.card)
}

@Composable
private fun PlainLine(value: String, placeholder: String, keyboard: KeyboardType, onChange: (String) -> Unit) {
    val c = Fariin.colors
    Box(Modifier.fillMaxWidth().padding(16.dp)) {
        if (value.isEmpty()) Text(placeholder, fontSize = 17.sp, color = c.secondary)
        BasicTextField(value, onChange, singleLine = true, textStyle = TextStyle(fontSize = 17.sp, color = c.label),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = false),
            cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth())
    }
}

/** iOS ProfileLinkChips: the links as small pills under the bio; a tap opens the address. */
@Composable
fun ProfileLinkChips(links: List<ProfileLink>, modifier: Modifier = Modifier, onDark: Boolean = false) {
    if (links.isEmpty() || !com.fariin.app.data.AppFeatures.value("profileLinks", false)) return
    val c = Fariin.colors
    val ctx = LocalContext.current
    val ink = if (onDark) Color.White else c.label
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (l in links) {
            Row(Modifier.clip(CircleShape).background(if (onDark) Color.White.copy(alpha = 0.2f) else c.card)
                .clickable { l.openUrl?.let { u -> com.fariin.app.ui.thread.openWebLink(ctx, u) } }
                .padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(ImageVector.vectorResource(com.fariin.app.R.drawable.ic_link_copy), null, tint = ink, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(5.dp))
                Text(l.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = ink, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
            }
        }
    }
}
