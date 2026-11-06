package com.fariin.app.ui.channels

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.channel.ChannelRules
import com.fariin.app.ui.components.Avatar
import com.fariin.app.ui.components.AvatarKind
import com.fariin.app.ui.components.floating
import com.fariin.app.ui.theme.Fariin
import kotlinx.coroutines.launch

/**
 * The channel's card at the start of its posts (iOS ChannelFollowerCard / ChannelAdminCard): a
 * rounded card 340 wide at most, corners of 36, 18 x 24 inside, 20 from the screen's sides.
 * Its owner and admins get their own version: the photo, the name, "You created this channel ·
 * N followers", and Share and Edit circles. Everyone else gets who the channel is: photo, name
 * with its tick, @name, the description and the day it began; nothing to tap.
 * It is a list row, so `floating` draws its plain fill here (the list provides no backdrop).
 */
@Composable
fun ChannelIntroCard(ch: ChannelThreadUi, modifier: Modifier = Modifier) {
    val admin = ch.role == ChannelRules.Role.ADMIN
    Column(
        modifier.padding(horizontal = 20.dp).widthIn(max = 340.dp).floating(RoundedCornerShape(36.dp))
            .padding(horizontal = 18.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (admin) 14.dp else 10.dp),
    ) {
        if (admin) AdminPart(ch) else FollowerPart(ch)
    }
}

@Composable
private fun FollowerPart(ch: ChannelThreadUi) {
    val c = Fariin.colors
    val info = ch.info
    Avatar(info.avatarUrl, 92.dp, squircle = true, kind = AvatarKind.CHANNEL)
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(info.title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = c.label, maxLines = 2,
                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.weight(1f, fill = false))
            if (info.verified) {
                Spacer(Modifier.width(5.dp))
                Icon(Icons.Filled.Verified, contentDescription = "Verified", tint = c.blue, modifier = Modifier.size(18.dp))
            }
        }
        if (info.isPublic && info.username.isNotEmpty()) Text("@" + info.username, fontSize = 15.sp, color = c.secondary, maxLines = 1)
    }
    if (info.description.isNotEmpty()) {
        Text(info.description, fontSize = 15.sp, color = c.label, textAlign = TextAlign.Center, maxLines = 4,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
    }
    val created = remember(info.createdAtMillis) {
        if (info.createdAtMillis <= 0) null
        else "Created " + java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(info.createdAtMillis.toLong()))
    }
    if (created != null) Text(created, fontSize = 13.sp, color = c.secondary, modifier = Modifier.padding(top = 2.dp))
}

@Composable
private fun AdminPart(ch: ChannelThreadUi) {
    val c = Fariin.colors
    val info = ch.info
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val nav = LocalChannelNav.current
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Avatar(info.avatarUrl, 92.dp, if (ch.canEdit) Modifier.clip(RoundedCornerShape(26.dp)).clickable { nav.edit(info.cid) } else Modifier,
            squircle = true, kind = AvatarKind.CHANNEL)
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(info.title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = c.label, maxLines = 2,
                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            Text(ChannelRules.adminCardLine(ch.createdByMe, info.shownCount), fontSize = 15.sp, color = c.secondary, textAlign = TextAlign.Center)
        }
    }
    // Only while nobody but the admins follows (iOS: from the same count as the line above).
    if (info.followerCount <= 0) {
        Text("Share your channel so people can follow it.", fontSize = 13.sp, color = c.secondary, textAlign = TextAlign.Center)
    }
    Row {
        CardCircle(Icons.Outlined.IosShare, "Share") {
            scope.launch {
                val link = ch.shareLink()
                if (link == null) {
                    ch.notice(if (!info.isPublic) "This channel is private. Only admins who can invite followers can share its link."
                        else com.fariin.app.data.ChannelAddFollowers.NO_LINK)
                    return@launch
                }
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(android.content.Intent.EXTRA_TEXT, com.fariin.app.data.ChannelAddFollowers.shareText(info.title, link))
                ctx.startActivity(android.content.Intent.createChooser(send, null))
            }
        }
        if (ch.canEdit) CardCircle(Icons.Outlined.Edit, if (info.description.isEmpty()) "Description" else "Edit") { nav.edit(info.cid) }
    }
}

/** iOS PosterActionIcon at 56: a round button and its name under it. */
@Composable
private fun CardCircle(icon: ImageVector, label: String, onClick: () -> Unit) {
    val c = Fariin.colors
    Column(Modifier.padding(horizontal = 22.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(56.dp).floating(CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = c.label, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 13.sp, color = c.label, maxLines = 1)
    }
}
