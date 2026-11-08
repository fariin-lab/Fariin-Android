package com.fariin.app.ui.thread

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.data.Message
import com.fariin.app.data.MessageInfoRules
import com.fariin.app.ui.components.Avatar
import com.fariin.app.ui.components.SheetCard
import com.fariin.app.ui.components.SheetShape
import com.fariin.app.ui.components.sheetFill
import com.fariin.app.ui.theme.Fariin
import androidx.compose.ui.res.vectorResource
import com.fariin.app.R

/**
 * Message Info (iOS MessageInfoView), for a group message I sent: the message, then who has read it
 * and who it has only been delivered to, from the per-member lastRead times. Opens half height and
 * drags to full, as the iOS sheet's medium/large detents; an empty list is left out.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MessageInfoSheet(m: Message, ui: ThreadUi, is24h: Boolean, onDismiss: () -> Unit) {
    val c = Fariin.colors
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val name: (String) -> String = { ui.names[it] ?: "Member" }
    val split = remember(m.id, ui.members, ui.memberReads, ui.names) {
        MessageInfoRules.split(ui.members, ui.me, ui.memberReads, m.createdAtMillis, name)
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = sheetFill, shape = SheetShape) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            Text("Message Info", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label,
                modifier = Modifier.align(Alignment.Center))
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterEnd)) {
                Text("Done", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.accent)
            }
        }
        LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            item {
                SheetCard {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(MessageInfoRules.snippet(m), fontSize = 15.sp, color = c.label,
                            maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.size(4.dp))
                        Text(MessageInfoRules.dateLine(m.createdAtMillis, is24h), fontSize = 12.sp, color = c.secondary)
                    }
                }
            }
            if (split.readBy.isEmpty() && split.deliveredTo.isEmpty()) item {
                Text("No one has received it yet.", fontSize = 15.sp, color = c.secondary,
                    modifier = Modifier.fillMaxWidth().padding(24.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            if (split.readBy.isNotEmpty()) item {
                MemberSection("Read by ${split.readBy.size}", ImageVector.vectorResource(R.drawable.ic_sf_checkmark_circle_fill), split.readBy, ui, name)
            }
            if (split.deliveredTo.isNotEmpty()) item {
                MemberSection("Delivered to ${split.deliveredTo.size}", ImageVector.vectorResource(R.drawable.ic_sf_checkmark_circle), split.deliveredTo, ui, name)
            }
        }
    }
}

@Composable
private fun MemberSection(title: String, icon: ImageVector, uids: List<String>, ui: ThreadUi, name: (String) -> String) {
    val c = Fariin.colors
    Row(Modifier.padding(start = 32.dp, end = 32.dp, top = 18.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = c.secondary, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text(title, fontSize = 13.sp, color = c.secondary)
    }
    SheetCard {
        uids.forEachIndexed { i, uid ->
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Avatar(ui.photos[uid], 40.dp)
                Spacer(Modifier.width(12.dp))
                Text(name(uid), fontSize = 16.sp, fontWeight = FontWeight.Medium, color = c.label,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            if (i < uids.lastIndex) HorizontalDivider(thickness = 0.5.dp, color = c.separator, modifier = Modifier.padding(start = 68.dp))
        }
    }
}
