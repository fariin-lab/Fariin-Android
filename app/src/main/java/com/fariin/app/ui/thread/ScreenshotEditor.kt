package com.fariin.app.ui.thread

import android.content.Intent
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.fariin.app.R
import com.fariin.app.data.ChatWallpaper
import com.fariin.app.data.ScreenshotRules
import com.fariin.app.data.ThreadLogic
import com.fariin.app.data.Tick
import com.fariin.app.ui.components.drawWallpaper
import com.fariin.app.ui.components.floating
import com.fariin.app.ui.theme.Fariin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Customize screenshot (iOS ScreenshotEditorView + ShotNoticeBar). The chat listens for the phone's
 * screenshot (Android 14+, registerScreenCaptureCallback) only while it is showing; older phones get
 * nothing. A screenshot of a 1:1 or group chat brings up a glass bar for 5 s; Customize opens the
 * editor with a copy of the messages that were on screen. The picture is made only on Share, saved
 * in cache/open (cleared with the other temp copies at sign-out) and handed to the system share
 * sheet. Nothing is uploaded.
 */

/** The chat's screenshot listener, the bar above the composer and the editor. Inside the list's Box. */
@Composable
internal fun BoxScope.ScreenshotOffer(ui: ThreadUi, listState: LazyListState, is24h: Boolean, bottomBarPx: Int) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val live = rememberUpdatedState(ui)
    var offer by remember(ui.cid) { mutableStateOf<List<ScreenshotRules.Shot>?>(null) }
    var editing by remember(ui.cid) { mutableStateOf<List<ScreenshotRules.Shot>?>(null) }
    val editingNow = rememberUpdatedState(editing)
    val activity = remember(ctx) { ctx.findActivity() }
    if (Build.VERSION.SDK_INT >= 34 && activity != null) {
        DisposableEffect(activity, ui.cid) {
            val cb = android.app.Activity.ScreenCaptureCallback {
                val u = live.value
                val secure = (activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE) != 0
                val ok = ScreenshotRules.offers(
                    isChannel = u.channel != null || u.channelWaiting,
                    isOfficial = u.cid == com.fariin.app.data.OfficialChannel.CID,
                    isGuest = u.groupGuest != null || u.modHidden,
                    restricted = com.fariin.app.data.ChatRestrictions.isOn(u.cid),
                    windowSecure = secure,
                )
                if (!ok || editingNow.value != null) return@ScreenCaptureCallback
                val onScreen = listState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String }.toSet()
                val now = System.currentTimeMillis().toDouble()
                val shots = ScreenshotRules.snapshot(u.messages, onScreen, u.me, u.isGroup, u.receipts,
                    name = { uid -> u.names[uid].orEmpty().ifEmpty { "Someone" } },
                    day = { ms -> ThreadLogic.dayLabel(ms, now) })
                if (shots.isNotEmpty()) offer = shots
            }
            val registered = runCatching { activity.registerScreenCaptureCallback(activity.mainExecutor, cb) }.isSuccess
            onDispose { if (registered) runCatching { activity.unregisterScreenCaptureCallback(cb) } }
        }
    }
    // The bar goes after 5 s; a newer screenshot starts its time again.
    LaunchedEffect(offer) { if (offer != null) { delay(ScreenshotRules.BAR_MS); offer = null } }
    AnimatedVisibility(offer != null,
        Modifier.align(Alignment.BottomCenter).zIndex(2f)
            .padding(start = COMPOSER_SIDE, end = COMPOSER_SIDE, bottom = with(density) { bottomBarPx.toDp() } + 8.dp),
        enter = com.fariin.app.ui.components.Smooth.fadeIn(), exit = com.fariin.app.ui.components.Smooth.fadeOut()) {
        ShotNoticeBar(onCustomize = { editing = offer; offer = null })
    }
    editing?.let { shots -> ScreenshotEditor(ui.cid, shots, is24h) { editing = null } }
}

