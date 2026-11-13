package com.fariin.app.ui.thread

import coil3.gif.repeatCount
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.border
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.combinedClickable
import com.fariin.app.data.GifFavorites
import com.fariin.app.data.GifLists
import com.fariin.app.data.GifRecents
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.rememberLottieComposition
import com.fariin.app.data.Giphy
import com.fariin.app.data.Message
import com.fariin.app.data.StickerCatalog
import com.fariin.app.data.StickerGifRules
import com.fariin.app.data.StickerPackInfo
import com.fariin.app.data.StickerRef
import com.fariin.app.media.AutoDownload
import com.fariin.app.perf.PowerSaving
import com.fariin.app.ui.components.floating
import com.fariin.app.ui.theme.Fariin
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.zip.GZIPInputStream
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.fariin.app.R

// ⛔ STICKERS AND GIFS, after iOS RowStickerView / AnimatedGifView / StickerKeyboardView /
// GifPickerView. A sticker has no bubble (a reply keeps one), sits in 160 x 160 and plays while on
// screen; the time goes in its dark capsule just under it. A GIF is a picture bubble at most
// 240 x 340. Power Saving > "Autoplay GIFs and animated stickers" off = the first frame only.

/** Settings > Power Saving: GIFs and animated stickers move. */
@Composable
private fun autoplay(): Boolean {
    // Only while the app is in front (owner 2026-11-13: in the open-apps view our GIFs kept moving).
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val front = owner.lifecycle.currentStateFlow.collectAsState().value.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
    return PowerSaving.state.collectAsState().value.autoplayGifs && front
}

/** A Coil request that animates GIF and WebP (Android 9+ decodes both; older phones GIF only). */
@Composable
private fun animatedRequest(model: Any?, play: Boolean, plays: Int = 0): ImageRequest {
    val ctx = LocalContext.current
    return remember(model, play, plays) {
        ImageRequest.Builder(ctx).data(model).apply {
            if (play) decoderFactory(if (Build.VERSION.SDK_INT >= 28) coil3.gif.AnimatedImageDecoder.Factory() else coil3.gif.GifDecoder.Factory())
            // [plays] > 0: that many plays, then the last frame (stickers); 0 = loops (GIFs).
            if (play && plays > 0) repeatCount(plays - 1)
        }.build()
    }
}

/** How many times an animated sticker plays each time it appears, then rests (battery). */
private const val STICKER_PLAYS = 3

/**
 * One sticker's art: this phone's copy of the pack (WebP, or .tgs played by Lottie), else the
 * public copy in [url]; one somebody made ([enc] set) is a sealed PNG opened like a photo.
 */
