package com.fariin.app.ui.channels

import com.fariin.app.ui.components.GlassDropdown
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.fariin.app.R
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.onPlaced
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
import com.fariin.app.data.ReportFlow
import com.fariin.app.data.ReportKind
import com.fariin.app.data.ReportTarget
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
 * A POST'S REPLIES SWITCH CHANGED ON THIS PHONE (iOS setRepliesOffLocally, owner 2026-11-07): the
 * post changes on screen at once, from the post menu or the sheet's ... menu. Older posts are outside
 * the live listener's window and would never hear their own doc change. Kept by post id for this run;
 * a post whose doc catches up drops its entry ([settled]).
 */
object PostRepliesSwitch {
    private val local = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()
    /** The menu closes before the server answers, so the call outlives it. */
    private val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)

    fun of(mid: String): Boolean? = local[mid]

    fun note(mid: String, off: Boolean) { local[mid] = off }

    /** The post's doc says the same now: the entry is no longer needed. */
    fun settled(mid: String, docOff: Boolean?) { if (docOff != null && local[mid] == docOff) local.remove(mid) }

    /** The post menu's Turn Off / Turn On Replies (Change info right; the server checks it). */
    fun set(cid: String, mid: String, off: Boolean, notice: (String) -> Unit) {
        scope.launch {
            val ok = PostReplies.setOff(cid, mid, off)
            if (ok) note(mid, off)
            notice(if (!ok) "Couldn't change replies" else if (off) "Replies turned off for this post" else "Replies turned on for this post")
        }
    }
}

/** The chat's Quick Reaction (Settings), a reply's double tap; the heart when none was picked. */
@Composable
internal fun rememberReplyQuickReaction(): String {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    return remember {
        ctx.getSharedPreferences(com.fariin.app.ui.settings.CHAT_PREFS, android.content.Context.MODE_PRIVATE)
            .getString(com.fariin.app.ui.settings.SettingsRules.QUICK_KEY, null)?.takeIf { it.isNotEmpty() } ?: PostReplyRules.HEART
    }
}

/**
 * THE REPLIES OF ONE POST, KEPT BY ONE MODEL (owner 2026-11-06, as iOS RepliesModel). Threads come
 * newest first a page at a time (shown oldest at the top); a thread's answers are fetched when it is
 * opened. While the sheet is open it listens to ONE small doc (`messages/{mid}/rh/head`) and asks
 * for the newest page only when that changes: no polling, never the replies themselves. Every local
 * action (a send, a heart, a delete) shows at once and is kept over any answer that left the server
 * before it. A send carries the phone's id, which becomes the reply's id: a retry cannot post twice.
 */
@Stable
class PostRepliesState(val cid: String, private val mid: String, private val scope: CoroutineScope) {
    private val cacheKey = (com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: "") + "/$cid/$mid"

    /** Top (the default) or Newest: the order the server pages come in; switching reloads from page one. */
    var sort by mutableStateOf(PostReplyRules.Sort.TOP)
        private set
    private fun key() = "$cacheKey/${sort.wire}"
    var threads by mutableStateOf(cache["$cacheKey/top"] ?: emptyList())
        private set
    /** The pages' thread ids in the server's order (PostReplyRules.displayOrder draws by it). */
    var order by mutableStateOf(cache["$cacheKey/top"]?.map { it.id } ?: emptyList())
        private set
    /** Ids page one held last time, and ids only later pages brought (mergePageOne). */
    private var firstIds: Set<String> = order.toSet()
    private val laterIds = HashSet<String>()
    /** Bumped by a sort switch: an answer for the old sort is dropped. */
    private var loadGen = 0
    /** Open threads showing all their answers, not only the first three ("View N more replies"). */
    var expanded by mutableStateOf<Set<String>>(emptySet())
        private set
    /** How many answers each open thread shows (missing = [PostReplyRules.ANSWERS_FIRST]); Hide resets it. */
    var shownAnswers by mutableStateOf<Map<String, Int>>(emptyMap())
        private set
    /** The row lit for a moment (a jump to an "@name", a push's comment). */
    var flashId by mutableStateOf<String?>(null)
        private set
    /** My reply whose words the field is changing (editReply), or null. */
    var editing by mutableStateOf<PostReply?>(null)
        private set
    /** A comment to open on once it is loaded (a push tap), or null. */
    private var focusWanted: com.fariin.app.data.ReplyFocus? = null
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
    var loaded by mutableStateOf(cache["$cacheKey/top"] != null)
        private set
    /**
     * The pinned thread (one per post, owner 2026-11-07): "" = none. Its row is kept in [threads]
     * like any other (so a reaction or an answer reaches it) and drawn first under "Pinned".
     */
    var pinnedId by mutableStateOf(pinCache[cacheKey] ?: "")
        private set
    /** A pin or unpin on its way: the pages' answer does not overrule it meanwhile. */
    private var pinBusy = false
    var failed by mutableStateOf(false)
        private set
    /** The server answered at least once while open (its off/on then counts over the bar's guess). */
    var answered by mutableStateOf(false)
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
    /** Replies by others that landed out of sight while I was reading further down ("N new replies"). */
    var unseenNew by mutableIntStateOf(0)
    /** The first of them, where the pill takes me. */
    var firstNewId: String? = null
    /** The row the list should bring into view next (my own new reply, a jump, a push's comment). */
    var scrollTarget by mutableStateOf<String?>(null)
    /** My new top-level reply: the list goes to its very top, so the pinned reply above it shows too (iOS). */
    var scrollTop by mutableStateOf(false)
    /** The top of the list is on screen: new replies then simply appear instead of counting in the pill. */
    var atTop = true
    var draft by mutableStateOf("")
    /** The server's reason a reply was not taken (off, too fast), shown over the field. */
    var problem by mutableStateOf<String?>(null)
    /** The reply the field answers; null = the post itself. */
    var answering by mutableStateOf<PostReply?>(null)
    var banning by mutableStateOf<PostReply?>(null)
    /** A short line over the sheet ("Thanks. We'll take a look."). */
    var toast by mutableStateOf<String?>(null)
    val field = FocusRequester()

    /** Reply id to the emoji wanted ("" = none) while a call is on its way. */
    private val pendingLikes = HashMap<String, String>()
    private val likeInFlight = HashSet<String>()
    /** What the server last agreed mine was, per reply, so a failed call puts it back. */
    private val confirmedMine = HashMap<String, String>()
    /**
     * Threads whose answers came from the server at least once. A thread can hold only my own
     * just-sent answer; "View N replies" then showed that one alone (owner 2026-11-07: "show me the
     * first ones"). Opened, it loads from the first answer down.
     */
    private val answersLoaded = HashSet<String>()
    /** Threads whose first answers are on their way: an open one reads "Loading…", not "Hide replies". */
    var answersLoading by mutableStateOf<Set<String>>(emptySet())
        private set
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
        private val pinCache = HashMap<String, String>()
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

    /**
     * The first page's pinned thread put in [list] (the server leaves it out of `items`, on every
     * page). While a pin of mine is on its way, the row I pinned stays where it is.
     */
    private fun withPin(p: com.fariin.app.data.RepliesPage, list: List<PostReply>): List<PostReply> {
        if (pinBusy) {
            val keep = threads.firstOrNull { it.id == pinnedId }
            return if (keep != null && list.none { it.id == keep.id }) list + keep else list
        }
        val pin = p.pinned?.let { rows(listOf(it)).firstOrNull() }
        pinnedId = pin?.id ?: ""
        pinCache[cacheKey] = pinnedId
        return if (pin == null) list else list.filterNot { it.id == pin.id } + pin
    }

