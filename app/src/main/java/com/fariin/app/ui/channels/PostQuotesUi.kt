package com.fariin.app.ui.channels

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import com.fariin.app.ui.components.Avatar
import com.fariin.app.ui.components.AvatarKind
import com.fariin.app.ui.components.SheetShape
import com.fariin.app.ui.components.sheetFill
import com.fariin.app.ui.theme.Fariin
import kotlinx.coroutines.flow.MutableStateFlow
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
            QuoteFaces(m.quoteFaces)
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.blue, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
 * The Quotes sheet (iOS QuotesPage), drawn once per chat: the post on top, its numbers, each quote in
 * a public channel with its views (most seen first), then how many more are in private places, never
 * which. A row opens that quote: in this chat it scrolls there; in a channel I have it opens there; in
 * a public channel I don't follow it opens that channel from outside, the way its fariin.com link does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostQuotesHost(openCid: String) {
    val post by PostQuotesSheet.open.collectAsState()
    val m = post ?: return
    val channelNav = LocalChannelNav.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val close = { PostQuotesSheet.open.value = null }
    var answer by remember(m.id) { mutableStateOf<PostQuotesAnswer?>(null) }
    var failed by remember(m.id) { mutableStateOf(false) }
    var attempt by remember(m.id) { mutableIntStateOf(0) }
    LaunchedEffect(m.id, attempt) {
        failed = false
        val a = PostQuotes.load(openCid, m.id)
        if (a == null) failed = true else answer = a
    }
    val c = Fariin.colors
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        containerColor = sheetFill, shape = SheetShape) {
        LazyColumn(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            item {
                Text("Quotes", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            item { PostHeader(m) }
            val a = answer
            when {
                a != null -> {
                    item { Numbers(a) }
                    if (a.items.isNotEmpty()) {
                        item {
                            Text("PUBLIC CHANNELS", fontSize = 13.sp, color = c.secondary,
                                modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 6.dp))
                        }
                        items(a.items, key = { it.cid + "/" + it.mid }) { q ->
                            QuoteRow(q) {
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
                        }
                    }
                    PostQuoteRules.hiddenLine(a.hidden)?.let { line ->
                        item {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Lock, null, tint = c.secondary, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(line, fontSize = 14.sp, color = c.secondary)
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
                else -> item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp), color = c.secondary, strokeWidth = 2.5.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun PostHeader(m: Message) {
    val c = Fariin.colors
    val album = m.type == "album" && m.album.isNotEmpty()
    val icon: ImageVector = when {
        album -> Icons.Filled.PhotoLibrary
        m.type == "image" -> Icons.Filled.Photo
        m.type == "video" -> Icons.Filled.Videocam
        else -> Icons.Filled.Notes
    }
    // A text post's words as its bubble shows them; a picture's or video's caption; else its kind.
    val shown = (if (m.mediaKind == null) m.bodyText else m.text).trim()
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(c.secondary.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = c.secondary, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(shown.ifEmpty { PostQuoteRules.kindName(m.type, album) }, fontSize = 15.sp, color = c.label,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("Posted " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(m.createdAtMillis.toLong())),
                fontSize = 12.sp, color = c.secondary)
        }
    }
}

@Composable
private fun Numbers(a: PostQuotesAnswer) {
    val c = Fariin.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 16.dp)) {
        PostQuoteRules.stats(a).forEach { s ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(s.value, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = c.label)
                Text(s.label, fontSize = 12.sp, color = c.secondary)
            }
        }
    }
}

@Composable
private fun QuoteRow(q: QuotedIn, onTap: () -> Unit) {
    val c = Fariin.colors
    Row(Modifier.fillMaxWidth().clickable(onClick = onTap).padding(horizontal = 20.dp, vertical = 8.dp).heightIn(min = 44.dp)) {
        Avatar(q.photoUrl.ifEmpty { null }, 36.dp, squircle = true, kind = AvatarKind.of(true, true))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(q.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.label, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (q.verified) {
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Filled.Verified, contentDescription = "Verified", tint = c.blue, modifier = Modifier.size(14.dp))
                    }
                }
                Spacer(Modifier.width(6.dp))
                if (q.at > 0) Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(q.at)), fontSize = 12.sp, color = c.secondary, maxLines = 1)
            }
            if (q.text.isEmpty()) {
                Text("Quote with no text", fontSize = 14.sp, fontStyle = FontStyle.Italic, color = c.secondary)
            } else {
                Text(q.text, fontSize = 14.sp, color = c.label, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(ImageVector.vectorResource(com.fariin.app.R.drawable.ic_views), null, tint = c.secondary, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(4.dp))
                Text(PostQuoteRules.viewsLine(q.views), fontSize = 12.sp, color = c.secondary)
            }
        }
    }
}
