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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import com.fariin.app.data.ReplyRights
import com.fariin.app.ui.components.Avatar
import com.fariin.app.ui.components.AvatarKind
import com.fariin.app.ui.components.CarefulButton
import com.fariin.app.ui.components.CarefulKind
import com.fariin.app.ui.components.CarefulSheet
import com.fariin.app.ui.components.CarefulStep
import com.fariin.app.ui.theme.Fariin
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * THE REPLIES OF ONE POST, KEPT BY ONE MODEL (owner 2026-11-06, as iOS RepliesModel). Threads come
 * newest first a page at a time (shown oldest at the top); a thread's answers are fetched when it is
 * opened. While the sheet is open it listens to ONE small doc (`messages/{mid}/rh/head`) and asks
 * for the newest page only when that changes: no polling, never the replies themselves. Every local
 * action (a send, a heart, a delete) shows at once and is kept over any answer that left the server
 * before it. A send carries the phone's id, which becomes the reply's id: a retry cannot post twice.
 */
@Stable
class PostRepliesState(private val cid: String, private val mid: String, private val scope: CoroutineScope) {
    private val cacheKey = (com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: "") + "/$cid/$mid"

    var threads by mutableStateOf(cache[cacheKey] ?: emptyList())
        private set
    /** Thread id to its answers, oldest first. */
    var answers by mutableStateOf<Map<String, List<PostReply>>>(emptyMap())
        private set
    var answerNext by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    /** Threads whose answers show. */
    var open by mutableStateOf<Set<String>>(emptySet())
        private set
    var olderCursor by mutableStateOf("")
        private set
    var loaded by mutableStateOf(cache[cacheKey] != null)
        private set
    var failed by mutableStateOf(false)
        private set
    var loadingOlder by mutableStateOf(false)
        private set
    var off by mutableStateOf(false)
        private set
    var channelOff by mutableStateOf(false)
        private set
    var postOff by mutableStateOf(false)
        private set
    var may by mutableStateOf(ReplyRights())
        private set
    var count by mutableIntStateOf(0)
        private set
    /** Replies by others that landed while I was reading further up ("N new replies"). */
    var unseenNew by mutableIntStateOf(0)
    /** The row the list should bring into view next (my own new reply). */
    var scrollTarget by mutableStateOf<String?>(null)
    /** The end of the list is on screen: new replies then scroll in instead of counting in the pill. */
    var atEnd = true
    var draft by mutableStateOf("")
    /** The server's reason a reply was not taken (off, too fast), shown over the field. */
    var problem by mutableStateOf<String?>(null)
    /** The reply the field answers; null = the post itself. */
    var answering by mutableStateOf<PostReply?>(null)
    var reporting by mutableStateOf<PostReply?>(null)
    var banning by mutableStateOf<PostReply?>(null)
    /** A short line over the sheet ("Thanks. We'll take a look."). */
    var toast by mutableStateOf<String?>(null)
    val field = FocusRequester()

    private val pendingLikes = HashMap<String, Boolean>()
    private val likeInFlight = HashSet<String>()
    private val deletedIds = HashSet<String>()
    private val recentMine = HashMap<String, Long>()
    private var listener: ListenerRegistration? = null
    private var lastRev = -1
    private var refreshJob: Job? = null
    /** Only the newest refresh may land (an older one finishing last would flicker rows). */
    private var refreshGen = 0

    companion object {
        /** The last first page per post, for an instant paint when the sheet opens again (this run, this account). */
        private val cache = HashMap<String, List<PostReply>>()
    }

    // ---- Loading ----

    fun start() {
        scope.launch { loadFirst() }
        if (listener != null) return
        listener = FirebaseFirestore.getInstance().collection("conversations").document(cid)
            .collection("messages").document(mid).collection("rh").document("head")
            .addSnapshotListener { snap, _ ->
                if (snap == null) return@addSnapshotListener
                headChanged((snap.getLong("rev") ?: 0L).toInt())
            }
    }

    fun stop() {
        listener?.remove()
        listener = null
        refreshJob?.cancel()
    }

    private fun headChanged(rev: Int) {
        val moved = PostReplyRules.headMoved(lastRev, rev, loaded)
        lastRev = rev
        if (!moved) return
        // Several changes close together become one fetch (a busy thread).
        refreshJob?.cancel()
        refreshJob = scope.launch {
            delay(2_000)
            refreshNewest()
        }
    }