    /** Page one in the current sort merged in (PostReplyRules.mergePageOne); null when the call failed. */
    private suspend fun takePageOne(): PostReplyRules.PageOne? {
        val gen = loadGen
        val p = PostReplies.page(cid, mid, sort = sort) ?: return null
        if (gen != loadGen) return PostReplyRules.PageOne(threads, emptyList())
        applyFlags(p)
        answered = true
        val page = rows(p.items)
        val ids = page.map { it.id }
        val now = System.currentTimeMillis()
        recentMine.entries.removeAll { now - it.value > PostReplyRules.RECENT_MINE_MS }
        val m = PostReplyRules.mergePageOne(threads, page, firstIds, laterIds, recentMine.keys)
        threads = withPin(p, m.list)
        val idSet = ids.toSet()
        order = ids + order.filter { it !in idSet && it in laterIds }
        firstIds = idSet
        // The "more" cursor is page one's until a later page has been read.
        if (laterIds.isEmpty()) olderCursor = p.next
        if (lastRev < 0) lastRev = p.rev
        loaded = true
        failed = false
        cache[key()] = page + listOfNotNull(threads.firstOrNull { it.id == pinnedId })
        return m
    }

    suspend fun loadFirst() {
        if (takePageOne() == null) { failed = true; loaded = true; return }
        prefetchAnswers(threads.filter { it.id in firstIds })
        tryFocus()
    }

    /** Top or Newest: the list starts again from page one in that order. */
    fun pickSort(s: PostReplyRules.Sort) {
        if (s == sort) return
        sort = s
        loadGen++
        val mine = threads.filter { it.send != PostReply.Send.SENT }
        // No empty list on a switch (iOS 83aed9034): with no saved page for the new order yet, the
        // rows already here stand in, newest first for Newest, until the server's page replaces them.
        val saved = cache[key()]
        val standIn = saved ?: threads.filter { it.send == PostReply.Send.SENT }.let { now ->
            if (s == PostReplyRules.Sort.NEW) now.sortedByDescending { it.at } else now.sortedByDescending { it.likes } }
        threads = standIn + mine
        order = standIn.map { it.id }
        firstIds = order.toSet()
        laterIds.clear()
        olderCursor = ""
        unseenNew = 0
        firstNewId = null
        loaded = cache[key()] != null
        scrollTarget = null
        scope.launch { loadFirst() }
    }

    /**
     * Owner 2026-11-07 ("View 1 reply opens late"), as iOS: once the first page is in, the first
     * answers of the pinned thread and the newest threads with answers, five at most, load in the
     * background, so "View N replies" opens on them at once. A thread loaded or loading is skipped.
     */
    private fun prefetchAnswers(page: List<PostReply>) {
        val pin = threads.firstOrNull { it.id == pinnedId && it.answers > 0 }
        val picks = (listOfNotNull(pin) + page.filter { it.answers > 0 })
            .map { it.id }.distinct()
            .filter { it !in answersLoaded && it !in answersLoading }
            .take(5)
        for (rid in picks) scope.launch { loadAnswers(rid, more = false) }
    }

    fun retry() { failed = false; scope.launch { loadFirst() } }

    /** Page one again (the change marker moved), merged by id; open threads whose answers moved, too. */
    suspend fun refreshNewest() {
        val gen = ++refreshGen
        val m = takePageOne() ?: return
        if (gen != refreshGen) return
        if (m.added.isNotEmpty()) {
            // Others' new replies never pull the list: out of sight they count in the "N new replies"
            // pill, which takes the reader to the first of them; in sight they simply appear.
            if (!atTop) { if (unseenNew == 0) firstNewId = m.added.first(); unseenNew += m.added.size }
        }
        val changed = open.filter { t ->
            val r = threads.firstOrNull { it.id == t } ?: return@filter false
            r.answers != (answers[t]?.count { it.send == PostReply.Send.SENT } ?: -1)
        }
        changed.take(5).forEach { t -> scope.launch { loadAnswers(t, more = false) } }
    }

    /** The next page in the sort's order, put under what is shown. */
    fun loadOlder() {
        if (olderCursor.isEmpty() || loadingOlder) return
        loadingOlder = true
        val gen = loadGen
        scope.launch {
            val p = PostReplies.page(cid, mid, olderCursor, sort)
            if (p != null && gen == loadGen) {
                olderCursor = p.next
                val page = rows(p.items)
                val byId = page.associateBy { it.id }
                threads = threads.map { byId[it.id] ?: it } + page.filter { r -> threads.none { it.id == r.id } }
                order = order + page.map { it.id }.filter { it !in order }
                laterIds += page.map { it.id }
                tryFocus()
            }
            loadingOlder = false
        }
    }

    /** The row lit for a moment. */
    fun flash(id: String) {
        flashId = id
        scope.launch { delay(1_600); if (flashId == id) flashId = null }
    }

    /**
     * A tap on an answer's "@name": that reply comes into view and lights up, its thread open and
     * showing all its answers (the next pages of answers fetched while it is not among them yet).
     */
    fun jumpTo(r: PostReply) {
        val to = r.to ?: return
        val thread = r.parentId.ifEmpty { r.id }
        show(thread, if (to.id == thread) "" else to.id)
    }

    /** A "replied to your comment" push: the sheet opens on that comment once it is loaded. */
    fun focus(f: com.fariin.app.data.ReplyFocus) { focusWanted = f; tryFocus() }

    private fun tryFocus() {
        val f = focusWanted ?: return
        if (threads.none { it.id == f.rid }) {
            // Not on the pages read so far: a few more pages are looked through, then it is let go.
            if (olderCursor.isNotEmpty() && laterIds.size < 4 * 30) loadOlder() else if (loaded) focusWanted = null
            return
        }
        focusWanted = null
        show(f.rid, f.aid)
    }

    private fun show(thread: String, answer: String) {
        if (answer.isEmpty()) { scrollTarget = thread; flash(thread); return }
        open = open + thread
        expanded = expanded + thread
        scope.launch {
            var tries = 0
            if (thread !in answersLoaded) loadAnswers(thread, more = false)
            while (answers[thread].orEmpty().none { it.id == answer } && answerNext[thread].orEmpty().isNotEmpty() && tries++ < 5)
                loadAnswers(thread, more = true)
            if (answers[thread].orEmpty().any { it.id == answer }) { scrollTarget = answer; flash(answer) }
            else { scrollTarget = thread; flash(thread) }
        }
    }

    /** "View N more replies": five more answers, the next page fetched when the loaded ones run out. */
    fun showMoreAnswers(rid: String) {
        val next = (shownAnswers[rid] ?: PostReplyRules.ANSWERS_FIRST) + PostReplyRules.ANSWERS_FIRST
        shownAnswers = shownAnswers + (rid to next)
        if (answers[rid].orEmpty().size < next && answerNext[rid].orEmpty().isNotEmpty())
            scope.launch { loadAnswers(rid, more = true) }
    }

