package com.fariin.app.ui.settings

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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.fariin.app.R
import com.fariin.app.data.ActivityFeed
import com.fariin.app.data.ActivityItem
import com.fariin.app.data.ActivityRules
import com.fariin.app.ui.components.Avatar
import com.fariin.app.ui.components.AvatarKind
import com.fariin.app.ui.theme.Fariin
import kotlinx.coroutines.launch

/**
 * The Activity page (owner 2026-11-09, design "4 + reactions"): replies and reactions to my
 * comments. Opening it marks everything read; the Unread chip keeps what was unread on arrival.
 */
@Composable
fun ActivityScreen(onBack: () -> Unit, onOpen: (ActivityItem) -> Unit) {
    val c = Fariin.colors
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) {
        ActivityFeed.startItems()
        onDispose { ActivityFeed.stopItems() }
    }
    val items by ActivityFeed.items.collectAsState()
    val fresh by ActivityFeed.itemsFresh.collectAsState()
    // What was unread when the page opened: the Unread chip's list. Taken from the SERVER's first
    // answer, not the phone's cache (a cached copy can be older and already read), then all is
    // marked read.
    var unreadAtOpen by remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(fresh) {
        if (!fresh || unreadAtOpen != null) return@LaunchedEffect
        val list = items ?: return@LaunchedEffect
        unreadAtOpen = list.filter { !it.read }.map { it.id }.toSet()
        if (unreadAtOpen!!.isNotEmpty() || (ActivityFeed.summary.value?.unread ?: 0) > 0) ActivityFeed.markRead()
    }
    var unreadOnly by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    BarPage("Activity", onBack = onBack, under = com.fariin.app.ui.components.underBar(scroll), action = {
        var open by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { open = true }, modifier = com.fariin.app.ui.components.barGlass()) {
                Icon(ImageVector.vectorResource(R.drawable.ic_more_ios), contentDescription = "More", tint = c.label)
            }
            com.fariin.app.ui.components.GlassDropdown(expanded = open, onDismissRequest = { open = false },
                shape = com.fariin.app.ui.components.FariinMenu.shape, border = com.fariin.app.ui.components.FariinMenu.border,
                containerColor = com.fariin.app.ui.components.FariinMenu.container) {
                DropdownMenuItem(text = { Text("Mark All as Read") }, onClick = { open = false; scope.launch { ActivityFeed.markRead() } })
            }
        }
    }) { top ->
        Column(Modifier.fillMaxSize().background(c.bgSecondary).verticalScroll(scroll).padding(top = top)) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((label, on) in listOf("All" to !unreadOnly, "Unread" to unreadOnly)) {
                    Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (on) c.bg else c.label,
                        modifier = Modifier.clip(CircleShape).background(if (on) c.label else c.label.copy(alpha = 0.08f))
                            .clickable { unreadOnly = label == "Unread" }.padding(horizontal = 14.dp, vertical = 6.dp))
                }
            }
            val list = items
            val shown = list?.let { l -> if (unreadOnly) l.filter { it.id in unreadAtOpen.orEmpty() } else l }
            when {
                shown == null -> com.fariin.app.ui.components.LoadingLine("Loading…", Modifier.padding(16.dp), fontSize = 17.sp)
                shown.isEmpty() -> Text(if (unreadOnly) "Nothing unread." else "When people reply to or react to your comments, you'll see it here.",
                    fontSize = 15.sp, color = c.secondary, modifier = Modifier.padding(horizontal = 24.dp, vertical = 32.dp).fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                else -> shown.forEach { ActivityRow(it) { onOpen(it) } }
            }
            Spacer(Modifier.size(com.fariin.app.ui.shell.bottomBarInset()))
        }
    }
}

