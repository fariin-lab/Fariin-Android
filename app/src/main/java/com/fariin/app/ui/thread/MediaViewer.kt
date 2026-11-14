package com.fariin.app.ui.thread

import com.fariin.app.ui.components.GlassDropdown
import android.content.Intent
import android.text.format.DateFormat
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import com.fariin.app.crypto.EncMeta
import com.fariin.app.data.Message
import com.fariin.app.data.ViewerRules
import com.fariin.app.media.ChatMedia
import com.fariin.app.media.GallerySaver
import com.fariin.app.ui.components.FloatingBack
import com.fariin.app.ui.components.FloatingIcon
import com.fariin.app.ui.components.FariinMenu
import com.fariin.app.ui.components.floating
import com.fariin.app.ui.theme.Fariin
import com.fariin.app.ui.theme.FariinTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.fariin.app.R

/** What the full-screen viewer shows. */
sealed class Viewing {
    /**
     * [once]: a view-once photo (cid + message id): no Save, spent the moment it shows. [msg] and
     * [albumIndex]: the message tapped, so the viewer can page its album and show its sender, date
     * and Delete.
     */
    data class Photo(val url: String, val enc: EncMeta?, val once: Pair<String, String>? = null,
                     val msg: Message? = null, val albumIndex: Int? = null,
                     /** A group's or channel's photos to page over, [msg]'s own page among them (chatGallery). */
                     val gallery: List<ViewerRules.Page>? = null) : Viewing()
    data class Video(val url: String, val enc: EncMeta?, val msg: Message? = null, val albumIndex: Int? = null) : Viewing()
    /** Ready-made pages (All Media), starting on [start]. */
    data class Pages(val pages: List<ViewerRules.Page>, val start: Int) : Viewing()
}

/**
 * What a screen lends the viewer: who I am, a name for a sender, the live message behind a page
 * and the chat's own delete question (iOS onDeleteForMe / onDeleteForEveryone; null in All Media).
 */
class ViewerHost(
    val me: String,
    val nameOf: (String) -> String? = { null },
    val message: (String) -> Message? = { null },
    val deleteDialog: (@Composable (m: Message, title: String, onDismiss: () -> Unit, onDone: () -> Unit) -> Unit)? = null,
    /** The pen's Send (iOS onSendEdited): the drawn-on copy goes to this chat as a new photo. Null = no pen. */
    val sendEdited: ((photo: android.net.Uri, caption: String, viewOnce: Boolean) -> Unit)? = null,
    /** The editor's view-once switch: one-to-one chats only, as the attach sheet. */
    val allowViewOnce: Boolean = false,
)

/**
 * A single photo tapped in a group or channel opens in a pager over every photo the chat has loaded
 * (iOS openChatPhoto, owner 2026-11-08). A 1:1 chat, a view-once photo and an album tile (its own
 * album pager) are left as they are.
 */
internal fun withChatGallery(v: Viewing, messages: List<Message>, groupLike: Boolean): Viewing {
    if (!groupLike || v !is Viewing.Photo || v.once != null || v.albumIndex != null) return v
    val m = v.msg ?: return v
    if (m.viewOnce) return v
    val pages = ViewerRules.chatGallery(messages)
    if (pages.size < 2 || pages.none { it.key == m.id }) return v
    return v.copy(gallery = pages)
}

internal fun viewerPages(v: Viewing): Pair<List<ViewerRules.Page>, Int> = when (v) {
    is Viewing.Pages -> v.pages to v.start
    is Viewing.Photo -> v.gallery?.let { g -> v.msg?.let { m -> g.indexOfFirst { it.key == m.id } }?.takeIf { it >= 0 }?.let { g to it } }
        ?: v.msg?.let { ViewerRules.chatPages(it, v.albumIndex, v.once) }?.takeIf { it.first.isNotEmpty() }
        ?: (listOf(ViewerRules.Page(v.url, null, false, v.url, v.enc, once = v.once)) to 0)
    is Viewing.Video -> v.msg?.let { ViewerRules.chatPages(it, v.albumIndex) }?.takeIf { it.first.isNotEmpty() }
        ?: (listOf(ViewerRules.Page(v.url, null, true, v.url, v.enc)) to 0)
}

// iOS numbers (ImageViewerView, MediaDismiss.swift), points read as dp.
/** At or under this zoom a page counts as fit: the drag-close and paging only begin there. */
private const val ZOOM_FIT = 1.02f
/** DirectionalPanGestureRecognizer.decisionDistance: travel before the direction is judged. */
private val DECISION = 12.dp
/** distanceToCompletion: the scrub (shrink to 0.8, fade of the black and the chrome) is done by 88 * 0.8. */
private val SCRUB_DISTANCE = 88.dp
/** UIKit's easeInOut, for the chrome's 0.25 s (photo) and 0.3 s (video) fade. */
internal val IosEaseInOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
/*
 * The landing (0.25 s) and the spring back (0.26 s) are critically damped springs carrying the
 * release velocity. A critically damped spring settles in about 9.2 / sqrt(stiffness) seconds, so
 * these stiffnesses give the same lengths.
 */
