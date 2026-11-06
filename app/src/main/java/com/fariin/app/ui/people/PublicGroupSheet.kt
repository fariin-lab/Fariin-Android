package com.fariin.app.ui.people

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.channel.ChannelPreview
import com.fariin.app.channel.ChannelService
import com.fariin.app.data.PublicGroups
import com.fariin.app.ui.components.Avatar
import com.fariin.app.ui.components.SheetShape
import com.fariin.app.ui.components.sheetFill
import com.fariin.app.ui.theme.Fariin
import kotlinx.coroutines.launch

/**
 * A public group found by its @name (iOS PublicGroupSheet, the join half): photo, name, @name and
 * size, the description, and Join Group or Request to Join; Open when I am in it already. The
 * server's publicGroupPreview fills it (the group document stays members-only).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PublicGroupSheet(cid: String, onClose: () -> Unit, onOpenChat: (String) -> Unit, onOpenChannel: ((String) -> Unit)? = null) {
    val c = Fariin.colors
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<ChannelPreview?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var joining by remember { mutableStateOf(false) }
    var requested by remember { mutableStateOf(false) }
    LaunchedEffect(cid) {
        when (val r = ChannelService.preview(cid)) {
            // A channel's @name opens the channel itself (its posts), not a join sheet.
            is ChannelService.PreviewResult.Found -> if (r.preview.isChannel && onOpenChannel != null) { onClose(); onOpenChannel(cid) } else preview = r.preview
            is ChannelService.PreviewResult.Refused -> problem = r.text
            is ChannelService.PreviewResult.Failed -> problem = r.text
        }
    }
    ModalBottomSheet(onDismissRequest = onClose, containerColor = sheetFill, shape = SheetShape) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val p = preview
            when {
                p != null -> {
                    Avatar(p.photoUrl, 100.dp, squircle = p.isChannel, kind = com.fariin.app.ui.components.AvatarKind.of(true, p.isChannel))
                    Spacer(Modifier.height(14.dp))
                    Text(p.title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = c.label, textAlign = TextAlign.Center, maxLines = 2)
                    val n = p.memberCount
                    Text(listOfNotNull(p.username.takeIf { it.isNotEmpty() }?.let { "@$it" }, "$n ${if (n == 1) "member" else "members"}").joinToString(" · "),
                        fontSize = 15.sp, color = c.secondary, modifier = Modifier.padding(top = 4.dp))
                    if (p.description.isNotEmpty()) Text(p.description, fontSize = 15.sp, color = c.label, textAlign = TextAlign.Center,
                        maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
                    problem?.let { Text(it, fontSize = 14.sp, color = c.secondary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp)) }
                    val sent = requested || p.requested
                    if (sent) Text("An admin will review your request.", fontSize = 14.sp, color = c.secondary, modifier = Modifier.padding(top = 16.dp))
                    Spacer(Modifier.height(24.dp))
                    val title = when {
                        p.isMember -> "Open Group"
                        sent -> "Request Sent"
                        joining -> if (p.needsApproval) "Sending…" else "Joining…"
                        p.needsApproval -> "Request to Join"
                        else -> "Join Group"
                    }
                    val enabled = !joining && !sent
                    Box(Modifier.fillMaxWidth().height(50.dp).clip(CircleShape).background(if (enabled || joining) c.accent else c.accent.copy(alpha = 0.4f))
                        .clickable(enabled = enabled && !joining) {
                            if (p.isMember) { onClose(); onOpenChat(cid); return@clickable }
                            joining = true; problem = null
                            scope.launch {
                                runCatching { PublicGroups.join(cid) }
                                    .onSuccess { status -> if (status == "requested") requested = true else { onClose(); onOpenChat(cid) } }
                                    .onFailure { problem = it.message }
                                joining = false
                            }
                        }, contentAlignment = Alignment.Center) {
                        com.fariin.app.ui.components.BusyContent(joining, c.onAccent) { Text(title, color = c.onAccent, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
                    }
                }
                problem != null -> Text(problem!!, fontSize = 15.sp, color = c.secondary, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 24.dp))
                else -> com.fariin.app.ui.components.FariinSpinner(Modifier.padding(32.dp), size = 30.dp, stroke = 3.dp)
            }
        }
    }
}
