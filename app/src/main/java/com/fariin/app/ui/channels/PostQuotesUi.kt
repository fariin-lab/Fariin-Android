package com.fariin.app.ui.channels

import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.graphicsLayer
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.channel.ChannelFollows
import com.fariin.app.data.ChatsNow
import com.fariin.app.data.Message
import com.fariin.app.data.PostQuoteRules
import com.fariin.app.data.PostQuotes
import com.fariin.app.data.PostQuotesAnswer
import com.fariin.app.data.PostReplyRules
import com.fariin.app.data.QuoteStore
import com.fariin.app.data.QuotedIn
import com.fariin.app.media.QuotePicture
import com.fariin.app.ui.components.Avatar
import com.fariin.app.ui.components.AvatarKind
import com.fariin.app.ui.components.SheetShape
import com.fariin.app.ui.components.sheetFill
import com.fariin.app.ui.theme.Fariin
import com.fariin.app.ui.thread.LocalChatMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

/** The channel post whose Quotes sheet is open (one at a time; [PostQuotesHost] draws it). */
object PostQuotesSheet {
    val open = MutableStateFlow<Message?>(null)
    /** The tab it opens on: 0 Replies, 1 Quotes (a bar showing only quotes, PostReplyRules.startTab). */
    @Volatile var startTab = 0
    /** The post's replies were off when its bar was tapped: the sheet opens as Quotes only. */
    @Volatile var startOff = false
    /** A reply alert tapped (cid to post id): the chat opens that post's Replies once it is on screen. */
    val pendingReplies = MutableStateFlow<Pair<String, String>?>(null)
}

/** The bar's start, 24 dp (iOS, owner 2026-11-07 "a little bigger"). */
private val BarFace = 24.dp

/**
 * Up to 3 round photos, overlapping: the quoting channels' ("" = a plain circle) or the repliers'
 * ([people]: "" = the person placeholder).
 */
@Composable
private fun QuoteFaces(urls: List<String>, people: Boolean = false) {
    if (urls.isEmpty()) return
    val c = Fariin.colors
    Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
        urls.forEach { u ->
            if (people) Avatar(u.ifEmpty { null }, BarFace)
            else Box(Modifier.size(BarFace).clip(CircleShape).background(c.secondary.copy(alpha = 0.25f))) {
                if (u.isNotEmpty()) coil3.compose.AsyncImage(u, null, contentScale = ContentScale.Crop, modifier = Modifier.size(BarFace))
            }
        }
    }
}

/**
 * "3 Replies · 4 Quotes" along a sent channel post's bottom, inside its bubble, under the reactions
 * and time (iOS QuoteBarView, owner 2026-11-06): a hairline across the bubble, the quoting channels'
 * faces (once quoted), the words and a chevron at the right end. "Reply" on a post with neither yet;
 * with replies off (this post's or the whole channel's, [channelRepliesOff]) only the quotes, and no
 * bar at all when it was never quoted (PostReplyRules.barLabel). The bar is as wide as the bubble's other content once that is known
 * (it takes the parent's width on the next pass), so a short post's bubble does not grow to full width.
 * [inBubble] false: a media post, whose bubble is the picture, gets the same row under it.
 */