private fun android.content.Context.findActivity(): android.app.Activity? {
    var c: android.content.Context? = this
    while (c is android.content.ContextWrapper) { if (c is android.app.Activity) return c; c = c.baseContext }
    return null
}

/** iOS ShotNoticeBar: glass with round corners, a title and a grey line, an outlined capsule button. */
@Composable
private fun ShotNoticeBar(onCustomize: () -> Unit) {
    val c = Fariin.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 54.dp).floating(RoundedCornerShape(22.dp))
        .padding(start = 16.dp, end = 10.dp, top = 9.dp, bottom = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Screenshot saved", color = c.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text("Remove messages or hide text", color = c.secondary, fontSize = 14.sp)
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.height(32.dp).clip(CircleShape).border(1.dp, c.blue, CircleShape).clickable(onClick = onCustomize)
            .padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
            Text("Customize", color = c.blue, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** A background choice: this chat's own wallpaper, or one of the built-in themes. */
private data class ShotTheme(val id: String?, val name: String)

/** The editor sheet: header, the card with the chat, theme swatches, Share. */
@Composable
private fun ScreenshotEditor(cid: String, shots: List<ScreenshotRules.Shot>, is24h: Boolean, onDismiss: () -> Unit) {
    val motion = com.fariin.app.ui.components.rememberSheetMotion(onDismiss)
    com.fariin.app.ui.components.FariinSheetDialog(motion) { m ->
        EditorPage(cid, shots, is24h, close = { m.close() })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EditorPage(cid: String, shots: List<ScreenshotRules.Shot>, is24h: Boolean, close: () -> Unit) {
    val c = Fariin.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var edits by remember { mutableStateOf(ScreenshotRules.Edits()) }
    var undoFor by remember { mutableStateOf<String?>(null) }
    // The message held (its actions show in a capsule) and the one whose words are being picked.
    var active by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf<String?>(null) }
    var dark by remember { mutableStateOf(c.dark) }
    var theme by remember { mutableStateOf(ShotTheme(null, "This chat")) }
    var rendering by remember { mutableStateOf(false) }
    val rows = edits.rows(shots)
    // Long chats are split into pictures no taller than the screen (iOS plan), measured as drawn.
    val density = LocalDensity.current
    val bubbleH = remember { androidx.compose.runtime.mutableStateMapOf<String, Int>() }
    var dayPx by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val screenPx = with(density) { LocalConfiguration.current.screenHeightDp.dp.roundToPx() }
    val topPx = with(density) { ScreenshotRules.PAGE_TOP_DP.dp.roundToPx() }
    val footPx = with(density) { ScreenshotRules.FOOTER_DP.dp.roundToPx() }
    val pages = ScreenshotRules.plan(rows.map { bubbleH[it.id] ?: 0 }, ScreenshotRules.placements(rows).map { it.showDay },
        dayPx.takeIf { it > 0 } ?: with(density) { 37.dp.roundToPx() }, screenPx - topPx - footPx)
    val places = ScreenshotRules.placements(rows, pages.drop(1).map { it.first() }.toSet())
    // Where the rows and the footer sit in the recorded column, for cutting the pictures out of it.
    val rowAt = remember { HashMap<String, androidx.compose.ui.layout.LayoutCoordinates>() }
    val colAt = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(2) }
    val layer = rememberGraphicsLayer()
    val themes = remember { listOf(ShotTheme(null, "This chat")) + com.fariin.app.data.ChatWallpapers.all.map { ShotTheme(it.id, it.name) } }
    val chatWall = com.fariin.app.data.ChatLookStore.wallpaper(ctx, cid)
    // "This chat" with a photo wallpaper: that photo behind the bubbles (the copy already on this phone).
    val chatPhoto = com.fariin.app.ui.components.rememberWallpaperPhoto(ctx, (chatWall as? ChatWallpaper.Photo)?.id)
    val wallpaper = theme.id?.let { ChatWallpaper.Gradient(it) } ?: chatWall
    val photo = if (theme.id == null) chatPhoto else null
    val pattern = remember { com.fariin.app.ui.components.WallpaperArt.pattern(ctx) }
    val mine = com.fariin.app.data.ChatLookStore.color(ctx, cid)
        ?.let { Color(0xFF000000 or com.fariin.app.data.ChatLookRules.flatHex(it)) } ?: c.blue
    val plain = if (dark) Color.Black else Color.White
    val cardH = (LocalConfiguration.current.screenHeightDp * 0.52f).dp
    LaunchedEffect(undoFor) { if (undoFor != null) { delay(ScreenshotRules.UNDO_MS); undoFor = null } }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            // Header: close, title and count, day / night.
            Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
                RoundButton(R.drawable.ic_sf_xmark, "Close", Modifier.align(Alignment.CenterStart), onClick = close)
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Share your chat", color = c.label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(ScreenshotRules.countLine(rows.size, maxOf(1, pages.size)), color = c.secondary, fontSize = 13.sp)
                }
                RoundButton(if (dark) R.drawable.ic_sf_sun else R.drawable.ic_sf_moon_fill, if (dark) "Day" else "Night",
                    Modifier.align(Alignment.CenterEnd)) { dark = !dark }
            }
            // The card. Its inner column is recorded as it draws (the rows and the footer, not the
            // wallpaper), so Share cuts exactly what is shown into pictures, each on its own wallpaper.
            val scroll = rememberScrollState()
            LaunchedEffect(rows.size) { scroll.scrollTo(scroll.maxValue) }
            Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 22.dp).fillMaxWidth().height(cardH)
                .clip(RoundedCornerShape(32.dp)).background(plain)) {
                Column(Modifier.fillMaxWidth().verticalScroll(scroll)
                    .heightIn(min = cardH)
                    .drawBehind { drawWallpaper(wallpaper, dark, plain, pattern, photo) }
                    .onGloballyPositioned { colAt[0] = it }
                    .drawWithContent { layer.record { this@drawWithContent.drawContent() }; drawLayer(layer) }
                    .padding(top = ScreenshotRules.PAGE_TOP_DP.dp), verticalArrangement = Arrangement.Bottom) {
                    if (rows.isEmpty()) Text("No messages left", color = if (dark) Color.White else Color.Black,
                        fontSize = 15.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 50.dp))
                    rows.forEachIndexed { i, s ->
                        androidx.compose.runtime.key(s.id) {
                            ShotRow(s, places[i], cid = cid, cover = edits.cover(s.id), quote = ScreenshotRules.quoteText(s, edits), dark = dark, mine = mine, is24h = is24h,
                                onRemove = {
                                    edits = edits.remove(s.id); undoFor = s.id
                                    if (active == s.id) active = null
                                    if (picking == s.id) picking = null
                                },
                                onLong = { active = s.id; picking = null; undoFor = null },
                                onTextTap = { at ->
                                    val cv = edits.cover(s.id)
                                    edits = when {
                                        cv.whole -> edits.toggleHidden(s.id)
                                        picking == s.id -> edits.tapWord(s.id, s.text, at)
                                        cv.hides(at) -> edits.reveal(s.id, at)
                                        else -> edits
                                    }
                                },
                                onBubbleTap = { if (edits.isHidden(s.id)) edits = edits.toggleHidden(s.id) },
                                onBubbleSize = { bubbleH[s.id] = it }, onDaySize = { dayPx = it },
                                modifier = Modifier.onGloballyPositioned { rowAt[s.id] = it })
                        }
                    }
                    // The footer every picture carries.
                    Text("Fariin", color = if (dark) Color.White.copy(alpha = 0.8f) else Color.Black.copy(alpha = 0.7f),
                        fontSize = 16.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.onGloballyPositioned { colAt[1] = it }.padding(start = 20.dp, top = 10.dp, bottom = 12.dp).height(22.dp))
                }
            }
            // Swatches: this chat's own look first, then the built-in themes. Only the background changes.
            Row(Modifier.fillMaxWidth().padding(top = 20.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (t in themes) {
                    val w = t.id?.let { ChatWallpaper.Gradient(it) } ?: chatWall
                    val picked = t == theme
                    Box(Modifier.size(40.dp).then(if (picked) Modifier.border(2.dp, c.blue, CircleShape) else Modifier)
                        .padding(if (picked) 4.dp else 2.dp).clip(CircleShape)
                        .drawBehind { drawWallpaper(w, dark, plain, pattern, if (t.id == null) chatPhoto else null) }
                        .border(0.5.dp, c.label.copy(alpha = 0.08f), CircleShape)
                        .clickable(onClickLabel = t.name) { theme = t })
                }
            }
            Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp).fillMaxWidth().height(0.5.dp).background(c.separator))
            // Share.
            Box(Modifier.padding(start = 30.dp, end = 30.dp, top = 20.dp, bottom = 12.dp).fillMaxWidth().heightIn(min = 52.dp)
                .clip(CircleShape).background(c.blue.copy(alpha = if (rows.isEmpty()) 0.5f else 1f))
                .clickable(enabled = rows.isNotEmpty() && !rendering) {
                    rendering = true
                    scope.launch {
                        val ok = runCatching {
                            val col = colAt[0]!!
                            val foot = colAt[1]!!
                            fun y(l: androidx.compose.ui.layout.LayoutCoordinates) = col.localPositionOf(l, androidx.compose.ui.geometry.Offset.Zero).y.toInt()
                            val cuts = pages.map { page ->
                                val first = rowAt[rows[page.first()].id]!!
                                val last = rowAt[rows[page.last()].id]!!
                                y(first) to (y(last) + last.size.height)
                            }
                            val footer = y(foot) to (y(foot) + foot.size.height)
                            val full = layer.toImageBitmap().asAndroidBitmap()
                            val back: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit = { drawWallpaper(wallpaper, dark, plain, pattern, photo) }
                            val pictures = withContext(Dispatchers.Default) { cutPictures(full, cuts, footer, topPx, density, back) }
                            sharePictures(ctx, pictures)
                        }.getOrDefault(false)
                        rendering = false
                        if (!ok) com.fariin.app.ui.components.Snack.show("Couldn't make the picture")
                    }
                }, contentAlignment = Alignment.Center) {
                if (rendering) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                else Text("Share Picture", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        // The held message's actions (iOS edit menu: Hide Text, Hide Message, Remove), then, while
        // words are picked, a hint and Done.
        val held = rows.firstOrNull { it.id == (picking ?: active) }
        AnimatedVisibility(held != null, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 96.dp).zIndex(1f),
            enter = com.fariin.app.ui.components.Smooth.fadeIn(), exit = com.fariin.app.ui.components.Smooth.fadeOut()) {
            val h = held ?: return@AnimatedVisibility
            Row(Modifier.height(44.dp).floating(CircleShape).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (picking == h.id) {
                    Text("Tap words to hide them", color = c.secondary, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 12.dp))
                    CapsuleAction("Done", c.blue) { picking = null }
                } else {
                    if (h.text.isNotEmpty() && !edits.isHidden(h.id)) CapsuleAction("Hide Words", c.label, R.drawable.ic_eye_off) { picking = h.id; active = null }
                    CapsuleAction(if (edits.isHidden(h.id)) "Show Message" else "Hide Message", c.label) {
                        edits = edits.toggleHidden(h.id); active = null
                    }
                    CapsuleAction("Remove", c.red, R.drawable.ic_sf_trash) {
                        edits = edits.remove(h.id); undoFor = h.id; active = null
                    }
                }
            }
        }
        // Undo, for a moment after a message is taken out.
        AnimatedVisibility(undoFor != null && held == null, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 96.dp),
            enter = com.fariin.app.ui.components.Smooth.fadeIn(), exit = com.fariin.app.ui.components.Smooth.fadeOut()) {
            Row(Modifier.height(40.dp).floating(CircleShape).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Message removed", color = c.label, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text("Undo", color = c.blue, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { edits = edits.undo(); undoFor = null })
            }
        }
    }
}