    suspend fun loadAnswers(rid: String, more: Boolean) {
        // One first-page fetch per thread at a time (the background fetch and a tap meet here).
        if (!more && rid in answersLoading) return
        if (!more) answersLoading = answersLoading + rid
        val cursor = if (more) answerNext[rid].orEmpty() else ""
        val p = try { PostReplies.answers(cid, mid, rid, cursor) } finally { if (!more) answersLoading = answersLoading - rid } ?: return
        answers = answers + (rid to PostReplyRules.mergeAnswers(answers[rid].orEmpty(), rows(p.items), more))
        answerNext = answerNext + (rid to p.next)
        answersLoaded += rid
    }

    fun toggleThread(rid: String) {
        if (rid in open) { open = open - rid; expanded = expanded - rid; shownAnswers = shownAnswers - rid; return }
        open = open + rid
        if (rid !in answersLoaded) scope.launch { loadAnswers(rid, more = false) }
    }

    /** Reply to [r]: its thread opens, so the answer is seen landing under it. */
    fun startAnswer(r: PostReply) {
        if (editing != null) cancelEdit()
        answering = r
        val t = r.parentId.ifEmpty { r.id }
        if (t !in open) toggleThread(t)
        runCatching { field.requestFocus() }
    }

    // ---- Actions ----

    fun send() {
        if (editing != null) { saveEdit(); return }
        val text = PostReplyRules.cleanDraft(draft) ?: return
        val a = answering
        val thread = a?.let { it.parentId.ifEmpty { it.id } }.orEmpty()
        // Reply on an answer answers that answer ("@name"); on a thread, the thread itself.
        val to = a?.takeIf { it.parentId.isNotEmpty() }?.let { com.fariin.app.data.ReplyTo(it.id, it.name) }
        draft = ""
        answering = null
        problem = null
        sendWith(text, thread, clientId = null, to = to)
    }

    fun retry(r: PostReply) = sendWith(r.text, r.parentId, r.id, r.to)

    /** Edit on my reply: the field takes its words, an "Editing" bar over it. */
    fun startEdit(r: PostReply) {
        answering = null
        editing = r
        draft = r.text
        problem = null
        runCatching { field.requestFocus() }
    }

    fun cancelEdit() { editing = null; draft = ""; problem = null }

    /** The edit shown at once; put back with the server's words if refused (too late, replies off). */
    private fun saveEdit() {
        val r = editing ?: return
        val text = PostReplyRules.cleanDraft(draft) ?: return
        editing = null
        draft = ""
        problem = null
        val before = current(r)
        if (text == before.text) return
        edit(r.parentId) { list -> list.map { if (it.id == r.id) it.copy(text = text, edited = true) else it } }
        scope.launch {
            PostReplies.edit(cid, mid, before, text)
                .onSuccess { saved -> edit(r.parentId) { list -> list.map { if (it.id == r.id) PostReplyRules.overlay(saved, pendingLikes[r.id]) else it } } }
                .onFailure { e ->
                    edit(r.parentId) { list -> list.map { if (it.id == r.id) it.copy(text = before.text, edited = before.edited) else it } }
                    toast = e.message ?: "Couldn't save your edit."
                }
        }
    }

    fun discard(r: PostReply) = edit(r.parentId) { list -> list.filterNot { it.id == r.id } }