@Composable
internal fun StickerArt(packId: String, stickerId: String, url: String?, enc: com.fariin.app.crypto.EncMeta?,
                        modifier: Modifier, still: Boolean = false,
                        /** Bumped by a tap: the sticker plays its few times again. */
                        replay: Int = 0,
                        /** Mine still on its way: the shipped still, the animation starts once it lands. */
                        holdStill: Boolean = false,
                        /** How many times it plays before resting, and its frames a second (the panel: fewer). */
                        plays: Int = STICKER_PLAYS, fps: Int = 30) {
    val ctx = LocalContext.current
    val play = autoplay() && !still
    val asset = remember(packId, stickerId) { if (packId == "mine") null else StickerCatalog.assetPath(ctx, packId, stickerId) }
    // One I made that this phone holds (Favorites, or my own just sent): drawn from the file.
    val own = remember(packId, stickerId) { if (packId == "mine") com.fariin.app.data.StickerFavorites.png(stickerId) else null }
    when {
        own != null -> AsyncImage(own, contentDescription = null, contentScale = ContentScale.Fit, modifier = modifier)
        packId == "mine" || (asset == null && enc != null) -> {
            val got = rememberGatedFile(url, enc, AutoDownload.Kind.PHOTOS)
            val px = with(LocalDensity.current) { StickerGifRules.STICKER_SIDE.dp.roundToPx() }
            val bmp = rememberBitmap(got.file, px)
            Box(modifier) { if (bmp != null) Image(bmp, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
        }
        asset != null && asset.endsWith(".tgs") -> {
            // The shipped still (iOS TGSSticker `_still.png`): the panel's tiles, a sticker of mine
            // while it is sent (owner 2026-11-10: sending lagged), and every sticker until its
            // animation is parsed, so it never shows an empty box first.
            val stillPath = remember(asset) { StickerCatalog.stillPath(ctx, packId, stickerId) }
            val drawStill: @Composable () -> Unit = {
                if (stillPath != null) AsyncImage("file:///android_asset/$stillPath", null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
            if (still || !play || holdStill) {
                Box(modifier) { drawStill() }
            } else {
                // Parsed once per sticker, off the main thread, and kept (StickerLottie).
                val comp by produceState(StickerLottie.cached(asset), asset) {
                    if (value == null) value = StickerLottie.load(ctx, asset)
                }
                Box(modifier) {
                    val c = comp
                    if (c == null) drawStill()
                    else {
                        // A few plays, then it rests on its last frame (owner 2026-11-10: animated, but not
                        // eating battery). It plays again when it comes back on screen.
                        // Painted into a picture off the main thread, at most 30 times a second, and only
                        // shown here (owner 2026-11-10: smooth like the reference app). Until the first
                        // picture is ready the still stays, so it never blinks.
                        androidx.compose.runtime.key(replay) { StickerFrames(c, plays, fps, onWaiting = drawStill) }
                    }
                }
            }
        }
        else -> androidx.compose.runtime.key(replay) { AsyncImage(animatedRequest(asset?.let { "file:///android_asset/$it" } ?: url ?: StickerGifRules.stickerPublicUrl(packId, stickerId), play, plays = plays), contentDescription = null,
            contentScale = ContentScale.Fit, modifier = modifier) }
    }
}

/**
 * A sticker in the chat (MessageRowLayout sticker branch): no fill, rim or corners, 160 x 160
 * aspect-fit, the time capsule 4 under it on the sticker's side. Sent as a REPLY it keeps its bubble,
 * the quote's colours are made for one.
 */
@Composable
internal fun StickerRow(m: Message, isMe: Boolean, shape: Shape, fill: Color, header: @Composable () -> Unit,
                        meta: @Composable () -> Unit) {
    val (w, h) = StickerGifRules.stickerBox(m.width, m.height)
    val desc = StickerGifRules.stickerLabel(m.stickerEmoji)
    // A tap opens the sticker's sheet (iOS StickerInfoSheet); long press still opens the message menu.
    var info by remember { mutableStateOf(false) }
    // A tap plays it again (it rests after a few plays) and opens its sheet.
    var replay by remember { mutableStateOf(0) }
    if (info) StickerInfoSheet(m) { info = false }
    val art: @Composable () -> Unit = {
        StickerArt(m.stickerPack ?: "", m.stickerId ?: "", m.localPath ?: m.imageUrl, m.enc,
            Modifier.size(w.dp, h.dp).clickable(enabled = m.sendState == null) { replay++; info = true }, replay = replay,
            holdStill = m.sendState != null)
    }
    if (m.replyTo != null) {
        Column(Modifier.clip(shape).background(fill).padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalAlignment = if (isMe) Alignment.End else Alignment.Start) {
            Box(Modifier.widthIn(min = w.dp)) { header() }
            art()
            meta()
        }
        return
    }
    Column(horizontalAlignment = if (isMe) Alignment.End else Alignment.Start,
        modifier = Modifier.semanticsLabel(desc)) {
        art()
        Spacer(Modifier.height(4.dp))
        MetaCapsule { meta() }
    }
}

private fun Modifier.semanticsLabel(text: String): Modifier =
    this.semantics { contentDescription = text }

/**
 * A GIF (iOS isGif bubble): the moving picture fills a bubble at most 240 x 340 at its own aspect,
 * the time in the dark capsule on it; name, Forwarded and the quote above it.
 */
@Composable
internal fun GifRow(m: Message, shape: Shape, fill: Color, hasHeader: Boolean, header: @Composable () -> Unit,
                    onOpen: (() -> Unit)? = null, meta: @Composable () -> Unit) {
    val (w, h) = StickerGifRules.gifBox(m.width, m.height)
    // A tap opens it in the photo viewer (who sent it, when, Share, Save), as a photo opens
    // (owner 2026-11-13); the plain black page only where there is no viewer to hand it to.
    var open by remember { mutableStateOf(false) }
    if (open) GifFullScreen(m.imageUrl) { open = false }
    Column(Modifier.width(w.dp).clip(shape).background(fill)) {
        if (hasHeader) Box(Modifier.padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 4.dp)) { header() }
        Box(Modifier.size(w.dp, h.dp).background(Fariin.colors.secondary.copy(alpha = 0.18f))
            .clickable(enabled = m.sendState == null && !m.imageUrl.isNullOrEmpty(), onClickLabel = "Open GIF") { if (onOpen != null) onOpen() else open = true }) {
            // Played as its small MP4 on the video chip (GifVideo), its still under it; a GIF with no
            // MP4 (not from Giphy) plays as the picture, as before.
            val hasMp4 = remember(m.imageUrl) { GifVideo.mp4Of(m.imageUrl) != null }
            // Not while the chat slides in: starting the video decoder took ~32 ms on the Pixel inside the
            // open (owner's friend's mid-range phone opened chats in ~0.6 s, 2026-11-11). The still slides
            // in; the GIF plays once the page has landed. Read through derivedStateOf so the slide's
            // frames do not recompose every bubble.
            val landed by remember { androidx.compose.runtime.derivedStateOf { !com.fariin.app.ui.components.PageMotion.moving } }
            GifMp4(m.imageUrl, autoplay() && landed, Modifier.fillMaxSize()) {
                if (hasMp4) AsyncImage(com.fariin.app.media.GifLoader.still(LocalContext.current, m.imageUrl ?: ""), contentDescription = "GIF",
                    imageLoader = com.fariin.app.media.GifLoader.get(LocalContext.current), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else AsyncImage(animatedRequest(m.imageUrl, autoplay()), contentDescription = "GIF", imageLoader = com.fariin.app.media.GifLoader.get(LocalContext.current),
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Box(Modifier.align(Alignment.BottomEnd).padding(6.dp)) { MetaCapsule { meta() } }
        }
    }
}

/** A GIF opened from the chat: full screen on black, fitted, playing; a tap or Back closes it. */
@Composable
private fun GifFullScreen(url: String?, onClose: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)
            .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = onClose),
            contentAlignment = Alignment.Center) {
            AsyncImage(animatedRequest(url, true), contentDescription = "GIF", imageLoader = com.fariin.app.media.GifLoader.get(LocalContext.current),
                contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth())
        }
    }
}

// ------------------------------------------------------------------ the panel (StickerKeyboardView)

/**
 * The sticker panel in the keyboard's place (iOS StickerKeyboardView): a "GIFs | Stickers" switch on
 * top, the tab remembered for every chat; Stickers = one grid with a shelf per pack (Recently Used
 * first), 70 tiles, 12 apart, 10 from the sides, the pack strip in a floating bar at the foot;
 * GIFs = trending in two columns, Search opens the tall GIF sheet.
 */
@Composable
internal fun StickerPanel(me: String, onSticker: (StickerRef) -> Unit, onGif: (StickerGifRules.Gif) -> Unit,
    /** The keyboard's height, so the field does not move when the panel and the keys swap (ThreadScreen PanelSlot). */
    height: androidx.compose.ui.unit.Dp = 320.dp) {
    val ctx = LocalContext.current
    val c = Fariin.colors
    var gifs by remember { mutableStateOf(StickerCatalog.lastTabGifs(ctx)) }
    var searchOpen by remember { mutableStateOf(false) }
    // A GIF sent from the search sheet goes to the Recent tab too (iOS pick).
    val sendGif: (StickerGifRules.Gif) -> Unit = { g -> GifRecents.note(ctx, me, g); onGif(g) }
    // iOS StickerKeyboardView (owner 2026-11-09, "like our design in iOS"): a card with rounded top
    // corners; the shelves row on top (packs, or the GIF moods); GIFs | Stickers floating at the
    // foot, 200 wide; on GIFs a round search as tall as the switch at the foot's right. Scrolling
    // into the grid sends the top row up and the switch down; scrolling back brings both.
    var barsHidden by remember { mutableStateOf(false) }
    val hide by androidx.compose.animation.core.animateFloatAsState(if (barsHidden) 1f else 0f,
        androidx.compose.animation.core.tween(220), label = "bars")
    // Rounded all round like the iPhone keyboard (owner 2026-11-09): the top corners, and the bottom
    // ones following the phone's own screen corners, with a thin light rim.
    val panelShape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp, bottomStart = 40.dp, bottomEnd = 40.dp)
    Box(Modifier.fillMaxWidth().height(height).clip(panelShape)
        .background(MaterialTheme.colorScheme.surfaceContainerLow)
        .border(1.dp, Color.White.copy(alpha = if (c.dark) 0.12f else 0.7f), panelShape)) {
        if (gifs) GifTab(me, onGif, hide = hide, onScrolledIn = { barsHidden = it })
        else StickerTab(me, onSticker, hide = hide, onScrolledIn = { barsHidden = it })
        Box(Modifier.fillMaxWidth().align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 10.dp, end = 10.dp, bottom = 8.dp)
            .graphicsLayer { translationY = hide * 60.dp.toPx(); alpha = 1f - hide }) {
            Row(Modifier.align(Alignment.Center).width(200.dp).height(38.dp).floating(RoundedCornerShape(19.dp)).padding(3.dp)) {
                for ((label, isGif) in listOf("GIFs" to true, "Stickers" to false)) {
                    val on = gifs == isGif
                    Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(16.dp))
                        .background(if (on) c.label.copy(alpha = 0.12f) else Color.Transparent)
                        .clickable(enabled = hide < 0.5f) { gifs = isGif; barsHidden = false; StickerCatalog.setLastTabGifs(ctx, isGif) },
                        contentAlignment = Alignment.Center) {
                        Text(label, fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (on) c.label else c.secondary)
                    }
                }
            }
            if (gifs) Box(Modifier.align(Alignment.CenterEnd).size(38.dp).floating(CircleShape)
                .clickable(enabled = hide < 0.5f) { searchOpen = true }, contentAlignment = Alignment.Center) {
                Icon(ImageVector.vectorResource(R.drawable.ic_search_ios), contentDescription = "Search GIFs", tint = c.label, modifier = Modifier.size(18.dp))
            }
        }
    }
    if (searchOpen) GifSearchSheet(me, onDismiss = { searchOpen = false }) { g -> searchOpen = false; sendGif(g) }
}

