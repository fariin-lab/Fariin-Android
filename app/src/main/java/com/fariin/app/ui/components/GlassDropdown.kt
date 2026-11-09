package com.fariin.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
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
    DropdownMenu(
        expanded = expanded, onDismissRequest = onDismissRequest,
        // The light along the top of the glass and its bright inner rim, under the rows.
        modifier = Modifier.drawBehind {
            drawRect(Brush.verticalGradient(0f to sheen, 0.4f to sheen.copy(alpha = 0f), startY = 0f, endY = size.height))
            val w = 1.dp.toPx(); val r = 24.dp.toPx() - w
            drawRoundRect(rim, topLeft = androidx.compose.ui.geometry.Offset(w, w),
                size = androidx.compose.ui.geometry.Size(size.width - 2 * w, size.height - 2 * w),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
                style = androidx.compose.ui.graphics.drawscope.Stroke(w))
        }.then(modifier),
        offset = offset, scrollState = scrollState, properties = properties,
        shape = RoundedCornerShape(24.dp),
        containerColor = base,
        tonalElevation = 0.dp,
        shadowElevation = shadowElevation.coerceAtLeast(16.dp),
        // The fine dark edge outside the white rim: the clear glass edge on a white page.
        border = BorderStroke(0.5.dp, if (dark) Color.Black.copy(alpha = 0.55f) else Color.Black.copy(alpha = 0.10f)),
        content = content,
    )
}