private const val CLOSE_STIFFNESS = 1363f
private const val BACK_STIFFNESS = 1260f

@Composable
internal fun MediaViewer(v: Viewing, host: ViewerHost? = null, onClose: () -> Unit) {
    val (pages, start) = remember(v) { viewerPages(v) }
    // Opened from a chat bubble: the picture zooms out of the bubble and back (MediaOrigins). All
    // Media has no bubbles on screen, so it keeps the plain window fade.
    val zooms = v !is Viewing.Pages
    // Back runs the same close as the X: the body fills this in.
    val closer = remember { arrayOfNulls<() -> Unit>(1) }
    Dialog(onDismissRequest = { closer[0]?.invoke() ?: onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        ViewerWindow(zooms)
        // iOS: this screen is always dark, whatever the phone is set to (.environment(\.colorScheme, .dark)).
        FariinTheme(dark = true) { ViewerBody(pages, start, host, onClose, zooms, closer) }
    }
}

/** The dialog's own window: no dim (the drag-close fades to the chat behind) and light bar icons on the black. */
@Composable
private fun ViewerWindow(zooms: Boolean) {
    val view = LocalView.current
    SideEffect {
        (view.parent as? DialogWindowProvider)?.window?.let { w ->
            w.setDimAmount(0f)
            // The zoom paints its own coming and going: the window itself neither fades in nor out.
            if (zooms) w.setWindowAnimations(0)
            // Edge to edge under clear bars: the dialog stopped at the status bar, so a grey band
            // of the page behind showed above the black viewer.
            w.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
            WindowCompat.setDecorFitsSystemWindows(w, false)
            // The window paints under its own bars. Without this flag a dialog window does not, so the
            // phone drew an opaque black status bar for it and faded that out AFTER the viewer had
            // closed: a black strip over the chat for ~0.2 s (owner 2026-11-13, found frame by frame).
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            @Suppress("DEPRECATION") w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
            @Suppress("DEPRECATION") run {
                w.statusBarColor = android.graphics.Color.TRANSPARENT
                w.navigationBarColor = android.graphics.Color.TRANSPARENT
            }
            if (android.os.Build.VERSION.SDK_INT >= 29) { w.isStatusBarContrastEnforced = false; w.isNavigationBarContrastEnforced = false }
            if (android.os.Build.VERSION.SDK_INT >= 28) w.attributes = w.attributes.apply {
                layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            WindowCompat.getInsetsController(w, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }
}

@Composable
private fun ViewerBody(
    pages: List<ViewerRules.Page>, start: Int, host: ViewerHost?, onClose: () -> Unit,
    zooms: Boolean = false, closer: Array<(() -> Unit)?> = arrayOfNulls(1),
) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val media = chatMedia()
    val me = host?.me ?: com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
    val pager = rememberPagerState(initialPage = start.coerceIn(0, (pages.size - 1).coerceAtLeast(0))) { pages.size }
    val page = pages.getOrNull(pager.currentPage) ?: return
    val video = remember { VideoCtl() }

    // Chrome: fades in over 0.15 s linear as the viewer opens; a tap toggles it (0.25 s photo, 0.3 s video).
    var chromeHidden by remember { mutableStateOf(false) }
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val enter by animateFloatAsState(if (entered) 1f else 0f, tween(150, easing = LinearEasing), label = "enter")
    val shown by animateFloatAsState(if (chromeHidden) 0f else 1f, tween(if (page.isVideo) 300 else 250, easing = IosEaseInOut), label = "chrome")
    val toggle = { chromeHidden = !chromeHidden }

    var zoom by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(pager.currentPage) { zoom = 1f }

    // The drag-close: the page follows the finger 1:1, shrinks to 0.8 and the black and the chrome
    // fade with it; read in graphicsLayer only, so a drag frame recomposes nothing.
    var drag by remember { mutableStateOf(Offset.Zero) }
    var dragging by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    val scrubPx = with(density) { SCRUB_DISTANCE.toPx() }
    val scrub = { min(1f, (hypot(drag.x, drag.y) / scrubPx).coerceIn(0f, 1f) / 0.8f) }
    // ---- The zoom: out of the tapped bubble on open, back into the page's own bubble on close ----
    val hostView = LocalView.current
    var box by remember { mutableStateOf(Size.Zero) }
    /** A flight from one pose to another on [flightT]; null = the page follows the drag. */
    var flight by remember { mutableStateOf<Pair<HeroPose, HeroPose>?>(null) }
    val flightT = remember { Animatable(0f) }
    // Until the opening flight is set up (the window's size is known), nothing shows, so the
    // picture never flashes full screen before it leaves the bubble.
    var opened by remember { mutableStateOf(!zooms) }
    fun anchorFor(key: String): Rect? {
        if (!zooms || box == Size.Zero) return null
        val r = MediaOrigins.rects[key] ?: return null
        if (r.width < 1f || r.height < 1f) return null
        val loc = IntArray(2)
        hostView.getLocationOnScreen(loc)
        val w = r.translate(-loc[0].toFloat(), -loc[1].toFloat())
        // Scrolled away under the bars or off the screen: no landing, the fade instead.
        return w.takeIf { it.overlaps(Rect(Offset.Zero, box)) }
    }
    fun aspectOf(p: ViewerRules.Page): Float? {
        val m = p.messageId?.let { id -> host?.message?.invoke(id) } ?: return null
        val (w, h) = if (p.albumParent != null) {
            val a = p.key.substringAfterLast('-').toIntOrNull()?.let { m.album.getOrNull(it) }
            a?.width to a?.height
        } else m.width to m.height
        return if (w != null && h != null && w > 0 && h > 0) (w / h).toFloat() else null
    }
    fun targetFor(p: ViewerRules.Page): HeroPose? = anchorFor(p.key)?.let { a ->
        HeroPose.onto(a, box, aspectOf(p), MediaOrigins.radii[p.key] ?: 0f)
    }
    fun dragPose(): HeroPose {
        val sc = scrub()
        return HeroPose(drag.x, drag.y, 1f - 0.2f * sc, Rect(Offset.Zero, box), 0f, 1f - sc)
    }
    /** What the current page draws this frame. */
    fun pose(): HeroPose = flight?.let { (a, b) -> HeroPose.lerp(a, b, flightT.value) } ?: dragPose()
    val heroSpec = tween<Float>(250, easing = FastOutSlowInEasing)
    if (zooms) {
        // The hole in the chat follows the page on screen, so the landing fills the bubble it left.
        // Only once the flying copy is on screen (one frame after `opened`): hiding it at the tap
        // left the bubble empty, its blue fill showing, for the two frames the viewer took to
        // measure itself (owner's video, 2026-11-05).
        LaunchedEffect(page.key, opened) {
            if (closing || !opened) return@LaunchedEffect
            androidx.compose.runtime.withFrameNanos { }
            MediaOrigins.hiddenKey = page.key
        }
        DisposableEffect(Unit) { onDispose { MediaOrigins.hiddenKey = null } }
        LaunchedEffect(box != Size.Zero) {
            if (opened || box == Size.Zero) return@LaunchedEffect
            val id = HeroPose.identity(box)
            // No bubble to grow out of (scrolled away, an album tile): the picture fades in where it sits.
            val tgt = targetFor(page)
            flight = (tgt ?: id.copy(bg = 0f, alpha = 0f)) to id
            flightT.snapTo(0f)
            opened = true
            // The open runs in the viewer's own scope: this effect was being cancelled a frame in
            // (seen on the Pixel), which left the picture frozen at the bubble and the viewer
            // invisible over the chat. Whatever happens, it ends on the full-screen page.
            scope.launch {
                try { flightT.animateTo(1f, heroSpec) } finally { flight = null }
            }
        }
    }
    /** Close: into the current page's bubble when it is on screen, else a fade where it is. */
    fun closeAnimated() {
        if (closing) return
        if (!zooms || box == Size.Zero) { onClose(); return }
        closing = true
        val from = pose()
        val to = targetFor(page) ?: from.copy(bg = 0f, alpha = 0f)
        scope.launch {
            flight = from to to
            flightT.snapTo(0f)
            flightT.animateTo(1f, if (to.alpha == 0f) tween(200, easing = LinearEasing) else heroSpec)
            // Landed: the bubble shows its picture again BEFORE the viewer goes, two frames, so the
            // chat has drawn it under the copy. Clearing it on dispose left one frame of the empty
            // blue bubble between the viewer leaving and the chat redrawing (owner's video).
            MediaOrigins.hiddenKey = null
            androidx.compose.runtime.withFrameNanos { }
            androidx.compose.runtime.withFrameNanos { }
            onClose()
        }
    }
    closer[0] = ::closeAnimated
    val release: (Offset) -> Unit = { v ->
        dragging = false
        val dy = with(density) { drag.y.toDp().value }
        val vy = with(density) { v.y.toDp().value }
        // iOS MediaDismiss `.ended`: past 120 and not travelling back up fast, or a 1000/s flick.
        val commit = (dy > 120f && vy > -300f) || (vy > 1000f && dy > 20f)
        // The dragged picture lands in its bubble when that is on screen.
        if (commit && targetFor(page) != null) closeAnimated()
        else scope.launch {
            val a = Animatable(drag, Offset.VectorConverter)
            if (commit) {
                closing = true
                // No thumbnail to land on here: iOS's no-destination fallback, the frame shifted down by its height.
                val h = ctx.resources.displayMetrics.heightPixels.toFloat()
                a.animateTo(Offset(0f, h), spring(1f, CLOSE_STIFFNESS), v) { drag = value }
                onClose()
            } else {
                a.animateTo(Offset.Zero, spring(1f, BACK_STIFFNESS), v) { drag = value }
            }
        }
    }

    val restricted = com.fariin.app.data.ChatRestrictions.isOn(LocalThreadCid.current)
    RestrictedWindow(restricted)
    val buttons = ViewerRules.buttons(page, pages, me, host?.deleteDialog != null, chatCanSend = host?.sendEdited != null)
        .let { if (restricted) it.copy(share = false, barSave = false, saveAll = 0, menuSave = false, draw = false) else it }
        // A GIF moves: no pen on it.
        .let { if (GifVideo.mp4Of(page.url) != null || page.url.substringBefore('?').endsWith(".gif", true)) it.copy(draw = false) else it }
    // The pen (iOS penEdit): the photo's plain copy opens in the editor, straight into drawing.
    var drawOn by remember { mutableStateOf<android.net.Uri?>(null) }
    val draw: () -> Unit = {
        scope.launch {
            val f = media.cached(page.url) ?: media.file(page.url, page.enc)
            if (f != null) drawOn = android.net.Uri.fromFile(f)
        }
    }
    var askDelete by remember { mutableStateOf(false) }
    val save = rememberSaver(media)
    val share: () -> Unit = { scope.launch { sharePage(ctx, media, page) } }

    Box(Modifier.fillMaxSize().onSizeChanged { box = Size(it.width.toFloat(), it.height.toFloat()) }
        .graphicsLayer { alpha = if (opened) 1f else 0f }) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = pose().bg }.background(Color.Black))
        HorizontalPager(
            pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, key = { pages[it].key },
            userScrollEnabled = zoom <= ZOOM_FIT && !dragging && !closing,
        ) { i ->
            val p = pages[i]
            val current = i == pager.currentPage
            Box(Modifier.fillMaxSize().dismissDrag(
                canBegin = { current && zoom <= ZOOM_FIT && !closing && flight == null && !pager.isScrollInProgress },
                onStart = { dragging = true }, onMove = { drag = it }, onEnd = release,
            )) {
                Box(Modifier.fillMaxSize().graphicsLayer {
                    if (current) {
                        val ps = pose()
                        translationX = ps.tx; translationY = ps.ty
                        scaleX = ps.scale; scaleY = ps.scale
                        alpha = ps.alpha
                        // In flight: cropped to the bubble's frame and corners (radius in the page's own px).
                        if (flight != null) { shape = HeroClip(ps.clip, ps.radius / ps.scale); clip = true }
                    }
                }) {
                    if (p.isVideo) VideoPage(p, current, video, chromeHidden, onTap = toggle)
                    else PhotoPage(p, current, onTap = toggle, onZoom = { z ->
                        zoom = z
                        // iOS: passing fit hides the chrome once; a tap brings it back.
                        if (z > ZOOM_FIT && !chromeHidden) chromeHidden = true
                    })
                }
            }
        }
        val chromeAlpha = enter * shown
        if (chromeAlpha > 0f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = chromeAlpha * pose().bg }) {
            Column(Modifier.fillMaxSize()) {
                ViewerHeader(ViewerRules.title(page, me, host?.nameOf ?: { null }), dateLine(ctx, page.atMillis), page.isVideo,
                    onBack = { closeAnimated() },
                    hasMenu = buttons.menuDelete || (!buttons.barSave && (buttons.saveAll > 1 || buttons.menuSave))) { dismiss ->
                    // Save is in the ⋯ menu only when the bottom bar has no Save button (it holds
                    // Delete there, on my own photo): two Saves on one screen (owner 2026-11-13).
                    if (buttons.barSave) Unit
                    else if (buttons.saveAll > 1) {
                        MenuRow("Save All (${buttons.saveAll})", painterResource(R.drawable.ic_sf_photo_on_rectangle)) {
                            dismiss(); save(ViewerRules.siblings(pages, page))
                        }
                        MenuRow("Save This", painterResource(R.drawable.ic_save_down)) { dismiss(); save(listOf(page)) }
                    } else if (buttons.menuSave) {
                        MenuRow(if (page.isVideo) "Save Video" else "Save Image", painterResource(R.drawable.ic_save_down)) {
                            dismiss(); save(listOf(page))
                        }
                    }
                    if (buttons.menuDelete) MenuRow("Delete", painterResource(R.drawable.ic_sf_trash), destructive = true) {
                        dismiss(); askDelete = true
                    }
                }
                Spacer(Modifier.weight(1f))
                if (page.isVideo) VideoPanel(video, canShare = buttons.share, onShare = share)
                else {
                    if (pages.size > 1) ThumbStrip(pages, pager)
                    PhotoBar(buttons, onShare = share, onDraw = draw, onDelete = { askDelete = true },
                        onSave = { save(listOf(page)) }, onSaveAll = { save(ViewerRules.siblings(pages, page)) })
                }
            }
        }
        // Paused with the chrome away: a 92 round play mid-screen, so there is always a way back to playing.
        if (page.isVideo && chromeHidden && !video.playing && video.player != null) {
            Box(Modifier.align(Alignment.Center).size(92.dp).floating(CircleShape).clickable { video.togglePlay() },
                contentAlignment = Alignment.Center) {
                Icon(ImageVector.vectorResource(R.drawable.ic_sf_play_fill), "Play", tint = Fariin.colors.label, modifier = Modifier.size(40.dp))
            }
        }
    }

    drawOn?.let { u ->
        val send = host?.sendEdited
        PhotoDrawEditor(u, editOnly = false, onClose = { drawOn = null }, allowViewOnce = host?.allowViewOnce == true,
            onSend = { photo, caption, once ->
                send?.invoke(photo, caption, once)
                // Back to the chat, where the edited copy is sending (iOS dismisses the viewer too).
                drawOn = null; closeAnimated()
            })
    }

    if (askDelete) {
        val m = page.messageId?.let { id -> host?.message?.invoke(id) }
        val dialog = host?.deleteDialog
        if (m == null || dialog == null) askDelete = false
        else dialog(m, if (page.isVideo) "Delete this video?" else "Delete this photo?", { askDelete = false }, { closeAnimated() })
    }
}

