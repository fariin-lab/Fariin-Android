package com.fariin.app.ui.thread

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.fariin.app.data.QuoteCard
import com.fariin.app.data.QuoteRules
import com.fariin.app.data.QuoteStore
import com.fariin.app.ui.components.Avatar
import com.fariin.app.ui.components.AvatarKind
import com.fariin.app.ui.theme.Fariin

/** iOS quoteAlbumTiles: 2 side by side, 3 = one tall + two stacked, 4 = two by two, 2 dp gaps. */
@Composable
private fun QuoteAlbumTiles(urls: List<String>) {
    val gap = 2.dp
    @Composable
    fun tile(u: String, m: Modifier) = coil3.compose.AsyncImage(u, null, contentScale = ContentScale.Crop, modifier = m)
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
        when (urls.size) {
            2 -> { tile(urls[0], Modifier.weight(1f).fillMaxHeight()); tile(urls[1], Modifier.weight(1f).fillMaxHeight()) }
            3 -> {
                tile(urls[0], Modifier.weight(1f).fillMaxHeight())
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(gap)) {
                    tile(urls[1], Modifier.weight(1f).fillMaxWidth()); tile(urls[2], Modifier.weight(1f).fillMaxWidth())
                }
            }
            else -> for (col in 0..1) Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(gap)) {
                tile(urls[col], Modifier.weight(1f).fillMaxWidth()); tile(urls[col + 2], Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

/**
 * A quoted channel post in a bubble (iOS quote card, owner 2026-11-06, his reference). It sits
 * UNDER my words, with the time on its own line below it ([meta]). The card is outlined, not
 * filled: the channel's 20 dp photo and its name, the post's words (8 lines), then its picture
 * with its own corners (iOS, owner 2026-11-07: words above the picture). Until the first answer lands it shows
 * grey pulsing bars (iOS 2026-11-10), never the raw id.
 * A tap opens the post in its channel; when that channel is the chat already open ([openCid]) it
 * scrolls to the post and lights it instead. A post this reader may not see gets a short notice.
 */
@Composable
internal fun QuoteCardBubble(qid: String, isMe: Boolean, openCid: String?, meta: (@Composable () -> Unit)? = null,
                              /** The message this card sits in (its chat is [openCid]): the server's `hosts`. */ hostMid: String? = null,
                              /** The quoting post has its own photo, video or album: the compact row. */ compact: Boolean = false,
                              /** The words hold the post's own link (iOS 251b4be31, owner's design B): the small link card. */ postLink: Boolean = false) {
    val cards by QuoteStore.cards.collectAsState()
    // Every draw asks: a miss, or an answer older than two minutes, is fetched with the others on screen.
    SideEffect {
        if (openCid != null && hostMid != null) QuoteStore.noteHost(qid, openCid, hostMid)
        QuoteStore.card(qid)
    }
    val card = cards[qid]
    val channelNav = com.fariin.app.ui.channels.LocalChannelNav.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val c = Fariin.colors
    val ink = if (isMe) Color.White else c.label
    val quiet = card == null
    // The picture this phone made when I quoted (shown at once), else the server's copy.
    val image: Any? = QuoteRules.image(card)?.let { url -> QuoteStore.picture(qid) ?: url }
    Column(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp).clip(RoundedCornerShape(12.dp))
                // A soft grey fill in the bubble's own text colour, no edge (owner 2026-11-06: "don't use
                // blue"; earlier "give it a colour"), as iOS.
                .background(ink.copy(alpha = 0.08f))
                .clickable {
                    val q = card ?: return@clickable
                    val notice = QuoteRules.tapNotice(q)
                    if (notice != null) { com.fariin.app.ui.components.Snack.show(notice); return@clickable }
                    if (q.cid.isEmpty()) return@clickable
                    // The chat that is open: ThreadScreen scrolls to the post and lights it.
                    if (q.mid.isNotEmpty()) QuoteStore.pendingJump.value = QuoteStore.Jump(q.cid, q.mid)
                    if (QuoteRules.jumpsInPlace(q, openCid)) return@clickable
                    // Another chat: the keyboard goes down first, so it is not still up on coming back here.
                    keyboard?.hide()
                    focus.clearFocus()
                    // Opened as a thread: a follower sees the channel, a public visitor its posts from outside.
                    channelNav.open(q.cid)
                }
                .padding(if (postLink) 8.dp else 10.dp),
        ) {
            // Until the quote lands (iOS spec 2026-11-10 item 5): no "Channel post / Loading..." words,
            // grey pulsing bars over the photo, the name and two lines, at the card's own size.
            if (quiet) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    com.fariin.app.ui.components.SkeletonBlock(24.dp, 24.dp, radius = 7.dp)
                    Spacer(Modifier.width(6.dp))
                    com.fariin.app.ui.components.SkeletonBlock(110.dp, 14.dp)
                }
                Spacer(Modifier.height(10.dp))
                com.fariin.app.ui.components.SkeletonBlock(200.dp, 12.dp)
                Spacer(Modifier.height(7.dp))
                com.fariin.app.ui.components.SkeletonBlock(140.dp, 12.dp)
                return@Column
            }
            // A post sent as its link (design B): the post's picture on the left (62, corners 9), then
            // the channel's photo and name, two lines of the post, and "fariin.com".
            if (postLink) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (image != null) {
                        Box(Modifier.size(62.dp).clip(RoundedCornerShape(9.dp)).background(c.secondary.copy(alpha = 0.15f))) {
                            coil3.compose.AsyncImage(image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                        Spacer(Modifier.width(10.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Avatar(card?.photoUrl?.ifEmpty { null }, 18.dp, squircle = true, kind = AvatarKind.of(true, true))
                            Spacer(Modifier.width(5.dp))
                            Text(QuoteRules.heading(card), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = ink,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        val words = QuoteRules.body(card)
                        if (words.isNotEmpty()) Text(words, fontSize = 13.sp, color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            lineHeight = 17.sp, modifier = Modifier.padding(top = 2.dp))
                        Text("fariin.com", fontSize = 12.sp, color = ink.copy(alpha = 0.6f), modifier = Modifier.padding(top = 2.dp))
                    }
                }
                return@Column
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // iOS 2026-11-08: the header at the card's own size, photo 24, name 16 semibold.
                Avatar(card?.photoUrl?.ifEmpty { null }, 24.dp, squircle = true, kind = AvatarKind.of(true, true))
                Spacer(Modifier.width(6.dp))
                // Mine sits on my own coloured bubble, where blue would not read: white there.
                Text(QuoteRules.heading(card), fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    color = ink.copy(alpha = if (quiet) 0.6f else 1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                // A short "Quote" tag at the row's right end; the name shrinks to leave it room.
                Spacer(Modifier.width(8.dp))
                Icon(androidx.compose.ui.res.painterResource(com.fariin.app.R.drawable.ic_quote), null, tint = ink.copy(alpha = 0.55f), modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(2.dp))
                Text("Quote", fontSize = 12.sp, color = ink.copy(alpha = 0.55f), maxLines = 1)
            }
            val body = QuoteRules.body(card)
            if (image != null && !compact) {
                // A text-only quoting post (owner 2026-11-07): the quoted words full width, then the
                // quoted picture across the card under them.
                if (body.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(body, fontSize = 14.sp, color = ink.copy(alpha = if (quiet) 0.5f else 1f), maxLines = 8,
                        overflow = TextOverflow.Ellipsis, lineHeight = 18.sp)
                }
                Spacer(Modifier.height(8.dp))
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    Box(Modifier.fillMaxWidth().height(min(220.dp, maxWidth * 0.75f)).clip(RoundedCornerShape(10.dp))
                        .background(c.secondary.copy(alpha = 0.15f))) {
                        // An album shows every picture, up to four, as tiles (iOS 5d2ef4c95).
                        val album = card?.images.orEmpty().take(4)
                        if (album.size >= 2) QuoteAlbumTiles(album)
                        else coil3.compose.AsyncImage(image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                }
            } else if (image != null) {
                // Owner's mockup (2026-11-07, both apps): a quoted post with a picture is one compact
                // row: the picture on the left (42% of the inner width, 0.72 as tall, corners 14), the
                // words on the right, top-aligned, at most 4 lines.
                Spacer(Modifier.height(8.dp))
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val picW = maxWidth * 0.42f
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Box(Modifier.width(picW).height(picW * 0.72f).clip(RoundedCornerShape(14.dp))
                            .background(c.secondary.copy(alpha = 0.15f))) {
                            coil3.compose.AsyncImage(image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                        if (body.isNotEmpty()) {
                            Spacer(Modifier.width(12.dp))
                            Text(body, fontSize = 16.sp, color = ink.copy(alpha = if (quiet) 0.5f else 1f), maxLines = 4,
                                overflow = TextOverflow.Ellipsis, lineHeight = 20.sp, modifier = Modifier.weight(1f))
                        }
                    }
                }
            } else if (body.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(body, fontSize = 14.sp, color = ink.copy(alpha = if (quiet) 0.5f else 1f), maxLines = 8,
                    overflow = TextOverflow.Ellipsis, lineHeight = 18.sp)
            }
        }
        if (meta != null) Box(Modifier.align(Alignment.End)) { meta() }
    }
}

/** The quote's picture in the bar above the composer (36 square, like a reply's). */
@Composable
internal fun QuoteThumb(card: QuoteCard, qid: String? = null, side: Dp = 36.dp) {
    // The JPEG this phone made for the quote first, then the server's picture, then the channel's photo.
    val url: Any = qid?.let { QuoteStore.picture(it) } ?: QuoteRules.image(card) ?: card.photoUrl.ifEmpty { null } ?: return
    coil3.compose.AsyncImage(url, null, contentScale = ContentScale.Crop,
        modifier = Modifier.size(side).clip(RoundedCornerShape(6.dp)).background(Fariin.colors.secondary.copy(alpha = 0.15f)))
}