@Composable
fun PostQuotesBar(m: Message, inBubble: Boolean = true, channelRepliesOff: Boolean = true, edge: Dp = 0.dp) {
    if (m.isSystem) return
    // The post's own switch wins over the channel's (off by default); one just changed here wins over both.
    val local = PostRepliesSwitch.of(m.id)
    if (local != null) androidx.compose.runtime.SideEffect { PostRepliesSwitch.settled(m.id, m.repliesOff) }
    val repliesOn = !PostReplyRules.postOff(m.repliesOff, channelRepliesOff, local)
    val label = PostReplyRules.barLabel(m.replyCount, m.quoteCount, repliesOn = repliesOn,
        sent = m.sendState == null, deleted = m.deleted) ?: return
    val start = PostReplyRules.barStart(repliesOn, m.replyCount, m.replyFaces, m.quoteCount, m.quoteFaces)
    val c = Fariin.colors
    var parentW by remember { mutableIntStateOf(0) }
    var selfX by remember { mutableIntStateOf(0) }
    val line = c.label.copy(alpha = 0.12f)
    Layout(
        content = {
            // Always one node, even with no faces (QuoteFaces then draws nothing): the measure below
            // reads three children by position, and two crashed the app (a post quoted only privately).
            // Replies on: the owner's comment icon until someone replies, then the repliers' faces.
            Box {
                when (start) {
                    PostReplyRules.BarStart.Icon -> Icon(androidx.compose.ui.res.painterResource(com.fariin.app.R.drawable.ic_reply_comment),
                        null, tint = c.label, modifier = Modifier.size(BarFace))
                    is PostReplyRules.BarStart.Faces -> QuoteFaces(start.urls, start.people)
                }
            }
            // The label colour, not blue (owner 2026-11-06: "don't use blue"); 15 sp (2026-11-07).
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = c.tertiary, modifier = Modifier.size(18.dp))
        },
        modifier = Modifier
            .padding(top = if (inBubble && edge == 0.dp) 6.dp else if (inBubble) 0.dp else 4.dp)
            .then(if (inBubble) Modifier else Modifier.padding(horizontal = 12.dp).clip(RoundedCornerShape(10.dp))
                .background(c.label.copy(alpha = 0.06f)))
            .onPlaced { co ->
                parentW = co.parentLayoutCoordinates?.size?.width ?: 0
                selfX = co.positionInParent().x.roundToInt()
            }
            .drawBehind {
                // Across the whole bubble, into its side padding (the bubble's clip trims it).
                if (inBubble) drawLine(line, Offset(-20.dp.toPx(), 0f), Offset(size.width + 20.dp.toPx(), 0f), strokeWidth = 1f)
            }
            .clickable {
                PostQuotesSheet.startTab = PostReplyRules.startTab(repliesOn)
                PostQuotesSheet.startOff = !repliesOn
                PostQuotesSheet.open.value = m
            }
            .semantics { contentDescription = label },
    ) { ms, cons ->
        val loose = cons.copy(minWidth = 0, minHeight = 0)
        val gap = 9.dp.roundToPx()
        // [edge]: a bubble with no side padding of its own (a photo or video post) insets the bar itself.
        val side = if (inBubble) edge.roundToPx() else 10.dp.roundToPx()
        val chev = ms[2].measure(loose)
        val faces = ms[0].measure(loose)
        val facesW = if (faces.width > 0) faces.width + gap else 0
        val text = ms[1].measure(loose.copy(maxWidth = maxOf(0, cons.maxWidth - 2 * side - facesW - gap - chev.width)))
        val own = 2 * side + facesW + text.width + gap + chev.width
        val w = if (inBubble) maxOf(own, parentW - selfX).coerceAtMost(cons.maxWidth) else own.coerceAtMost(cons.maxWidth)
        val h = maxOf(44.dp.roundToPx(), text.height)
        layout(w, h) {
            faces.place(side, (h - faces.height) / 2)
            text.place(side + facesW, (h - text.height) / 2)
            chev.place(w - side - chev.width + if (inBubble) 4.dp.roundToPx() else 0, (h - chev.height) / 2)
        }
    }
}

