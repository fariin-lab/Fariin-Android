package com.fariin.app.data

/**
 * A reply's quote tapped: where the chat goes (iOS ThreadView.jumpToQuotedOriginal and the list's
 * `.message` landing). Pure, so it is unit tested.
 *
 * The list is reversed (offset 0 is the bottom edge of the usable band, above the composer), and
 * [usable] is the band between the header and the composer, in pixels.
 */
object ReplyJumpRules {
    enum class Outcome { GO, NOT_AVAILABLE, NOT_LOADED }

    /** What a finished look-up means. A deleted original is still a row (its tombstone), so it is GO. */
    fun outcome(found: Boolean, hiddenByBlock: Boolean): Outcome = when {
        found -> Outcome.GO
        hiddenByBlock -> Outcome.NOT_AVAILABLE
        else -> Outcome.NOT_LOADED
    }

    /** The words iOS shows for an outcome that does not go anywhere. */
    fun notice(o: Outcome): String? = when (o) {
        Outcome.GO -> null
        Outcome.NOT_AVAILABLE -> "Not available"
        Outcome.NOT_LOADED -> "Original message isn't loaded"
    }

    /** iOS: a row already wholly in the band does not move the list at all. */
    fun fullyVisible(offset: Int, size: Int, usable: Int): Boolean = offset >= 0 && offset + size <= usable

    /** The offset that centres a row of [size] in the band (negative for a row taller than it, as iOS centres its middle). */
    fun centredOffset(size: Int, usable: Int): Int = (usable - size) / 2

    /** The scroll (LazyList scrollBy units: +1 lowers the row by 1 px) that takes a row from [offset] to the centre. */
    fun scrollToCentre(offset: Int, size: Int, usable: Int): Int = offset - centredOffset(size, usable)
}
