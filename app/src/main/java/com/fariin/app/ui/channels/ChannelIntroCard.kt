package com.fariin.app.ui.channels

import androidx.compose.ui.res.vectorResource
import com.fariin.app.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
 * rounded card across the screen (24 from each side), corners of 36, 20 x 28 inside, on the posts' surface.
 * Its owner and admins get their own version: the photo, the name, "You created this channel ·
 * N followers", and Share and Edit circles. Everyone else gets who the channel is: photo, name
 * with its tick, @name, the description and the day it began; nothing to tap.
 * A list row, so no live glass: the incoming bubbles' see-through wash and rim (receivedFill).
 */
@Composable
fun ChannelIntroCard(ch: ChannelThreadUi, modifier: Modifier = Modifier) {
    val admin = ch.role == ChannelRules.Role.ADMIN
    // iOS: the card spans the screen (24 in from each side), the posts' own see-through surface
    // and rim, not a white tile. Owner 2026-11-06: the narrow white card "is not same as apple".
    val shape = RoundedCornerShape(36.dp)
    Column(
        modifier.padding(horizontal = 24.dp).widthIn(max = 420.dp).fillMaxWidth().clip(shape)
            .background(com.fariin.app.ui.thread.receivedFill()).then(com.fariin.app.ui.thread.receivedRim(false, shape))
            .padding(horizontal = 20.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (admin) 14.dp else 12.dp),
    ) {
        if (admin) AdminPart(ch) else FollowerPart(ch)
    }
}

@Composable
private fun FollowerPart(ch: ChannelThreadUi) {
    val c = Fariin.colors
    val info = ch.info
    Avatar(info.avatarUrl, 96.dp, squircle = true, kind = AvatarKind.CHANNEL)
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(info.title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = c.label, maxLines = 2,
                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.weight(1f, fill = false))
            if (info.verified) {
                Spacer(Modifier.width(5.dp))
                com.fariin.app.ui.components.VerifiedSeal(c.blue, Modifier.size(22.dp), "Verified")
            }
        }
        if (info.isPublic && info.username.isNotEmpty()) Text("@" + info.username, fontSize = 17.sp, color = c.secondary, maxLines = 1)
    }
    if (info.description.isNotEmpty()) {
        Text(info.description, fontSize = 17.sp, color = c.label, textAlign = TextAlign.Center, maxLines = 4,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
    }
    val created = remember(info.createdAtMillis) {
        if (info.createdAtMillis <= 0) null
        else "Created " + java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(info.createdAtMillis.toLong()))
    }
    if (created != null) Text(created, fontSize = 15.sp, color = c.secondary, modifier = Modifier.padding(top = 2.dp))
}

@Composable
private fun AdminPart(ch: ChannelThreadUi) {
    val c = Fariin.colors
    val info = ch.info
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val nav = LocalChannelNav.current
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Avatar(info.avatarUrl, 96.dp, if (ch.canEdit) Modifier.clip(RoundedCornerShape(26.dp)).clickable { nav.edit(info.cid) } else Modifier,
            squircle = true, kind = AvatarKind.CHANNEL)
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(info.title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = c.label, maxLines = 2,
                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            Text(ChannelRules.adminCardLine(ch.createdByMe, info.shownCount), fontSize = 15.sp, color = c.secondary, textAlign = TextAlign.Center)
        }
    }
    // Only while nobody but the admins follows (iOS: from the same count as the line above).
    if (info.followerCount <= 0) {
        Text("Share your channel so people can follow it.", fontSize = 13.sp, color = c.secondary, textAlign = TextAlign.Center)
    }
    Row {
        CardCircle(ImageVector.vectorResource(R.drawable.ic_link_share), "Share") {
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
        if (ch.canEdit) CardCircle(ImageVector.vectorResource(R.drawable.ic_sf_pencil), if (info.description.isEmpty()) "Description" else "Edit") { nav.edit(info.cid) }
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
