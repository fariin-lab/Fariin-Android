package com.fariin.app.ui.thread.bubbles

import androidx.compose.runtime.Composable
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.fariin.app.data.BubbleArt

/** The words' colour on a picture bubble. */
internal fun BubbleArt.Spec.ink(): Color = Color(0xFF000000 or BubbleArt.textColor(this))

/** The style's flat colour (the earlier bubbles of a run, and the picture's stand-in while it decodes). */
internal fun BubbleArt.Spec.fillColor(): Color = Color(0xFF000000 or fill)

/**
 * A text bubble drawn as the style's picture (SPEC 6): the whole picture stretched 9-slice around the
 * content, its corners at their own size, the words inset so they clear the corner art. Not
 * clipped, the art reaches outside the bubble's edge. The picture is not mirrored in a right-to-left
 * layout (it has a fixed look, like on iOS), so the insets are absolute.
 *
 * While [image] is still decoding: the bubble's plain colour where the bubble will be, so nothing
 * jumps when the picture arrives. Dark mode: a dark bubble gets a 0.5 dp light rim (white 45%) so
 * it does not vanish on black. Cost per frame: 9 bitmap draws (or one rounded rect), no allocation.
 */
internal fun Modifier.artBubble(spec: BubbleArt.Spec, image: ImageBitmap?, dark: Boolean, centre: Boolean = true): Modifier {
    val l = spec.layout
    return this
        .drawWithCache {
            val d = density
            val w = size.width.toInt()
            val h = size.height.toInt()
            val parts = image?.let {
                BubbleArt.nineSlice(spec, it.width, it.height, w, h, l.capTop * d, l.capRight * d, l.capBottom * d, l.capLeft * d)
            }
            val edgeAt = Offset(l.edgeLeft * d, l.edgeTop * d)
            val edgeSize = Size(size.width - (l.edgeLeft + l.edgeRight) * d, size.height - (l.edgeTop + l.edgeBottom) * d)
            val radius = CornerRadius(l.edgeRadius * d)
            val fill = spec.fillColor()
            val rim = if (dark && spec.lightText) Stroke(width = 0.5f * d) else null
            val rimInset = 0.25f * d
            val rimAt = Offset(edgeAt.x + rimInset, edgeAt.y + rimInset)
            val rimSize = Size(edgeSize.width - 2 * rimInset, edgeSize.height - 2 * rimInset)
            val rimColor = Color.White.copy(alpha = 0.45f)
            onDrawBehind {
                if (image == null || parts == null) {
                    drawRoundRect(fill, edgeAt, edgeSize, radius)
                } else {
                    var o = 0
                    while (o < 72) {
                        if (parts[o + 2] > 0) drawImage(
                            image,
                            srcOffset = IntOffset(parts[o], parts[o + 1]),
                            srcSize = IntSize(parts[o + 2] - parts[o], parts[o + 3] - parts[o + 1]),
                            dstOffset = IntOffset(parts[o + 4], parts[o + 5]),
                            dstSize = IntSize(parts[o + 6] - parts[o + 4], parts[o + 7] - parts[o + 5]),
                        )
                        o += 8
                    }
                }
                if (rim != null) drawRoundRect(rimColor, rimAt, rimSize, radius, style = rim)
            }
        }
        .layout { measurable, constraints ->
            // The words get the room inside both clearances; BubbleArt.place centres a short line
            // between the bubble's edges and keeps long ones where they always were.
            val d = density
            val padH = ((l.padLeft + l.padRight) * d).roundToInt()
            val padV = ((l.padTop + l.padBottom) * d).roundToInt()
            val p = measurable.measure(Constraints(
                maxWidth = if (constraints.hasBoundedWidth) max(0, constraints.maxWidth - padH) else Constraints.Infinity,
                maxHeight = if (constraints.hasBoundedHeight) max(0, constraints.maxHeight - padV) else Constraints.Infinity,
            ))
            val maxW = if (constraints.hasBoundedWidth) constraints.maxWidth.toFloat() else Float.MAX_VALUE
            val at = BubbleArt.place(spec, p.width.toFloat(), p.height.toFloat(), maxW, d, centre)
            val w = ceil(at.width).toInt().coerceIn(constraints.minWidth, constraints.maxWidth)
            val h = ceil(at.height).toInt().coerceIn(constraints.minHeight, constraints.maxHeight)
            // Absolute, like the picture: it is not mirrored right to left.
            layout(w, h) { p.place(at.x.roundToInt(), at.y.roundToInt()) }
        }
}

/** [artBubble] with its picture loaded here (decoded off the main thread the first time). [centre] false: a link-card bubble, laid out as before. */
@Composable
internal fun Modifier.artBubble(spec: BubbleArt.Spec, dark: Boolean, centre: Boolean = true): Modifier =
    artBubble(spec, rememberBubbleArt(spec), dark, centre)