    private fun applyFlags(p: com.fariin.app.data.RepliesPage) {
        off = p.off; channelOff = p.channelOff; postOff = p.postOff; may = p.may
        count = p.count
    }

    private fun rows(list: List<PostReply>): List<PostReply> =
        list.filter { it.id !in deletedIds }.map { PostReplyRules.overlay(it, pendingLikes[it.id]) }

    suspend fun loadFirst() {
        val p = PostReplies.page(cid, mid)
        if (p == null) { failed = true; loaded = true; return }
        applyFlags(p)
        val page = rows(p.items)
        if (threads.isEmpty() || olderCursor.isEmpty()) olderCursor = p.next
        threads = PostReplyRules.mergeFirst(threads, page)
        if (lastRev < 0) lastRev = p.rev
        loaded = true
        failed = false
        cache[cacheKey] = page
    }

    fun retry() { failed = false; scope.launch { loadFirst() } }

    /** The newest page again, merged by id (PostReplyRules.mergeNewest); open threads whose answers moved, too. */
    suspend fun refreshNewest() {
        val gen = ++refreshGen
        val p = PostReplies.page(cid, mid) ?: return
        if (gen != refreshGen) return
        applyFlags(p)
        val page = rows(p.items)
        val now = System.currentTimeMillis()
        recentMine.entries.removeAll { now - it.value > PostReplyRules.RECENT_MINE_MS }
        val m = PostReplyRules.mergeNewest(threads, page, p.next, recentMine.keys)
        threads = m.list
        m.resetCursor?.let { olderCursor = it }
        if (m.added > 0) {
            if (atEnd) scrollTarget = m.list.lastOrNull()?.id else unseenNew += m.added
        }
        cache[cacheKey] = page
        val changed = open.filter { t ->
            val r = page.firstOrNull { it.id == t } ?: return@filter false
            r.answers != (answers[t]?.count { it.send == PostReply.Send.SENT } ?: -1)
        }
        changed.take(5).forEach { t -> scope.launch { loadAnswers(t, more = false) } }
    }

    fun loadOlder() {
        if (olderCursor.isEmpty() || loadingOlder) return
        loadingOlder = true
        scope.launch {
            val p = PostReplies.page(cid, mid, olderCursor)
            if (p != null) {
                olderCursor = p.next
                threads = PostReplyRules.mergeOlder(threads, rows(p.items))
            }
            loadingOlder = false
        }
    }

    suspend fun loadAnswers(rid: String, more: Boolean) {
        val cursor = if (more) answerNext[rid].orEmpty() else ""
        val p = PostReplies.answers(cid, mid, rid, cursor) ?: return
        answers = answers + (rid to PostReplyRules.mergeAnswers(answers[rid].orEmpty(), rows(p.items), more))
        answerNext = answerNext + (rid to p.next)
    }

    fun toggleThread(rid: String) {
        if (rid in open) { open = open - rid; return }
        open = open + rid
        if (answers[rid] == null) scope.launch { loadAnswers(rid, more = false) }
    }

    /** Reply to [r]: its thread opens, so the answer is seen landing under it. */
    fun startAnswer(r: PostReply) {
        answering = r
        val t = r.parentId.ifEmpty { r.id }
        if (t !in open) toggleThread(t)
        runCatching { field.requestFocus() }
    }

    // ---- Actions ----

    fun send() {
        val text = PostReplyRules.cleanDraft(draft) ?: return
        val thread = answering?.let { it.parentId.ifEmpty { it.id } }.orEmpty()
        draft = ""
        answering = null
        problem = null
        sendWith(text, thread, clientId = null)
    }

    fun retry(r: PostReply) = sendWith(r.text, r.parentId, r.id)

    fun discard(r: PostReply) = edit(r.parentId) { list -> list.filterNot { it.id == r.id } }

