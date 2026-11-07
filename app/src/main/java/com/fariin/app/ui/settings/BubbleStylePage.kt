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
import com.fariin.app.data.ChatColorSpec
import com.fariin.app.data.ChatColors
import com.fariin.app.data.ChatLookStore
import com.fariin.app.ui.components.brush
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import com.fariin.app.ui.theme.Fariin
import com.fariin.app.ui.thread.bubbles.BubbleArtCache
import com.fariin.app.ui.thread.bubbles.artBubble
import com.fariin.app.ui.thread.bubbles.fillColor
import com.fariin.app.ui.thread.bubbles.ink
import com.fariin.app.ui.thread.bubbles.rememberBubbleThumb

internal const val PAGE_BUBBLE_STYLE = "look:bubblestyle"

/** The chips under the preview, in this order (owner 2026-11-07: colours last). */
private const val COLOURS = "Colours"
private val CATEGORIES: List<String> = BubbleArt.sections + COLOURS

/** The chat colours' names (iOS ChatColors.presets comments), in [ChatColors.presets] order. */
private val PRESET_NAMES = listOf(
    "Ultramarine", "Crimson", "Vermilion", "Burlap", "Forest", "Wintergreen", "Teal", "Blue", "Indigo",
    "Violet", "Plum", "Taupe", "Steel", "Ember", "Midnight", "Infrared", "Lagoon", "Fluorescent", "Basil",
    "Sublime", "Sea", "Tangerine",
)

/** A chat colour's name for a Settings row; one I mixed myself is "Custom". */
internal fun chatColorName(spec: ChatColorSpec): String =
    PRESET_NAMES.getOrNull(ChatColors.presets.indexOf(spec)) ?: "Custom"

/**
 * Settings > Appearance > Bubble Style (SPEC 4, iOS BubbleStylesPage). Save at the top; the chat info
 * "Chat Bubble" row opens this same page. [startOnColours]: opened as the old "Chat Color" (search,
 * the new-account card), so the Colours chip shows first.
 */
