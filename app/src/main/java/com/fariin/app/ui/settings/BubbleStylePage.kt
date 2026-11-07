package com.fariin.app.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.data.BubbleArt
import com.fariin.app.data.BubbleStyles
import com.fariin.app.data.ChatLookStore
import com.fariin.app.ui.components.brush
import com.fariin.app.ui.theme.Fariin
import com.fariin.app.ui.thread.bubbles.BubbleArtCache
import com.fariin.app.ui.thread.bubbles.artBubble
import com.fariin.app.ui.thread.bubbles.fillColor
import com.fariin.app.ui.thread.bubbles.ink
import com.fariin.app.ui.thread.bubbles.rememberBubbleThumb

internal const val PAGE_BUBBLE_STYLE = "look:bubblestyle"

/**
 * The chips under the preview: "Color" first (owner 2026-11-07, his mockup: the chat colour palette
 * as circles, the Bubble Color page's own grid), then the picture sections. Title Case like them.
 */
private const val COLOR = "Color"

/** The picture styles the admin panel has not hidden (config/features.bubblesHidden), live. */
@Composable
private fun offeredPictures(): List<BubbleArt.Spec> {
    val hidden by com.fariin.app.data.HiddenLooks.bubbles.collectAsState()
    return remember(hidden) { com.fariin.app.data.HiddenLooks.visible(BubbleArt.all, hidden) { it.id } }
}

/**
 * Settings > Appearance > Bubble Style (SPEC 4, iOS BubbleStylesPage). Save at the top; the chat info
 * "Chat Bubble" row opens this same page.
 */
@Composable
internal fun BubbleStylePage(onClose: () -> Unit) {
    BubbleStyleChooser(
        bottomPad = 16.dp + com.fariin.app.ui.shell.bottomBarInset(),
        onSaved = onClose,
        bar = { dirty, save ->
            PageBar("Bubble Style", onBack = onClose, action = {
                com.fariin.app.ui.components.FloatingTextAction("Save", enabled = dirty) { save() }
            })
        },
    )
}

/**
 * The picker itself, shared by the Settings page and the chat's long-press sheet (owner 2026-11-07):
 * the live preview stays pinned at the top while one row of category chips picks which grid shows
 * below; "Default" (no style) is the first tile of every grid. Nothing changes until Save, and only
 * the messages sent after it carry the style.
 *
 * The Color chip shows the chat colour circles (Auto, the palette, my own, +). Picking one shows it
 * on the preview's bubble at once; Save makes it the colour of all my chats and clears a picture
 * style, so one or the other applies. Picking a picture style drops a colour picked here.
 *
 * Tiles are the style pictures, small, in a lazy grid: only the tiles on screen decode, into their
 * own cache that is dropped when the picker closes.
 */