    private fun sendWith(text: String, thread: String, clientId: String?) {
        val id = clientId ?: PostReplyRules.clientId(UUID.randomUUID().toString())
        val row = PostReplyRules.pending(id, text, "You", "", thread, System.currentTimeMillis())
        edit(thread) { list ->
            if (list.any { it.id == id }) list.map { if (it.id == id) it.copy(send = PostReply.Send.SENDING) else it } else list + row
        }
        if (thread.isNotEmpty()) open = open + thread
        scrollTarget = id
        scope.launch {
            PostReplies.add(cid, mid, id, text, thread)
                .onSuccess { saved ->
                    edit(thread) { list -> list.map { if (it.id == id) saved else it } }
                    recentMine[id] = System.currentTimeMillis()
                    if (clientId == null) {
                        if (thread.isNotEmpty()) threads = threads.map { if (it.id == thread) it.copy(answers = it.answers + 1) else it }
                        count += 1
                    }
                }
                .onFailure { e ->
                    edit(thread) { list -> list.map { if (it.id == id) it.copy(send = PostReply.Send.FAILED) else it } }
                    problem = e.message
                }
        }
    }

    /** Changes the threads ([thread] "") or one thread's answers. */
    private fun edit(thread: String, change: (List<PostReply>) -> List<PostReply>) {
        if (thread.isEmpty()) threads = change(threads)
        else answers = answers + (thread to change(answers[thread].orEmpty()))
    }

    /** One call in flight per heart; taps meanwhile only change what is wanted, sent when it returns. */
    fun toggleLike(r: PostReply) {
        if (r.send != PostReply.Send.SENT) return
        val cur = (if (r.parentId.isEmpty()) threads else answers[r.parentId].orEmpty()).firstOrNull { it.id == r.id } ?: r
        val want = !cur.liked
        edit(r.parentId) { list -> list.map { if (it.id == r.id) PostReplyRules.withLike(it, want) else it } }
        pendingLikes[r.id] = want
        if (!likeInFlight.add(r.id)) return
        scope.launch {
            while (true) {
                val w = pendingLikes[r.id] ?: break
                val ok = PostReplies.like(cid, mid, r, w)
                if (pendingLikes[r.id] == w) {
                    pendingLikes.remove(r.id)
                    if (!ok) edit(r.parentId) { list -> list.map { if (it.id == r.id) PostReplyRules.withLike(it, !w) else it } }
                }
            }
            likeInFlight.remove(r.id)
        }
    }

    fun delete(r: PostReply) {
        deletedIds += r.id
        val t = r.parentId
        if (answering?.let { it.id == r.id || it.parentId == r.id } == true) answering = null
        edit(t) { list -> list.filterNot { it.id == r.id } }
        if (t.isNotEmpty()) threads = threads.map { if (it.id == t) it.copy(answers = maxOf(0, it.answers - 1)) else it }
        else { answers = answers - r.id; open = open - r.id }
        scope.launch {
            val removed = PostReplies.delete(cid, mid, r)
            if (removed != null) { count = maxOf(0, count - removed); return@launch }
            // Refused: only this one row comes back, never a saved list over newer arrivals.
            deletedIds -= r.id
            edit(t) { list -> (list + r).sortedBy { it.at } }
            if (t.isNotEmpty()) threads = threads.map { if (it.id == t) it.copy(answers = it.answers + 1) else it }
            toast = "Couldn't delete this reply."
        }
    }

    fun report(r: PostReply, reason: String) = scope.launch {
        toast = if (PostReplies.report(cid, mid, r, reason)) "Thanks. We'll take a look." else "Couldn't send the report."
    }

    fun ban(r: PostReply, all: Boolean) = scope.launch {
        if (PostReplies.ban(cid, mid, r, all)) {
            toast = if (all) "Banned. Their replies are gone." else "Removed and banned."
            refreshNewest()
        } else toast = "Couldn't do that."
    }

    /** [post] true = this post only, false = the whole channel. */
    fun setOff(post: Boolean, off: Boolean) = scope.launch {
        if (PostReplies.setOff(cid, if (post) mid else null, off)) loadFirst() else toast = "Couldn't change replies."
    }

