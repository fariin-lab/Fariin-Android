package com.fariin.app.ui.stories

import com.fariin.app.R
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.AlertDialog
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
import com.fariin.app.data.StoryTapTarget
import com.fariin.app.data.StoryTaps
import com.fariin.app.data.StoryViewSummary
import com.fariin.app.data.StoryViewer
import com.fariin.app.data.TextLinks
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
import kotlinx.coroutines.withContext
import com.fariin.app.data.StoryMedia
import coil3.request.allowHardware
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
    /** A card that is not the ring's own (Posted Stories' tile): the flight leaves and lands there. */
    origin: StoryOrigin? = null,
) {
    // Back reaches this window's own dispatcher too (owner 2026-11-04: the back swipe did nothing in a
    // story). Since 919d7a6 the app is off the predictive-back system, so the viewer's
    // PredictiveBackHandler alone was not always reached; the dialog's back now asks the viewer to
    // close, with the same shrink into the card as the swipe down.
    var backTick by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    Dialog(
        onDismissRequest = { backTick++ },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
            dismissOnBackPress = true, dismissOnClickOutside = false),
    ) {
        // Block screenshots (iOS captureProtected): while such a story is on screen this window is
        // FLAG_SECURE, so a screenshot or a screen recording of it comes out black.
        val window = (androidx.compose.ui.platform.LocalView.current.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
        // The window itself neither dims nor fades: the zoom paints its own wall, on the card's fraction.
        DisposableEffect(window) {
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window?.setWindowAnimations(0)
            // The bar areas are the viewer's own wall, not a grey band of this window's: grey bands
            // stayed while the card flew home and snapped white the frame the window went (owner
            // 2026-11-03, "flick" top and bottom on close).
            window?.let { w ->
                @Suppress("DEPRECATION") run {
                    w.statusBarColor = android.graphics.Color.TRANSPARENT
                    w.navigationBarColor = android.graphics.Color.TRANSPARENT
                }
                if (android.os.Build.VERSION.SDK_INT >= 29) { w.isStatusBarContrastEnforced = false; w.isNavigationBarContrastEnforced = false }
            }
            onDispose { }
        }
        // Bar icons follow the wall under them: white over the story, the app's own once the wall is
        // under half its flight dim, both ways. A switch at the start of the close (and none on the
        // open) left white icons on a light list for half of each flight (owner's video 2026-11-07).
        var overList by remember { mutableStateOf(true) }
        val appDark = com.fariin.app.ui.theme.Fariin.colors.dark
        val view = androidx.compose.ui.platform.LocalView.current
        DisposableEffect(window, overList, appDark) {
            window?.let { w ->
                androidx.core.view.WindowCompat.getInsetsController(w, view).apply {
                    isAppearanceLightStatusBars = overList && !appDark
                    isAppearanceLightNavigationBars = overList && !appDark
                }
            }
            onDispose { }
        }
        var secure by remember { mutableStateOf(false) }
        DisposableEffect(window, secure) {
            if (secure) window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            else window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            onDispose { }
        }
        // A fariin.com link tapped on a story opens its page in the app; the viewer goes first, so
        // the page is not left under it.
        val nav = com.fariin.app.ui.people.LocalPeopleNav.current
        val closing by rememberUpdatedState(onClose)
        val routed = remember(nav) {
            nav.copy(
                contact = { closing(); nav.contact(it) }, myProfile = { closing(); nav.myProfile() },
                chat = { closing(); nav.chat(it) }, joinLink = { k, v -> closing(); nav.joinLink(k, v) },
            )
        }
        androidx.compose.runtime.CompositionLocalProvider(com.fariin.app.ui.people.LocalPeopleNav provides routed) {
            StoryViewer(me, groups, startGroup, conversations, onClose, onShown = { secure = it.captureProtected },
                onBarsOverList = { overList = it }, backTick = backTick, origin = origin)
        }
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
    /** True while the wall is thin enough that the list behind owns the bar area. */
    onBarsOverList: (Boolean) -> Unit = {},
    /** Bumped by the window's own back: close as the back gesture would. */
    backTick: Int = 0,
    /** See [StoryViewerDialog]: the tapped card when it is not the ring's own slot. */
    origin: StoryOrigin? = null,
) {
    val service = remember(me) { StoryService(me) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val groups = remember { mutableStateListOf<StoryGroup>().apply { addAll(initialGroups) } }
    // "..." > Edit Viewers on my own story (iOS ShareStorySheet editing): the audience page over it.
    var editViewersFor by remember { mutableStateOf<Story?>(null) }
    var updatingViewers by remember { mutableStateOf(false) }
    // The report sheet is up: the story stands still under it.
    val reportUp = com.fariin.app.data.ReportFlow.current.collectAsState().value != null
    // The first-time guide (iOS StoryGuideHost): the story stands still under it.
    var guideUp by remember { mutableStateOf(StoryGuide.isOwed(context)) }
    val pager = rememberPagerState(initialPage = startGroup.coerceIn(0, maxOf(0, initialGroups.size - 1))) { groups.size }
    val convs by rememberUpdatedState(conversations)
    // Repost (iOS beginRepost): the original drawn as a card, then the story composer with the credit.
    val reposting = remember { mutableStateOf(false) }
    var repostDraft by remember { mutableStateOf<StoryMedia.Prepared?>(null) }
    val repostCtx = androidx.compose.ui.platform.LocalContext.current
    val repostScope = rememberCoroutineScope()
    fun startRepost(st: Story, g: StoryGroup) {
        if (reposting.value) return
        reposting.value = true
        repostScope.launch {
            val draft = withContext(Dispatchers.IO) {
                runCatching {
                    val loader = coil3.SingletonImageLoader.get(repostCtx)
                    suspend fun load(u: String?): android.graphics.Bitmap? = u?.takeIf { it.isNotEmpty() }?.let { url ->
                        (loader.execute(coil3.request.ImageRequest.Builder(repostCtx).data(url)
                            .allowHardware(false).build()) as? coil3.request.SuccessResult)
                            ?.image?.let { (it as? coil3.BitmapImage)?.bitmap }
                    }
                    val credit = com.fariin.app.data.StoryRepost(st.authorUid, g.name, st.id, card = true)
                    // iOS beginVideoRepost: the video itself, downloaded, into the composer with the credit.
                    if (st.isVideo) {
                        repostCtx.cacheDir.listFiles { f -> f.name.startsWith("repost-") && f.name.endsWith(".mp4") &&
                            System.currentTimeMillis() - f.lastModified() > 3_600_000 }?.forEach { it.delete() }
                        val f = java.io.File(repostCtx.cacheDir, "repost-${st.id}.mp4")
                        val c = java.net.URL(st.mediaUrl).openConnection() as java.net.HttpURLConnection
                        try {
                            if (c.responseCode != 200) return@runCatching null
                            c.inputStream.use { i -> f.outputStream().use { i.copyTo(it) } }
                        } finally { c.disconnect() }
                        return@runCatching StoryMedia.prepareVideo(repostCtx, android.net.Uri.fromFile(f))?.let { p ->
                            StoryMedia.Prepared(p.jpeg, p.blurThumb, p.video, p.width, p.height, p.stickers, credit)
                        }
                    }
                    val image = load(st.previewUrl) ?: return@runCatching null
                    val card = RepostCard.render(image, g.name, load(g.photoUrl))
                    val f = java.io.File(repostCtx.cacheDir, "repost-${st.id}.jpg")
                    f.outputStream().use { card.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it) }
                    StoryMedia.preparePhoto(repostCtx, android.net.Uri.fromFile(f))?.let { p ->
                        StoryMedia.Prepared(p.jpeg, p.blurThumb, null, p.width, p.height, p.stickers, credit)
                    }
                }.getOrNull()
            }
            if (draft == null) { reposting.value = false; com.fariin.app.ui.components.Snack.show("Could not load that story") }
            else repostDraft = draft
        }
    }
    repostDraft?.let { d ->
        StoryComposerDialog(me, d, conversations,
            onClose = { repostDraft = null; reposting.value = false },
            onPosted = { repostDraft = null; reposting.value = false; com.fariin.app.ui.components.Snack.show("Reposted") })
    }
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
    // Stories whose picture a page has on screen: until then the viewers row keeps its own thumbnail
    // over the live card, so landing on a story never shows an empty card while it loads.
    val pageLoaded = remember { mutableStateMapOf<String, Boolean>() }
    // The viewers sheet and the shrink of my story into the row above it (iOS viewersProgress).
    val sheet = remember { ViewersSheetState(scope) }
    val sheetFor = sheet.id
    var profileUid by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<Story?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    // iOS StoryNotifyMenuAction: the bar that confirms Get / Mute notifications, 3 s, a newer tap keeps its own.
    var notifyBar by remember { mutableStateOf<Triple<Boolean, String, Int>?>(null) }
    LaunchedEffect(me) { com.fariin.app.data.StoryNotify.load(me) }
    LaunchedEffect(notifyBar) { val b = notifyBar ?: return@LaunchedEffect; delay(3000); if (notifyBar == b) notifyBar = null }
    var replyFocused by remember { mutableStateOf(false) }

    LaunchedEffect(toast) { if (toast != null) { delay(1500); toast = null } }
    // A link on a story, through the chats' own routing: a call link or a username opens in the
    // app, any other address in the in-app browser (a Custom Tab).
    val linkTap = com.fariin.app.ui.thread.rememberBubbleLinkTap(
        remember(me) { com.fariin.app.ui.thread.ThreadUi(title = "", me = me) }) { toast = it }
    // OUR OWN LINKS OPEN IN THE APP (owner 2026-11-06: a channel link in a story shows Join, a
    // person's shows Chat). The story closes, and the app's link host (the one outside links go
    // through) opens the channel or group page, with Join / Follow there, or the chat with the person.
    // Everything else stays as it was: the in-app browser over the story.
    val openLink: (String) -> Unit = { url ->
        if (com.fariin.app.data.StoryLinkCardRules.opensInApp(url)) {
            onClose()
            com.fariin.app.ui.thread.ExternalLinks.pending.value = url
        } else linkTap(TextLinks.Span(0, url.length, TextLinks.Kind.WEB, url))
    }

    BackHandler(enabled = profileUid != null || sheetFor != null) {
        if (profileUid != null) profileUid = null else sheet.close()
    }
    // My stories in the row while the sheet is up, and every one's count under its card.
    val sheetStories = sheetFor?.let { id -> groups.firstOrNull { g -> g.stories.any { it.id == id } }?.stories }.orEmpty()
    LaunchedEffect(sheetFor != null, sheetStories.map { it.id }) {
        if (sheetFor == null) return@LaunchedEffect
        sheetStories.forEach { s -> if (s.id != sheetFor) service.viewSummary(s.id, who)?.let { summaries[s.id] = it } }
    }
    // Which story of my ring the row landed on, for its page to show.
    var jumpTo by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(sheetFor) { if (sheetFor == null) jumpTo = null }
    // Live, not captured: this window learns its status bar height a frame or two after it opens,
    // and a flight that fixed its target on the first frame landed that much too high, then
    // jumped down (owner 2026-11-03, "something jump" opening My Story).
    val topInset by rememberUpdatedState(WindowInsets.statusBars.getTop(LocalDensity.current).toFloat())
    val bottomInset by rememberUpdatedState(WindowInsets.navigationBars.getBottom(LocalDensity.current).toFloat())

    // ---- The zoom: out of the tapped card, back into it (iOS StoryZoomPresenter + the hero drag) ----
    val density = LocalDensity.current.density
    val hostView = LocalView.current
    var size by remember { mutableStateOf(IntSize.Zero) }
    val hero = remember { HeroState() }
    val heroJob = remember { arrayOfNulls<Job>(1) }
    fun currentKey() = groups.getOrNull(pager.currentPage)?.key
    // The slot the flight uses: the tapped tile when one was given (Posted Stories), else the ring's card.
    fun slotKey() = origin?.key ?: currentKey()
    val cardRadius = 14f * density
    // The hole follows whoever is on screen, so the landing fills the slot it came out of.
    val shownKey = slotKey()
    // The card in the list hides only once the viewer has drawn its copy (one frame), as the photo
    // and menu zooms: hiding it in the same frame left an empty card for a frame.
    LaunchedEffect(shownKey) { if (!hero.closed) { withFrameMillis { }; if (!hero.closed) StoryOrigins.hiddenKey = shownKey } }
    DisposableEffect(Unit) { onDispose { StoryOrigins.hiddenKey = null } }

    fun anchorFor(key: String?): Rect? {
        val r = StoryOrigins.rects[key ?: return null] ?: return null
        if (r.width < 1f || r.height < 1f) return null
        val loc = IntArray(2)
        hostView.getLocationOnScreen(loc)
        return r.translate(-loc[0].toFloat(), -loc[1].toFloat())
    }
    /**
     * A ring's story card in this window (iOS StoryDetailView): under the status bar, 9:16 unless
     * shorter, ending above my own bar or the reply bar. Every flight and the sheet aim at this.
     */
    fun cardOf(g: StoryGroup?): Rect {
        val w = size.width.toFloat(); val h = size.height.toFloat()
        val first = g?.stories?.firstOrNull()
        val mine = first != null && com.fariin.app.data.GroupStoryRules.canSeeViews(first, me, convs)
        val footer = if (mine) StorySheetGeometry.ownFooter(bottomInset, density) else StorySheetGeometry.replyFooter(bottomInset, density)
        return Rect(0f, topInset, w, topInset + StorySheetGeometry.cardHeight(w, h, topInset, footer))
    }
    fun card() = cardOf(groups.getOrNull(pager.currentPage))
    fun rest() = card().center

    /**
     * What the slot shows, for the cover to wear: a chat row's ring holds the person's (or group's)
     * round photo, a Stories card the newest story's picture. A chat row is told by its square,
     * well-rounded slot (the Stories cards are 66 by 94 with 14 corners). Call after [HeroState.radius].
     */
    fun stageCover(a: Rect, key: String?) {
        val g = groups.firstOrNull { it.key == key }
        hero.avatar = g != null && abs(a.width - a.height) < 2f && hero.radius >= 0.25f * minOf(a.width, a.height)
        if (hero.avatar && g != null) {
            val conv = if (g.key.startsWith(StoryGroup.GROUP_PREFIX)) convs.firstOrNull { it.id == g.key.removePrefix(StoryGroup.GROUP_PREFIX) }
                else convs.firstOrNull { !it.isGroup && it.otherUid(me) == g.key }
            hero.coverUrl = conv?.displayPhoto(me) ?: g.photoUrl ?: ""
            hero.avatarKind = if (conv == null) com.fariin.app.ui.components.AvatarKind.PERSON
                else com.fariin.app.ui.components.AvatarKind.of(conv.isGroup, conv.isChannel)
            hero.squircle = conv?.isChannel == true
            hero.ringFlags = com.fariin.app.data.StoryRings.seenFlags(g.copy(lastViewedAtMs = watermarks[g.key] ?: g.lastViewedAtMs))
        } else {
            hero.coverUrl = g?.stories?.lastOrNull()?.listThumb
        }
        // A Posted Stories tile wears its own story's picture, the one already on screen.
        origin?.let { hero.avatar = false; hero.coverUrl = it.coverUrl }
    }

    /** The card's corner at its size on screen now, in px: into a chat row's photo it is round once its crop is done. */
    fun cornerFor(r: Rect): Float {
        val restR = StorySheetGeometry.CARD_RADIUS * density
        val base = restR + (hero.radius - restR) * hero.f
        val a = hero.anchor
        if (!hero.avatar || a == null) return base
        val target = hero.radius / maxOf(1f, minOf(a.width, a.height)) * minOf(r.width, r.height)
        return base + (target - base) * hero.crop
    }

    // The bar icons' side of the wall (see StoryViewerDialog): only the flips reach the window.
    val barsTo by rememberUpdatedState(onBarsOverList)
    LaunchedEffect(Unit) {
        androidx.compose.runtime.snapshotFlow {
            val wall = if (hero.visible) StoryMotion.dim(hero.f, hero.dimFloor) * hero.rootAlpha else 0f
            wall < StoryMotion.BARS_FLIP
        }.collect { barsTo(it) }
    }

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
        val a = anchorFor(slotKey())
        hero.center = rest()
        // Power Saving's animations off: no flight, the same short fade as a story with no slot.
        if (a == null || !com.fariin.app.ui.components.Motion.on) {
            // Nothing to fly from (Posted Stories): show the story, faded in like the pager's backstop.
            hero.rootAlpha = 0f
            hero.visible = true
            Animatable(0f).animateTo(1f, tween(180)) { hero.rootAlpha = value }
            return@LaunchedEffect
        }
        hero.anchor = a
        hero.radius = StoryOrigins.radii[slotKey()] ?: cardRadius
        stageCover(a, currentKey())
        hero.f = 1f; hero.center = a.center; hero.crop = 1f; hero.cover = 1f; hero.dimFloor = 0f
        hero.ring = if (hero.avatar) 1f else 0f
        hero.live = true
        hero.visible = true
        // Seated first, flown after: the first frames go to building the page, not to the spring's clock.
        withFrameMillis { }; withFrameMillis { }
        val from = a.center
        runHero(0f, rest(), 1f, 0f, StoryMotion.OPEN_STIFFNESS, StoryMotion.OPEN_SETTLE,
            cover = StoryMotion::coverOut, crop = StoryMotion::coverOut, dimFloor = { it },
            // Aim at where the card is now, every frame, so the landing is where the page sits.
            extra = { t ->
                hero.center = androidx.compose.ui.geometry.lerp(from, rest(), t)
                if (hero.avatar) hero.ring = StoryMotion.ringOut(t)
            }) {
            hero.live = false; hero.cover = 0f; hero.crop = 0f; hero.ring = 0f
        }
    }

    fun land() {
        if (hero.closed) return
        hero.closed = true
        // The card is home: put the row's own card back, and lift the viewer off it a frame later.
        StoryOrigins.hiddenKey = null
        scope.launch { withFrameMillis { }; withFrameMillis { }; onClose() }
    }

    /** The flight home from where the card is now: into slot [a], or off the bottom with none. */
    fun closeTo(a: Rect?, vy: Float) {
        hero.committing = true
        if (!com.fariin.app.ui.components.Motion.on) {
            // Power Saving's animations off: a short fade where it stands.
            heroJob[0] = scope.launch { Animatable(hero.rootAlpha).animateTo(0f, tween(150)) { hero.rootAlpha = value }; land() }
        } else if (a == null) {
            // No card to fly to (scrolled away, or opened from Posted Stories): drift down by its own
            // height, shrink to 0.72 and fade, on the 0.25s critically damped spring (iOS MediaDismiss).
            val h = card().height * (1f + (((hero.anchor?.width ?: 0f) / size.width) - 1f) * hero.f)
            runHero(hero.f, hero.center + Offset(0f, h), 0f, 0f, StoryMotion.DRIFT_STIFFNESS, 0.004f,
                dimFloor = { 1f - it }, extra = { t -> hero.shrink = 1f - (1f - StoryMotion.DRIFT_SCALE) * t; hero.rootAlpha = 1f - t }) { land() }
        } else {
            hero.anchor = a
            hero.radius = StoryOrigins.radii[slotKey()] ?: cardRadius
            stageCover(a, currentKey())
            val remaining = maxOf(1f, (a.center - hero.center).getDistance())
            // Into a chat row's photo the card turns round and wears the photo early, and the row's
            // ring comes in at the end; a Stories card keeps the card's own timings.
            val av = hero.avatar
            runHero(1f, a.center, 1f, minOf(6f, maxOf(0f, vy) / remaining), StoryMotion.CLOSE_STIFFNESS,
                StoryMotion.settle(remaining, density),
                // A Stories card: the face shrinks whole and turns into the slot's picture only at
                // the end, so the landing is the slot itself (no empty slot, no pop).
                cover = if (av) StoryMotion::avatarCoverIn else StoryMotion::coverInLate,
                crop = if (av) StoryMotion::avatarCropIn else StoryMotion::coverIn, dimFloor = { 1f - it },
                extra = if (av) ({ t -> hero.ring = StoryMotion.ringIn(t) }) else null) { land() }
        }
        // A flight that never lands must not trap anyone in the viewer: 1.2s is four times the flight.
        scope.launch { delay(1200); land() }
    }

    /** Every way out: the swipe, the X, back, the last story ending. Same landing for all of them. */
    fun close(vy: Float = 0f) {
        if (hero.committing || hero.closed) return
        if (!hero.staged || size == IntSize.Zero) { land(); return }
        // The viewers sheet is up: the story is the row's small card. Fold the sheet first so the card
        // grows back into the full story on its own spring (iOS closeViewers), then fly home from
        // there. Taking the hero path straight away snapped the card to full size in one frame.
        // Read from the state, not `sheetFor`: this runs again from the coroutine below.
        if (sheet.id != null && !hero.live) {
            hero.committing = true
            scope.launch {
                runCatching { sheet.collapse() }
                withFrameMillis { }
                hero.committing = false
                close(vy)
            }
            return
        }
        heroJob[0]?.cancel()
        hero.committing = true
        // The header, its name, time and X stay in the card as it shrinks home (iOS): held at what
        // they show now (all of it after a tap on X), not faded on the card's size.
        hero.chromeHold = StoryMotion.chrome(hero.f) * (1f - sheet.fraction * 1.4f).coerceIn(0f, 1f)
        if (!hero.live) { hero.center = rest(); hero.f = 0f }
        hero.live = true
        val a = anchorFor(slotKey())
        if (a == null && com.fariin.app.ui.components.Motion.on) {
            // The slot may be a frame behind (the list re-laid out under the viewer): look once more
            // before giving up on it, so the card never drops off the bottom when its slot is there.
            scope.launch {
                withFrameMillis { }
                val again = anchorFor(slotKey())
                hero.committing = false
                if (again != null) closeTo(again, vy) else closeTo(null, vy)
            }
            return
        }
        closeTo(a, vy)
    }

    fun dragBegin() {
        if (hero.committing || hero.closed || size == IntSize.Zero) return
        heroJob[0]?.cancel()
        hero.anchor = anchorFor(slotKey())
        hero.radius = StoryOrigins.radii[slotKey()] ?: cardRadius
        hero.live = true
        hero.f = 0f; hero.center = rest(); hero.alpha = 1f
        // Nothing cross-fades under the finger, nothing is cropped, the floor is held.
        hero.cover = 0f; hero.crop = 0f; hero.dimFloor = 1f; hero.ring = 0f
    }
    /** Down only, measured from the touch: 1:1 while the card shrinks, then a short rubber band. */
    fun dragTo(dy: Float) {
        if (!hero.live || hero.committing || size == IntSize.Zero) return
        val w = size.width.toFloat(); val h = size.height.toFloat()
        val c = card()
        val ty = maxOf(0f, dy)
        val ratio = (hero.anchor?.width ?: 0f) / w
        val ceiling = StoryMotion.dragCeiling(ratio)
        val span = StoryMotion.DRAG_SPAN * density
        hero.f = minOf(ceiling, ty / span)
        var y = c.center.y + StoryMotion.dragTravel(ty, span * ceiling, StoryMotion.DRAG_TAIL * density)
        // And the bottom edge stays on the display.
        val scaleNow = 1f + (ratio - 1f) * hero.f
        y = minOf(y, maxOf(c.center.y, h - c.height * scaleNow / 2f - 10f * density))
        hero.center = Offset(w / 2f, y)
        hero.alpha = 1f
    }
    fun springBack(vy: Float) {
        if (!hero.live || hero.committing) return
        // An arrival: the chrome fades back in over the last 30% with the card.
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

    // The window's back (see StoryViewerDialog): a sheet or profile over the story closes first.
    androidx.compose.runtime.LaunchedEffect(backTick) {
        if (backTick == 0) return@LaunchedEffect
        when {
            profileUid != null -> profileUid = null
            sheetFor != null -> sheet.close()
            else -> close()
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
        val card = card()
        val w = card.width; val h = card.height
        val a = hero.anchor
        val aw = a?.width ?: 0f; val ah = a?.height ?: 0f
        // The crop converges the story to the slot's own shape on a flight; a drag keeps it whole.
        val th = h * aw / w + (ah - h * aw / w) * hero.crop
        val vw = (w + (aw - w) * hero.f) * hero.shrink
        // Into a chat row's photo the crop drives the shape itself, so the card is already a circle
        // at its crop's end, not a tall pill until the very last frame.
        val vh = if (hero.avatar && aw > 0f) vw * (h / w + (ah / aw - h / w) * hero.crop)
            else (h + (th - h) * hero.f) * hero.shrink
        // At rest the card is where the window puts it, whatever the window has done since.
        val c = if (hero.live) hero.center else card.center
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
            userScrollEnabled = sheetFor == null,
            // The phone's Back stays on the whole edge, as the reference apps (owner 2026-11-08: with the
            // middle band taken, leaving a story needed a swipe at the very end of the edge). Person
            // to person is a swipe from inside the screen.
            modifier = Modifier.fillMaxSize().graphicsLayer {
                if (size == IntSize.Zero) return@graphicsLayer
                val w = size.width.toFloat(); val h = size.height.toFloat()
                // Viewers up: the story is the row's centre card, picture and all, scaled (never resized,
                // so a playing clip's surface is untouched) and clipped to its 9:16 band with 24 corners.
                // The layer scales about its centre, so a card off the centre is moved by where the
                // scale put it: card point p lands on centre + s * (p - centre) + translation.
                val live = sheetStories.indexOfFirst { it.id == sheetFor }
                if (sheetFor != null && live >= 0 && !hero.live) {
                    val g = StorySheetGeometry(w, h, topInset, density, sheet.fraction, StorySheetGeometry.ownFooter(bottomInset, density))
                    val pl = g.placement(live - sheet.row.value)
                    val s = pl.h / g.contentH
                    scaleX = s; scaleY = s
                    translationX = pl.cx - w / 2f
                    translationY = pl.cy - (h / 2f + s * (g.restCenterY - h / 2f))
                    alpha = 1f
                    clip = true
                    shape = CardShape(w / 2f, g.restCenterY, w, g.contentH, pl.corner / maxOf(0.001f, s))
                    return@graphicsLayer
                }
                val c = card()
                val r = visibleRect()
                val s = maxOf(r.width / c.width, r.height / c.height)
                scaleX = s; scaleY = s
                translationX = r.center.x - (w / 2f + s * (c.center.x - w / 2f))
                translationY = r.center.y - (h / 2f + s * (c.center.y - h / 2f))
                alpha = hero.alpha
                val moving = hero.f > 0.0001f || hero.shrink < 1f
                clip = moving
                if (moving && s > 0f) {
                    // The card's own corner at full size, the slot's at home (iOS lands the mask on it).
                    val radius = cornerFor(r)
                    // The bar under the card stays in the clip while it is showing (the chrome span, both ways).
                    val bandBottom = c.center.y + r.height / s / 2f
                    val bar = StoryMotion.chrome(hero.f)
                    shape = CardShape(c.center.x, c.center.y, r.width / s, r.height / s, radius / s, maxOf(0f, h - bandBottom) * bar)
                }
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
                    canRepost = { st -> com.fariin.app.data.StoryRepostRules.allows(st, st.repostOf, me,
                        convs.firstOrNull { !it.isGroup && it.users.contains(st.authorUid) && it.users.contains(me) }?.isBlockedByMe(me) == true) },
                    onRepost = { st -> startRepost(st, g) },
                    me = me, group = g, card = cardOf(g), active = page == pager.currentPage && !pager.isScrollInProgress,
                    paused = sheetFor != null || profileUid != null || confirmDelete != null || replyFocused || hero.live || reposting.value || guideUp || editViewersFor != null || reportUp,
                    // The bars and caption stay inside the shrinking card and fade as it shrinks (iOS);
                    // over a third of the pull they were gone in a frame on a quick swipe.
                    chrome = {
                        if (hero.chromeHold >= 0f) hero.chromeHold
                        else StoryMotion.chrome(hero.f) * (1f - sheet.fraction * 1.4f).coerceIn(0f, 1f)
                    },
                    jumpTo = if (page == pager.currentPage) jumpTo else null,
                    summaries = summaries, service = service, who = who,
                    onSeen = { s ->
                        // Fariin's own story: seen on this phone and one view document, no receipt to anyone.
                        if (com.fariin.app.data.OfficialStories.isOfficial(s)) { com.fariin.app.data.OfficialStories.recordView(context, s, receipts); return@StoryPage }
                        if (s.authorUid != me && marked.add(s.id)) {
                            val wm = watermarks[s.ringKey]
                            if (StoryRules.advancesWatermark(wm, s.createdAtMs)) watermarks[s.ringKey] = s.createdAtMs + 1
                            storyScope.launch { service.markViewed(s, wm, receipts) }
                        }
                    },
                    onEnd = { nextGroup() }, onBeforeStart = { previousGroup() },
                    onClose = { close() },
                    onLink = openLink,
                    onRepostAuthor = { uid -> profileUid = uid },
                    authorPhoto = { uid -> groups.firstOrNull { g -> g.stories.any { it.authorUid == uid } }?.photoUrl },
                    onDrag = { dy ->
                        if (!hero.live && dy > 0f) dragBegin()
                        dragTo(dy)
                    },
                    onDragEnd = { dy, vy -> dragEnd(dy, vy) },
                    onOpenViewers = { id -> sheet.open(id, maxOf(0, g.stories.indexOfFirst { it.id == id })) },
                    onDelete = { confirmDelete = it },
                    onReplyFocus = { replyFocused = it },
                    // Typed text is a reply; a react bar emoji is the same message marked isReaction
                    // (iOS StoryViewer.handle: ReplyRef isStatus + storyThumb, isReaction for the bar).
                    onReply = { s, text, reaction ->
                        toast = "Sent"
                        if (reaction) flying = text to System.nanoTime()
                        // A group story (iOS group stories v2): into the story's shared thread that its
                        // owner and admins read, not privately to the poster.
                        if (s.groupId != null) storyScope.launch {
                            val ok = runCatching {
                                com.fariin.app.data.GroupStoryReplies.send(me, s.id, text = if (reaction) "" else text, emoji = if (reaction) text else "")
                            }.isSuccess
                            if (!ok) toast = "Couldn't send. Check your connection and try again."
                        } else storyScope.launch {
                            val ok = runCatching {
                                val cid = listOf(me, s.authorUid).sorted().joinToString("_")
                                ThreadRepository(cid, me, CryptoHub.crypto, storyScope).sendStoryReply(
                                    text, Message.ReplyRef(s.id, s.authorUid, "", isStatus = true, storyThumb = s.previewUrl, isReaction = reaction),
                                )
                            }.isSuccess
                            if (!ok) toast = "Couldn't send. Check your connection and try again."
                        }
                    },
                    canReply = { s ->
                        if (s.groupId != null) com.fariin.app.data.GroupStoryRules.memberCanReply(s, me, convs)
                        else com.fariin.app.data.StoryPrivacyRules.canReply(s, contacts)
                    },
                    ownerBar = { s -> s.groupId != null && com.fariin.app.data.GroupStoryRules.canSeeViews(s, me, convs) },
                    receipts = receipts,
                    onShown = onShown,
                    onMediaLoaded = { pageLoaded[it] = true },
                    // iOS "..." > Hide Stories: hidden at once, and the viewer closes.
                    onHide = { uid -> com.fariin.app.data.StoryMutes.setHidden(context, uid, true); close() },
                    onMenuAction = { st, key ->
                        when (key) {
                            "save" -> storyScope.launch { toast = StoryMenuActions.save(context, st) }
                            "share" -> storyScope.launch { StoryMenuActions.shareLink(context, st, me) }
                            "report" -> com.fariin.app.data.ReportFlow.ask(com.fariin.app.data.ReportTarget(com.fariin.app.data.ReportKind.STORY,
                                name = if (st.groupId == null) groups.firstOrNull { it.key == st.ringKey }?.name else null,
                                personUid = st.authorUid, storyId = st.id))
                            "viewers" -> editViewersFor = st
                        }
                    },
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

        // The slot's own picture riding the flight: it dissolves into the story on the way out and is
        // fully on before the way home ends, so the landing swaps two identical pictures. From a chat
        // row that is the row's photo and ring, never a story thumbnail: the newest story's thumb flew
        // out and back and sat in the ring until the real photo snapped in (owner's video 2026-11-07).
        hero.coverUrl?.let { url ->
            Box(
                Modifier.layout { m, c ->
                    val r = if (size == IntSize.Zero) Rect.Zero else visibleRect()
                    val p = m.measure(Constraints.fixed(maxOf(1, r.width.roundToInt()), maxOf(1, r.height.roundToInt())))
                    layout(c.maxWidth, c.maxHeight) { p.place(r.left.roundToInt(), r.top.roundToInt()) }
                }.graphicsLayer {
                    alpha = hero.cover * hero.rootAlpha
                    clip = true
                    shape = RoundedCornerShape(androidx.compose.foundation.shape.CornerSize(
                        if (size == IntSize.Zero) 0f else cornerFor(visibleRect())))
                },
            ) {
                val a = hero.anchor
                if (a != null && (hero.cover > 0f || hero.avatar)) {
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
                        if (hero.avatar) {
                            // The row's photo, uncut: the cover's clip is its shape, a circle at home.
                            // No photo: the row's own empty avatar, its grey reaching the corners.
                            Box(Modifier.fillMaxSize().background(com.fariin.app.ui.theme.Fariin.colors.placeholderFill)) {
                                if (url.isEmpty()) Avatar(null, (aw / density).dp, squircle = hero.squircle, kind = hero.avatarKind)
                                else AsyncImage(url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            }
                        } else if (url.startsWith("demo:")) Box(Modifier.fillMaxSize().background(
                            runCatching { Color(android.graphics.Color.parseColor(url.removePrefix("demo:"))) }.getOrDefault(Color.Gray)))
                        else AsyncImage(url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }

        // The chat row's story ring riding the flight round the photo (the row hides it with the photo),
        // so the landing puts back exactly what is already drawn there.
        val ringAnchor = hero.anchor
        if (hero.avatar && ringAnchor != null && hero.ring > 0f) {
            val ringPx = ringAnchor.width * 56f / 48f
            Box(
                Modifier.layout { m, c ->
                    val p = m.measure(Constraints.fixed(maxOf(1, ringPx.roundToInt()), maxOf(1, ringPx.roundToInt())))
                    layout(c.maxWidth, c.maxHeight) {
                        val r = if (size == IntSize.Zero) Rect.Zero else visibleRect()
                        p.place((r.center.x - p.width / 2f).roundToInt(), (r.center.y - p.height / 2f).roundToInt())
                    }
                }.graphicsLayer {
                    val r = visibleRect()
                    val s = maxOf(r.width, r.height) / maxOf(1f, ringAnchor.width)
                    scaleX = s; scaleY = s
                    alpha = hero.ring * hero.rootAlpha
                },
            ) { StoryRing(hero.ringFlags, Modifier.fillMaxSize(), squircle = hero.squircle) }
        }

        // The rest of my ring beside the shrunk story, with every card's count, and the touches above the sheet.
        if (sheetFor != null) ViewersCarousel(sheet, sheetStories, summaries, topInset,
            StorySheetGeometry.ownFooter(bottomInset, density), pageShows = { pageLoaded[it] == true }, onLanded = { jumpTo = it })

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

        sheetStories.firstOrNull { it.id == sheetFor }?.let { s ->
            val openChat = LocalOpenChat.current
            ViewersSheet(
                sheet, s, me, service, who,
                // A sideways swipe anywhere on the sheet pages my stories too (iOS), never a row tap.
                // Remembered, so a recomposition mid-swipe does not restart the gesture under the finger.
                rowSwipe = remember(sheetStories, size, topInset, bottomInset) {
                    RowSwipe(sheet, sheetStories,
                        StorySheetGeometry(size.width.toFloat(), size.height.toFloat(), topInset, density, 1f,
                            StorySheetGeometry.ownFooter(bottomInset, density)).fullDist,
                        onLanded = { jumpTo = it })
                },
                friendUids = remember(conversations) { conversations.filter { !it.isGroup }.map { it.otherUid(me) }.filter { it.isNotEmpty() }.toSet() },
                receipts = receipts, customName = customTitle(s),
                onOpenPerson = { uid -> profileUid = uid },
                // iOS onSendMessage: the viewer closes and the chat with them opens; with no chat yet, their profile.
                onSendMessage = { v ->
                    val conv = convs.firstOrNull { !it.isGroup && it.users.contains(v.uid) }
                    if (conv != null) { onClose(); openChat(conv) } else profileUid = v.uid
                },
                // iOS onBlock: straight away, no question (ChatService.setBlocked).
                onBlock = { v -> storyScope.launch { runCatching { FirestorePeople(me, CryptoHub.crypto).setBlocked(v.uid, true) } } },
                convs = convs,
                knownViews = summaries[s.id]?.count,
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
        if (guideUp && !hero.live) StoryGuideOverlay(onGotIt = { guideUp = false }, onExit = { close() })
        editViewersFor?.let { st ->
            Box(Modifier.fillMaxSize().background(com.fariin.app.ui.theme.Fariin.colors.bg)) {
                ShareStoryAudiencePage(me, convs, myPhoto = groups.firstOrNull { it.key == st.ringKey }?.photoUrl, editing = st,
                    busy = updatingViewers, onBack = { editViewersFor = null }) { choice, _, lists, _ ->
                    updatingViewers = true
                    storyScope.launch {
                        toast = try {
                            service.updateAudience(st, choice, lists, storyConvs(convs),
                                com.fariin.app.data.StoryPrivacyStore.myFriends.value, com.fariin.app.data.StoryPrivacyStore.hidden.value)
                            editViewersFor = null
                            "Viewers updated"
                        } catch (e: StoryService.PostRefused) { e.message }
                        catch (e: Exception) { "Who can see this story hasn't changed. Check your connection and try again." }
                        updatingViewers = false
                    }
                }
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
    canRepost: (Story) -> Boolean,
    onRepost: (Story) -> Unit,
    me: String,
    group: StoryGroup,
    /** Where the story card sits in this page, in pixels (the viewer's `cardOf`). */
    card: Rect,
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
    /** A link on the story was asked for: its tap area's Visit link, or a link in the caption. */
    onLink: (String) -> Unit,
    onRepostAuthor: (String) -> Unit,
    authorPhoto: (String) -> String?,
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
    /** The poster's bar and the viewers sheet: my story, or a group story of a group I own or run (iOS hasOwnerBar). */
    ownerBar: (Story) -> Boolean = { false },
    onHide: (String) -> Unit,
    onNotify: (uid: String, name: String) -> Unit = { _, _ -> },
    /** The "..." menu's other rows (iOS UserView.moreItems): "save", "viewers", "share", "report". */
    onMenuAction: (Story, String) -> Unit = { _, _ -> },
    /** The story's picture or first frame is on screen. */
    onMediaLoaded: (String) -> Unit = {},
    /** A story of this ring to show now: the viewers row landed on it. */
    jumpTo: String? = null,
) {
    val stories = group.stories
    if (stories.isEmpty()) return
    var index by rememberSaveable(group.key) { mutableIntStateOf(StoryRules.startIndex(group)) }
    if (index > stories.lastIndex) index = stories.lastIndex
    // In the same frame the row lands, not one after it: the row hides the landed card's thumbnail
    // in that frame, so the page must already be showing that story (no flash of the old one).
    var tookJump by remember { mutableStateOf<String?>(null) }
    if (jumpTo != tookJump) {
        tookJump = jumpTo
        val i = stories.indexOfFirst { it.id == jumpTo }
        if (i >= 0) index = i
    }
    val story = stories[index]
    val mine = story.authorUid == me
    val poster = mine || ownerBar(story)
    var holding by remember { mutableStateOf(false) }
    var loaded by remember(story.id) { mutableStateOf(false) }
    var progress by remember(story.id) { mutableFloatStateOf(0f) }
    // The ... menu holds the story still while it is open (iOS pauses under its menu).
    var menu by remember { mutableStateOf(false) }
    // A link area tapped once: its Visit link stands over it and the story holds still (iOS armTapArea).
    var armed by remember(story.id) { mutableStateOf<StoryTapTarget?>(null) }
    val stopped = !active || paused || holding || menu || armed != null

    fun goNext() { if (index + 1 < stories.size) index++ else onEnd() }
    fun goBack() { if (index > 0) index-- else onBeforeStart() }

    // Receipt only the story actually on screen and loaded (iOS markSeenItem).
    LaunchedEffect(story.id, active, loaded) { if (active && loaded) onSeen(story) }
    LaunchedEffect(story.id, active) { if (active) onShown(story) }
    LaunchedEffect(story.id, loaded) { if (loaded) onMediaLoaded(story.id) }

    // Photo clock: 5 s of shown time, standing still while paused or held.
    if (!story.isVideo) {
        val stoppedNow by rememberUpdatedState(stopped)
        var elapsed by remember(story.id) { mutableLongStateOf(0L) }
        LaunchedEffect(story.id, active, loaded) {
            if (!active || !loaded) return@LaunchedEffect
            // Back on this ring (owner 2026-11-07): its story starts over. The page kept its clock
            // from before, so a story watched to the end was over at once and threw me to the next
            // person; the reference apps replay the story you come back to.
            elapsed = 0L; progress = 0f
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
    if (poster && active) {
        DisposableEffect(story.id) {
            var job: kotlinx.coroutines.Job? = null
            fun refresh() {
                job?.cancel()
                job = storyScope.launch {
                    service.viewSummary(story.id, who)?.let { summaries[story.id] = it }
                    // The viewers list too, so a swipe up shows the real rows from its first frame (iOS).
                    service.fetchViewers(story.id, who)?.let { StoryViewersCache.put(story.id, it) }
                }
            }
            refresh()
            val reg = service.watchViews(story.id) { refresh() }
            onDispose { reg.remove(); job?.cancel() }
        }
    }

    val slop = LocalViewConfiguration.current.touchSlop
    val focus = LocalFocusManager.current
    Box(
        Modifier.fillMaxSize().pointerInput(story.id, poster) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
                tracker.addPosition(down.uptimeMillis, down.position)
                var dx = 0f; var dy = 0f
                var vertical = false
                // Which way the drag went when it started: only an upward one can open the viewers.
                var upward = false
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
                    if (!vertical && abs(dy) > slop && abs(dy) > abs(dx) * 1.2f) { vertical = true; upward = dy < 0 }
                    if (!ch.pressed) {
                        if (vertical) {
                            val vy = tracker.calculateVelocity().y
                            // My story: a drag that started UP and went 40dp or flicked 300dp/s up opens the
                            // viewers (iOS onSwipeUpEnded). A pull down never does, even if the finger
                            // flicks back up as it lifts (owner 2026-11-03: a short pull down opened them).
                            if (poster && upward && dy < 0 && (dy < -StoryMotion.VIEWERS_DISTANCE * density || vy < -StoryMotion.VIEWERS_VELOCITY * density)) {
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
        // THE CARD (iOS StoryDetailView): from under the status bar, 9:16 or shorter, one 20 corner
        // all round. The status bar and the bar under it are the black wall behind the page.
        Box(
            Modifier.layout { m, c ->
                val p = m.measure(Constraints.fixed(maxOf(1, card.width.roundToInt()), maxOf(1, card.height.roundToInt())))
                layout(c.maxWidth, c.maxHeight) { p.place(card.left.roundToInt(), card.top.roundToInt()) }
            }.clip(RoundedCornerShape(StorySheetGeometry.CARD_RADIUS.dp)).background(Color.Black),
        ) {
            StoryMedia(story, active = active && !stopped, onLoaded = { loaded = true },
                onVideoProgress = { progress = it }, onVideoEnd = { goNext() }, isCurrentPage = active)
            // iOS repostTapArea: a repost's middle column pauses it and shows the original author;
            // their face and name open the profile.
            val taps = remember(story.id) { story.taps + listOfNotNull(story.repostOf?.let { REPOST_TAP }) }
            if (taps.isNotEmpty()) StoryTapLayer(taps, card.width, card.height, armed,
                onArm = { armed = it }, onOpen = { url -> armed = null; onLink(url) },
                person = story.repostOf?.let { r -> (r.authorName.ifEmpty { "Reposted" }) to authorPhoto(r.authorUid) },
                onPerson = { armed = null; story.repostOf?.let { onRepostAuthor(it.authorUid) } })

            // Top shade, bars, header, inside the card: they fade with the drag like the bottom bar
            // (iOS chrome), so a card on its way down is the story alone (owner 2026-11-03).
            Column(
                Modifier.fillMaxWidth()
                    .graphicsLayer { alpha = chrome() }
                    .drawBehind { drawRect(TopScrim, size = androidx.compose.ui.geometry.Size(size.width, 90.dp.toPx())) }
                    .padding(bottom = 18.dp),
            ) {
                // iOS: 8 from the card's top, 16 in from the sides, bars 2 tall and 2 apart.
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    stories.forEachIndexed { i, _ ->
                        val f = when { i < index -> 1f; i == index -> progress; else -> 0f }
                        Box(Modifier.weight(1f).height(2.dp).clip(RoundedCornerShape(1.dp)).background(Color.White.copy(alpha = 0.35f))) {
                            Box(Modifier.fillMaxWidth(f).height(2.dp).background(Color.White))
                        }
                    }
                }
                // iOS UserView: 38 photo, 13 to the name, name 14 semibold, the age 13 at 70% under it;
                // my story keeps the age beside the name and the audience under both.
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(group.photoUrl, 38.dp, squircle = group.key.startsWith(StoryGroup.GROUP_PREFIX),
                            kind = com.fariin.app.ui.components.AvatarKind.of(group.key.startsWith(StoryGroup.GROUP_PREFIX), false))
                        Spacer(Modifier.width(13.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            // iOS UserView: the author's own name (mine too) with the white tick, the
                            // age 13 at 70 %; my story puts the age beside the name and the audience,
                            // with its icon, under both.
                            val name = group.name.ifEmpty { if (mine) "My Story" else "Fariin user" }
                            val age = StoryRules.timeAgo(story.createdAtMs)
                            if (mine) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                    HeaderTick(group.key)
                                    Spacer(Modifier.width(6.dp))
                                    Text(age, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, maxLines = 1)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    AudienceGlyph(story)
                                    Spacer(Modifier.width(5.dp))
                                    Text(customTitle(story) ?: StoryRules.audienceTitle(story), color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp, maxLines = 1)
                                }
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                    HeaderTick(group.key)
                                }
                                Text(age, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, maxLines = 1)
                            }
                        }
                    }
                    run {
                        Box {
                            // iOS StoryMoreGlyph: two white lines (21 and 14.4 long, 1.8 thick, 7.1 apart,
                            // left aligned) in the 44 target iOS gives its menu.
                            Box(Modifier.size(44.dp).clickable { menu = true }.semantics { contentDescription = "More" },
                                contentAlignment = Alignment.Center) {
                                Box(Modifier.size(32.dp).drawBehind {
                                    val t = 1.8.dp.toPx(); val gap = 7.1.dp.toPx()
                                    val left = (size.width - 21.dp.toPx()) / 2f
                                    val top = (size.height - (2 * t + gap)) / 2f
                                    val shade = Color.Black.copy(alpha = 0.3f)
                                    for ((w, y) in listOf(21.dp.toPx() to top, 14.4.dp.toPx() to top + t + gap)) {
                                        drawRoundRect(shade, androidx.compose.ui.geometry.Offset(left, y + 1f), androidx.compose.ui.geometry.Size(w, t),
                                            androidx.compose.ui.geometry.CornerRadius(t / 2f))
                                        drawRoundRect(Color.White, androidx.compose.ui.geometry.Offset(left, y), androidx.compose.ui.geometry.Size(w, t),
                                            androidx.compose.ui.geometry.CornerRadius(t / 2f))
                                    }
                                })
                            }
                            androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                val item: @Composable (String, androidx.compose.ui.graphics.vector.ImageVector, Color?, String) -> Unit = { t, icon, tint, key ->
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = { Text(t, color = tint ?: Color.Unspecified) },
                                        leadingIcon = { Icon(icon, contentDescription = null, tint = tint ?: androidx.compose.material3.LocalContentColor.current, modifier = Modifier.size(22.dp)) },
                                        onClick = { menu = false; onMenuAction(story, key) })
                                }
                                // Fariin's own story offers Save and nothing else (iOS StorySaveSeam).
                                val official = com.fariin.app.data.OfficialStories.isOfficial(story)
                                if (official) item("Save", ImageVector.vectorResource(R.drawable.ic_save_down), null, "save")
                                // My own story (iOS): Save, Edit viewers, and Share when it went to Everyone.
                                if (mine) {
                                    item("Save", ImageVector.vectorResource(R.drawable.ic_save_down), null, "save")
                                    if (com.fariin.app.data.PostedStoriesRules.canEditViewers(story)) item("Edit Viewers", ImageVector.vectorResource(com.fariin.app.R.drawable.ic_gs_members), null, "viewers")
                                    if (com.fariin.app.data.PostedStoriesRules.canShare(story)) item("Share", ImageVector.vectorResource(R.drawable.ic_link_share), null, "share")
                                }
                                // iOS: Get notifications first, for a person's story (not a group's).
                                if (!mine && !official && story.groupId == null) {
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
                                // Somebody else's story that anyone may pass on (the repost rule): Share Story.
                                if (!mine && !official && canRepost(story)) item("Share Story", ImageVector.vectorResource(R.drawable.ic_link_share), null, "share")
                                if (!mine && !official) androidx.compose.material3.DropdownMenuItem(
                                    text = { Text("Hide Stories") },
                                    leadingIcon = { Icon(androidx.compose.ui.res.painterResource(com.fariin.app.R.drawable.ic_eye_off), contentDescription = null, modifier = Modifier.size(22.dp)) },
                                    onClick = { menu = false; onHide(story.authorUid) },
                                )
                                if (!mine && !official) item("Report", ImageVector.vectorResource(com.fariin.app.R.drawable.ic_report), Color(0xFFFF3B30), "report")
                            }
                        }
                    }
                    Box(Modifier.size(44.dp).clickable { onClose() }, contentAlignment = Alignment.Center) {
                        Icon(ImageVector.vectorResource(R.drawable.ic_sf_xmark), contentDescription = "Close", tint = Color.White, modifier = Modifier.size(24.dp))
                    }
                }
            }

            // The caption: 16 white, 16 in from the sides and 14 off the card's bottom edge (iOS).
            if (story.caption.isNotEmpty()) {
                // Its web addresses open on a tap (iOS StoryLinkOpener); the rest of it is the story's.
                val linked = remember(story.caption) {
                    val spans = TextLinks.find(story.caption).filter { it.kind == TextLinks.Kind.WEB }
                    androidx.compose.ui.text.AnnotatedString.Builder(story.caption).apply {
                        for (sp in spans) addLink(androidx.compose.ui.text.LinkAnnotation.Clickable(sp.target,
                            androidx.compose.ui.text.TextLinkStyles(androidx.compose.ui.text.SpanStyle(
                                textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline))) { onLink(sp.target) },
                            sp.start, sp.end)
                    }.toAnnotatedString()
                }
                Text(linked, color = Color.White, fontSize = 16.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.25f), blurRadius = 8f)),
                    modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth()
                        .graphicsLayer { alpha = chrome() }
                        .padding(start = 16.dp, end = 16.dp, bottom = 14.dp))
            }
        }

        // Under the card, on the black: my own bar, or the reply field (which rides the keyboard).
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .graphicsLayer { alpha = chrome() }
                .navigationBarsPadding().imePadding(),
        ) {
            // Only the author deletes (rules): a group's owner and admins get the bar without the bin.
            if (poster) OwnerBar(story, summaries[story.id], onViews = { onOpenViewers(story.id) },
                onDelete = if (mine) ({ onDelete(story) }) else null)
            else if (com.fariin.app.data.OfficialStories.isOfficial(story)) OfficialStoryButton(story, onLink)
            // A followed channel's story (iOS ChannelStoryFooter): nobody messages the channel from it;
            // views, reposts and likes for everyone, and Share.
            else if (com.fariin.app.data.ChannelStories.isFollowerStory(story, me)) ChannelStoryFooter(story, service, receipts,
                onRepost = if (canRepost(story)) ({ onRepost(story) }) else null,
                onLike = { onLike(story, it) }, onShare = { onMenuAction(story, "share") })
            else ReplyBar(story, service, canReply = canReply(story), showLike = receipts, onFocus = onReplyFocus,
                onRepost = if (canRepost(story)) ({ onRepost(story) }) else null,
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
            // The row's thumbnail until the photo is in (iOS shows the preview first), so a story
            // the viewers row lands on is never an empty card while its photo loads.
            var shown by remember(story.id) { mutableStateOf(false) }
            if (!shown && story.thumbUrl.isNotEmpty()) StoryPhoto(story.thumbUrl)
            StoryPhoto(story.mediaUrl, onLoaded = { shown = true; onLoaded() })
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
                Icon(ImageVector.vectorResource(R.drawable.ic_sf_play_fill), contentDescription = null, tint = Color.White, modifier = Modifier.size(40.dp))
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
        // Back on a clip that had ended: from its start, not stuck at the end (same bug as the photo clock).
        LaunchedEffect(active, player) {
            if (active && player.playbackState == Player.STATE_ENDED) player.seekTo(0)
            player.playWhenReady = active
        }
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
        if (!ready) com.fariin.app.ui.components.FariinSpinner(size = 30.dp, stroke = 3.dp, color = Color.White)
    }
}

/** iOS StoryOwnerStats + the trash: viewer faces, "N Views", reposts and hearts when there are any. */
@Composable
private fun OwnerBar(story: Story, summary: StoryViewSummary?, onViews: () -> Unit, onDelete: (() -> Unit)?) {
    val count = summary?.count ?: 0
    val faces = summary?.recent ?: emptyList()
    val full = StoryRules.oneTimeFull(story)
    // The numbers fade in once they are known, instead of "0 Views" turning into the real count
    // a moment after the story opens (owner 2026-11-03).
    // A count that never comes still shows the bar after a moment, so Views can always be opened.
    var waited by remember(story.id) { mutableStateOf(false) }
    LaunchedEffect(story.id) { kotlinx.coroutines.delay(1500); waited = true }
    val ready = summary != null || waited
    val shown by androidx.compose.animation.core.animateFloatAsState(if (ready) 1f else 0f,
        androidx.compose.animation.core.tween(160), label = "ownerStats")
    Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.graphicsLayer { alpha = shown }.clickable(enabled = ready) { onViews() }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (faces.isNotEmpty()) {
                Box(Modifier.width((26 + (faces.size - 1) * 18).dp).height(26.dp)) {
                    faces.forEachIndexed { i, v ->
                        Avatar(v.photoUrl, 26.dp, modifier = Modifier.offset(x = (i * 18).dp).border(1.5.dp, Color.Black, CircleShape))
                    }
                }
            } else {
                Icon(ImageVector.vectorResource(com.fariin.app.R.drawable.ic_views), contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(StoryRules.viewsLabel(count, full), color = Color.White, fontSize = 15.sp, maxLines = 1)
            if ((summary?.repostCount ?: 0) > 0) {
                Spacer(Modifier.width(18.dp))
                Text("Reposts ${summary!!.repostCount}", color = Color.White, fontSize = 15.sp)
            }
            if ((summary?.reactionCount ?: 0) > 0) {
                Spacer(Modifier.width(18.dp))
                Icon(ImageVector.vectorResource(R.drawable.ic_sf_heart), contentDescription = "Likes", tint = Color.White, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(6.dp))
                Text("${summary!!.reactionCount}", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
        }
        Spacer(Modifier.weight(1f))
        if (onDelete != null) Box(Modifier.size(44.dp).clickable { onDelete() }, contentAlignment = Alignment.Center) {
            Icon(ImageVector.vectorResource(R.drawable.ic_sf_trash), contentDescription = "Delete story", tint = Color.White, modifier = Modifier.size(24.dp))
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
    onRepost: (() -> Unit)? = null,
) {
    var text by remember(story.id) { mutableStateOf("") }
    var liked by remember(story.id) { mutableStateOf(false) }
    var focused by remember(story.id) { mutableStateOf(false) }
    LaunchedEffect(story.id, showLike) { if (showLike) liked = !service.myReaction(story.id).isNullOrEmpty() }
    val focus = LocalFocusManager.current
    // The keyboard closed by Back or its own button: the field gives up focus too, or the react
    // bar stayed over a paused story with no keyboard (Pixel 2026-11-05).
    val imeShown = androidx.compose.foundation.layout.WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
    var imeSeen by remember(story.id) { mutableStateOf(false) }
    LaunchedEffect(imeShown, focused) {
        if (imeShown) imeSeen = true
        else if (focused && imeSeen) { imeSeen = false; focus.clearFocus() }
    }
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
        // iOS MessageView: 16 all round, so the bar is 44 + 32 over the home inset (the card ends above it).
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            // iOS ReplyPillStyle: black 38% fill, a 1pt white 50% rim, 22 corners, the words 18 in.
            Box(
                Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(22.dp))
                    .background(Color.Black.copy(alpha = 0.38f))
                    .border(1.dp, Color.White.copy(alpha = if (canReply) 0.5f else 0.2f), RoundedCornerShape(22.dp))
                    .padding(start = 18.dp, end = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (!canReply) {
                    Text(com.fariin.app.data.StoryPrivacyRules.REPLY_LOCKED, color = Color.White.copy(alpha = 0.6f), fontSize = 16.sp, maxLines = 1)
                } else {
                    if (text.isEmpty()) Text("Send message…", color = Color.White, fontSize = 16.sp)
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
            Spacer(Modifier.width(12.dp))
            if (canReply && text.isNotBlank()) {
                // Send in a blue circle, as the chat composer's.
                Box(Modifier.size(44.dp).clip(CircleShape).background(com.fariin.app.ui.theme.Fariin.colors.blue).clickable {
                    onSend(text.trim(), false); text = ""; focus.clearFocus()
                }, contentAlignment = Alignment.Center) {
                    Icon(ImageVector.vectorResource(R.drawable.ic_sf_arrow_up), contentDescription = "Send", tint = Color.White)
                }
            } else if (showLike || onRepost != null) {
                // iOS repost button (ic_repost), left of the heart, only where the story may be passed on.
                if (onRepost != null) Box(Modifier.size(44.dp).clip(CircleShape).clickable { onRepost() }, contentAlignment = Alignment.Center) {
                    Icon(ImageVector.vectorResource(com.fariin.app.R.drawable.ic_repost), contentDescription = "Repost", tint = Color.White, modifier = Modifier.size(26.dp))
                }
                // iOS likeButton: the bare heart on the black, in a 44 target, no plate.
                if (showLike)
                Box(Modifier.size(44.dp).clip(CircleShape)
                    .clickable { liked = !liked; onLike(liked) }, contentAlignment = Alignment.Center) {
                    Icon(if (liked) ImageVector.vectorResource(R.drawable.ic_sf_heart_fill) else ImageVector.vectorResource(R.drawable.ic_sf_heart), contentDescription = if (liked) "Unlike" else "Like",
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
    /** The slot is a chat row's round photo (its ring and photo ride the flight), not a story card. */
    var avatar by mutableStateOf(false)
    var avatarKind by mutableStateOf(com.fariin.app.ui.components.AvatarKind.PERSON)
    var squircle by mutableStateOf(false)
    var ring by mutableFloatStateOf(0f)
    var ringFlags by mutableStateOf(emptyList<Boolean>())
    var live by mutableStateOf(false)
    var visible by mutableStateOf(false)
    var committing = false
    /** The bars' opacity held for a close's flight: they shrink inside the card (iOS), not fade away. */
    var chromeHold by mutableFloatStateOf(-1f)
    var closed = false
    var staged = false
}

/**
 * A rounded rect of [w] by [h] centred on ([cx], [cy]): the flying card's clip, in layer pixels.
 * [below] lets it reach that much further down, over the bar under the card.
 */
private data class CardShape(val cx: Float, val cy: Float, val w: Float, val h: Float, val r: Float, val below: Float = 0f) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val l = cx - w / 2f
        val t = cy - h / 2f
        return Outline.Rounded(RoundRect(l, t, l + w, t + h + below, CornerRadius(r)))
    }
}

/**
 * The link areas on a story (iOS StoryDetailView.tapAreas): invisible, laid over the card where the
 * drawing already is. The first tap arms one and its "Visit link" stands under it (over it near the
 * bottom); a tap on that opens the link, a tap anywhere else puts it away. Only the areas and,
 * while one is armed, the catcher take touches, so every other tap still moves the story on.
 */
@Composable
private fun StoryTapLayer(
    taps: List<StoryTapTarget>,
    cw: Float,
    ch: Float,
    armed: StoryTapTarget?,
    onArm: (StoryTapTarget?) -> Unit,
    onOpen: (String) -> Unit,
    person: Pair<String, String?>? = null,
    onPerson: () -> Unit = {},
) {
    val d = LocalDensity.current
    val floor = with(d) { 24.dp.toPx() }
    taps.forEach { t ->
        val r = StoryTaps.rect(t, cw, ch, floor)
        Box(
            Modifier.offset { IntOffset(r.left.roundToInt(), r.top.roundToInt()) }
                .size(with(d) { r.width.toDp() }, with(d) { r.height.toDp() })
                .graphicsLayer { rotationZ = Math.toDegrees(t.rotation).toFloat() }
                .clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null,
                    onClickLabel = if (t === REPOST_TAP) "Show who posted it" else "Visit link") { if (armed != t) onArm(t) },
        )
    }
    val a = armed ?: return
    Box(Modifier.fillMaxSize().clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { onArm(null) })
    val r = StoryTaps.rect(a, cw, ch, floor)
    val bubbleH = with(d) { (VISIT_ROW + VISIT_TAIL).dp.toPx() }
    val gap = with(d) { 8.dp.toPx() }
    val margin = with(d) { 60.dp.toPx() }
    val above = StoryTaps.bubbleAbove(r, ch, bubbleH, gap, margin)
    val cx = r.center.x.coerceIn(minOf(margin, cw / 2), maxOf(cw - margin, cw / 2))
    val top = if (above) r.top - gap - bubbleH else r.bottom + gap
    val who = if (a === REPOST_TAP) person else null
    VisitLinkBubble(tailDown = above, onTap = { if (who != null) onPerson() else onOpen(a.url) }, person = who,
        modifier = Modifier.layout { m, c ->
            val p = m.measure(c.copy(minWidth = 0, minHeight = 0))
            layout(c.maxWidth, c.maxHeight) { p.place((cx - p.width / 2f).roundToInt(), top.roundToInt()) }
        })
}

private const val VISIT_ROW = 50
/** iOS RepostCard.tapRect: the middle column, off the left and right fifths that move between stories. */
private val REPOST_TAP = StoryTapTarget(x = 0.5, y = 0.5, w = 0.40, h = 0.76, rotation = 0.0, url = "fariin://repost")
private const val VISIT_TAIL = 9

/** iOS VisitLinkBubble: "Visit link >" on a dark rounded body, its tail pointing at the area. */
@Composable
private fun VisitLinkBubble(tailDown: Boolean, onTap: () -> Unit, modifier: Modifier, person: Pair<String, String?>? = null) {
    val shape = remember(tailDown) { VisitBubbleShape(tailDown) }
    Row(
        modifier.shadow(10.dp, shape, ambientColor = Color.Black.copy(alpha = 0.2f), spotColor = Color.Black.copy(alpha = 0.2f))
            .clip(shape).background(Color(0xE61C1C1E)).clickable(onClick = onTap)
            .padding(top = if (tailDown) 0.dp else VISIT_TAIL.dp, bottom = if (tailDown) VISIT_TAIL.dp else 0.dp)
            .height(VISIT_ROW.dp).padding(start = 18.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (person != null) {
            Avatar(person.second, 28.dp)
            Spacer(Modifier.width(10.dp))
            Text(person.first, color = Color.White, fontSize = 17.sp, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp))
        } else Text("Visit link", color = Color.White, fontSize = 17.sp)
        Spacer(Modifier.width(6.dp))
        Icon(ImageVector.vectorResource(R.drawable.ic_sf_chevron_right), contentDescription = null,
            tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(20.dp))
    }
}

/** Body and tail as one outline (iOS BubbleShape): 10 corners, a centred tail 18 wide and 9 tall. */
private class VisitBubbleShape(private val tailDown: Boolean) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val tailH = VISIT_TAIL * density.density
        val tailW = 18 * density.density
        val rad = 10 * density.density
        val bodyTop = if (tailDown) 0f else tailH
        val bodyBottom = if (tailDown) size.height - tailH else size.height
        val path = androidx.compose.ui.graphics.Path().apply {
            addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, bodyTop, size.width, bodyBottom,
                androidx.compose.ui.geometry.CornerRadius(rad)))
            val mid = size.width / 2
            if (tailDown) {
                moveTo(mid - tailW / 2, bodyBottom - 1); lineTo(mid, size.height); lineTo(mid + tailW / 2, bodyBottom - 1)
            } else {
                moveTo(mid - tailW / 2, bodyTop + 1); lineTo(mid, 0f); lineTo(mid + tailW / 2, bodyTop + 1)
            }
            close()
        }
        return Outline.Generic(path)
    }
}

/** An official story's foot (iOS): no reply field or heart; its button when the admins gave it one. */
@Composable
private fun OfficialStoryButton(story: Story, onLink: (String) -> Unit) {
    if (story.buttonText.isEmpty() || story.buttonUrl.isEmpty()) { Spacer(Modifier.height(56.dp)); return }
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(48.dp).clip(CircleShape)
        .background(Color.White).clickable { onLink(story.buttonUrl) }, contentAlignment = Alignment.Center) {
        Text(story.buttonText, color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp))
    }
}

/**
 * A followed channel's story foot (iOS ChannelStoryFooterView, minus views): reposts (a button when
 * reposting is allowed) and likes, from `stories/{id}/meta/public` which the server keeps; the
 * heart likes it (with read receipts on, as iOS); Share on the right.
 */
@Composable
private fun ChannelStoryFooter(story: Story, service: StoryService, receipts: Boolean, onRepost: (() -> Unit)?,
                               onLike: (Boolean) -> Unit, onShare: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var views by remember(story.id) { mutableIntStateOf(0) }
    var likes by remember(story.id) { mutableIntStateOf(0) }
    var reposts by remember(story.id) { mutableIntStateOf(0) }
    var liked by remember(story.id) { mutableStateOf(false) }
    var delta by remember(story.id) { mutableIntStateOf(0) }
    LaunchedEffect(story.id) { if (receipts) liked = !service.myReaction(story.id).isNullOrEmpty() }
    DisposableEffect(story.id) {
        val reg = com.google.firebase.firestore.FirebaseFirestore.getInstance().collection("stories").document(story.id)
            .collection("meta").document("public").addSnapshotListener { snap, _ ->
                val next = (snap?.get("likes") as? Number)?.toInt() ?: 0
                if (next != likes) delta = 0
                views = (snap?.get("views") as? Number)?.toInt() ?: 0
                likes = next
                reposts = (snap?.get("reposts") as? Number)?.toInt() ?: 0
            }
        onDispose { reg.remove() }
    }
    fun n(v: Int) = when { v >= 1_000_000 -> "%.1fM".format(v / 1e6); v >= 1_000 -> "%.1fK".format(v / 1e3); else -> "$v" }.replace(".0", "")
    val toast: (String) -> Unit = { android.widget.Toast.makeText(ctx, it, android.widget.Toast.LENGTH_SHORT).show() }
    Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(22.dp)) {
        // No view count for followers (owner 2026-11-04: "how can they see views? only repost or like").
        // The channel's owner and admins see views on their own viewers bar.
        Row(Modifier.then(if (onRepost != null) Modifier.clickable { onRepost() } else Modifier), verticalAlignment = Alignment.CenterVertically) {
            Icon(ImageVector.vectorResource(com.fariin.app.R.drawable.ic_repost), "Reposts", tint = Color.White, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(7.dp)); Text(n(reposts), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
        }
        Row(Modifier.clickable {
            when {
                !receipts -> toast("Turn on Read Receipts for stories to like them.")
                !story.allowsReplies && !liked -> toast("Likes are turned off for this story.")
                else -> { liked = !liked; delta += if (liked) 1 else -1; onLike(liked) }
            }
        }, verticalAlignment = Alignment.CenterVertically) {
            Icon(if (liked) ImageVector.vectorResource(R.drawable.ic_sf_heart_fill) else ImageVector.vectorResource(R.drawable.ic_sf_heart), if (liked) "Unlike" else "Like",
                tint = if (liked) Color(0xFFFF3B30) else Color.White, modifier = Modifier.size(23.dp))
            Spacer(Modifier.width(7.dp)); Text(n(maxOf(0, likes + delta)), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.weight(1f))
        if (story.audienceLabel == "everyone" && !story.oneTime && !story.captureProtected)
            Box(Modifier.size(44.dp).clickable(onClick = onShare), contentAlignment = Alignment.Center) {
                Icon(ImageVector.vectorResource(R.drawable.ic_link_share), "Share", tint = Color.White, modifier = Modifier.size(22.dp))
            }
    }
}

/** iOS topScrim: the reference's eased eight stops, black 40 % at the top to nothing (scrimStops). */
private val TopScrim = Brush.verticalGradient(
    *listOf(0f to 1f, 0.142857f to 0.959096f, 0.285714f to 0.830299f, 0.428571f to 0.621569f,
        0.571429f to 0.378431f, 0.714286f to 0.169701f, 0.857143f to 0.040904f, 1f to 0f)
        .map { (at, a) -> at to Color.Black.copy(alpha = 0.4f * a) }.toTypedArray(),
)

/** iOS UserView verifiedTick: a white seal at 12 after the name, for a verified author or Fariin. */
@Composable
private fun HeaderTick(key: String) {
    if (key.isEmpty() || key.startsWith(StoryGroup.GROUP_PREFIX)) return
    val official = key == com.fariin.app.data.OfficialChannel.CID
    val ctx = LocalContext.current
    if (!official) LaunchedEffect(key) { com.fariin.app.data.VerifiedPeople.warm(ctx.applicationContext, key) }
    val kinds by com.fariin.app.data.VerifiedPeople.kinds.collectAsState()
    if (!official && com.fariin.app.data.VerifiedPeople.badge(kinds, key) == null) return
    Spacer(Modifier.width(4.dp))
    Icon(ImageVector.vectorResource(R.drawable.ic_sf_checkmark_seal_fill), contentDescription = "Verified", tint = Color.White, modifier = Modifier.size(12.dp))
}

/** iOS audienceBadge glyph, 12 white at 85 %: globe, one, sparkles, folder, people, group or channel. */
@Composable
private fun AudienceGlyph(s: Story) {
    val tint = Color.White.copy(alpha = 0.85f)
    val m = Modifier.size(12.dp)
    when {
        s.oneTime -> Icon(ImageVector.vectorResource(R.drawable.ic_sf_one_circle), null, tint = tint, modifier = m)
        s.audienceLabel == "everyone" -> Icon(ImageVector.vectorResource(R.drawable.ic_sf_globe), null, tint = tint, modifier = m)
        s.audienceLabel == "glowers" -> Icon(ImageVector.vectorResource(R.drawable.ic_sf_sparkles), null, tint = tint, modifier = m)
        s.audienceLabel == "custom" -> Icon(androidx.compose.ui.res.painterResource(com.fariin.app.R.drawable.ic_story_folder), null, tint = tint, modifier = m)
        s.audienceLabel == "group" -> {
            val channel = s.groupId?.let { com.fariin.app.data.GroupStoryRules.isChannel(it, com.fariin.app.data.ChatsNow.list) } == true
            Icon(if (channel) ImageVector.vectorResource(R.drawable.ic_channel_megaphone) else ImageVector.vectorResource(R.drawable.ic_sf_person2_fill), null, tint = tint, modifier = m)
        }
        else -> Icon(ImageVector.vectorResource(R.drawable.ic_sf_person2_fill), null, tint = tint, modifier = m)
    }
}