    private fun sendWith(text: String, thread: String, clientId: String?, to: com.fariin.app.data.ReplyTo? = null) {
        val id = clientId ?: PostReplyRules.clientId(UUID.randomUUID().toString())
        val row = PostReplyRules.pending(id, text, "You", "", thread, System.currentTimeMillis(), to)
        // My answer lands at the thread's end: the whole thread shows, so it is seen.
        if (thread.isNotEmpty()) expanded = expanded + thread
        // A thread never opened: its other answers load too, or only this one would show and the
        // thread would look fully loaded. mergeAnswers keeps this row while it is on its way.
        if (thread.isNotEmpty() && thread !in answersLoaded) scope.launch { loadAnswers(thread, more = false) }
        edit(thread) { list ->
            if (list.any { it.id == id }) list.map { if (it.id == id) it.copy(send = PostReply.Send.SENDING) else it } else list + row
        }
        if (thread.isNotEmpty()) open = open + thread
        // An answer is brought into view; a new top-level reply goes to the list's very top (iOS: the
        // pinned reply above it must not be scrolled off, "pin disappeared").
        if (thread.isEmpty()) scrollTop = true else scrollTarget = id
        scope.launch {
            PostReplies.add(cid, mid, id, text, thread, to)
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

    private fun current(r: PostReply): PostReply =
        (if (r.parentId.isEmpty()) threads else answers[r.parentId].orEmpty()).firstOrNull { it.id == r.id } ?: r

    /** Double tap: the Quick Reaction on, or mine off when I already reacted (iOS toggleLike). */
    fun quickReact(r: PostReply, quick: String) {
        val cur = current(r)
        // A Quick Reaction that is not one plain emoji (a custom one) reacts with the heart here.
        react(cur, cur.my.ifEmpty { PostReplyRules.cleanEmoji(quick) ?: PostReplyRules.HEART })
    }

    /**
     * Mine becomes [emoji]; the same emoji again takes it back, as in the chat. One call in flight
     * per reply; picks meanwhile only change what is wanted, sent when it returns.
     */
    fun react(r: PostReply, emoji: String) {
        // Replies off for this post closes reactions too (server likeReply refuses).
        if (r.send != PostReply.Send.SENT || off) return
        val clean = PostReplyRules.cleanEmoji(emoji) ?: return
        val cur = current(r)
        val want = PostReplyRules.nextPick(cur, clean)
        if (r.id !in confirmedMine) confirmedMine[r.id] = cur.my
        edit(r.parentId) { list -> list.map { if (it.id == r.id) PostReplyRules.withMine(it, want) else it } }
        pendingLikes[r.id] = want
        if (!likeInFlight.add(r.id)) return
        scope.launch {
            while (true) {
                val w = pendingLikes[r.id] ?: break
                val why = PostReplies.reactOrWhy(cid, mid, r, w)
                val ok = why == null
                if (pendingLikes[r.id] == w) {
                    pendingLikes.remove(r.id)
                    val back = confirmedMine.remove(r.id)
                    if (!ok && back != null) edit(r.parentId) { list -> list.map { if (it.id == r.id) PostReplyRules.withMine(it, back) else it } }
                    if (!ok) toast = why
                } else if (ok) confirmedMine[r.id] = w
            }
            likeInFlight.remove(r.id)
        }
    }

    /** Pin [r] (replacing any other pin) or, [on] false, unpin it. Shown at once; put back if refused. */
    fun pin(r: PostReply, on: Boolean) {
        val before = pinnedId
        pinnedId = if (on) r.id else ""
        pinBusy = true
        scope.launch {
            val ok = PostReplies.pin(cid, mid, if (on) r.id else "")
            pinBusy = false
            if (!ok) pinnedId = before
            pinCache[cacheKey] = pinnedId
            toast = if (!ok) "Couldn't change the pin." else if (on) "Reply pinned." else "Reply unpinned."
        }
    }

    /**
     * A tap on a reply's photo or name: [open] gets the author's uid, or null for a Creator reply
     * (it speaks as the channel: the channel's page, for everyone, moderators too, as iOS).
     * Moderators have the uid on the row already; everyone else asks `replyAuthor` for this one
     * reply. My own reply does nothing.
     */
    fun openAuthor(r: PostReply, open: (String?) -> Unit) {
        if (r.mine || r.send != PostReply.Send.SENT) return
        if (r.creator) { open(null); return }
        if (r.uid.isNotEmpty()) { open(r.uid); return }
        scope.launch {
            PostReplies.author(cid, mid, r)
                .onSuccess { a -> if (a.channel) open(null) else if (a.uid.isNotEmpty()) open(a.uid) else toast = "Couldn't open this profile." }
                .onFailure { toast = "Couldn't open this profile." }
        }
    }

    fun delete(r: PostReply) {
        deletedIds += r.id
        val t = r.parentId
        // The server unpins a deleted pinned thread.
        val wasPinned = t.isEmpty() && r.id == pinnedId
        if (wasPinned) pinnedId = ""
        if (answering?.let { it.id == r.id || it.parentId == r.id } == true) answering = null
        edit(t) { list -> list.filterNot { it.id == r.id } }
        if (t.isNotEmpty()) threads = threads.map { if (it.id == t) it.copy(answers = maxOf(0, it.answers - 1)) else it }
        else { answers = answers - r.id; open = open - r.id }
        scope.launch {
            val removed = PostReplies.delete(cid, mid, r)
            if (removed != null) { count = maxOf(0, count - removed); return@launch }
            // Refused: only this one row comes back, never a saved list over newer arrivals.
            deletedIds -= r.id
            if (wasPinned) pinnedId = r.id
            edit(t) { list -> (list + r).sortedBy { it.at } }
            if (t.isNotEmpty()) threads = threads.map { if (it.id == t) it.copy(answers = it.answers + 1) else it }
            toast = "Couldn't delete this reply."
        }
    }

    /** The one report sheet, for this reply (a thread's `rid`, an answer's `rid` + `aid`). */
    /** A reply's report waiting for its one yes (iOS 69a5b01f6: one step, no reasons list). */
    var quickReport by mutableStateOf<ReportTarget?>(null)

    fun report(r: PostReply) {
        val answer = r.parentId.isNotEmpty()
        quickReport = ReportTarget(ReportKind.REPLY, name = r.name, personUid = r.uid.ifEmpty { null }, cid = cid, messageId = mid,
            replyId = if (answer) r.parentId else r.id, answerId = if (answer) r.id else null)
    }

    /** "Report this reply?" said yes: a plain report (Something else, no note, no block). */
    fun sendQuickReport() {
        val t = quickReport ?: return
        quickReport = null
        scope.launch {
            val refused = com.fariin.app.data.ReportSender.send(t, com.fariin.app.data.ReportReason.OTHER, "", block = false)
            toast = refused ?: "Reported. Thanks for letting us know."
        }
    }

    fun ban(r: PostReply, all: Boolean) = scope.launch {
        if (PostReplies.ban(cid, mid, r, all)) {
            toast = if (all) "Banned. Their replies are gone." else "Removed and banned."
            refreshNewest()
        } else toast = "Couldn't do that."
    }

    /** This post's switch (the sheet's ... menu): the post on screen changes at once (PostRepliesSwitch). */
    fun setPostOff(off: Boolean) = scope.launch {
        if (PostReplies.setOff(cid, mid, off)) {
            PostRepliesSwitch.note(mid, off)
            toast = if (off) "Replies turned off for this post." else "Replies turned on for this post."
            loadFirst()
        } else toast = "Couldn't change replies."
    }

    fun setAlerts(on: Boolean) = scope.launch {
        toast = if (PostReplies.setAlerts(cid, on)) (if (on) "Reply alerts on." else "Reply alerts off.") else "Couldn't change reply alerts."
    }
}

/**
 * A COMMENTS SHEET'S TOP (iOS, owner 2026-11-07, his reference picture): no channel card, no
 * segmented control. Two underlined word tabs on the left ("Replies" hidden while replies are off:
 * Quotes only), the admins' ... and a round 40 dp X on the right.
 */
@Composable
internal fun RepliesSheetTop(tab: Int, showReplies: Boolean, s: PostRepliesState, showQuotes: Boolean = true,
                             onPick: (Int) -> Unit, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 18.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            if (showReplies) TabWord("Replies", tab == 0) { onPick(0) }
            // The channel's Quotes switch off: no Quotes tab.
            if (showQuotes) TabWord("Quotes", tab == 1) { onPick(1) }
        }
        Spacer(Modifier.weight(1f))
        if (PostReplyRules.showsAdminMenu(s.may)) {
            RepliesAdminMenu(s)
            Spacer(Modifier.width(10.dp))
        }
        RoundTopButton(ImageVector.vectorResource(R.drawable.ic_sf_xmark), "Close", onClose)
    }
}

/** One tab: its word (bold when open), a 2.5 dp line under the open one, as wide as the word. */
@Composable
private fun TabWord(label: String, on: Boolean, onTap: () -> Unit) {
    val c = Fariin.colors
    Column(Modifier.width(androidx.compose.foundation.layout.IntrinsicSize.Max)
        .clickable(interactionSource = null, indication = null, onClick = onTap)
        .semantics { selected = on }) {
        Text(label, fontSize = 17.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
            color = if (on) c.label else c.secondary, maxLines = 1)
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(2.5.dp).clip(RoundedCornerShape(50))
            .background(if (on) c.label else Color.Transparent))
    }
}

/** A 40 dp round button in the soft fill (the X, the admins' ...). */
@Composable
private fun RoundTopButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onTap: () -> Unit) {
    val c = Fariin.colors
    Box(Modifier.size(40.dp).clip(CircleShape).background(c.label.copy(alpha = 0.08f)).clickable(onClick = onTap),
        contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = label, tint = c.label, modifier = Modifier.size(20.dp))
    }
}

/**
 * The admins' "..." menu, by right (iOS): this post's replies off or on (Change info; the whole
 * channel's switch lives in the channel's settings); the summaries of new replies on or off
 * (Delete messages, the admins they go to).
 */
