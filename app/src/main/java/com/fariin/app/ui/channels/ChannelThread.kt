package com.fariin.app.ui.channels

import com.fariin.app.data.NewBadgeSpots
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.ui.res.vectorResource
import androidx.compose.material.icons.filled.Close
import android.content.Intent
import androidx.compose.animation.AnimatedContent
import com.fariin.app.ui.components.floating
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fariin.app.channel.ChannelInfo
import com.fariin.app.channel.ChannelRules
import com.fariin.app.channel.ChannelRules.Role
import com.fariin.app.data.Message
import com.fariin.app.ui.theme.Fariin

/**
 * What a channel adds to the chat page (Kulan-iOS ThreadView channel parts, MessageRowModelBuilder
 * channel rows, ChannelFollowerBar). Every post is the channel speaking, drawn as received for
 * everyone, with its eye count, counts-only reactions and a share button beside it.
 */
data class ChannelThreadUi(
    val info: ChannelInfo,
    val role: Role,
    /** Looking from outside (not following, not an admin). */
    val guest: Boolean,
    val muted: Boolean,
    val canReact: Boolean,
    val offered: List<String>,
    val pickable: List<String>,
    /** My pick on a post (one on its way first) and the one the server already holds. */
    val mine: (String) -> String? = { null },
    val held: (String) -> String? = { null },
    /** The counts a post shows (the server's last answer while newer than the post). */
    val counts: (Message) -> Map<String, Int> = { it.reactionCounts },
    val onFollow: () -> Unit = {},
    /** null = unmute; 0 = forever; else the end in ms. */
    val onMute: (Double?) -> Unit = {},
    val onReact: (Message, String?) -> Unit = { _, _ -> },
    val onReport: (Message) -> Unit = {},
    /** The follow's own problem in the server's words, shown over the bar. */
    val problem: String? = null,
    /** Follow tapped, the server's answer on its way: drawn as followed already. */
    val joining: Boolean = false,
    /** A short line over the chat (a toast). */
    val notice: (String) -> Unit = {},
    /** The admin card: I started this channel; I may change its name, photo and description. */
    val createdByMe: Boolean = false,
    val canEdit: Boolean = false,
    /** The link Share on the admin card sends (public link, else the invite link); null = none. */
    val shareLink: suspend () -> String? = { null },
)

/** A post's eye count for the meta line (null = not a channel post). */
val LocalPostViews = compositionLocalOf<Int?> { null }

@Composable
fun PostViews(color: Color) {
    val v = LocalPostViews.current ?: return
    Icon(ImageVector.vectorResource(com.fariin.app.R.drawable.ic_views), contentDescription = null, tint = color, modifier = Modifier.size(11.dp))
    Spacer(Modifier.width(2.dp))
    Text(ChannelRules.compactCount(v) + "  ", fontSize = 11.sp, color = color, maxLines = 1,
        modifier = Modifier.semantics { contentDescription = "$v views" })
}

/** The chat is ticking rows (Select): a post's share button hides, as iOS. */
val LocalPostSelecting = compositionLocalOf { false }

/**
 * The round share button beside a post (iOS applyShareButton, owner 2026-11-07 "share use ios
 * style"): 32 dp, soft fill (black 8% light, white 12% dark), no edge or shadow, the filled curved
 * arrow in the label colour; 8 dp right of the bubble, its bottom level with the bubble's bottom.
 * Hidden on a post still sending or failed, while selecting, and under Protect Content. It fades in
 * (180 ms) the first time it shows instead of moving in with the row.
 */
@Composable
fun PostShareButton(ch: ChannelThreadUi, m: Message) {
    // Not while it is still on its way: there is no post to link to yet.
    if (m.deleted || ch.info.restricted || m.isSystem || m.sendState != null || LocalPostSelecting.current) return
    val c = Fariin.colors
    val ctx = LocalContext.current
    val shown = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(Unit) { shown.animateTo(1f, androidx.compose.animation.core.tween(180)) }
    Box(
        Modifier.padding(start = 8.dp).size(32.dp).graphicsLayer { alpha = shown.value }.clip(CircleShape)
            .background(if (c.dark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.08f))
            .clickable { sharePost(ctx, ch.info, m) },
        contentAlignment = Alignment.Center,
    ) {
        Icon(ImageVector.vectorResource(com.fariin.app.R.drawable.ic_share_turn_right), contentDescription = "Share",
            tint = c.label, modifier = Modifier.size(15.dp))
    }
}

