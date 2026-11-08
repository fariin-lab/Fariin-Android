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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
class GlassMenuItem(val title: String, val icon: Painter, val danger: Boolean = false, val divider: Boolean = false,
                    /** The chosen one of a set (a filter): a tick at the row's end. */ val checked: Boolean = false,
                    val onClick: () -> Unit)

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
fun GlassMenu(open: Boolean, anchor: GlassMenuAnchor, items: List<GlassMenuItem>, backdrop: Backdrop?, onDismiss: () -> Unit) {
    val c = Fariin.colors
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    // 0 = folded into the button, 1 = open. A spring with a little give, as the reference.
    val grow = remember { Animatable(0f) }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(open) {
        // Out of the button on a soft spring, and back into it the same way (owner: the way it
        // goes back must be seen), not a quick fade.
        // A LIVING MORPH, not a flat zoom (owner's video 2026-11-08): a slower spring with a real
        // overshoot on the way out, a firm one back.
        if (open) { shown = true; grow.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 150f)) }
        else if (shown) { grow.animateTo(0f, spring(dampingRatio = 0.9f, stiffness = 260f, visibilityThreshold = 0.01f)); shown = false }
    }
    if (!shown) return
    BackHandler { onDismiss() }
    val src = anchor.rect ?: return
    var origin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var size by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    var rowH by remember { mutableIntStateOf(0) }
    var pressed by remember { mutableIntStateOf(-1) }
    val pill = remember { Animatable(0f) }
    val lean = remember { Animatable(0f) }
    val menuW = with(density) { 260.dp.toPx() }
    val margin = with(density) { 16.dp.toPx() }
    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() }
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() }) {
        // Open: under the button, its right edge on the button's right (kept on screen).
        val left = (src.right - origin.x - menuW).coerceAtLeast(margin)
        val top = src.bottom - origin.y + with(density) { 8.dp.toPx() }
        val g = grow.value.coerceIn(0f, 1.05f)
        val h = size.height.toFloat()
        // The box flies from the button's circle to the menu's place, its corners from round to 28.
        val sx = if (size.width > 0) (src.width / size.width).coerceAtMost(1f) else 1f
        val sy = if (h > 0) (src.height / h).coerceAtMost(1f) else 1f
        val shape = RoundedCornerShape(28.dp)
        val glassMod = if (backdrop != null) Modifier.drawBackdrop(
            backdrop = backdrop, shape = { shape },
            effects = { vibrancy(); blur(24.dp.toPx()); lens(14.dp.toPx(), 28.dp.toPx()) },
            shadow = null, highlight = null,
            // Thick frost (owner: rows under it showed through, "On >" read in the menu).
            onDrawSurface = { drawRect((if (c.dark) c.card else Color.White).copy(alpha = if (c.dark) 0.88f else 0.9f)) },
        ) else Modifier.background((if (c.dark) c.card else Color(0xFFF7F7F9)).copy(alpha = 0.97f), shape)
        Column(
            Modifier.offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                .width(260.dp)
                .onGloballyPositioned { size = it.size }
                .graphicsLayer {
                    val k = g
                    // From the button's centre to the menu's own place.
                    val cx0 = src.center.x - origin.x - (left + this.size.width / 2f)
                    val cy0 = src.center.y - origin.y - (top + this.size.height / 2f)
                    translationX = cx0 * (1f - k)
                    translationY = cy0 * (1f - k)
                    // Jelly: the glass widens first and drops after (x leads, y lags), so it stretches out
                    // of the button instead of zooming; the overshoot of [grow] gives the bounce.
                    val kx = if (k >= 1f) k else Math.pow(k.coerceAtLeast(0f).toDouble(), 0.6).toFloat()
                    val ky = if (k >= 1f) 1f + (k - 1f) * 1.6f else Math.pow(k.coerceAtLeast(0f).toDouble(), 1.5).toFloat()
                    scaleX = (sx + (1f - sx) * kx) * (1f + 0.025f * lean.value)
                    scaleY = (sy + (1f - sy) * ky) * (1f - 0.015f * lean.value)
                    alpha = (k * 6f).coerceIn(0f, 1f)   // only the last bit of the fold fades
                    transformOrigin = TransformOrigin.Center
                    shadowElevation = 18.dp.toPx() * k
                    this.shape = shape; clip = true
                }
                .then(glassMod)
                .glassRim(c)
                // Finger tracking: the pill follows the row under the finger; lifting on a row picks it.
                .pointerInput(items) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        fun rowAt(y: Float) = if (rowH <= 0) -1 else (y / rowH).toInt().coerceIn(0, items.lastIndex)
                        pressed = rowAt(down.position.y)
                        scope.launch { pill.snapTo(pressed.toFloat()) }
                        scope.launch { lean.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 600f)) }
                        var last = down.position
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            last = ch.position
                            if (!ch.pressed) break
                            val r = rowAt(ch.position.y)
                            if (r != pressed) { pressed = r; scope.launch { pill.animateTo(r.toFloat(), spring(dampingRatio = 0.8f, stiffness = 900f)) } }
                            ch.consume()
                        }
                        scope.launch { lean.animateTo(0f, spring(dampingRatio = 0.4f, stiffness = 300f)) }
                        val inside = last.y >= 0 && last.y <= this.size.height && last.x >= 0 && last.x <= this.size.width
                        val pick = pressed
                        pressed = -1
                        if (inside && pick in items.indices) { onDismiss(); items[pick].onClick() }
                    }
                }
                .padding(vertical = 6.dp),
        ) {
            // The rows come in after the glass has opened, and leave first.
            Box(Modifier.graphicsLayer { alpha = ((grow.value - 0.55f) / 0.35f).coerceIn(0f, 1f)
                translationY = (1f - ((grow.value - 0.55f) / 0.45f).coerceIn(0f, 1f)) * -6.dp.toPx() }) {
                // The light pill under the finger.
                if (pressed >= 0 && rowH > 0) Box(Modifier.offset { IntOffset(0, (pill.value * rowH).roundToInt()) }
                    .padding(horizontal = 6.dp).fillMaxWidth().height(with(density) { rowH.toDp() })
                    .background(c.label.copy(alpha = if (c.dark) 0.14f else 0.08f), RoundedCornerShape(18.dp)))
                Column {
                    items.forEachIndexed { i, it ->
                        if (it.divider) Box(Modifier.padding(horizontal = 18.dp).fillMaxWidth().height(0.5.dp).background(c.separator))
                        Row(Modifier.fillMaxWidth().height(52.dp).onGloballyPositioned { if (i == 0) rowH = it.size.height }
                            .padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(it.icon, null, tint = if (it.danger) c.red else c.label, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(14.dp))
                            Text(it.title, fontSize = 17.sp, color = if (it.danger) c.red else c.label, maxLines = 2,
                                modifier = Modifier.weight(1f))
                            if (it.checked) Icon(androidx.compose.ui.graphics.vector.rememberVectorPainter(
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
private fun Modifier.glassRim(c: com.fariin.app.ui.theme.FariinColors): Modifier =
    this.then(Modifier.border(if (c.dark) 0.5.dp else 1.dp,
        if (c.dark) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.9f), RoundedCornerShape(28.dp)))