/**
 * The drag-down close's gesture, after iOS DirectionalPanGestureRecognizer(.vertical): it waits for
 * 12 of travel from where the finger went down, judges the WHOLE vector, and either takes the touch
 * (mostly vertical, up or down) or lets it go for good to the pager (mostly sideways). Once taken
 * it never gives the touch back, so a drag that drifts sideways still closes.
 */
@Composable
private fun Modifier.dismissDrag(
    canBegin: () -> Boolean, onStart: () -> Unit, onMove: (Offset) -> Unit, onEnd: (Offset) -> Unit,
): Modifier {
    val begin by rememberUpdatedState(canBegin)
    val start by rememberUpdatedState(onStart)
    val move by rememberUpdatedState(onMove)
    val end by rememberUpdatedState(onEnd)
    return pointerInput(Unit) {
        val decision = DECISION.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (!begin()) return@awaitEachGesture
            val tracker = VelocityTracker()
            tracker.addPosition(down.uptimeMillis, down.position)
            var from: Offset? = null
            while (true) {
                val e = awaitPointerEvent()
                val ch = e.changes.firstOrNull { it.id == down.id } ?: break
                if (from == null) {
                    // A second finger is a pinch, a consumed move is the zoomed photo's pan,
                    // a scrolling pager is a page turn: none of them is a close.
                    if (e.changes.size > 1 || ch.isConsumed || !begin()) return@awaitEachGesture
                    if (!ch.pressed) return@awaitEachGesture
                    val d = ch.position - down.position
                    if (hypot(d.x, d.y) < decision) continue
                    if (abs(d.y) <= abs(d.x)) return@awaitEachGesture   // sideways: the pager's
                    from = ch.position
                    start()
                }
                tracker.addPosition(ch.uptimeMillis, ch.position)
                if (!ch.pressed) {
                    val v = tracker.calculateVelocity()
                    end(Offset(v.x, v.y))
                    return@awaitEachGesture
                }
                move(ch.position - from)
                ch.consume()
            }
            // The pointer went away without an up (cancelled): spring back.
            if (from != null) end(Offset.Zero)
        }
    }
}