@Composable
private fun RepliesAdminMenu(s: PostRepliesState) {
    val c = Fariin.colors
    var open by remember { mutableStateOf(false) }
    Box {
        RoundTopButton(ImageVector.vectorResource(R.drawable.ic_more_ios), "Reply settings") { open = true }
        GlassDropdown(open, onDismissRequest = { open = false }, shape = com.fariin.app.ui.components.FariinMenu.shape,
            border = com.fariin.app.ui.components.FariinMenu.border, containerColor = c.card) {
            if (s.may.settings) {
                DropdownMenuItem(text = { Text(PostReplyRules.postToggleLabel(s.postOff), color = c.label) },
                    onClick = { open = false; s.setPostOff(!s.postOff) })
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
    data object PinLabel : ReplyItem { override val key = "pinned" }
    data class Thread(val r: PostReply) : ReplyItem { override val key = "t-" + r.id }
    data class Answer(val r: PostReply) : ReplyItem { override val key = "a-" + r.id }
    data class Toggle(val rid: String, val n: Int, val open: Boolean) : ReplyItem { override val key = "g-$rid" }
    data class More(val rid: String) : ReplyItem { override val key = "m-$rid" }
    data class MoreCount(val rid: String, val n: Int) : ReplyItem { override val key = "n-$rid" }
}

private fun replyItems(s: PostRepliesState): List<ReplyItem> = buildList {
    fun thread(r: PostReply) {
        add(ReplyItem.Thread(r))
        val got = s.answers[r.id].orEmpty()
        val total = maxOf(r.answers, got.size)
        if (total <= 0) return
        val isOpen = r.id in s.open
        if (isOpen) {
            // Five answers, then "View N more replies" adds five each tap (iOS ab0a283a); all of them
            // when the thread is wanted whole (my own answer landing, a jump to an "@name").
            val cap = if (r.id in s.expanded) Int.MAX_VALUE else (s.shownAnswers[r.id] ?: PostReplyRules.ANSWERS_FIRST)
            val all = total <= cap
            (if (all) got else got.take(cap)).forEach { add(ReplyItem.Answer(it)) }
            if (!all) add(ReplyItem.MoreCount(r.id, total - minOf(got.size, cap)))
            else if (s.answerNext[r.id].orEmpty().isNotEmpty()) add(ReplyItem.More(r.id))
        }
        add(ReplyItem.Toggle(r.id, total, isOpen))
    }
    // The pinned thread first, under a small "Pinned" line, then the rest in the sort's order, the
    // next page's link at the end.
    val pin = s.pinnedId.takeIf { it.isNotEmpty() }?.let { id -> s.threads.firstOrNull { it.id == id } }
    if (pin != null) { add(ReplyItem.PinLabel); thread(pin) }
    for (r in PostReplyRules.displayOrder(s.threads, s.order, s.pinnedId)) thread(r)
    if (s.olderCursor.isNotEmpty()) add(ReplyItem.Older)
}

/** Top | Newest over the Replies list (Top by default); a pick reloads from the first page. */
@Composable
private fun SortSwitch(s: PostRepliesState) {
    val c = Fariin.colors
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (o in PostReplyRules.Sort.entries) {
            val on = s.sort == o
            Text(o.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (on) c.bg else c.label,
                modifier = Modifier.clip(CircleShape).background(if (on) c.label else c.label.copy(alpha = 0.08f))
                    .semantics { selected = on }
                    .clickable { s.pickSort(o) }.padding(horizontal = 14.dp, vertical = 6.dp))
        }
    }
}

/** The Replies tab's list: skeleton while loading, a failed page with Retry, the empty words, or the threads. */
@Composable
internal fun RepliesList(s: PostRepliesState, modifier: Modifier) {
    if (s.quickReport != null) {
        val c = Fariin.colors
        com.fariin.app.ui.components.GlassAlert(onDismissRequest = { s.quickReport = null }, containerColor = c.card,
            title = { Text("Report this reply?") },
            confirmButton = { androidx.compose.material3.TextButton({ s.sendQuickReport() }) { Text("Report", color = c.red) } },
            dismissButton = { androidx.compose.material3.TextButton({ s.quickReport = null }) { Text("Cancel", color = c.accent) } })
    }
    val c = Fariin.colors
    val list = rememberLazyListState()
    val items = replyItems(s)
    val now = remember(s.threads, s.answers) { System.currentTimeMillis() }
    // ⛔ OPENS AT THE TOP, on the first replies of the sort (iOS, owner 2026-11-07).
    LaunchedEffect(list) {
        snapshotFlow { !list.canScrollBackward }.collect { top -> s.atTop = top; if (top) s.unseenNew = 0 }
    }
    // A sort switch starts at the top again.
    LaunchedEffect(s.sort) { if (items.isNotEmpty()) list.scrollToItem(0) }
    // My new reply, a jump to an "@name" or a push's comment, brought into view once it is laid out
    // (waiting while its thread's answers are still coming).
    LaunchedEffect(s.scrollTop) {
        if (!s.scrollTop) return@LaunchedEffect
        withFrameNanos { }
        s.scrollTop = false
        list.animateScrollToItem(0)
    }
    LaunchedEffect(s.scrollTarget, items.size) {
        val id = s.scrollTarget ?: return@LaunchedEffect
        withFrameNanos { }
        val i = replyItems(s).indexOfFirst { it.key == "t-$id" || it.key == "a-$id" }
        if (i < 0) return@LaunchedEffect
        s.scrollTarget = null
        list.animateScrollToItem(i)
    }
    val scope = rememberCoroutineScope()
    val quick = rememberReplyQuickReaction()
    // A photo or name tapped: the sheet goes down, then that person's profile (or the channel's page
    // for a Creator reply) opens.
    val peopleNav = com.fariin.app.ui.people.LocalPeopleNav.current
    val channelNav = LocalChannelNav.current
    val author: (PostReply) -> Unit = { r ->
        s.openAuthor(r) { uid ->
            PostQuotesSheet.open.value = null
            PostQuotesSheet.startOff = false
            if (uid == null) channelNav.page(s.cid) else peopleNav.contact(uid)
        }
    }
    Column(modifier) {
    if (s.loaded && s.threads.isNotEmpty() || s.sort != PostReplyRules.Sort.TOP) SortSwitch(s)
    Box(Modifier.fillMaxWidth().weight(1f)) {
        when {
            s.failed && s.threads.isEmpty() -> Column(Modifier.fillMaxWidth().padding(top = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Couldn't load replies", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.label)
                Text("Check your connection and try again.", fontSize = 14.sp, color = c.secondary, modifier = Modifier.padding(top = 4.dp))
                TextButton(onClick = { s.retry() }) { Text("Try Again", color = c.label) }
            }
            // The loading shapes only over an empty sheet, never over replies already drawn (iOS).
            !s.loaded && s.threads.isEmpty() -> RepliesSkeleton()
            // The owner's comment icon (the Reply bar's) and small words (iOS, owner 2026-11-07).
            s.threads.isEmpty() -> Column(Modifier.fillMaxSize().padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(androidx.compose.ui.res.painterResource(com.fariin.app.R.drawable.ic_reply_comment), null,
                    tint = c.secondary, modifier = Modifier.padding(bottom = 12.dp).size(40.dp))
                Text(if (s.off) "Replies are off" else "No replies yet", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.label)
                Text(if (s.off) "The channel turned off replies for this post." else "Be the first to reply.", fontSize = 13.sp,
                    color = c.secondary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
            }
            else -> LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(top = 4.dp, bottom = 8.dp)) {
                items(items.size, key = { items[it].key }) { i ->
                    when (val row = items[i]) {
                        ReplyItem.Older -> {
                            // The next page comes in by itself when the end comes into view.
                            LaunchedEffect(s.olderCursor) { s.loadOlder() }
                            SmallLink(if (s.loadingOlder) "Loading…" else "Show more replies",
                                Modifier.fillMaxWidth().padding(vertical = 4.dp), center = true) { s.loadOlder() }
                        }
                        is ReplyItem.MoreCount -> SmallLink(PostReplyRules.moreAnswers(row.n),
                            Modifier.fillMaxWidth().threadLineThrough().padding(start = 108.dp, bottom = 8.dp)) { s.showMoreAnswers(row.rid) }
                        ReplyItem.PinLabel -> Row(Modifier.padding(start = 16.dp + 40.dp + 24.dp, top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(ImageVector.vectorResource(R.drawable.ic_pin_menu), null, tint = c.secondary, modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Pinned", fontSize = 12.sp, color = c.secondary)
                        }
                        is ReplyItem.Thread -> ReplyRow(s, row.r, small = false, now, quick,
                            line = if (maxOf(row.r.answers, s.answers[row.r.id].orEmpty().size) > 0) LINE_DOWN else LINE_NONE) { author(row.r) }
                        is ReplyItem.Answer -> Box(Modifier.padding(start = AnswerIndent)) { ReplyRow(s, row.r, small = true, now, quick, line = LINE_BRANCH) { author(row.r) } }
                        is ReplyItem.Toggle -> ThreadToggle(
                            if (row.open && row.rid in s.answersLoading && s.answers[row.rid].isNullOrEmpty()) "Loading…" else PostReplyRules.threadToggle(row.n, row.open),
                        ) { s.toggleThread(row.rid) }
                        is ReplyItem.More -> SmallLink("Show more replies", Modifier.fillMaxWidth().threadLineThrough().padding(start = 108.dp, bottom = 8.dp)) {
                            scope.launch { s.loadAnswers(row.rid, more = true) }
                        }
                    }
                }
            }
        }
        if (s.unseenNew > 0) {
            Row(Modifier.align(Alignment.TopCenter).padding(top = 8.dp).clip(RoundedCornerShape(50)).background(c.card)
                .clickable {
                    val to = s.firstNewId
                    s.unseenNew = 0
                    scope.launch {
                        val i = to?.let { id -> items.indexOfFirst { it.key == "t-$id" } } ?: -1
                        list.animateScrollToItem(if (i >= 0) i else 0)
                    }
                }
                .padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(ImageVector.vectorResource(R.drawable.ic_sf_chevron_up), null, tint = c.label, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(PostReplyRules.newPill(s.unseenNew), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.label)
            }
        }
    }
    }
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

/** "Remove this person?": delete this reply and ban, or ban and delete every reply they wrote here. */
@Composable
private fun BanSheet(onDismiss: () -> Unit, onBan: (Boolean) -> Unit) {
    val c = Fariin.colors
    com.fariin.app.ui.components.GlassAlert(
        onDismissRequest = onDismiss,
        title = { Text("Remove this person?") },
        text = { Text("They leave the channel and can't come back or reply.") },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = { onDismiss(); onBan(false) }) { Text("Delete and Ban", color = c.red) }
                TextButton(onClick = { onDismiss(); onBan(true) }) { Text("Ban and Delete All Their Replies", color = c.red) }
                TextButton(onClick = onDismiss) { Text("Cancel", color = c.accent, fontWeight = FontWeight.SemiBold) }
            }
        },
        containerColor = c.card,
    )
}

/**
 * THREAD LINES (iOS, owner 2026-11-07): from the reply's photo a solid light-grey line runs down and
 * bends into the "View N replies" line (and into each open answer's photo) with iOS's continuous
 * corner of [ThreadLineRadius], never a sharp elbow. Each row draws its own piece behind itself, at the list's
 * x of the reply photo's centre ([ThreadLineX]), so no extra layouts.
 */
private const val LINE_NONE = 0
private const val LINE_DOWN = 1
private const val LINE_BRANCH = 2
private val ThreadLineX = 16.dp + 20.dp
private val ThreadLineRadius = 12.dp   // iOS 2026-11-07: 12, was 16
private val ThreadLineWidth = 1.5.dp
/** Where an answer row starts in the list (its Box padding); the line sits left of it. */
private val AnswerIndent = 46.dp

@Composable
private fun threadLineColor(): Color = if (Fariin.colors.dark) Color(0xFF48484A) else Color(0xFFC7C7CC)

/** A vertical from the top at [x] that turns, with the quarter circle, into a horizontal at [y] up to [toX]. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.elbow(color: Color, x: Float, y: Float, toX: Float) {
    val r = ThreadLineRadius.toPx()
    val w = ThreadLineWidth.toPx()
    // iOS's continuous corner (iOS 2026-11-08), not a quarter circle: it starts 1.53 radii before
    // the corner and eases in over three cubics (the rounded-rect curve's own control points).
    val a = 1.52866483f * r
    if (y - a > 0f) drawLine(color, androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, y - a), w)
    val p = androidx.compose.ui.graphics.Path().apply {
        moveTo(x, y - a)
        cubicTo(x, y - 1.08849299f * r, x + 0.02225930f * r, y - 0.86840701f * r, x + 0.07491100f * r, y - 0.63149399f * r)
        cubicTo(x + 0.16905201f * r, y - 0.37282401f * r, x + 0.37282401f * r, y - 0.16905201f * r, x + 0.63149399f * r, y - 0.07491100f * r)
        cubicTo(x + 0.86840701f * r, y - 0.02225930f * r, x + 1.08849299f * r, y, x + a, y)
        lineTo(toX, y)
    }
    drawPath(p, color, style = androidx.compose.ui.graphics.drawscope.Stroke(w, cap = androidx.compose.ui.graphics.StrokeCap.Round))
}

/** The straight piece through a whole row ("Show more replies" between the answers and the toggle). */
@Composable
private fun Modifier.threadLineThrough(): Modifier {
    val color = threadLineColor()
    return drawBehind {
        drawLine(color, androidx.compose.ui.geometry.Offset(ThreadLineX.toPx(), 0f),
            androidx.compose.ui.geometry.Offset(ThreadLineX.toPx(), size.height), ThreadLineWidth.toPx())
    }
}

/** "View 3 replies" / "Hide replies": the thread line bends into it, then the words. */
@Composable
private fun ThreadToggle(label: String, onTap: () -> Unit) {
    val c = Fariin.colors
    val color = threadLineColor()
    val bottom = 10.dp
    // The bend may start in the row above (its top is above this row), so the curve is never cut.
    Box(Modifier.fillMaxWidth().drawBehind {
        elbow(color, ThreadLineX.toPx(), (size.height - bottom.toPx()) / 2f, (62.dp + 2.dp + 20.dp).toPx())
    }) {
        Row(Modifier.padding(start = 62.dp, bottom = bottom).clip(RoundedCornerShape(6.dp)).clickable(onClick = onTap)
            .padding(vertical = 4.dp, horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(28.dp))
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.secondary)
        }
    }
}

/**
 * One reply (iOS, the owner's reference 2026-11-07): name, seal and age over the bubble, the photo
 * beside the bubble's top (40 dp, an answer 28; it stays up top on a long reply), the words in a soft rounded bubble, and under it
 * the chat's reaction pills and Reply. Double-tap the bubble = the Quick Reaction ([quick]); a
 * long press = the chat's six-emoji bar (a pick closes it) over the menu the server's rights allow
 * (PostReplyRules.actions). Mine on its way reads "Sending…"; failed, "Not sent" with Retry.
 */
@Composable
private fun ReplyRow(s: PostRepliesState, r: PostReply, small: Boolean, now: Long, quick: String,
                     /** The thread line: [LINE_DOWN] from my photo to the row's end, [LINE_BRANCH] (an answer) through the row and into my photo. */
                     line: Int = LINE_NONE, onAuthor: () -> Unit) {
    val c = Fariin.colors
    val clipboard = LocalClipboardManager.current
    var menu by remember { mutableStateOf(false) }
    val dim = r.held || r.send != PostReply.Send.SENT
    val side = if (small) 28.dp else 40.dp
    // The row's and the photo's places, read while drawing (no state, no recomposition).
    val places = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(2) }
    val lineColor = threadLineColor()
    val lineModifier = if (line == LINE_NONE) Modifier else Modifier
        .onPlaced { places[0] = it }
        .drawBehind {
            val row = places[0] ?: return@drawBehind
            val photo = places[1]?.takeIf { it.isAttached && row.isAttached } ?: return@drawBehind
            val at = row.localPositionOf(photo, androidx.compose.ui.geometry.Offset.Zero)
            val w = ThreadLineWidth.toPx()
            if (line == LINE_DOWN) {
                val x = at.x + photo.size.width / 2f
                // Starts 2 under the photo's edge (iOS), so no gap shows between the photo and the line.
                drawLine(lineColor, androidx.compose.ui.geometry.Offset(x, at.y + photo.size.height - 2.dp.toPx()), androidx.compose.ui.geometry.Offset(x, size.height), w)
            } else {
                // An answer: the parent's line goes on through the row; a bend leaves it into my photo.
                val x = (ThreadLineX - AnswerIndent).toPx()
                drawLine(lineColor, androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, size.height), w)
                elbow(lineColor, x, at.y + photo.size.height / 2f, at.x)
            }
        }
    // A jump to it or a push's comment lights the row for a moment.
    val lit by androidx.compose.animation.animateColorAsState(
        when {
            s.flashId == r.id -> c.accent.copy(alpha = 0.14f)
            // The reply being answered stays lit while the field is open (iOS secondarySystemFill).
            s.answering?.id == r.id -> c.label.copy(alpha = 0.045f)
            else -> Color.Transparent
        },
        androidx.compose.animation.core.tween(300), label = "replyFlash")
    // The shade hugs the row: from just above the name to just below the Reply line, not the row's
    // full padding (owner's Pixel 2026-11-09: "our grey looks bigger").
    Column(Modifier.fillMaxWidth().then(lineModifier).drawBehind {
        val inset = 5.dp.toPx()
        drawRect(lit, topLeft = androidx.compose.ui.geometry.Offset(0f, inset),
            size = androidx.compose.ui.geometry.Size(size.width, (size.height - 2 * inset).coerceAtLeast(0f)))
    }.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.padding(start = side + 24.dp, end = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(r.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.label, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
                    .clickable(enabled = !r.mine, interactionSource = null, indication = null, onClick = onAuthor))
            if (r.verified) {
                Spacer(Modifier.width(4.dp))
                Icon(ImageVector.vectorResource(R.drawable.ic_sf_checkmark_seal_fill), contentDescription = "Verified", tint = c.blue, modifier = Modifier.size(12.dp))
            }
            // The channel's owner, writing as the channel (owner 2026-11-07).
            if (r.creator) {
                Spacer(Modifier.width(5.dp))
                Text("Creator", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = c.secondary, maxLines = 1,
                    modifier = Modifier.clip(CircleShape).background(c.label.copy(alpha = 0.08f)).padding(horizontal = 6.dp, vertical = 1.5.dp))
            }
            // An answer to an answer: "Jayz ▸ Salmo" in the name line (iOS 785831e73); a tap on the
            // name brings that reply into view and lights it.
            r.to?.let { to ->
                Spacer(Modifier.width(4.dp))
                Text("\u25B8", fontSize = 8.sp, color = c.secondary)
                Spacer(Modifier.width(4.dp))
                Text(to.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.secondary, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
                        .clickable(interactionSource = null, indication = null, onClickLabel = "Show the reply") { s.jumpTo(r) })
            }
            Spacer(Modifier.width(4.dp))
            Text(PostReplyRules.ageLine(r, now), fontSize = 13.sp, color = c.secondary, maxLines = 1)
        }
        // The photo level with the bubble's top, not its middle (iOS): a long reply keeps it up top.
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.Top) {
            // A Creator reply wears the channel's squircle photo.
            Avatar(r.photoUrl.ifEmpty { null }, side, squircle = r.creator,
                kind = if (r.creator) AvatarKind.CHANNEL else AvatarKind.PERSON,
                modifier = Modifier.onPlaced { places[1] = it }
                    .clickable(enabled = !r.mine, interactionSource = null, indication = null, onClick = onAuthor))
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f, fill = false).padding(end = 24.dp)) {
                // The words only: whom it answers is in the name line now (iOS 785831e73).
                val words = remember(r.text) { androidx.compose.ui.text.AnnotatedString(r.text) }
                Text(words, fontSize = if (small) 15.sp else 17.sp, color = c.label,
                    modifier = Modifier.alpha(if (dim) 0.55f else 1f).clip(RoundedCornerShape(20.dp)).background(c.fieldFill)
                        .pointerInput(r.id, r.send) {
                            detectTapGestures(
                                onDoubleTap = { if (r.send == PostReply.Send.SENT && !s.off) s.quickReact(r, quick) },
                                onLongPress = { menu = true },
                            )
                        }
                        .padding(horizontal = 14.dp, vertical = 10.dp))
                GlassDropdown(menu, onDismissRequest = { menu = false }, shape = com.fariin.app.ui.components.FariinMenu.shape,
                    border = com.fariin.app.ui.components.FariinMenu.border, containerColor = c.card) {
                    // The chat's react bar (iOS): the same six, in the same order, mine marked; a pick
                    // closes the menu, the same one again takes mine back.
                    if (r.send == PostReply.Send.SENT && !s.off) {
                        Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            for (e in PostReplyRules.REACT_BAR) {
                                Box(Modifier.size(40.dp).clip(CircleShape)
                                    .background(if (e == r.my) c.secondary.copy(alpha = 0.22f) else Color.Transparent)
                                    .clickable { menu = false; s.react(r, e) }
                                    .semantics { contentDescription = if (e == r.my) "$e, yours" else e },
                                    contentAlignment = Alignment.Center) {
                                    Text(e, fontSize = 24.sp)
                                }
                            }
                        }
                        androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = c.separator)
                    }
                    for (a in PostReplyRules.actions(r, s.off, s.may, s.pinnedId,
                        editable = PostReplyRules.canEdit(r, s.off, System.currentTimeMillis()))) {
                        val (label, red) = when (a) {
                            PostReplyRules.Action.REPLY -> "Reply" to false
                            PostReplyRules.Action.COPY -> "Copy" to false
                            PostReplyRules.Action.EDIT -> "Edit" to false
                            PostReplyRules.Action.REPORT -> "Report" to false
                            PostReplyRules.Action.DELETE -> "Delete" to true
                            PostReplyRules.Action.BAN -> "Delete and Ban" to true
                            PostReplyRules.Action.RETRY -> "Retry" to false
                            PostReplyRules.Action.PIN -> "Pin" to false
                            PostReplyRules.Action.UNPIN -> "Unpin" to false
                        }
                        DropdownMenuItem(text = { Text(label, color = if (red) c.red else c.label) }, onClick = {
                            menu = false
                            when (a) {
                                PostReplyRules.Action.REPLY -> s.startAnswer(r)
                                PostReplyRules.Action.COPY -> clipboard.setText(AnnotatedString(r.text))
                                PostReplyRules.Action.EDIT -> s.startEdit(r)
                                PostReplyRules.Action.REPORT -> s.report(r)
                                PostReplyRules.Action.DELETE -> if (r.send == PostReply.Send.FAILED) s.discard(r) else s.delete(r)
                                PostReplyRules.Action.BAN -> s.banning = r
                                PostReplyRules.Action.RETRY -> s.retry(r)
                                PostReplyRules.Action.PIN -> s.pin(r, on = true)
                                PostReplyRules.Action.UNPIN -> s.pin(r, on = false)
                            }
                        })
                    }
                }
            }
        }
        Row(Modifier.padding(start = side + 18.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                when (r.send) {
                    PostReply.Send.SENDING -> Text("Sending…", fontSize = 12.sp, color = c.secondary, modifier = Modifier.padding(vertical = 4.dp))
                    PostReply.Send.FAILED -> {
                        Text("Not sent", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = c.red)
                        Spacer(Modifier.width(12.dp))
                        FooterWord("Retry", bold = true) { s.retry(r) }
                        Spacer(Modifier.width(12.dp))
                        FooterWord("Delete") { s.discard(r) }
                    }
                    PostReply.Send.SENT -> FooterFlow {
                        // THE CHAT'S REACTION PILL, NOT A HEART BUTTON (iOS, owner 2026-11-07: "make it like
                        // how bubble react works in my app"): no empty outline; a pill per emoji with its
                        // count, mine the dark filled one; a tap on a pill takes mine back or adds it.
                        // At most 4, most used first, mine always among them (PostReplyRules.shownPills).
                        PostReplyRules.shownPills(r.rx, r.my).forEach { x ->
                            val mine = x.emoji == r.my
                            Row(Modifier.padding(end = 6.dp).clip(CircleShape)
                                .background(if (mine) c.label else c.label.copy(alpha = 0.08f))
                                .clickable(enabled = !s.off) { s.react(r, x.emoji) }
                                .semantics { contentDescription = "${x.emoji} ${x.count}" + if (mine) ", yours" else "" }
                                .padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(x.emoji, fontSize = 14.sp)
                                Spacer(Modifier.width(4.dp))
                                Text(com.fariin.app.channel.ChannelRules.compactCount(x.count), fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold, color = if (mine) c.bg else c.label)
                            }
                        }
                        // Reply on the pills' line only when it fits there; else on its own line under them.
                        if (!s.off) FooterWord("Reply", bold = true) { s.startAnswer(r) }
                        if (r.held) Text("Hidden after reports", fontSize = 11.sp, color = c.secondary,
                            modifier = Modifier.padding(start = 6.dp))
                    }
                }
        }
    }
}