private const val TOP_BAND = 48

@Composable
private fun StickerTab(me: String, onSticker: (StickerRef) -> Unit, hide: Float = 0f, onScrolledIn: (Boolean) -> Unit = {}) {
    val ctx = LocalContext.current
    val c = Fariin.colors
    // The admin panel's order, on/off and server packs (iOS StickerStore), the shipped order meanwhile.
    val packs by remember { StickerCatalog.live(ctx) }.collectAsState(initial = StickerCatalog.packs(ctx))
    var recents by remember { mutableStateOf(StickerCatalog.recents(ctx, me)) }
    // iOS panel order: Recently Used, Favorites (made and saved), then the packs.
    val favorites by com.fariin.app.data.StickerFavorites.list.collectAsState()
    val shelves: List<StickerPackInfo> = remember(recents, favorites, packs) {
        (if (recents.isEmpty()) emptyList() else listOf(StickerPackInfo("recent", "Recently Used", recents))) +
            listOf(StickerPackInfo("favorites", "Favorites", favorites)) + packs
    }
    // The recent and favourite stickers parsed ahead, so sending one does not parse it on the spot.
    val warmCtx = LocalContext.current
    LaunchedEffect(recents, favorites) {
        withContext(Dispatchers.IO) {
            StickerLottie.warm(warmCtx, (recents + favorites).take(24).mapNotNull { r ->
                runCatching { StickerCatalog.assetPath(warmCtx, r.packId, r.stickerId) }.getOrNull() })
        }
    }
    // Where each shelf's header sits in the grid, for the strip's jumps (Favorites has the Create tile too).
    val starts = remember(shelves) { var i = 0; shelves.map { s -> val at = i; i += 1 + s.stickers.size + (if (s.id == "favorites") 1 else 0); at } }
    // Create Sticker (iOS panel.onCreate): Android's photo picker, then the creator page.
    var creating by remember { mutableStateOf<android.net.Uri?>(null) }
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { u -> if (u != null) creating = u }
    creating?.let { u -> StickerCreatorPage(u, onClose = { creating = null }) { made -> creating = null; onSticker(made) } }
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    // Packs not added show two rows and a "+N" tile that opens the pack (iOS, owner 2026-10-21).
    remember(me) { com.fariin.app.data.StickerPacksAdded.load(ctx, me) }
    val added by com.fariin.app.data.StickerPacksAdded.ids.collectAsState()
    var packOpen by remember { mutableStateOf<StickerPackInfo?>(null) }
    packOpen?.let { p -> StickerPackSheet(p, me, onSend = { s -> StickerCatalog.noteUsed(ctx, me, s); onSticker(s); recents = StickerCatalog.recents(ctx, me) },
        onDismiss = { packOpen = null }) }
    // The grid's columns, as GridCells.Adaptive(70) lays them out (10 each side, 12 apart).
    val cols = maxOf(1, ((androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp - 20 + 12) / (70 + 12)))
    Box(Modifier.fillMaxSize().scrollHide(onScrolledIn)) {
        LazyVerticalGrid(GridCells.Adaptive(70.dp), state = grid, modifier = Modifier.fillMaxSize().gridTopClip { hide },
            contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = TOP_BAND.dp, bottom = 84.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for (shelf in shelves) {
                item(key = "h:${shelf.id}", span = { GridItemSpan(maxLineSpan) }) {
                    // A pack's name opens its sheet (Add to / Remove from my stickers).
                    val isPack = shelf.id != "recent" && shelf.id != "favorites"
                    Text(shelf.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.secondary,
                        modifier = Modifier.height(24.dp).then(if (isPack) Modifier.clickable { packOpen = shelf } else Modifier).padding(top = 6.dp))
                }
                if (shelf.id == "favorites") item(key = "create") {
                    Box(Modifier.size(70.dp).clip(RoundedCornerShape(10.dp)).background(c.label.copy(alpha = 0.06f)).clickable {
                        pick.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }.semanticsLabel("Create Sticker"), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(ImageVector.vectorResource(R.drawable.ic_plus_ios), contentDescription = null, tint = c.secondary, modifier = Modifier.size(26.dp))
                            Text("Create", fontSize = 11.sp, color = c.secondary)
                        }
                    }
                }
                val preview = shelf.id != "recent" && shelf.id != "favorites" && shelf.id !in added && shelf.stickers.size > 2 * cols
                val shown = if (preview) shelf.stickers.take(2 * cols - 1) else shelf.stickers
                for (s in shown) item(key = "${shelf.id}:${s.key}") {
                    Box(Modifier.size(70.dp).clip(RoundedCornerShape(10.dp)).clickable {
                        StickerCatalog.noteUsed(ctx, me, s)
                        onSticker(s)
                        recents = StickerCatalog.recents(ctx, me)
                    }.semanticsLabel(StickerGifRules.stickerLabel(s.emoji)), contentAlignment = Alignment.Center) {
                        // The tiles on screen move (owner 2026-11-10, as iOS), lightly for cheap phones: painted
                        // off the main thread at 20 a second, twice, then at rest; again when scrolled back.
                        StickerArt(s.packId, s.stickerId, null, null, Modifier.size(64.dp), plays = 2, fps = 20)
                    }
                }
                // The second row's last place: "+N", the pack's stickers not shown; a tap opens the pack.
                if (preview) item(key = "more:${shelf.id}") {
                    val more = shelf.stickers.size - shown.size
                    Box(Modifier.size(70.dp).clickable { packOpen = shelf }.semanticsLabel("$more more stickers in ${shelf.title}"),
                        contentAlignment = Alignment.Center) {
                        Box(Modifier.size(58.dp).clip(CircleShape).background(c.label.copy(alpha = 0.06f)), contentAlignment = Alignment.Center) {
                            Text("+$more", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = c.secondary)
                        }
                    }
                }
            }
        }
        // The pack row on top, centred, no glass behind it (iOS, owner 2026-10-31): 28 cells, 24 pictures, 8 apart.
        LazyRow(Modifier.align(Alignment.TopCenter).padding(top = 8.dp, start = 10.dp, end = 10.dp).height(34.dp)
            .graphicsLayer { translationY = -hide * 48.dp.toPx(); alpha = 1f - hide },
            contentPadding = PaddingValues(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            items(shelves.size) { i ->
                val shelf = shelves[i]
                Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).clickable { scope.launch { grid.animateScrollToItem(starts[i]) } },
                    contentAlignment = Alignment.Center) {
                    if (shelf.id == "recent") Icon(ImageVector.vectorResource(R.drawable.ic_sf_clock), contentDescription = "Recently Used", tint = c.secondary, modifier = Modifier.size(20.dp))
                    else if (shelf.id == "favorites") Icon(ImageVector.vectorResource(R.drawable.ic_sf_star_fill), contentDescription = "Favorites", tint = c.secondary, modifier = Modifier.size(20.dp))
                    else shelf.stickers.firstOrNull()?.let { s -> StickerArt(s.packId, s.stickerId, null, null, Modifier.size(24.dp), still = true) }
                }
            }
        }
    }
}


