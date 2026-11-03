package com.fariin.app.ui.stories

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.Job
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.fariin.app.data.Conversation
import com.fariin.app.data.CryptoHub
import com.fariin.app.data.FirestorePeople
import com.fariin.app.data.Message
import com.fariin.app.data.Story
import com.fariin.app.data.StoryGroup
import com.fariin.app.data.StoryRules
import com.fariin.app.data.StoryService
import com.fariin.app.data.StoryViewSummary
import com.fariin.app.data.StoryViewer
import com.fariin.app.data.ThreadRepository
import com.fariin.app.ui.components.Avatar
import com.fariin.app.ui.people.ContactInfoScreen
import com.fariin.app.ui.thread.LocalOpenChat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Sends that must outlive the viewer (a reply typed just before closing it). */
private val storyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * The full-screen story viewer (iOS StoryViewer over the StoryUI package): one page per ring,
 * the next ring turning in like a cube; on a page the progress bars, a tap on the left third to go
 * back and anywhere else to go on, hold to pause, swipe down to close, and on my own story swipe
 * up (or tap the bar) for the viewers. Another person's story has the reply field and the heart.
 *
 * The owner bar's numbers are kept per story id and drawn from the id on screen, so coming back
 * to my story always shows that story's own count (the iOS StoryUI `lastChangedItem` fix).
 */
@Composable
fun StoryViewerDialog(
    me: String,
    groups: List<StoryGroup>,
    startGroup: Int,
    conversations: List<Conversation>,
    onClose: () -> Unit,
) {
    Dialog(
        onDismissRequest = onClose,
        // Back is the viewer's own: it runs the same shrink into the card as the swipe down.
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnBackPress = false),
    ) {
        // Block screenshots (iOS captureProtected): while such a story is on screen this window is
        // FLAG_SECURE, so a screenshot or a screen recording of it comes out black.
        val window = (androidx.compose.ui.platform.LocalView.current.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
        // The window itself neither dims nor fades: the zoom paints its own wall, on the card's fraction.
        DisposableEffect(window) {
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window?.setWindowAnimations(0)
            onDispose { }
        }
        var secure by remember { mutableStateOf(false) }
        DisposableEffect(window, secure) {
            if (secure) window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            else window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            onDispose { }
        }
        StoryViewer(me, groups, startGroup, conversations, onClose, onShown = { secure = it.captureProtected })
    }
}