@Composable
internal fun BubbleStylePage(onClose: () -> Unit, startOnColours: Boolean = false) {
    BubbleStyleChooser(
        bottomPad = 16.dp + com.fariin.app.ui.shell.bottomBarInset(),
        onSaved = onClose,
        startOnColours = startOnColours,
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
 * The Colours chip holds the chat colours first (owner 2026-11-07: the Chat Color page merged here):
 * Auto, the palette, my own and +, exactly as that page had them. A chat colour is for all my chats
 * and only I see it; picking one also sets the sent style to Default, since a colour and a style
 * cannot both show. Under "Colours others see" are the sent colour styles. Save applies both.
 *
 * Tiles are the style pictures, small, in a lazy grid: only the tiles on screen decode, into their
 * own cache that is dropped when the picker closes.
 */
@Composable
internal fun BubbleStyleChooser(
    bottomPad: androidx.compose.ui.unit.Dp,
    onSaved: () -> Unit,
    bar: @Composable (dirty: Boolean, save: () -> Unit) -> Unit,
    startOnColours: Boolean = false,
) {
    val ctx = LocalContext.current
    remember { BubbleStyles.init(ctx) }
    val c = Fariin.colors
    val saved = remember { BubbleStyles.current()?.takeIf { BubbleStyles.known(it) } }
    var selected by remember { mutableStateOf(saved) }
    val savedDecor = remember { BubbleStyles.decorations() }
    var decor by remember { mutableStateOf(savedDecor) }
    var plusNote by remember { mutableStateOf(false) }
    val savedColor = remember { ChatLookStore.globalChosen(ctx) }
    var chatColor by remember { mutableStateOf(savedColor) }
    ChatLookStore.version.intValue
    val customs = ChatLookStore.customColors(ctx)
    var custom by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ChatColorSpec?>(null) }
    // The bubble a chat colour paints, live: Auto is the wallpaper's own colour, else blue.
    val chatBrush: Brush = (chatColor ?: ChatLookStore.globalAuto(ctx))?.brush() ?: SolidColor(c.blue)
    fun pickColour(spec: ChatColorSpec?) { chatColor = spec; selected = null }
    var category by remember {
        mutableStateOf(when {
            startOnColours -> COLOURS
            else -> BubbleArt.find(saved)?.section ?: if (saved != null || savedColor != null) COLOURS else CATEGORIES.first()
        })
    }
    DisposableEffect(Unit) { onDispose { BubbleArtCache.dropThumbs() } }
    val dirty = selected != saved || decor != savedDecor || chatColor != savedColor
    val colourPicked = selected != null && BubbleArt.find(selected) == null
    val thumbPx = with(LocalDensity.current) { 96.dp.roundToPx() }
    val grid = rememberLazyGridState()
    LaunchedEffect(category) { grid.scrollToItem(0) }

    Column(Modifier.fillMaxSize().background(c.bgSecondary)) {
        bar(dirty) {
            if (!BubbleStyles.mayUse(selected)) plusNote = true
            else {
                if (chatColor != savedColor) ChatLookStore.setDefaultColor(ctx, chatColor)
                BubbleStyles.save(selected, decor)
                onSaved()
            }
        }
        // Pinned: never scrolls away, redrawn at once when a tile is tapped.
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp)) {
            Preview(selected, chatBrush)
            Text("Applies to messages you send from now on.", fontSize = 13.sp, color = c.secondary,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp))
        }
        LazyRow(
            Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(CATEGORIES.size, key = { CATEGORIES[it] }) { i ->
                val cat = CATEGORIES[i]
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
            if (category == COLOURS) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "chatcolours") {
                    val circles = buildList<@Composable (Modifier) -> Unit> {
                        add { m ->
                            ColorCircle(selected == null && chatColor == null, m, onTap = { pickColour(null) }) {
                                Box(Modifier.fillMaxSize().clip(CircleShape).background(ChatLookStore.globalAuto(ctx)?.brush() ?: SolidColor(c.blue)),
                                    contentAlignment = Alignment.Center) { Text("Auto", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.White) }
                            }
                        }
                        ChatColors.presets.forEach { p ->
                            add { m -> ColorCircle(selected == null && chatColor == p, m, onTap = { pickColour(p) }) { Box(Modifier.fillMaxSize().clip(CircleShape).background(p.brush())) } }
                        }
                        customs.forEach { p ->
                            add { m ->
                                Box(m) {
                                    ColorCircle(selected == null && chatColor == p, Modifier.fillMaxWidth(), onLong = { deleting = p }, onTap = { pickColour(p) }) {
                                        Box(Modifier.fillMaxSize().clip(CircleShape).background(p.brush()))
                                    }
                                    DropdownMenu(expanded = deleting == p, onDismissRequest = { deleting = null }, shape = com.fariin.app.ui.components.FariinMenu.shape,
                                        border = com.fariin.app.ui.components.FariinMenu.border, containerColor = com.fariin.app.ui.components.FariinMenu.container) {
                                        DropdownMenuItem(text = { Text("Delete", color = c.red) }, onClick = {
                                            deleting = null
                                            if (chatColor == p) chatColor = savedColor.takeIf { it != p }
                                            ChatLookStore.removeCustom(ctx, p)
                                        })
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
                }
                item(span = { GridItemSpan(maxLineSpan) }, key = "others") {
                    Text("Colours others see", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.secondary,
                        modifier = Modifier.padding(start = 4.dp, top = 8.dp))
                }
                items(BubbleStyles.colours, key = { "c:" + it.id }) { s ->
                    Tile(s.name, selected == s.id, plus = s.plus, onTap = { selected = s.id }) { ColourSwatch(s) }
                }
                if (colourPicked) item(span = { GridItemSpan(maxLineSpan) }, key = "decor") {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.card)
                        .clickable { decor = !decor }.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("Decorations", fontSize = 17.sp, color = c.label, modifier = Modifier.weight(1f))
                        com.fariin.app.ui.components.FariinSwitch(checked = decor, onCheckedChange = { decor = it })
                    }
                }
            } else {
                item(key = "default") { Tile("Default", selected == null, onTap = { selected = null }) { DefaultSwatch(chatBrush) } }
                items(BubbleArt.all.filter { it.section == category }, key = { "p:" + it.id }) { s ->
                    Tile(s.name, selected == s.id, onTap = { selected = s.id }) { PictureSwatch(s, thumbPx) }
                }
            }
        }
    }
    if (plusNote) AlertDialog(onDismissRequest = { plusNote = false },
        title = { Text("Fariin Plus") },
        text = { Text("This style is part of Fariin Plus, coming soon. The free styles are ready to use now.") },
        confirmButton = { TextButton({ plusNote = false }) { Text("OK", color = c.accent) } }, containerColor = c.card)
    // A mixed colour joins my colours at once (as before) and becomes the pick; Save applies it.
    if (custom) CustomColorSheet(onClose = { custom = false }) { spec ->
        ChatLookStore.addCustom(ctx, spec)
        pickColour(spec)
    }
}