/**
 * GifPickerView's mood row: Favorites and Recent answer from the phone (instant, offline), Trending
 * is the browse default, the rest are ready-made searches.
 */
private enum class GifCategory(val term: String, val label: String) {
    RECENT("", "Recent"), FAVORITES("", "Favorites"), TRENDING("", "Trending"), HAPPY("happy", "Happy"),
    LOVE("love", "Love"), SAD("sad", "Sad"), PARTY("party", "Party"), THUMBS_UP("thumbs up", "Thumbs up");

    val local: Boolean get() = this == FAVORITES || this == RECENT

    val icon: androidx.compose.ui.graphics.vector.ImageVector @Composable get() = when (this) {
        FAVORITES -> ImageVector.vectorResource(R.drawable.ic_star_outline)
        RECENT -> ImageVector.vectorResource(R.drawable.ic_sf_clock)
        TRENDING -> ImageVector.vectorResource(R.drawable.ic_sf_flame)
        HAPPY -> ImageVector.vectorResource(R.drawable.ic_sf_face_smiling)
        LOVE -> ImageVector.vectorResource(R.drawable.ic_sf_heart)
        SAD -> ImageVector.vectorResource(R.drawable.ic_sf_cloud_rain)
        PARTY -> ImageVector.vectorResource(R.drawable.ic_sf_party_popper)
        THUMBS_UP -> ImageVector.vectorResource(R.drawable.ic_sf_hand_thumbsup)
    }
}