/** One word button in the held message's capsule, with one of our own icons when given. */
@Composable
private fun CapsuleAction(title: String, ink: Color, icon: Int? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxHeight().clip(CircleShape).clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Image(painterResource(icon), null, Modifier.size(17.dp), colorFilter = ColorFilter.tint(ink))
            Spacer(Modifier.width(6.dp))
        }
        Text(title, color = ink, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** A 44 dp round glass button with one of our own icons. */
@Composable
private fun RoundButton(icon: Int, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.size(44.dp).floating(CircleShape).clickable(onClickLabel = label, onClick = onClick), contentAlignment = Alignment.Center) {
        Image(painterResource(icon), label, Modifier.size(20.dp), colorFilter = ColorFilter.tint(Fariin.colors.label))
    }
}

/**
 * One message in the card: a simplified bubble in the chat's colours (mine in the chat colour, theirs
 * in the received grey), its day pill and group name where they start. Swipe left to take it out;
 * touch and hold for its actions. Hidden words are drawn as dots, never as the words; a tap on
 * hidden words (or a hidden message) shows them again, and while picking a tap on a word hides it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShotRow(
    s: ScreenshotRules.Shot, place: ScreenshotRules.Place, cid: String, cover: ScreenshotRules.Cover, quote: String?, dark: Boolean, mine: Color, is24h: Boolean,
    onRemove: () -> Unit, onLong: () -> Unit, onTextTap: (Int) -> Unit, onBubbleTap: () -> Unit,
    onBubbleSize: (Int) -> Unit, onDaySize: (Int) -> Unit, modifier: Modifier = Modifier,
) {
    val hidden = cover.whole
    val picture = rememberShotPicture(s.pic, cid)
    val isSticker = s.pic?.stickerId != null
    // A sticker with no words stands on the wallpaper, as in the chat; its time sits in a chip.
    val bare = isSticker && picture != null && s.text.isEmpty() && s.replyName == null && !s.forwarded && s.senderName == null
    val longNow = rememberUpdatedState(onLong)
    val tapNow = rememberUpdatedState(onTextTap)
    var layout by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
    // Taps on the words find the letter under the finger (the drawn text keeps every place).
    val textTaps = Modifier.pointerInput(s.id) {
        detectTapGestures(
            onTap = { p -> layout?.let { tapNow.value(it.getOffsetForPosition(p)) } },
            onLongPress = { longNow.value() })
    }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val removeAt = with(density) { 80.dp.toPx() }
    val dx = remember { Animatable(0f) }
    val ink = if (dark) Color.White else Color.Black
    val chip = if (dark) Color.Black.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.75f)
    val chipInk = if (dark) Color.White.copy(alpha = 0.85f) else Color.Black.copy(alpha = 0.6f)
    Column(modifier.fillMaxWidth()) {
        if (place.showDay) Box(Modifier.onSizeChanged { onDaySize(it.height) }.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
            Text(s.day, color = chipInk, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clip(CircleShape).background(chip).padding(horizontal = 10.dp, vertical = 3.dp))
        }
        Box(Modifier.onSizeChanged { onBubbleSize(it.height) }.fillMaxWidth()
            .pointerInput(s.id) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (dx.value <= -removeAt) onRemove()
                        else scope.launch { dx.animateTo(0f) }
                    },
                    onDragCancel = { scope.launch { dx.animateTo(0f) } },
                ) { change, amount -> change.consume(); scope.launch { dx.snapTo((dx.value + amount * 0.85f).coerceAtMost(0f)) } }
            }
            .combinedClickable(onClick = onBubbleTap, onLongClick = onLong, onLongClickLabel = "Hide or remove")
            .padding(horizontal = 10.dp, vertical = 2.dp)) {
            val body = ScreenshotRules.drawnText(s.text, cover)
            if (s.kind == ScreenshotRules.Kind.SYSTEM) {
                Text(body, color = chipInk, fontSize = 13.sp, onTextLayout = { layout = it }, modifier = Modifier.align(Alignment.Center)
                    .offset { IntOffset(dx.value.toInt(), 0) }.clip(RoundedCornerShape(10.dp)).background(chip).padding(horizontal = 10.dp, vertical = 4.dp).then(textTaps))
            } else {
                val fill = if (bare) Color.Transparent else if (s.isMe) mine else if (dark) Color(0xFF26262B) else Color(0xFFF2F2F2)
                val text = if (s.isMe && !bare) Color.White else ink
                val meta = if (bare) chipInk else if (s.isMe) Color.White.copy(alpha = 0.7f) else ink.copy(alpha = 0.45f)
                Column(Modifier.align(if (s.isMe) Alignment.CenterEnd else Alignment.CenterStart).offset { IntOffset(dx.value.toInt(), 0) }
                    .widthIn(max = 290.dp).clip(RoundedCornerShape(18.dp)).background(fill)
                    .padding(start = if (bare) 0.dp else 12.dp, end = if (bare) 0.dp else 10.dp, top = 6.dp, bottom = 5.dp)) {
                    if (place.showName && s.senderName != null)
                        Text(s.senderName, color = senderColor(s.authorId), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    if (s.forwarded) Text("Forwarded message", color = meta, fontSize = 13.sp)
                    if (s.replyName != null && !hidden) {
                        Row(Modifier.padding(vertical = 3.dp).clip(RoundedCornerShape(6.dp))
                            .background(if (s.isMe) Color.White.copy(alpha = 0.18f) else ink.copy(alpha = 0.06f))) {
                            Box(Modifier.width(3.dp).height(36.dp).background(if (s.isMe) Color.White else senderColor(s.authorId)))
                            Column(Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
                                Text(s.replyName, color = text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                Text(quote.orEmpty(), color = text.copy(alpha = 0.8f), fontSize = 13.sp, maxLines = 1)
                            }
                        }
                    }
                    if (s.kind == ScreenshotRules.Kind.MEDIA) {
                        if (picture != null) {
                            // The real picture, from the copy this phone already opened. Hidden: dots
                            // in its place, the picture itself is not drawn.
                            Box(Modifier.padding(vertical = 3.dp).width(if (isSticker) 140.dp else 236.dp)
                                .aspectRatio(if (isSticker) 1f else s.pic!!.aspect).clip(RoundedCornerShape(12.dp))
                                .then(if (hidden) Modifier.background(ink.copy(alpha = 0.10f)) else Modifier),
                                contentAlignment = Alignment.Center) {
                                if (hidden) Text(ScreenshotRules.coverText(s.label.orEmpty()), color = text, fontSize = 17.sp, letterSpacing = 1.sp)
                                else {
                                    Image(picture, null, Modifier.fillMaxSize(),
                                        contentScale = if (isSticker) ContentScale.Fit else ContentScale.Crop)
                                    if (s.pic!!.video) Box(Modifier.size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
                                        contentAlignment = Alignment.Center) {
                                        Image(painterResource(R.drawable.ic_sf_play_fill), null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(Color.White))
                                    }
                                }
                            }
                        } else Row(verticalAlignment = Alignment.CenterVertically) {
                            // Not opened on this phone: its label only, never a new download.
                            Image(painterResource(R.drawable.ic_sf_photo), null, Modifier.size(16.dp), colorFilter = ColorFilter.tint(text))
                            Spacer(Modifier.width(6.dp))
                            val label = s.label.orEmpty()
                            Text(if (hidden) ScreenshotRules.coverText(label) else label, color = text, fontSize = 17.sp)
                        }
                        if (s.text.isNotEmpty()) Text(body, color = text, fontSize = 17.sp, onTextLayout = { layout = it }, modifier = textTaps,
                            letterSpacing = if (hidden) 1.sp else androidx.compose.ui.unit.TextUnit.Unspecified)
                    } else Text(body, color = text, fontSize = 17.sp, onTextLayout = { layout = it }, modifier = textTaps,
                        letterSpacing = if (hidden) 1.sp else androidx.compose.ui.unit.TextUnit.Unspecified)
                    // A link card's title, only while nothing in the message is hidden (a title can
                    // give the hidden words away).
                    if (s.linkTitle != null && cover.isEmpty) Row(Modifier.padding(top = 4.dp).clip(RoundedCornerShape(6.dp))
                        .background(if (s.isMe) Color.White.copy(alpha = 0.18f) else ink.copy(alpha = 0.06f))) {
                        Box(Modifier.width(3.dp).height(40.dp).background(if (s.isMe) Color.White else Fariin.colors.blue))
                        Column(Modifier.padding(horizontal = 8.dp, vertical = 3.dp)) {
                            s.linkHost?.takeIf { it.isNotEmpty() }?.let { Text(it, color = text.copy(alpha = 0.75f), fontSize = 13.sp, maxLines = 1) }
                            Text(s.linkTitle, color = text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                        }
                    }
                    Row(Modifier.align(Alignment.End)
                        .then(if (bare) Modifier.clip(CircleShape).background(chip).padding(horizontal = 7.dp, vertical = 1.dp) else Modifier),
                        verticalAlignment = Alignment.CenterVertically) {
                        val time = ThreadLogic.timeLabel(s.timeMs, is24h)
                        Text(if (s.edited) "edited $time" else time, color = meta, fontSize = 11.sp)
                        if (s.isMe) ShotTick(s.tick, if (s.tick != Tick.READ) meta else if (bare) Fariin.colors.blue else Color.White)
                    }
                }
            }
        }
    }
}

/**
 * A media row's real picture, read only from this phone's own copies: the chat's opened files, a
 * sticker seen before or one shipped with the app. Nothing is downloaded here; null draws the label.
 */
