package com.fariin.app.ui.channels

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.data.PostReplies
import com.fariin.app.data.PostReply
import com.fariin.app.data.PostReplyRules
import com.fariin.app.ui.components.Avatar
import com.fariin.app.ui.components.AvatarKind
import com.fariin.app.ui.theme.Fariin
import kotlinx.coroutines.launch

/**
 * One post's replies while its sheet is open (iOS PostThreadSheet's state): the list, whether
 * replies are off and why, whether I run the channel, the field's words, the reply being answered
 * and which threads are open.
 */
@Stable
class PostRepliesState(private val cid: String, private val mid: String) {
    var replies by mutableStateOf<List<PostReply>>(emptyList())
        private set
    var loaded by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set
    var off by mutableStateOf(false)
        private set
    var channelOff by mutableStateOf(false)
        private set
    var postOff by mutableStateOf(false)
        private set
    var staff by mutableStateOf(false)
        private set
    var draft by mutableStateOf("")
    var sending by mutableStateOf(false)
        private set
    /** The server's reason a reply was not taken (off, too fast), shown over the field. */
    var problem by mutableStateOf<String?>(null)
    /** The reply the field answers; null = the post itself. */
    var answering by mutableStateOf<PostReply?>(null)
    /** Top-level replies whose answers show. */
    var open by mutableStateOf<Set<String>>(emptySet())
        private set
    val field = FocusRequester()

    suspend fun load() {
        val a = PostReplies.load(cid, mid)
        if (a == null) {
            failed = true
        } else {
            if (a.replies != replies) replies = a.replies
            off = a.off; channelOff = a.channelOff; postOff = a.postOff; staff = a.staff
            failed = false
        }
        loaded = true
    }

    fun toggleThread(id: String) { open = if (id in open) open - id else open + id }

    /** Reply to [r]: its thread opens, so the answer is seen landing under it. */
    fun startAnswer(r: PostReply) {
        answering = r
        open = open + PostReplyRules.threadOf(r)
        runCatching { field.requestFocus() }
    }

    suspend fun send() {
        val text = PostReplyRules.cleanDraft(draft) ?: return
        if (sending) return
        sending = true
        problem = null
        val parent = answering
        draft = ""
        answering = null
        PostReplies.add(cid, mid, text, parent?.id)
            .onSuccess {
                replies = PostReplyRules.appended(replies, it)
                if (it.parentId.isNotEmpty()) open = open + it.parentId
            }
            .onFailure { e ->
                if (draft.isEmpty()) draft = text   // the words come back to try again
                answering = parent
                problem = e.message
            }
        sending = false
    }

    /** The heart flips at once and flips back if the server did not take it. */
    suspend fun toggleLike(r: PostReply) {
        val before = replies.firstOrNull { it.id == r.id } ?: return
        replies = PostReplyRules.toggledLike(replies, r.id)
        if (!PostReplies.like(cid, mid, r.id, on = !before.liked)) replies = replies.map { if (it.id == r.id) before else it }
    }

    suspend fun delete(r: PostReply) {
        val before = replies
        replies = PostReplyRules.removed(replies, r.id)
        if (answering?.let { it.id == r.id || it.parentId == r.id } == true) answering = null
        if (!PostReplies.delete(cid, mid, r.id)) replies = before
    }

    /** [post] true = this post only, false = the whole channel. */
    suspend fun setOff(post: Boolean, off: Boolean) {
        if (PostReplies.setOff(cid, if (post) mid else null, off)) load()
    }
}

/**
 * The sheet's header (owner's reference): the channel's photo, the tab's name and the post's opening
 * words in quotes; the admins' "..." menu at the right end.
 */
@Composable
internal fun RepliesSheetHeader(title: String, photoUrl: String?, postWords: String, s: PostRepliesState?) {
    val c = Fariin.colors
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(photoUrl, 36.dp, squircle = true, kind = AvatarKind.CHANNEL)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label, maxLines = 1)
            if (postWords.isNotEmpty()) {
                Text("“$postWords”", fontSize = 13.sp, color = c.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (s != null && s.staff) RepliesAdminMenu(s) else Spacer(Modifier.size(48.dp))
    }
}