@Composable
fun StoryViewer(
    me: String,
    initialGroups: List<StoryGroup>,
    startGroup: Int,
    conversations: List<Conversation>,
    onClose: () -> Unit,
    onShown: (Story) -> Unit = {},
) {
    val service = remember(me) { StoryService(me) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val groups = remember { mutableStateListOf<StoryGroup>().apply { addAll(initialGroups) } }
    val pager = rememberPagerState(initialPage = startGroup.coerceIn(0, maxOf(0, initialGroups.size - 1))) { groups.size }
    val convs by rememberUpdatedState(conversations)
    val who: (String) -> Pair<String, String?> = { StoryService.who(it, convs, me) }
    // The people I may reply to: my accepted 1:1 chats (iOS StoryContact.isFriend).
    val contacts = remember(conversations) { StoryRules.contacts(me, conversations) }
    // The react bar's emoji flying up after a tap (iOS EmojiReactionView).
    var flying by remember { mutableStateOf<Pair<String, Long>?>(null) }
    // Seen watermarks as this viewer moves them, so a ring is not written twice.
    val watermarks = remember { mutableStateMapOf<String, Long>().apply { initialGroups.forEach { g -> g.lastViewedAtMs?.let { put(g.key, it) } } } }
    val marked = remember { mutableSetOf<String>() }
    var receipts by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { receipts = service.receiptsOn() }

    // Owner-bar numbers by story id (never by "the item I last heard about").
    val summaries = remember { mutableStateMapOf<String, StoryViewSummary>() }
    var sheetFor by rememberSaveable { mutableStateOf<String?>(null) }
    var profileUid by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<Story?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    // iOS StoryNotifyMenuAction: the bar that confirms Get / Mute notifications, 3 s, a newer tap keeps its own.
    var notifyBar by remember { mutableStateOf<Triple<Boolean, String, Int>?>(null) }
    LaunchedEffect(me) { com.fariin.app.data.StoryNotify.load(me) }
    LaunchedEffect(notifyBar) { val b = notifyBar ?: return@LaunchedEffect; delay(3000); if (notifyBar == b) notifyBar = null }
    var replyFocused by remember { mutableStateOf(false) }

    LaunchedEffect(toast) { if (toast != null) { delay(1500); toast = null } }

    BackHandler(enabled = profileUid != null || sheetFor != null) {
        if (profileUid != null) profileUid = null else sheetFor = null
    }

    // ---- The zoom: out of the tapped card, back into it (iOS StoryZoomPresenter + the hero drag) ----
    val density = LocalDensity.current.density
    val hostView = LocalView.current
    var size by remember { mutableStateOf(IntSize.Zero) }
    val hero = remember { HeroState() }
    val heroJob = remember { arrayOfNulls<Job>(1) }
    fun currentKey() = groups.getOrNull(pager.currentPage)?.key
    val cardRadius = 14f * density
    // The hole follows whoever is on screen, so the landing fills the slot it came out of.
    val shownKey = currentKey()
    LaunchedEffect(shownKey) { if (!hero.closed) StoryOrigins.hiddenKey = shownKey }
    DisposableEffect(Unit) { onDispose { StoryOrigins.hiddenKey = null } }

    fun anchorFor(key: String?): Rect? {
        val r = StoryOrigins.rects[key ?: return null] ?: return null
        if (r.width < 1f || r.height < 1f) return null
        val loc = IntArray(2)
        hostView.getLocationOnScreen(loc)
        return r.translate(-loc[0].toFloat(), -loc[1].toFloat())
    }
    fun rest() = Offset(size.width / 2f, size.height / 2f)

    /** One flight on the iOS animator: critically damped, mass 1, every property on the same t. */
    fun runHero(
        toF: Float, toC: Offset, toA: Float, velocity: Float, stiffness: Float, settle: Float,
        cover: ((Float) -> Float)? = null, crop: ((Float) -> Float)? = null, dimFloor: ((Float) -> Float)? = null,
        extra: ((Float) -> Unit)? = null, done: () -> Unit,
    ) {
        heroJob[0]?.cancel()
        val fromF = hero.f; val fromC = hero.center; val fromA = hero.alpha
        heroJob[0] = scope.launch {
            Animatable(0f).animateTo(1f, spring(dampingRatio = 1f, stiffness = stiffness, visibilityThreshold = settle), initialVelocity = velocity) {
                // A real release velocity can carry the spring a hair past 1; never publish outside it.
                val t = value.coerceIn(0f, 1f)
                hero.f = fromF + (toF - fromF) * t
                hero.center = androidx.compose.ui.geometry.lerp(fromC, toC, t)
                hero.alpha = fromA + (toA - fromA) * t
                cover?.let { hero.cover = it(t) }
                crop?.let { hero.crop = it(t) }
                dimFloor?.let { hero.dimFloor = it(t) }
                extra?.invoke(t)
            }
            done()
        }
    }

    // THE OPEN: the first painted frame is already the size of the card it came out of.
    // Keyed on nothing: a window that re-lays itself out (the screenshot block going on) must not
    // restart this half way and leave the card parked on its slot.
    LaunchedEffect(Unit) {
        androidx.compose.runtime.snapshotFlow { size }.first { it != IntSize.Zero }
        if (hero.staged) return@LaunchedEffect
        hero.staged = true
        val a = anchorFor(currentKey())
        hero.center = rest()
        if (a == null) {
            // Nothing to fly from (Posted Stories): show the story, faded in like the pager's backstop.
            hero.rootAlpha = 0f
            hero.visible = true
            Animatable(0f).animateTo(1f, tween(180)) { hero.rootAlpha = value }
            return@LaunchedEffect
        }
        hero.anchor = a
        hero.radius = StoryOrigins.radii[currentKey()] ?: cardRadius
        hero.coverUrl = groups.getOrNull(pager.currentPage)?.stories?.lastOrNull()?.listThumb
        hero.f = 1f; hero.center = a.center; hero.crop = 1f; hero.cover = 1f; hero.dimFloor = 0f
        hero.exiting = false; hero.live = true
        hero.visible = true
        // Seated first, flown after: the first frames go to building the page, not to the spring's clock.
        withFrameMillis { }; withFrameMillis { }
        runHero(0f, rest(), 1f, 0f, StoryMotion.OPEN_STIFFNESS, StoryMotion.OPEN_SETTLE,
            cover = StoryMotion::coverOut, crop = StoryMotion::coverOut, dimFloor = { it }) {
            hero.live = false; hero.cover = 0f; hero.crop = 0f
        }
    }

    fun land() {
        if (hero.closed) return
        hero.closed = true
        // The card is home: put the row's own card back, and lift the viewer off it a frame later.
        StoryOrigins.hiddenKey = null
        scope.launch { withFrameMillis { }; onClose() }
    }

    /** Every way out: the swipe, the X, back, the last story ending. Same landing for all of them. */
    fun close(vy: Float = 0f) {
        if (hero.committing || hero.closed) return
        if (!hero.staged || size == IntSize.Zero) { land(); return }
        heroJob[0]?.cancel()
        hero.committing = true
        if (!hero.live) { hero.center = rest(); hero.f = 0f }
        hero.live = true
        hero.exiting = true
        val a = anchorFor(currentKey())
        if (a == null) {
            // No card to fly to (scrolled away, or opened from Posted Stories): drift down by its own
            // height, shrink to 0.72 and fade, on the 0.25s critically damped spring (iOS MediaDismiss).
            val h = size.height * (1f + (((hero.anchor?.width ?: 0f) / size.width) - 1f) * hero.f)
            runHero(hero.f, hero.center + Offset(0f, h), 0f, 0f, StoryMotion.DRIFT_STIFFNESS, 0.004f,
                dimFloor = { 1f - it }, extra = { t -> hero.shrink = 1f - (1f - StoryMotion.DRIFT_SCALE) * t; hero.rootAlpha = 1f - t }) { land() }
        } else {
            hero.anchor = a
            hero.radius = StoryOrigins.radii[currentKey()] ?: cardRadius
            hero.coverUrl = groups.getOrNull(pager.currentPage)?.stories?.lastOrNull()?.listThumb
            val remaining = maxOf(1f, (a.center - hero.center).getDistance())
            runHero(1f, a.center, 1f, minOf(6f, maxOf(0f, vy) / remaining), StoryMotion.CLOSE_STIFFNESS,
                StoryMotion.settle(remaining, density),
                cover = StoryMotion::coverIn, crop = StoryMotion::coverIn, dimFloor = { 1f - it }) { land() }
        }
        // A flight that never lands must not trap anyone in the viewer: 1.2s is four times the flight.
        scope.launch { delay(1200); land() }
    }

    fun dragBegin() {
        if (hero.committing || hero.closed || size == IntSize.Zero) return
        heroJob[0]?.cancel()
        hero.anchor = anchorFor(currentKey())
        hero.radius = StoryOrigins.radii[currentKey()] ?: cardRadius
        hero.live = true
        hero.f = 0f; hero.center = rest(); hero.alpha = 1f
        // Nothing cross-fades under the finger, nothing is cropped, the floor is held.
        hero.cover = 0f; hero.crop = 0f; hero.dimFloor = 1f
        // A pull is an exit: the reply bar goes now.
        hero.exiting = true
    }
    /** Down only, measured from the touch: 1:1 while the card shrinks, then a short rubber band. */
    fun dragTo(dy: Float) {
        if (!hero.live || hero.committing || size == IntSize.Zero) return
        val w = size.width.toFloat(); val h = size.height.toFloat()
        val ty = maxOf(0f, dy)
        val ratio = (hero.anchor?.width ?: 0f) / w
        val ceiling = StoryMotion.dragCeiling(ratio)
        val span = StoryMotion.DRAG_SPAN * density
        hero.f = minOf(ceiling, ty / span)
        var y = h / 2f + StoryMotion.dragTravel(ty, span * ceiling, StoryMotion.DRAG_TAIL * density)
        // And the bottom edge stays on the display.
        val scaleNow = 1f + (ratio - 1f) * hero.f
        y = minOf(y, maxOf(h / 2f, h - h * scaleNow / 2f - 10f * density))
        hero.center = Offset(w / 2f, y)
        hero.alpha = 1f
    }
    fun springBack(vy: Float) {
        if (!hero.live || hero.committing) return
        // An arrival: the reply bar fades back in over the last 18% with the card.
        hero.exiting = false
        val home = rest()
        val remaining = maxOf(1f, (home - hero.center).getDistance())
        runHero(0f, home, 1f, minOf(6f, maxOf(0f, -vy) / remaining), StoryMotion.CLOSE_STIFFNESS,
            StoryMotion.settle(remaining, density), cover = { 0f }, crop = { 0f }) { hero.live = false }
    }
    fun dragEnd(dy: Float, vy: Float) {
        if (!hero.live || hero.committing) return
        if (dy > StoryMotion.COMMIT_DISTANCE * density || vy > StoryMotion.COMMIT_VELOCITY * density) close(vy) else springBack(vy)
    }

    // System back closes with the same shrink; the predictive gesture pulls the card as a short swipe would.
    PredictiveBackHandler(enabled = profileUid == null && sheetFor == null && !hero.committing) { events ->
        var began = false
        try {
            events.collect { e ->
                if (!began) { dragBegin(); began = true }
                dragTo(e.progress * StoryMotion.COMMIT_DISTANCE * density)
            }
            close()
        } catch (c: kotlin.coroutines.cancellation.CancellationException) {
            if (began) springBack(0f)
            throw c
        }
    }

    val turnSpec = tween<Float>(StoryMotion.TURN_MS, easing = StoryMotion.TURN_EASING)
    fun nextGroup() {
        if (pager.currentPage + 1 < groups.size) scope.launch { pager.animateScrollToPage(pager.currentPage + 1, animationSpec = turnSpec) } else close()
    }
    fun previousGroup() {
        if (pager.currentPage > 0) scope.launch { pager.animateScrollToPage(pager.currentPage - 1, animationSpec = turnSpec) }
    }

    /** Where the card is on screen right now, in this window's pixels. */
    fun visibleRect(): Rect {
        val w = size.width.toFloat(); val h = size.height.toFloat()
        val a = hero.anchor
        val aw = a?.width ?: 0f; val ah = a?.height ?: 0f
        // The crop converges the story to the slot's own shape on a flight; a drag keeps it whole.
        val th = h * aw / w + (ah - h * aw / w) * hero.crop
        val vw = (w + (aw - w) * hero.f) * hero.shrink
        val vh = (h + (th - h) * hero.f) * hero.shrink
        // At rest the card is the window, whatever the window has done since (insets, rotation).
        val c = if (hero.live) hero.center else Offset(w / 2f, h / 2f)
        return Rect(c.x - vw / 2f, c.y - vh / 2f, c.x + vw / 2f, c.y + vh / 2f)
    }

    Box(
        Modifier.fillMaxSize().onSizeChanged { size = it }
            .graphicsLayer { alpha = if (hero.visible) hero.rootAlpha else 0f },
    ) {
        // The wall behind the card: solid at rest, giving way as the card leaves, on the same fraction.
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = StoryMotion.dim(hero.f, hero.dimFloor) }.background(Color.Black))
        HorizontalPager(
            pager, beyondViewportPageCount = 1, key = { groups.getOrNull(it)?.key ?: it },
            // Person to person: commit at 30% of a face, settle over 0.4s on the iOS curve.
            flingBehavior = PagerDefaults.flingBehavior(pager, snapAnimationSpec = turnSpec, snapPositionalThreshold = StoryMotion.TURN_THRESHOLD),
            modifier = Modifier.fillMaxSize().graphicsLayer {
                if (size == IntSize.Zero) return@graphicsLayer
                val r = visibleRect()
                val w = size.width.toFloat(); val h = size.height.toFloat()
                val s = maxOf(r.width / w, r.height / h)
                scaleX = s; scaleY = s
                translationX = r.center.x - w / 2f
                translationY = r.center.y - h / 2f
                alpha = hero.alpha
                val moving = hero.f > 0.0001f || hero.shrink < 1f
                clip = moving
                if (moving && s > 0f) shape = CardShape(r.width / s, r.height / s, hero.radius * hero.f / s)
            },
        ) { page ->
            val g = groups.getOrNull(page) ?: return@HorizontalPager
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    // The cube turn between rings (iOS StoryPager.cubeTransform): every face at the
                    // same frame, only the turn separates them, so the pager's own slide is undone.
                    val off = (pager.currentPage - page) + pager.currentPageOffsetFraction
                    val t = -off
                    if (abs(t) >= 1f) { alpha = 0f; return@graphicsLayer }
                    alpha = 1f
                    val face = StoryMotion.face(t, size.width / density)
                    cameraDistance = StoryMotion.CUBE_PERSPECTIVE * density / 72f
                    transformOrigin = TransformOrigin.Center
                    rotationY = face.rotationDeg
                    scaleX = face.scale; scaleY = face.scale
                    translationX = off * size.width + face.shiftPt * density
                }.drawWithContent {
                    drawContent()
                    // Each face darkens as it turns away: black 1.0, 0.8, 0.5 across it at |t| x 1.3.
                    val t = -((pager.currentPage - page) + pager.currentPageOffsetFraction)
                    if (abs(t) > 0.001f) {
                        val stops = listOf(Color.Black, Color.Black.copy(alpha = 0.8f), Color.Black.copy(alpha = 0.5f))
                        val brush = Brush.horizontalGradient(if (t < 0f) stops else stops.reversed())
                        drawRect(brush, alpha = minOf(1f, abs(t) * StoryMotion.CUBE_TINT))
                    }
                },
            ) {
                StoryPage(
                    me = me, group = g, active = page == pager.currentPage && !pager.isScrollInProgress,
                    paused = sheetFor != null || profileUid != null || confirmDelete != null || replyFocused || hero.live,
                    chrome = { if (hero.exiting) 0f else StoryMotion.chrome(hero.f) },
                    summaries = summaries, service = service, who = who,
                    onSeen = { s ->
                        if (s.authorUid != me && marked.add(s.id)) {
                            val wm = watermarks[s.ringKey]
                            if (StoryRules.advancesWatermark(wm, s.createdAtMs)) watermarks[s.ringKey] = s.createdAtMs + 1
                            storyScope.launch { service.markViewed(s, wm, receipts) }
                        }
                    },
                    onEnd = { nextGroup() }, onBeforeStart = { previousGroup() },
                    onClose = { close() },
                    onDrag = { dy ->
                        if (!hero.live && dy > 0f) dragBegin()
                        dragTo(dy)
                    },
                    onDragEnd = { dy, vy -> dragEnd(dy, vy) },
                    onOpenViewers = { id -> sheetFor = id },
                    onDelete = { confirmDelete = it },
                    onReplyFocus = { replyFocused = it },
                    // Typed text is a reply; a react bar emoji is the same message marked isReaction
                    // (iOS StoryViewer.handle: ReplyRef isStatus + storyThumb, isReaction for the bar).
                    onReply = { s, text, reaction ->
                        toast = "Sent"
                        if (reaction) flying = text to System.nanoTime()
                        storyScope.launch {
                            val ok = runCatching {
                                val cid = listOf(me, s.authorUid).sorted().joinToString("_")
                                ThreadRepository(cid, me, CryptoHub.crypto, storyScope).sendStoryReply(
                                    text, Message.ReplyRef(s.id, s.authorUid, "", isStatus = true, storyThumb = s.previewUrl, isReaction = reaction),
                                )
                            }.isSuccess
                            if (!ok) toast = "Couldn't send. Check your connection and try again."
                        }
                    },
                    canReply = { s -> com.fariin.app.data.StoryPrivacyRules.canReply(s, contacts) },
                    receipts = receipts,
                    onShown = onShown,
                    // iOS "..." > Hide Stories: hidden at once, and the viewer closes.
                    onHide = { uid -> com.fariin.app.data.StoryMutes.setHidden(context, uid, true); close() },
                    onNotify = { uid, name ->
                        val on = com.fariin.app.data.StoryNotify.toggle(me, uid)
                        notifyBar = Triple(on, name, (notifyBar?.third ?: 0) + 1)
                    },
                    onLike = { s, liked ->
                        if (liked) toast = "Reacted"
                        storyScope.launch { service.setLiked(s, liked) }
                    },
                )
            }
        }

        // The slot's own picture riding the flight: it dissolves into the story on the way out and
        // is fully on by 85% of the way home, so the landing swaps two identical pictures.
        hero.coverUrl?.let { url ->
            Box(
                Modifier.layout { m, c ->
                    val r = if (size == IntSize.Zero) Rect.Zero else visibleRect()
                    val p = m.measure(Constraints.fixed(maxOf(1, r.width.roundToInt()), maxOf(1, r.height.roundToInt())))
                    layout(c.maxWidth, c.maxHeight) { p.place(r.left.roundToInt(), r.top.roundToInt()) }
                }.graphicsLayer {
                    alpha = hero.cover * hero.rootAlpha
                    clip = true
                    shape = RoundedCornerShape(androidx.compose.foundation.shape.CornerSize(hero.radius * hero.f))
                },
            ) {
                val a = hero.anchor
                if (a != null && hero.cover > 0f) {
                    val aw = a.width; val ah = a.height
                    // Drawn at the slot's own size and scaled to fill, so the picture is never re-fetched mid-flight.
                    Box(
                        Modifier.align(Alignment.Center).wrapContentSize(unbounded = true)
                            .layout { m, _ ->
                                val p = m.measure(Constraints.fixed(maxOf(1, aw.roundToInt()), maxOf(1, ah.roundToInt())))
                                layout(p.width, p.height) { p.place(0, 0) }
                            }
                            .graphicsLayer {
                                val r = visibleRect()
                                val s = maxOf(r.width / aw, r.height / ah)
                                scaleX = s; scaleY = s
                            },
                    ) {
                        if (url.startsWith("demo:")) Box(Modifier.fillMaxSize().background(
                            runCatching { Color(android.graphics.Color.parseColor(url.removePrefix("demo:"))) }.getOrDefault(Color.Gray)))
                        else AsyncImage(url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }

        flying?.let { (emoji, key) -> FlyingEmoji(emoji, key) { if (flying?.second == key) flying = null } }

        AnimatedVisibility(notifyBar != null, enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 12.dp, end = 12.dp, bottom = 84.dp)) {
            val b = notifyBar
            if (b != null) {
                val (before, name, after) = com.fariin.app.data.StoryNotifyRules.barParts(b.first, b.second)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 50.dp).clip(RoundedCornerShape(25.dp)).background(Color(0xE6202022)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(50.dp), contentAlignment = Alignment.Center) {
                        Icon(androidx.compose.ui.res.painterResource(if (b.first) com.fariin.app.R.drawable.ic_notifications else com.fariin.app.R.drawable.ic_bell_off_outline),
                            contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                    }
                    Text(
                        androidx.compose.ui.text.buildAnnotatedString {
                            append(before)
                            pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.SemiBold)); append(name); pop()
                            append(after)
                        },
                        color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f).padding(end = 16.dp, top = 10.dp, bottom = 10.dp),
                    )
                }
            }
        }

        AnimatedVisibility(toast != null, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.Center)) {
            Text(
                toast ?: "", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }

        sheetFor?.let { id ->
            StoryViewersSheet(
                storyId = id, service = service, who = who,
                onDismiss = { sheetFor = null },
                onOpenPerson = { uid -> profileUid = uid },
            )
        }
        // A profile opened from the sheet sits over it; closing it leaves the sheet where it was.
        profileUid?.let { uid ->
            val people = remember(me) { FirestorePeople(me, CryptoHub.crypto) }
            val openChat = LocalOpenChat.current
            Box(Modifier.fillMaxSize().background(com.fariin.app.ui.theme.Fariin.colors.bg)) {
                ContactInfoScreen(people, uid, convs.firstOrNull { !it.isGroup && it.users.contains(uid) },
                    onBack = { profileUid = null },
                    onOpenChat = { cid ->
                        val conv = convs.firstOrNull { it.id == cid }
                        onClose()
                        if (conv != null) openChat(conv)
                    })
            }
        }
    }

    confirmDelete?.let { s ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this story?") },
            text = { Text("It will also be deleted for everyone who received it.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    scope.launch {
                        if (!service.deleteStory(s.id)) { toast = "Couldn't delete. Check your connection and try again."; return@launch }
                        val gi = groups.indexOfFirst { g -> g.stories.any { it.id == s.id } }
                        if (gi < 0) return@launch
                        val left = groups[gi].stories.filter { it.id != s.id }
                        if (left.isEmpty()) { if (groups.size == 1) close() else groups.removeAt(gi) }
                        else groups[gi] = groups[gi].copy(stories = left)
                    }
                }) { Text("Delete", color = Color(0xFFFF3B30)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

/** One ring's stories, with its own place in them. */
@Composable
private fun StoryPage(
    me: String,
    group: StoryGroup,
    active: Boolean,
    paused: Boolean,
    /** The reply bar's alpha while the card flies (read in the draw pass, never recomposes). */
    chrome: () -> Float,
    summaries: MutableMap<String, StoryViewSummary>,
    service: StoryService,
    who: (String) -> Pair<String, String?>,
    onSeen: (Story) -> Unit,
    onEnd: () -> Unit,
    onBeforeStart: () -> Unit,
    onClose: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: (dy: Float, vy: Float) -> Unit,
    onOpenViewers: (String) -> Unit,
    onDelete: (Story) -> Unit,
    onReplyFocus: (Boolean) -> Unit,
    onReply: (Story, String, Boolean) -> Unit,
    onLike: (Story, Boolean) -> Unit,
    canReply: (Story) -> Boolean,
    receipts: Boolean,
    onShown: (Story) -> Unit,
    onHide: (String) -> Unit,
    onNotify: (uid: String, name: String) -> Unit = { _, _ -> },
) {
    val stories = group.stories
    if (stories.isEmpty()) return
    var index by rememberSaveable(group.key) { mutableIntStateOf(StoryRules.startIndex(group)) }
    if (index > stories.lastIndex) index = stories.lastIndex
    val story = stories[index]
    val mine = story.authorUid == me
    var holding by remember { mutableStateOf(false) }
    var loaded by remember(story.id) { mutableStateOf(false) }
    var progress by remember(story.id) { mutableFloatStateOf(0f) }
    // The ... menu holds the story still while it is open (iOS pauses under its menu).
    var menu by remember { mutableStateOf(false) }
    val stopped = !active || paused || holding || menu

    fun goNext() { if (index + 1 < stories.size) index++ else onEnd() }
    fun goBack() { if (index > 0) index-- else onBeforeStart() }

    // Receipt only the story actually on screen and loaded (iOS markSeenItem).
    LaunchedEffect(story.id, active, loaded) { if (active && loaded) onSeen(story) }
    LaunchedEffect(story.id, active) { if (active) onShown(story) }

    // Photo clock: 5 s of shown time, standing still while paused or held.
    if (!story.isVideo) {
        val stoppedNow by rememberUpdatedState(stopped)
        var elapsed by remember(story.id) { mutableLongStateOf(0L) }
        LaunchedEffect(story.id, active, loaded) {
            if (!active || !loaded) return@LaunchedEffect
            var last = withFrameMillis { it }
            while (elapsed < story.showMs) {
                if (stoppedNow) {
                    // Paused: wait without asking for a frame every vsync, then restart the clock.
                    androidx.compose.runtime.snapshotFlow { stoppedNow }.first { !it }
                    last = withFrameMillis { it }
                    continue
                }
                val now = withFrameMillis { it }
                elapsed += now - last
                last = now
                progress = (elapsed.toFloat() / story.showMs).coerceIn(0f, 1f)
            }
            goNext()
        }
    }

    // My story: the owner bar's numbers for THIS story, live while it is on screen.
    if (mine && active) {
        DisposableEffect(story.id) {
            var job: kotlinx.coroutines.Job? = null
            fun refresh() {
                job?.cancel()
                job = storyScope.launch { service.viewSummary(story.id, who)?.let { summaries[story.id] = it } }
            }
            refresh()
            val reg = service.watchViews(story.id) { refresh() }
            onDispose { reg.remove(); job?.cancel() }
        }
    }

    val slop = LocalViewConfiguration.current.touchSlop
    val focus = LocalFocusManager.current
    Box(
        Modifier.fillMaxSize().pointerInput(story.id, mine) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
                tracker.addPosition(down.uptimeMillis, down.position)
                var dx = 0f; var dy = 0f
                var vertical = false
                var held = false
                while (true) {
                    // Still and down for 220 ms is a hold (pause), not a tap.
                    val wait = if (!held && !vertical) maxOf(1L, 220L - (android.os.SystemClock.uptimeMillis() - down.uptimeMillis)) else Long.MAX_VALUE
                    val ev = withTimeoutOrNull(wait) { awaitPointerEvent() }
                    if (ev == null) { held = true; holding = true; continue }
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (ch.isConsumed) { holding = false; break }   // the pager took the swipe
                    tracker.addPosition(ch.uptimeMillis, ch.position)
                    // Counted before the up check: a fast flick can carry most of its travel in the last event.
                    val d = ch.positionChange()
                    dx += d.x; dy += d.y
                    if (!vertical && abs(dy) > slop && abs(dy) > abs(dx) * 1.2f) vertical = true
                    if (!ch.pressed) {
                        if (vertical) {
                            val vy = tracker.calculateVelocity().y
                            // My story: up past 40dp or a 300dp/s flick opens the viewers (iOS onSwipeUpEnded).
                            if (mine && (dy < -StoryMotion.VIEWERS_DISTANCE * density || vy < -StoryMotion.VIEWERS_VELOCITY * density)) {
                                onDragEnd(0f, 0f); onOpenViewers(story.id)
                            } else onDragEnd(dy, vy)
                        } else if (!held && ch.isConsumed.not()) {
                            focus.clearFocus()
                            if (StoryRules.tapGoesBack(ch.position.x, size.width.toFloat())) goBack() else goNext()
                        }
                        holding = false
                        break
                    }
                    if (!vertical && abs(dx) > slop) { holding = false; break }
                    if (vertical) { ch.consume(); holding = true; onDrag(dy) }
                }
            }
        },
    ) {
        StoryMedia(story, active = active && !stopped, onLoaded = { loaded = true },
            onVideoProgress = { progress = it }, onVideoEnd = { goNext() }, isCurrentPage = active)

        // Top shade, bars, header.
        Column(
            Modifier.fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent)))
                .statusBarsPadding().padding(top = 6.dp, start = 8.dp, end = 8.dp, bottom = 18.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                stories.forEachIndexed { i, _ ->
                    val f = when { i < index -> 1f; i == index -> progress; else -> 0f }
                    Box(Modifier.weight(1f).height(2.5.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.35f))) {
                        Box(Modifier.fillMaxWidth(f).height(2.5.dp).background(Color.White))
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(group.photoUrl, 34.dp, squircle = group.key.startsWith(StoryGroup.GROUP_PREFIX))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (mine) "My Story" else group.name.ifEmpty { "Fariin user" }, color = Color.White,
                            fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false))
                        Text("  " + StoryRules.timeAgo(story.createdAtMs), color = Color.White.copy(alpha = 0.75f), fontSize = 14.sp)
                    }
                    if (mine) Text(customTitle(story) ?: StoryRules.audienceTitle(story), color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp, maxLines = 1)
                }
                if (!mine) {
                    Box {
                        Box(Modifier.size(40.dp).clickable { menu = true }, contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More", tint = Color.White)
                        }
                        androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            // iOS: Get notifications first, for a person's story (not a group's).
                            if (story.groupId == null) {
                                val notifyOn = story.authorUid in com.fariin.app.data.StoryNotify.on.collectAsState().value
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(com.fariin.app.data.StoryNotifyRules.menuTitle(notifyOn)) },
                                    leadingIcon = {
                                        Icon(androidx.compose.ui.res.painterResource(if (notifyOn) com.fariin.app.R.drawable.ic_bell_off_outline else com.fariin.app.R.drawable.ic_notifications),
                                            contentDescription = null, modifier = Modifier.size(22.dp))
                                    },
                                    onClick = { menu = false; onNotify(story.authorUid, group.name.ifEmpty { "Fariin user" }) },
                                )
                            }
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Hide Stories") },
                                leadingIcon = { Icon(androidx.compose.ui.res.painterResource(com.fariin.app.R.drawable.ic_eye_off), contentDescription = null, modifier = Modifier.size(22.dp)) },
                                onClick = { menu = false; onHide(story.authorUid) },
                            )
                        }
                    }
                }
                Box(Modifier.size(40.dp).clickable { onClose() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
                }
            }
        }

        // Bottom: caption, then the owner bar or the reply field.
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .graphicsLayer { alpha = chrome() }
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))))
                .navigationBarsPadding().imePadding().padding(top = 28.dp),
        ) {
            if (story.caption.isNotEmpty()) {
                Text(story.caption, color = Color.White, fontSize = 16.sp,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp))
            }
            if (mine) OwnerBar(story, summaries[story.id], onViews = { onOpenViewers(story.id) }, onDelete = { onDelete(story) })
            else ReplyBar(story, service, canReply = canReply(story), showLike = receipts, onFocus = onReplyFocus,
                onSend = { t, reaction -> onReply(story, t, reaction) }, onLike = { onLike(story, it) })
        }
    }
}

