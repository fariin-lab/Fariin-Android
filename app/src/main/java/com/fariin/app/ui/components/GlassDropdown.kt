package com.fariin.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import com.fariin.app.ui.theme.Fariin

/**
 * THE GLASS ACTION BUBBLE (owner 2026-11-08): every small DropdownMenu as crisp glass. A thick
 * frosted fill (the menu is a popup window, so the page's backdrop cannot reach it), a bright rim,
 * a soft light along the top, 24dp corners and a deep shadow. Same parameters as material3
 * DropdownMenu, so the items (icon before the words) stay as they are.
 *
 * [shape], [border], [tonalElevation] are taken for a drop-in swap; the glass draws its own.
 * [containerColor] is kept only when it is not the theme card (a fixed dark menu on a camera page).
 */
@Composable
fun GlassDropdown(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    scrollState: ScrollState = rememberScrollState(),
    properties: PopupProperties = PopupProperties(focusable = true),
    @Suppress("UNUSED_PARAMETER") shape: Shape? = null,
    containerColor: Color = Color.Unspecified,
    @Suppress("UNUSED_PARAMETER") tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 16.dp,
    @Suppress("UNUSED_PARAMETER") border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Fariin.colors
    val base = if (containerColor == Color.Unspecified || containerColor == c.card)
        (if (c.dark) Color(0xFF262628) else Color(0xFFF9F9FB)) else containerColor
    val dark = base.luminance() < 0.4f
    val rim = if (dark) Color.White.copy(alpha = 0.18f) else Color.White
    val sheen = if (dark) Color.White.copy(alpha = 0.09f) else Color.White.copy(alpha = 0.85f)
    // OUR OWN popup and motion (owner 2026-11-09: "it opens from small but closes flat"): material's
    // menu grows in but only fades out. This one grows out of the button it belongs to and shrinks
    // back into it, the same curve both ways.
    val state = androidx.compose.runtime.remember { androidx.compose.animation.core.MutableTransitionState(false) }
    state.targetState = expanded
    if (!state.currentState && !state.targetState) return
    val density = androidx.compose.ui.platform.LocalDensity.current
    val origin = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(androidx.compose.ui.graphics.TransformOrigin(1f, 0f)) }
    val provider = androidx.compose.runtime.remember(offset, density) { DropdownPlace(offset, density) { origin.value = it } }
    androidx.compose.ui.window.Popup(popupPositionProvider = provider, onDismissRequest = onDismissRequest, properties = properties) {
        val t = androidx.compose.animation.core.rememberTransition(state, "glassDropdown")
        val k by t.animateFloat({
            // The same clock as GlassMenu (timed against the reference app, 2026-11-10): out on a
            // spring with a small overshoot, back in one 240 ms eased fold.
            if (targetState) androidx.compose.animation.core.spring(dampingRatio = 0.72f, stiffness = 420f)
            else androidx.compose.animation.core.tween(240, easing = androidx.compose.animation.core.CubicBezierEasing(0.4f, 0f, 0.2f, 1f))
        }, label = "k") { if (it) 1f else 0f }
        val shape = RoundedCornerShape(24.dp)
        androidx.compose.foundation.layout.Column(
            modifier.graphicsLayer {
                // Closing, the width goes first (a tall pill into the button), as GlassMenu; the
                // glass fades only in the last quarter, so the menu is seen going back.
                val closing = !state.targetState
                val kx = if (closing) Math.pow(k.coerceAtLeast(0f).toDouble(), 1.5).toFloat() else k
                scaleX = 0.15f + 0.85f * kx; scaleY = 0.15f + 0.85f * k
                alpha = (k * if (closing) 4f else 6f).coerceIn(0f, 1f)
                transformOrigin = origin.value
            }
                .padding(4.dp)   // room for the shadow
                .shadow(shadowElevation.coerceAtLeast(16.dp), shape)
                .background(base, shape)
                .border(BorderStroke(0.5.dp, if (dark) Color.Black.copy(alpha = 0.55f) else Color.Black.copy(alpha = 0.10f)), shape)
                .drawBehind {
                    drawRect(Brush.verticalGradient(0f to sheen, 0.4f to sheen.copy(alpha = 0f), startY = 0f, endY = size.height))
                    val w = 1.dp.toPx(); val r = 24.dp.toPx() - w
                    drawRoundRect(rim, topLeft = androidx.compose.ui.geometry.Offset(w, w),
                        size = androidx.compose.ui.geometry.Size(size.width - 2 * w, size.height - 2 * w),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(w))
                }
                .width(androidx.compose.foundation.layout.IntrinsicSize.Max)
                .widthIn(min = 112.dp, max = 280.dp)
                .verticalScroll(scrollState)
                .padding(vertical = 8.dp),
            content = content,
        )
    }
}

/**
 * Under the button, its end edge on the button's end (or above it when there is no room below),
 * kept on screen; reports where the button sits inside the menu, so the menu grows out of it.
 */
private class DropdownPlace(val offset: DpOffset, val density: androidx.compose.ui.unit.Density,
                            val onOrigin: (androidx.compose.ui.graphics.TransformOrigin) -> Unit) : androidx.compose.ui.window.PopupPositionProvider {
    override fun calculatePosition(anchorBounds: androidx.compose.ui.unit.IntRect, windowSize: androidx.compose.ui.unit.IntSize,
                                   layoutDirection: androidx.compose.ui.unit.LayoutDirection, popupContentSize: androidx.compose.ui.unit.IntSize): androidx.compose.ui.unit.IntOffset {
        val dx = with(density) { offset.x.roundToPx() }; val dy = with(density) { offset.y.roundToPx() }
        val margin = with(density) { 8.dp.roundToPx() }
        val w = popupContentSize.width; val h = popupContentSize.height
        val startAligned = anchorBounds.left + dx
        val endAligned = anchorBounds.right - w - dx
        var x = if (layoutDirection == androidx.compose.ui.unit.LayoutDirection.Ltr) {
            if (anchorBounds.center.x > windowSize.width / 2) endAligned else startAligned
        } else { if (anchorBounds.center.x < windowSize.width / 2) startAligned else endAligned }
        x = x.coerceIn(margin, (windowSize.width - w - margin).coerceAtLeast(margin))
        val below = anchorBounds.bottom + dy
        var y = if (below + h <= windowSize.height - margin) below else anchorBounds.top - h - dy
        y = y.coerceIn(margin, (windowSize.height - h - margin).coerceAtLeast(margin))
        if (w > 0 && h > 0) onOrigin(androidx.compose.ui.graphics.TransformOrigin(
            ((anchorBounds.center.x - x).toFloat() / w).coerceIn(0f, 1f), ((anchorBounds.center.y - y).toFloat() / h).coerceIn(0f, 1f)))
        return androidx.compose.ui.unit.IntOffset(x, y)
    }
}