/**
 * The Replies | Quotes sheet a post's bar opens (iOS PostThreadSheet, owner 2026-11-06), drawn once
 * per chat. It opens on Replies (PostRepliesUi.kt): the thread, refreshed when the post's change
 * marker moves while the sheet is open, and the field at the bottom; the channel's admins get a menu
 * by their rights. A bar showing only quotes opens on Quotes.
 * The Quotes tab (iOS QuotesPage, after the owner's reference). Plain inside:
 * a small count line on top, then each quote in a public channel (newest first, 20 a page): the quoting
 * channel's photo, name and the time, its words, and under them a soft grey card of MY post (my
 * channel, the post's picture and text), then the quote's views. Quotes in private
 * places are only a number at the bottom, never named. A row opens that quote: in this chat it
 * scrolls there; in a channel I have it opens there; in a public channel I don't follow it opens that
 * channel from outside, the way its fariin.com link does.
 * [title], [photoUrl] and [verified] are the open channel (mine), as its card shows it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostQuotesHost(openCid: String, title: String, photoUrl: String?, verified: Boolean) {
    val post by PostQuotesSheet.open.collectAsState()
    val m = post ?: return
    val channelNav = LocalChannelNav.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val media = LocalChatMedia.current
    val close = { PostQuotesSheet.open.value = null; PostQuotesSheet.startOff = false }
    var answer by remember(m.id) { mutableStateOf<PostQuotesAnswer?>(null) }
    var failed by remember(m.id) { mutableStateOf(false) }
    var attempt by remember(m.id) { mutableIntStateOf(0) }
    var loadingMore by remember(m.id) { mutableStateOf(false) }
    var thumb by remember(m.id) { mutableStateOf<ImageBitmap?>(null) }
    val startOff = remember(m.id) { PostQuotesSheet.startOff }
    var tab by remember(m.id) { mutableIntStateOf(if (startOff) 1 else PostQuotesSheet.startTab) }
    // The Quotes page loads once it is first shown, and stays loaded when switching back.
    var quotesShown by remember(m.id) { mutableStateOf(tab == 1) }
    val scope = rememberCoroutineScope()
    val replies = remember(openCid, m.id) { PostRepliesState(openCid, m.id, scope) }
    // The post's change marker is listened to only while the sheet is open (never the replies).
    DisposableEffect(replies) {
        replies.start()
        onDispose { replies.stop() }
    }
    // Replies off (this post's or the channel's, as the bar said until the server answers): no
    // Replies tab, Quotes alone (iOS, owner 2026-11-07). The admin's ... still turns them back on.
    val repliesShown = if (replies.answered) !replies.off else !startOff
    LaunchedEffect(repliesShown) {
        if (!repliesShown && tab == 0) { tab = 1; quotesShown = true; keyboard?.hide(); focus.clearFocus() }
    }
    LaunchedEffect(m.id, attempt, quotesShown) {
        if (!quotesShown) return@LaunchedEffect
        failed = false
        val a = PostQuotes.load(openCid, m.id)
        if (a == null) failed = true else answer = a
    }
    // The next page of 20, when the end of the list comes into view (or Show more is tapped).
    fun loadMore() {
        val a = answer ?: return
        if (a.next.isEmpty() || loadingMore) return
        loadingMore = true
        scope.launch {
            PostQuotes.load(openCid, m.id, a.next)?.let { more -> answer = PostQuoteRules.appended(answer ?: a, more) }
            loadingMore = false
        }
    }
    // The post's picture: the same small JPEG this phone makes when quoting it (from this chat's cache,
    // else the post's inline thumbnail). Nothing is downloaded for it.
    LaunchedEffect(m.id) {
        thumb = withContext(Dispatchers.IO) {
            QuotePicture.make(media, m)?.let { b -> BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap() }
        }
    }
    val c = Fariin.colors
    val album = m.type == "album" && m.album.isNotEmpty()
    val card = SourceCard(
        title = title.ifEmpty { "Channel" }, photoUrl = photoUrl, verified = verified,
        // A text post's words as its bubble shows them; a picture's or video's caption.
        text = PostQuoteRules.cardText(if (m.mediaKind == null) m.bodyText else m.text, m.type, album),
        kindIcon = when {
            album -> Icons.Filled.PhotoLibrary
            m.type == "image" -> Icons.Filled.Photo
            m.type == "video" -> Icons.Filled.Videocam
            else -> null
        },
        thumb = thumb,
    )
    // ⛔ 70% AND ONLY 70% (iOS, owner 2026-11-07, twice: a scroll made it full, then tapping the reply
    // field made it full). One height, no taller stop: the keyboard does not grow it, the field rides
    // above the keyboard inside it.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val sheetH = with(density) { (androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.height * 0.70f).toDp() }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = close, sheetState = sheetState,
        containerColor = sheetFill, shape = SheetShape,
        // The column below takes the navigation bar and the keyboard itself, so the field rides on the keyboard.
        contentWindowInsets = { androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0) }) {
      Column(Modifier.fillMaxWidth().height(sheetH).navigationBarsPadding().imePadding()) {
        RepliesSheetTop(tab, repliesShown, replies, onPick = {
            tab = it
            if (it == 1) {
                quotesShown = true
                // The field is on the Replies tab: the keyboard goes with it.
                keyboard?.hide()
                focus.clearFocus()
            }
        }, onClose = { scope.launch { sheetState.hide() }.invokeOnCompletion { close() } })
        if (tab == 0) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                RepliesList(replies, Modifier.fillMaxSize())
                RepliesToast(replies, Modifier.align(Alignment.TopCenter))
            }
            RepliesComposer(replies)
        } else {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
            val a = answer
            when {
                a != null && PostQuoteRules.isEmpty(a) -> item {
                    Column(Modifier.fillMaxWidth().padding(top = 36.dp, bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(androidx.compose.ui.res.painterResource(com.fariin.app.R.drawable.ic_quote), null, tint = c.secondary, modifier = Modifier.size(36.dp))
                        Text("No quotes yet", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.label,
                            modifier = Modifier.padding(top = 8.dp))
                    }
                }
                a != null -> {
                    item {
                        Text(PostQuoteRules.summaryLine(a), fontSize = 13.sp, color = c.secondary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                    }
                    items(a.items, key = { it.cid + "/" + it.mid }) { q ->
                        QuoteRow(q, card) {
                            close()
                            QuoteStore.pendingJump.value = QuoteStore.Jump(q.cid, q.mid)
                            val known = ChatsNow.find(q.cid) != null || ChannelFollows.follows(q.cid)
                            when (PostQuoteRules.route(q, openCid, known)) {
                                // This chat: ThreadScreen scrolls to the quote and lights it.
                                PostQuoteRules.Route.SAME_CHAT -> Unit
                                // Another channel, as a follower or, for a public one, from outside
                                // (a fariin.com/<name> link of a channel lands on the same page).
                                PostQuoteRules.Route.MY_CHANNEL, PostQuoteRules.Route.PUBLIC_PAGE -> {
                                    keyboard?.hide()
                                    focus.clearFocus()
                                    channelNav.open(q.cid)
                                }
                            }
                        }
                        HorizontalDivider(Modifier.padding(start = 16.dp + QuoteIndent), thickness = 0.5.dp, color = c.separator)
                    }
                    if (a.next.isNotEmpty()) item(key = "more") {
                        LaunchedEffect(a.next) { loadMore() }
                        Text(if (loadingMore) "Loading…" else "Show more", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            color = c.secondary, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().clickable { loadMore() }.padding(vertical = 12.dp))
                    }
                    PostQuoteRules.hiddenLine(a.hidden)?.let { line ->
                        item {
                            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Lock, null, tint = c.secondary, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(line, fontSize = 13.sp, color = c.secondary)
                            }
                        }
                    }
                }
                failed -> item {
                    Column(Modifier.fillMaxWidth().padding(top = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Couldn't load quotes", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.label)
                        Text("Check your connection and try again.", fontSize = 14.sp, color = c.secondary, modifier = Modifier.padding(top = 4.dp))
                        TextButton(onClick = { attempt++ }) { Text("Try Again", color = c.label) }
                    }
                }
                // Loading: three skeleton quotes in the page's own layout (owner 2026-11-06 "use skeleton
                // loading", iOS), soft grey, the whole block pulsing, top-aligned under the summary.
                else -> item { QuotesSkeleton() }
            }
        }
        }
      }
    }
}

/** What every quote's card shows of my post (the same for each row). [kindIcon] null = a text post, no picture box. */
private data class SourceCard(
    val title: String,
    val photoUrl: String?,
    val verified: Boolean,
    val text: String,
    val kindIcon: ImageVector?,
    val thumb: ImageBitmap?,
)

