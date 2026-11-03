package com.fariin.app.ui.settings

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.data.ChatColorSpec
import com.fariin.app.data.ChatColors
import com.fariin.app.data.ChatLookStore
import com.fariin.app.data.ChatWallpaper
import com.fariin.app.data.ChatWallpapers
import com.fariin.app.ui.components.WallpaperArt
import com.fariin.app.ui.components.brush
import com.fariin.app.ui.components.drawWallpaper
import com.fariin.app.ui.components.floating
import com.fariin.app.ui.theme.Fariin
import com.fariin.app.ui.theme.FariinTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/*
 * Settings > Appearance > Chat Wallpaper, Wallpaper Color and Chat Color (iOS AppearancePages.swift:
 * ChatWallpaperPage, WallpaperColorPage, WallpaperPreviewScreen, ChatColorPage, CustomColorView). The
 * same grids, values, words and order; the reset menu sits behind the three-dot button (Android).
 */

internal const val PAGE_WALLPAPER = "look:wallpaper"
internal const val PAGE_WALL_COLOR = "look:wallcolor"
internal const val PAGE_CHAT_COLOR = "look:chatcolor"

@Composable
internal fun lookRoute(page: String, push: (String) -> Unit, pop: () -> Unit): Boolean {
    when (page) {
        PAGE_WALLPAPER -> ChatWallpaperPage(push, pop)
        PAGE_WALL_COLOR -> WallpaperColorPage(pop)
        PAGE_CHAT_COLOR -> ChatColorPage(pop)
        else -> return false
    }
    return true
}

/** iOS AppearanceResetMenu: this page's own reset, then both (which asks first). */
@Composable
private fun ResetMenu(wallpaperPage: Boolean) {
    val ctx = LocalContext.current
    val c = Fariin.colors
    var open by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    fun resetWallpaper() = ChatLookStore.setDefaultWallpaper(ctx, ChatWallpaper.None)
    fun resetColor() = ChatLookStore.setDefaultColor(ctx, null)
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Reset options", tint = c.label) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, shape = com.fariin.app.ui.components.FariinMenu.shape,
            border = com.fariin.app.ui.components.FariinMenu.border, containerColor = com.fariin.app.ui.components.FariinMenu.container) {
            DropdownMenuItem(text = { Text(if (wallpaperPage) "Reset Wallpaper" else "Reset Chat Color") },
                onClick = { open = false; if (wallpaperPage) resetWallpaper() else resetColor() })
            DropdownMenuItem(text = { Text(if (wallpaperPage) "Reset Wallpaper and Chat Color" else "Reset Chat Color and Wallpaper", color = c.red) },
                onClick = { open = false; confirm = true })
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(if (wallpaperPage) "Reset wallpaper and chat color?" else "Reset chat color and wallpaper?") },
        text = { Text("Chats go back to no wallpaper and the Auto color. Chats with their own keep it.") },
        confirmButton = { TextButton(onClick = { confirm = false; resetWallpaper(); resetColor() }) { Text("Reset", color = c.red) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel", color = c.label) } },
        containerColor = c.card, titleContentColor = c.label, textContentColor = c.label,
    )
}

/**
 * iOS WallpaperPickerSheet's Reset menu, for one chat: Reset Chat Color (back to the default blue)
 * while the chat has a colour of its own, Reset Wallpaper (plain, not the Settings one) while it has
 * a wallpaper of its own. Nothing to reset, no button. Each closes the picker, as iOS does.
 */
@Composable
private fun ChatResetMenu(cid: String, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val c = Fariin.colors
    ChatLookStore.version.intValue
    val blue = ChatColors.presets.first()
    val own = ChatLookStore.ownColor(ctx, cid)
    val canColor = own != null && own != blue
    val canWall = ChatLookStore.hasOwnWallpaper(ctx, cid) && ChatLookStore.wallpaper(ctx, cid) != ChatWallpaper.None
    if (!canColor && !canWall) return
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Reset options", tint = c.label) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, shape = com.fariin.app.ui.components.FariinMenu.shape,
            border = com.fariin.app.ui.components.FariinMenu.border, containerColor = com.fariin.app.ui.components.FariinMenu.container) {
            if (canColor) DropdownMenuItem(text = { Text("Reset Chat Color") },
                onClick = { open = false; ChatLookStore.setColor(ctx, cid, blue); onDone() })
            if (canWall) DropdownMenuItem(text = { Text("Reset Wallpaper") },
                onClick = { open = false; ChatLookStore.setWallpaper(ctx, cid, ChatWallpaper.None); onDone() })
        }
    }
}