/**
 * A reply's footer: its pieces left to right, each moved to the next line when it does not fit
 * (the pills, then Reply). Measured and placed in one pass, no extra frame. Each line's pieces are
 * centred on that line's height.
 */
@Composable
private fun FooterFlow(content: @Composable () -> Unit) {
    androidx.compose.ui.layout.Layout(content) { ms, cons ->
        val loose = cons.copy(minWidth = 0, minHeight = 0)
        val ps = ms.map { it.measure(loose) }
        val rows = mutableListOf(mutableListOf<androidx.compose.ui.layout.Placeable>())
        var x = 0
        for (p in ps) {
            if (rows.last().isNotEmpty() && x + p.width > cons.maxWidth) { rows += mutableListOf<androidx.compose.ui.layout.Placeable>(); x = 0 }
            rows.last() += p; x += p.width
        }
        val heights = rows.map { r -> r.maxOfOrNull { it.height } ?: 0 }
        val w = rows.maxOf { r -> r.sumOf { it.width } }.coerceAtMost(cons.maxWidth)
        layout(w, heights.sum()) {
            var y = 0
            rows.forEachIndexed { i, r ->
                var px = 0
                r.forEach { p -> p.place(px, y + (heights[i] - p.height) / 2); px += p.width }
                y += heights[i]
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
                Box(Modifier.padding(top = 20.dp).size(40.dp).clip(CircleShape).background(grey))
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
            s.editing?.let {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Editing", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = c.accent, maxLines = 1,
                        modifier = Modifier.weight(1f))
                    IconButton({ s.cancelEdit() }, Modifier.size(28.dp)) {
                        Icon(ImageVector.vectorResource(R.drawable.ic_sf_xmark), "Stop editing", tint = c.secondary, modifier = Modifier.size(14.dp))
                    }
                }
            }
            s.answering?.let { a ->
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    // iOS: "Replying to yourself" on my own reply, then " · Cancel" as a word, no x
                    // (owner's Pixel 2026-11-09: it said "Replying to Roodo" on his own reply).
                    val cancelKeyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
                    val cancelFocus = androidx.compose.ui.platform.LocalFocusManager.current
                    Text(androidx.compose.ui.text.buildAnnotatedString {
                        append("Replying to ")
                        pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.SemiBold))
                        append(if (a.mine) "yourself" else a.name)
                        pop()
                    }, fontSize = 13.sp, color = c.label, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Text(" · ", fontSize = 13.sp, color = c.secondary)
                    // Cancel clears the reply and closes the keyboard (iOS).
                    Text("Cancel", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.secondary,
                        modifier = Modifier.clickable(onClickLabel = "Stop replying") {
                            s.answering = null; cancelFocus.clearFocus(); cancelKeyboard?.hide()
                        }.padding(vertical = 6.dp))
                }
            }
            Row(Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.fieldFill)
                .padding(start = 18.dp, end = 5.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                // Replying to someone else: their name in blue before the cursor (drawn, never sent; iOS).
                val token = s.answering?.takeIf { !it.mine }?.name
                if (token != null) Text(token, fontSize = 16.sp, color = c.blue, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 6.dp).widthIn(max = 160.dp))
                Box(Modifier.weight(1f).padding(vertical = 10.dp), contentAlignment = Alignment.CenterStart) {
                    if (s.draft.isEmpty() && token == null) Text("Add a reply…", fontSize = 16.sp, color = c.tertiary)
                    BasicTextField(
                        value = s.draft,
                        onValueChange = { s.draft = it.take(PostReplyRules.MAX_LENGTH); s.problem = null },
                        textStyle = TextStyle(fontSize = 16.sp, color = c.label),
                        cursorBrush = SolidColor(c.accent),
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth().focusRequester(s.field),
                    )
                }
                // The chat's send button (iOS 2026-11-07): a white up arrow on a 40x34 blue pill, inside the
                // field on the right, there only while the field has words; it scales and fades in.
                val sendKeyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
                val sendFocus = androidx.compose.ui.platform.LocalFocusManager.current
                val can = PostReplyRules.cleanDraft(s.draft) != null
                androidx.compose.animation.AnimatedVisibility(can,
                    enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(150)) +
                        androidx.compose.animation.scaleIn(androidx.compose.animation.core.tween(150), initialScale = 0.7f),
                    exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)) +
                        androidx.compose.animation.scaleOut(androidx.compose.animation.core.tween(120), targetScale = 0.7f)) {
                    Box(Modifier.padding(start = 6.dp, end = 0.dp).size(width = 40.dp, height = 34.dp).clip(RoundedCornerShape(17.dp))
                        .background(c.blue).clickable(onClickLabel = "Send") {
                            s.send()
                            // The keyboard closes after Send (iOS 7c20b4077); the sheet shows the new reply.
                            sendKeyboard?.hide(); sendFocus.clearFocus()
                        }, contentAlignment = Alignment.Center) {
                        Icon(ImageVector.vectorResource(R.drawable.ic_sf_arrow_up), contentDescription = "Send", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
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