/** iOS dateLine: `.formatted(date: .numeric, time: .shortened)`, with the phone's own 12/24 h setting. */
private fun dateLine(ctx: android.content.Context, atMillis: Double?): String {
    val at = atMillis?.takeIf { it > 0 } ?: return ""
    val d = Date(at.toLong())
    return DateFormat.getDateFormat(ctx).format(d) + ", " + DateFormat.getTimeFormat(ctx).format(d)
}

/** The floating header: back · name and date in a capsule · the overflow menu (iOS header / topBar). */
@Composable
private fun ViewerHeader(title: String, date: String, video: Boolean, onBack: () -> Unit,
                         hasMenu: Boolean = true, menu: @Composable (dismiss: () -> Unit) -> Unit) {
    val c = Fariin.colors
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp).padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        FloatingBack(onBack, contentDescription = "Close", circle = true)
        Spacer(Modifier.weight(1f))
        if (title.isNotEmpty() || date.isNotEmpty()) Column(
            Modifier.padding(horizontal = 8.dp).floating(CircleShape).heightIn(min = if (video) 44.dp else 0.dp)
                .padding(horizontal = if (video) 24.dp else 16.dp, vertical = if (video) 4.dp else 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (video) 2.dp else 1.dp, Alignment.CenterVertically),
        ) {
            if (title.isNotEmpty()) Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.label,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (date.isNotEmpty()) Text(date, fontSize = if (video) 12.sp else 11.sp, color = c.label, maxLines = 1)
        }
        Spacer(Modifier.weight(1f))
        var open by remember { mutableStateOf(false) }
        // Nothing to offer, no ⋯ (an empty menu opened as a thin bar, owner 2026-11-13); the room is kept.
        if (!hasMenu) { Spacer(Modifier.size(44.dp)); return@Row }
        Box {
            FloatingIcon(ImageVector.vectorResource(R.drawable.ic_more_ios), "More", onClick = { open = true }, circle = true)
            GlassDropdown(open, onDismissRequest = { open = false }, shape = FariinMenu.shape, border = FariinMenu.border,
                containerColor = FariinMenu.container) { menu { open = false } }
        }
    }
}

