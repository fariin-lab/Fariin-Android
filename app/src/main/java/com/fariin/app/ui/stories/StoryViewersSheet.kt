package com.fariin.app.ui.stories

import com.fariin.app.ui.components.GlassDropdown
import com.fariin.app.R
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.fariin.app.data.Story
import com.fariin.app.data.StoryPrivacyStore
import com.fariin.app.data.StoryRules
import com.fariin.app.data.StoryService
import com.fariin.app.data.StoryViewSummary
import com.fariin.app.data.StoryViewer
import com.fariin.app.ui.components.Avatar
import com.fariin.app.ui.components.SkeletonBlock
import com.fariin.app.ui.components.SkeletonCircle
import com.fariin.app.ui.theme.Fariin
import com.fariin.app.ui.theme.LocalFariinColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The viewers sheet's one number and the row's position (iOS `viewersProgress` + the row scroll):
 * [p] is 0 closed to 1 open and drives the sheet, the live story's shrink and the row together, on
 * one critically damped spring (iOS SheetProgressAnimator, stiffness 340). [row] is where the row
 * stands, in cards. [id] is the story whose viewers are listed; null when the sheet is gone.
 */
@Stable
internal class ViewersSheetState(private val scope: CoroutineScope) {
    val p = Animatable(0f)
    val row = Animatable(0f)
    /** 0 = the resting height, 1 = pulled up near full screen (iOS large detent, owner 2026-11-09). */
    val up = Animatable(0f)
    var id by mutableStateOf<String?>(null)
    private var job: Job? = null
    private val springSpec = spring<Float>(dampingRatio = 1f, stiffness = StoryMotion.SHEET_STIFFNESS)

    fun open(storyId: String, index: Int, velocity: Float = 0f) {
        id = storyId
        job?.cancel()
        job = scope.launch { row.snapTo(index.toFloat()); p.animateTo(1f, springSpec, initialVelocity = velocity) }
    }

    /** Back to fully open after a pull that did not close it. */
    fun settleOpen(velocity: Float) {
        job?.cancel()
        job = scope.launch { p.animateTo(1f, springSpec, initialVelocity = velocity) }
    }

    fun upTo(v: Float) { scope.launch { up.snapTo(v.coerceIn(0f, 1f)) } }
    /** Let go between the two heights: a flick decides, else the nearer one. [velocity] in heights/s, + = up. */
    fun settleUp(velocity: Float) {
        val target = when { velocity > 1.2f -> 1f; velocity < -1.2f -> 0f; up.value > 0.5f -> 1f; else -> 0f }
        scope.launch { up.animateTo(target, springSpec, initialVelocity = velocity) }
    }

    fun close(velocity: Float = 0f) {
        if (id == null) return
        job?.cancel()
        scope.launch { up.snapTo(0f) }
        job = scope.launch { p.animateTo(0f, springSpec, initialVelocity = velocity); id = null }
    }

    /**
     * The sheet folds in the caller's own coroutine (iOS closeViewers before a close): the card grows
     * back into the full story on the sheet's spring, and the caller goes on once it is down.
     */
    suspend fun collapse(velocity: Float = 0f) {
        if (id == null) return
        job?.cancel()
        try { p.animateTo(0f, springSpec, initialVelocity = velocity) } finally { id = null }
    }

    /** A finger is on the sheet or the row: it owns them, any spring stops. */
    fun hold() { job?.cancel(); scope.launch { p.stop(); row.stop(); up.stop() } }
    fun dragTo(v: Float) { scope.launch { p.snapTo(v.coerceIn(0f, 1f)) } }
    fun rowTo(v: Float) { scope.launch { row.snapTo(v) } }

    /** The row lands on [index]; once still, that story becomes the live one (iOS onIndexSettled). */
    fun pageTo(index: Int, stories: List<Story>, velocity: Float = 0f, onLanded: (String) -> Unit) {
        job?.cancel()
        job = scope.launch {
            row.animateTo(index.toFloat(), springSpec, initialVelocity = velocity)
            stories.getOrNull(index)?.id?.let { if (it != id) { id = it; onLanded(it) } }
        }
    }