@Composable
internal fun GifTab(
    me: String = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: "",
    onGif: (StickerGifRules.Gif) -> Unit,
    hide: Float = 0f,
    onScrolledIn: (Boolean) -> Unit = {},
) {
    val ctx = LocalContext.current
    // Every GIF sent from here (the panel or the attach sheet) goes to the Recent tab first (iOS pick).
    val send: (StickerGifRules.Gif) -> Unit = { g -> GifRecents.note(ctx, me, g); onGif(g) }
    val c = Fariin.colors
    var category by remember { mutableStateOf(GifCategory.TRENDING) }
    val changes by GifLists.changes.collectAsState()
    val local = remember(category, changes) {
        when (category) {
            GifCategory.FAVORITES -> GifFavorites.all(ctx, me)
            GifCategory.RECENT -> GifRecents.all(ctx, me)
            else -> null
        }
    }
    // The tab's last list shows at once (iOS); the fresh one replaces it when it lands, and a failed
    // refresh keeps the old list rather than blanking the grid.
    val fetched by produceState(if (category.local) null else Giphy.cached(ctx, category.term), category) {
        value = if (category.local) null else Giphy.cached(ctx, category.term)
        if (!category.local) {
            val fresh = Giphy.search(ctx, category.term)
            if (fresh.isNotEmpty() || value == null) value = fresh
        }
    }
    val grid = androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState()
    LaunchedEffect(category) { grid.scrollToItem(0); onScrolledIn(false) }
    Box(Modifier.fillMaxSize().scrollHide(onScrolledIn)) {
        val pad = PaddingValues(start = 6.dp, end = 6.dp, top = TOP_BAND.dp, bottom = 84.dp)
        // The GIFs stop under the mood row (and a little under the panel's top edge once the row has
        // slid away): scrolled up, they ran under the half-hidden row and up into the rounded rim,
        // cut off against the message bar (owner 2026-11-10, "bug space").
        Box(Modifier.fillMaxSize().gridTopClip { hide }) {
        when (category) {
            GifCategory.FAVORITES -> GifGrid(local, pad, me = me, onRecentTab = false, onGif = send, state = grid,
                emptyTitle = "No favourites yet", emptyText = "Hold a GIF and choose Add to Favourites.")
            GifCategory.RECENT -> GifGrid(local, pad, me = me, onRecentTab = true, onGif = send, state = grid,
                emptyTitle = "No recent GIFs", emptyText = "GIFs you send will appear here.")
            else -> GifGrid(fetched, pad, me = me, onGif = send, state = grid)
        }
        }
        // The mood row on top, centred, on the panel itself (iOS PanelGifGrid): 28 cells 8 apart, the
        // chosen one on a soft rounded square.
        Row(Modifier.align(Alignment.TopCenter).padding(top = 8.dp).height(34.dp)
            .graphicsLayer { translationY = -hide * 48.dp.toPx(); alpha = 1f - hide },
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            for (cat in GifCategory.entries) {
                val on = cat == category
                Box(Modifier.size(34.dp).clip(RoundedCornerShape(9.dp))
                    .background(if (on) c.label.copy(alpha = 0.1f) else Color.Transparent)
                    .clickable(enabled = hide < 0.5f) { category = cat }, contentAlignment = Alignment.Center) {
                    Icon(cat.icon, contentDescription = cat.label, tint = if (on) c.label else c.secondary, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}

/** A GIPHY GIF's animated WebP at the same size (…/200w.gif -> …/200w.webp); any other link as it is. */
internal fun gridWebp(url: String): String {
    val path = url.substringBefore('?')
    return if (url.contains("giphy.com/media", ignoreCase = true) && path.endsWith(".gif", ignoreCase = true))
        path.dropLast(4) + ".webp" else url
}

/**
 * GifPickerView's masonry: two columns, each GIF at its own aspect, 6 apart, corners 8. A long
 * press offers Add to / Remove from Favourites, and Remove from Recent on the Recent tab only.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun GifGrid(list: List<StickerGifRules.Gif>?, padding: PaddingValues, query: String = "", me: String,
                    onRecentTab: Boolean = false, emptyTitle: String? = null, emptyText: String? = null,
                    state: androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState? = null,
                    onGif: (StickerGifRules.Gif) -> Unit) {
    val c = Fariin.colors
    val ctx = LocalContext.current
    if (list != null && list.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            // GifPickerView: a local tab says what fills it; nothing for words = the system search
            // wording; nothing at all = Could not load.
            if (emptyTitle != null) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(emptyTitle, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label)
                if (emptyText != null) Text(emptyText, fontSize = 14.sp, color = c.secondary, modifier = Modifier.padding(top = 4.dp))
            } else Text(if (query.isBlank()) "Could not load" else "No Results for “${query.trim()}”", fontSize = 15.sp, color = c.secondary)
        }
        return
    }
    // Stills only while the panel slides (ThreadScreen LocalPanelMoving); the videos start once it stands.
    val play = autoplay() && !LocalPanelMoving.current
    val changes by GifLists.changes.collectAsState()
    // iOS grid (2026-11-07): 3 columns edge to edge (no side margin), 2 apart both ways, square
    // corners; the top and bottom padding stay as they were.
    val edge = androidx.compose.foundation.layout.PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())
    LazyVerticalStaggeredGrid(StaggeredGridCells.Fixed(3), modifier = Modifier.fillMaxSize(), contentPadding = edge,
        state = state ?: androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState(),
        horizontalArrangement = Arrangement.spacedBy(2.dp), verticalItemSpacing = 2.dp) {
        val items = list ?: emptyList()
        // Still loading (a new phone has no cached list): grey tiles, so the grid reads as coming,
        // not as empty (the first open on a new account sat blank ~8 s, overnight test 2026-11-10).
        if (list == null) items(12, key = { "wait$it" }) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f).background(c.secondary.copy(alpha = 0.15f)))
        }
        items(items.size, key = { items[it].id }) { i ->
            val g = items[i]
            // Square tiles, cropped (iOS b5232314d: w x w), not each GIF at its own shape.
            val ratio = 1f
            var menu by remember { mutableStateOf(false) }
            Box {
                // Its first frame under it, kept in memory (an animated GIF never is): the panel
                // opened again showed grey tiles for ~0.5 s while every GIF was decoded from disk
                // again (owner's Pixel 2026-11-10). The moving one draws over it once it is ready.
                AsyncImage(com.fariin.app.media.GifLoader.still(LocalContext.current, g.url), contentDescription = null,
                    imageLoader = com.fariin.app.media.GifLoader.get(LocalContext.current), contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize().background(c.secondary.copy(alpha = 0.15f)))
                // The moving one: GIPHY's animated WebP of the same size, drawn as a picture, over the
                // still once decoded. Not the MP4 on a video view (the chat bubbles keep that): a
                // grid of video views made every fast scroll stutter, 3-6 % late frames on the
                // owner's Pixel against ~1 % with WebP, at no more CPU (2026-11-10). The tap area lies over it.
                Box(Modifier.fillMaxWidth().aspectRatio(ratio).clipToBounds()) {
                    if (play) AsyncImage(animatedRequest(remember(g.url) { gridWebp(g.url) }, true), contentDescription = "GIF",
                        imageLoader = com.fariin.app.media.GifLoader.get(LocalContext.current),
                        contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                    Box(Modifier.matchParentSize().semantics { contentDescription = "GIF" }
                        .combinedClickable(onLongClick = { menu = true }) { onGif(g) })
                }
                com.fariin.app.ui.components.GlassDropdown(expanded = menu, onDismissRequest = { menu = false }) {
                    val favourite = remember(changes, g.url) { GifFavorites.contains(ctx, me, g.url) }
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(if (favourite) "Remove from Favourites" else "Add to Favourites") },
                        leadingIcon = { Icon(if (favourite) ImageVector.vectorResource(R.drawable.ic_sf_star_fill) else ImageVector.vectorResource(R.drawable.ic_star_outline), null) },
                        onClick = {
                            menu = false
                            if (favourite) GifFavorites.remove(ctx, me, g.url) else GifFavorites.add(ctx, me, g)
                        })
                    if (onRecentTab) androidx.compose.material3.DropdownMenuItem(
                        text = { Text("Remove from Recent", color = c.red) },
                        leadingIcon = { Icon(ImageVector.vectorResource(R.drawable.ic_sf_clock), null, tint = c.red) },
                        onClick = { menu = false; GifRecents.forget(ctx, me, g.url) })
                }
            }
        }
    }
}

/**
 * The tall GIF sheet (GifPickerView): "Search GIFs" field, trending until words, "Powered by GIPHY".
 *
 * Our own sheet in a full-screen window, not the system bottom sheet (owner 2026-11-13, recorded
 * against the reference app on the Pixel): the system sheet pushed itself above the keyboard, so it
 * jumped up under the status bar on every open of the keyboard and back down on every close. Here
 * the window ignores the keyboard ("adjust nothing"), the sheet stays where it is, and the keyboard
 * slides over its lower GIFs, as the reference app's does. Nearly the whole screen tall.
 */
@Composable
internal fun GifSearchSheet(me: String, onDismiss: () -> Unit, onPick: (StickerGifRules.Gif) -> Unit) {
    val ctx = LocalContext.current
    val c = Fariin.colors
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<StickerGifRules.Gif>?>(null) }
    LaunchedEffect(query) {
        if (query.isNotEmpty()) delay(350)
        results = Giphy.search(ctx, query)
    }
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val screenH = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp
    val tall = (screenH * 0.94f).dp
    val tallPx = with(density) { tall.toPx() }
    // 1 = off screen below, 0 = up. Slides in on open, follows a pull on the handle, slides out.
    val off = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(Unit) { off.animateTo(0f, androidx.compose.animation.core.tween(280, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
    var closing by remember { mutableStateOf(false) }
    fun close(then: () -> Unit = onDismiss) {
        if (closing) return
        closing = true
        scope.launch { off.animateTo(1f, androidx.compose.animation.core.tween(220)); then() }
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = { close() },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val host = androidx.compose.ui.platform.LocalView.current
        androidx.compose.runtime.DisposableEffect(host) {
            (host.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.let { w ->
                w.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
                w.setDimAmount(0f)
                w.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
            }
            onDispose {}
        }
        Box(Modifier.fillMaxSize()) {
            // The dim behind, fading with the sheet; a tap on it closes.
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = (1f - off.value).coerceIn(0f, 1f) }
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { close() })
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(tall)
                .graphicsLayer { translationY = off.value * tallPx }
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(c.bg)) {
                // The handle: a pull down past a third (or a quick flick) closes, else it springs back.
                Box(Modifier.fillMaxWidth().height(28.dp).pointerInput(Unit) {
                    val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
                    detectVerticalDragGestures(
                        onDragStart = { tracker.resetTracking() },
                        onVerticalDrag = { ch, dy ->
                            tracker.addPosition(ch.uptimeMillis, ch.position)
                            scope.launch { off.snapTo((off.value + dy / tallPx).coerceIn(0f, 1f)) }
                        },
                        onDragEnd = {
                            if (off.value > 0.33f || tracker.calculateVelocity().y > 1500f) close()
                            else scope.launch { off.animateTo(0f, androidx.compose.animation.core.spring(stiffness = 500f)) }
                        })
                }, contentAlignment = Alignment.Center) {
                    Box(Modifier.size(36.dp, 5.dp).clip(RoundedCornerShape(3.dp)).background(c.secondary.copy(alpha = 0.4f)))
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(40.dp).clip(RoundedCornerShape(12.dp))
                    .background(c.secondary.copy(alpha = 0.14f)).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(ImageVector.vectorResource(R.drawable.ic_search_ios), null, tint = c.secondary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) Text("Search GIFs", fontSize = 17.sp, color = c.secondary)
                        BasicTextField(query, { query = it }, singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.label), cursorBrush = SolidColor(c.accent),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus))
                    }
                }
                Spacer(Modifier.height(6.dp))
                Box(Modifier.weight(1f)) { GifGrid(results, PaddingValues(6.dp), query, me = me, onGif = { g -> close { onPick(g) } }) }
                Text("Powered by GIPHY", fontSize = 11.sp, color = c.secondary,
                    modifier = Modifier.align(Alignment.CenterHorizontally).navigationBarsPadding().padding(vertical = 6.dp))
            }
        }
    }
}


/**
 * iOS: scrolling down into the grid hides the panel's bars, scrolling back up shows them (and they
 * always show at the very top). Read from the scroll itself (direction of each step), not from item
 * positions, which only change once a whole row has passed.
 */
@Composable
internal fun Modifier.scrollHide(onScrolledIn: (Boolean) -> Unit): Modifier {
    val latest by androidx.compose.runtime.rememberUpdatedState(onScrolledIn)
    val conn = remember {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset,
                                     source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (available.y < -2f) latest(true) else if (available.y > 2f) latest(false)
                return androidx.compose.ui.geometry.Offset.Zero
            }
        }
    }
    return this.nestedScroll(conn)
}

