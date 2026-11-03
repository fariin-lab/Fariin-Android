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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
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
    when {
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
        else -> AsyncImage(animatedRequest(asset?.let { "file:///android_asset/$it" } ?: url, play), contentDescription = null,
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
    val art: @Composable () -> Unit = {
        StickerArt(m.stickerPack ?: "", m.stickerId ?: "", m.localPath ?: m.imageUrl, m.enc,
            Modifier.size(w.dp, h.dp))
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
            AsyncImage(animatedRequest(m.imageUrl, autoplay()), contentDescription = "GIF",
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
    Box(Modifier.fillMaxWidth().height(320.dp).background(MaterialTheme.colorScheme.surfaceContainerLow)) {
        if (gifs) GifTab(onGif) else StickerTab(me, onSticker)
        // Top row: Search (GIFs only), the switch in the middle.
        Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
            if (gifs) {
                Box(Modifier.align(Alignment.CenterStart).size(32.dp).floating(CircleShape).clickable { searchOpen = true },
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Search, contentDescription = "Search GIFs", tint = c.label, modifier = Modifier.size(18.dp))
                }
            }
            Row(Modifier.align(Alignment.Center).height(32.dp).floating(RoundedCornerShape(16.dp)).padding(3.dp)) {
                for ((label, isGif) in listOf("GIFs" to true, "Stickers" to false)) {
                    val on = gifs == isGif
                    Box(Modifier.height(26.dp).clip(RoundedCornerShape(13.dp))
                        .background(if (on) c.label.copy(alpha = 0.12f) else Color.Transparent)
                        .clickable { gifs = isGif; StickerCatalog.setLastTabGifs(ctx, isGif) }
                        .padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                        Text(label, fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (on) c.label else c.secondary)
                    }
                }
            }
        }
    }
    if (searchOpen) GifSearchSheet(onDismiss = { searchOpen = false }) { g -> searchOpen = false; onGif(g) }
}

private const val TOP_BAND = 48

@Composable
private fun StickerTab(me: String, onSticker: (StickerRef) -> Unit) {
    val ctx = LocalContext.current
    val c = Fariin.colors
    val packs = remember { StickerCatalog.packs(ctx) }
    var recents by remember { mutableStateOf(StickerCatalog.recents(ctx, me)) }
    val shelves: List<StickerPackInfo> = remember(recents) {
        (if (recents.isEmpty()) emptyList() else listOf(StickerPackInfo("recent", "Recently Used", recents))) + packs
    }
    // Where each shelf's header sits in the grid, for the strip's jumps.
    val starts = remember(shelves) { var i = 0; shelves.map { s -> val at = i; i += 1 + s.stickers.size; at } }
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(GridCells.Adaptive(70.dp), state = grid, modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = TOP_BAND.dp, bottom = 52.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for (shelf in shelves) {
                item(key = "h:${shelf.id}", span = { GridItemSpan(maxLineSpan) }) {
                    Text(shelf.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.secondary,
                        modifier = Modifier.height(24.dp).padding(top = 6.dp))
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
        // The pack strip in its floating bar at the foot (stripAtFoot): 28 cells, 24 pictures, 8 apart.
        LazyRow(Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp, start = 10.dp, end = 10.dp)
            .height(34.dp).floating(RoundedCornerShape(17.dp)),
            contentPadding = PaddingValues(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            items(shelves.size) { i ->
                val shelf = shelves[i]
                Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).clickable { scope.launch { grid.animateScrollToItem(starts[i]) } },
                    contentAlignment = Alignment.Center) {
                    if (shelf.id == "recent") Icon(Icons.Filled.Schedule, contentDescription = "Recently Used", tint = c.secondary, modifier = Modifier.size(20.dp))
                    else shelf.stickers.firstOrNull()?.let { s -> StickerArt(s.packId, s.stickerId, null, null, Modifier.size(24.dp), still = true) }
                }
            }
        }
    }
}

private fun isTgs(ctx: android.content.Context, s: StickerRef): Boolean =
    StickerCatalog.assetPath(ctx, s.packId, s.stickerId)?.endsWith(".tgs") == true

@Composable
private fun GifTab(onGif: (StickerGifRules.Gif) -> Unit) {
    val ctx = LocalContext.current
    val list by produceState<List<StickerGifRules.Gif>?>(null) { value = Giphy.search(ctx, "") }
    GifGrid(list, PaddingValues(start = 6.dp, end = 6.dp, top = TOP_BAND.dp, bottom = 8.dp), onGif = onGif)
}

/** GifPickerView's masonry: two columns, each GIF at its own aspect, 6 apart, corners 8. */
@Composable
private fun GifGrid(list: List<StickerGifRules.Gif>?, padding: PaddingValues, query: String = "", onGif: (StickerGifRules.Gif) -> Unit) {
    val c = Fariin.colors
    if (list != null && list.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            // GifPickerView: nothing for words = the system search wording; nothing at all = Could not load.
            Text(if (query.isBlank()) "Could not load" else "No Results for “${query.trim()}”", fontSize = 15.sp, color = c.secondary)
        }
        return
    }
    val play = autoplay()
    LazyVerticalStaggeredGrid(StaggeredGridCells.Fixed(2), modifier = Modifier.fillMaxSize(), contentPadding = padding,
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalItemSpacing = 6.dp) {
        val items = list ?: emptyList()
        items(items.size, key = { items[it].id }) { i ->
            val g = items[i]
            val ratio = if (g.height > 0) (g.width / g.height).toFloat().coerceIn(0.4f, 3f) else 1f
            AsyncImage(animatedRequest(g.url, play), contentDescription = "GIF", contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(ratio).clip(RoundedCornerShape(8.dp))
                    .background(c.secondary.copy(alpha = 0.15f)).clickable { onGif(g) })
        }
    }
}

/** The tall GIF sheet (GifPickerView): "Search GIFs" field, trending until words, "Powered by GIPHY". */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun GifSearchSheet(onDismiss: () -> Unit, onPick: (StickerGifRules.Gif) -> Unit) {
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
                Icon(Icons.Filled.Search, null, tint = c.secondary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("Search GIFs", fontSize = 17.sp, color = c.secondary)
                    BasicTextField(query, { query = it }, singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.label), cursorBrush = SolidColor(c.blue),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus))
                }
            }
            Box(Modifier.weight(1f)) { GifGrid(results, PaddingValues(6.dp), query, onPick) }
            Text("Powered by GIPHY", fontSize = 11.sp, color = c.secondary,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 6.dp))
        }
    }
}
