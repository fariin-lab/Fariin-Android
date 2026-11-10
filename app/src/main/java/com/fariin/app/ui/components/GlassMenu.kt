package com.fariin.app.ui.components

import androidx.compose.ui.res.vectorResource
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.ui.theme.Fariin
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** One row of a [GlassMenu]; [divider] draws a line above it. */
class GlassMenuItem(val title: String, val icon: Painter?, val danger: Boolean = false, val divider: Boolean = false,
                    /** The chosen one of a set (a filter): a tick at the row's end (in the icon's place when there is none). */ val checked: Boolean = false,
                    /** Choices that open in place of the menu (iOS inline submenu: "Save to Photos: Off" then On / Off). */
                    val submenu: List<GlassMenuItem>? = null,
                    internal val header: Boolean = false,
                    val onClick: () -> Unit = {})

/** Where a menu's button sits in the window, so the menu can grow out of it. */
class GlassMenuAnchor { var rect by mutableStateOf<Rect?>(null) }

/** Records the button's place in the window for [GlassMenu]. */
fun Modifier.glassMenuAnchor(a: GlassMenuAnchor): Modifier = onGloballyPositioned { a.rect = it.boundsInWindow() }

/**
 * THE GLASS MENU (owner 2026-11-08, after an Android liquid-glass menu he found): the menu grows out of
 * the button it belongs to, as liquid glass over the page (frosted, refracting at its rim); a light
 * pill slides under the finger as it drags across the rows and lifting on a row picks it; the glass
 * leans a little toward the finger and springs back; closing folds it back into the button.
 *
 * Draw it as an overlay over the whole page, in the page's own window, with [backdrop] being the
 * page's layer (so the glass sees what is under it). No backdrop (older phones, Power Saving): the
 * same motion over a frosted solid card.
 */
