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
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.fariin.app.ui.theme.Fariin
import com.fariin.app.ui.thread.bubbles.BubbleArtCache
import com.fariin.app.ui.thread.bubbles.artBubble
import com.fariin.app.ui.thread.bubbles.fillColor
import com.fariin.app.ui.thread.bubbles.ink
import com.fariin.app.ui.thread.bubbles.rememberBubbleThumb

internal const val PAGE_BUBBLE_STYLE = "look:bubblestyle"

/**
 * Settings > Appearance > Bubble Style (SPEC 4, iOS BubbleStylesPage): a live preview of their bubble
 * and mine at the top, the styles below in sections (Default, Colours, then the picture styles),
 * Save at the top. Nothing changes until Save, and only the messages sent after it carry the style.
 * One style for every chat; the chat info "Chat Bubble" row opens this same page.
 *
 * Tiles are the style pictures, small, in a lazy grid: only the tiles on screen decode, into their
 * own cache that is dropped when the page closes.
 */
@Composable
internal fun BubbleStylePage(onClose: () -> Unit) {
    val ctx = LocalContext.current
    remember { BubbleStyles.init(ctx) }
    val c = Fariin.colors
    val saved = remember { BubbleStyles.current()?.takeIf { BubbleStyles.known(it) } }
    var selected by remember { mutableStateOf(saved) }
    val savedDecor = remember { BubbleStyles.decorations() }
    var decor by remember { mutableStateOf(savedDecor) }
    var plusNote by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { BubbleArtCache.dropThumbs() } }
    val dirty = selected != saved || decor != savedDecor
    val colourPicked = selected != null && BubbleArt.find(selected) == null
    val thumbPx = with(LocalDensity.current) { 104.dp.roundToPx() }

    Column(Modifier.fillMaxSize().background(c.bgSecondary)) {
        PageBar("Bubble Style", onBack = onClose, action = {
            com.fariin.app.ui.components.FloatingTextAction("Save", enabled = dirty) {
                if (!BubbleStyles.mayUse(selected)) { plusNote = true; return@FloatingTextAction }
                BubbleStyles.save(selected, decor)
                onClose()
            }
        })
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp,
                bottom = 24.dp + com.fariin.app.ui.shell.bottomBarInset()),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "preview") { Preview(selected) }
            item(span = { GridItemSpan(maxLineSpan) }, key = "footer") {
                Text("Your style goes with the messages you send from now on, so the people you write to see it too. " +
                    "Messages you already sent keep their look.", fontSize = 13.sp, color = c.secondary,
                    modifier = Modifier.padding(horizontal = 4.dp))
            }
            header("Default")
            item(key = "default") { Tile("Default", selected == null, onTap = { selected = null }) { DefaultSwatch() } }
            header("Colours")
            items(BubbleStyles.colours, key = { "c:" + it.id }) { s ->
                Tile(s.name, selected == s.id, plus = s.plus, onTap = { selected = s.id }) { ColourSwatch(s) }
            }
            if (colourPicked) item(span = { GridItemSpan(maxLineSpan) }, key = "decor") {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.card)
                    .clickable { decor = !decor }.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Decorations", fontSize = 17.sp, color = c.label, modifier = Modifier.weight(1f))
                    com.fariin.app.ui.components.FariinSwitch(checked = decor, onCheckedChange = { decor = it })
                }
            }
            for (section in BubbleArt.sections) {
                header(section)
                items(BubbleArt.all.filter { it.section == section }, key = { "p:" + it.id }) { s ->
                    Tile(s.name, selected == s.id, onTap = { selected = s.id }) { PictureSwatch(s, thumbPx) }
                }
            }
        }
    }
    if (plusNote) AlertDialog(onDismissRequest = { plusNote = false },
        title = { Text("Fariin Plus") },
        text = { Text("This style is part of Fariin Plus, coming soon. The free styles are ready to use now.") },
        confirmButton = { TextButton({ plusNote = false }) { Text("OK", color = c.accent) } }, containerColor = c.card)
}

private fun LazyGridScope.header(title: String) = item(span = { GridItemSpan(maxLineSpan) }, key = "h:$title") {
    Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Fariin.colors.secondary,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp))
}

/** Their bubble, then mine in the chosen style (as the chat draws it, the picture for a picture style). */
@Composable
private fun Preview(selected: String?) {
    val c = Fariin.colors
    val text = androidx.compose.ui.text.TextStyle(fontSize = 17.sp, lineHeight = 22.sp)
    val maxW = (LocalConfiguration.current.screenWidthDp * 0.72f).dp
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.bg).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Looks so cute! I'm changing mine.", style = text, color = c.label,
            modifier = Modifier.widthIn(max = maxW).clip(RoundedCornerShape(18.dp))
                .background(if (c.dark) Color(0xFF2C2C2E) else Color(0xFFE5E5EA)).padding(horizontal = 14.dp, vertical = 9.dp))
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
                            .background(look?.let { brushOf(it) } ?: SolidColor(c.blue)).padding(horizontal = 14.dp, vertical = 9.dp))
                }
            }
        }
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
    val shape = RoundedCornerShape(16.dp)
    Column(Modifier.clip(shape).background(c.card)
        .border(2.5.dp, if (on) c.accent else Color.Transparent, shape)
        .clickable(onClick = onTap).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(52.dp), contentAlignment = Alignment.Center) { swatch() }
        Spacer(Modifier.height(6.dp))
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

@Composable
private fun DefaultSwatch() = Bar(SolidColor(Fariin.colors.blue), Color.White)

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