/**
 * The animated stickers' parsed animations, kept for the run (owner 2026-11-10: sending a sticker
 * lagged; each one was unzipped and parsed again on every draw of its row).
 */
internal object StickerLottie {
    /** The shared painters: four background threads for every animated sticker on screen. */
    val painters = java.util.concurrent.Executors.newFixedThreadPool(4) { r ->
        Thread(r, "sticker-paint").apply { priority = Thread.NORM_PRIORITY - 1; isDaemon = true }
    }.asCoroutineDispatcher()

    private val comps = object : android.util.LruCache<String, com.airbnb.lottie.LottieComposition>(40) {}

    fun cached(asset: String): com.airbnb.lottie.LottieComposition? = comps.get(asset)

    suspend fun load(ctx: android.content.Context, asset: String): com.airbnb.lottie.LottieComposition? =
        comps.get(asset) ?: withContext(Dispatchers.IO) {
            runCatching {
                val json = GZIPInputStream(ctx.assets.open(asset)).bufferedReader().use { it.readText() }
                com.airbnb.lottie.LottieCompositionFactory.fromJsonStringSync(json, asset).value
            }.getOrNull()?.also { comps.put(asset, it) }
        }

    @Volatile private var app: android.content.Context? = null
    fun init(c: android.content.Context) { app = c.applicationContext }