    private var swipeFrom = 0f
    /** A sideways drag on the row or the sheet: it starts where the row stands now. */
    fun swipeBegin() { hold(); swipeFrom = row.value }
    fun swipeTo(dx: Float, fullDist: Float, last: Int) = rowTo(rubberBand(swipeFrom - dx / fullDist, last))
    /**
     * Let go: ONE card per swipe, as iOS pages it (owner 2026-11-12: "it runs too much, I just want
     * the next one"). A flick or a drag past a third of a card goes to the neighbour on that side,
     * never further; a short slow drag goes back. The spring starts no faster than two cards a second.
     */
    fun swipeEnd(vx: Float, fullDist: Float, stories: List<Story>, onLanded: (String) -> Unit) {
        val cardsPerSec = -vx / fullDist
        val from = kotlin.math.round(swipeFrom).toInt()
        val moved = row.value - from
        val step = when {
            cardsPerSec > 0.8f || moved > 0.33f -> 1
            cardsPerSec < -0.8f || moved < -0.33f -> -1
            else -> 0
        }
        val target = (from + step).coerceIn(0, maxOf(0, stories.lastIndex))
        pageTo(target, stories, cardsPerSec.coerceIn(-2f, 2f), onLanded)
    }

    /** Open fraction, kept inside 0..1 for drawing (a release velocity can carry a spring past). */
    val fraction: Float get() = p.value.coerceIn(0f, 1f)
}

/** The tall height, a little room left at the top (iOS large detent). */
private const val TALL_FRACTION = 0.92f

// iOS StoryViewersPanelView, always dark (overrideUserInterfaceStyle = .dark).
private val SheetFill = Color(0xFF1A1A1A)        // white 0.10
private val SearchFill = Color(0xFF2B2B2B)       // white 0.17
private val Placeholder = Color(0x99EBEBF5)      // dark placeholderText
private val HeartRed = Color(0xFFFF4561)         // (1.0, 0.27, 0.38)

/**
 * The row of my stories above the open sheet (iOS MyStoriesCarousel over StoryRowUIKit): every
 * card but the live one, drawn from its thumbnail, and the view count under every card, the live
 * one included. The live story itself is the pager, placed by the same geometry.
 *
 * Also owns every touch above the sheet: a sideways drag across the cards pages the row, a pull
 * down closes the sheet with the finger, a tap on the centre card or the empty space closes it,
 * a tap on a side card brings it to the centre.
 */
