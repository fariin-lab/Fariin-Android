package com.fariin.app.ui.thread

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.PersonAddAlt
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.data.GroupRules
import com.fariin.app.data.GroupWelcome
import com.fariin.app.ui.components.Avatar
import com.fariin.app.ui.components.floating
import com.fariin.app.ui.theme.Fariin

/**
 * iOS GroupWelcomeCard: an empty group chat shows the group itself in a rounded glass card: its
 * photo (a camera badge while whoever may edit has set none), name, who started it and its size, and
 * the next steps as the group page's round circles. Each step opens its own page as iOS opens its
 * sheet: [onStep] "add" (Add Members), "link" (Invite Links, iOS InviteLinkSheet), "edit" (Edit Group,
 * also the photo and Description).
 */
@Composable
internal fun GroupWelcomeCard(ui: ThreadUi, onStep: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = Fariin.colors
    val doc = ui.groupDoc
    val canEdit = GroupRules.canEditInfo(ui.me, doc)
    val canAdd = GroupWelcome.canAdd(ui.me, doc)
    val canLink = GroupWelcome.canLink(ui.me, doc)
    val photo = (doc["avatarUrl"] as? String)?.ifEmpty { null } ?: ui.photoUrl
    val title = doc["title"] as? String ?: ui.title
    Column(modifier.padding(horizontal = 20.dp).widthIn(max = 340.dp).floating(RoundedCornerShape(36.dp))
        .padding(horizontal = 18.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(if (canEdit) Modifier.clip(CircleShape).clickable { onStep("edit") } else Modifier) {
                Avatar(photo, 92.dp)
                if (canEdit && photo.isNullOrEmpty()) Box(Modifier.align(Alignment.BottomEnd).size(32.dp).floating(CircleShape),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.PhotoCamera, contentDescription = null, tint = c.label, modifier = Modifier.size(16.dp))
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = c.label, maxLines = 2, textAlign = TextAlign.Center)
                Text("${GroupWelcome.startedLine(ui.me, doc)} · ${GroupWelcome.sizeLine(doc)}", fontSize = 15.sp,
                    color = c.secondary, textAlign = TextAlign.Center)
            }
        }
        if (canEdit || canAdd) Row {
            if (canAdd) WelcomeCircle(Icons.Outlined.PersonAddAlt, "Add Members") { onStep("add") }
            if (canLink) WelcomeCircle(Icons.Outlined.Link, "Group Link") { onStep("link") }
            if (canEdit) WelcomeCircle(Icons.Outlined.Edit,
                if (GroupRules.description(doc).isEmpty()) "Description" else "Edit") { onStep("edit") }
        }
    }
}

/** iOS PosterActionIcon at 56, off a photo: a glass circle and its name under it. */
@Composable
private fun WelcomeCircle(icon: ImageVector, label: String, onClick: () -> Unit) {
    val c = Fariin.colors
    Column(Modifier.padding(horizontal = 6.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(56.dp).floating(CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = c.label, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 13.sp, color = c.label, maxLines = 1)
    }
}