@Composable
private fun MenuRow(text: String, icon: Painter, destructive: Boolean = false, onClick: () -> Unit) {
    val tint = if (destructive) Fariin.colors.red else Fariin.colors.label
    DropdownMenuItem(text = { Text(text, color = tint) }, onClick = onClick,
        leadingIcon = { Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp)) })
}

/** iOS bottomBar: 48 round buttons, Share at the start, Delete (mine) or Save (theirs) at the end. */
@Composable
private fun PhotoBar(b: ViewerRules.Buttons, onShare: () -> Unit, onDraw: () -> Unit, onDelete: () -> Unit, onSave: () -> Unit, onSaveAll: () -> Unit) {
    val c = Fariin.colors
    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (b.share) BarButton(painterResource(com.fariin.app.R.drawable.ic_link_share), "Share", onClick = onShare)
        Spacer(Modifier.weight(1f))
        // iOS scribble.variable: draw on this photo and send the copy here.
        if (b.draw) {
            BarButton(painterResource(R.drawable.ic_sf_pencil), "Draw", onClick = onDraw)
            Spacer(Modifier.weight(1f))
        }
        if (b.barDelete) BarButton(painterResource(R.drawable.ic_sf_trash), "Delete", tint = c.red, onClick = onDelete)
        else if (b.barSave) {
            // iOS: an album asks which (Save All / Save This) from the same round button.
            var open by remember { mutableStateOf(false) }
            Box {
                BarButton(painterResource(R.drawable.ic_save_down), "Save") { if (b.saveAll > 1) open = true else onSave() }
                GlassDropdown(open, onDismissRequest = { open = false }, shape = FariinMenu.shape, border = FariinMenu.border,
                    containerColor = FariinMenu.container) {
                    MenuRow("Save All (${b.saveAll})", painterResource(R.drawable.ic_sf_photo_on_rectangle)) { open = false; onSaveAll() }
                    MenuRow("Save This", painterResource(R.drawable.ic_save_down)) { open = false; onSave() }
                }
            }
        }
    }
}

