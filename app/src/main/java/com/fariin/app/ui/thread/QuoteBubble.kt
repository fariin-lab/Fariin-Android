package com.fariin.app.ui.thread

import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import com.fariin.app.ui.components.cachedVector
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
                              /** The words hold the post's own link (iOS 251b4be31, owner's design B): the small link card. */ postLink: Boolean = false,
                              /** The sender hid the post's text (`quote.mediaOnly`): the kind's word in its place. */ hideText: Boolean = false) {
    val cards by QuoteStore.cards.collectAsState()
    // Every draw asks: a miss, or an answer older than two minutes, is fetched with the others on screen.
    SideEffect {
        if (openCid != null && hostMid != null) QuoteStore.noteHost(qid, openCid, hostMid)
        QuoteStore.card(qid)
    }
    val card = cards[qid]?.let { if (hideText && it.state == QuoteCard.State.OK) it.copy(text = "") else it }
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
            // A poll post (iOS quoted poll spec 2026-11-14): never the raw marker, a live mini poll.
            val poll = card?.takeIf { it.state == QuoteCard.State.OK }?.let { com.fariin.app.data.PollRules.parse(it.text.trim()) }
            if (poll != null && postLink) { QuotedPollLink(card, poll, ink); return@Column }
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
                Icon(androidx.compose.ui.res.painterResource(if (poll != null) com.fariin.app.R.drawable.ic_sf_chart_bar_fill else com.fariin.app.R.drawable.ic_quote),
                    null, tint = ink.copy(alpha = 0.55f), modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(2.dp))
                Text(if (poll != null) "Poll" else "Quote", fontSize = 12.sp, color = ink.copy(alpha = 0.55f), maxLines = 1)
            }
            if (poll != null) { QuotedPollBody(card, poll, ink); return@Column }
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
                QuoteViaLine(card, ink)
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
                QuoteViaLine(card, ink)
            } else if (body.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(body, fontSize = 14.sp, color = ink.copy(alpha = if (quiet) 0.5f else 1f), maxLines = 8,
                    overflow = TextOverflow.Ellipsis, lineHeight = 18.sp)
            }
        }
        if (meta != null) Box(Modifier.align(Alignment.End)) { meta() }
    }
}

/**
 * The picture came from another channel (iOS 4743aacce, owner 2026-11-26, `QuoteCard.viaTitle`):
 * "From <name>" on its own quiet line under the card's words, so the source channel is still named.
 */
@Composable
private fun QuoteViaLine(card: QuoteCard?, ink: Color) {
    val via = card?.takeIf { it.state == QuoteCard.State.OK }?.viaName ?: return
    val quiet = ink.copy(alpha = 0.6f)
    Text(androidx.compose.ui.text.buildAnnotatedString {
        append("From ")
        pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.SemiBold))
        append(via)
        pop()
    }, fontSize = 13.sp, color = quiet, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
}

/** A quoted poll's live state: the original post's tally and my own vote doc (shared PollVotes listener). */
private class QuotedPollState(val votes: Map<String, List<Int>>, val mine: List<Int>, val open: Boolean)