@Composable
internal fun BubbleStyleChooser(
    bottomPad: androidx.compose.ui.unit.Dp,
    onSaved: () -> Unit,
    /** Opened from a chat: the style and colour picked here are that chat's own; null = the Settings ones. */
    cid: String? = null,
    bar: @Composable (dirty: Boolean, save: () -> Unit) -> Unit,
) {
    val ctx = LocalContext.current
    remember { BubbleStyles.init(ctx) }
    val c = Fariin.colors
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    var saved by remember { mutableStateOf(BubbleStyles.forChat(cid)?.takeIf { BubbleStyles.known(it) }) }
    var selected by remember { mutableStateOf(saved) }
    val savedDecor = remember { BubbleStyles.decorations() }
    var decor by remember { mutableStateOf(savedDecor) }
    var plusNote by remember { mutableStateOf(false) }
    var category by remember {
        mutableStateOf(BubbleArt.find(saved)?.section ?: COLOR)
    }
    DisposableEffect(Unit) { onDispose { BubbleArtCache.dropThumbs() } }
    // My chat colour as picked here, null = Auto; applied on Save.
    var savedColor by remember { mutableStateOf(if (cid != null) ChatLookStore.ownColor(ctx, cid) else ChatLookStore.globalChosen(ctx)) }
    var chatColor by remember { mutableStateOf(savedColor) }
    val dirty = selected != saved || decor != savedDecor || chatColor != savedColor
    // "Default" is the plain bubble, drawn in my chat colour (the wallpaper's own on Auto).
    ChatLookStore.version.intValue
    val plain: Brush = (chatColor ?: ChatLookStore.globalAuto(ctx))?.brush() ?: SolidColor(c.blue)
    fun pickStyle(id: String?) { selected = id; if (id != null) chatColor = savedColor }
    val thumbPx = with(LocalDensity.current) { 96.dp.roundToPx() }
    val grid = rememberLazyGridState()
    LaunchedEffect(category) { grid.scrollToItem(0) }
    // A section whose styles are all hidden loses its chip; if it was showing, Color shows instead.
    val pictures = offeredPictures()
    val categories = remember(pictures) { listOf(COLOR) + BubbleArt.sections.filter { sec -> pictures.any { it.section == sec } } }
    LaunchedEffect(categories) { if (category !in categories) category = COLOR }

    Column(Modifier.fillMaxSize().background(c.bgSecondary)) {
        bar(dirty) {
            if (!BubbleStyles.mayUse(selected)) plusNote = true
            else {
                // A colour applies only with no picture style; saving null clears a saved style.
                if (selected == null && chatColor != savedColor) {
                    if (cid != null) ChatLookStore.setColor(ctx, cid, chatColor) else ChatLookStore.setDefaultColor(ctx, chatColor)
                }
                if (cid != null) {
                    // This chat only: the Settings style stays; decorations are one switch for all.
                    BubbleStyles.saveForChat(cid, selected)
                    if (decor != savedDecor) BubbleStyles.save(BubbleStyles.current(), decor)
                } else BubbleStyles.save(selected, decor)
                onSaved()
            }
        }
        // Pinned: never scrolls away, redrawn at once when a tile is tapped.
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp)) {
            Preview(selected, plain)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (cid != null) "Only this chat" else "Applies to messages you send from now on.", fontSize = 13.sp,
                    color = c.secondary, modifier = Modifier.weight(1f).padding(start = 4.dp))
                // Back to the app's default bubble at once (no style, default colour), as the chat
                // wallpaper sheet's Reset does. Only while this page's scope has a style or colour.
                if (saved != null || savedColor != null) com.fariin.app.ui.components.FloatingTextAction("Reset") {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                    if (cid != null) resetChatBubble(ctx, cid) else resetGlobalBubble(ctx)
                    saved = null; selected = null; savedColor = null; chatColor = null
                }
            }
        }
        LazyRow(
            Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(categories.size, key = { categories[it] }) { i ->
                val cat = categories[i]
                Chip(cat, cat == category) { category = cat }
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            state = grid,
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = bottomPad),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (category == COLOR) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "colors") {
                    ChatColorGrid(chatColor, ring = selected == null) { spec -> chatColor = spec; selected = null }
                }
            } else {
                item(key = "default") { Tile("Default", selected == null, onTap = { pickStyle(null) }) { DefaultSwatch(plain) } }
                items(pictures.filter { it.section == category }, key = { "p:" + it.id }) { s ->
                    Tile(s.name, selected == s.id, onTap = { pickStyle(s.id) }) { PictureSwatch(s, thumbPx) }
                }
            }
        }
    }
    if (plusNote) AlertDialog(onDismissRequest = { plusNote = false },
        title = { Text("Fariin Plus") },
        text = { Text("This style is part of Fariin Plus, coming soon. The free styles are ready to use now.") },
        confirmButton = { TextButton({ plusNote = false }) { Text("OK", color = c.accent) } }, containerColor = c.card)
}

/**
 * The "Bubble Styles" strip on one chat's wallpaper picker (owner 2026-11-07): six picture styles,
 * the one in use first and ringed, and "See all" opening the full chooser as a sheet. A tap picks a
 * style at once with a light tick; the style is mine for every chat, so the caption says so.
 */
@Composable
internal fun BubbleStylesStrip(cid: String) {
    val ctx = LocalContext.current
    remember { BubbleStyles.init(ctx) }
    val c = Fariin.colors
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val v = BubbleStyles.version.intValue
    val current = remember(v, cid) { BubbleStyles.forChat(cid) }
    val pictures = offeredPictures()
    val picks = remember(current, pictures) {
        val first = pictures.take(6)
        val cur = BubbleArt.find(current)?.takeIf { it in pictures }
        if (cur != null && cur !in first) listOf(cur) + first.take(5) else first
    }
    // Every picture style hidden: no strip at all.
    if (picks.isEmpty()) return
    var all by remember { mutableStateOf(false) }
    val thumbPx = with(LocalDensity.current) { 92.dp.roundToPx() }
    DisposableEffect(Unit) { onDispose { BubbleArtCache.dropThumbs() } }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Bubble Styles", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = c.label, modifier = Modifier.weight(1f))
            Text("See all", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = c.accent,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable { all = true }.padding(horizontal = 8.dp, vertical = 4.dp))
        }
        // Cards about as wide as the wallpaper tiles below: a 92x58 picture inside 5dp padding.
        LazyRow(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(picks.size, key = { picks[it].id }) { i ->
                val s = picks[i]
                Box(Modifier.width(102.dp)) {
                    Tile(s.name, current == s.id, pad = 5.dp, swatchHeight = 58.dp, onTap = {
                        if (current != s.id) {
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                            BubbleStyles.saveForChat(cid, s.id)
                        }
                    }) { PictureSwatch(s, thumbPx) }
                }
            }
        }
        Text("Only this chat", fontSize = 13.sp, color = c.secondary, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
    }
    if (all) BubbleStyleSheet.Show(cid, onDismiss = { all = false })
}

/** One category in the row above the grid; the shown one filled. */
@Composable
private fun Chip(title: String, on: Boolean, onTap: () -> Unit) {
    val c = Fariin.colors
    Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = if (on) Color.White else c.label, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) c.accent else c.card)
            .clickable(onClick = onTap).padding(horizontal = 14.dp, vertical = 7.dp))
}