@Composable
internal fun BarButton(icon: Painter, label: String, size: androidx.compose.ui.unit.Dp = 48.dp, tint: Color = Fariin.colors.label,
                       enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(size).floating()) {
        Icon(icon, contentDescription = label, tint = if (enabled) tint else tint.copy(alpha = 0.35f), modifier = Modifier.size(22.dp))
    }
}

/**
 * iOS thumbStrip: every page as a small rounded thumb (32, the current one 38 with a white rim),
 * 5 apart, centred over the bottom bar; it follows the page, and a tap jumps there.
 */
@Composable
private fun ThumbStrip(pages: List<ViewerRules.Page>, pager: PagerState) {
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    val density = LocalDensity.current
    LaunchedEffect(pager.currentPage) {
        val viewport = list.layoutInfo.viewportSize.width
        val side = with(density) { 38.dp.toPx() }
        list.animateScrollToItem(pager.currentPage, -((viewport - side) / 2f).toInt())
    }
    LazyRow(
        Modifier.fillMaxWidth().height(44.dp).padding(bottom = 0.dp), state = list,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(pages, key = { _, p -> p.key }) { i, p ->
            val on = i == pager.currentPage
            val side by animateDpAsState(if (on) 38.dp else 32.dp, tween(200, easing = IosEaseInOut), label = "thumb")
            val shape = RoundedCornerShape(8.dp)
            val bmp = rememberBitmap(rememberPlainFile(p.posterUrl ?: p.url, if (p.posterUrl != null) p.posterEnc else p.enc), 120)
            Box(Modifier.size(side).clip(shape).background(Color.White.copy(alpha = 0.15f))
                .then(if (on) Modifier.border(1.5.dp, Color.White, shape) else Modifier)
                .clickable { scope.launch { pager.animateScrollToPage(i, animationSpec = tween(200, easing = IosEaseInOut)) } }) {
                if (bmp != null) Image(bmp, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}

/** Pinch to zoom (1x to 5x), drag while zoomed, double tap for 2.5x and back; a single tap toggles the chrome. */
@Composable
private fun PhotoPage(p: ViewerRules.Page, current: Boolean, onTap: () -> Unit, onZoom: (Float) -> Unit) {
    // A GIF moves here as it does in the chat (owner 2026-11-13: GIFs open in this viewer, with its
    // header, Share and Save): drawn by the GIF loader, a tap toggles the chrome.
    if (GifVideo.mp4Of(p.url) != null || p.url.substringBefore('?').endsWith(".gif", ignoreCase = true)) {
        val tap by rememberUpdatedState(onTap)
        Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { tap() }) }, contentAlignment = Alignment.Center) {
            coil3.compose.AsyncImage(
                coil3.request.ImageRequest.Builder(LocalContext.current).data(p.url).apply {
                    decoderFactory(if (android.os.Build.VERSION.SDK_INT >= 28) coil3.gif.AnimatedImageDecoder.Factory() else coil3.gif.GifDecoder.Factory())
                }.build(),
                contentDescription = "GIF", imageLoader = com.fariin.app.media.GifLoader.get(LocalContext.current),
                contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth())
        }
        return
    }
    val file = rememberPlainFile(p.url, p.enc)
    val ctx = LocalContext.current
    // Full screen: twice the screen's long side keeps a 2x zoom sharp; a low-RAM phone stops at
    // 1440, so a big camera photo never needs 48 MB of pixels.
    val longPx = ctx.resources.displayMetrics.let { maxOf(it.widthPixels, it.heightPixels) }
    val cap = com.fariin.app.media.LowEnd.viewerSide(longPx * 2, com.fariin.app.media.LowEnd.isLowRam(ctx))
    val bmp = rememberBitmap(file, cap, maxLongPx = cap)
    // iOS: a view-once photo is spent the moment the picture is in hand (ViewedOnce.mark, then the
    // server burn); the plain copy this phone opened is removed when the viewer closes.
    val once = p.once
    if (once != null && bmp != null) LaunchedEffect(once) {
        com.fariin.app.media.ViewedOnce.mark(ctx, once.second)
        com.fariin.app.media.OnceBurn.burn(once.first, once.second)
    }
    if (once != null) DisposableEffect(file) { onDispose { file?.delete() } }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val tap by rememberUpdatedState(onTap)
    val zoomed by rememberUpdatedState(onZoom)
    LaunchedEffect(current, scale) { if (current) zoomed(scale) }
    // Paged away: back to fit, as iOS resets each page's zoom.
    LaunchedEffect(current) { if (!current) { scale = 1f; offset = Offset.Zero } }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (bmp == null) {
            com.fariin.app.ui.components.FariinSpinner(size = 30.dp, stroke = 3.dp, color = Color.White)
        } else {
            Image(
                bmp, "Photo", contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { tap() }, onDoubleTap = {
                            if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                        })
                    }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            do {
                                val e = awaitPointerEvent()
                                val zoom = e.calculateZoom()
                                val pan = e.calculatePan()
                                val multi = e.changes.size > 1
                                if (multi || scale > 1f) {
                                    scale = (scale * zoom).coerceIn(1f, 5f)
                                    val maxX = (scale - 1) * size.width / 2
                                    val maxY = (scale - 1) * size.height / 2
                                    offset = if (scale == 1f) Offset.Zero else Offset(
                                        (offset.x + pan.x * scale).coerceIn(-maxX, maxX),
                                        (offset.y + pan.y * scale).coerceIn(-maxY, maxY),
                                    )
                                    e.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                            } while (e.changes.any { it.pressed })
                        }
                    }
                    .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
            )
        }
    }
}