@Composable
private fun ActivityRow(i: ActivityItem, onTap: () -> Unit) {
    val c = Fariin.colors
    val pic = i.thumb.isNotEmpty()
    Row(Modifier.fillMaxWidth().clickable(onClick = onTap).heightIn(min = if (i.isReact) 92.dp else 80.dp)
        .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (i.isReact) ReactFaces(i) else Box(Modifier.size(56.dp)) {
            // A reply: the person's face with the channel's squircle as a badge.
            Avatar(i.fromPhoto.ifEmpty { if (i.fromUid.isEmpty()) i.chPhoto else "" }.ifEmpty { null }, 56.dp,
                squircle = i.fromUid.isEmpty(), kind = if (i.fromUid.isEmpty()) AvatarKind.CHANNEL else AvatarKind.PERSON)
            Box(Modifier.align(Alignment.BottomEnd).offset(x = 3.dp, y = 3.dp).size(24.dp).clip(CircleShape).background(c.bgSecondary).padding(2.dp)) {
                Avatar(i.chPhoto.ifEmpty { null }, 20.dp, squircle = true, kind = AvatarKind.CHANNEL)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            if (i.isReact) {
                // Design 3 (owner 2026-11-09): "Roodo, kream and 12 others" / "reacted to your comment in VE3".
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(ActivityRules.reactNames(i), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (!pic) Text(" " + shortAgo(i.at), fontSize = 13.sp, color = c.secondary, maxLines = 1)
                }
                Text(ActivityRules.reactLine(i) + if (pic) " · " + shortAgo(i.at) else "", fontSize = 15.sp, color = c.secondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                val pills = ActivityRules.pills(i.rx).ifEmpty { if (i.emoji.isNotEmpty()) listOf(i.emoji to 1) else emptyList() }
                if (pills.isNotEmpty()) Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    pills.forEach { (e, n) ->
                        Row(Modifier.clip(CircleShape).background(c.label.copy(alpha = 0.08f)).padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(e, fontSize = 13.sp)
                            Spacer(Modifier.width(4.dp))
                            Text(com.fariin.app.channel.ChannelRules.compactCount(n), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = c.label)
                        }
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(ActivityRules.who(i)) }
                        append(" in ")
                        append(i.chTitle.ifEmpty { "a channel" })
                    }, fontSize = 15.sp, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Text(" · " + shortAgo(i.at), fontSize = 13.sp, color = c.secondary, maxLines = 1)
                }
                if (i.text.isNotEmpty()) Text(i.text, fontSize = 15.sp, color = c.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(ActivityRules.mineLine(i), fontSize = 14.sp, color = c.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        // The post's picture on the right (owner 2026-11-09): 56 dp, radius 12; a video gets a play mark.
        if (pic) {
            Spacer(Modifier.width(10.dp))
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(c.label.copy(alpha = 0.06f)), contentAlignment = Alignment.Center) {
                AsyncImage(i.thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                if (i.thumbKind == "video") Icon(ImageVector.vectorResource(R.drawable.ic_sf_play_fill), null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** Who reacted: one face, or two 40 dp faces stacked in the 56 dp box (the newest bottom-right, ringed). */
@Composable
private fun ReactFaces(i: ActivityItem) {
    val c = Fariin.colors
    fun photo(u: String, p: String) = p.ifEmpty { if (u.isEmpty()) i.chPhoto else "" }.ifEmpty { null }
    if (!i.hasFrom2) {
        Avatar(photo(i.fromUid, i.fromPhoto), 56.dp, squircle = i.fromUid.isEmpty(), kind = if (i.fromUid.isEmpty()) AvatarKind.CHANNEL else AvatarKind.PERSON)
        return
    }
    Box(Modifier.size(56.dp)) {
        Avatar(photo(i.from2Uid, i.from2Photo), 40.dp, modifier = Modifier.align(Alignment.TopStart),
            squircle = i.from2Uid.isEmpty(), kind = if (i.from2Uid.isEmpty()) AvatarKind.CHANNEL else AvatarKind.PERSON)
        Box(Modifier.align(Alignment.BottomEnd).offset(x = 2.5.dp, y = 2.5.dp).size(45.dp).clip(CircleShape).background(c.bgSecondary).padding(2.5.dp)) {
            Avatar(photo(i.fromUid, i.fromPhoto), 40.dp, squircle = i.fromUid.isEmpty(), kind = if (i.fromUid.isEmpty()) AvatarKind.CHANNEL else AvatarKind.PERSON)
        }
    }
}

/** "now", "5m", "3h", "2d", then the date. */
internal fun shortAgo(ms: Double, now: Long = System.currentTimeMillis()): String {
    val s = maxOf(0L, (now - ms.toLong()) / 1000)
    return when {
        s < 60 -> "now"
        s < 3600 -> "${s / 60}m"
        s < 86_400 -> "${s / 3600}h"
        s < 7 * 86_400 -> "${s / 86_400}d"
        else -> java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault()).format(java.util.Date(ms.toLong()))
    }
}