/** The info page's "Change Wallpaper": the chat it names opens its picker (iOS .openChatWallpaper). */
object ChatWallpaperLink {
    val requests = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    fun request(cid: String) { requests.value = cid }
}

/**
 * One chat's wallpaper picker (iOS "Change Wallpaper" from the chat's info page, WallpaperPickerSheet
 * per chat): the Settings pages for [cid] alone, with the chat's own resets. Back steps out.
 */
@Composable
fun ChatWallpaperPicker(cid: String, onClose: () -> Unit) {
    var colors by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler { if (colors) colors = false else onClose() }
    // Drawn over the chat: it takes every touch so none reaches the list under it.
    Box(Modifier.fillMaxSize().background(Fariin.colors.bgSecondary)
        .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } }) {
        if (colors) WallpaperColorPage({ colors = false }, cid, done = onClose)
        else ChatWallpaperPage({ colors = true }, onClose, cid)
    }
}

/** A rounded white card holding a grid (iOS: secondarySystemGroupedBackground, corner 24, 14 inside). */
@Composable
private fun GridCard(columns: Int, spacing: Int, inner: Int, items: List<@Composable (Modifier) -> Unit>) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Fariin.colors.card).padding(inner.dp),
        verticalArrangement = Arrangement.spacedBy(spacing.dp)) {
        items.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing.dp)) {
                row.forEach { it(Modifier.weight(1f)) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** A wallpaper tile: 150 tall, corner 18, the tick when it is the Settings wallpaper. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WallTile(w: ChatWallpaper, current: ChatWallpaper, modifier: Modifier, onLong: (() -> Unit)? = null, onTap: () -> Unit) {
    val ctx = LocalContext.current
    val c = Fariin.colors
    val shape = RoundedCornerShape(18.dp)
    val pattern = WallpaperArt.pattern(ctx)
    val photo = (w as? ChatWallpaper.Photo)?.let { ChatLookStore.libraryImage(ctx, it.id)?.asImageBitmap() }
    val solid = w as? ChatWallpaper.Solid
    Box(modifier.height(150.dp).clip(shape)
        .drawBehind { drawWallpaper(w, c.dark, c.bg, pattern, photo) }
        .then(if (solid != null) Modifier.border(1.dp, c.label.copy(alpha = 0.10f), shape) else Modifier)
        .combinedClickable(onLongClick = onLong, onClick = onTap),
        contentAlignment = Alignment.Center) {
        if (current == w) {
            val darkTick = solid != null && !ChatWallpapers.isDark(solid.hex)
            // iOS: the white tick carries a soft black shadow (0.5, radius 3); a dimmed copy under it here.
            if (!darkTick) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.Black.copy(alpha = 0.35f),
                modifier = Modifier.size(32.dp).offset(y = 1.dp))
            Icon(Icons.Filled.Check, contentDescription = "Selected", tint = if (darkTick) Color.Black else Color.White,
                modifier = Modifier.size(30.dp))
        }
    }
}

@Composable
private fun ChatWallpaperPage(push: (String) -> Unit, pop: () -> Unit, cid: String? = null) {
    val ctx = LocalContext.current
    val c = Fariin.colors
    val scope = rememberCoroutineScope()
    ChatLookStore.version.intValue
    val current = cid?.let { ChatLookStore.wallpaper(ctx, it) } ?: ChatLookStore.defaultWallpaper(ctx)
    val library = ChatLookStore.libraryIds(ctx)
    var previewing by remember { mutableStateOf<ChatWallpaper?>(null) }
    // The photo just picked: the only one that opens alone with pinch and drag framing.
    var justPicked by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val id = withContext(Dispatchers.IO) {
                com.fariin.app.media.BitmapDecode.fromUri(ctx, uri, 2560)?.let { ChatLookStore.addToLibrary(ctx, it) }
            }
            if (id != null) { justPicked = id; previewing = ChatWallpaper.Photo(id) }
        }
    }
    Column(Modifier.fillMaxSize()) {
        PageBar(if (cid != null) "Chat Wallpaper" else "Set Wallpaper", onBack = pop,
            action = { if (cid != null) ChatResetMenu(cid, pop) else ResetMenu(wallpaperPage = true) })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.card)) {
                SettingsRow("Choose from Photos") {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                SettingsRow("Wallpaper Color", last = true) { push(PAGE_WALL_COLOR) }
            }
            Text("Presets", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = c.label, modifier = Modifier.padding(horizontal = 4.dp))
            val tiles = buildList<@Composable (Modifier) -> Unit> {
                add { m -> WallTile(ChatWallpaper.None, current, m) { previewing = ChatWallpaper.None } }
                library.forEach { id ->
                    add { m ->
                        Box(m) {
                            WallTile(ChatWallpaper.Photo(id), current, Modifier.fillMaxWidth(), onLong = { deleting = id }) { previewing = ChatWallpaper.Photo(id) }
                            DropdownMenu(expanded = deleting == id, onDismissRequest = { deleting = null }, shape = com.fariin.app.ui.components.FariinMenu.shape,
                                border = com.fariin.app.ui.components.FariinMenu.border, containerColor = com.fariin.app.ui.components.FariinMenu.container) {
                                DropdownMenuItem(text = { Text("Delete", color = c.red) }, onClick = { deleting = null; ChatLookStore.deleteFromLibrary(ctx, id) })
                            }
                        }
                    }
                }
                ChatWallpapers.all.forEach { g -> add { m -> WallTile(ChatWallpaper.Gradient(g.id), current, m) { previewing = ChatWallpaper.Gradient(g.id) } } }
            }
            GridCard(3, 10, 14, tiles)
            Spacer(Modifier.height(com.fariin.app.ui.shell.bottomBarInset()))
        }
    }
    previewing?.let { w ->
        val alone = w is ChatWallpaper.Photo && w.id == justPicked
        WallpaperPreview(w, siblings = if (alone) emptyList() else library.map { ChatWallpaper.Photo(it) } + ChatWallpapers.all.map { ChatWallpaper.Gradient(it.id) },
            cid = cid, onApplied = pop,
            onClose = {
                // Not kept unless applied (iOS): a picked photo closed without Apply leaves the library.
                val id = justPicked
                val inUse = ChatLookStore.defaultWallpaper(ctx) == ChatWallpaper.Photo(id ?: "") ||
                    (cid != null && ChatLookStore.wallpaper(ctx, cid) == ChatWallpaper.Photo(id ?: ""))
                if (id != null && !inUse && id in ChatLookStore.libraryIds(ctx)) {
                    ChatLookStore.deleteFromLibrary(ctx, id)
                }
                justPicked = null; previewing = null
            })
    }
}