@Composable
private fun rememberQuotedPoll(card: QuoteCard, poll: com.fariin.app.data.PollCard): QuotedPollState {
    val me = remember { com.fariin.app.Fb.auth.currentUser?.uid.orEmpty() }
    var tally by remember(card.cid, card.mid) { mutableStateOf(com.fariin.app.data.PollVotes.Tally()) }
    DisposableEffect(card.cid, card.mid) {
        val reg = com.fariin.app.data.PollVotes.listenPost(card.cid, card.mid) { tally = it }
        onDispose { reg?.remove() }
    }
    val flow = remember(card.cid, card.mid) { com.fariin.app.data.PollVotes.subscribe(card.cid, card.mid, me, true) }
    DisposableEffect(card.cid, card.mid) { onDispose { com.fariin.app.data.PollVotes.release(card.cid, card.mid, true) } }
    val v by flow.collectAsState()
    val mine = v.byUid[me] ?: emptyList()
    var now by remember { mutableStateOf(System.currentTimeMillis().toDouble()) }
    LaunchedEffect(poll.endsAt) {
        val wait = (poll.endsAt ?: return@LaunchedEffect) - System.currentTimeMillis()
        if (wait > 0) { kotlinx.coroutines.delay(wait.toLong() + 50); now = System.currentTimeMillis().toDouble() }
    }
    val votes = com.fariin.app.data.PollRules.channelVotes(tally.counts, tally.voters, tally.tallyAt, mine, v.mineAt, me, v.prevMine, v.prevAt)
    return QuotedPollState(votes, mine, com.fariin.app.data.PollRules.isOpen(poll, tally.closed, now))
}

/**
 * The quote card's "mini poll (live)" (iOS QuotedPollView): the question, up to three bars, "+N more"
 * and the footer. Fills and percents only once I have voted or the poll is over. No voting here: a
 * tap opens the post, like any quote.
 */
@Composable
private fun QuotedPollBody(card: QuoteCard, poll: com.fariin.app.data.PollCard, ink: Color) {
    val st = rememberQuotedPoll(card, poll)
    val results = com.fariin.app.data.PollRules.showResults(st.mine, st.open)
    Spacer(Modifier.height(8.dp))
    Text(poll.question, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = ink, maxLines = 3,
        overflow = TextOverflow.Ellipsis, lineHeight = 19.sp)
    Spacer(Modifier.height(8.dp))
    poll.options.take(3).forEachIndexed { i, label ->
        if (i > 0) Spacer(Modifier.height(6.dp))
        val frac = if (results) com.fariin.app.data.PollRules.fraction(st.votes, i).toFloat().coerceIn(0f, 1f) else 0f
        Box(Modifier.fillMaxWidth().height(28.dp).clip(RoundedCornerShape(14.dp)).background(ink.copy(alpha = 0.07f))) {
            if (results && frac > 0f) Box(Modifier.fillMaxHeight().fillMaxWidth(frac)
                .background(ink.copy(alpha = if (i in st.mine) 0.28f else 0.12f)))
            Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = ink, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (results) {
                    Spacer(Modifier.width(6.dp))
                    Text(com.fariin.app.data.PollRules.percent(st.votes, i), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ink)
                }
            }
        }
    }
    if (poll.options.size > 3) Text("+${poll.options.size - 3} more", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        color = ink.copy(alpha = 0.55f), modifier = Modifier.padding(top = 6.dp))
    Text(com.fariin.app.data.PollRules.quoteFooter(st.votes.size, st.open, st.mine.isNotEmpty()), fontSize = 12.sp,
        color = ink.copy(alpha = 0.6f), modifier = Modifier.padding(top = 8.dp))
}

