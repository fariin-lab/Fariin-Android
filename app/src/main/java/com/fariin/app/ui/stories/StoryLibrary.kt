package com.fariin.app.ui.stories

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.fariin.app.R
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.data.StoryCameraRules
import com.fariin.app.data.StoryLimits
import com.fariin.app.data.StoryMedia
import com.fariin.app.media.MediaRules
import com.fariin.app.media.RecentMedia
import com.fariin.app.ui.components.FariinSheetDialog
import com.fariin.app.ui.components.FariinSpinner
import com.fariin.app.ui.components.rememberSheetMotion
import com.fariin.app.ui.components.sheetDrag
import com.fariin.app.ui.theme.Fariin
import com.fariin.app.ui.theme.FariinTheme
import com.fariin.app.ui.thread.attachThumb
import com.fariin.app.ui.thread.heroSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The story's own photo library (iOS StoryLibraryPicker in AddStorySheet.swift), opened by the
 * camera's gallery button and its flick up. Never the system photo picker (owner 2026-11-07: it
 * looked like stock Android and was not smooth).
 *
 * As iOS: a sheet up from the bottom, always dark, X top left and a Photos / Collections switch in
 * the middle; Photos is the phone's photos and videos newest first in 4 columns with 2 dp gaps and
 * the video's length bottom right; Collections lists the folders (80 dp cover, 18 corners) and a
 * folder opens its own grid. A tap hands the item to the editor, which opens OVER this sheet, so
 * closing the editor comes back here with the scroll where it was (iOS presents it from the sheet).
 *
 * Access is asked when the sheet first opens. Android 14's "selected photos" works: the grid shows
 * what was granted and a line on top lets the owner pick more. Refused: the iOS "Photos access is
 * off" page, with the button that asks again or, once Android stops asking, opens Settings.
 */
@Composable
fun StoryLibrarySheet(
    /** True while a picked item is being made ready (a video can take a moment): a spinner over the grid. */
    busy: Boolean,
    onPick: (Uri) -> Unit,
    onClose: () -> Unit,
    /** Tick several, then add them all at once (the editor's +); null = a tap opens the editor (the camera's door). */
    multi: StoryLibraryMulti? = null,
    /** Photos only, one tap picks (iOS StoryLibraryPicker photosOnly: a group's or profile photo). */
    photosOnly: Boolean = false,
) {
    val motion = rememberSheetMotion(onClose)
    FariinTheme(dark = true, bars = false) {
        FariinSheetDialog(motion, fill = Color.Black) { m ->
            StoryLibrary(busy, onPick, onClose = { m.close() }, motion = m, multi = multi, photosOnly = photosOnly)
        }
    }
}

/**
 * iOS StoryLibraryPicker(allowsMultiple: true): a tap only ticks, numbered in tap order; the bar at
 * the bottom shows the ticks and "Add N" (or "Done" when something only came off). The five is the
 * POST's: [preselected] is what the post already holds (ticked as this opens, so the + at the limit
 * still opens and shows them), [reserved] what it holds that has no tile (a camera capture).
 * [onApply] gets what was taken off and the new picks, made ready, in tap order; the sheet closes.
 */
class StoryLibraryMulti(
    val preselected: List<Uri>,
    val reserved: Int,
    val onApply: (removed: List<Uri>, added: List<Pair<Uri, StoryMedia.Prepared>>) -> Unit,
    /**
     * PHOTOS SENT AS FILES (Add files > Photos, iOS f46637b3, owner 2026-11-08): set, the picker shows
     * photos only, holds up to [maxPicks], reads "Send N" and hands back the picked uris in tap order,
     * nothing made ready (the file path reads the stored bytes itself). Null for every story door.
     */
    val onSendFiles: ((List<Uri>) -> Unit)? = null,
    val maxPicks: Int = StoryCameraRules.POST_ITEMS,
) {
    val fileMode: Boolean get() = onSendFiles != null
}

/** A batch where some picks would not open (iOS "Couldn't add everything"): the ones that did still go on OK. */
private class FailedBatch(val failed: Int, val removed: List<Uri>, val resolved: List<Pair<Uri, StoryMedia.Prepared>>)