/** iOS segmented Picker: Replies | Quotes, the picked half raised. */
@Composable
internal fun RepliesQuotesTabs(tab: Int, onPick: (Int) -> Unit) {
    val c = Fariin.colors
    Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp).fillMaxWidth().height(32.dp)
        .clip(RoundedCornerShape(9.dp)).background(c.label.copy(alpha = 0.08f)).padding(2.dp)) {
        listOf("Replies", "Quotes").forEachIndexed { i, label ->
            Box(Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(7.dp))
                .background(if (i == tab) c.card else Color.Transparent)
                .clickable { onPick(i) }, contentAlignment = Alignment.Center) {
                Text(label, color = c.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** The channel admins' "..." menu: replies off or on for this post, or for the whole channel. */
@Composable
private fun RepliesAdminMenu(s: PostRepliesState) {
    val c = Fariin.colors
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton({ open = true }) { Icon(Icons.Filled.MoreVert, "More", tint = c.label) }
        DropdownMenu(open, onDismissRequest = { open = false }, shape = com.fariin.app.ui.components.FariinMenu.shape,
            border = com.fariin.app.ui.components.FariinMenu.border, containerColor = c.card) {
            DropdownMenuItem(text = { Text(PostReplyRules.postToggleLabel(s.postOff), color = c.label) },
                onClick = { open = false; scope.launch { s.setOff(post = true, off = !s.postOff) } })
            DropdownMenuItem(text = { Text(PostReplyRules.channelToggleLabel(s.channelOff), color = c.label) },
                onClick = { open = false; scope.launch { s.setOff(post = false, off = !s.channelOff) } })
        }
    }
}

/** The Replies tab's list: skeleton while loading, a failed page with Retry, the empty words, or the threads. */
@Composable
internal fun RepliesList(s: PostRepliesState, modifier: Modifier, onRetry: () -> Unit) {
    val c = Fariin.colors
    val list = rememberLazyListState()
    // A reply added (mine or a refresh's) scrolls the list to the end; the first load does not.
    var seen by remember { mutableIntStateOf(-1) }
    LaunchedEffect(s.replies.size, s.loaded) {
        val n = s.replies.size
        if (s.loaded && seen >= 0 && n > seen) {
            val last = list.layoutInfo.totalItemsCount - 1
            if (last >= 0) list.animateScrollToItem(last)
        }
        if (s.loaded) seen = n
    }
    val now = remember(s.replies) { System.currentTimeMillis() }
    Box(modifier) {
        when {
            s.failed && s.replies.isEmpty() -> Column(Modifier.fillMaxWidth().padding(top = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Couldn't load replies", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.label)
                Text("Check your connection and try again.", fontSize = 14.sp, color = c.secondary, modifier = Modifier.padding(top = 4.dp))
                TextButton(onClick = onRetry) { Text("Retry", color = c.blue) }
            }
            !s.loaded -> RepliesSkeleton()
            s.replies.isEmpty() -> Column(Modifier.fillMaxWidth().padding(top = 36.dp, start = 24.dp, end = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (s.off) "Replies are off" else "No replies yet", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.label)
                Text(if (s.off) PostReplyRules.offNote(s.channelOff) else "Be the first to reply.", fontSize = 14.sp,
                    color = c.secondary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
            }
            else -> LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(top = 4.dp, bottom = 8.dp)) {
                PostReplyRules.threads(s.replies).forEach { r ->
                    item(key = r.id) { ReplyRow(s, r, small = false, now) }
                    val kids = PostReplyRules.answers(s.replies, r.id)
                    if (kids.isNotEmpty()) {
                        val isOpen = r.id in s.open
                        if (isOpen) items(kids, key = { it.id }) { k ->
                            Box(Modifier.padding(start = 46.dp)) { ReplyRow(s, k, small = true, now) }
                        }
                        item(key = "toggle/" + r.id) {
                            ThreadToggle(PostReplyRules.threadToggle(kids.size, isOpen)) { s.toggleThread(r.id) }
                        }
                    }
                }
            }
        }
    }
}

/** "View 3 replies" / "Hide replies": a short line and the words, under the reply's bubble. */
@Composable
private fun ThreadToggle(label: String, onTap: () -> Unit) {
    val c = Fariin.colors
    Row(Modifier.padding(start = 62.dp, bottom = 10.dp).clip(RoundedCornerShape(6.dp)).clickable(onClick = onTap)
        .padding(vertical = 4.dp, horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(20.dp).height(1.dp).background(c.secondary.copy(alpha = 0.35f)))
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.secondary)
    }
}

/**
 * One reply (the owner's reference): the photo beside the bubble (34 dp, an answer 26), the name,
 * seal and age above it, the words in a soft rounded bubble, and under it a heart and Reply.
 * Double-tap the bubble = heart; long-press = Reply, Copy, Delete.
 */
@Composable
private fun ReplyRow(s: PostRepliesState, r: PostReply, small: Boolean, now: Long) {
    val c = Fariin.colors
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 20.dp)) { Avatar(r.photoUrl.ifEmpty { null }, if (small) 26.dp else 34.dp) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(Modifier.padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(r.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.label, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (r.verified) {
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Filled.Verified, contentDescription = "Verified", tint = c.blue, modifier = Modifier.size(12.dp))
                }
                Spacer(Modifier.width(4.dp))
                Text(PostReplyRules.age(r.at, now), fontSize = 13.sp, color = c.secondary, maxLines = 1)
            }
            Box(Modifier.padding(top = 4.dp, end = 24.dp)) {
                Text(r.text, fontSize = if (small) 15.sp else 17.sp, color = c.label,
                    modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(c.fieldFill)
                        .pointerInput(r.id) {
                            detectTapGestures(
                                onDoubleTap = { scope.launch { s.toggleLike(r) } },
                                onLongPress = { menu = true },
                            )
                        }
                        .padding(horizontal = 14.dp, vertical = 9.dp))
                DropdownMenu(menu, onDismissRequest = { menu = false }, shape = com.fariin.app.ui.components.FariinMenu.shape,
                    border = com.fariin.app.ui.components.FariinMenu.border, containerColor = c.card) {
                    if (!s.off) DropdownMenuItem(text = { Text("Reply", color = c.label) },
                        onClick = { menu = false; s.startAnswer(r) })
                    DropdownMenuItem(text = { Text("Copy", color = c.label) },
                        onClick = { menu = false; clipboard.setText(AnnotatedString(r.text)) })
                    if (r.canDelete) DropdownMenuItem(text = { Text("Delete", color = c.red) },
                        onClick = { menu = false; scope.launch { s.delete(r) } })
                }
            }
            Row(Modifier.padding(start = 6.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.clip(RoundedCornerShape(6.dp)).clickable { scope.launch { s.toggleLike(r) } }
                    .padding(vertical = 4.dp, horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (r.liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = if (r.liked) "Unlike" else "Like",
                        tint = if (r.liked) c.red else c.secondary, modifier = Modifier.size(15.dp))
                    if (r.likes > 0) {
                        Spacer(Modifier.width(4.dp))
                        Text(com.fariin.app.channel.ChannelRules.compactCount(r.likes), fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold, color = c.secondary)
                    }
                }
                if (!s.off) {
                    Spacer(Modifier.width(12.dp))
                    Text("Reply", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = c.secondary,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { s.startAnswer(r) }
                            .padding(vertical = 4.dp, horizontal = 2.dp))
                }
            }
        }
    }
}