@Composable
private fun WallpaperColorPage(pop: () -> Unit, cid: String? = null, done: () -> Unit = pop) {
    val ctx = LocalContext.current
    ChatLookStore.version.intValue
    val current = cid?.let { ChatLookStore.wallpaper(ctx, it) } ?: ChatLookStore.defaultWallpaper(ctx)
    var previewing by remember { mutableStateOf<ChatWallpaper?>(null) }
    Column(Modifier.fillMaxSize()) {
        PageBar("Wallpaper Color", onBack = pop)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            GridCard(3, 10, 14, ChatWallpapers.colors.map<Long, @Composable (Modifier) -> Unit> { hex ->
                { m -> WallTile(ChatWallpaper.Solid(hex), current, m) { previewing = ChatWallpaper.Solid(hex) } }
            })
            Spacer(Modifier.height(com.fariin.app.ui.shell.bottomBarInset()))
        }
    }
    previewing?.let { w ->
        WallpaperPreview(w, ChatWallpapers.colors.map { ChatWallpaper.Solid(it) }, cid = cid, onApplied = done, onClose = { previewing = null })
    }
}

/**
 * iOS WallpaperPreviewScreen, as a sheet: the wallpaper behind two sample bubbles, X and the
 * light/dark button on top, "Apply For All Chats" at the bottom. Swipes through [siblings]; a photo
 * opened alone (just picked) pinches and drags instead and offers Blurred.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WallpaperPreview(first: ChatWallpaper, siblings: List<ChatWallpaper>, cid: String? = null, onApplied: () -> Unit = {}, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val appDark = Fariin.colors.dark
    val inSet = first in siblings
    val pages = inSet && siblings.size > 1
    val editable = first is ChatWallpaper.Photo && !inSet
    val pager = rememberPagerState(initialPage = siblings.indexOf(first).coerceAtLeast(0)) { siblings.size.coerceAtLeast(1) }
    var previewDark by remember { mutableStateOf<Boolean?>(null) }
    val dark = previewDark ?: appDark
    var blurred by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var canvas by remember { mutableStateOf(IntSize.Zero) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val shown = if (inSet) siblings.getOrElse(pager.currentPage) { first } else first
    val photoBitmap = (first as? ChatWallpaper.Photo)?.let { ChatLookStore.libraryImage(ctx, it.id) }

    fun apply(allChats: Boolean = cid == null) {
        var final = shown
        if (editable && photoBitmap != null && (zoom != 1f || pan != Offset.Zero || blurred)) {
            val baked = WallpaperBake.bake(photoBitmap, canvas, zoom, pan, blurred)
            val id = ChatLookStore.addToLibrary(ctx, baked)
            if (id != null) {
                if (id != (first as ChatWallpaper.Photo).id) ChatLookStore.deleteFromLibrary(ctx, first.id)
                final = ChatWallpaper.Photo(id)
            }
        }
        // From a chat (iOS): Apply Wallpaper is this chat's; Apply For All Chats sets the Settings one
        // and this chat follows it again.
        if (cid == null) ChatLookStore.setDefaultWallpaper(ctx, final)
        else if (allChats) ChatLookStore.applyToAllChats(ctx, final, alsoFor = cid)
        else ChatLookStore.setWallpaper(ctx, cid, final)
        // A chat's picker closes once applied, back to the chat (iOS dismiss).
        scope.launch { sheet.hide() }.invokeOnCompletion { onClose(); if (cid != null) onApplied() }
    }

    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheet, dragHandle = null,
        shape = com.fariin.app.ui.components.SheetShape, containerColor = Color.Transparent) {
        FariinTheme(dark = dark) {
            val c = Fariin.colors
            Box(Modifier.fillMaxSize().statusBarsPadding().padding(top = 8.dp).clip(com.fariin.app.ui.components.SheetShape).onSizeChanged { canvas = it }) {
                val pattern = WallpaperArt.pattern(ctx)
                if (pages) {
                    HorizontalPager(pager, Modifier.fillMaxSize()) { i ->
                        val w = siblings[i]
                        val photo = (w as? ChatWallpaper.Photo)?.let { ChatLookStore.libraryImage(ctx, it.id)?.asImageBitmap() }
                        Box(Modifier.fillMaxSize().drawBehind { drawWallpaper(w, dark, c.bg, pattern, photo) })
                    }
                } else if (editable && photoBitmap != null) {
                    EditablePhoto(photoBitmap, canvas, zoom, pan, blurred, dark) { z, p -> zoom = z; pan = p }
                } else {
                    val photo = (first as? ChatWallpaper.Photo)?.let { ChatLookStore.libraryImage(ctx, it.id)?.asImageBitmap() }
                    Box(Modifier.fillMaxSize().drawBehind { drawWallpaper(first, dark, c.bg, pattern, photo) })
                }
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 14.dp)) {
                        com.fariin.app.ui.components.FloatingIcon(Icons.Filled.Close, "Close", onClick = { scope.launch { sheet.hide() }.invokeOnCompletion { onClose() } },
                            modifier = Modifier.align(Alignment.CenterStart))
                        Text("Preview", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label, modifier = Modifier.align(Alignment.Center))
                        com.fariin.app.ui.components.FloatingIcon(if (dark) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                            if (dark) "Show in light mode" else "Show in dark mode", onClick = { previewDark = !dark },
                            modifier = Modifier.align(Alignment.CenterEnd))
                    }
                    Spacer(Modifier.weight(1f))
                    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        MockBubble(if (pages) "Swipe to preview more wallpapers." else "This is how the wallpaper looks", mine = false,
                            mineFill = null, modifier = Modifier.padding(end = 40.dp).align(Alignment.Start))
                        val mine = cid?.let { ChatLookStore.ownColor(ctx, it) } ?: ChatLookStore.globalChosen(ctx) ?: shown.pairedColor
                        MockBubble("Nice, applying it", mine = true, mineFill = mine?.brush(angled = true), modifier = Modifier.padding(start = 40.dp).align(Alignment.End))
                    }
                    if (editable) {
                        Text("Pinch to zoom · drag to reposition", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = c.secondary,
                            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 12.dp).floating(CircleShape).padding(horizontal = 12.dp, vertical = 6.dp))
                        Row(Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp).floating(CircleShape).clickable { blurred = !blurred }
                            .padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (blurred) Icons.Filled.CheckCircle else Icons.Outlined.Circle, contentDescription = null, tint = c.label, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.size(8.dp))
                            Text("Blurred", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.label)
                        }
                    }
                    Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 10.dp, bottom = if (cid != null) 6.dp else 12.dp)
                        .then(if (cid != null) Modifier else Modifier.navigationBarsPadding())
                        .height(52.dp).floating(CircleShape).clickable { apply() }, contentAlignment = Alignment.Center) {
                        Text(if (cid != null) "Apply Wallpaper" else "Apply For All Chats", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label)
                    }
                    // iOS: from a chat, the smaller all-chats button rides under Apply Wallpaper.
                    if (cid != null) Text("Apply For All Chats", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.label,
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 12.dp).navigationBarsPadding()
                            .clip(CircleShape).clickable { apply(allChats = true) }.padding(horizontal = 16.dp, vertical = 10.dp))
                }
            }
        }
    }
}

/** The just-picked photo: fills the sheet, pinch 1x to 5x, drag within its edges, Blurred on demand. */
@Composable
private fun EditablePhoto(img: Bitmap, canvas: IntSize, zoom: Float, pan: Offset, blurred: Boolean, dark: Boolean, onChange: (Float, Offset) -> Unit) {
    val shownImg = remember(img, blurred) { (if (blurred) WallpaperBake.blur(img) else img).asImageBitmap() }
    var z by remember { mutableFloatStateOf(zoom) }
    var p by remember { mutableStateOf(pan) }
    Canvas(Modifier.fillMaxSize().pointerInput(canvas) {
        detectTransformGestures { _, dPan, dZoom, _ ->
            z = (z * dZoom).coerceIn(1f, 5f)
            p = WallpaperBake.clampPan(p + dPan, img.width, img.height, canvas, z)
            onChange(z, p)
        }
    }) {
        val fill = max(size.width / img.width, size.height / img.height) * z
        val w = img.width * fill; val h = img.height * fill
        drawImage(shownImg, dstOffset = androidx.compose.ui.unit.IntOffset(((size.width - w) / 2 + p.x).toInt(), ((size.height - h) / 2 + p.y).toInt()),
            dstSize = IntSize(w.toInt(), h.toInt()))
        drawRect(if (dark) Color.Black.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.14f))
    }
}