@Composable
private fun rememberShotPicture(pic: ScreenshotRules.Pic?, cid: String): androidx.compose.ui.graphics.ImageBitmap? {
    val ctx = LocalContext.current.applicationContext
    return androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, pic, cid) {
        if (pic != null) value = withContext(Dispatchers.IO) { runCatching { loadShotPicture(ctx, pic, cid) }.getOrNull() }
    }.value
}

private fun loadShotPicture(ctx: android.content.Context, pic: ScreenshotRules.Pic, cid: String): androidx.compose.ui.graphics.ImageBitmap? {
    val id = pic.stickerId
    if (id != null) {
        val pack = pic.stickerPack.orEmpty()
        val own = if (pack == com.fariin.app.data.StickerFavorites.MINE) com.fariin.app.data.StickerFavorites.png(id)
            else SeenStickers.file(ctx, pack, id)
        if (own != null) return decodeSmall(own)
        if (pack.isNotEmpty() && pack != com.fariin.app.data.StickerFavorites.MINE) {
            val asset = com.fariin.app.data.StickerCatalog.stillPath(ctx, pack, id)
                ?: com.fariin.app.data.StickerCatalog.assetPath(ctx, pack, id)?.takeIf { !it.endsWith(".tgs") }
            if (asset != null) return ctx.assets.open(asset).use { android.graphics.BitmapFactory.decodeStream(it) }?.asImageBitmap()
        }
    }
    val url = pic.url ?: return null
    // peek: the opened copy only (an encrypted file is kept opened); it never starts a download.
    val file = com.fariin.app.media.ChatMedia(ctx, cid, null).peek(url) ?: return null
    return decodeSmall(file)
}

