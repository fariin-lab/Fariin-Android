package com.fariin.app.ui.thread

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FormatQuote
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

/**
 * A quoted channel post in a bubble (iOS quote card, owner 2026-11-06, his reference). It sits
 * UNDER my words, with the time on its own line below it ([meta]). The card is outlined, not
 * filled: the channel's 20 dp photo and its name in blue, the post's picture with its own
 * corners, then the post's words (4 lines). Until the first answer lands it shows quietly as
 * "Channel post / Loading…", never the raw id.
 * A tap opens the post in its channel; when that channel is the chat already open ([openCid]) it
 * scrolls to the post and lights it instead. A post this reader may not see gets a short notice.
 */
@Composable
internal fun QuoteCardBubble(qid: String, isMe: Boolean, openCid: String?, meta: (@Composable () -> Unit)? = null) {
    val cards by QuoteStore.cards.collectAsState()
    // Every draw asks: a miss, or an answer older than two minutes, is fetched with the others on screen.
    SideEffect { QuoteStore.card(qid) }
    val card = cards[qid]
    val channelNav = com.fariin.app.ui.channels.LocalChannelNav.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val c = Fariin.colors
    val ink = if (isMe) Color.White else c.label
    val tint = if (isMe) Color.White else c.blue
    val quiet = card == null
    // The picture this phone made when I quoted (shown at once), else the server's copy.
    val image: Any? = QuoteRules.image(card)?.let { url -> QuoteStore.picture(qid) ?: url }
    Column(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp).clip(RoundedCornerShape(12.dp))
                .border(1.dp, tint.copy(alpha = 0.22f), RoundedCornerShape(12.dp))
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
                .padding(10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Avatar(card?.photoUrl?.ifEmpty { null }, 20.dp, squircle = true, kind = AvatarKind.of(true, true))
                Spacer(Modifier.width(6.dp))
                // Mine sits on my own coloured bubble, where blue would not read: white there.
                Text(QuoteRules.heading(card), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    color = tint.copy(alpha = if (quiet) 0.6f else 1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                // A short "Quote" tag at the row's right end; the name shrinks to leave it room.
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Filled.FormatQuote, null, tint = ink.copy(alpha = 0.55f), modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(2.dp))
                Text("Quote", fontSize = 12.sp, color = ink.copy(alpha = 0.55f), maxLines = 1)
            }
            if (image != null) {
                Spacer(Modifier.height(8.dp))
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    Box(Modifier.fillMaxWidth().height(min(220.dp, maxWidth * 0.75f)).clip(RoundedCornerShape(10.dp))
                        .background(c.secondary.copy(alpha = 0.15f))) {
                        coil3.compose.AsyncImage(image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                }
            }
            val body = QuoteRules.body(card)
            if (body.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(body, fontSize = 14.sp, color = ink.copy(alpha = if (quiet) 0.5f else 1f), maxLines = 4,
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