/**
 * The "Bubble Styles" strip on one chat's wallpaper picker (owner 2026-11-07): six picture styles,
 * the one in use first and ringed, and "See all" opening the full chooser as a sheet. A tap picks a
 * style at once with a light tick; the style is mine for every chat, so the caption says so.
 */
@Composable
internal fun BubbleStylesStrip() {
    val ctx = LocalContext.current
    remember { BubbleStyles.init(ctx) }
    val c = Fariin.colors
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val v = BubbleStyles.version.intValue
    val current = remember(v) { BubbleStyles.current() }
    val picks = remember(current) {
        val first = BubbleArt.all.take(6)
        val cur = BubbleArt.find(current)
        if (cur != null && cur !in first) listOf(cur) + first.take(5) else first
    }
    var all by remember { mutableStateOf(false) }
    val thumbPx = with(LocalDensity.current) { 96.dp.roundToPx() }
    DisposableEffect(Unit) { onDispose { BubbleArtCache.dropThumbs() } }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Bubble Styles", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = c.label, modifier = Modifier.weight(1f))
            Text("See all", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = c.accent,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable { all = true }.padding(horizontal = 8.dp, vertical = 4.dp))
        }
        LazyRow(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(picks.size, key = { picks[it].id }) { i ->
                val s = picks[i]
                Box(Modifier.width(96.dp)) {
                    Tile(s.name, current == s.id, onTap = {
                        if (current != s.id) {
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                            BubbleStyles.save(s.id, BubbleStyles.decorations())
                        }
                    }) { PictureSwatch(s, thumbPx) }
                }
            }
        }
        Text("Applies to all your chats", fontSize = 13.sp, color = c.secondary, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
    }
    if (all) BubbleStyleSheet.Show(onDismiss = { all = false })
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
private fun Preview(selected: String?, chatBrush: Brush) {
    val c = Fariin.colors
    val text = androidx.compose.ui.text.TextStyle(fontSize = 17.sp, lineHeight = 22.sp)
    val maxW = (LocalConfiguration.current.screenWidthDp * 0.72f).dp
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.bg).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val mine = "Did you know you can change your bubble style? So cool!"
        val art = BubbleArt.find(selected)
        val colour = if (art == null) BubbleStyles.find(selected) else null
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            when {
                art != null -> Text(mine, style = text, color = art.ink(),
                    modifier = Modifier.widthIn(max = maxW).artBubble(art, c.dark))
                else -> {
                    val look = colour?.look(c.dark)
                    Text(mine, style = text, color = look?.let { Color(0xFF000000 or it.text) } ?: Color.White,
                        modifier = Modifier.widthIn(max = maxW).clip(RoundedCornerShape(18.dp))
                            .background(look?.let { brushOf(it) } ?: chatBrush).padding(horizontal = 14.dp, vertical = 9.dp))
                }
            }
        }
        Text("Looks so cute! I'm changing mine.", style = text, color = c.label,
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
private fun Tile(name: String, on: Boolean, plus: Boolean = false, onTap: () -> Unit, swatch: @Composable () -> Unit) {
    val c = Fariin.colors
    val shape = RoundedCornerShape(14.dp)
    Column(Modifier.clip(shape).background(c.card)
        .border(2.5.dp, if (on) c.accent else Color.Transparent, shape)
        .clickable(onClick = onTap).padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(44.dp), contentAlignment = Alignment.Center) { swatch() }
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

/** The plain bubble in my chat colour. */
@Composable
private fun DefaultSwatch(fill: Brush) = Bar(fill, Color.White)

@Composable
private fun ColourSwatch(s: BubbleStyles.Style) {
    val look = s.look(Fariin.colors.dark)
    Bar(brushOf(look), Color(0xFF000000 or look.text))
}

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