/** Decoded at about the size the picture shows, as a plain (not hardware) bitmap the picture can hold. */
private fun decodeSmall(f: java.io.File, maxPx: Int = 900): androidx.compose.ui.graphics.ImageBitmap? {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(f.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
    return android.graphics.BitmapFactory.decodeFile(f.path, opts)?.asImageBitmap()
}

/** The chat's own drawn ticks (one sent, two delivered or read), the clock while sending, ! when it failed. */
@Composable
private fun ShotTick(t: Tick, tint: Color) {
    val (icon, size) = when (t) {
        Tick.SENT -> R.drawable.ic_tick_single to null
        Tick.DELIVERED, Tick.READ -> R.drawable.ic_tick_double to null
        Tick.SENDING -> R.drawable.ic_sf_clock to 13.dp
        Tick.FAILED -> R.drawable.ic_sf_exclamation_circle to 13.dp
        else -> return
    }
    Spacer(Modifier.width(3.dp))
    val ink = if (t == Tick.FAILED) Fariin.colors.red else tint
    // At the drawn size, as the chat's bubble draws them.
    Image(painterResource(icon), null, if (size != null) Modifier.size(size) else Modifier, colorFilter = ColorFilter.tint(ink))
}

// Sender names use the chat's own colours (ThreadScreen senderColor), so the picture matches.

/**
 * Cuts the recorded rows into pictures (iOS pageView): each one its own wallpaper ([back]), the room
 * above, its rows ([cuts]: top and bottom in the recording), then the footer copied under them.
 */
private fun cutPictures(
    recorded: android.graphics.Bitmap, cuts: List<Pair<Int, Int>>, footer: Pair<Int, Int>, topPx: Int,
    density: androidx.compose.ui.unit.Density, back: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit,
): List<android.graphics.Bitmap> {
    // A plain copy: a hardware bitmap cannot be drawn onto a plain canvas.
    val full = recorded.copy(android.graphics.Bitmap.Config.ARGB_8888, false) ?: return emptyList()
    val w = full.width
    val footH = (footer.second - footer.first).coerceAtLeast(0)
    return cuts.map { (top, bottom) ->
        val t = top.coerceIn(0, full.height)
        val b = bottom.coerceIn(t, full.height)
        val h = topPx + (b - t) + footH
        val out = android.graphics.Bitmap.createBitmap(w, h.coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
        runCatching {
            androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(density, androidx.compose.ui.unit.LayoutDirection.Ltr,
                androidx.compose.ui.graphics.Canvas(out.asImageBitmap()), androidx.compose.ui.geometry.Size(w.toFloat(), h.toFloat())) { back() }
        }
        val cv = android.graphics.Canvas(out)
        cv.drawBitmap(full, android.graphics.Rect(0, t, w, b), android.graphics.Rect(0, topPx, w, topPx + b - t), null)
        if (footH > 0) cv.drawBitmap(full, android.graphics.Rect(0, footer.first, w, footer.second),
            android.graphics.Rect(0, h - footH, w, h), null)
        out
    }.also { full.recycle() }
}

/** Saves the pictures in cache/open (served by our FileProvider) and opens the system share sheet with all of them. */
private suspend fun sharePictures(ctx: android.content.Context, pictures: List<android.graphics.Bitmap>): Boolean {
    if (pictures.isEmpty()) return false
    val uris = withContext(Dispatchers.IO) {
        val dir = java.io.File(ctx.cacheDir, "open").also { it.mkdirs() }
        // Only the newest pictures are kept; ones shared earlier go.
        dir.listFiles()?.filter { it.name.startsWith(ScreenshotRules.FILE_PREFIX) }?.forEach { it.delete() }
        val stamp = System.currentTimeMillis()
        pictures.mapIndexed { i, bmp ->
            val out = java.io.File(dir, ScreenshotRules.FILE_PREFIX + stamp + "_" + (i + 1) + ".png")
            out.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
            androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", out)
        }
    }
    val clip = android.content.ClipData.newRawUri(null, uris.first()).apply { uris.drop(1).forEach { addItem(android.content.ClipData.Item(it)) } }
    val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uris.first())
        else Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/png").putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = clip
    ctx.startActivity(Intent.createChooser(send, null))
    return true
}
