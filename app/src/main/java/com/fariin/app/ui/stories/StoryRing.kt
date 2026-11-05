package com.fariin.app.ui.stories

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fariin.app.data.StoryRings
import com.fariin.app.ui.theme.Fariin

/**
 * iOS StoryRingView: one arc per story from the top, clockwise, oldest first; unseen arcs in the
 * green-to-blue gradient (#34C76F to #3DA1FD, top to bottom), seen arcs thinner in the owner's grey
 * (#505052 dark, #CACACA light); gaps of two line widths, round caps, at most twenty arcs.
 */
@Composable
fun StoryRing(seen: List<Boolean>, modifier: Modifier = Modifier, lineWidth: Dp = 2.dp, squircle: Boolean = false) {
    val grey = if (Fariin.colors.dark) Color(0xFF505052) else Color(0xFFCACACA)
    // A channel's ring follows its rounded-square photo (iOS StoryRingView squircle): one outline,
    // the gradient while any story is unseen, grey once all are seen.
    if (squircle) {
        Canvas(modifier) {
            val w = lineWidth.toPx()
            val d = minOf(size.width, size.height)
            val unseen = seen.any { !it }
            val r = (d - w) * 0.3f
            val tl = Offset((size.width - d) / 2 + w / 2, (size.height - d) / 2 + w / 2)
            val sz = Size(d - w, d - w)
            val st = Stroke(width = if (unseen) w else maxOf(1.dp.toPx(), w * 0.66f))
            if (unseen) drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF34C76F), Color(0xFF3DA1FD))), tl, sz,
                androidx.compose.ui.geometry.CornerRadius(r, r), style = st)
            else drawRoundRect(grey, tl, sz, androidx.compose.ui.geometry.CornerRadius(r, r), style = st)
        }
        return
    }
    Canvas(modifier) {
        val arcs = StoryRings.condensed(seen)
        val n = maxOf(1, arcs.size)
        val w = lineWidth.toPx()
        val seenW = maxOf(1.dp.toPx(), w * 0.66f)
        val d = minOf(size.width, size.height)
        val gap = StoryRings.gap(n, d, w)
        val seg = 1f / n
        val gradient = Brush.verticalGradient(listOf(Color(0xFF34C76F), Color(0xFF3DA1FD)))
        val inset = w / 2
        val box = Size(d - w, d - w)
        val topLeft = Offset((size.width - d) / 2 + inset, (size.height - d) / 2 + inset)
        for (i in 0 until n) {
            val isSeen = arcs.getOrElse(i) { false }
            val from = i * seg + gap / 2
            val to = (i + 1) * seg - gap / 2
            val style = Stroke(width = if (isSeen) seenW else w, cap = StrokeCap.Round)
            if (isSeen) drawArc(grey, -90f + 360f * from, 360f * (to - from), false, topLeft, box, style = style)
            else drawArc(gradient, -90f + 360f * from, 360f * (to - from), false, topLeft, box, style = style)
        }
    }
}
