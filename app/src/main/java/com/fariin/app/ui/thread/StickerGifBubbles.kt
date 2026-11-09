package com.fariin.app.ui.thread

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
private fun autoplay(): Boolean = PowerSaving.state.collectAsState().value.autoplayGifs

/** A Coil request that animates GIF and WebP (Android 9+ decodes both; older phones GIF only). */
@Composable
private fun animatedRequest(model: Any?, play: Boolean): ImageRequest {
    val ctx = LocalContext.current
    return remember(model, play) {
        ImageRequest.Builder(ctx).data(model).apply {
            if (play) decoderFactory(if (Build.VERSION.SDK_INT >= 28) coil3.gif.AnimatedImageDecoder.Factory() else coil3.gif.GifDecoder.Factory())
        }.build()
    }
}

/**
 * One sticker's art: this phone's copy of the pack (WebP, or .tgs played by Lottie), else the
 * public copy in [url]; one somebody made ([enc] set) is a sealed PNG opened like a photo.
 */
@Composable
internal fun StickerArt(packId: String, stickerId: String, url: String?, enc: com.fariin.app.crypto.EncMeta?,
                        modifier: Modifier, still: Boolean = false) {
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
            // The panel's tiles draw the shipped still (iOS TGSSticker `_still.png`).
            val stillPath = if (still || !play) remember(asset) { StickerCatalog.stillPath(ctx, packId, stickerId) } else null
            if (stillPath != null) {
                AsyncImage("file:///android_asset/$stillPath", null, contentScale = ContentScale.Fit, modifier = modifier)
            } else {
                val json by produceState<String?>(null, asset) {
                    value = withContext(Dispatchers.IO) {
                        runCatching { GZIPInputStream(ctx.assets.open(asset)).bufferedReader().use { it.readText() } }.getOrNull()
                    }
                }
                Box(modifier) {
                    json?.let { j ->
                        val comp by rememberLottieComposition(LottieCompositionSpec.JsonString(j), cacheKey = asset)
                        LottieAnimation(comp, iterations = LottieConstants.IterateForever, isPlaying = play,
                            modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
        else -> AsyncImage(animatedRequest(asset?.let { "file:///android_asset/$it" } ?: url ?: StickerGifRules.stickerPublicUrl(packId, stickerId), play), contentDescription = null,
            contentScale = ContentScale.Fit, modifier = modifier)
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
    if (info) StickerInfoSheet(m) { info = false }
    val art: @Composable () -> Unit = {
        StickerArt(m.stickerPack ?: "", m.stickerId ?: "", m.localPath ?: m.imageUrl, m.enc,
            Modifier.size(w.dp, h.dp).clickable(enabled = m.sendState == null) { info = true })
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
                    meta: @Composable () -> Unit) {
    val (w, h) = StickerGifRules.gifBox(m.width, m.height)
    Column(Modifier.width(w.dp).clip(shape).background(fill)) {
        if (hasHeader) Box(Modifier.padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 4.dp)) { header() }
        Box(Modifier.size(w.dp, h.dp).background(Fariin.colors.secondary.copy(alpha = 0.18f))) {
            AsyncImage(animatedRequest(m.imageUrl, autoplay()), contentDescription = "GIF", imageLoader = com.fariin.app.media.GifLoader.get(LocalContext.current),
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.align(Alignment.BottomEnd).padding(6.dp)) { MetaCapsule { meta() } }
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
internal fun StickerPanel(me: String, onSticker: (StickerRef) -> Unit, onGif: (StickerGifRules.Gif) -> Unit) {
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
    Box(Modifier.fillMaxWidth().height(320.dp).clip(panelShape)
        .background(MaterialTheme.colorScheme.surfaceContainerLow)
        .border(1.dp, Color.White.copy(alpha = if (c.dark) 0.12f else 0.7f), panelShape)) {
        if (gifs) GifTab(me, onGif, hide = hide, onScrolledIn = { barsHidden = it })
        else StickerTab(me, onSticker, hide = hide, onScrolledIn = { barsHidden = it })
        Box(Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(start = 10.dp, end = 10.dp, bottom = 8.dp)
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
    // Where each shelf's header sits in the grid, for the strip's jumps (Favorites has the Create tile too).
    val starts = remember(shelves) { var i = 0; shelves.map { s -> val at = i; i += 1 + s.stickers.size + (if (s.id == "favorites") 1 else 0); at } }
    // Create Sticker (iOS panel.onCreate): Android's photo picker, then the creator page.
    var creating by remember { mutableStateOf<android.net.Uri?>(null) }
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { u -> if (u != null) creating = u }
    creating?.let { u -> StickerCreatorPage(u, onClose = { creating = null }) { made -> creating = null; onSticker(made) } }
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize().scrollHide(onScrolledIn)) {
        LazyVerticalGrid(GridCells.Adaptive(70.dp), state = grid, modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = TOP_BAND.dp, bottom = 60.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for (shelf in shelves) {
                item(key = "h:${shelf.id}", span = { GridItemSpan(maxLineSpan) }) {
                    Text(shelf.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.secondary,
                        modifier = Modifier.height(24.dp).padding(top = 6.dp))
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
                for (s in shelf.stickers) item(key = "${shelf.id}:${s.key}") {
                    Box(Modifier.size(70.dp).clip(RoundedCornerShape(10.dp)).clickable {
                        StickerCatalog.noteUsed(ctx, me, s)
                        onSticker(s)
                        recents = StickerCatalog.recents(ctx, me)
                    }.semanticsLabel(StickerGifRules.stickerLabel(s.emoji)), contentAlignment = Alignment.Center) {
                        StickerArt(s.packId, s.stickerId, null, null, Modifier.size(64.dp), still = s.stickerId.isNotEmpty() && isTgs(ctx, s))
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

private fun isTgs(ctx: android.content.Context, s: StickerRef): Boolean =
    StickerCatalog.assetPath(ctx, s.packId, s.stickerId)?.endsWith(".tgs") == true

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
        val pad = PaddingValues(start = 6.dp, end = 6.dp, top = TOP_BAND.dp, bottom = 60.dp)
        when (category) {
            GifCategory.FAVORITES -> GifGrid(local, pad, me = me, onRecentTab = false, onGif = send, state = grid,
                emptyTitle = "No favourites yet", emptyText = "Hold a GIF and choose Add to Favourites.")
            GifCategory.RECENT -> GifGrid(local, pad, me = me, onRecentTab = true, onGif = send, state = grid,
                emptyTitle = "No recent GIFs", emptyText = "GIFs you send will appear here.")
            else -> GifGrid(fetched, pad, me = me, onGif = send, state = grid)
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
    val play = autoplay()
    val changes by GifLists.changes.collectAsState()
    // iOS grid (2026-11-07): 3 columns edge to edge (no side margin), 2 apart both ways, square
    // corners; the top and bottom padding stay as they were.
    val edge = androidx.compose.foundation.layout.PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())
    LazyVerticalStaggeredGrid(StaggeredGridCells.Fixed(3), modifier = Modifier.fillMaxSize(), contentPadding = edge,
        state = state ?: androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState(),
        horizontalArrangement = Arrangement.spacedBy(2.dp), verticalItemSpacing = 2.dp) {
        val items = list ?: emptyList()
        items(items.size, key = { items[it].id }) { i ->
            val g = items[i]
            // Square tiles, cropped (iOS b5232314d: w x w), not each GIF at its own shape.
            val ratio = 1f
            var menu by remember { mutableStateOf(false) }
            Box {
                AsyncImage(animatedRequest(g.url, play), contentDescription = "GIF", imageLoader = com.fariin.app.media.GifLoader.get(LocalContext.current), contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(ratio).clipToBounds()
                        .background(c.secondary.copy(alpha = 0.15f))
                        .combinedClickable(onLongClick = { menu = true }) { onGif(g) })
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

/** The tall GIF sheet (GifPickerView): "Search GIFs" field, trending until words, "Powered by GIPHY". */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun GifSearchSheet(me: String, onDismiss: () -> Unit, onPick: (StickerGifRules.Gif) -> Unit) {
    val ctx = LocalContext.current
    val c = Fariin.colors
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<StickerGifRules.Gif>?>(null) }
    LaunchedEffect(query) {
        if (query.isNotEmpty()) delay(350)
        results = Giphy.search(ctx, query)
    }
    val focus = remember { FocusRequester() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().height(560.dp).imePadding()) {
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
            Box(Modifier.weight(1f)) { GifGrid(results, PaddingValues(6.dp), query, me = me, onGif = onPick) }
            Text("Powered by GIPHY", fontSize = 11.sp, color = c.secondary,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 6.dp))
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