@Composable
fun GlassMenu(open: Boolean, anchor: GlassMenuAnchor, items: List<GlassMenuItem>, backdrop: Backdrop?,
              /** A grey line over the rows ("Mute this chat for..."), not a row. */ caption: String? = null, onDismiss: () -> Unit) {
    val c = Fariin.colors
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    // An open submenu: its row on top (tap it to go back), a line, then its choices.
    var sub by remember(open) { mutableStateOf<GlassMenuItem?>(null) }
    val rows = sub?.let { s -> listOf(GlassMenuItem(s.title, s.icon, header = true)) +
        s.submenu.orEmpty().mapIndexed { i, x -> if (i == 0) GlassMenuItem(x.title, x.icon, x.danger, true, x.checked, onClick = x.onClick) else x } } ?: items
    // The finger handler below reads these live instead of being keyed on them. Keyed on [rows], it was
    // torn down mid-tap: the press itself recomposes this menu (the pill), the submenu's rows are a new
    // list on every pass, so the handler restarted between finger down and up and the lift was lost
    // (owner 2026-11-09: "Save to Photos" On / Off did nothing and the menu stayed open).
    val liveRows by rememberUpdatedState(rows)
    val liveDismiss by rememberUpdatedState(onDismiss)
    // 0 = folded into the button, 1 = open. A spring with a little give, as the reference.
    val grow = remember { Animatable(0f) }
    var shown by remember { mutableStateOf(false) }
    // Folding back: both sides shrink together into the button. The jelly (width leads, height lags)
    // is for opening only; on the way back it left a wide flat strip (owner 2026-11-08: "goes back flat").
    var closing by remember { mutableStateOf(false) }
    LaunchedEffect(open) {
        // Out of the button on a soft spring, and back into it the same way (owner: the way it
        // goes back must be seen), not a quick fade.
        // A LIVING MORPH, not a flat zoom (owner's video 2026-11-08): a slower spring with a real
        // overshoot on the way out, a firm one back.
        // Timed against the reference app's profile menu on the Pixel (60 fps, owner 2026-11-10): open
        // ~280 ms with a small overshoot, back into the button ~200 ms. Ours was a slow spring that
        // showed an empty box for 100 ms and drifted for 400 ms after.
        if (open) { closing = false; shown = true; grow.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 420f)) }
        else if (shown) { closing = true; grow.animateTo(0f, spring(dampingRatio = 1f, stiffness = 420f, visibilityThreshold = 0.01f)); shown = false }
    }
    if (!shown) return
    BackHandler { onDismiss() }
    val src = anchor.rect ?: return
    var origin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var size by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    var rowH by remember { mutableIntStateOf(0) }
    var captionH by remember { mutableIntStateOf(0) }
    var pressed by remember { mutableIntStateOf(-1) }
    val pill = remember { Animatable(0f) }
    val lean = remember { Animatable(0f) }
    val menuW = with(density) { 260.dp.toPx() }
    val margin = with(density) { 16.dp.toPx() }
    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() }
        // The page dims a little under the menu, as the reference (about 10 %).
        .drawBehind { drawRect(Color.Black.copy(alpha = (if (c.dark) 0.2f else 0.1f) * grow.value.coerceIn(0f, 1f))) }
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() }) {
        // Open OVER the button, its top right on the button's top right, as the reference: the
        // button's circle becomes the menu. Kept on screen.
        val left = (src.right - origin.x - menuW).coerceAtLeast(margin)
        val screenH = with(density) { androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp.toPx() }
        val top = (src.top - origin.y).coerceAtMost(screenH - size.height - margin * 2).coerceAtLeast(margin)
        val g = grow.value.coerceIn(0f, 1.05f)
        val h = size.height.toFloat()
        // The box flies from the button's circle to the menu's place, its corners from round to 28.
        val sx = if (size.width > 0) (src.width / size.width).coerceAtMost(1f) else 1f
        val sy = if (h > 0) (src.height / h).coerceAtMost(1f) else 1f
        // Closing, the corners round up as it shrinks, so the glass ends as the button's circle (the
        // reference folds into a tall pill, then the round button; ours ended as a square ghost).
        val fold = if (closing) (1f - g).coerceIn(0f, 1f) else 0f
        val r0 = with(density) { 28.dp.toPx() }
        val shape = RoundedCornerShape(r0 + (minOf(size.width, size.height) / 2f - r0).coerceAtLeast(0f) * fold)
        val glassMod = if (backdrop != null) Modifier.drawBackdrop(
            backdrop = backdrop, shape = { shape },
            effects = { vibrancy(); blur(24.dp.toPx()); lens(14.dp.toPx(), 28.dp.toPx()) },
            shadow = null, highlight = null,
            // Thick frost (owner: rows under it showed through, "On >" read in the menu).
            // Near solid, as the long-press menu (owner 2026-11-08: "too soft, you can see the background").
            // Solid (dark check 2026-11-09: the rows showed through, sharp).
            onDrawSurface = { drawRect(if (c.dark) c.card else Color.White) },
        ) else Modifier.background((if (c.dark) c.card else Color(0xFFF7F7F9)), shape)
        Column(
            Modifier.offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                .width(260.dp)
                .onGloballyPositioned { size = it.size }
                .graphicsLayer {
                    val k = g
                    // Grows out of the button where it sits under the menu (no flight across the page).
                    val w0 = this.size.width.coerceAtLeast(1f); val h0 = this.size.height.coerceAtLeast(1f)
                    val px = ((src.center.x - origin.x - left) / w0).coerceIn(0f, 1f)
                    val py = ((src.center.y - origin.y - top) / h0).coerceIn(0f, 1f)
                    // Jelly: the glass widens first and drops after (x leads, y lags), so it stretches out
                    // of the button instead of zooming; the overshoot of [grow] gives the bounce.
                    // Both sides grow together, out of the round button and back into it (owner 2026-11-08:
                    // the width-first jelly read as "coming out flat").
                    // Closing, the width goes first (the reference's tall pill), the height after.
                    val kx = if (closing) (k * k).coerceAtLeast(0f) else k.coerceAtLeast(0f)
                    val ky = k.coerceAtLeast(0f)
                    scaleX = (sx + (1f - sx) * kx) * (1f + 0.025f * lean.value)
                    scaleY = (sy + (1f - sy) * ky) * (1f - 0.015f * lean.value)
                    alpha = (k * 6f).coerceIn(0f, 1f)   // only the last bit of the fold fades
                    transformOrigin = TransformOrigin(px, py)
                    shadowElevation = 18.dp.toPx() * k
                    this.shape = shape; clip = true
                }
                .then(glassMod)
                .glassRim(c, shape)
                // Finger tracking: the pill follows the row under the finger; lifting on a row picks it.
                .pointerInput(open) {   // open: [sub] is a new state per opening
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        // Ours: the page's tap-outside layer must not close the menu (a submenu stays open).
                        down.consume()
                        // Finger y is in the glass box, which has 6 dp of padding above the caption and rows.
                        val padTop = 6.dp.toPx()
                        fun rowAt(y: Float) = if (rowH <= 0 || y < padTop + captionH) -1
                            else ((y - padTop - captionH) / rowH).toInt().coerceIn(0, liveRows.lastIndex)
                        pressed = rowAt(down.position.y)
                        scope.launch { pill.snapTo(pressed.toFloat()) }
                        scope.launch { lean.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 600f)) }
                        var last = down.position
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            last = ch.position
                            if (!ch.pressed) { ch.consume(); break }
                            val r = rowAt(ch.position.y)
                            if (r != pressed) { pressed = r; scope.launch { pill.animateTo(r.toFloat(), spring(dampingRatio = 0.8f, stiffness = 900f)) } }
                            ch.consume()
                        }
                        scope.launch { lean.animateTo(0f, spring(dampingRatio = 0.4f, stiffness = 300f)) }
                        val inside = last.y >= 0 && last.y <= this.size.height && last.x >= 0 && last.x <= this.size.width
                        val pick = pressed
                        pressed = -1
                        val now = liveRows
                        if (inside && pick in now.indices) {
                            val row = now[pick]
                            when {
                                row.submenu != null -> sub = row
                                row.header -> sub = null
                                else -> { liveDismiss(); row.onClick() }
                            }
                        }
                    }
                }
                .padding(vertical = 6.dp),
        ) {
            // The rows come in after the glass has opened, and leave first.
            // Reference: the rows are there (scaled with the glass) from the second frame on, and on
            // the way back they fade in the first half while the empty glass folds into the button.
            Box(Modifier.graphicsLayer { alpha = (if (closing) (grow.value - 0.4f) / 0.45f else (grow.value - 0.08f) / 0.3f).coerceIn(0f, 1f) }) {
                // The light pill under the finger.
                if (pressed >= 0 && rowH > 0) Box(Modifier.offset { IntOffset(0, (captionH + pill.value * rowH).roundToInt()) }
                    .padding(horizontal = 6.dp).fillMaxWidth().height(with(density) { rowH.toDp() })
                    .background(c.label.copy(alpha = if (c.dark) 0.14f else 0.08f), RoundedCornerShape(18.dp)))
                Column {
                    if (caption != null) Text(caption, fontSize = 15.sp, color = c.secondary, maxLines = 1,
                        modifier = Modifier.fillMaxWidth().onGloballyPositioned { captionH = it.size.height }
                            .padding(start = 54.dp, end = 18.dp, top = 8.dp, bottom = 2.dp))
                    rows.forEachIndexed { i, it ->
                        if (it.divider && i > 0) Box(Modifier.padding(horizontal = 18.dp).fillMaxWidth().height(0.5.dp).background(c.separator))
                        Row(Modifier.fillMaxWidth().height(52.dp).onGloballyPositioned { if (i == 0) rowH = it.size.height }
                            .padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                            val tick = androidx.compose.ui.graphics.vector.rememberVectorPainter(
                                androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.fariin.app.R.drawable.ic_sf_checkmark))
                            if (it.icon != null) Icon(it.icon, null, tint = if (it.danger) c.red else c.label, modifier = Modifier.size(22.dp))
                            else Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                                if (it.checked) Icon(tick, "Selected", tint = c.label, modifier = Modifier.size(18.dp)) }
                            Spacer(Modifier.width(14.dp))
                            Text(it.title, fontSize = 17.sp, fontWeight = if (it.header) FontWeight.SemiBold else null,
                                color = if (it.danger) c.red else c.label, maxLines = 2, modifier = Modifier.weight(1f))
                            if (it.header || it.submenu != null) Icon(androidx.compose.ui.graphics.vector.rememberVectorPainter(
                                androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.fariin.app.R.drawable.ic_sf_chevron_down)),
                                null, tint = c.label, modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = if (it.header) 0f else -90f })
                            if (it.checked && it.icon != null) Icon(androidx.compose.ui.graphics.vector.rememberVectorPainter(
                                androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.fariin.app.R.drawable.ic_sf_checkmark)),
                                "Selected", tint = c.label, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

/** A bright glass rim, as the glass buttons. */
private fun Modifier.glassRim(c: com.fariin.app.ui.theme.FariinColors, shape: androidx.compose.ui.graphics.Shape): Modifier =
    this.then(Modifier.border(if (c.dark) 0.5.dp else 1.dp,
        if (c.dark) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.9f), shape))
