package com.fariin.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.horizontalScroll
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
    var reactorsFor by remember { mutableStateOf<ActivityItem?>(null) }
    // The newest reaction sheets, fetched ahead so a tap opens them filled (owner 2026-11-10).
    LaunchedEffect(items != null) { items?.let { com.fariin.app.data.ReplyReactors.prefetch(it) } }
    reactorsFor?.let { item -> ReactorsSheet(item, onDismiss = { reactorsFor = null }, onComment = { reactorsFor = null; onOpen(item) }) }
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
                // The owner's icon: a chat bubble with a check (2026-11-10), as in the Chats menu.
                DropdownMenuItem(text = { Text("Mark All as Read") },
                    leadingIcon = { Icon(ImageVector.vectorResource(R.drawable.ic_mark_all_read), contentDescription = null, tint = c.label) },
                    onClick = { open = false; scope.launch { ActivityFeed.markRead() } })
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
            // Not from people I blocked since (iOS ActivityFeedView; audit A9).
            val blocked by com.fariin.app.data.BlockList.state.collectAsState()
            val shown = list?.let { l -> if (unreadOnly) l.filter { it.id in unreadAtOpen.orEmpty() } else l }
                ?.filter { it.fromUid.isEmpty() || !blocked.contains(it.fromUid) }
            when {
                shown == null -> com.fariin.app.ui.components.LoadingLine("Loading…", Modifier.padding(16.dp), fontSize = 17.sp)
                shown.isEmpty() -> Text(if (unreadOnly) "Nothing unread." else "When people reply to or react to your comments, you'll see it here.",
                    fontSize = 15.sp, color = c.secondary, modifier = Modifier.padding(horizontal = 24.dp, vertical = 32.dp).fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                else -> shown.forEach { item -> ActivityRow(item) {
                    when {
                        // Many reacted: who reacted, by emoji (owner 2026-11-09). One person: the comment.
                        item.isReact && item.others > 0 -> reactorsFor = item
                        // A quote in a public channel: that channel, at the quoting post.
                        item.isQuote && item.whereCid.isNotEmpty() -> {
                            if (item.whereMid.isNotEmpty()) com.fariin.app.push.PushRouter.pendingMid.value =
                                com.fariin.app.push.PushOpen(item.whereCid, item.whereMid, android.os.SystemClock.elapsedRealtime())
                            com.fariin.app.push.PushRouter.pendingOpen.value = item.whereCid
                        }
                        // A quote elsewhere: my post's sheet, on Quotes.
                        item.isQuote -> { com.fariin.app.ui.channels.PostQuotesSheet.startTab = 1; onOpen(item) }
                        else -> onOpen(item)
                    }
                } }
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
        if (i.isReact || (i.isComment && i.hasFrom2)) ReactFaces(i) else if (i.isQuote) Box(Modifier.size(56.dp)) {
            Avatar(i.fromPhoto.ifEmpty { null }, 56.dp, squircle = i.fromUid.isEmpty(), kind = if (i.fromUid.isEmpty()) AvatarKind.CHANNEL else AvatarKind.PERSON)
            Box(Modifier.align(Alignment.BottomEnd).offset(x = 3.dp, y = 3.dp).size(24.dp).clip(CircleShape).background(c.bgSecondary).padding(2.dp)) {
                Box(Modifier.size(20.dp).clip(CircleShape).background(Color(0xFF8E8E93)), contentAlignment = Alignment.Center) {
                    Icon(ImageVector.vectorResource(R.drawable.ic_quote), null, tint = Color.White, modifier = Modifier.size(11.dp))
                }
            }
        } else Box(Modifier.size(56.dp)) {
            // A reply: the person's face with the channel's squircle as a badge.
            Avatar(i.fromPhoto.ifEmpty { if (i.fromUid.isEmpty()) i.chPhoto else "" }.ifEmpty { null }, 56.dp,
                squircle = i.fromUid.isEmpty(), kind = if (i.fromUid.isEmpty()) AvatarKind.CHANNEL else AvatarKind.PERSON)
            Box(Modifier.align(Alignment.BottomEnd).offset(x = 3.dp, y = 3.dp).size(24.dp).clip(CircleShape).background(c.bgSecondary).padding(2.dp)) {
                // A mention: a small blue "@" badge instead of the channel's photo.
                if (i.isMention) Box(Modifier.size(20.dp).clip(CircleShape).background(c.blue), contentAlignment = Alignment.Center) {
                    Text("@", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                } else Avatar(i.chPhoto.ifEmpty { null }, 20.dp, squircle = true, kind = AvatarKind.CHANNEL)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            if (i.isComment) {
                // Comments on my channel's post (owner 2026-11-09): "Nan commented in VE3", or with
                // more people "Abdi, Nan and 3 others commented"; the newest words; my post.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(ActivityRules.reactNames(i)) }
                        append(if (i.hasFrom2 || i.others > 0) " commented" else " commented in " + i.chTitle.ifEmpty { "a channel" })
                    }, fontSize = 15.sp, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Text(" · " + shortAgo(i.at), fontSize = 13.sp, color = c.secondary, maxLines = 1)
                }
                if (i.text.isNotEmpty()) Text(i.text, fontSize = 15.sp, color = c.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("On your post" + if (i.post.isNotEmpty()) ": ${i.post}" else "", fontSize = 14.sp, color = c.secondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else if (i.isQuote) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(i.fromName.ifEmpty { "Someone" }) }
                        append(" quoted your post")
                    }, fontSize = 15.sp, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Text(" · " + shortAgo(i.at), fontSize = 13.sp, color = c.secondary, maxLines = 1)
                }
                if (i.text.isNotEmpty()) Text(i.text, fontSize = 15.sp, color = c.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(ActivityRules.postLine(i), fontSize = 14.sp, color = c.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else if (i.isMention) {
                // "Nan mentioned you · 5m" / their words, @handles blue / "In VE3 · On the post: …" (CHANNEL-SYNC-SPEC 10).
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(i.fromName.ifEmpty { "Someone" }) }
                        append(" mentioned you")
                    }, fontSize = 15.sp, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Text(" · " + shortAgo(i.at), fontSize = 13.sp, color = c.secondary, maxLines = 1)
                }
                if (i.text.isNotEmpty()) Text(buildAnnotatedString {
                    append(i.text)
                    com.fariin.app.data.PostReplyRules.mentions(i.text).forEach { (r, _) -> addStyle(SpanStyle(color = c.blue), r.first, r.last + 1) }
                }, fontSize = 15.sp, color = c.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("In " + i.chTitle.ifEmpty { "a channel" } + if (i.post.isNotEmpty()) " · On the post: ${i.post}" else "",
                    fontSize = 14.sp, color = c.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else if (i.isReact) {
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


/**
 * Who reacted to my comment (owner 2026-11-09, design 3 as a sheet): my comment and the post's
 * picture on top (a tap opens the comment), "All N" then one chip per emoji, then the people, newest
 * first; a tap opens their profile. Pages of 60 from the server as the list nears its end.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ReactorsSheet(i: ActivityItem, onDismiss: () -> Unit, onComment: () -> Unit) {
    val c = Fariin.colors
    val people = com.fariin.app.ui.people.LocalPeopleNav.current
    var emoji by remember { mutableStateOf("") }
    var rx by remember { mutableStateOf(i.rx) }
    var list by remember { mutableStateOf<List<com.fariin.app.data.Reactor>?>(null) }
    var next by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    suspend fun load(more: Boolean) {
        if (loading) return
        loading = true
        val asked = emoji
        var cursor = if (more) next else ""
        var got = emptyList<com.fariin.app.data.Reactor>()
        var page: com.fariin.app.data.ReactorsPage? = null
        // The server filters an emoji after taking 60 likes, so a page can be empty with more behind
        // it (audit A12): keep asking, at most five pages, until someone shows.
        for (k in 0 until 5) {
            val p = com.fariin.app.data.ReplyReactors.page(i.cid, i.mid, i.mineRid, i.mineAid, asked, cursor) ?: run { page = null; break }
            page = p; got = got + p.people; cursor = p.next
            if (got.isNotEmpty() || p.next.isEmpty()) break
        }
        loading = false
        if (asked != emoji) return   // the chip changed meanwhile (audit A15)
        val pg = page
        if (pg == null) { if (!more) failed = true; return }
        failed = false
        if (pg.rx.isNotEmpty()) rx = pg.rx
        list = if (more) list.orEmpty() + got else got
        next = cursor
        if (!more) com.fariin.app.data.ReplyReactors.keep(i.id, asked, com.fariin.app.data.ReplyReactors.Kept(got, cursor, rx))
    }
    LaunchedEffect(emoji) {
        // The last answer for this tab first (instant), then the fresh one.
        val k = com.fariin.app.data.ReplyReactors.kept(i.id, emoji)
        list = k?.people; next = k?.next ?: ""; if (k != null && k.rx.isNotEmpty()) rx = k.rx
        load(false)
    }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.bgSecondary) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            // My comment and the post's picture.
            Row(Modifier.fillMaxWidth().clickable(onClick = onComment).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (i.mineAid.isNotEmpty()) "Your reply" else "Your comment", fontSize = 13.sp, color = c.secondary)
                    Text(i.mineText, fontSize = 15.sp, color = c.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (i.thumb.isNotEmpty()) {
                    Spacer(Modifier.width(10.dp))
                    AsyncImage(i.thumb, null, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(c.label.copy(alpha = 0.06f)))
                }
            }
            // All N, then a chip per emoji, most used first.
            val total = rx.values.sum()
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val chips = listOf("" to total) + rx.entries.sortedByDescending { it.value }.map { it.key to it.value }
                for ((e, n) in chips) {
                    val on = emoji == e
                    Text((if (e.isEmpty()) "All" else e) + " " + com.fariin.app.channel.ChannelRules.compactCount(n), fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold, color = if (on) c.bg else c.label,
                        modifier = Modifier.clip(CircleShape).background(if (on) c.label else c.label.copy(alpha = 0.08f))
                            .clickable { emoji = e }.padding(horizontal = 14.dp, vertical = 6.dp))
                }
            }
            val rows = list
            when {
                rows == null && failed -> Text("Couldn't load. Tap to try again.", fontSize = 15.sp, color = c.secondary,
                    modifier = Modifier.fillMaxWidth().clickable { scope.launch { load(false) } }.padding(24.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                // No spinner (owner 2026-11-10): grey rows shaped like the people, as many as the tab holds (max 8).
                rows == null -> Column(Modifier.fillMaxWidth()) {
                    val n = (if (emoji.isEmpty()) rx.values.sum() else rx[emoji] ?: 0).coerceIn(1, 8)
                    val shade = c.label.copy(alpha = 0.08f)
                    repeat(n) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(48.dp).clip(CircleShape).background(shade))
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Box(Modifier.width(140.dp).height(14.dp).clip(RoundedCornerShape(7.dp)).background(shade))
                                Spacer(Modifier.height(8.dp))
                                Box(Modifier.width(90.dp).height(12.dp).clip(RoundedCornerShape(6.dp)).background(shade))
                            }
                        }
                    }
                }
                else -> androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                    items(rows.size, key = { rows[it].uid + rows[it].emoji }) { k ->
                        val r = rows[k]
                        if (k >= rows.size - 10 && next.isNotEmpty()) LaunchedEffect(next) { load(true) }
                        Row(Modifier.fillMaxWidth().clickable(enabled = r.uid.isNotEmpty()) { onDismiss(); people.contact(r.uid) }
                            .padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(48.dp)) {
                                Avatar(r.photo.ifEmpty { null }, 48.dp)
                                Box(Modifier.align(Alignment.BottomEnd).offset(x = 3.dp, y = 3.dp).size(22.dp).clip(CircleShape)
                                    .background(c.bgSecondary), contentAlignment = Alignment.Center) { Text(r.emoji, fontSize = 12.sp) }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(r.name.ifEmpty { "Someone" }, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.label, maxLines = 1)
                                if (r.handle.isNotEmpty()) Text("@" + r.handle, fontSize = 14.sp, color = c.secondary, maxLines = 1)
                            }
                            if (r.atMs > 0) Text(shortAgo(r.atMs.toDouble()), fontSize = 13.sp, color = c.secondary)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
