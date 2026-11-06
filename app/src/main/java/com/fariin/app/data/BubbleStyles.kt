package com.fariin.app.data

/**
 * Bubble styles, stage one (iOS BubbleStyles.swift, owner 2026-11-06). The sender picks a style and
 * every text message they send carries its plain id as `bs`, so both sides draw that bubble in the
 * style's colours with the style's own text colour. An unknown id, or none, draws the normal bubble.
 *
 * Colours are copied from iOS exactly. One colour is a solid fill, two a top-to-bottom gradient.
 * Soft Glass is the system blur on iOS; here it is its colour, partly see-through (see [Look.glass]).
 */
object BubbleStyles {
    data class Look(
        /** 0xRRGGBB, one = solid, two = gradient from top to bottom. */
        val fill: List<Long>,
        /** 0xRRGGBB, the words, links, time and ticks. */
        val text: Long,
        /** Soft Glass: drawn as its colour with some of the background showing through. */
        val glass: Boolean = false,
    )

    data class Style(
        val id: String,
        val name: String,
        /** A Fariin Plus style: only the Fariin team may pick it until Plus is sold. */
        val plus: Boolean,
        val light: Look,
        val dark: Look,
    ) {
        fun look(dark: Boolean): Look = if (dark) this.dark else light
    }

    val all: List<Style> = listOf(
        Style("strawberry", "Strawberry Pink", false,
            Look(listOf(0xFFC9D6), 0x4A1020), Look(listOf(0x7E2F49), 0xFFE3EA)),
        Style("lavender", "Dreamy Lavender", false,
            Look(listOf(0xE6D8FF, 0xCDB6FF), 0x2E1A55), Look(listOf(0x4B3577, 0x6A4BA3), 0xF1E9FF)),
        Style("peach", "Peach Blossom", false,
            Look(listOf(0xFFDCC8), 0x4A2414), Look(listOf(0x7A4632), 0xFFE8DC)),
        Style("mint", "Mint Cutie", false,
            Look(listOf(0xCBF2DF), 0x123D2C), Look(listOf(0x2F6B54), 0xE2FFF3)),
        Style("cotton", "Cotton Candy", true,
            Look(listOf(0xFFC6E3, 0xBFE3FF), 0x2A2440), Look(listOf(0x7A3B66, 0x2F5D86), 0xF5EEFF)),
        Style("heart", "Heart Love", true,
            Look(listOf(0xFF9DBB), 0x4A0A22), Look(listOf(0xA8325C), 0xFFE6EF)),
        Style("kawaii", "Kawaii", true,
            Look(listOf(0xFFEBA8), 0x4A3A05), Look(listOf(0x76611E), 0xFFF6D6)),
        Style("glass", "Soft Glass", true,
            Look(listOf(0xFFFFFF), 0x1C1C1E, glass = true), Look(listOf(0x2C2C2E), 0xF2F2F7, glass = true)),
    )

    fun find(id: String?): Style? = if (id.isNullOrEmpty()) null else all.firstOrNull { it.id == id }

    /** The `bs` field as it arrives: a short plain string, else nothing (iOS: 1 to 32 characters). */
    fun parseId(raw: Any?): String? = (raw as? String)?.takeIf { it.isNotEmpty() && it.length <= 32 }
}