/** My post's card fill: soft grey with no border (iOS systemGray6). */
@Composable
private fun cardFill(): Color = if (Fariin.colors.dark) Color(0xFF1C1C1E) else Color(0xFFF2F2F7)

/** Where everything under a quote's first line starts: past the 32 dp photo and its 9 dp gap, under the name. */
private val QuoteIndent = 41.dp

/** Three quotes' shapes while the page loads, in the same layout as [QuoteRow]. */
@Composable
private fun QuotesSkeleton() {
    val c = Fariin.colors
    val grey = c.secondary.copy(alpha = 0.16f)
    val fill = cardFill()
    val pulse by androidx.compose.animation.core.rememberInfiniteTransition(label = "quotesSkeleton").animateFloat(
        1f, 0.45f, androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(900), androidx.compose.animation.core.RepeatMode.Reverse), label = "pulse")
    @Composable fun bar(w: Dp, h: Dp = 10.dp) =
        Box(Modifier.width(w).height(h).clip(RoundedCornerShape(h / 2)).background(grey))
    Column(Modifier.fillMaxWidth().graphicsLayer { alpha = pulse }.padding(horizontal = 16.dp)) {
        repeat(3) {
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(grey))
                    Spacer(Modifier.width(9.dp)); bar(120.dp); Spacer(Modifier.weight(1f)); bar(36.dp, 8.dp)
                }
                Column(Modifier.padding(start = QuoteIndent)) {
                    Box(Modifier.padding(top = 4.dp)) { bar(200.dp) }
                    Column(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(fill).padding(9.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(16.dp).clip(RoundedCornerShape(5.dp)).background(grey))
                            Spacer(Modifier.width(6.dp)); bar(90.dp, 8.dp)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(grey))
                            Spacer(Modifier.width(9.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { bar(150.dp, 8.dp); bar(100.dp, 8.dp) }
                        }
                    }
                    Box(Modifier.padding(top = 6.dp)) { bar(50.dp, 8.dp) }
                }
            }
        }
    }
}

