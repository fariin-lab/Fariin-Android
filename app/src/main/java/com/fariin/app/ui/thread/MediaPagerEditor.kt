package com.fariin.app.ui.thread

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fariin.app.media.BitmapDecode
import com.fariin.app.media.MediaRules
import com.fariin.app.media.SentQuality
import com.fariin.app.media.VideoTrim
import com.fariin.app.ui.components.floating
import com.fariin.app.ui.theme.FariinTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The attach sheet's editor for two or more chosen items (iOS MediaApprovalView): the photos and
 * videos as pages in the order they were chosen, swiped left and right. Each photo has its own crop
 * and pen (the single editor's crop page and pen); the rail of thumbnails beside the tools jumps to
 * an item, the current one ringed in blue with an x that drops it from the batch. One HD button,
 * one caption and one Send for the whole batch, which goes as one album.
 *
 * [items] and [videos] are the sheet's own lists: an edit replaces the item in place, a removal
 * takes it out of the sheet too (iOS onRemove), and the last removal closes the editor.
 * A video page plays and pauses on a tap and has its own trimmer above the tools (iOS trimStrip);
 * turning the page or raising the keyboard pauses every clip.
 */
@Composable
internal fun MediaPagerEditor(
    items: SnapshotStateList<Uri>,
    videos: SnapshotStateList<Uri>,
    startPage: Int,
    caption: String,
    onCaption: (String) -> Unit,
    onClose: () -> Unit,
    onSend: (List<Uri>, String) -> Unit,
) {
    var back by remember { mutableStateOf<() -> Unit>({ onClose() }) }
    Dialog(
        onDismissRequest = { back() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        com.fariin.app.ui.components.EdgeToEdgeDarkDialogWindow()
        EditorWindow()
        // iOS: the editor is always dark, whatever the phone is set to.
        FariinTheme(dark = true) {
            PagerBody(items, videos, startPage, caption, onCaption, onClose, onSend) { back = it }
        }
    }
}

@Composable
private fun PagerBody(
    items: SnapshotStateList<Uri>, videos: SnapshotStateList<Uri>, startPage: Int,
    caption: String, onCaption: (String) -> Unit, onClose: () -> Unit,
    onSend: (List<Uri>, String) -> Unit, setBack: (() -> Unit) -> Unit,
) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(startPage.coerceIn(0, (items.size - 1).coerceAtLeast(0))) { items.size }
    val page = pager.currentPage.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    val current = items.getOrNull(page)
    val currentIsPhoto = current != null && current !in videos

    // iOS HD: starts as Settings > Sent Media Quality says, applies to every photo in the batch.
    var hd by remember { mutableStateOf(SentQuality.high(ctx)) }
    var sent by remember { mutableStateOf(false) }
    var penOn by remember { mutableStateOf<Uri?>(null) }
    var cropOn by remember { mutableStateOf<Pair<Uri, Bitmap>?>(null) }
    var busy by remember { mutableStateOf(false) }
    // Each video's trim and play state, kept while the pages turn (iOS trimStart/trimEnd per id).
    val trims = remember { mutableStateMapOf<Uri, TrimState>() }
    fun trimFor(u: Uri) = trims.getOrPut(u) { TrimState(u) }

    // The media sits between the top bar and the bottom chrome; the bottom height is not taken
    // while the keyboard is up, so the pages never move with it (as the single editor).
    var bottomPx by remember { mutableIntStateOf(0) }
    val imeUp = WindowInsets.ime.getBottom(density) > 0
    val ime = WindowInsets.ime
    val imeNow = { ime.getBottom(density) > 0 }
    val topDp = with(density) { WindowInsets.statusBars.getTop(this).toDp() } + (44 + 16).dp
    val bottomDp = with(density) { bottomPx.toDp() } + 12.dp
    // iOS: a page turn, or the keyboard coming up, pauses everything.
    LaunchedEffect(page, imeUp) { trims.values.forEach { it.pause() } }

    fun replace(old: Uri, new: Uri) {
        val i = items.indexOf(old)
        if (i < 0 || new == old) return
        items[i] = new
        val v = videos.indexOf(old)
        if (v >= 0) videos[v] = new
    }
    fun remove(i: Int) {
        val u = items.getOrNull(i) ?: return
        items.removeAt(i); videos.remove(u)
        if (items.isEmpty()) onClose()
    }
    fun openCrop(u: Uri) {
        if (busy) return
        busy = true
        scope.launch {
            val b = withContext(Dispatchers.IO) { BitmapDecode.fromUri(ctx, u, MediaRules.PhotoQuality.HIGH.maxSide) }
            busy = false
            if (b != null) cropOn = u to b
        }
    }
    fun send() {
        // One send per screen (iOS `sent`): a double tap posted the album twice there.
        if (sent || items.isEmpty()) return
        sent = true
        val list = items.toList()
        for (u in list) {
            SentQuality.choose(u, hd)   // videos too: 1080p or 540p (iOS VideoTranscoder)
            if (u in videos) trims[u]?.takeIf { it.trimmed }?.let { VideoTrim.choose(u, it.start, it.end) }
        }
        onSend(list, caption.trim())
    }
    setBack { if (cropOn != null) cropOn = null else onClose() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1,
            key = { i -> items.getOrNull(i)?.toString() ?: "$i" }) { i ->
            val u = items.getOrNull(i)
            val m = Modifier.fillMaxSize().padding(top = topDp, bottom = bottomDp)
            if (u != null && u in videos) {
                val t = trimFor(u)
                LaunchedEffect(u) { t.load(ctx, 8) }
                TrimVideo(t, active = i == page, modifier = m)
            } else if (u != null) PagerPage(u, m)
        }

        // The X, 12 in from the edge (iOS topBar). It closes the editor; the batch stays chosen.
        Box(
            Modifier.statusBarsPadding().padding(start = 12.dp, top = 6.dp).size(44.dp).floating()
                .clickable { onClose() },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White) }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().imePadding()
                .onSizeChanged { if (!imeNow()) bottomPx = it.height }.navigationBarsPadding()
                .padding(top = 8.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // iOS: with the keyboard up only the caption and Send stay.
            // A video page: its trimmer sits right above the tools (iOS).
            val trim = current?.takeIf { it in videos }?.let { trims[it] }
            if (!imeUp && trim != null && trim.thumbs.isNotEmpty() && trim.durationMs > 0) {
                VideoTrimStrip(trim, Modifier.padding(horizontal = 20.dp))
            }
            if (!imeUp) Box(Modifier.padding(horizontal = 16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                    // Crop and pen only make sense on a photo page; HD is for the whole batch.
                    if (currentIsPhoto) {
                        ToolCircle(onClick = { current?.let { openCrop(it) } }) {
                            Icon(Icons.Filled.Crop, contentDescription = "Crop", tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                        ToolCircle(onClick = { penOn = current }) {
                            Icon(Icons.Filled.Draw, contentDescription = "Draw", tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                    }
                    ToolCircle(onClick = { hd = !hd }) {
                        Text("HD", color = if (hd) EditorBlue else Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Rail(items, videos, page, Modifier.weight(1f),
                        onPick = { i -> scope.launch { pager.animateScrollToPage(i) } }, onRemove = { remove(it) })
                }
                QualityHint(hd)
            }
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.Bottom) {
                Box(
                    Modifier.weight(1f).heightIn(min = 40.dp).floating(RoundedCornerShape(20.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (caption.isEmpty()) Text("Add a caption…", color = Color(0xFF8E8E93), fontSize = 17.sp)
                    // One line with the keyboard down, the whole caption with it up (iOS).
                    BasicTextField(caption, { onCaption(it.take(1000)) }, textStyle = TextStyle(color = Color.White, fontSize = 17.sp),
                        cursorBrush = SolidColor(Color.White), maxLines = if (imeUp) 7 else 1, modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.width(10.dp))
                Box(Modifier.size(40.dp).clip(CircleShape).background(EditorBlue).clickable { send() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.ArrowUpward, contentDescription = "Send", tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
        }
        if (busy) Box(Modifier.fillMaxSize().clickable(enabled = false) {})
        cropOn?.let { (u, b) ->
            PhotoCropPage(b, onCancel = { cropOn = null }) { out ->
                cropOn = null
                scope.launch { saveEditedPhoto(ctx, out)?.let { replace(u, it) } }
            }
        }
    }
    // The pen on this photo only (iOS editOnly + startDrawing); the tick puts the drawn copy in its place.
    penOn?.let { u ->
        PhotoDrawEditor(u, editOnly = true, onClose = { penOn = null }, onReturn = { edited -> replace(u, edited) })
    }
}

@Composable
private fun ToolCircle(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.size(40.dp).floating().clickable(onClick = onClick), contentAlignment = Alignment.Center) { content() }
}

/** A photo page, fitted on black (a video page is [TrimVideo]). */
@Composable
private fun PagerPage(uri: Uri, modifier: Modifier) {
    val ctx = LocalContext.current
    val bmp by produceState<ImageBitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching { BitmapDecode.fromUri(ctx, uri, 2048)?.asImageBitmap() }.getOrNull()
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val b = bmp
        if (b == null) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
        else Image(b, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
    }
}

/**
 * The thumbnail rail (iOS rail): 48 dp tiles in the chosen order, a short rail hugging the right
 * edge, videos badged with their length, the current one ringed in blue with an x that drops it.
 * The current tile is scrolled into view as the pages turn.
 */
@Composable
private fun Rail(
    items: List<Uri>, videos: List<Uri>, page: Int, modifier: Modifier,
    onPick: (Int) -> Unit, onRemove: (Int) -> Unit,
) {
    val list = rememberLazyListState()
    LaunchedEffect(page, items.size) {
        if (page !in items.indices) return@LaunchedEffect
        val seen = list.layoutInfo.visibleItemsInfo
        val whole = seen.any { it.index == page && it.offset >= 0 && it.offset + it.size <= list.layoutInfo.viewportEndOffset }
        if (!whole) list.animateScrollToItem((page - 1).coerceAtLeast(0))
    }
    LazyRow(
        modifier.height(60.dp), state = list,
        contentPadding = PaddingValues(top = 6.dp, end = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.Bottom,
    ) {
        itemsIndexed(items, key = { _, u -> u.toString() }) { i, u ->
            RailTile(u, u in videos, i == page, onTap = { onPick(i) }, onRemove = { onRemove(i) })
        }
    }
}

@Composable
private fun RailTile(uri: Uri, isVideo: Boolean, current: Boolean, onTap: () -> Unit, onRemove: () -> Unit) {
    val ctx = LocalContext.current
    val tile = RoundedCornerShape(10.dp)
    val thumb by produceState<Pair<ImageBitmap?, Long>?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            val pic = runCatching {
                if (android.os.Build.VERSION.SDK_INT >= 29) ctx.contentResolver.loadThumbnail(uri, android.util.Size(144, 144), null).asImageBitmap()
                else if (isVideo) android.media.MediaMetadataRetriever().run {
                    try { setDataSource(ctx, uri); getFrameAtTime(0) } finally { release() }
                }?.asImageBitmap()
                else BitmapDecode.fromUri(ctx, uri, 144)?.asImageBitmap()
            }.getOrNull()
            val ms = if (!isVideo) 0L else runCatching {
                android.media.MediaMetadataRetriever().run {
                    try { setDataSource(ctx, uri); extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L }
                    finally { release() }
                }
            }.getOrDefault(0L)
            pic to ms
        }
    }
    Box {
        Box(
            Modifier.size(48.dp).clip(tile).background(Color.Gray.copy(alpha = 0.3f))
                .clickable(onClick = onTap),
        ) {
            val pic = thumb?.first
            if (pic != null) Image(pic, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else if (isVideo) Icon(Icons.Filled.Videocam, null, tint = Color.White, modifier = Modifier.align(Alignment.Center).size(14.dp))
            // The ring is drawn over the picture, as iOS's overlay.
            if (current) Box(Modifier.fillMaxSize().border(2.5.dp, EditorBlue, tile))
            val ms = thumb?.second ?: 0L
            if (isVideo && ms > 0) {
                val t = (ms + 500) / 1000
                Text("%d:%02d".format(t / 60, t % 60), color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.BottomStart).padding(3.dp).clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 4.dp, vertical = 1.dp))
            }
        }
        if (current) Box(
            Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-6).dp).size(16.dp).clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.7f)).border(1.dp, Color.White, CircleShape).clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Close, contentDescription = "Remove", tint = Color.White, modifier = Modifier.size(11.dp)) }
    }
}