/** Save to the phone's gallery (MediaStore); Android 8 and 9 ask for storage the first time. */
@Composable
private fun rememberSaver(media: ChatMedia): (List<ViewerRules.Page>) -> Unit {
    val ctx = LocalContext.current
    val view = androidx.compose.ui.platform.LocalView.current
    val scope = rememberCoroutineScope()
    var waiting by remember { mutableStateOf<List<ViewerRules.Page>>(emptyList()) }
    var blocked by remember { mutableStateOf(false) }
    val run: (List<ViewerRules.Page>) -> Unit = { pages ->
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                pages.count { p -> media.file(p.url, p.enc)?.let { GallerySaver.save(ctx, it, p.isVideo) } == true }
            }
            val words = when {
                saved == 0 -> "Couldn't save"
                saved == pages.size -> "Saved to Photos"
                else -> "Saved $saved of ${pages.size}"
            }
            if (saved > 0) view.lightTap()   // iOS: a light tap once the photo is saved
            Toast.makeText(ctx, words, Toast.LENGTH_SHORT).show()
        }
    }
    val askStorage = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) run(waiting)
        else if (com.fariin.app.ui.components.PermissionDenied.isBlocked(ctx, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)) blocked = true
        else Toast.makeText(ctx, GallerySaver.PERMISSION_NOTICE, Toast.LENGTH_SHORT).show()
    }
    // Refused for good: the viewer is its own window, so the app's snackbar would sit under it.
    // A small dialog over the viewer offers the way back instead.
    if (blocked) {
        val c = com.fariin.app.ui.theme.Fariin.colors
        com.fariin.app.ui.components.GlassAlert(
            onDismissRequest = { blocked = false },
            title = { androidx.compose.material3.Text("Storage access is off") },
            text = { androidx.compose.material3.Text("Fariin needs storage access to save photos and videos. Turn it on in Settings.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    blocked = false; com.fariin.app.ui.components.PermissionDenied.openAppSettings(ctx)
                }) { androidx.compose.material3.Text("Open Settings", color = c.accent) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { blocked = false }) { androidx.compose.material3.Text("Not Now", color = c.label) }
            },
            containerColor = c.card, titleContentColor = c.label, textContentColor = c.label,
        )
    }
    return { pages ->
        if (pages.any { it.once != null }) Unit   // a view-once photo never leaves the viewer
        else if (GallerySaver.needsPermission(ctx)) { waiting = pages; askStorage.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) }
        else run(pages)
    }
}

