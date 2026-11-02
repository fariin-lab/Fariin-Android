package com.fariin.app.data

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The move-and-scale maths behind the circle photo cropper (iOS ProfilePhotoCropper, its
 * MoveAndScaleController + CropScrollView). Pure numbers so the unit tests can hold it.
 *
 * The picture is drawn centred in a square window, scaled by `scale` (screen px per image px) and
 * shifted by (offX, offY) screen px from the window's centre. It must always COVER the window:
 * the smallest scale is whichever edge runs out first, the largest four times that, and the shift
 * never lets an edge of the photo into the window (iOS turns bounce off for the same reason).
 */
object CropMath {
    /** iOS maximumZoomScale = fit * 4. */
    const val MAX_ZOOM = 4f

    /** iOS MoveAndScaleController.capped: the crop comes down to this edge before it leaves. */
    const val MAX_OUTPUT = 1280

    /** iOS: the circle is as big as the screen allows, 24 pt each side, 200 pt of height kept free. */
    fun windowSide(screenW: Float, screenH: Float, sideMargin: Float, heightReserve: Float): Float =
        max(1f, min(screenW - 2 * sideMargin, screenH - heightReserve))

    /** The zoom floor: the photo covers the window on both edges. */
    fun minScale(imgW: Int, imgH: Int, window: Float): Float =
        if (imgW <= 0 || imgH <= 0) 1f else max(window / imgW, window / imgH)

    fun clampScale(scale: Float, minScale: Float): Float = scale.coerceIn(minScale, minScale * MAX_ZOOM)

    /** How far the photo may move on one axis before its edge would show inside the window. */
    fun clampOffset(offset: Float, imgSide: Int, scale: Float, window: Float): Float {
        val limit = max(0f, (imgSide * scale - window) / 2f)
        return offset.coerceIn(-limit, limit)
    }

    /**
     * One pinch-and-drag step: zoom by [zoom] about the fingers' [centroidX]/[centroidY] (relative to
     * the window centre), then move by [panX]/[panY]. Returns (scale, offX, offY), all clamped.
     */
    fun step(
        imgW: Int, imgH: Int, window: Float,
        scale: Float, offX: Float, offY: Float,
        zoom: Float, centroidX: Float, centroidY: Float, panX: Float, panY: Float,
    ): Triple<Float, Float, Float> {
        val minS = minScale(imgW, imgH, window)
        val newScale = clampScale(scale * zoom, minS)
        val k = if (scale > 0f) newScale / scale else 1f
        // The image point under the fingers stays under the fingers.
        val nx = (offX - centroidX) * k + centroidX + panX
        val ny = (offY - centroidY) * k + centroidY + panY
        return Triple(newScale, clampOffset(nx, imgW, newScale, window), clampOffset(ny, imgH, newScale, window))
    }

    /** The square inside the window, in image pixels: (left, top, side). Clamped inside the image. */
    fun cropRect(imgW: Int, imgH: Int, window: Float, scale: Float, offX: Float, offY: Float): IntArray {
        if (imgW <= 0 || imgH <= 0 || scale <= 0f) return intArrayOf(0, 0, 0)
        val side = min((window / scale).roundToInt(), min(imgW, imgH)).coerceAtLeast(1)
        val cx = imgW / 2f - offX / scale
        val cy = imgH / 2f - offY / scale
        val left = (cx - side / 2f).roundToInt().coerceIn(0, imgW - side)
        val top = (cy - side / 2f).roundToInt().coerceIn(0, imgH - side)
        return intArrayOf(left, top, side)
    }

    /** The side the cut square is sent at: itself, or [MAX_OUTPUT] when larger. */
    fun outputSide(cropSide: Int): Int = min(cropSide, MAX_OUTPUT)
}