@Composable
private fun StoryLibrary(busy: Boolean, onPick: (Uri) -> Unit, onClose: () -> Unit, motion: com.fariin.app.ui.components.SheetMotion,
                         multi: StoryLibraryMulti?, photosOnly: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The ticks in tap order, seeded from the post; what was there at the open tells a new pick from
    // one still ticked, and a tick turned off is a removal (iOS seedFromPost / initialIDs).
    val picked = remember { mutableStateListOf<Uri>().apply { multi?.preselected?.let { addAll(it.distinct()) } } }
    val initial = remember { multi?.preselected?.toSet() ?: emptySet() }
    val fileMode = multi?.fileMode == true
    val noVideos = fileMode || photosOnly
    val limit = if (fileMode) (multi?.maxPicks ?: 0) else StoryCameraRules.pickLimit(multi?.reserved ?: 0)
    val fresh = picked.filter { it !in initial }
    var tooMany by remember { mutableStateOf(false) }
    var tooLongVideo by remember { mutableStateOf(false) }
    var resolving by remember { mutableStateOf(false) }
    var failedBatch by remember { mutableStateOf<FailedBatch?>(null) }
    val toggle: (RecentMedia.Item) -> Unit = { item ->
        when (StoryCameraRules.tick(picked, item.uri, limit, item.isVideo && StoryLimits.pickTooLong(item.durationMs / 1000.0))) {
            StoryCameraRules.Tick.REMOVED -> { picked.remove(item.uri) }
            StoryCameraRules.Tick.ADDED -> { picked.add(item.uri) }
            StoryCameraRules.Tick.FULL -> { tooMany = true }
            StoryCameraRules.Tick.TOO_LONG -> { tooLongVideo = true }
        }
    }
    // One door for the tile's tap: a tick here, the editor in the camera's door.
    val tap: (RecentMedia.Item) -> Unit = { item -> if (multi != null) toggle(item) else onPick(item.uri) }
    // The long press's lifted picture (iOS StoryPhotoPreview), with the tile's picture to size it.
    var preview by remember { mutableStateOf<Pair<RecentMedia.Item, androidx.compose.ui.graphics.ImageBitmap?>?>(null) }
    val hold: (RecentMedia.Item, androidx.compose.ui.graphics.ImageBitmap?) -> Unit = { item, bmp -> preview = item to bmp }
    // Add: only the new ticks are made ready (iOS addPicked), in tap order, then the whole change at once.
    val addPicked: () -> Unit = add@{
        val m = multi ?: return@add
        if (resolving) return@add
        val still = picked.toSet()
        val removed = initial.filter { it !in still }
        val chosen = picked.filter { it !in initial }
        // Files door: the uris as picked; the file path reads each photo's own bytes.
        m.onSendFiles?.let { send -> if (chosen.isNotEmpty()) send(chosen); onClose(); return@add }
        if (chosen.isEmpty()) { m.onApply(removed, emptyList()); onClose(); return@add }
        resolving = true
        scope.launch {
            val out = ArrayList<Pair<Uri, StoryMedia.Prepared>>()
            for (u in chosen) prepareStoryMedia(context, u).getOrNull()?.let { out += u to it }
            resolving = false
            if (out.size < chosen.size) failedBatch = FailedBatch(chosen.size - out.size, removed, out)
            else { m.onApply(removed, out); onClose() }
        }
    }
    var access by remember { mutableStateOf(RecentMedia.access(context)) }
    var blocked by remember { mutableStateOf(false) }
    // Bumped after every answer: a second "selected photos" pick changes the grid, not the access level.
    var reload by remember { mutableIntStateOf(0) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        access = RecentMedia.access(context)
        blocked = access == RecentMedia.Access.NONE &&
            com.fariin.app.ui.components.PermissionDenied.isBlocked(context, RecentMedia.permissions().first())
        reload++
    }
    LaunchedEffect(Unit) { if (access == RecentMedia.Access.NONE) ask.launch(RecentMedia.permissions()) }
    // Back from Settings (iOS willEnterForeground: store.load()): read the access again.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                val now = RecentMedia.access(context)
                if (now != access) { access = now; if (now != RecentMedia.Access.NONE) blocked = false; reload++ }
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    var tab by remember { mutableIntStateOf(0) }          // 0 = Photos, 1 = Collections
    var album by remember { mutableStateOf<RecentMedia.Album?>(null) }
    val none = access == RecentMedia.Access.NONE
    val photos by produceState(emptyList<RecentMedia.Item>(), access, reload) {
        value = if (none) emptyList() else withContext(Dispatchers.IO) {
            RecentMedia.load(context, limit = 2000).let { l -> if (noVideos) l.filter { !it.isVideo } else l }
        }
    }
    val albums by produceState<List<RecentMedia.Album>?>(null, access, reload, tab == 1) {
        if (tab == 1 && !none) value = withContext(Dispatchers.IO) { RecentMedia.albums(context) }
    }
    val inAlbum by produceState(emptyList<RecentMedia.Item>(), album, reload) {
        val a = album
        value = if (a == null) emptyList() else withContext(Dispatchers.IO) {
            RecentMedia.load(context, limit = 5000, bucket = a.id).let { l -> if (noVideos) l.filter { !it.isVideo } else l }
        }
    }
    BackHandler(enabled = album != null) { album = null }
    val askAgain = {
        if (blocked) com.fariin.app.ui.components.PermissionDenied.openAppSettings(context) else ask.launch(RecentMedia.permissions())
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        // The bar: X (or back inside a folder) left, the switch in the middle. It drags the sheet.
        Box(Modifier.fillMaxWidth().height(56.dp).sheetDrag(motion).padding(horizontal = 8.dp)) {
            val a = album
            Box(Modifier.align(Alignment.CenterStart).size(44.dp).clip(CircleShape)
                .background(Color.White.copy(alpha = 0.12f))
                .clickable { if (a != null) album = null else onClose() }, contentAlignment = Alignment.Center) {
                if (a != null) Icon(ImageVector.vectorResource(R.drawable.ic_sf_chevron_left), "Back", tint = Color.White, modifier = Modifier.size(22.dp))
                else Icon(ImageVector.vectorResource(R.drawable.ic_sf_xmark), "Close", tint = Color.White, modifier = Modifier.size(22.dp))
            }
            if (a != null) Text(a.name, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.Center).padding(horizontal = 60.dp))
            else LibrarySwitch(tab, { tab = it }, Modifier.align(Alignment.Center))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                none -> AccessOff(blocked, askAgain, fileMode)
                album != null -> LibraryGrid(inAlbum, null, motion, if (multi != null) picked else null, tap, hold)
                tab == 0 -> LibraryGrid(photos, if (access == RecentMedia.Access.PARTIAL) askAgain else null, motion,
                    if (multi != null) picked else null, tap, hold)
                else -> {
                    val list = albums
                    if (list == null) FariinSpinner(Modifier.align(Alignment.Center), size = 28.dp, color = Color.White)
                    else Collections(list, motion) { album = it }
                }
            }
            // Taps are held at once, but the dim and the spinner wait 300 ms: a pick that is ready
            // sooner flies straight out of its tile with no flash (owner's video 2026-11-08).
            if (busy || resolving) {
                var slow by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { kotlinx.coroutines.delay(300); slow = true }
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (slow) 0.35f else 0f)).clickable(enabled = true) {},
                    contentAlignment = Alignment.Center) {
                    if (slow) FariinSpinner(size = 40.dp, stroke = 4.dp, color = Color.White)
                }
            }
        }
        if (multi != null && picked.isNotEmpty()) SelectionBar(picked,
            if (fileMode && fresh.isNotEmpty()) "Send ${fresh.size}" else StoryCameraRules.addLabel(fresh.size), enabled = !resolving,
            onRemove = { picked.remove(it) }, onAdd = addPicked)
    }
    preview?.let { (item, bmp) ->
        // The one action is the tap itself, so the menu can never mean something the grid does not.
        StoryLibraryPreview(item, bmp, if (multi != null) "Select" else "Add to Story",
            onAction = { preview = null; tap(item) }, onDismiss = { preview = null })
    }
    if (tooMany) androidx.compose.material3.AlertDialog(
        onDismissRequest = { tooMany = false },
        title = { Text("That's the limit") },
        text = { Text(if (fileMode) "You can send up to $limit photos at once."
            else "A story can hold ${StoryCameraRules.POST_ITEMS} photos and videos. Take one off to add another.") },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { tooMany = false }) { Text("OK") } },
    )
    if (tooLongVideo) androidx.compose.material3.AlertDialog(
        onDismissRequest = { tooLongVideo = false },
        title = { Text(StoryLimits.TOO_LONG_TITLE) },
        text = { Text(StoryLimits.TOO_LONG_TEXT) },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { tooLongVideo = false }) { Text("OK") } },
    )
    failedBatch?.let { f ->
        // OK hands over what did open (iOS: the notice first, so it is never closed away unseen).
        val ok: () -> Unit = {
            failedBatch = null
            if (f.resolved.isNotEmpty()) { multi?.onApply?.invoke(f.removed, f.resolved); onClose() }
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = ok,
            title = { Text("Couldn't add everything") },
            text = {
                Text(if (f.resolved.isEmpty()) "None of what you picked would open. Give it a moment and try again."
                    else "${f.failed} of them wouldn't open, so the rest were added.")
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = ok) { Text("OK") } },
        )
    }
}