@Composable
internal fun ViewersCarousel(
    sheet: ViewersSheetState,
    stories: List<Story>,
    summaries: Map<String, StoryViewSummary>,
    topInset: Float,
    /** The room my own bar takes under the story card. */
    footer: Float,
    /** The live page has this story's picture up, so its card here can step aside. */
    pageShows: (String) -> Boolean,
    onLanded: (String) -> Unit,
) {
    val d = LocalDensity.current.density
    var size by remember { mutableStateOf(IntSize.Zero) }
    val live = stories.indexOfFirst { it.id == sheet.id }
    if (live < 0) return
    fun geo(f: Float) = StorySheetGeometry(size.width.toFloat(), size.height.toFloat(), topInset, d, f, footer)
    val slop = LocalViewConfiguration.current.touchSlop
    Box(
        Modifier.fillMaxSize().onSizeChanged { size = it }.pointerInput(stories, live) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (size == IntSize.Zero) return@awaitEachGesture
                val g = geo(1f)
                val inBand = down.position.y in g.slotTop..(g.slotTop + g.slotH)
                val tracker = VelocityTracker()
                tracker.addPosition(down.uptimeMillis, down.position)
                sheet.hold()
                val startP = sheet.fraction
                var dx = 0f; var dy = 0f
                var mode = 0   // 1 = paging the row, 2 = pulling the sheet
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    tracker.addPosition(ch.uptimeMillis, ch.position)
                    val m = ch.positionChange()
                    dx += m.x; dy += m.y
                    if (mode == 0) {
                        if (inBand && stories.size > 1 && abs(dx) > slop && abs(dx) > abs(dy)) { mode = 1; sheet.swipeBegin() }
                        else if (abs(dy) > slop) mode = 2
                    }
                    if (mode == 1) { ch.consume(); sheet.swipeTo(dx, g.fullDist, stories.lastIndex) }
                    if (mode == 2) { ch.consume(); sheet.dragTo(startP - dy / g.sheetH) }
                    if (!ch.pressed) break
                }
                val v = tracker.calculateVelocity()
                when (mode) {
                    1 -> sheet.swipeEnd(v.x, g.fullDist, stories, onLanded)
                    2 -> {
                        val dropped = (startP - sheet.fraction) * g.sheetH
                        if (StorySheetGeometry.shouldClose(dropped, v.y, g.fullH, d)) sheet.close(-v.y / g.sheetH)
                        else sheet.settleOpen(-v.y / g.sheetH)
                    }
                    else -> {
                        // Which card is under the finger: the centre one closes, a side one comes to the centre.
                        val hit = if (!inBand) -1 else stories.indices.firstOrNull { i ->
                            val pl = g.placement(i - sheet.row.value)
                            abs(down.position.x - pl.cx) <= pl.w / 2f && abs(down.position.y - pl.cy) <= pl.h / 2f
                        } ?: -1
                        if (hit >= 0 && hit != live) sheet.pageTo(hit, stories, 0f, onLanded) else sheet.close()
                    }
                }
            }
        },
    ) {
        if (size == IntSize.Zero) return@Box
        val g1 = geo(1f)
        val slotW = g1.slotW; val slotH = g1.slotH
        stories.forEachIndexed { i, s ->
            // One box the size of the seated card, moved and scaled in the draw pass only.
            Box(
                Modifier.layout { m, _ ->
                    val p = m.measure(Constraints.fixed(maxOf(1, slotW.toInt()), maxOf(1, slotH.toInt())))
                    layout(size.width, size.height) { p.place(((size.width - slotW) / 2f).toInt(), (g1.centerY - slotH / 2f).toInt()) }
                }.graphicsLayer {
                    val g = geo(sheet.fraction)
                    val pl = g.placement(i - sheet.row.value)
                    val s2 = pl.w / slotW
                    transformOrigin = TransformOrigin.Center
                    scaleX = s2; scaleY = s2
                    translationX = pl.cx - size.width / 2f
                    translationY = pl.cy - g1.centerY
                },
            ) {
                // Every card stays composed, the live one too, so its picture is already in when the
                // row moves on and it becomes a side card again (removing it made it reload: a flash).
                // The live one is see-through once the page under it shows the same story.
                run {
                    Box(
                        Modifier.fillMaxSize().graphicsLayer {
                            val pl = geo(sheet.fraction).placement(i - sheet.row.value)
                            alpha = if (i == live && pageShows(s.id)) 0f else 1f
                            clip = true
                            shape = RoundedCornerShape(pl.corner / maxOf(0.001f, pl.w / slotW))
                        }.background(Color.Black),
                    ) {
                        val url = s.listThumb
                        if (url.startsWith("demo:")) Box(Modifier.fillMaxSize().background(
                            runCatching { Color(android.graphics.Color.parseColor(url.removePrefix("demo:"))) }.getOrDefault(Color.Gray)))
                        // Drawn exactly as the story page draws it (StoryPhoto): a side card that was
                        // cropped turned into the page's picture the moment it became the live one
                        // (owner 2026-11-03, a wide photo went from a tall card to a thin strip).
                        else StoryPhoto(url)
                        Box(Modifier.fillMaxSize().graphicsLayer { alpha = geo(sheet.fraction).placement(i - sheet.row.value).dim }.background(Color.Black))
                    }
                }
            }
            // The count rides under the card's bottom edge from the first frame and fades in (iOS); it
            // is moved with the card but never scaled with it (scaled, it showed as a big ghost low on
            // the screen while the card was still near full size).
            CardCount(
                summaries[s.id],
                Modifier.layout { m, _ ->
                    val p = m.measure(Constraints())
                    layout(size.width, size.height) { p.place(((size.width - p.width) / 2f).toInt(), 0) }
                }.graphicsLayer {
                    val pl = geo(sheet.fraction).placement(i - sheet.row.value)
                    translationX = pl.cx - size.width / 2f
                    translationY = pl.cy + pl.h / 2f + StorySheetGeometry.COUNT_GAP * d
                    alpha = geo(sheet.fraction).countAlpha(i - sheet.row.value) * sheet.fraction
                },
            )
        }
    }
}

/** My stories paged by a sideways swipe that starts on the sheet (the row's own swipe is in [ViewersCarousel]). */
internal class RowSwipe(
    val sheet: ViewersSheetState,
    val stories: List<Story>,
    val fullDist: Float,
    val onLanded: (String) -> Unit,
)

/**
 * Takes a sideways drag before anything under it sees it, so a swipe over a viewer's row pages the
 * stories instead of ending as a tap that opens that viewer. Up and down stay the list's and the sheet's.
 */