/** The picture, or the clip through Media3 while this page is the one on screen. */
@Composable
private fun StoryMedia(
    story: Story,
    active: Boolean,
    isCurrentPage: Boolean,
    onLoaded: () -> Unit,
    onVideoProgress: (Float) -> Unit,
    onVideoEnd: () -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (!story.isVideo) {
            AsyncImage(story.mediaUrl, contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(), onSuccess = { onLoaded() })
            return@Box
        }
        AsyncImage(story.thumbUrl.ifEmpty { null }, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        if (!isCurrentPage) return@Box
        // Power Saving > Autoplay videos off: the poster and a play disc until the viewer taps it
        // (the story's timer waits, because nothing has loaded yet).
        val autoplay = com.fariin.app.perf.rememberPowerState().autoplayVideos
        var tapped by remember(story.id) { mutableStateOf(false) }
        if (!autoplay && !tapped) {
            Box(Modifier.size(72.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color.Black.copy(alpha = 0.45f))
                .clickable { tapped = true }.semantics { contentDescription = "Play video" },
                contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(40.dp))
            }
            return@Box
        }
        val context = LocalContext.current
        val player = remember(story.id) {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(story.mediaUrl))
                repeatMode = Player.REPEAT_MODE_OFF
                prepare()
            }
        }
        var ready by remember(story.id) { mutableStateOf(false) }
        val end by rememberUpdatedState(onVideoEnd)
        val loadedCb by rememberUpdatedState(onLoaded)
        DisposableEffect(player) {
            val l = object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY && !ready) { ready = true; loadedCb() }
                    if (state == Player.STATE_ENDED) end()
                }
            }
            player.addListener(l)
            onDispose { player.removeListener(l); player.release() }
        }
        LaunchedEffect(active, player) { player.playWhenReady = active }
        // Only while it plays: a paused clip does not move, so no frame each vsync for it.
        LaunchedEffect(player, active) {
            while (active) {
                withFrameMillis { }
                val d = player.duration
                if (d > 0) onVideoProgress((player.currentPosition.toFloat() / d).coerceIn(0f, 1f))
            }
        }
        AndroidView(
            factory = { ctx ->
                // From res/layout/story_player.xml: the TextureView surface can only be chosen there.
                (android.view.LayoutInflater.from(ctx).inflate(com.fariin.app.R.layout.story_player, null) as PlayerView).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                }
            },
            update = { it.player = player },
            modifier = Modifier.fillMaxSize(),
        )
        if (!ready) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(32.dp))
    }
}