    /** The animated stickers of a chat's saved messages, parsed before it opens (ChatHistoryPreloader). */
    fun warmMessages(messages: List<com.fariin.app.data.Message>) {
        val c = app ?: return
        if (!com.fariin.app.ui.components.Motion.on) return
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            val assets = messages.asReversed().asSequence().filter { !it.deleted && it.stickerPack != null && it.stickerId != null && it.stickerPack != "mine" }
                .take(30).mapNotNull { m -> runCatching { StickerCatalog.assetPath(c, m.stickerPack!!, m.stickerId!!) }.getOrNull() }.toSet()
            warm(c, assets)
        }
    }

    /** Parsed ahead, off the main thread, so a send or a scroll does not parse on the spot. */
    fun warm(ctx: android.content.Context, assets: Collection<String>) {
        val todo = assets.filter { it.endsWith(".tgs") && comps.get(it) == null }.take(20)
        if (todo.isEmpty()) return
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { for (a in todo) load(ctx, a) }
    }

    /** [progress] rounded down to a 30 fps step of [c]. */
    fun step(c: com.airbnb.lottie.LottieComposition, progress: Float): Float {
        val frames = c.durationFrames
        if (frames <= 0f) return progress
        val per = (c.frameRate / 30f).coerceAtLeast(1f)
        return (kotlin.math.floor(progress * frames / per) * per / frames).coerceIn(0f, 1f)
    }
}