private fun Modifier.rowSwipe(swipe: RowSwipe?): Modifier = if (swipe == null || swipe.stories.size < 2) this else pointerInput(swipe) {
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        var dx = 0f; var dy = 0f
        var paging = false
        while (true) {
            val ev = awaitPointerEvent(PointerEventPass.Initial)
            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
            tracker.addPosition(ch.uptimeMillis, ch.position)
            val m = ch.positionChange()
            dx += m.x; dy += m.y
            if (!paging) {
                if (abs(dx) > slop && abs(dx) > abs(dy) * 1.2f) { paging = true; swipe.sheet.swipeBegin() }
                else if (abs(dy) > slop) break
            }
            if (paging) { ch.consume(); swipe.sheet.swipeTo(dx, swipe.fullDist, swipe.stories.lastIndex) }
            if (!ch.pressed) break
        }
        if (paging) swipe.sheet.swipeEnd(tracker.calculateVelocity().x, swipe.fullDist, swipe.stories, swipe.onLanded)
    }
}

private fun rubberBand(pos: Float, last: Int): Float {
    // Past either end the row gives way less and less (UIScrollView's 0.55 rubber band, in cards).
    fun band(x: Float) = (1f - 1f / (x * 0.55f + 1f))
    return when {
        pos < 0f -> -band(-pos)
        pos > last -> last + band(pos - last)
        else -> pos
    }
}

