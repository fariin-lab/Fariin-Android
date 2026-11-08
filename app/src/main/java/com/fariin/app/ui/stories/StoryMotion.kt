package com.fariin.app.ui.stories

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Where each story card sits on screen, so the viewer can zoom out of it and shrink back into it
 * (iOS StoryZoomPresenter + MediaOpenRects). Rects are in SCREEN pixels: the viewer lives in its
 * own dialog window, so both sides convert through `getLocationOnScreen`.
 *
 * [hiddenKey] is the slot the viewer has lifted out of the row: iOS keeps the row card at alpha 0
 * for the whole visit and the hole follows whoever is on screen, so the landing fills it.
 */
object StoryOrigins {
    val rects = mutableStateMapOf<String, Rect>()
    /** Corner radius of each card, in px (the row card is 14dp). */
    val radii = mutableStateMapOf<String, Float>()
    var hiddenKey by mutableStateOf<String?>(null)
}

/** A card the viewer flies from and back into that is not a ring's own slot ([key] in [StoryOrigins]),
 *  wearing [coverUrl] (the picture the card shows) for the flight. */
class StoryOrigin(val key: String, val coverUrl: String)

/** The iOS story viewer's motion numbers (StoriesViews.swift hero, StoryCubePager, StoryPager). */
internal object StoryMotion {
    /** Finger travel (dp) for the card to be fully seated in its slot (`heroDragSpan`). */
    const val DRAG_SPAN = 420f
    /** Smallest the story may get while the finger is down, as a share of its width (`heroDragMinScale`). */
    const val DRAG_MIN_SCALE = 0.46f
    /** Rubber band past the floor, dp (`heroDragTail`). */
    const val DRAG_TAIL = 28f
    /** Release: close past this pull (dp) or this downward speed (dp/s); else spring back. */
    const val COMMIT_DISTANCE = 120f
    const val COMMIT_VELOCITY = 550f
    /** Critically damped spring stiffness on the 0..1 flight (`SheetProgressAnimator`, mass 1). */
    const val OPEN_STIFFNESS = 560f
    const val CLOSE_STIFFNESS = 720f
    const val OPEN_SETTLE = 0.0015f
    /** The no-destination close: response 0.25, damping 1 (MediaDismiss), stiffness (2π/0.25)². */
    const val DRIFT_STIFFNESS = 631.65f
    const val DRIFT_SCALE = 0.72f
    /** Backdrop (`heroDimMax`, `heroDimFloor`, `heroDimSolid`). The wall turns solid over the last
     *  30% of an open (owner's video 2026-11-07: at 15% the list still showed through a card that
     *  already filled the screen, the spring's tail being that long). */
    const val DIM_MAX = 0.58f
    const val DIM_FLOOR = 0.36f
    const val DIM_SOLID = 0.3f
    /** Header, bars and reply field: in over the last 30% of an open, out over the first 30% of a
     *  close, on the card's own fraction, so they neither pop in late nor vanish before it moves. */
    const val CHROME_SPAN = 0.3f
    /** Bar icons go dark (over a light list) once the wall is under half its flight dim. */
    const val BARS_FLIP = DIM_MAX / 2f
    /** Swipe up on my story opens the viewers past 40dp or 300dp/s. */
    const val VIEWERS_DISTANCE = 40f
    const val VIEWERS_VELOCITY = 300f
    /** Viewers sheet: critically damped stiffness 340; closes on 30% of the screen, or 5% at 150dp/s. */
    const val SHEET_STIFFNESS = 340f
    /** Person to person: 0.4s flat on bezier(0.23, 1, 0.32, 1), commit at 30% of a face. */
    const val TURN_MS = 400
    val TURN_EASING = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)
    const val TURN_THRESHOLD = 0.3f
    /** Cube: 500pt perspective, faces meet 40pt inside the screen edge, tint |t| x 1.3. */
    const val CUBE_PERSPECTIVE = 500f
    const val CUBE_SIDE_DISTANCE = 40f
    const val CUBE_TINT = 1.3f

    /** The wall behind the card at fraction [f] (0 full screen, 1 in the slot); [floorMix] 0..1. */
    fun dim(f: Float, floorMix: Float): Float {
        val x = f.coerceIn(0f, 1f)
        if (x < DIM_SOLID) return 1f - (1f - DIM_MAX) * (x / DIM_SOLID)
        val travelled = (x - DIM_SOLID) / (1f - DIM_SOLID)
        val floor = DIM_FLOOR * floorMix.coerceIn(0f, 1f)
        return floor + (DIM_MAX - floor) * (1f - travelled)
    }

    /** Chrome alpha at fraction [f]: all of it at rest, none past [CHROME_SPAN], both ways. */
    fun chrome(f: Float): Float = ((CHROME_SPAN - f) / CHROME_SPAN).coerceIn(0f, 1f)

    /** Close: the slot's picture is fully on by 85% of the landing (`heroCoverIn`). */
    fun coverIn(t: Float): Float = (t / 0.85f).coerceIn(0f, 1f)

    /** Close into a Stories card: the slot's picture comes over the last 15% of the landing. */
    fun coverInLate(t: Float): Float = ((t - 0.85f) / 0.15f).coerceIn(0f, 1f)

    /** Open: the slot's picture dissolves into the story between 20% and 55% (`heroCoverOut`, card). */
    fun coverOut(t: Float): Float = 1f - ((t - 0.2f) / 0.35f).coerceIn(0f, 1f)

    /**
     * Close into a chat row's round photo: the card is a circle (square and round) by 40% and the
     * story dissolves into the photo between 10% and 55%, so the end of the flight is a shrinking
     * photo, not a big rounded card over the row's name.
     */
    fun avatarCropIn(t: Float): Float = (t / 0.4f).coerceIn(0f, 1f)
    fun avatarCoverIn(t: Float): Float = ((t - 0.1f) / 0.45f).coerceIn(0f, 1f)
    /** The row's ring rides the flight too: gone in the first 15% of an open, back over the last 25% of a close. */
    fun ringOut(t: Float): Float = 1f - (t / 0.15f).coerceIn(0f, 1f)
    fun ringIn(t: Float): Float = ((t - 0.75f) / 0.25f).coerceIn(0f, 1f)

    /** Where a flight may call itself done: two pixels from home, between 0.4% and 5% (`heroSettle`). */
    fun settle(remainingPx: Float, density: Float): Float = minOf(0.05f, maxOf(0.004f, 2f * density / maxOf(1f, remainingPx)))

    /** The cap on the drag's fraction so the card never drops below [DRAG_MIN_SCALE] (`heroDragCeiling`). */
    fun dragCeiling(anchorRatio: Float): Float {
        val r = anchorRatio.coerceIn(0f, 0.99f)
        if (r >= DRAG_MIN_SCALE) return 1f
        return ((1f - DRAG_MIN_SCALE) / (1f - r)).coerceIn(0.05f, 1f)
    }

    /** Finger travel past the free stretch: a rubber band onto [DRAG_TAIL] (`heroDragY`). */
    fun dragTravel(ty: Float, free: Float, tail: Float): Float =
        if (ty <= free) ty else free + tail * (1f - 1f / ((ty - free) / tail + 1f))

    /** One cube face at turn [t] (-1..1) on a page [widthPt] wide: the iOS transform, flattened. */
    class Face(val rotationDeg: Float, val shiftPt: Float, val scale: Float)

    private var cache: Triple<Float, Float, Float>? = null   // width, sideAngle, backDistance

    /**
     * The iOS `cubeTransform`: a face pushed out by w/2, turned about the cube's axis, pushed back by
     * the cube distance. Compose has no z translation, so the depth becomes a scale about the face
     * centre with the camera left at the perspective distance (a plane moved back by dz projects
     * exactly as the same plane scaled by p / (p + dz) under an unchanged camera).
     */
    fun face(t: Float, widthPt: Float): Face {
        val tc = t.coerceIn(-1f, 1f)
        val absT = kotlin.math.abs(tc)
        val w = widthPt
        val c = cache?.takeIf { it.first == w } ?: run {
            val side = sideAngle(w, CUBE_SIDE_DISTANCE)
            val back = w / 2f + w * (tan(side) / 2f) - w / (2f * cos(side))
            Triple(w, side, back).also { cache = it }
        }
        val side = c.second
        val back = c.third
        val turn = (Math.PI / 2).toFloat() + side
        val angle = absT * turn
        val a = -1.4142135f * absT * cos(side + 0.7853982f)
        val b = sin((side + 1.5707964f) * absT + 0.7853982f)
        val cubeDistance = 0.5f * w * (a + absT + 1.4142135f * b - 1f)
        val p = CUBE_PERSPECTIVE
        val half = w / 2f
        // Face centre after the push, the turn and the push back (worked for the face on the right;
        // the left one is its mirror image).
        val arm = half - back
        val zc = arm * cos(angle) - arm - cubeDistance
        val k = p / (p - zc)
        // The shared edge, where iOS projects it: the camera there sits on the screen's centre line.
        val xe = arm * sin(angle) - half * cos(angle)
        val ze = zc + half * sin(angle)
        val edge = xe * p / (p - ze)
        // Compose projects about the face's own centre, so place the face by its shared edge: both
        // faces then meet on exactly the line iOS draws, with no seam and no overlap.
        val edgeHere = -k * half * cos(angle) * p / (p - k * half * sin(angle))
        val sign = if (tc < 0f) -1f else 1f
        return Face(sign * Math.toDegrees(angle.toDouble()).toFloat(), sign * (edge - edgeHere), k)
    }

    /** The reference implementation's closed form for the side angle, verbatim (iOS `cubeSideAngle`). */
    private fun sideAngle(w: Float, s: Float): Float {
        val p = CUBE_PERSPECTIVE.toDouble(); val wd = w.toDouble(); val sd = s.toDouble()
        val c = sqrt(-p.pow(2) * sd.pow(2) + p.pow(2) * wd.pow(2) + sd.pow(2) * wd.pow(2) + sd * wd.pow(3) + 0.25 * wd.pow(4))
        val a = 2.0 * p * wd - 2.0 * c
        val b = -2.0 * p * sd + 2.0 * sd * wd + wd.pow(2)
        if (b == 0.0) return 0f
        return (2.0 * atan(a / b)).toFloat()
    }
}
