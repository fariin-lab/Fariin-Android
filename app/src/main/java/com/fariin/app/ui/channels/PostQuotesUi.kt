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
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.LaunchedEffect
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
}

/** Up to 3 quoting channels' round photos, overlapping; "" = a plain circle. */
@Composable
private fun QuoteFaces(urls: List<String>) {
    if (urls.isEmpty()) return
    val c = Fariin.colors
    Row(horizontalArrangement = Arrangement.spacedBy((-7).dp)) {
        urls.forEach { u ->
            Box(Modifier.size(22.dp).clip(CircleShape).background(c.secondary.copy(alpha = 0.25f))) {
                if (u.isNotEmpty()) coil3.compose.AsyncImage(u, null, contentScale = ContentScale.Crop, modifier = Modifier.size(22.dp))
            }
        }
    }
}

/**
 * "N Quotes" along a channel post's bottom, inside its bubble, under the reactions and time (iOS
 * QuoteBarView): a hairline across the bubble, the quoting channels' faces, the count in blue and a
 * chevron at the right end. The bar is as wide as the bubble's other content once that is known
 * (it takes the parent's width on the next pass), so a short post's bubble does not grow to full width.
 * [inBubble] false: a media post, whose bubble is the picture, gets the same row under it.
 */
@Composable
fun PostQuotesBar(m: Message, inBubble: Boolean = true) {
    if (m.quoteCount <= 0 || m.deleted || m.isSystem) return
    val c = Fariin.colors
    var parentW by remember { mutableIntStateOf(0) }
    var selfX by remember { mutableIntStateOf(0) }
    val line = c.label.copy(alpha = 0.12f)
    val label = PostQuoteRules.barLabel(m.quoteCount)
    Layout(
        content = {
            // Always one node, even with no faces (QuoteFaces then draws nothing): the measure below
            // reads three children by position, and two crashed the app (a post quoted only privately).
            Box { QuoteFaces(m.quoteFaces) }
            // The label colour, not blue (owner 2026-11-06: "don't use blue").
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = c.tertiary, modifier = Modifier.size(18.dp))
        },
        modifier = Modifier
            .padding(top = if (inBubble) 6.dp else 4.dp)
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
            .clickable { PostQuotesSheet.open.value = m }
            .semantics { contentDescription = label },
    ) { ms, cons ->
        val loose = cons.copy(minWidth = 0, minHeight = 0)
        val gap = 8.dp.roundToPx()
        val side = if (inBubble) 0 else 10.dp.roundToPx()
        val chev = ms[2].measure(loose)
        val faces = ms[0].measure(loose)
        val facesW = if (faces.width > 0) faces.width + gap else 0
        val text = ms[1].measure(loose.copy(maxWidth = maxOf(0, cons.maxWidth - 2 * side - facesW - gap - chev.width)))
        val own = 2 * side + facesW + text.width + gap + chev.width
        val w = if (inBubble) maxOf(own, parentW - selfX).coerceAtMost(cons.maxWidth) else own.coerceAtMost(cons.maxWidth)
        val h = maxOf(36.dp.roundToPx(), text.height)
        layout(w, h) {
            faces.place(side, (h - faces.height) / 2)
            text.place(side + facesW, (h - text.height) / 2)
            chev.place(w - side - chev.width + if (inBubble) 4.dp.roundToPx() else 0, (h - chev.height) / 2)
        }
    }
}

/**
 * The Quotes sheet (iOS QuotesPage, after the owner's reference), drawn once per chat. Plain inside:
 * a small count line on top, then each quote in a public channel (most seen first): the quoting
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
    val close = { PostQuotesSheet.open.value = null }
    var answer by remember(m.id) { mutableStateOf<PostQuotesAnswer?>(null) }
    var failed by remember(m.id) { mutableStateOf(false) }
    var attempt by remember(m.id) { mutableIntStateOf(0) }
    var thumb by remember(m.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(m.id, attempt) {
        failed = false
        val a = PostQuotes.load(openCid, m.id)
        if (a == null) failed = true else answer = a
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
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        containerColor = sheetFill, shape = SheetShape) {
        LazyColumn(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            item {
                Text("Quotes", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp), textAlign = TextAlign.Center)
            }
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
                        TextButton(onClick = { attempt++ }) { Text("Retry", color = c.blue) }
                    }
                }
                // Loading: three skeleton quotes in the page's own layout (owner 2026-11-06 "use skeleton
                // loading", iOS), soft grey, the whole block pulsing, top-aligned under the summary.
                else -> item { QuotesSkeleton() }
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
                Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(q.at)), fontSize = 12.sp,
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