/** Android's share sheet with the plain copy (cache/open, served by our FileProvider). Never a view-once photo. */
private suspend fun sharePage(ctx: android.content.Context, media: ChatMedia, p: ViewerRules.Page) {
    if (p.once != null) return
    val intent = withContext(Dispatchers.IO) {
        runCatching {
            val plain = media.file(p.url, p.enc) ?: return@runCatching null
            val dir = File(ctx.cacheDir, "open").also { it.mkdirs() }
            val out = File(dir, "Fariin_${p.key.filter { it.isLetterOrDigit() }}" + if (p.isVideo) ".mp4" else ".jpg")
            plain.copyTo(out, overwrite = true)
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", out)
            Intent(Intent.ACTION_SEND).setType(if (p.isVideo) "video/mp4" else "image/jpeg")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .apply { clipData = android.content.ClipData.newRawUri(null, uri) }
        }.getOrNull()
    }
    if (intent == null) Toast.makeText(ctx, "Couldn't share", Toast.LENGTH_SHORT).show()
    else ctx.startActivity(Intent.createChooser(intent, null))
}

/** The chat's side of the viewer: names, the live message, and the chat's own delete question (DeleteDialog, one set of rules). */
internal fun viewerHost(ui: ThreadUi, actions: ThreadActions, onSendPick: ((List<android.net.Uri>, String, Boolean) -> Unit)? = null): ViewerHost = ViewerHost(
    me = ui.me,
    // iOS shows the chat's name; in a group the sender's own name says more.
    nameOf = { uid -> if (ui.channel != null || !ui.isGroup) ui.title else ui.names[uid] ?: ui.title },
    message = { id -> ui.messages.firstOrNull { it.id == id } },
    deleteDialog = { m, title, dismiss, done ->
        DeleteDialog(m, ui, actions.copy(
            onDeleteForEveryone = { actions.onDeleteForEveryone(it); done() },
            onDeleteForMe = { actions.onDeleteForMe(it); done() },
        ), title = title, onDismiss = dismiss)
    },
    // Held back the same way as the attach sheet's photos: slow mode, then the group's photo rule.
    // Only where the composer is (not a follower of a channel, not blocked, not removed).
    sendEdited = onSendPick?.takeIf {
        val ch = ui.channel
        !(ch != null && ch.role != com.fariin.app.channel.ChannelRules.Role.ADMIN) && !(ui.receipts.iBlocked && !ui.isGroup) && !ui.notAMember
    }?.let { pick -> { photo, caption, once ->
        val doc = ui.groupDoc
        val stop = if (!ui.isGroup || doc.isEmpty()) null else
            com.fariin.app.data.GroupAdmins.slowWait(doc, ui.me,
                ui.messages.lastOrNull { it.authorId == ui.me && !it.isSystem }?.createdAtMillis, System.currentTimeMillis().toDouble())
                ?: com.fariin.app.data.GroupAdmins.heldBack(doc, ui.me, com.fariin.app.data.GroupAdmins.SendKind.PHOTOS)
        if (stop != null) actions.onNotice(stop) else pick(listOf(photo), caption, once && !ui.isGroup)
    } },
    allowViewOnce = !ui.isGroup,
)