/**
 * One quote (the owner's option 1, 2026-11-06): the quoting channel's photo, name, seal and the time
 * on one line; under the name, their words; then my post in a soft grey card (my channel, the post's
 * picture and words); its views last.
 */
@Composable
private fun QuoteRow(q: QuotedIn, card: SourceCard, onTap: () -> Unit) {
    val c = Fariin.colors
    Column(Modifier.fillMaxWidth().clickable(onClick = onTap).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(q.photoUrl.ifEmpty { null }, 32.dp, squircle = true, kind = AvatarKind.of(true, true))
            Spacer(Modifier.width(9.dp))
            Box(Modifier.weight(1f)) { NameAndSeal(q.title, q.verified, 15.sp, 13.dp) }
            if (q.at > 0) {
                Spacer(Modifier.width(6.dp))
                // Today the time; an older quote its day and month.
                Text(if (android.text.format.DateUtils.isToday(q.at)) DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(q.at))
                    else android.text.format.DateFormat.format("d MMM", q.at).toString(), fontSize = 12.sp,
                    color = c.secondary, maxLines = 1)
            }
        }
        Column(Modifier.padding(start = QuoteIndent)) {
            if (q.text.isNotEmpty()) {
                Text(q.text, fontSize = 15.sp, color = c.label, maxLines = 4, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp))
            }
            Column(
                Modifier.padding(top = 8.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp)).background(cardFill()).padding(9.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(card.photoUrl, 16.dp, squircle = true, kind = AvatarKind.of(true, true))
                    Spacer(Modifier.width(6.dp))
                    NameAndSeal(card.title, card.verified, 13.sp, 11.dp, c.secondary)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val shape = RoundedCornerShape(8.dp)
                    val pic = card.thumb
                    val icon = card.kindIcon
                    if (pic != null) {
                        Image(pic, null, contentScale = ContentScale.Crop, modifier = Modifier.size(40.dp).clip(shape))
                        Spacer(Modifier.width(9.dp))
                    } else if (icon != null) {
                        Box(Modifier.size(40.dp).clip(shape).background(c.secondary.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                            Icon(icon, null, tint = c.secondary, modifier = Modifier.size(16.dp))
                        }
                        Spacer(Modifier.width(9.dp))
                    }
                    Text(card.text, fontSize = 13.sp, color = c.label, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f))
                }
            }
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(ImageVector.vectorResource(com.fariin.app.R.drawable.ic_views), null, tint = c.secondary, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(4.dp))
                Text(PostQuoteRules.viewsLine(q.views), fontSize = 11.sp, color = c.secondary)
            }
        }
    }
}

/** A bold name that gives way (ellipsis) before the blue seal does. */
@Composable
private fun NameAndSeal(name: String, verified: Boolean, size: TextUnit, seal: Dp, color: Color = Fariin.colors.label) {
    val c = Fariin.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(name, fontSize = size, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (verified) {
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Filled.Verified, contentDescription = "Verified", tint = c.blue, modifier = Modifier.size(seal))
        }
    }
}