    fun setAlerts(on: Boolean) = scope.launch {
        toast = if (PostReplies.setAlerts(cid, on)) (if (on) "Reply alerts on." else "Reply alerts off.") else "Couldn't change reply alerts."
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
        if (s != null && PostReplyRules.showsAdminMenu(s.may)) RepliesAdminMenu(s) else Spacer(Modifier.size(48.dp))
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

/**
 * The admins' "..." menu, by right: replies off or on for this post or the whole channel (Change
 * info); the summaries of new replies on or off (Delete messages, the admins they go to).
 */
@Composable
private fun RepliesAdminMenu(s: PostRepliesState) {
    val c = Fariin.colors
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton({ open = true }) { Icon(Icons.Filled.MoreVert, "Reply settings", tint = c.label) }
        DropdownMenu(open, onDismissRequest = { open = false }, shape = com.fariin.app.ui.components.FariinMenu.shape,
            border = com.fariin.app.ui.components.FariinMenu.border, containerColor = c.card) {
            if (s.may.settings) {
                DropdownMenuItem(text = { Text(PostReplyRules.postToggleLabel(s.postOff), color = c.label) },
                    onClick = { open = false; s.setOff(post = true, off = !s.postOff) })
                DropdownMenuItem(text = { Text(PostReplyRules.channelToggleLabel(s.channelOff), color = c.label) },
                    onClick = { open = false; s.setOff(post = false, off = !s.channelOff) })
            }
            if (s.may.del) {
                DropdownMenuItem(text = { Text("Reply Alerts On", color = c.label) }, onClick = { open = false; s.setAlerts(true) })
                DropdownMenuItem(text = { Text("Reply Alerts Off", color = c.label) }, onClick = { open = false; s.setAlerts(false) })
            }
        }
    }
}

/** One line of the Replies list, flattened so the list stays lazy all the way down. */
private sealed interface ReplyItem {
    val key: String
    data object Older : ReplyItem { override val key = "older" }
    data class Thread(val r: PostReply) : ReplyItem { override val key = "t-" + r.id }
    data class Answer(val r: PostReply) : ReplyItem { override val key = "a-" + r.id }
    data class Toggle(val rid: String, val n: Int, val open: Boolean) : ReplyItem { override val key = "g-$rid" }
    data class More(val rid: String) : ReplyItem { override val key = "m-$rid" }
}

private fun replyItems(s: PostRepliesState): List<ReplyItem> = buildList {
    if (s.olderCursor.isNotEmpty()) add(ReplyItem.Older)
    for (r in s.threads) {
        add(ReplyItem.Thread(r))
        val got = s.answers[r.id].orEmpty()
        val total = maxOf(r.answers, got.size)
        if (total <= 0) continue
        val isOpen = r.id in s.open
        if (isOpen) {
            got.forEach { add(ReplyItem.Answer(it)) }
            if (s.answerNext[r.id].orEmpty().isNotEmpty()) add(ReplyItem.More(r.id))
        }
        add(ReplyItem.Toggle(r.id, total, isOpen))
    }
}

/** The Replies tab's list: skeleton while loading, a failed page with Retry, the empty words, or the threads. */
@Composable
internal fun RepliesList(s: PostRepliesState, modifier: Modifier) {
    val c = Fariin.colors
    val list = rememberLazyListState()
    val items = replyItems(s)
    val now = remember(s.threads, s.answers) { System.currentTimeMillis() }
    // Opens at the newest replies, next to the field (iOS anchors the list at its end).
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(s.loaded, items.size) {
        if (!placed && s.loaded && items.isNotEmpty()) { placed = true; list.scrollToItem(items.size - 1) }
    }
    LaunchedEffect(list) {
        snapshotFlow { !list.canScrollForward }.collect { end -> s.atEnd = end; if (end) s.unseenNew = 0 }
    }
    // My new reply (or others' while at the end) brought into view once it is laid out.
    LaunchedEffect(s.scrollTarget) {
        val id = s.scrollTarget ?: return@LaunchedEffect
        withFrameNanos { }
        val i = replyItems(s).indexOfFirst { it.key == "t-$id" || it.key == "a-$id" }
        s.scrollTarget = null
        if (i >= 0) list.animateScrollToItem(i)
    }
    val scope = rememberCoroutineScope()
    Box(modifier) {
        when {
            s.failed && s.threads.isEmpty() -> Column(Modifier.fillMaxWidth().padding(top = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Couldn't load replies", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.label)
                Text("Check your connection and try again.", fontSize = 14.sp, color = c.secondary, modifier = Modifier.padding(top = 4.dp))
                TextButton(onClick = { s.retry() }) { Text("Try Again", color = c.label) }
            }
            !s.loaded -> RepliesSkeleton()
            s.threads.isEmpty() -> Column(Modifier.fillMaxWidth().padding(top = 36.dp, start = 24.dp, end = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (s.off) "Replies are off" else "No replies yet", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.label)
                Text(if (s.off) PostReplyRules.offNote(s.channelOff) else "Be the first to reply.", fontSize = 14.sp,
                    color = c.secondary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
            }
            else -> LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(top = 4.dp, bottom = 8.dp)) {
                items(items.size, key = { items[it].key }) { i ->
                    when (val row = items[i]) {
                        ReplyItem.Older -> SmallLink(if (s.loadingOlder) "Loading…" else "Show earlier replies",
                            Modifier.fillMaxWidth().padding(vertical = 4.dp), center = true) { s.loadOlder() }
                        is ReplyItem.Thread -> ReplyRow(s, row.r, small = false, now)
                        is ReplyItem.Answer -> Box(Modifier.padding(start = 46.dp)) { ReplyRow(s, row.r, small = true, now) }
                        is ReplyItem.Toggle -> ThreadToggle(PostReplyRules.threadToggle(row.n, row.open)) { s.toggleThread(row.rid) }
                        is ReplyItem.More -> SmallLink("Show more replies", Modifier.padding(start = 108.dp, bottom = 8.dp)) {
                            scope.launch { s.loadAnswers(row.rid, more = true) }
                        }
                    }
                }
            }
        }
        if (s.unseenNew > 0) {
            Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp).clip(RoundedCornerShape(50)).background(c.card)
                .clickable { s.unseenNew = 0; scope.launch { if (items.isNotEmpty()) list.animateScrollToItem(items.size - 1) } }
                .padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.KeyboardArrowDown, null, tint = c.label, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(PostReplyRules.newPill(s.unseenNew), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.label)
            }
        }
    }
    s.reporting?.let { r -> ReportSheet(onDismiss = { s.reporting = null }) { reason -> s.report(r, reason) } }
    s.banning?.let { r -> BanSheet(onDismiss = { s.banning = null }) { all -> s.ban(r, all) } }
}

