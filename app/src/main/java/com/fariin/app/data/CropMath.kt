package com.fariin.app.data

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
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

    // ---- The chat photo crop (iOS ChatCropView): a free frame inside the fitted photo.
    //
    // The WINDOW is the photo fitted on screen; it does not move. The crop frame lives inside it,
    // and the picture moves under it (zoom, turn, slide), always covering the window.

    /** A rectangle in screen px. */
    data class Box(val left: Float, val top: Float, val width: Float, val height: Float) {
        val right get() = left + width
        val bottom get() = top + height
        val centerX get() = left + width / 2
        val centerY get() = top + height / 2
    }

    /** iOS: pinch stops at six times the window. */
    const val FREE_MAX_ZOOM = 6f

    /** iOS minSize: the frame never gets smaller than this on a side (pt, so dp here). */
    const val FREE_MIN_SIDE_DP = 64f

    /** iOS hit: a corner's grab square, and a side's grab thickness (dp). */
    const val FREE_HIT_DP = 34f

    /** The straighten wheel's reach either way, in degrees. */
    const val MAX_STRAIGHTEN = 45f

    /** iOS zoomFloor: the smallest zoom at which a w x h picture turned by [angleDeg] still covers its window. */
    fun zoomFloor(w: Float, h: Float, angleDeg: Float): Float {
        if (w <= 1f || h <= 1f) return 1f
        val r = Math.toRadians(abs(angleDeg).toDouble())
        val c = abs(cos(r)).toFloat(); val s = abs(sin(r)).toFloat()
        return max(1f, max((w * c + h * s) / w, (w * s + h * c) / h))
    }

    /** iOS clampPan: the slide that keeps the window covered at this zoom and angle. */
    fun clampPan(panX: Float, panY: Float, w: Float, h: Float, zoom: Float, angleDeg: Float): Pair<Float, Float> {
        val r = Math.toRadians(abs(angleDeg).toDouble())
        val c = abs(cos(r)).toFloat(); val s = abs(sin(r)).toFloat()
        val z = max(zoom, zoomFloor(w, h, angleDeg))
        val zw = w * z; val zh = h * z
        val slackX = max(0f, (zw * c + zh * s - w) / 2)
        val slackY = max(0f, (zw * s + zh * c - h) / 2)
        return panX.coerceIn(-slackX, slackX) to panY.coerceIn(-slackY, slackY)
    }

    /** iOS displayFrame: the window scaled about its centre (never under the floor) and slid by the pan. */
    fun displayFrame(window: Box, zoom: Float, angleDeg: Float, panX: Float, panY: Float): Box {
        val z = max(zoom, zoomFloor(window.width, window.height, angleDeg))
        val w = window.width * z; val h = window.height * z
        return Box(window.centerX - w / 2 + panX, window.centerY - h / 2 + panY, w, h)
    }

    /** iOS setAspect: the biggest frame of [ratio] (w / h) centred in the window; null = the whole window. */
    fun aspectFrame(window: Box, ratio: Float?): Box {
        if (ratio == null || window.height <= 0f) return window
        return if (window.width / window.height > ratio) {
            val w = window.height * ratio
            Box(window.centerX - w / 2, window.top, w, window.height)
        } else {
            val h = window.width / ratio
            Box(window.left, window.centerY - h / 2, window.width, h)
        }
    }

    /** iOS moveGesture: the frame slid by (dx, dy), kept inside the window. */
    fun moved(start: Box, dx: Float, dy: Float, window: Box): Box {
        val l = (start.left + dx).coerceIn(window.left, max(window.left, window.right - start.width))
        val t = (start.top + dy).coerceIn(window.top, max(window.top, window.bottom - start.height))
        return start.copy(left = l, top = t)
    }

    /** Which sides a grab moves (iOS Handle). */
    enum class Handle(val left: Boolean, val top: Boolean, val right: Boolean, val bottom: Boolean) {
        TOP_LEFT(true, true, false, false), TOP_RIGHT(false, true, true, false),
        BOTTOM_LEFT(true, false, false, true), BOTTOM_RIGHT(false, false, true, true),
        TOP(false, true, false, false), BOTTOM(false, false, false, true),
        LEFT(true, false, false, false), RIGHT(false, false, true, false);
        val isCorner get() = (left || right) && (top || bottom)
    }

    /** iOS resized: [start] dragged by (dx, dy) at [h], inside the window, at least [minSide], held to [aspect] when locked. */
    fun resized(start: Box, h: Handle, dx: Float, dy: Float, window: Box, minSide: Float, aspect: Float?): Box {
        var minX = start.left; var minY = start.top; var maxX = start.right; var maxY = start.bottom
        if (h.left) minX = min(maxX - minSide, max(window.left, start.left + dx))
        if (h.right) maxX = max(minX + minSide, min(window.right, start.right + dx))
        if (h.top) minY = min(maxY - minSide, max(window.top, start.top + dy))
        if (h.bottom) maxY = max(minY + minSide, min(window.bottom, start.bottom + dy))
        var r = Box(minX, minY, maxX - minX, maxY - minY)
        if (aspect != null) r = when (h) {
            Handle.LEFT, Handle.RIGHT -> {
                val newH = min(r.width / aspect, window.height)
                r.copy(top = (r.centerY - newH / 2).coerceIn(window.top, max(window.top, window.bottom - newH)), height = newH)
            }
            Handle.TOP, Handle.BOTTOM -> {
                val newW = min(r.height * aspect, window.width)
                r.copy(left = (r.centerX - newW / 2).coerceIn(window.left, max(window.left, window.right - newW)), width = newW)
            }
            else -> {
                val newH = min(r.width / aspect, window.height)
                // The fixed side stays fixed.
                r.copy(top = if (h.top) r.bottom - newH else r.top, height = newH)
            }
        }
        return r
    }

    /** iOS straightenWheel: [halfWidth] px of drag reaches 45 degrees; within a degree of straight is straight. */
    fun straighten(startDeg: Float, dragX: Float, halfWidth: Float): Float {
        val v = (startDeg + dragX * MAX_STRAIGHTEN / max(1f, halfWidth)).coerceIn(-MAX_STRAIGHTEN, MAX_STRAIGHTEN)
        return if (abs(v) < 1f) 0f else v
    }
}