/** Eye and views, then the heart and likes when there are any: 15 bold white, 7 apart (iOS StoryRowCardView.count). */
@Composable
private fun CardCount(summary: StoryViewSummary?, modifier: Modifier) {
    val views = summary?.count ?: 0
    val likes = summary?.reactionCount ?: 0
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Icon(ImageVector.vectorResource(com.fariin.app.R.drawable.ic_views), contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        Text(compact(views), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        if (likes > 0) {
            Spacer(Modifier.width(4.dp))
            Icon(ImageVector.vectorResource(R.drawable.ic_sf_heart_fill), contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            Text(compact(likes), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** 999, 1.2K, 3.4M (iOS BubbleText.compactViews). */
private fun compact(n: Int): String = when {
    n < 1000 -> "$n"
    n < 1_000_000 -> String.format(java.util.Locale.US, "%.1fK", n / 1000.0).replace(".0K", "K")
    else -> String.format(java.util.Locale.US, "%.1fM", n / 1_000_000.0).replace(".0M", "M")
}

/**
 * My story's viewers (iOS StoryViewersPanelView in StoryViewersSheetView): 60% of the screen, 32
 * corners, always dark. The grabber, the audience tab (and Friends beside it for an Everyone
 * story), the grey Search capsule, then the people: reactions first, newest next. A tap on a row
 * opens the profile; the "..." holds Send message, Hide my stories from, and Block.
 *
 * Pulling the header, or the list once it is at its top, moves the sheet with the finger and the
 * story card with it; it closes on the iOS rule ([StorySheetGeometry.shouldClose]).
 */
@Composable
internal fun ViewersSheet(
    sheet: ViewersSheetState,
    story: Story,
    me: String,
    service: StoryService,
    who: (String) -> Pair<String, String?>,
    friendUids: Set<String>,
    receipts: Boolean,
    customName: String?,
    onOpenPerson: (String) -> Unit,
    onSendMessage: (StoryViewer) -> Unit,
    onBlock: (StoryViewer) -> Unit,
    rowSwipe: RowSwipe? = null,
    /** The chat list: a group story's Replies tab reads its group from it (names, who may delete). */
    convs: List<com.fariin.app.data.Conversation> = emptyList(),
    /** The story's view count when known: the loading rows are that many (never six for one view). */
    knownViews: Int? = null,
) {
    val storyId = story.id
    // What is already known (fetched while the story was on screen) shows from the first frame.
    var people by remember(storyId) { mutableStateOf(StoryViewersCache.get(storyId)) }
    var failed by remember(storyId) { mutableStateOf(false) }
    var query by rememberSaveable(storyId) { mutableStateOf("") }
    var tab by rememberSaveable(storyId) { mutableStateOf(0) }
    val loadScope = rememberCoroutineScope()
    DisposableEffect(storyId) {
        var job: Job? = null
        fun load() {
            job?.cancel()
            job = loadScope.launch {
                var got = service.fetchViewers(storyId, who)
                while (got == null) { failed = true; delay(3000); got = service.fetchViewers(storyId, who) }
                failed = false; people = got; StoryViewersCache.put(storyId, got)
            }
        }
        load()
        val reg = service.watchViews(storyId) { load() }
        onDispose { reg.remove(); job?.cancel() }
    }
    val d = LocalDensity.current.density
    var sheetH by remember { mutableFloatStateOf(1f) }
    var screenH by remember { mutableFloatStateOf(1f) }
    val focus = LocalFocusManager.current
    // A group story's list is the poster's to read only: "All Viewers", then Replies (iOS group
    // stories v2), the story's shared thread, when replies are on and it is not a channel's.
    val readOnly = story.groupId != null
    val repliesTab = readOnly && com.fariin.app.data.GroupStoryRules.hasRepliesTab(story, convs)
    val first = if (readOnly) "All Viewers" else customName ?: StoryRules.audienceTitle(story)
    val bothTabs = if (readOnly) repliesTab else story.audienceLabel == "everyone" && !story.oneTime
    if (!bothTabs && tab != 0) tab = 0
    var replies by remember(storyId) { mutableStateOf<List<com.fariin.app.data.GroupStoryReply>>(emptyList()) }
    var userPickedTab by remember(storyId) { mutableStateOf(false) }
    var autoPicked by remember(storyId) { mutableStateOf(false) }
    DisposableEffect(storyId, repliesTab) {
        val reg = if (repliesTab) com.fariin.app.data.GroupStoryReplies.listen(storyId) { replies = it } else null
        onDispose { reg?.remove() }
    }
    // Opens on Replies when there are any, once, and never over a tab picked by hand (iOS).
    if (repliesTab && replies.isNotEmpty() && !autoPicked && !userPickedTab && tab == 0) { autoPicked = true; tab = 1 }
    val showingReplies = repliesTab && tab == 1

    // The list hands a pull to the sheet once it is at its top, and takes back an upward one first.
    // How far the sheet grows from its resting height to its tall one, in px.
    val growPx = maxOf(1f, screenH * (TALL_FRACTION - StorySheetGeometry.HEIGHT_FRACTION))
    val listPull = remember(sheet, growPx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < 0f && sheet.fraction < 1f) {
                    sheet.dragTo(sheet.fraction - available.y / sheetH)
                    return Offset(0f, available.y)
                }
                // Pushing up: the sheet grows to its tall height before the list scrolls.
                if (available.y < 0f && source == NestedScrollSource.UserInput && sheet.up.value < 1f) {
                    sheet.upTo(sheet.up.value - available.y / growPx)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y > 0f && source == NestedScrollSource.UserInput) {
                    sheet.hold()
                    // Pulling down at the list's top: back to the resting height first, then down.
                    if (sheet.up.value > 0f) sheet.upTo(sheet.up.value - available.y / growPx)
                    else sheet.dragTo(sheet.fraction - available.y / sheetH)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (sheet.up.value > 0f && sheet.up.value < 1f) { sheet.settleUp(-available.y / growPx); return available }
                if (sheet.fraction >= 1f) return Velocity.Zero
                val dropped = (1f - sheet.fraction) * sheetH
                if (StorySheetGeometry.shouldClose(dropped, available.y, screenH, d)) sheet.close(-available.y / sheetH)
                else sheet.settleOpen(-available.y / sheetH)
                return available
            }
        }
    }

    // One pull for the whole sheet (owner 2026-11-12: "only the top lets me swipe it down"): the
    // header and the empty or loading space below it; a list hands its pull over through listPull.
    val sheetDrag = Modifier.pointerInput(sheet, growPx) {
                        var start = 1f
                        val tracker = VelocityTracker()
                        detectVerticalDragGestures(
                            onDragStart = { sheet.hold(); start = sheet.fraction; tracker.resetTracking() },
                            onVerticalDrag = { ch, dy ->
                                tracker.addPosition(ch.uptimeMillis, ch.position)
                                // Up past fully open, or down while tall: the height moves, not the sheet.
                                if (sheet.fraction >= 1f && (dy < 0f || sheet.up.value > 0f)) sheet.upTo(sheet.up.value - dy / growPx)
                                else sheet.dragTo(sheet.fraction - dy / sheetH)
                            },
                            onDragEnd = {
                                val vDown = tracker.calculateVelocity().y
                                if (sheet.fraction >= 1f && (sheet.up.value > 0f || vDown < 0f)) { sheet.settleUp(-vDown / growPx); return@detectVerticalDragGestures }
                                val dropped = (start - sheet.fraction) * sheetH
                                if (StorySheetGeometry.shouldClose(dropped, vDown, screenH, d)) sheet.close(-vDown / sheetH)
                                else sheet.settleOpen(-vDown / sheetH)
                            },
                            onDragCancel = { sheet.settleOpen(0f) },
                        )
                    }

    // The sheet's own palette: the skeleton reads `dark`, and nothing here follows the app's light mode.
    CompositionLocalProvider(LocalFariinColors provides Fariin.colors.copy(dark = true, label = Color.White, bg = SheetFill)) {
        Box(Modifier.fillMaxSize().onSizeChanged { screenH = maxOf(1f, it.height.toFloat()) }) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .fillMaxHeight(StorySheetGeometry.HEIGHT_FRACTION + (TALL_FRACTION - StorySheetGeometry.HEIGHT_FRACTION) * sheet.up.value.coerceIn(0f, 1f))
                    .onSizeChanged { sheetH = maxOf(1f, it.height.toFloat()) }
                    .graphicsLayer { translationY = (1f - sheet.fraction) * sheetH }
                    .rowSwipe(rowSwipe)
                    .clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)).background(SheetFill)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { focus.clearFocus() }
                    .navigationBarsPadding(),
            ) {
                // Grabber, tabs and search: a pull anywhere on them moves the sheet.
                Column(
                    Modifier.fillMaxWidth().then(sheetDrag)) {
                    Box(Modifier.padding(top = 8.dp).align(Alignment.CenterHorizontally).size(38.dp, 5.dp)
                        .clip(RoundedCornerShape(2.5.dp)).background(Color.White.copy(alpha = 0.28f)))
                    Row(Modifier.padding(start = 18.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        SheetTab(first, selected = tab == 0, enabled = bothTabs) { userPickedTab = true; tab = 0 }
                        if (bothTabs) SheetTab(if (readOnly) "Replies" else "Friends", selected = tab == 1, enabled = true) { userPickedTab = true; tab = 1 }
                    }
                    Row(
                        Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).fillMaxWidth().height(38.dp)
                            .clip(RoundedCornerShape(19.dp)).background(SearchFill).padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(ImageVector.vectorResource(R.drawable.ic_search_ios), contentDescription = null, tint = Placeholder, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) Text("Search", color = Placeholder, fontSize = 17.sp, maxLines = 1)
                            BasicTextField(query, { query = it }, singleLine = true,
                                textStyle = TextStyle(color = Color.White, fontSize = 17.sp), cursorBrush = SolidColor(Color.White),
                                // The keyboard says Search, and return only lowers it: the query stays.
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                                modifier = Modifier.fillMaxWidth())
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
                val list = people
                val hidden by StoryPrivacyStore.hidden.collectAsState()
                when {
                    showingReplies -> RepliesList(story, me, replies, query, who, convs, Modifier.weight(1f).nestedScroll(listPull),
                        onOpenPerson = onOpenPerson,
                        onDelete = { r ->
                            // Off the list at once; the listener confirms it, or brings it back on a refusal.
                            replies = replies.filter { it.id != r.id }
                            loadScope.launch { runCatching { com.fariin.app.data.GroupStoryReplies.delete(storyId, r.id) } }
                        })
                    list == null && !failed && knownViews != 0 -> Box(Modifier.fillMaxWidth().weight(1f).then(sheetDrag)) { ViewersSkeleton(knownViews ?: 6) }
                    list == null || list.isEmpty() -> Box(Modifier.fillMaxWidth().weight(1f).then(sheetDrag).padding(horizontal = 24.dp)) {
                        Text(
                            when {
                                failed -> buildAnnotatedString { append("Couldn't load viewers. Trying again…") }
                                !receipts -> buildAnnotatedString {
                                    pushStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp))
                                    append("Views not available\n"); pop()
                                    append("Can't see views because you disabled Read Receipts in Settings > Stories")
                                }
                                else -> buildAnnotatedString { append("No views yet") }
                            },
                            color = Color.White.copy(alpha = 0.55f), fontSize = 15.sp, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                        )
                    }
                    else -> {
                        val rows = StorySheetRows.order(list, query, if (tab == 1) friendUids else null)
                        LazyColumn(Modifier.fillMaxWidth().weight(1f).nestedScroll(listPull)) {
                            items(rows.size, key = { rows[it].uid }) { i ->
                                val v = rows[i]
                                ViewerRow(v, readOnly, isHidden = v.uid in hidden,
                                    onOpen = { onOpenPerson(v.uid) },
                                    onSendMessage = { onSendMessage(v) },
                                    onToggleHidden = { StoryPrivacyStore.setHidden(setOf(v.uid), v.uid !in hidden) },
                                    onBlock = { onBlock(v) })
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A tab title, 15 semibold white when picked, regular at half white when not, with its 2dp underline. */
@Composable
private fun SheetTab(title: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.width(androidx.compose.foundation.layout.IntrinsicSize.Max).then(
            if (enabled) Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClick() } else Modifier,
        ),
    ) {
        Box(Modifier.height(26.dp), contentAlignment = Alignment.CenterStart) {
            Text(title, color = if (selected) Color.White else Color.White.copy(alpha = 0.5f), fontSize = 15.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(2.dp).clip(RoundedCornerShape(1.dp)).background(if (selected) Color.White else Color.Transparent))
    }
}

/** One viewer (iOS StoryViewerRowContent): 46dp face with the reaction disc, name, "6m ago", the "...". */
@Composable
private fun ViewerRow(
    v: StoryViewer, readOnly: Boolean, isHidden: Boolean,
    onOpen: () -> Unit, onSendMessage: () -> Unit, onToggleHidden: () -> Unit, onBlock: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onOpen() }
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Avatar(v.photoUrl, 46.dp)
            val r = v.reaction
            if (!r.isNullOrEmpty()) {
                Box(
                    Modifier.align(Alignment.BottomEnd).offset(x = 5.dp, y = 4.dp).size(22.dp).clip(CircleShape)
                        .background(Color(0xE63A3A3C)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (r == "❤️") Icon(ImageVector.vectorResource(R.drawable.ic_sf_heart_fill), contentDescription = "Liked", tint = HeartRed, modifier = Modifier.size(12.dp))
                    else Text(r, fontSize = 11.sp)
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(v.name, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(viewedAgo(v.viewedAtMs), color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        if (!readOnly) {
            Box {
                Box(Modifier.size(38.dp).clickable { menu = true }, contentAlignment = Alignment.Center) {
                    Icon(ImageVector.vectorResource(R.drawable.ic_more_ios), contentDescription = "More", tint = Color.White.copy(alpha = 0.55f))
                }
                // Dark like the sheet it opens from (iOS draws the row's menu in the dark style).
                androidx.compose.material3.MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme(
                    surfaceContainer = Color(0xFF2C2C2E), onSurface = Color.White, onSurfaceVariant = Color.White)) {
                GlassDropdown(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Send message") },
                        leadingIcon = { Icon(ImageVector.vectorResource(R.drawable.ic_chat), contentDescription = null) },
                        onClick = { menu = false; onSendMessage() })
                    DropdownMenuItem(
                        text = { Text(if (isHidden) "Don't hide my stories from ${v.name}" else "Hide my stories from ${v.name}") },
                        leadingIcon = { Icon(if (isHidden) ImageVector.vectorResource(com.fariin.app.R.drawable.ic_views) else ImageVector.vectorResource(com.fariin.app.R.drawable.ic_views_off), contentDescription = null) },
                        onClick = { menu = false; onToggleHidden() })
                    // Destructive and last.
                    DropdownMenuItem(text = { Text("Block ${v.name}", color = Fariin.colors.red) },
                        leadingIcon = { Icon(ImageVector.vectorResource(com.fariin.app.R.drawable.ic_block), contentDescription = null, tint = Fariin.colors.red) },
                        onClick = { menu = false; onBlock() })
                }
                }
            }
        }
    }
}

/**
 * A group story's Replies tab (iOS StoryViewersPanelView replies): oldest at the top, landing on
 * the newest; search matches the writer's name or the words; "No replies yet" when empty.
 */
@Composable
private fun RepliesList(
    story: Story, me: String, replies: List<com.fariin.app.data.GroupStoryReply>, query: String,
    who: (String) -> Pair<String, String?>, convs: List<com.fariin.app.data.Conversation>, modifier: Modifier,
    onOpenPerson: (String) -> Unit, onDelete: (com.fariin.app.data.GroupStoryReply) -> Unit,
) {
    val gid = story.groupId.orEmpty()
    val group = convs.firstOrNull { it.id == gid }
    // iOS replyPerson: me, then the group's own member maps, then a 1:1 chat.
    val person: (String) -> Pair<String, String?> = { uid ->
        val groupName = group?.names?.get(uid)
        when {
            uid == me -> "You" to group?.photos?.get(me)?.ifEmpty { null }
            !groupName.isNullOrEmpty() -> groupName to group?.photos?.get(uid)?.ifEmpty { null }
            else -> who(uid)
        }
    }
    val q = query.trim()
    val rows = if (q.isEmpty()) replies
        else replies.filter { person(it.uid).first.contains(q, ignoreCase = true) || it.text.contains(q, ignoreCase = true) }
    if (rows.isEmpty()) {
        Box(modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            Text("No replies yet", color = Color.White.copy(alpha = 0.55f), fontSize = 15.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 40.dp))
        }
        return
    }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    androidx.compose.runtime.LaunchedEffect(rows.size) { listState.scrollToItem(rows.size - 1) }
    LazyColumn(modifier.fillMaxWidth(), state = listState) {
        items(rows.size, key = { rows[it].id }) { i ->
            val r = rows[i]
            val (name, photo) = person(r.uid)
            ReplyRow(r, name, photo, canDelete = com.fariin.app.data.GroupStoryRules.canDelete(r, gid, me, convs),
                onOpenProfile = { if (r.uid != me) onOpenPerson(r.uid) }, onDelete = { onDelete(r) })
        }
    }
}

/**
 * One reply as a comment (iOS GroupStoryReplyRowContent): face, bold name and "5m", the words or
 * "Reacted to the story" with the emoji large at the end. Long press: Copy for words, Delete for
 * the writer and the group's owner or admins.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ReplyRow(
    r: com.fariin.app.data.GroupStoryReply, name: String, photoUrl: String?, canDelete: Boolean,
    onOpenProfile: () -> Unit, onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val hasMenu = !r.isReaction || canDelete
    Box {
        Row(
            Modifier.fillMaxWidth()
                .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                    onLongClick = if (hasMenu) ({ menu = true }) else null, onClick = {})
                .padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onOpenProfile() }) {
                Avatar(photoUrl, 40.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onOpenProfile() },
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(6.dp))
                    Text(replyAgo(r.createdAtMs), color = Color.White.copy(alpha = 0.45f), fontSize = 12.sp)
                }
                if (r.isReaction) Text("Reacted to the story", color = Color.White.copy(alpha = 0.7f), fontSize = 17.sp)
                else Text(r.text, color = Color.White, fontSize = 17.sp)
            }
            if (r.isReaction) {
                Spacer(Modifier.width(8.dp))
                Text(r.emoji, fontSize = 30.sp)
            }
        }
        androidx.compose.material3.MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme(
            surfaceContainer = Color(0xFF2C2C2E), onSurface = Color.White, onSurfaceVariant = Color.White)) {
            GlassDropdown(expanded = menu, onDismissRequest = { menu = false }) {
                if (!r.isReaction) DropdownMenuItem(text = { Text("Copy") },
                    leadingIcon = { Icon(ImageVector.vectorResource(R.drawable.ic_sf_doc_on_doc), contentDescription = null) },
                    onClick = { menu = false; clipboard.setText(androidx.compose.ui.text.AnnotatedString(r.text)) })
                if (canDelete) DropdownMenuItem(text = { Text("Delete", color = Fariin.colors.red) },
                    leadingIcon = { Icon(ImageVector.vectorResource(R.drawable.ic_sf_trash), contentDescription = null, tint = Fariin.colors.red) },
                    onClick = { menu = false; onDelete() })
            }
        }
    }
}

/** Number and one letter, as the story header writes its age (iOS GroupStoryReplyRowContent.ago). */
internal fun replyAgo(atMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val s = maxOf(0L, (nowMs - atMs) / 1000)
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m"
        s < 86_400 -> "${s / 3600}h"
        else -> "${s / 86_400}d"
    }
}

/**
 * The viewers already fetched for each of my stories (iOS shows the real rows from the first frame):
 * filled by the story page while it is on screen and by the sheet itself.
 */
object StoryViewersCache {
    private val map = java.util.concurrent.ConcurrentHashMap<String, List<StoryViewer>>()
    fun get(id: String): List<StoryViewer>? = map[id]
    fun put(id: String, list: List<StoryViewer>) { map[id] = list }
}

/** The list while it loads (iOS StoryViewerSkeleton): one row per known view (1 view, 1 row), at most six. */
@Composable
private fun ViewersSkeleton(rows: Int = 6) {
    Column(Modifier.fillMaxWidth()) {
        listOf(130, 96, 150, 110, 84, 140).take(rows.coerceIn(1, 6)).forEach { w ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                SkeletonCircle(46.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    SkeletonBlock(w.dp, 13.dp)
                    SkeletonBlock(64.dp, 10.dp)
                }
                SkeletonBlock(22.dp, 6.dp, radius = 3.dp)
            }
        }
    }
}

/** The list's order and filters (iOS StoryViewersPanelView.applyFilter), kept pure for the tests. */
internal object StorySheetRows {
    /** Friends tab narrows to [friends]; Search to names containing it; then reactions first, newest next. */
    fun order(viewers: List<StoryViewer>, query: String, friends: Set<String>?): List<StoryViewer> {
        val q = query.trim()
        return viewers.asSequence()
            .filter { friends == null || it.uid in friends }
            .filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) }
            .sortedWith(compareByDescending<StoryViewer> { !it.reaction.isNullOrEmpty() }.thenByDescending { it.viewedAtMs })
            .toList()
    }
}
