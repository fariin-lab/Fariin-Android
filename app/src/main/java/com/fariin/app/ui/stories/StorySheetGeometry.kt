package com.fariin.app.ui.stories

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * My story with the viewers sheet up (iOS StoriesViews `cardSlot` + `StoryRowGeometry`): the story
 * shrinks into the centre card of a row of my stories above the sheet, its neighbours smaller on
 * each side. Everything is in window pixels; [d] is the density for the iOS point numbers.
 *
 * [fraction] is the sheet's progress, 0 closed to 1 open. The live story and every side card are
 * placed by the same [placement], so the shrink and the row are one motion.
 */
internal class StorySheetGeometry(
    val fullW: Float,
    val fullH: Float,
    val topInset: Float,
    val d: Float,
    val fraction: Float,
) {
    /** The story's own rectangle at rest: a 9:16 band centred in the window (iOS estimatedContentHeight). */
    val contentH: Float = minOf(ceil(fullW * 1.77778f), fullH)
    val restCenterY: Float = fullH / 2f

    val sheetH: Float = fullH * HEIGHT_FRACTION
    /** Free height above the open sheet, status bar cleared. */
    private val avail = fullH - sheetH - topInset
    private val countArea = COUNT_AREA * d
    val slotH: Float = (avail - countArea) * 0.94f * 0.88f
    val slotW: Float = slotH * (fullW / maxOf(contentH, 1f))
    val slotTop: Float = topInset + (avail - countArea - slotH) / 2f
    val centerY: Float = slotTop + slotH / 2f

    private val spacing = ITEM_SPACING * d
    private val sideW = maxOf(1f, slotW - SIDE_INSET * d)
    /** One step from the centre card to its neighbour, and between two side cards. */
    val fullDist: Float = maxOf(1f, slotW * 0.5f + spacing + sideW * 0.5f)
    private val halfDist = maxOf(1f, sideW * 0.5f + spacing + sideW * 0.5f)
    private val sideRelScale = if (slotW > 0f) sideW / slotW else 1f

    // The row is laid out at full-screen card size when the sheet is down and at the slot when up,
    // so the neighbours start one screen away and slide in as the story shrinks.
    private val contentMinScale = if (fullW > 0f) slotW / fullW else 1f
    private val scaledCentralW = fullW * (contentMinScale * fraction + (1f - fraction))
    private val scaledSideW = maxOf(1f, scaledCentralW - SIDE_INSET * d * fraction)
    private val scaledFullDist = maxOf(1f, scaledCentralW * 0.5f + spacing + scaledSideW * 0.5f)
    private val scaledHalfDist = maxOf(1f, scaledSideW * 0.5f + spacing + scaledSideW * 0.5f)

    fun offsetX(cf: Float): Float {
        val sign = if (cf < 0f) -1f else 1f
        val a = abs(cf)
        return minOf(1f, a) * sign * scaledFullDist + maxOf(0f, a - 1f) * sign * scaledHalfDist
    }

    fun itemScale(cf: Float): Float {
        val s = minOf(1f, abs(cf))
        return (1f - s) + sideRelScale * s
    }

    /** The black over a card: full strength three cards out, none on the centre card once seated. */
    fun dim(cf: Float): Float {
        val counted = minOf(1f, abs(cf) / 3f)
        val distance = minOf(1f, abs(cf))
        return 1f - (1f - counted) * (1f - distance * (1f - fraction))
    }

    /** The view count under a card: two thirds on a neighbour, a third on the next. */
    fun countAlpha(cf: Float): Float = (1f - abs(cf) / 3f).coerceIn(0f, 1f)

    /** Card [cf] places from the centre (negative = left). */
    fun placement(cf: Float): Placement {
        val scale = itemScale(cf)
        val w = fullW + (slotW * scale - fullW) * fraction
        val h = contentH + (slotH * scale - contentH) * fraction
        val y = restCenterY + (centerY - restCenterY) * fraction
        return Placement(fullW / 2f + offsetX(cf), y, maxOf(1f, w), maxOf(1f, h), CORNER * d * scale * fraction, dim(cf))
    }

    data class Placement(val cx: Float, val cy: Float, val w: Float, val h: Float, val corner: Float, val dim: Float)

    companion object {
        /** The sheet's height against the screen (iOS StoryViewersSheetView.heightFraction, his 0.60). */
        const val HEIGHT_FRACTION = 0.60f
        /** The big count row under the centre card. */
        const val COUNT_AREA = 40f
        const val ITEM_SPACING = 12f
        /** A side card is this much narrower than the centre one. */
        const val SIDE_INSET = 54f
        const val CORNER = 24f
        /** Count row's gap under its card. */
        const val COUNT_GAP = 10f

        /** UIScrollView's normal deceleration, as the row's paging projects a fling (0.998 per ms). */
        const val FLING_PROJECTION = 0.998f / (1f - 0.998f) / 1000f

        /**
         * The one release rule for a finger on the sheet or the card (iOS settleViewers): close if
         * the pull covered 30% of the screen, or 5% with 150 pt/s toward closed; else back open.
         */
        fun shouldClose(droppedPx: Float, vDownPx: Float, screenH: Float, d: Float): Boolean =
            droppedPx >= screenH * 0.30f || (droppedPx >= screenH * 0.05f && vDownPx >= 150f * d)

        /** Where a released row lands: the nearest card to its projected stop, kept in the row. */
        fun snapIndex(position: Float, velocityCardsPerSec: Float, count: Int): Int {
            if (count <= 0) return 0
            return (position + velocityCardsPerSec * FLING_PROJECTION).roundToInt().coerceIn(0, count - 1)
        }
    }
}

/** "just now", "6m ago", "3h ago", "2d ago": a viewer's time in the sheet (iOS StoryViewerRowContent.dateFmt). */
internal fun viewedAgo(atMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val s = maxOf(0L, (nowMs - atMs) / 1000)
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60}m ago"
        s < 86_400 -> "${s / 3600}h ago"
        else -> "${s / 86_400}d ago"
    }
}
