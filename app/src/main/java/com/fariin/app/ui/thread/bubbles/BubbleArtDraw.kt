package com.fariin.app.ui.thread.bubbles

import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
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
internal fun Modifier.artBubble(spec: BubbleArt.Spec, image: ImageBitmap?, dark: Boolean): Modifier {
    val l = spec.layout
    return this
        .defaultMinSize(minWidth = l.minWidth.dp, minHeight = l.minHeight.dp)
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
        .absolutePadding(left = l.padLeft.dp, top = l.padTop.dp, right = l.padRight.dp, bottom = l.padBottom.dp)
}

/** [artBubble] with its picture loaded here (decoded off the main thread the first time). */
@Composable
internal fun Modifier.artBubble(spec: BubbleArt.Spec, dark: Boolean): Modifier = artBubble(spec, rememberBubbleArt(spec), dark)