/** "Show earlier replies" / "Show more replies": small grey words that load the next page. */
@Composable
private fun SmallLink(label: String, modifier: Modifier, center: Boolean = false, onTap: () -> Unit) {
    val c = Fariin.colors
    Box(modifier, contentAlignment = if (center) Alignment.Center else Alignment.CenterStart) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.secondary,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onTap).padding(vertical = 6.dp, horizontal = 4.dp))
    }
}

/** "Report this reply?": the reasons the server takes, one picked, then Report. */
@Composable
private fun ReportSheet(onDismiss: () -> Unit, onReport: (String) -> Unit) {
    var pick by remember { mutableStateOf("spam") }
    CarefulSheet(onDismiss, listOf(CarefulStep(
        title = "Report this reply?", options = PostReplyRules.REASONS, selected = pick, onSelect = { pick = it },
        buttons = listOf(CarefulButton("Report", CarefulKind.MAIN) { onReport(pick) }),
    )))
}

/** "Remove this person?": delete this reply and ban, or ban and delete every reply they wrote here. */
@Composable
private fun BanSheet(onDismiss: () -> Unit, onBan: (Boolean) -> Unit) {
    CarefulSheet(onDismiss, listOf(CarefulStep(
        title = "Remove this person?", body = "They leave the channel and can't come back or reply.",
        buttons = listOf(
            CarefulButton("Delete and Ban", CarefulKind.DESTRUCTIVE) { onBan(false) },
            CarefulButton("Ban and Delete All Their Replies", CarefulKind.DESTRUCTIVE) { onBan(true) },
        ),
    )))
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
 * Double-tap the bubble = heart; long-press = the menu the server's rights allow
 * (PostReplyRules.actions). Mine on its way reads "Sending…"; failed, "Not sent" with Retry.
 */
@Composable
private fun ReplyRow(s: PostRepliesState, r: PostReply, small: Boolean, now: Long) {
    val c = Fariin.colors
    val clipboard = LocalClipboardManager.current
    var menu by remember { mutableStateOf(false) }
    val dim = r.held || r.send != PostReply.Send.SENT
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
                    modifier = Modifier.alpha(if (dim) 0.55f else 1f).clip(RoundedCornerShape(18.dp)).background(c.fieldFill)
                        .pointerInput(r.id, r.send) {
                            detectTapGestures(
                                onDoubleTap = { s.toggleLike(r) },
                                onLongPress = { menu = true },
                            )
                        }
                        .padding(horizontal = 14.dp, vertical = 9.dp))
                DropdownMenu(menu, onDismissRequest = { menu = false }, shape = com.fariin.app.ui.components.FariinMenu.shape,
                    border = com.fariin.app.ui.components.FariinMenu.border, containerColor = c.card) {
                    for (a in PostReplyRules.actions(r, s.off, s.may)) {
                        val (label, red) = when (a) {
                            PostReplyRules.Action.REPLY -> "Reply" to false
                            PostReplyRules.Action.COPY -> "Copy" to false
                            PostReplyRules.Action.REPORT -> "Report" to false
                            PostReplyRules.Action.DELETE -> "Delete" to true
                            PostReplyRules.Action.BAN -> "Delete and Ban" to true
                            PostReplyRules.Action.RETRY -> "Retry" to false
                        }
                        DropdownMenuItem(text = { Text(label, color = if (red) c.red else c.label) }, onClick = {
                            menu = false
                            when (a) {
                                PostReplyRules.Action.REPLY -> s.startAnswer(r)
                                PostReplyRules.Action.COPY -> clipboard.setText(AnnotatedString(r.text))
                                PostReplyRules.Action.REPORT -> s.reporting = r
                                PostReplyRules.Action.DELETE -> if (r.send == PostReply.Send.FAILED) s.discard(r) else s.delete(r)
                                PostReplyRules.Action.BAN -> s.banning = r
                                PostReplyRules.Action.RETRY -> s.retry(r)
                            }
                        })
                    }
                }
            }
            Row(Modifier.padding(start = 6.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                when (r.send) {
                    PostReply.Send.SENDING -> Text("Sending…", fontSize = 12.sp, color = c.secondary, modifier = Modifier.padding(vertical = 4.dp))
                    PostReply.Send.FAILED -> {
                        Text("Not sent", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = c.red)
                        Spacer(Modifier.width(12.dp))
                        FooterWord("Retry", bold = true) { s.retry(r) }
                        Spacer(Modifier.width(12.dp))
                        FooterWord("Delete") { s.discard(r) }
                    }
                    PostReply.Send.SENT -> {
                        Row(Modifier.clip(RoundedCornerShape(6.dp)).clickable { s.toggleLike(r) }
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
                            FooterWord("Reply", bold = true) { s.startAnswer(r) }
                        }
                        if (r.held) {
                            Spacer(Modifier.width(12.dp))
                            Text("Hidden after reports", fontSize = 11.sp, color = c.secondary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FooterWord(label: String, bold: Boolean = false, onTap: () -> Unit) {
    val c = Fariin.colors
    Text(label, fontSize = 12.sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal, color = c.secondary,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onTap).padding(vertical = 4.dp, horizontal = 2.dp))
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
    when {
        s.off -> Text(PostReplyRules.offNote(s.channelOff), fontSize = 13.sp, color = c.secondary, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(14.dp))
        s.loaded && (!s.failed || s.threads.isNotEmpty()) -> Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
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
                val can = PostReplyRules.cleanDraft(s.draft) != null
                TextButton(onClick = { s.send() }, enabled = can) {
                    Text("Send", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = if (can) c.accent else c.tertiary)
                }
            }
        }
    }
}

/** The sheet's short line ("Thanks. We'll take a look."), gone after a moment. */
@Composable
internal fun RepliesToast(s: PostRepliesState, modifier: Modifier) {
    val t = s.toast ?: return
    LaunchedEffect(t) { delay(2_200); if (s.toast == t) s.toast = null }
    val c = Fariin.colors
    Text(t, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.label,
        modifier = modifier.padding(top = 6.dp).clip(RoundedCornerShape(50)).background(c.card)
            .padding(horizontal = 14.dp, vertical = 8.dp))
}