/** Three replies' shapes while the list loads, in the same layout as [ReplyRow], pulsing. */
@Composable
private fun RepliesSkeleton() {
    val c = Fariin.colors
    val grey = c.secondary.copy(alpha = 0.16f)
    val pulse by rememberInfiniteTransition(label = "repliesSkeleton").animateFloat(
        1f, 0.45f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse")
    @Composable fun bar(w: Dp, h: Dp = 10.dp) = Box(Modifier.width(w).height(h).clip(RoundedCornerShape(h / 2)).background(grey))
    Column(Modifier.fillMaxWidth().graphicsLayer { alpha = pulse }) {
        repeat(3) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Box(Modifier.padding(top = 20.dp).size(34.dp).clip(CircleShape).background(grey))
                Spacer(Modifier.width(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    bar(110.dp, 8.dp)
                    Box(Modifier.width(220.dp).height(40.dp).clip(RoundedCornerShape(18.dp)).background(grey))
                    bar(60.dp, 8.dp)
                }
            }
        }
    }
}

/** The field at the bottom of the Replies tab, or the "turned off" line; nothing before the first load. */
@Composable
internal fun RepliesComposer(s: PostRepliesState) {
    val c = Fariin.colors
    val scope = rememberCoroutineScope()
    when {
        s.off -> Text(PostReplyRules.offNote(s.channelOff), fontSize = 13.sp, color = c.secondary, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(14.dp))
        s.loaded && (!s.failed || s.replies.isNotEmpty()) -> Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            s.problem?.let {
                Text(it, fontSize = 13.sp, color = c.red, modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 6.dp))
            }
            s.answering?.let { a ->
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Replying to ${a.name}", fontSize = 12.sp, color = c.secondary, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    IconButton({ s.answering = null }, Modifier.size(28.dp)) {
                        Icon(Icons.Filled.Close, "Stop replying", tint = c.secondary, modifier = Modifier.size(14.dp))
                    }
                }
            }
            Row(Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.fieldFill)
                .padding(start = 18.dp, end = 6.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).padding(vertical = 10.dp), contentAlignment = Alignment.CenterStart) {
                    if (s.draft.isEmpty()) Text("Add a reply…", fontSize = 16.sp, color = c.tertiary)
                    BasicTextField(
                        value = s.draft,
                        onValueChange = { s.draft = it.take(PostReplyRules.MAX_LENGTH); s.problem = null },
                        textStyle = TextStyle(fontSize = 16.sp, color = c.label),
                        cursorBrush = SolidColor(c.accent),
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth().focusRequester(s.field),
                    )
                }
                val can = !s.sending && PostReplyRules.cleanDraft(s.draft) != null
                TextButton(onClick = { scope.launch { s.send() } }, enabled = can) {
                    Text("Send", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = if (can) c.accent else c.tertiary)
                }
            }
        }
    }
}