/**
 * iOS selectionBar: a thin line, the ticks as 52 dp thumbnails (8 corners, an x top right; a tap
 * takes one off), and the action pill on the right.
 */
@Composable
private fun SelectionBar(picked: List<Uri>, label: String, enabled: Boolean, onRemove: (Uri) -> Unit, onAdd: () -> Unit) {
    val c = Fariin.colors
    Column(Modifier.fillMaxWidth().background(Color.Black).navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color.White.copy(alpha = 0.12f)))
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 10.dp)) {
                items(picked.size, key = { picked[it].toString() }) { i ->
                    val u = picked[i]
                    Box(Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).clickable { onRemove(u) }
                        .semantics { contentDescription = "Remove" }) {
                        PickedThumb(u)
                        Box(Modifier.align(Alignment.TopEnd).padding(2.dp).size(16.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)),
                            contentAlignment = Alignment.Center) {
                            Icon(ImageVector.vectorResource(R.drawable.ic_sf_xmark), null, tint = Color.White, modifier = Modifier.size(11.dp))
                        }
                    }
                }
            }
            Text(label, color = c.onAccent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(CircleShape).background(c.accent).clickable(enabled = enabled, onClick = onAdd)
                    .padding(horizontal = 18.dp, vertical = 10.dp))
        }
    }
}