/** iOS StoryOwnerStats + the trash: viewer faces, "N Views", reposts and hearts when there are any. */
@Composable
private fun OwnerBar(story: Story, summary: StoryViewSummary?, onViews: () -> Unit, onDelete: () -> Unit) {
    val count = summary?.count ?: 0
    val faces = summary?.recent ?: emptyList()
    val full = StoryRules.oneTimeFull(story)
    Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.clickable { onViews() }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (faces.isNotEmpty()) {
                Box(Modifier.width((26 + (faces.size - 1) * 18).dp).height(26.dp)) {
                    faces.forEachIndexed { i, v ->
                        Avatar(v.photoUrl, 26.dp, modifier = Modifier.offset(x = (i * 18).dp).border(1.5.dp, Color.Black, CircleShape))
                    }
                }
            } else {
                Icon(Icons.Filled.Visibility, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(StoryRules.viewsLabel(count, full), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if ((summary?.repostCount ?: 0) > 0) {
                Spacer(Modifier.width(18.dp))
                Text("Reposts ${summary!!.repostCount}", color = Color.White, fontSize = 15.sp)
            }
            if ((summary?.reactionCount ?: 0) > 0) {
                Spacer(Modifier.width(18.dp))
                Icon(Icons.Filled.FavoriteBorder, contentDescription = "Likes", tint = Color.White, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(6.dp))
                Text("${summary!!.reactionCount}", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(44.dp).clickable { onDelete() }, contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete story", tint = Color.White)
        }
    }
}

/**
 * "Send message…" and the heart (iOS reply pill; the heart only with receipts on). With the field
 * focused and empty the react bar sits above it: "Send reaction as a private message" and seven
 * emojis, each sent to the chat as a reaction. A story that takes no replies from me shows
 * "You can’t reply to this story" in the field's place, and keeps the heart.
 */
@Composable
private fun ReplyBar(
    story: Story, service: StoryService, canReply: Boolean, showLike: Boolean,
    onFocus: (Boolean) -> Unit, onSend: (String, Boolean) -> Unit, onLike: (Boolean) -> Unit,
) {
    var text by remember(story.id) { mutableStateOf("") }
    var liked by remember(story.id) { mutableStateOf(false) }
    var focused by remember(story.id) { mutableStateOf(false) }
    LaunchedEffect(story.id, showLike) { if (showLike) liked = !service.myReaction(story.id).isNullOrEmpty() }
    val focus = LocalFocusManager.current
    Column(Modifier.fillMaxWidth()) {
        if (canReply && focused && text.isEmpty()) {
            Column(
                Modifier.padding(start = 16.dp, end = 44.dp, bottom = 4.dp).fillMaxWidth().height(62.dp)
                    .clip(RoundedCornerShape(24.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Text(com.fariin.app.data.StoryPrivacyRules.REACT_LINE, color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp)
                Row(Modifier.fillMaxWidth()) {
                    com.fariin.app.data.StoryPrivacyRules.REACTIONS.forEach { e ->
                        Box(Modifier.weight(1f).clickable { focus.clearFocus(); onSend(e, true) }, contentAlignment = Alignment.Center) {
                            Text(e, fontSize = 24.sp)
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            // A dark glass capsule over the photo (the chat composer's floating pill, for a dark
            // backdrop): a see-through fill with a faint light rim, not a bare outline.
            Box(
                Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(22.dp))
                    .background(Color.Black.copy(alpha = 0.35f))
                    .border(0.5.dp, Color.White.copy(alpha = if (canReply) 0.35f else 0.2f), RoundedCornerShape(22.dp))
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (!canReply) {
                    Text(com.fariin.app.data.StoryPrivacyRules.REPLY_LOCKED, color = Color.White.copy(alpha = 0.6f), fontSize = 16.sp, maxLines = 1)
                } else {
                    if (text.isEmpty()) Text("Send message…", color = Color.White.copy(alpha = 0.75f), fontSize = 16.sp)
                    // Focusable only after a tap on it, and again only after the next tap once it lets
                    // go: the dialog window hands its focus to this field whenever it has none, so the
                    // keyboard came up as soon as a story opened, and again right after a send.
                    var armed by remember(story.id) { mutableStateOf(false) }
                    val fr = remember { androidx.compose.ui.focus.FocusRequester() }
                    LaunchedEffect(armed) { if (armed) runCatching { fr.requestFocus() } }
                    BasicTextField(
                        text, { text = it.take(1000) }, singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = 16.sp), cursorBrush = SolidColor(Color.White),
                        modifier = Modifier.fillMaxWidth().focusRequester(fr)
                            .focusProperties { canFocus = armed }
                            .onFocusChanged { focused = it.isFocused; if (!it.isFocused) armed = false; onFocus(it.isFocused) },
                    )
                    if (!armed) Box(Modifier.matchParentSize().clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null,
                    ) { armed = true })
                }
            }
            Spacer(Modifier.width(8.dp))
            if (canReply && text.isNotBlank()) {
                // Send in a blue circle, as the chat composer's.
                Box(Modifier.size(44.dp).clip(CircleShape).background(com.fariin.app.ui.theme.Fariin.colors.blue).clickable {
                    onSend(text.trim(), false); text = ""; focus.clearFocus()
                }, contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = Color.White)
                }
            } else if (showLike) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f))
                    .border(0.5.dp, Color.White.copy(alpha = 0.35f), CircleShape)
                    .clickable { liked = !liked; onLike(liked) }, contentAlignment = Alignment.Center) {
                    Icon(if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, contentDescription = if (liked) "Unlike" else "Like",
                        tint = if (liked) Color(0xFFFF3B30) else Color.White, modifier = Modifier.size(28.dp))
                }
            }
        }
    }
}

/** The tapped emoji rising and growing as it fades (iOS EmojiReactionView), 900 ms. */
@Composable
private fun FlyingEmoji(emoji: String, key: Long, onDone: () -> Unit) {
    val t = remember(key) { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(key) { t.animateTo(1f, androidx.compose.animation.core.tween(900)); onDone() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(emoji, fontSize = 72.sp, modifier = Modifier.graphicsLayer {
            val v = t.value
            translationY = -v * 260f * density
            scaleX = 0.6f + v * 0.9f; scaleY = scaleX
            alpha = if (v < 0.7f) 1f else (1f - v) / 0.3f
        })
    }
}

/**
 * My story's viewers (iOS StoryViewersSheet): newest first, a heart by anyone who liked it,
 * Search, and a tap opens that person's profile over the sheet.
 */
@Composable
private fun StoryViewersSheet(
    storyId: String,
    service: StoryService,
    who: (String) -> Pair<String, String?>,
    onDismiss: () -> Unit,
    onOpenPerson: (String) -> Unit,
) {
    val c = com.fariin.app.ui.theme.Fariin.colors
    var people by remember(storyId) { mutableStateOf<List<StoryViewer>?>(null) }
    var failed by remember(storyId) { mutableStateOf(false) }
    var query by rememberSaveable(storyId) { mutableStateOf("") }
    DisposableEffect(storyId) {
        var job: kotlinx.coroutines.Job? = null
        fun load() {
            job?.cancel()
            job = storyScope.launch {
                var got = service.fetchViewers(storyId, who)
                while (got == null) { failed = true; delay(3000); got = service.fetchViewers(storyId, who) }
                failed = false; people = got
            }
        }
        load()
        val reg = service.watchViews(storyId) { load() }
        onDispose { reg.remove(); job?.cancel() }
    }
    // Slides up and away on the iOS sheet spring (critically damped, stiffness 340); the finger can
    // pull it down, and it closes past 30% of the screen or 5% with a 150dp/s flick.
    val shown = remember { Animatable(0f) }
    val sheetScope = rememberCoroutineScope()
    var sheetH by remember { mutableFloatStateOf(1f) }
    val dens = LocalDensity.current.density
    val screenH = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * dens
    val sheetSpring = spring<Float>(dampingRatio = 1f, stiffness = StoryMotion.SHEET_STIFFNESS)
    LaunchedEffect(Unit) { shown.animateTo(1f, sheetSpring) }
    var leaving by remember { mutableStateOf(false) }
    val close: (Float) -> Unit = { v ->
        if (!leaving) {
            leaving = true
            sheetScope.launch { shown.animateTo(0f, sheetSpring, initialVelocity = v); onDismiss() }
        }
    }
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = shown.value.coerceIn(0f, 1f) }.background(Color.Black.copy(alpha = 0.35f)).clickable(
        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null,
    ) { close(0f) }) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.62f)
                .onSizeChanged { sheetH = maxOf(1f, it.height.toFloat()) }
                .graphicsLayer { translationY = (1f - shown.value) * sheetH }
                .pointerInput(Unit) {
                    var start = 1f
                    val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
                    detectVerticalDragGestures(
                        onDragStart = { start = shown.value; tracker.resetTracking() },
                        onVerticalDrag = { ch, d ->
                            tracker.addPosition(ch.uptimeMillis, ch.position)
                            sheetScope.launch { shown.snapTo((shown.value - d / sheetH).coerceIn(0f, 1f)) }
                        },
                        onDragEnd = {
                            val vDown = tracker.calculateVelocity().y
                            val dropped = (start - shown.value) * sheetH
                            if (dropped >= screenH * 0.30f || (dropped >= screenH * 0.05f && vDown >= 150f * dens)) close(-vDown / sheetH)
                            else sheetScope.launch { shown.animateTo(1f, sheetSpring, initialVelocity = -vDown / sheetH) }
                        },
                        onDragCancel = { sheetScope.launch { shown.animateTo(1f, sheetSpring) } },
                    )
                }
                .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)).background(c.bg)
                .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {}
                .navigationBarsPadding(),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp).size(36.dp, 5.dp).clip(RoundedCornerShape(3.dp)).background(c.separator))
            val list = people
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(list?.let { StoryRules.viewsLabel(it.size, false) } ?: "Views", color = c.label, fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Box(Modifier.size(36.dp).clip(CircleShape).background(c.fieldFill).clickable { close(0f) }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = c.label, modifier = Modifier.size(18.dp))
                }
            }
            Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(38.dp).clip(RoundedCornerShape(10.dp)).background(c.fieldFill)
                .padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) Text("Search", color = c.secondary, fontSize = 16.sp)
                BasicTextField(query, { query = it }, singleLine = true, textStyle = TextStyle(color = c.label, fontSize = 16.sp),
                    cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(8.dp))
            when {
                list == null -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    if (failed) Text("Couldn't load viewers. Trying again…", color = c.secondary, fontSize = 15.sp)
                    else CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                }
                list.isEmpty() -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text("No views yet", color = c.secondary, fontSize = 15.sp)
                }
                else -> androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    val shown = list.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
                    items(shown.size, key = { shown[it].uid }) { i ->
                        val v = shown[i]
                        Row(
                            Modifier.fillMaxWidth().clickable { onOpenPerson(v.uid) }.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(v.photoUrl, 44.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(v.name, color = c.label, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(StoryRules.timeAgo(v.viewedAtMs), color = c.secondary, fontSize = 13.sp)
                            }
                            if (!v.reaction.isNullOrEmpty()) {
                                Icon(Icons.Filled.Favorite, contentDescription = "Liked", tint = Color(0xFFFF3B30), modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** My custom story's header line: its list's name now (iOS StoryAudienceStore.storyName), else null. */
@Composable
private fun customTitle(s: Story): String? {
    if (s.audienceLabel != "custom" || s.oneTime) return null
    val links by com.fariin.app.data.StoryPrivacyStore.links.collectAsState()
    val custom by com.fariin.app.data.StoryPrivacyStore.custom.collectAsState()
    return com.fariin.app.data.StoryPrivacyRules.storyName(links[s.id], custom)
}

/** The card's place in its flight. Every field is snapshot state read in the draw pass only. */
private class HeroState {
    /** 0 = full screen, 1 = seated in the slot. */
    var f by mutableFloatStateOf(0f)
    var center by mutableStateOf(Offset.Zero)
    var alpha by mutableFloatStateOf(1f)
    /** How far the story has converged to the slot's shape (0 whole picture, 1 the slot's aspect). */
    var crop by mutableFloatStateOf(0f)
    /** The slot's picture over the story. */
    var cover by mutableFloatStateOf(0f)
    var coverUrl by mutableStateOf<String?>(null)
    /** How much of the backdrop's floor is held (1 under the finger, faded out by a landing). */
    var dimFloor by mutableFloatStateOf(1f)
    /** The no-destination close: shrink and whole-screen fade. */
    var shrink by mutableFloatStateOf(1f)
    var rootAlpha by mutableFloatStateOf(1f)
    var anchor by mutableStateOf<Rect?>(null)
    var radius by mutableFloatStateOf(0f)
    var exiting by mutableStateOf(false)
    var live by mutableStateOf(false)
    var visible by mutableStateOf(false)
    var committing = false
    var closed = false
    var staged = false
}

/** A rounded rect of [w] by [h] centred in the layer: the flying card's clip, in layer pixels. */
private data class CardShape(val w: Float, val h: Float, val r: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val l = (size.width - w) / 2f
        val t = (size.height - h) / 2f
        return Outline.Rounded(RoundRect(l, t, l + w, t + h, CornerRadius(r)))
    }
}