/** A sample bubble: mine in the chat colour (or the default blue), theirs frosted. */
@Composable
private fun MockBubble(text: String, mine: Boolean, mineFill: Brush?, modifier: Modifier = Modifier, size: Int = 15, corner: Int = 18) {
    val c = Fariin.colors
    val shape = RoundedCornerShape(corner.dp)
    val bg = if (mine) Modifier.background(mineFill ?: androidx.compose.ui.graphics.SolidColor(c.blue), shape)
    else Modifier.background(c.card.copy(alpha = 0.88f), shape)
    Text(text, fontSize = size.sp, color = if (mine) Color.White else c.label,
        modifier = modifier.widthIn(max = 300.dp).clip(shape).then(bg).padding(horizontal = 14.dp, vertical = 9.dp))
}

/** Bakes the framing and blur of a picked photo into a new picture (iOS renderCrop + gaussianBlur 10). */
internal object WallpaperBake {
    fun clampPan(p: Offset, iw: Int, ih: Int, canvas: IntSize, zoom: Float): Offset {
        if (canvas.width <= 0 || iw <= 0 || ih <= 0) return p
        val s = max(canvas.width.toFloat() / iw, canvas.height.toFloat() / ih) * zoom
        val mx = max(0f, (iw * s - canvas.width) / 2); val my = max(0f, (ih * s - canvas.height) / 2)
        return Offset(p.x.coerceIn(-mx, mx), p.y.coerceIn(-my, my))
    }

