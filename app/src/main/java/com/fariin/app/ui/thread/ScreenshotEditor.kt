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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
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
    val places = ScreenshotRules.placements(rows)
    val layer = rememberGraphicsLayer()
    val themes = remember { listOf(ShotTheme(null, "This chat")) + com.fariin.app.data.ChatWallpapers.all.map { ShotTheme(it.id, it.name) } }
    val wallpaper = theme.id?.let { ChatWallpaper.Gradient(it) } ?: com.fariin.app.data.ChatLookStore.wallpaper(ctx, cid)
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
                    Text(ScreenshotRules.countLine(rows.size), color = c.secondary, fontSize = 13.sp)
                }
                RoundButton(if (dark) R.drawable.ic_sf_sun else R.drawable.ic_sf_moon_fill, if (dark) "Day" else "Night",
                    Modifier.align(Alignment.CenterEnd)) { dark = !dark }
            }
            // The card. Its inner column is recorded as it draws, so Share saves exactly what is shown,
            // full length (not only the part scrolled into view).
            val scroll = rememberScrollState()
            LaunchedEffect(rows.size) { scroll.scrollTo(scroll.maxValue) }
            Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 22.dp).fillMaxWidth().height(cardH)
                .clip(RoundedCornerShape(32.dp)).background(plain)) {
                Column(Modifier.fillMaxWidth().verticalScroll(scroll)
                    .drawWithContent { layer.record { this@drawWithContent.drawContent() }; drawLayer(layer) }
                    .heightIn(min = cardH)
                    .drawBehind { drawWallpaper(wallpaper, dark, plain, pattern, null) }
                    .padding(top = 12.dp), verticalArrangement = Arrangement.Bottom) {
                    if (rows.isEmpty()) Text("No messages left", color = if (dark) Color.White else Color.Black,
                        fontSize = 15.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 50.dp))
                    rows.forEachIndexed { i, s ->
                        androidx.compose.runtime.key(s.id) {
                            ShotRow(s, places[i], cover = edits.cover(s.id), dark = dark, mine = mine, is24h = is24h,
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
                                onBubbleTap = { if (edits.isHidden(s.id)) edits = edits.toggleHidden(s.id) })
                        }
                    }
                    // The footer every picture carries.
                    Text("Fariin", color = if (dark) Color.White.copy(alpha = 0.8f) else Color.Black.copy(alpha = 0.7f),
                        fontSize = 16.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 20.dp, top = 10.dp, bottom = 12.dp).height(22.dp))
                }
            }
            // Swatches: this chat's own look first, then the built-in themes. Only the background changes.
            Row(Modifier.fillMaxWidth().padding(top = 20.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (t in themes) {
                    val w = t.id?.let { ChatWallpaper.Gradient(it) } ?: com.fariin.app.data.ChatLookStore.wallpaper(ctx, cid)
                    val picked = t == theme
                    Box(Modifier.size(40.dp).then(if (picked) Modifier.border(2.dp, c.blue, CircleShape) else Modifier)
                        .padding(if (picked) 4.dp else 2.dp).clip(CircleShape)
                        .drawBehind { drawWallpaper(w, dark, plain, pattern, null) }
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
                        val ok = runCatching { sharePicture(ctx, layer.toImageBitmap().asAndroidBitmap()) }.getOrDefault(false)
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
    s: ScreenshotRules.Shot, place: ScreenshotRules.Place, cover: ScreenshotRules.Cover, dark: Boolean, mine: Color, is24h: Boolean,
    onRemove: () -> Unit, onLong: () -> Unit, onTextTap: (Int) -> Unit, onBubbleTap: () -> Unit,
) {
    val hidden = cover.whole
    val longNow = rememberUpdatedState(onLong)
    val tapNow = rememberUpdatedState(onTextTap)
    var layout by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
    // Taps on the words find the letter under the finger (the drawn text keeps every place).
    val textTaps = Modifier.pointerInput(s.id) {
        androidx.compose.foundation.gestures.detectTapGestures(
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
    Column(Modifier.fillMaxWidth()) {
        if (place.showDay) Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
            Text(s.day, color = chipInk, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clip(CircleShape).background(chip).padding(horizontal = 10.dp, vertical = 3.dp))
        }
        Box(Modifier.fillMaxWidth()
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
                val fill = if (s.isMe) mine else if (dark) Color(0xFF26262B) else Color(0xFFF2F2F2)
                val text = if (s.isMe) Color.White else ink
                val meta = if (s.isMe) Color.White.copy(alpha = 0.7f) else ink.copy(alpha = 0.45f)
                Column(Modifier.align(if (s.isMe) Alignment.CenterEnd else Alignment.CenterStart).offset { IntOffset(dx.value.toInt(), 0) }
                    .widthIn(max = 290.dp).clip(RoundedCornerShape(18.dp)).background(fill)
                    .padding(start = 12.dp, end = 10.dp, top = 6.dp, bottom = 5.dp)) {
                    if (place.showName && s.senderName != null)
                        Text(s.senderName, color = senderColor(s.authorId), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    if (s.forwarded) Text("Forwarded message", color = meta, fontSize = 13.sp)
                    if (s.replyName != null && !hidden) {
                        Row(Modifier.padding(vertical = 3.dp).clip(RoundedCornerShape(6.dp))
                            .background(if (s.isMe) Color.White.copy(alpha = 0.18f) else ink.copy(alpha = 0.06f))) {
                            Box(Modifier.width(3.dp).height(36.dp).background(if (s.isMe) Color.White else senderColor(s.authorId)))
                            Column(Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
                                Text(s.replyName, color = text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                Text(s.replyText.orEmpty(), color = text.copy(alpha = 0.8f), fontSize = 13.sp, maxLines = 1)
                            }
                        }
                    }
                    if (s.kind == ScreenshotRules.Kind.MEDIA && !hidden) Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(painterResource(R.drawable.ic_sf_photo), null, Modifier.size(16.dp), colorFilter = ColorFilter.tint(text))
                        Spacer(Modifier.width(6.dp))
                        Text(body, color = text, fontSize = 17.sp, onTextLayout = { layout = it }, modifier = textTaps)
                    } else Text(body, color = text, fontSize = 17.sp, onTextLayout = { layout = it }, modifier = textTaps,
                        letterSpacing = if (hidden) 1.sp else androidx.compose.ui.unit.TextUnit.Unspecified)
                    Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                        val time = ThreadLogic.timeLabel(s.timeMs, is24h)
                        Text(if (s.edited) "edited $time" else time, color = meta, fontSize = 11.sp)
                        tickMark(s.tick)?.let { Text(" $it", color = meta, fontSize = 11.sp) }
                    }
                }
            }
        }
    }
}

private fun tickMark(t: Tick): String? = when (t) {
    Tick.SENT -> "✓"
    Tick.DELIVERED, Tick.READ -> "✓✓"
    Tick.FAILED -> "!"
    else -> null
}

// Sender names use the chat's own colours (ThreadScreen senderColor), so the picture matches.

/** Saves the picture in cache/open (served by our FileProvider) and opens the system share sheet. */
private suspend fun sharePicture(ctx: android.content.Context, picture: android.graphics.Bitmap): Boolean {
    val uri = withContext(Dispatchers.IO) {
        val bmp = picture.copy(android.graphics.Bitmap.Config.ARGB_8888, false) ?: return@withContext null
        val dir = java.io.File(ctx.cacheDir, "open").also { it.mkdirs() }
        // Only the newest picture is kept; an older one shared earlier goes.
        dir.listFiles()?.filter { it.name.startsWith(ScreenshotRules.FILE_PREFIX) }?.forEach { it.delete() }
        val out = java.io.File(dir, ScreenshotRules.FILE_PREFIX + System.currentTimeMillis() + ".png")
        out.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", out)
    } ?: return false
    val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = android.content.ClipData.newRawUri(null, uri) }
    ctx.startActivity(Intent.createChooser(send, null))
    return true
}
