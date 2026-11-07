package com.fariin.app.ui.thread

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.data.Message
import com.fariin.app.data.MessageRules
import com.fariin.app.ui.components.Avatar
import com.fariin.app.ui.components.SheetShape
import com.fariin.app.ui.components.sheetFill
import com.fariin.app.ui.theme.Fariin

/**
 * Who reacted (iOS ReactorsSheet), opened by tapping a reaction pill under a bubble: "All" and a
 * tab per emoji, then each reactor with their photo, name and emoji. My own row reads "Tap to
 * remove" and takes my reaction back. [m] is the live copy, so a reaction that changes while the
 * sheet is up shows at once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReactorsSheet(m: Message, ui: ThreadUi, onRemoveMine: () -> Unit, onDismiss: () -> Unit) {
    val c = Fariin.colors
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    var picked by remember { mutableStateOf<String?>(null) }
    val reactions = m.reactions
    // My own row says my name, not "You" (iOS, owner 2026-09-28); "You" only when it is unknown.
    val name: (String) -> String = { uid -> ui.names[uid]?.trim()?.ifEmpty { null } ?: if (uid == ui.me) "You" else "Someone" }
    val tabs = remember(reactions) { MessageRules.reactorTabs(reactions) }
    val active = MessageRules.activeTab(reactions, picked)
    val rows = remember(reactions, active, ui.names) { MessageRules.reactorRows(reactions, active, name) }
    val canRemove = !m.deleted
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = sheetFill, shape = SheetShape) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            Text("Reactions", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label,
                modifier = Modifier.align(Alignment.Center))
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterEnd)) {
                Text("Done", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.accent)
            }
        }
        if (tabs.isNotEmpty()) Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Tab(null, "All ${reactions.count { it.value.isNotEmpty() }}", active == null) { picked = null }
            tabs.forEach { (e, n) -> Tab(e, "$n", active == e) { picked = e } }
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(min = 120.dp).navigationBarsPadding().padding(bottom = 12.dp)) {
            if (rows.isEmpty()) item {
                Text("No reactions yet", fontSize = 15.sp, color = c.secondary,
                    modifier = Modifier.fillMaxWidth().padding(24.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            items(rows, key = { it.uid }) { r ->
                val mine = r.uid == ui.me && canRemove
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = mine) { onRemoveMine(); onDismiss() }
                        .heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(if (r.uid == ui.me) ui.myPhoto ?: ui.photos[r.uid] else ui.photos[r.uid], 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.name, fontSize = 16.sp, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (mine) Text("Tap to remove", fontSize = 13.sp, color = c.secondary)
                    }
                    ReactionGlyph(r.emoji, 20.sp, 26.dp)
                }
            }
        }
    }
}

@Composable
private fun Tab(emoji: String?, label: String, on: Boolean, onClick: () -> Unit) {
    val c = Fariin.colors
    Row(
        Modifier.clip(CircleShape).background(if (on) c.secondary.copy(alpha = 0.22f) else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (emoji != null) { ReactionGlyph(emoji, 15.sp, 20.dp); Spacer(Modifier.width(4.dp)) }
        Text(label, fontSize = 15.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, color = c.label)
    }
}