    fun bake(img: Bitmap, canvas: IntSize, zoom: Float, pan: Offset, blurred: Boolean): Bitmap {
        var out = img
        if ((zoom != 1f || pan != Offset.Zero) && canvas.width > 0 && canvas.height > 0) {
            val s = max(canvas.width.toFloat() / img.width, canvas.height.toFloat() / img.height) * zoom
            val w = img.width * s; val h = img.height * s
            val left = (canvas.width - w) / 2 + pan.x; val top = (canvas.height - h) / 2 + pan.y
            out = Bitmap.createBitmap(canvas.width, canvas.height, Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(out).drawBitmap(img, null, android.graphics.RectF(left, top, left + w, top + h),
                android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        }
        return if (blurred) blur(out) else out
    }

    /** A soft blur: down to a sixteenth and back up twice with filtering, close to a radius-10 gaussian. */
    fun blur(img: Bitmap): Bitmap {
        val sw = (img.width / 16).coerceAtLeast(1); val sh = (img.height / 16).coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(Bitmap.createScaledBitmap(img, img.width / 4 + 1, img.height / 4 + 1, true), sw, sh, true)
        return Bitmap.createScaledBitmap(Bitmap.createScaledBitmap(small, img.width / 4 + 1, img.height / 4 + 1, true), img.width, img.height, true)
    }
}

/** iOS ChatColorPage: the preview on the Settings wallpaper, then Auto, the palette, my colours and +. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatColorPage(pop: () -> Unit) {
    val ctx = LocalContext.current
    val c = Fariin.colors
    ChatLookStore.version.intValue
    val selected = ChatLookStore.globalChosen(ctx)
    val customs = ChatLookStore.customColors(ctx)
    var custom by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ChatColorSpec?>(null) }
    val wall = ChatLookStore.defaultWallpaper(ctx)
    val pattern = WallpaperArt.pattern(ctx)
    val photo = (wall as? ChatWallpaper.Photo)?.let { ChatLookStore.libraryImage(ctx, it.id)?.asImageBitmap() }
    Column(Modifier.fillMaxSize()) {
        PageBar("Chat Color", onBack = pop, action = { ResetMenu(wallpaperPage = false) })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
                .drawBehind { drawWallpaper(wall, c.dark, c.card, pattern, photo) }.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PreviewBubble("Here's a preview of the chat color.", false, null, Modifier.padding(end = 36.dp).align(Alignment.Start))
                PreviewBubble("The color is visible to only you.", true, ChatLookStore.globalColor(ctx)?.brush(), Modifier.padding(start = 36.dp).align(Alignment.End))
            }
            val circles = buildList<@Composable (Modifier) -> Unit> {
                add { m ->
                    ColorCircle(selected == null, m, onTap = { ChatLookStore.setDefaultColor(ctx, null) }) {
                        Box(Modifier.fillMaxSize().clip(CircleShape).background(ChatLookStore.globalAuto(ctx)?.brush() ?: androidx.compose.ui.graphics.SolidColor(c.blue)),
                            contentAlignment = Alignment.Center) { Text("Auto", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.White) }
                    }
                }
                ChatColors.presets.forEach { p ->
                    add { m -> ColorCircle(selected == p, m, onTap = { ChatLookStore.setDefaultColor(ctx, p) }) { Box(Modifier.fillMaxSize().clip(CircleShape).background(p.brush())) } }
                }
                customs.forEach { p ->
                    add { m ->
                        Box(m) {
                            ColorCircle(selected == p, Modifier.fillMaxWidth(), onLong = { deleting = p }, onTap = { ChatLookStore.setDefaultColor(ctx, p) }) {
                                Box(Modifier.fillMaxSize().clip(CircleShape).background(p.brush()))
                            }
                            DropdownMenu(expanded = deleting == p, onDismissRequest = { deleting = null }, shape = com.fariin.app.ui.components.FariinMenu.shape,
                                border = com.fariin.app.ui.components.FariinMenu.border, containerColor = com.fariin.app.ui.components.FariinMenu.container) {
                                DropdownMenuItem(text = { Text("Delete", color = c.red) }, onClick = { deleting = null; ChatLookStore.removeCustom(ctx, p) })
                            }
                        }
                    }
                }
                add { m ->
                    ColorCircle(false, m, onTap = { custom = true }) {
                        Box(Modifier.fillMaxSize().clip(CircleShape).background(systemGray5(c.dark)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Add, contentDescription = "Custom Color", tint = c.label, modifier = Modifier.size(24.dp))
                        }
                    }
                }
            }
            GridCard(5, 16, 18, circles)
            Spacer(Modifier.height(com.fariin.app.ui.shell.bottomBarInset()))
        }
    }
    if (custom) CustomColorSheet(onClose = { custom = false }) { spec ->
        ChatLookStore.addCustom(ctx, spec)
        ChatLookStore.setDefaultColor(ctx, spec)
    }
}

@Composable
private fun PreviewBubble(text: String, mine: Boolean, mineFill: Brush?, modifier: Modifier) {
    val c = Fariin.colors
    val shape = RoundedCornerShape(17.dp)
    Text(text, fontSize = 14.sp, color = if (mine) Color.White else c.label,
        modifier = modifier.clip(shape).background(if (mine) mineFill ?: androidx.compose.ui.graphics.SolidColor(c.blue) else androidx.compose.ui.graphics.SolidColor(systemGray5(c.dark)), shape)
            .padding(horizontal = 13.dp, vertical = 8.dp))
}

/** UIColor.systemGray5. */
private fun systemGray5(dark: Boolean) = if (dark) Color(0xFF2C2C2E) else Color(0xFFE5E5EA)

/** A 54 colour circle; the chosen one has a 3 ring 5 outside it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ColorCircle(selected: Boolean, modifier: Modifier, onLong: (() -> Unit)? = null, onTap: () -> Unit, content: @Composable () -> Unit) {
    val c = Fariin.colors
    Box(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        Box(Modifier.requiredSize(64.dp).then(if (selected) Modifier.border(3.dp, c.label, CircleShape) else Modifier))
        Box(Modifier.size(54.dp).clip(CircleShape).combinedClickable(onLongClick = onLong, onClick = onTap)) { content() }
    }
}

/** iOS CustomColorView as a sheet: Solid / Gradient, the preview, the two ends, Hue and Saturation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomColorSheet(onClose: () -> Unit, onSet: (ChatColorSpec) -> Unit) {
    val ctx = LocalContext.current
    val c = Fariin.colors
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var gradient by remember { mutableStateOf(false) }
    var hue1 by remember { mutableFloatStateOf(0.62f) }
    var sat1 by remember { mutableFloatStateOf(0.72f) }
    var hue2 by remember { mutableFloatStateOf(0.78f) }
    var sat2 by remember { mutableFloatStateOf(0.72f) }
    var stop by remember { mutableStateOf(0) }
    val spec = if (gradient) ChatColorSpec(listOf(ChatColorSpec.hsb(hue1.toDouble(), sat1.toDouble()), ChatColorSpec.hsb(hue2.toDouble(), sat2.toDouble())))
    else ChatColorSpec(listOf(ChatColorSpec.hsb(hue1.toDouble(), sat1.toDouble())))
    val second = gradient && stop == 1
    val hue = if (second) hue2 else hue1
    val sat = if (second) sat2 else sat1
    fun close() { scope.launch { sheet.hide() }.invokeOnCompletion { onClose() } }
    val wall = ChatLookStore.defaultWallpaper(ctx)
    val pattern = WallpaperArt.pattern(ctx)
    val photo = (wall as? ChatWallpaper.Photo)?.let { ChatLookStore.libraryImage(ctx, it.id)?.asImageBitmap() }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheet, containerColor = com.fariin.app.ui.components.sheetFill,
        shape = com.fariin.app.ui.components.SheetShape) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                com.fariin.app.ui.components.FloatingIcon(Icons.Filled.Close, "Close", onClick = { close() }, modifier = Modifier.align(Alignment.CenterStart))
                Text("Custom Color", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label, modifier = Modifier.align(Alignment.Center))
                com.fariin.app.ui.components.FloatingTextAction("Set", modifier = Modifier.align(Alignment.CenterEnd)) { onSet(spec); close() }
            }
            com.fariin.app.ui.components.FloatingSegments(listOf("Solid", "Gradient"), if (gradient) 1 else 0,
                { gradient = it == 1; stop = 0 }, Modifier.align(Alignment.CenterHorizontally))
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(20.dp))
                .drawBehind { drawWallpaper(wall, c.dark, c.bg, pattern, photo) }.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Today", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = c.label,
                    modifier = Modifier.align(Alignment.CenterHorizontally).floating(CircleShape).padding(horizontal = 12.dp, vertical = 5.dp))
                MockBubble("Here's a preview of the chat color.", false, null, Modifier.align(Alignment.Start), corner = 17)
                MockBubble("The color is visible to only you.", true, spec.brush(), Modifier.align(Alignment.End), corner = 17)
                MockBubble("Another bubble", false, null, Modifier.align(Alignment.Start), corner = 17)
            }
            if (gradient) Row(Modifier.align(Alignment.CenterHorizontally), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                for (i in 0..1) {
                    val col = if (i == 0) ChatColorSpec.hsb(hue1.toDouble(), sat1.toDouble()) else ChatColorSpec.hsb(hue2.toDouble(), sat2.toDouble())
                    Box(Modifier.size(34.dp).clip(CircleShape).background(Color(0xFF000000 or col))
                        .then(if (stop == i) Modifier.border(3.dp, c.label, CircleShape) else Modifier).clickable { stop = i })
                }
            }
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                SliderBlock("Hue", hue, Brush.horizontalGradient((0..10).map { Color.hsv(it * 36f % 360f, 0.9f, 0.95f) })) {
                    if (second) hue2 = it else hue1 = it
                }
                SliderBlock("Saturation", sat, Brush.horizontalGradient(listOf(Color.hsv(hue * 360f % 360f, 0f, 0.7f), Color.hsv(hue * 360f % 360f, 1f, 0.86f)))) {
                    if (second) sat2 = it else sat1 = it
                }
            }
        }
    }
}

/** iOS GradientSlider: a 14 tall gradient capsule and a 28 white thumb. */
@Composable
private fun SliderBlock(title: String, value: Float, track: Brush, onChange: (Float) -> Unit) {
    val c = Fariin.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label)
        BoxWithConstraints(Modifier.fillMaxWidth().height(28.dp)) {
            val wPx = constraints.maxWidth.toFloat()
            val thumb = 28.dp
            val thumbPx = with(androidx.compose.ui.platform.LocalDensity.current) { thumb.toPx() }
            fun set(x: Float) = onChange(((x - thumbPx / 2) / (wPx - thumbPx)).coerceIn(0f, 1f))
            Box(Modifier.fillMaxSize().pointerInput(wPx) {
                detectTapGestures { set(it.x) }
            }.pointerInput(wPx) {
                detectDragGestures(onDragStart = { set(it.x) }) { ch, _ -> set(ch.position.x) }
            }, contentAlignment = Alignment.CenterStart) {
                Box(Modifier.fillMaxWidth().height(14.dp).clip(CircleShape).background(track))
                Box(Modifier.offset(x = with(androidx.compose.ui.platform.LocalDensity.current) { (value * (wPx - thumbPx)).toDp() })
                    .size(thumb).shadow(2.dp, CircleShape).clip(CircleShape).background(Color.White))
            }
        }
    }
}