/**
 * A pack's sheet from the panel's "+N" (iOS StickerPackSheet): its name, Add to / Remove from my
 * stickers, and every sticker as a still; a tap sends it.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun StickerPackSheet(pack: StickerPackInfo, me: String, onSend: (StickerRef) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val c = Fariin.colors
    val added by com.fariin.app.data.StickerPacksAdded.ids.collectAsState()
    val isAdded = pack.id in added
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.bgSecondary, dragHandle = null) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(c.label.copy(alpha = 0.08f)).clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center) {
                    Icon(ImageVector.vectorResource(R.drawable.ic_sf_xmark), contentDescription = "Close", tint = c.label, modifier = Modifier.size(20.dp))
                }
                Text(pack.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = c.label, maxLines = 1,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
            }
            LazyVerticalGrid(GridCells.Adaptive(80.dp), Modifier.fillMaxWidth().heightIn(max = 460.dp).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(pack.stickers.size, key = { pack.stickers[it].key }) { i ->
                    val p = pack.stickers[i]
                    Box(Modifier.size(80.dp).clip(RoundedCornerShape(10.dp)).clickable { onSend(p); onDismiss() },
                        contentAlignment = Alignment.Center) {
                        StickerArt(p.packId, p.stickerId, null, null, Modifier.size(72.dp), still = true)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            // Add shows the pack in full in the panel; Remove takes it back to its two-row preview.
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(50.dp).clip(CircleShape)
                .background(if (isAdded) c.label.copy(alpha = 0.08f) else c.accent)
                .clickable { com.fariin.app.data.StickerPacksAdded.set(ctx, me, pack.id, !isAdded); if (!isAdded) onDismiss() },
                contentAlignment = Alignment.Center) {
                Text(if (isAdded) "Remove from my stickers" else "Add to my stickers", fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    color = if (isAdded) c.red else c.onAccent)
            }
        }
    }
}

/**
 * One animated sticker played the way big messengers do it: each frame is painted into a bitmap on a
 * shared pool of background threads (four), at most 30 frames a second, and the screen only draws the
 * finished bitmap. Three bitmaps per sticker, in turn. It plays [STICKER_PLAYS] times and rests on its
 * last frame; it stops the moment it leaves the screen (its effect is cancelled) and starts again
 * when it comes back.
 */
@Composable
private fun StickerFrames(c: com.airbnb.lottie.LottieComposition, plays: Int, fps: Int, onWaiting: @Composable () -> Unit) {
    var shown by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.coerceIn(1, 1024)
        val h = constraints.maxHeight.coerceIn(1, 1024)
        LaunchedEffect(c, w, h) {
            // Three in turn: the one shown, the one the screen may still be drawing, and the one painted.
            val bufs = Array(3) { android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888) }
            val d = com.airbnb.lottie.LottieDrawable().apply { composition = c }
            // Fit inside the box, centred, as the vector drawing did.
            val b = c.bounds
            val scale = minOf(w / b.width().toFloat(), h / b.height().toFloat())
            val dx = (w - b.width() * scale) / 2f; val dy = (h - b.height() * scale) / 2f
            val frames = c.durationFrames.coerceAtLeast(1f)
            val per = (c.frameRate / fps).coerceAtLeast(1f)
            val stepMs = (1000f * per / c.frameRate).toLong().coerceAtLeast(16L)
            var n = 0
            try {
                for (play in 0 until plays) {
                    var f = 0f
                    // Up to the last frame, not its end: the end is the first pose again, so painting
                    // it showed the same pose twice at every loop seam (a hitch).
                    val last = if (play == plays - 1) frames else frames - per
                    while (f <= last) {
                        val started = android.os.SystemClock.uptimeMillis()
                        val target = bufs[n % 3]
                        val p = (f / frames).coerceIn(0f, 1f)
                        withContext(StickerLottie.painters) {
                            target.eraseColor(android.graphics.Color.TRANSPARENT)
                            val cv = android.graphics.Canvas(target)
                            cv.translate(dx, dy); cv.scale(scale, scale)
                            d.progress = p
                            d.draw(cv)
                        }
                        shown = target.asImageBitmap()
                        n++
                        f += per
                        kotlinx.coroutines.delay((stepMs - (android.os.SystemClock.uptimeMillis() - started)).coerceAtLeast(0L))
                    }
                }
            } finally {
                // The bitmaps go with the effect; the last one shown stays until the composable leaves.
            }
        }
        val img = shown
        if (img == null) onWaiting()
        else Image(img, contentDescription = null, modifier = Modifier.fillMaxSize())
    }
}

/** Clips a panel grid below its top row of controls ([hide] 0 = row shown, 1 = slid away) and 6 dp under the edge. */
private fun Modifier.gridTopClip(hide: () -> Float): Modifier = this.then(Modifier.drawWithContent {
    val top = ((TOP_BAND - 2) * (1f - hide()) + 6f).dp.toPx()
    clipRect(top = top) { this@drawWithContent.drawContent() }
})