/** A poll post sent as its link (iOS quotedPollLink): our own drawn bar chart, then name, question and counts. */
@Composable
private fun QuotedPollLink(card: QuoteCard, poll: com.fariin.app.data.PollCard, ink: Color) {
    val st = rememberQuotedPoll(card, poll)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(ink.copy(alpha = 0.12f))) {
            val bar = ink.copy(alpha = 0.85f)
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val w = 5.dp.toPx()
                val gap = 4.dp.toPx()
                val bottom = size.height / 2 + 10.dp.toPx()
                val left = size.width / 2 - (3 * w + 2 * gap) / 2
                listOf(11, 20, 15).forEachIndexed { i, h ->
                    val hp = h.dp.toPx()
                    drawRoundRect(bar, androidx.compose.ui.geometry.Offset(left + i * (w + gap), bottom - hp),
                        androidx.compose.ui.geometry.Size(w, hp), androidx.compose.ui.geometry.CornerRadius(w / 2, w / 2))
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("${QuoteRules.heading(card)} · Poll", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(poll.question, fontSize = 13.sp, color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis,
                lineHeight = 17.sp, modifier = Modifier.padding(top = 2.dp))
            Text(com.fariin.app.data.PollRules.linkLine(poll.options.size, st.votes.size), fontSize = 12.sp,
                color = ink.copy(alpha = 0.6f), maxLines = 1, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** The quote's picture in the bar above the composer (36 square, like a reply's). */
@Composable
internal fun QuoteThumb(card: QuoteCard, qid: String? = null, side: Dp = 36.dp) {
    // The JPEG this phone made for the quote first, then the server's picture, then the channel's photo.
    val raw: Any = qid?.let { QuoteStore.picture(it) } ?: QuoteRules.image(card) ?: card.photoUrl.ifEmpty { null } ?: return
    val url = rememberQuotePicture(card, raw) ?: return
    coil3.compose.AsyncImage(url, null, contentScale = ContentScale.Crop,
        modifier = Modifier.size(side).clip(RoundedCornerShape(6.dp)).background(Fariin.colors.secondary.copy(alpha = 0.15f)))
}

/**
 * A QUOTE SENT WITH ITS TEXT HIDDEN (iOS 2026-11-21, `quote.mediaOnly`): the quoted post's picture,
 * video poster or album's first picture as this bubble's own media, the channel named on a small chip
 * at its lower left. A tap opens the post. Draws nothing (and returns false) when the post is not
 * there for this reader or has no picture: the caller then shows the card, without the post's words.
 */
@Composable
internal fun QuoteMediaOnly(qid: String, openCid: String?, hostMid: String?): Boolean {
    val cards by QuoteStore.cards.collectAsState()
    SideEffect {
        if (openCid != null && hostMid != null) QuoteStore.noteHost(qid, openCid, hostMid)
        QuoteStore.card(qid)
    }
    val card = cards[qid]?.takeIf { it.state == QuoteCard.State.OK } ?: return false
    val url = QuoteRules.image(card) ?: return false
    val channelNav = com.fariin.app.ui.channels.LocalChannelNav.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    // The post itself: the chip, and the viewer's "From <Channel>" capsule.
    val openPost = {
        val notice = QuoteRules.tapNotice(card)
        if (notice != null) com.fariin.app.ui.components.Snack.show(notice)
        else if (card.cid.isNotEmpty()) {
            if (card.mid.isNotEmpty()) QuoteStore.pendingJump.value = QuoteStore.Jump(card.cid, card.mid)
            if (!QuoteRules.jumpsInPlace(card, openCid)) { keyboard?.hide(); focus.clearFocus(); channelNav.open(card.cid) }
        }
    }
    // A tap on the picture opens it full screen (iOS 671707b42); the chip opens the post.
    var viewing by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    if (viewing) QuoteMediaViewer(qid, card, url, onClose = { viewing = false }, onOpenPost = { viewing = false; openPost() })
    Box(Modifier.fillMaxWidth().height(220.dp).padding(bottom = 6.dp).clip(RoundedCornerShape(12.dp))
        .background(Fariin.colors.secondary.copy(alpha = 0.18f))
        .clickable { viewing = true }) {
        coil3.compose.AsyncImage(rememberQuotePicture(card, QuoteStore.picture(qid) ?: url), contentDescription = QuoteRules.body(card.copy(text = "")),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
        if (card.kind == "video") Box(Modifier.align(Alignment.Center).size(44.dp).clip(androidx.compose.foundation.shape.CircleShape)
            .background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
            androidx.compose.material3.Icon(androidx.compose.ui.graphics.vector.ImageVector.cachedVector(com.fariin.app.R.drawable.ic_sf_play_fill),
                null, tint = Color.White, modifier = Modifier.size(24.dp))
        }
        // The channel's chip: its picture and name, on the picture's lower left; a tap opens the post.
        Row(Modifier.align(Alignment.BottomStart).padding(8.dp).clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.5f)).clickable { openPost() }.padding(start = 3.dp, end = 9.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically) {
            com.fariin.app.ui.components.Avatar(chipPhoto(card), 20.dp, squircle = true,
                kind = com.fariin.app.ui.components.AvatarKind.CHANNEL)
            Spacer(Modifier.width(6.dp))
            Text(card.viaName ?: card.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
        }
    }
    return true
}

/** The chip's photo: the source channel's when the media came from another channel, else the card's own. */
private fun chipPhoto(card: QuoteCard): String? =
    (if (card.viaName != null) card.viaPhoto else card.photoUrl).ifEmpty { null }

/**
 * A quote's picture as the image loader can draw it. A channel's post picture on R2 (`r2:chat/<cid>/…`,
 * groups and channels since config r2Media) is fetched in the clear through the channel's own media
 * (iOS 671707b42: "the channel's R2 file"), and only from that channel's own folder; an https link or
 * this phone's own bytes go as they are. Null while an R2 file is coming.
 */
@Composable
internal fun rememberQuotePicture(card: QuoteCard, raw: Any?): Any? {
    val r2 = (raw as? String)?.takeIf { com.fariin.app.media.R2Media.isR2(it) }
    if (r2 == null) return raw
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // The card's own channel, or the channel its media came from (2026-11-26, `viaCid`).
    val key = com.fariin.app.media.R2Media.keyOf(r2)
    val folder = listOf(card.cid, card.viaCid).firstOrNull { it.isNotEmpty() && key.startsWith("chat/$it/") }
    val file by androidx.compose.runtime.produceState<java.io.File?>(null, r2, folder) {
        if (folder == null) return@produceState
        value = runCatching { com.fariin.app.media.ChatMedia(ctx.applicationContext, folder, null).file(r2, null) }.getOrNull()
    }
    return file
}

/**
 * A hidden-text quote's picture full screen (iOS 671707b42): fitted on black, a round X, and a glass
 * "From <Channel>" capsule at the foot that closes this and opens the post. Nothing to delete or
 * forward here: it is the channel's picture, not this chat's file. A quoted video shows its poster
 * with "Open original" (Android carries no clip in the quote copy).
 */
@Composable
private fun QuoteMediaViewer(qid: String, card: QuoteCard, url: String, onClose: () -> Unit, onOpenPost: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            coil3.compose.AsyncImage(rememberQuotePicture(card, QuoteStore.picture(qid) ?: url), contentDescription = QuoteRules.body(card.copy(text = "")),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit, modifier = Modifier.fillMaxSize())
            Box(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(12.dp).size(44.dp).clip(androidx.compose.foundation.shape.CircleShape)
                .background(Color.White.copy(alpha = 0.18f)).clickable(onClickLabel = "Close", onClick = onClose), contentAlignment = Alignment.Center) {
                androidx.compose.material3.Icon(androidx.compose.ui.graphics.vector.ImageVector.cachedVector(com.fariin.app.R.drawable.ic_sf_xmark),
                    "Close", tint = Color.White, modifier = Modifier.size(18.dp))
            }
            androidx.compose.foundation.layout.Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                if (card.kind == "video") {
                    Text("Open original", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                        modifier = Modifier.padding(bottom = 12.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.18f))
                            .clickable(onClick = onOpenPost).padding(horizontal = 16.dp, vertical = 9.dp))
                }
                Row(Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.18f)).clickable(onClick = onOpenPost)
                    .padding(start = 5.dp, end = 14.dp, top = 5.dp, bottom = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    com.fariin.app.ui.components.Avatar(chipPhoto(card), 26.dp, squircle = true,
                        kind = com.fariin.app.ui.components.AvatarKind.CHANNEL)
                    Spacer(Modifier.width(8.dp))
                    Text("From ${card.viaName ?: card.title}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 240.dp))
                }
            }
        }
    }
}
