package com.fariin.app.ui.thread

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.channel.ChannelFollows
import com.fariin.app.channel.ChannelService
import com.fariin.app.data.LiveStories
import com.fariin.app.data.Message
import com.fariin.app.ui.channels.LocalChannelNav
import com.fariin.app.ui.components.Avatar
import com.fariin.app.ui.theme.Fariin
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.fariin.app.R

private val ChannelBlue = Color(0xFF2392FE)

/**
 * The "Forwarded" line of a bubble. A post forwarded from a channel says which (iOS
 * FwdChannelCapsule): the channel's photo, its name, a small "Channel" tag, and Follow, or a chevron
 * once I follow it. A tap opens the channel; Follow follows it from the chat. Inside a channel a
 * post is that channel's own, so there it stays the plain word.
 */
/**
 * "Forwarded from <group or channel>" (iOS 2026-11-10), cut short at the line's end. A tap opens the
 * source at the post: this chat scrolls to it; a chat I am in or a channel I follow opens at it; a
 * public one I am not in opens its channel posts or the group's join sheet; a private one only says so.
 */
@Composable
private fun ForwardedFrom(src: Message.ForwardSource, isMe: Boolean, openCid: String) {
    val c = Fariin.colors
    val nav = com.fariin.app.ui.people.LocalPeopleNav.current
    val channelNav = LocalChannelNav.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    var joinSheet by remember { mutableStateOf(false) }
    if (joinSheet) com.fariin.app.ui.people.PublicGroupSheet(src.cid,
        onClose = { joinSheet = false; com.fariin.app.data.QuoteStore.pendingJump.value = null },
        onOpenChat = { nav.chat(it) }, onOpenChannel = { channelNav.open(it) })
    Text("Forwarded from ${src.title}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        color = if (isMe) Color.White.copy(alpha = 0.7f) else c.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clickable {
            val jump = com.fariin.app.data.QuoteStore.Jump(src.cid, src.mid)
            if (src.cid == openCid) { com.fariin.app.data.QuoteStore.pendingJump.value = jump; return@clickable }
            val mine = com.fariin.app.data.ChatsNow.find(src.cid) != null || ChannelFollows.entries.value.containsKey(src.cid)
            if (!mine && !src.isPublic) {
                com.fariin.app.ui.components.Snack.show(if (src.isChannel) "This channel is private" else "This group is private")
                return@clickable
            }
            // The keyboard goes down first, so it is not still up on coming back here.
            keyboard?.hide(); focus.clearFocus()
            com.fariin.app.data.QuoteStore.pendingJump.value = jump
            when {
                src.isChannel -> channelNav.open(src.cid)
                mine -> nav.chat(src.cid)
                else -> joinSheet = true
            }
        }.padding(vertical = 2.dp)
            .semantics { contentDescription = "Forwarded from ${src.title}" })
}

/** [openCid]: the chat this bubble is drawn in, so a "Forwarded from" this very chat scrolls instead. */
@Composable
internal fun ForwardedTag(m: Message, isMe: Boolean, inChannel: Boolean, openCid: String = "") {
    val c = Fariin.colors
    val fc = m.fwdChannel?.takeIf { !inChannel && !m.deleted }
    if (fc == null) {
        val src = m.forwardSource?.takeIf { !m.deleted }
        if (src != null) { ForwardedFrom(src, isMe, openCid); return }
        Text("Forwarded", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            color = if (isMe) Color.White.copy(alpha = 0.7f) else c.secondary)
        return
    }
    val nav = LocalChannelNav.current
    val scope = rememberCoroutineScope()
    val entries by ChannelFollows.entries.collectAsState()
    var followedHere by remember { mutableStateOf(false) }
    val following = followedHere || entries.containsKey(fc.cid) || com.fariin.app.data.ChatsNow.list.any { it.id == fc.cid }
    val ink = if (isMe) Color.White else c.label
    Row(
        Modifier.padding(vertical = 2.dp).height(32.dp).clip(CircleShape)
            .background(if (isMe) Color.White.copy(alpha = 0.18f) else c.label.copy(alpha = 0.06f))
            .clickable { nav.page(fc.cid) }.padding(start = 5.dp, end = 5.dp)
            .semantics { contentDescription = "Forwarded from ${fc.title}, channel" + if (following) "" else ". Follow" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(fc.photoUrl.ifEmpty { null }, 22.dp, squircle = true)
        Spacer(Modifier.width(7.dp))
        Text(fc.title, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = ink, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp))
        Spacer(Modifier.width(6.dp))
        Text("Channel", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = if (isMe) Color.White else ChannelBlue,
            modifier = Modifier.clip(RoundedCornerShape(8.5.dp))
                .background(if (isMe) Color.White.copy(alpha = 0.22f) else ChannelBlue.copy(alpha = 0.13f))
                .padding(horizontal = 6.dp, vertical = 1.dp))
        Spacer(Modifier.width(8.dp))
        if (following) {
            Icon(ImageVector.vectorResource(R.drawable.ic_sf_chevron_right), null, tint = ink.copy(alpha = 0.45f), modifier = Modifier.size(18.dp))
        } else {
            Box(Modifier.height(24.dp).clip(CircleShape).background(if (isMe) Color.White else ChannelBlue)
                .clickable {
                    followedHere = true
                    scope.launch { runCatching { ChannelService.follow(fc.cid) }.onFailure { followedHere = false } }
                }.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                Text("Follow", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = if (isMe) ChannelBlue else Color.White)
            }
        }
    }
}
