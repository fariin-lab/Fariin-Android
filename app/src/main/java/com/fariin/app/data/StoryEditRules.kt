package com.fariin.app.data

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The pure half of the story editor's text and pen tools (iOS StoryEditorView TextOverlay,
 * StoryTextToolEditor, penBar). Everything here is plain numbers so it can be tested without a phone.
 *
 * All positions live in one "edit space": the photo is [SPACE] units wide and SPACE * h / w tall,
 * whatever size it shows at. The screen draws that space scaled to the photo on screen, and the bake
 * draws the same space scaled to the posted bitmap, so the posted picture is what was on screen.
 * One unit is a third of an iOS point on a 390 pt wide card, so iOS sizes carry over times three.
 */
object StoryEditRules {
    const val SPACE = 1170f
    private const val PT = 3f

    /** iOS TextOverlay.FontStyle, in iOS order (the Aa button cycles through them). */
    enum class Font { ROUNDED, CLASSIC, SERIF, MONO; fun next() = entries[(ordinal + 1) % entries.size] }

    /** iOS TextOverlay.BgStyle: no badge, a dark see-through badge, a badge in the text colour. */
    enum class Bg { PLAIN, SEMI, SOLID; fun next() = entries[(ordinal + 1) % entries.size] }

    // iOS storyStyledText, in edit-space units.
    const val TEXT_SIZE = 34 * PT
    const val TEXT_MAX_WIDTH = SPACE * 0.9f
    const val CORNER = 10 * PT
    const val SHADOW_RADIUS = 3 * PT
    const val SHADOW_DY = 1 * PT
    fun padH(bg: Bg) = (if (bg == Bg.PLAIN) 6 else 14) * PT
    fun padV(bg: Bg) = (if (bg == Bg.PLAIN) 2 else 8) * PT
    const val SEMI_BADGE = 0x61000000 // black at 0.38
    const val TEXT_SHADOW = 0x8C000000.toInt() // black at 0.55
    const val MIN_SCALE = 0.3f
    const val MAX_SCALE = 6f

    /** iOS StoryTextColorValue.gradient: black at phase 0, white at phase 1. */
    val TEXT_STOPS = intArrayOf(0x000000, 0xff5500, 0xffff00, 0x00ff00, 0x00ffff, 0x0000ff, 0xff00ff, 0xff0000, 0xffffff)

    /** The colour under [phase] on the text colour bar, naive RGB between stops (iOS value(atPhase:)). */
    fun textColor(phase: Float): Int {
        val segments = TEXT_STOPS.size - 1
        val p = phase.coerceIn(0f, 1f)
        val scaled = p * segments
        val i = scaled.toInt().coerceIn(0, segments - 1)
        val t = scaled - i
        val a = TEXT_STOPS[i]; val b = TEXT_STOPS[i + 1]
        fun ch(shift: Int): Int {
            val x = (a shr shift) and 0xff; val y = (b shr shift) and 0xff
            return (x + (y - x) * t + 0.5f).toInt().coerceIn(0, 255)
        }
        return (0xff shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** iOS storyBadgeInk: Rec. 709 luminance over 0.5 takes black ink, else white. */
    fun badgeInk(argb: Int): Int {
        val r = ((argb shr 16) and 0xff) / 255f
        val g = ((argb shr 8) and 0xff) / 255f
        val b = (argb and 0xff) / 255f
        return if (0.2126f * r + 0.7152f * g + 0.0722f * b > 0.5f) 0xff000000.toInt() else 0xffffffff.toInt()
    }

    // The pen (iOS penBar).
    const val PEN_START_HUE = 0.01f
    const val PEN_START_WIDTH = 6f
    /** The pen track: white, then the hues from 0.02 in steps of 0.08. */
    val PEN_TRACK_HUES = generateSequence(0.02f) { it + 0.08f }.takeWhile { it <= 1.0001f }.toList()

    /** iOS: hue 0 is the white end of the track, any other hue is that hue at full strength. */
    fun penColor(hue: Float): Int = if (hue <= 0f) 0xffffffff.toInt() else hsv(hue.coerceAtMost(1f), 1f, 1f)

    /** The width button: 4, 10, 16, then back to 4 (iOS `penWidth >= 16 ? 4 : penWidth + 6`). */
    fun nextPenWidth(w: Float) = if (w >= 16f) 4f else w + 6f
    fun penStroke(widthPt: Float, highlighter: Boolean) = widthPt * PT * (if (highlighter) 2.5f else 1f)
    /** A highlighter stroke is see-through, so the photo shows under it. */
    fun inkAlpha(highlighter: Boolean) = if (highlighter) 0x80 else 0xff

    /** Plain HSV to ARGB, the same maths as android.graphics.Color.HSVToColor. */
    fun hsv(h: Float, s: Float, v: Float): Int {
        val hh = (h * 6f).let { if (it >= 6f) 0f else it }
        val i = hh.toInt()
        val f = hh - i
        val p = v * (1 - s); val q = v * (1 - s * f); val t = v * (1 - s * (1 - f))
        val (r, g, b) = when (i) {
            0 -> Triple(v, t, p); 1 -> Triple(q, v, p); 2 -> Triple(p, v, t)
            3 -> Triple(p, q, v); 4 -> Triple(t, p, v); else -> Triple(v, p, q)
        }
        fun c(x: Float) = (x * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (0xff shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
    }

    /** The bin: the finger itself has to be on it, a little larger than the glyph (iOS isOverTrash). */
    fun isOverTrash(x: Float, y: Float, binX: Float, binY: Float, diameter: Float, slop: Float): Boolean =
        hypot(x - binX, y - binY) < diameter / 2 + slop

    /** A point on screen into edit space, clamped onto the photo (iOS canvasCenter(fromScreen:)). */
    fun toSpace(sx: Float, sy: Float, left: Float, top: Float, width: Float, height: Float): Pair<Float, Float> {
        if (width <= 1f || height <= 1f) return SPACE / 2 to SPACE / 2
        val k = SPACE / width
        return ((sx - left).coerceIn(0f, width) * k) to ((sy - top).coerceIn(0f, height) * k)
    }

    /**
     * Is edit-space point (px, py) on a text box of size w x h centred at (cx, cy), grown by
     * [scale] and turned by [rotationDeg]? The point is turned back into the box's own frame first.
     */
    fun hitsBox(px: Float, py: Float, cx: Float, cy: Float, w: Float, h: Float, scale: Float, rotationDeg: Float, slop: Float = 0f): Boolean {
        val a = Math.toRadians(-rotationDeg.toDouble())
        val dx = px - cx; val dy = py - cy
        val lx = (dx * cos(a) - dy * sin(a)).toFloat() / scale
        val ly = (dx * sin(a) + dy * cos(a)).toFloat() / scale
        val s = slop / scale
        return lx >= -w / 2 - s && lx <= w / 2 + s && ly >= -h / 2 - s && ly <= h / 2 + s
    }

    fun clampScale(s: Float) = s.coerceIn(MIN_SCALE, MAX_SCALE)

    /** Nothing placed: the photo posts exactly as it was prepared, no second encode. */
    fun needsBake(texts: Int, strokes: Int) = texts > 0 || strokes > 0
}