/**
 * A post's words for Share. A location card goes out as its place and coordinates, a contact card as
 * nothing (the channel link alone): the card marker is never shown as text.
 */
internal fun shareBody(m: Message): String? {
    com.fariin.app.data.CardRules.location(m.text)?.let { l ->
        return com.fariin.app.data.CardRules.title(l) + "\n" + com.fariin.app.data.CardRules.coords(l.lat, l.lon)
    }
    if (com.fariin.app.data.CardRules.isCard(m.text)) return null
    return m.bodyText.takeIf { it.isNotBlank() }
}

fun sharePost(ctx: android.content.Context, info: ChannelInfo, m: Message) {
    val link = if (info.username.isEmpty()) "" else "https://fariin.com/${info.username}"
    val text = listOf(shareBody(m), link.ifEmpty { null }).filterNotNull().joinToString("\n\n")
    if (text.isEmpty()) return
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    ctx.startActivity(Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Counts-only chips under a post; nobody yet = nothing. Tapping one picks (or takes back) it. */
@Composable
fun ChannelChips(ch: ChannelThreadUi, m: Message) {
    if (ch.info.reactMode == "off" || m.isSystem || m.deleted) return
    val retired = com.fariin.app.ui.thread.rememberRetired()
    val chips = ChannelRules.chips(ch.counts(m), ch.mine(m.id), ch.held(m.id)).filterNot { retired(it.emoji) }
    if (chips.isEmpty()) return
    val c = Fariin.colors
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 3.dp), horizontalArrangement = Arrangement.Start) {
        chips.forEach { chip ->
            Row(
                Modifier.padding(end = 4.dp).clip(CircleShape)
                    .background(if (chip.mine) c.blue.copy(alpha = 0.18f) else if (c.dark) Color(0xFF26262B) else Color(0xFFF2F2F2))
                    .then(if (chip.mine) Modifier.border(1.dp, c.blue.copy(alpha = 0.5f), CircleShape) else Modifier)
                    .clickable(enabled = ch.canReact) { ch.onReact(m, ChannelRules.nextPick(ch.mine(m.id), chip.emoji)) }
                    .padding(horizontal = 8.dp, vertical = 3.dp)
                    .semantics { contentDescription = "${com.fariin.app.ui.thread.CustomReaction.spoken(chip.emoji)}, ${chip.count}" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                com.fariin.app.ui.thread.ReactionGlyph(chip.emoji, 15.sp, 20.dp)
                Spacer(Modifier.width(4.dp))
                Text(ChannelRules.compactCount(chip.count), fontSize = 13.sp, color = if (chip.mine) c.blue else c.label)
            }
        }
    }
}

/** A post's chips as shown (counts only, most first); empty = none, or reactions off. */
@Composable
fun rememberPostChips(ch: ChannelThreadUi, m: Message): List<ChannelRules.Chip> {
    if (ch.info.reactMode == "off" || m.isSystem || m.deleted) return emptyList()
    val retired = com.fariin.app.ui.thread.rememberRetired()
    return ChannelRules.chips(ch.counts(m), ch.mine(m.id), ch.held(m.id)).filterNot { retired(it.emoji) }
}

/**
 * A post's reactions INSIDE its bubble, under the words (iOS MessageRowLayout, reactions inside):
 * pills 30 high, 11 in on each side, the emoji in a 20 box, the count in 12 semibold, 6 apart and
 * 6 between rows, wrapping to the bubble's width. The time line joins the last row at its right
 * end when it fits there, else it takes a row of its own under the pills, on the right.
 * Mine is the solid text colour with the count in the page colour; the rest a faint wash.
 */
@Composable
fun ChannelChipsInBubble(ch: ChannelThreadUi, m: Message, chips: List<ChannelRules.Chip>, top: androidx.compose.ui.unit.Dp = 6.dp,
    meta: @Composable () -> Unit) {
    val c = Fariin.colors
    androidx.compose.ui.layout.Layout(content = {
        chips.forEach { chip ->
            Row(
                Modifier.height(30.dp).clip(CircleShape)
                    .background(if (chip.mine) c.label else c.label.copy(alpha = 0.08f))
                    .clickable(enabled = ch.canReact) { ch.onReact(m, ChannelRules.nextPick(ch.mine(m.id), chip.emoji)) }
                    .padding(horizontal = 11.dp)
                    .semantics { contentDescription = "${com.fariin.app.ui.thread.CustomReaction.spoken(chip.emoji)}, ${chip.count}" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                com.fariin.app.ui.thread.ReactionGlyph(chip.emoji, 17.sp, 20.dp)
                Text(" " + ChannelRules.compactCount(chip.count), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    color = if (chip.mine) c.bg else c.secondary, maxLines = 1)
            }
        }
        // Exactly one node whatever meta() draws: the measure takes the last child as the time.
        Box { meta() }
    }, modifier = Modifier.padding(top = top)) { measurables, constraints ->
        val maxW = constraints.maxWidth
        val gap = 6.dp.roundToPx()
        val metaGap = 8.dp.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val pills = measurables.dropLast(1).map { it.measure(loose) }
        val mt = measurables.last().measure(loose)
        // Rows of pills, each as wide as fits.
        val rows = mutableListOf(mutableListOf<Int>())
        var rowW = 0
        val rowWs = mutableListOf(0)
        pills.forEachIndexed { i, p ->
            if (rows.last().isNotEmpty() && rowW + gap + p.width > maxW) {
                rows.add(mutableListOf()); rowWs.add(0); rowW = 0
            }
            rowW = if (rows.last().isEmpty()) p.width else rowW + gap + p.width
            rows.last().add(i); rowWs[rowWs.lastIndex] = rowW
        }
        val pillH = pills.maxOfOrNull { it.height } ?: 0
        val lastW = rowWs.last()
        val joins = lastW + metaGap + mt.width <= maxW
        val widest = rowWs.maxOrNull() ?: 0
        val w = (if (joins) maxOf(widest, lastW + metaGap + mt.width) else maxOf(widest, mt.width)).coerceAtMost(maxW)
        val rowsH = rows.size * pillH + (rows.size - 1) * gap
        val h = if (joins) rowsH else rowsH + 2.dp.roundToPx() + mt.height
        layout(w, h) {
            rows.forEachIndexed { r, idx ->
                var x = 0
                val y = r * (pillH + gap)
                idx.forEach { i -> pills[i].place(x, y + (pillH - pills[i].height) / 2); x += pills[i].width + gap }
            }
            if (joins) mt.place(w - mt.width, rowsH - pillH + (pillH - mt.height) / 2)
            else mt.place(w - mt.width, rowsH + 2.dp.roundToPx())
        }
    }
}

private data class PostItem(val title: String, val icon: ImageVector, val destructive: Boolean = false,
                            /** The row's NewBadgeSpots id, for the admin panel's "New" pill. */
                            val badge: String? = null, val run: () -> Unit)

/**
 * Long press on a post: the channel's reactions (its own list, or the first six of the 73, with
 * a "more" button that opens the whole set), then Copy, Share, Report, and Delete for an admin.
 */
@Composable
fun ChannelPostMenu(
    ch: ChannelThreadUi, m: Message, onDismiss: () -> Unit,
    onCopy: (String) -> Unit, onDelete: (Message) -> Unit,
    onQuote: ((Message) -> Unit)? = null,
    onSelect: ((Message) -> Unit)? = null,
    onEdit: ((Message) -> Unit)? = null,
    onEditHistory: ((Message) -> Unit)? = null,
    /** null: no Pin row; else whether the post is pinned now. */
    pinned: Boolean? = null,
    onPin: ((Message) -> Unit)? = null,
) {
    val c = Fariin.colors
    val ctx = LocalContext.current
    var more by remember { mutableStateOf(false) }
    val mine = ch.mine(m.id)
    // The owner's quote mark (a speech bubble with two lines), as everywhere a quote shows.
    val quoteIcon = ImageVector.vectorResource(com.fariin.app.R.drawable.ic_quote)
    // The owner's comment icon (iOS ic_reply_comment), on "Turn On Replies".
    val replyIcon = ImageVector.vectorResource(com.fariin.app.R.drawable.ic_reply_comment)
    val items = buildList {
        if (!m.deleted) {
            // A contact or location card is no text to copy (as the chat menu, MessageActions).
            if (!ch.info.restricted && m.bodyText.isNotEmpty() && !com.fariin.app.data.CardRules.isCard(m.text)) add(PostItem("Copy", Icons.Filled.ContentCopy, badge = NewBadgeSpots.CHAT_MENU_COPY) { onCopy(m.bodyText) })
            if (!ch.info.restricted) add(PostItem("Share", Icons.AutoMirrored.Filled.Forward, badge = NewBadgeSpots.CHAT_MENU_FORWARD) { sharePost(ctx, ch.info, m) })
            // Quote into a chat of mine with my own words (iOS 6848baf9): where the post may be forwarded.
            // Not when the channel turned Quotes off, except for its own owner/admins (iOS 1280e92d).
            if (onQuote != null && !ch.info.restricted && com.fariin.app.data.MessageRules.canForward(m) && ch.info.canQuote(ch.role == Role.ADMIN))
                add(PostItem("Quote", quoteIcon, badge = NewBadgeSpots.CHAT_MENU_QUOTE) { onDismiss(); onQuote(m) })
            // Tick several posts to forward, copy or (owner and admins) delete them together.
            if (onSelect != null) add(PostItem("Select", Icons.Outlined.CheckCircle) { onDismiss(); onSelect(m) })
            if (ch.role != Role.ADMIN) add(PostItem("Report", Icons.Filled.Flag, destructive = true, badge = NewBadgeSpots.CHAT_MENU_REPORT) { ch.onReport(m) })
        }
        // The owner and admins edit, pin and see a post's earlier versions (the history is on the server).
        if (ch.role == Role.ADMIN && !m.deleted) {
            // onEdit is only passed when this post may be edited (my own, in the edit window).
            if (onEdit != null) add(PostItem("Edit", Icons.Outlined.Edit, badge = NewBadgeSpots.CHAT_MENU_EDIT) { onDismiss(); onEdit(m) })
            if (onEditHistory != null && m.edited) add(PostItem("Edit History", Icons.Outlined.History, badge = NewBadgeSpots.CHAT_MENU_EDIT_HISTORY) { onDismiss(); onEditHistory(m) })
            if (pinned != null && onPin != null) add(PostItem(if (pinned) "Unpin" else "Pin", Icons.Outlined.PushPin, badge = NewBadgeSpots.CHAT_MENU_PIN) { onDismiss(); onPin(m) })
            // Replies off or on for this post (iOS, owner 2026-11-06): the Change info right, which the
            // server checks too. The post's own switch wins over the channel's; the post changes on
            // screen at once (PostRepliesSwitch), older posts included.
            if (ch.canEdit && m.sendState == null) {
                val off = com.fariin.app.data.PostReplyRules.postOff(m.repliesOff, ch.info.repliesOff, PostRepliesSwitch.of(m.id))
                add(PostItem(if (off) "Turn On Replies" else "Turn Off Replies",
                    if (off) replyIcon else Icons.Outlined.Block, badge = NewBadgeSpots.CHAT_MENU_REPLIES) {
                    onDismiss(); PostRepliesSwitch.set(ch.info.cid, m.id, !off, ch.notice)
                })
            }
        }
        if (ch.role == Role.ADMIN) add(PostItem("Delete", Icons.Filled.Delete, destructive = true) { onDelete(m) })
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                if (ch.canReact && !m.deleted && !m.isSystem && m.sendState == null) {
                    if (more) {
                        LazyVerticalGrid(
                            GridCells.Fixed(7),
                            Modifier.fillMaxWidth().heightIn(max = 320.dp).shadow(8.dp, RoundedCornerShape(18.dp))
                                .clip(RoundedCornerShape(18.dp)).background(c.card).padding(8.dp),
                        ) {
                            items(ch.pickable) { e ->
                                Box(
                                    Modifier.size(44.dp).clip(CircleShape)
                                        .background(if (ChannelRules.sameEmoji(mine, e)) c.secondary.copy(alpha = 0.22f) else Color.Transparent)
                                        .clickable { ch.onReact(m, ChannelRules.nextPick(mine, e)); onDismiss() },
                                    contentAlignment = Alignment.Center,
                                ) { com.fariin.app.ui.thread.ReactionGlyph(e, 26.sp, 32.dp) }
                            }
                        }
                    } else {
                        // One pill, at most seven slots wide (iOS d0617d33): a channel's longer list (up to 12)
                        // scrolls sideways inside it instead of growing past the screen or into a second row.
                        Row(
                            Modifier.shadow(8.dp, CircleShape).clip(CircleShape).background(c.card)
                                .widthIn(max = 44.dp * 7 + 12.dp)
                                .horizontalScroll(androidx.compose.foundation.rememberScrollState())
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            for (e in ch.offered) {
                                Box(
                                    Modifier.size(44.dp).clip(CircleShape)
                                        .background(if (ChannelRules.sameEmoji(mine, e)) c.secondary.copy(alpha = 0.22f) else Color.Transparent)
                                        .clickable { ch.onReact(m, ChannelRules.nextPick(mine, e)); onDismiss() },
                                    contentAlignment = Alignment.Center,
                                ) { com.fariin.app.ui.thread.ReactionGlyph(e, 26.sp, 32.dp) }
                            }
                            if (ch.pickable.size > ch.offered.size) {
                                Box(Modifier.size(36.dp).clip(CircleShape).background(c.bgSecondary).clickable { more = true },
                                    contentAlignment = Alignment.Center) {
                                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "More reactions", tint = c.secondary)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
                Text(
                    if (m.deleted) "This post was deleted" else com.fariin.app.data.CardRules.previewWord(m.text) ?: m.bodyText.ifEmpty { "Post" },
                    fontSize = 15.sp, color = c.label, maxLines = 4, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(18.dp))
                        .background(if (c.dark) Color(0xFF26262B) else Color(0xFFF2F2F2)).padding(horizontal = 14.dp, vertical = 8.dp),
                )
                Spacer(Modifier.height(10.dp))
                if (items.isNotEmpty()) Column(Modifier.width(250.dp).shadow(8.dp, RoundedCornerShape(14.dp))
                    .clip(RoundedCornerShape(14.dp)).background(c.card)) {
                    items.forEachIndexed { i, it ->
                        if (i > 0) HorizontalDivider(thickness = 0.5.dp, color = c.separator)
                        Row(Modifier.fillMaxWidth().clickable { onDismiss(); it.run() }.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            if (com.fariin.app.ui.components.isNew(it.badge)) Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                Text(it.title, fontSize = 17.sp, color = if (it.destructive) c.red else c.label, modifier = Modifier.weight(1f, fill = false))
                                com.fariin.app.ui.components.NewPill(Modifier.padding(start = 8.dp, end = 8.dp))
                            } else Text(it.title, fontSize = 17.sp, color = if (it.destructive) c.red else c.label, modifier = Modifier.weight(1f))
                            Icon(it.icon, null, tint = if (it.destructive) c.red else c.label, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}

/**
 * The follower's bar where the composer would be (ChannelFollowerBar): one capsule that is blue
 * Follow while I do not follow and becomes Mute / Unmute the moment Follow is tapped. A tap mutes
 * or unmutes for good; a long press offers 1 hour, 8 hours, 1 day, 1 week and Mute Forever.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ChannelBottomBar(ch: ChannelThreadUi, notice: String?) {
    val c = Fariin.colors
    val joined = ch.role != Role.VISITOR || ch.joining
    var menu by remember { mutableStateOf(false) }
    val banned = ch.info.bannedUids.isNotEmpty() && ch.role == Role.VISITOR && ch.info.bannedUids.contains(com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid)
    // No strip behind it (iOS systemBarChrome): the capsule floats over the chat's background.
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        (ch.problem ?: notice)?.let {
            Text(it, fontSize = 14.sp, color = c.secondary, textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 8.dp))
        }
        // iOS SimilarChannels: after following in this run, a strip of channels like this one, until its X.
        if (joined && SimilarChannelsStore.showsAfterFollow(ch.info.cid)) SimilarChannelsStrip(ch.info.cid)
        if (banned) {
            Text("You're banned from this channel.", fontSize = 15.sp, color = c.secondary, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(c.card).padding(14.dp))
            return@Column
        }
        // Follow: a blue capsule. Following: the chat's glass (iOS liquidGlass, tinted blue only for Follow).
        val capsule = RoundedCornerShape(25.dp)
        Box {
            Box(
                Modifier.fillMaxWidth().height(50.dp).floating(capsule, fill = if (joined) null else c.blue).clip(capsule)
                    .combinedClickable(
                        onClick = {
                            if (!joined) { SimilarChannelsStore.followed(ch.info.cid); ch.onFollow() }
                            else ch.onMute(if (ch.muted) null else 0.0)
                        },
                        onLongClick = { if (joined) menu = true },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                val label = when { !joined -> "Follow"; ch.muted -> "Unmute"; else -> "Mute" }
                AnimatedContent(label, transitionSpec = {
                    (fadeIn() + slideInVertically { it / 3 }) togetherWith (fadeOut() + slideOutVertically { -it / 3 })
                }, label = "barText") { t ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (t != "Follow") {
                            Icon(if (t == "Unmute") ImageVector.vectorResource(com.fariin.app.R.drawable.ic_bell) else ImageVector.vectorResource(com.fariin.app.R.drawable.ic_bell_off), null,
                                tint = c.label, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(t, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = if (t == "Follow") Color.White else c.label)
                    }
                }
            }
            DropdownMenu(menu, onDismissRequest = { menu = false }, shape = com.fariin.app.ui.components.FariinMenu.shape, border = com.fariin.app.ui.components.FariinMenu.border, containerColor = c.card) {
                if (ch.muted) DropdownMenuItem(text = { Text("Unmute") }, onClick = { menu = false; ch.onMute(null) })
                else {
                    for ((label, hours) in listOf("1 hour" to 1, "8 hours" to 8, "1 day" to 24, "1 week" to 168)) {
                        DropdownMenuItem(text = { Text("Mute for $label") }, onClick = {
                            menu = false; ch.onMute(System.currentTimeMillis() + hours * 3_600_000.0)
                        })
                    }
                    DropdownMenuItem(text = { Text("Mute Forever", color = c.red) }, onClick = { menu = false; ch.onMute(0.0) })
                }
            }
        }
    }
}

/** Which channels show "Similar Channels": followed in this run of the app, and not closed with the X. */
internal object SimilarChannelsStore {
    private val just = androidx.compose.runtime.mutableStateListOf<String>()
    private val cache = HashMap<String, List<com.fariin.app.channel.DiscoveredChannel>>()
    fun followed(cid: String) { if (cid !in just) just.add(cid) }
    fun showsAfterFollow(cid: String) = cid in just
    fun close(cid: String) { just.remove(cid) }
    suspend fun load(cid: String) = cache[cid] ?: com.fariin.app.channel.ChannelService.similar(cid).also { cache[cid] = it }
}

/** iOS SimilarChannelsStrip: "Similar Channels", an X, and up to 10 small channel cards in a row. */
@Composable
private fun SimilarChannelsStrip(cid: String) {
    val c = Fariin.colors
    val nav = LocalChannelNav.current
    val list by androidx.compose.runtime.produceState<List<com.fariin.app.channel.DiscoveredChannel>?>(null, cid) { value = SimilarChannelsStore.load(cid) }
    val shown = list?.take(10).orEmpty()
    if (shown.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(20.dp)).background(c.card).padding(vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Similar Channels", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.label, modifier = Modifier.weight(1f))
            Box(Modifier.size(28.dp).clip(CircleShape).clickable { SimilarChannelsStore.close(cid) }, contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Close, "Close Similar Channels", tint = c.secondary, modifier = Modifier.size(16.dp))
            }
        }
        androidx.compose.foundation.lazy.LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            items(shown.size) { i ->
                val ch = shown[i]
                Column(Modifier.width(70.dp).clickable { nav.page(ch.cid) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    com.fariin.app.ui.components.Avatar(ch.photoUrl, 54.dp, squircle = true, kind = com.fariin.app.ui.components.AvatarKind.CHANNEL)
                    Text(ch.title, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = c.label, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text(com.fariin.app.channel.ChannelRules.followersLine(ch.followerCount), fontSize = 10.sp, color = c.secondary, maxLines = 1)
                }
            }
        }
    }
}
