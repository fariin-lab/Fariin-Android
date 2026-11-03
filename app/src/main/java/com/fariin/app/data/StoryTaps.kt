package com.fariin.app.data

import androidx.compose.ui.geometry.Rect

/**
 * A sticker that still does something after it has been posted (iOS StoryTapTarget): a text
 * story's link card or a Link sticker is drawn into the picture, and where it is and what it opens
 * travel beside it on the story document as `stickers`. The viewer lays an invisible tap area over
 * the picture from these.
 *
 * Everything is 0-1 against the posted file: [x], [y] the centre, [w], [h] the size, [rotation] in
 * radians. [aspect] is the file's width over height (absent on older stories).
 */
data class StoryTapTarget(
    val x: Double,
    val y: Double,
    val w: Double,
    val h: Double,
    val rotation: Double,
    val url: String,
    val aspect: Double? = null,
)

object StoryTaps {
    /** iOS StoryTapTarget.from: web addresses only (no tel:, sms: or other apps' schemes), and a size. */
    fun from(raw: Any?): StoryTapTarget? {
        val d = raw as? Map<*, *> ?: return null
        val url = d["url"] as? String ?: return null
        val scheme = url.substringBefore("://", "").lowercase()
        if (scheme != "http" && scheme != "https") return null
        val x = (d["x"] as? Number)?.toDouble() ?: return null
        val y = (d["y"] as? Number)?.toDouble() ?: return null
        val w = (d["w"] as? Number)?.toDouble() ?: return null
        val h = (d["h"] as? Number)?.toDouble() ?: return null
        if (w <= 0 || h <= 0) return null
        return StoryTapTarget(x, y, w, h, (d["rotation"] as? Number)?.toDouble() ?: 0.0, url,
            (d["aspect"] as? Number)?.toDouble()?.takeIf { it > 0 })
    }

    fun list(raw: Any?): List<StoryTapTarget> = (raw as? List<*>).orEmpty().mapNotNull { from(it) }

    /**
     * Where the area sits on a card [cw] x [ch] (iOS tapRect). The fractions are of the file; this
     * viewer shows the file fitted in the card, so with an [StoryTapTarget.aspect] they are laid over
     * the fitted picture. Without one they are laid over the card, as iOS does for older stories.
     * [floor]: the smallest side a finger can still hit (iOS 24 pt).
     */
    fun rect(t: StoryTapTarget, cw: Float, ch: Float, floor: Float): Rect {
        var left = 0f; var top = 0f
        var mw = cw; var mh = ch
        val a = t.aspect
        if (a != null && a > 0 && cw > 1 && ch > 1) {
            if (cw / a <= ch) { mw = cw; mh = (cw / a).toFloat() } else { mw = (ch * a).toFloat(); mh = ch }
            left = (cw - mw) / 2; top = (ch - mh) / 2
        }
        val w = maxOf(floor, (mw * t.w).toFloat())
        val h = maxOf(floor, (mh * t.h).toFloat())
        val cx = left + (mw * t.x).toFloat()
        val cy = top + (mh * t.y).toFloat()
        return Rect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
    }

    /** iOS linkBubble: under the area, unless there is no room under it, then over it. */
    fun bubbleAbove(area: Rect, ch: Float, bubbleH: Float, gap: Float, margin: Float): Boolean =
        area.bottom + bubbleH + gap + margin > ch
}