@Composable
private fun PickedThumb(uri: Uri) {
    val context = LocalContext.current
    val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) { attachThumb(context, uri, StoryMedia.isVideo(context, uri), 160) }
    }
    Box(Modifier.fillMaxSize().background(Color(0xFF1C1C1E))) {
        bmp?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

/** iOS's segmented "Photos | Collections" on dark: a grey capsule, the chosen half a lighter pill that slides. */
@Composable
private fun LibrarySwitch(tab: Int, onPick: (Int) -> Unit, modifier: Modifier = Modifier) {
    val w = 220.dp
    val half = (w - 4.dp) / 2
    val x by animateDpAsState(if (tab == 0) 0.dp else half, spring(dampingRatio = 0.85f, stiffness = 600f), label = "seg")
    Box(modifier.width(w).height(32.dp).clip(CircleShape).background(Color(0x3D767680)).padding(2.dp)) {
        Box(Modifier.offset(x = x).width(half).fillMaxHeight().clip(CircleShape).background(Color(0xFF636366)))
        Row(Modifier.fillMaxSize()) {
            listOf("Photos", "Collections").forEachIndexed { i, label ->
                Box(Modifier.weight(1f).fillMaxHeight().clip(CircleShape).clickable { onPick(i) }
                    .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
                    Text(label, color = Color.White, fontSize = 13.sp, fontWeight = if (i == tab) FontWeight.SemiBold else FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun LibraryGrid(items: List<RecentMedia.Item>, onMore: (() -> Unit)?, motion: com.fariin.app.ui.components.SheetMotion,
                        /** The ticks in tap order while selecting several; null = no ticks drawn. */
                        picked: List<Uri>?, onTap: (RecentMedia.Item) -> Unit,
                        onHold: (RecentMedia.Item, androidx.compose.ui.graphics.ImageBitmap?) -> Unit) {
    val grid = rememberLazyGridState()
    LazyVerticalGrid(
        GridCells.Fixed(4),
        Modifier.fillMaxSize()
            // At the top, a pull down moves the sheet (iOS); otherwise the grid scrolls.
            .sheetDrag(motion, atTop = { grid.firstVisibleItemIndex == 0 && grid.firstVisibleItemScrollOffset == 0 }),
        state = grid,
        contentPadding = PaddingValues(start = 2.dp, end = 2.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (onMore != null) item(key = "more", span = { GridItemSpan(4) }) {
            // Android 14 "selected photos": the owner gave some, a tap lets him choose more.
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("You've allowed access to some of your photos.", color = Color(0xFF8E8E93), fontSize = 14.sp, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(10.dp))
                Text("Manage", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.14f)).clickable(onClick = onMore)
                        .padding(horizontal = 14.dp, vertical = 7.dp))
            }
        }
        items(items, key = { it.uri.toString() }) { r ->
            LibraryTile(r, tick = if (picked == null) -1 else picked.indexOf(r.uri) + 1, onHold = { b -> onHold(r, b) }) { onTap(r) }
        }
    }
}

/**
 * One photo or video: square, cropped, the video's length bottom right (iOS tile). [tick]: -1 = no
 * tick (a tap opens the editor), 0 = an empty circle, n = chosen n-th: a white disc with its number,
 * and the picture under it dimmed (the dim stays off the tick, iOS).
 */
@Composable
private fun LibraryTile(item: RecentMedia.Item, tick: Int, onHold: (androidx.compose.ui.graphics.ImageBitmap?) -> Unit, onTap: () -> Unit) {
    val context = LocalContext.current
    val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, item.uri) {
        value = withContext(Dispatchers.IO) { attachThumb(context, item.uri, item.isVideo, 256) }
    }
    val click by rememberUpdatedState(onTap)
    val held by rememberUpdatedState(onHold)
    // A tap picks, a long press lifts the preview; a scroll that starts on the tile cancels both.
    val flyKey = StoryLibraryTiles.key(item.uri)
    Box(Modifier.aspectRatio(1f)
        // The tile a picked photo flies out of (iOS PickerFlySource), empty while it is in the air.
        .heroSource(flyKey, 0.dp, StoryLibraryTiles.rects, StoryLibraryTiles.radii) { StoryLibraryTiles.hiddenKey == flyKey }
        .background(Color(0xFF1C1C1E))
        .pointerInput(item.uri) { detectTapGestures(onLongPress = { held(bmp) }, onTap = { click() }) }
        .semantics {
            contentDescription = if (item.isVideo) "Video" else "Photo"
            onClick { click(); true }
            onLongClick(label = "Preview") { held(bmp); true }
        }) {
        bmp?.let {
            Image(it, null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (tick > 0) 0.55f else 1f })
        }
        if (tick >= 0) Box(
            Modifier.align(Alignment.TopEnd).padding(5.dp).size(22.dp).clip(CircleShape)
                .background(if (tick > 0) Color.White else Color.Black.copy(alpha = 0.25f))
                .border(1.5.dp, Color.White.copy(alpha = 0.9f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (tick > 0) Text("$tick", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        if (item.isVideo) Text(
            MediaRules.durationLabel(item.durationMs / 1000.0), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).clip(RoundedCornerShape(5.dp))
                .background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 5.dp, vertical = 2.dp),
        )
    }
}

/** iOS collectionsTab: "Albums" in bold, then each folder: 80 dp cover with 18 corners, name, count. */
@Composable
private fun Collections(albums: List<RecentMedia.Album>, motion: com.fariin.app.ui.components.SheetMotion, onOpen: (RecentMedia.Album) -> Unit) {
    val list = rememberLazyListState()
    val context = LocalContext.current
    LazyColumn(Modifier.fillMaxSize().sheetDrag(motion, atTop = { list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset == 0 }),
        state = list, contentPadding = PaddingValues(top = 6.dp)) {
        item {
            Text("Albums", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 10.dp))
            Divider()
        }
        items(albums.size) { i ->
            val a = albums[i]
            val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, a.cover.uri) {
                value = withContext(Dispatchers.IO) { attachThumb(context, a.cover.uri, a.cover.isVideo, 240) }
            }
            Row(Modifier.fillMaxWidth().clickable { onOpen(a) }.padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(80.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFF2C2C2E))) {
                    bmp?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(a.name, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(3.dp))
                    Text("${a.count}", color = Color(0xFF8E8E93), fontSize = 15.sp)
                }
            }
            if (i < albums.size - 1) Divider()
        }
        item { Spacer(Modifier.navigationBarsPadding().height(12.dp)) }
    }
}

@Composable
private fun Divider() = Box(Modifier.padding(start = 20.dp).fillMaxWidth().height(0.5.dp).background(Color.White.copy(alpha = 0.10f)))

/** iOS photosAccessOff, in the same words. */
@Composable
private fun AccessOff(blocked: Boolean, onAllow: () -> Unit, fileMode: Boolean = false) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Photos access is off", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        Text(if (fileMode) "Allow Photos access in Settings to send photos as files."
            else "Allow Photos access in Settings to add photos and videos to your story.", color = Color(0xFF8E8E93), fontSize = 15.sp,
            textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(if (blocked) "Open Settings" else "Allow Access", color = Color.Black, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(CircleShape).background(Color.White).clickable(onClick = onAllow).padding(horizontal = 18.dp, vertical = 9.dp))
    }
}