/** My bubble in the chosen style (as the chat draws it, the picture for a picture style), then a reply. */
@Composable
private fun Preview(selected: String?, plain: Brush) {
    val c = Fariin.colors
    val text = androidx.compose.ui.text.TextStyle(fontSize = 17.sp, lineHeight = 22.sp)
    val maxW = (LocalConfiguration.current.screenWidthDp * 0.72f).dp
    // Owner 2026-11-07: changing style must not resize the card (the chips and grid jumped). A
    // FIXED height that holds the tallest picture style, one-line samples, the same width cap for
    // every style, and the new style faded in where the old one was.
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.bg).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val mine = "My new bubble style!"
        androidx.compose.animation.Crossfade(selected, animationSpec = androidx.compose.animation.core.tween(180),
            label = "bubble preview", modifier = Modifier.fillMaxWidth().height(PREVIEW_MINE_HEIGHT)) { sel ->
            val art = BubbleArt.find(sel)
            val colour = if (art == null) BubbleStyles.find(sel) else null
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
                when {
                    art != null -> Text(mine, style = text, color = art.ink(), maxLines = 1,
                        modifier = Modifier.widthIn(max = maxW).artBubble(art, c.dark))
                    else -> {
                        val look = colour?.look(c.dark)
                        Text(mine, style = text, color = look?.let { Color(0xFF000000 or it.text) } ?: Color.White, maxLines = 1,
                            modifier = Modifier.widthIn(max = maxW).clip(RoundedCornerShape(18.dp))
                                .background(look?.let { brushOf(it) } ?: plain).padding(horizontal = 14.dp, vertical = 9.dp))
                    }
                }
            }
        }
        Text("Looks so cute!", style = text, color = c.label, maxLines = 1,
            modifier = Modifier.widthIn(max = maxW).clip(RoundedCornerShape(18.dp))
                .background(if (c.dark) Color(0xFF2C2C2E) else Color(0xFFE5E5EA)).padding(horizontal = 14.dp, vertical = 9.dp))
    }
}

private fun brushOf(look: BubbleStyles.Look): Brush {
    val colors = look.fill.map { Color(0xFF000000 or it) }
    return if (colors.size > 1) Brush.verticalGradient(colors) else SolidColor(colors.first())
}

/** One style in the grid: its swatch on a card, the name under it; the chosen one ringed. */
@Composable
private fun Tile(name: String, on: Boolean, plus: Boolean = false, pad: androidx.compose.ui.unit.Dp = 6.dp,
                 swatchHeight: androidx.compose.ui.unit.Dp = 44.dp, onTap: () -> Unit, swatch: @Composable () -> Unit) {
    val c = Fariin.colors
    val shape = RoundedCornerShape(14.dp)
    Column(Modifier.clip(shape).background(c.card)
        .border(2.5.dp, if (on) c.accent else Color.Transparent, shape)
        .clickable(onClick = onTap).padding(pad),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(swatchHeight), contentAlignment = Alignment.Center) { swatch() }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false))
            if (plus) {
                Spacer(Modifier.width(4.dp))
                Text("Plus", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xFFAF52DE)).padding(horizontal = 5.dp, vertical = 2.dp))
            }
        }
    }
}

/** The plain bubble, in my chat colour. */
@Composable
private fun DefaultSwatch(fill: Brush) = Bar(fill, Color.White)

/** A bubble-shaped bar with a short line for the words. */
@Composable
private fun Bar(fill: Brush, ink: Color) {
    Box(Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(14.dp)).background(fill),
        contentAlignment = Alignment.CenterStart) {
        Box(Modifier.padding(start = 12.dp).size(width = 30.dp, height = 5.dp).clip(RoundedCornerShape(50)).background(ink.copy(alpha = 0.55f)))
    }
}

/** The style's picture, whole and small; its flat colour until it has decoded. */
@Composable
private fun PictureSwatch(s: BubbleArt.Spec, widthPx: Int) {
    val img = rememberBubbleThumb(s, widthPx)
    if (img != null) Image(img, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
    else Box(Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(10.dp)).background(s.fillColor()))
}

/** The preview's own-bubble row: tall enough for the tallest picture style's art around one line. */
private val PREVIEW_MINE_HEIGHT = 120.dp

/** One chat back to the app's default bubble: no style here (an explicit none over the Settings
 *  one) and the chat's own colour cleared. The Bubble Style page's Reset and the chat sheet's Reset. */
internal fun resetChatBubble(ctx: android.content.Context, cid: String) {
    BubbleStyles.init(ctx)
    BubbleStyles.saveForChat(cid, null)
    ChatLookStore.setColor(ctx, cid, null)
}

/** The Settings bubble back to the default: no style, the Auto colour. Chats' own picks stay. */
internal fun resetGlobalBubble(ctx: android.content.Context) {
    BubbleStyles.init(ctx)
    BubbleStyles.save(null, BubbleStyles.decorations())
    ChatLookStore.setDefaultColor(ctx, null)
}
