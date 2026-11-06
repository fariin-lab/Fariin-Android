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

    /** The `bsd` field: the sender chose the style's decorations too. Only a real true counts. */
    fun parseDecor(raw: Any?): Boolean = raw == true

    // ---- Decorations, stage two (iOS BubbleDecor.swift, owner 2026-11-06) ----
    // Small art at a styled bubble's corners. Only the LAST bubble of a run carries them, mostly on
    // the bottom corners so they hang into the gap below; a top corner only on the run's first bubble.

    enum class Corner { TOP_LEADING, TOP_TRAILING, BOTTOM_LEADING, BOTTOM_TRAILING }

    /** What is drawn. Every one is drawn in code (ui/thread/BubbleStyles.kt), no artwork files. */
    enum class Glyph { HEART, SPARKLE, SPARKLES, BLOSSOM, LEAF, CLOUD, STAR, BOW, STRAWBERRY }

    data class Decor(
        val corner: Corner,
        val glyph: Glyph,
        /** Size in dp (iOS points). */
        val size: Float,
        val light: Long,
        val dark: Long,
        /** How far the art's centre sits out from the corner point, in dp. */
        val out: Float = 4f,
        /** Turn in radians, positive = clockwise. */
        val rotation: Float = 0f,
    )

    /** One decoration resolved for one bubble: the real corner (mirrored for theirs) and colour. */
    data class Placed(val corner: Corner, val glyph: Glyph, val size: Float, val color: Long, val out: Float, val rotation: Float)

    /** The style's set, copied from iOS (corners for MY bubble). */
    fun decor(styleId: String): List<Decor> = when (styleId) {
        "strawberry" -> listOf(
            Decor(Corner.BOTTOM_TRAILING, Glyph.STRAWBERRY, 22f, 0xE8364F, 0xFF5A72, rotation = 0.25f),
            Decor(Corner.TOP_LEADING, Glyph.HEART, 13f, 0xFF6F91, 0xFF8FAB, rotation = -0.3f))
        "lavender" -> listOf(
            Decor(Corner.BOTTOM_TRAILING, Glyph.SPARKLES, 17f, 0x8E6CF0, 0xD6C6FF),
            Decor(Corner.TOP_LEADING, Glyph.SPARKLE, 11f, 0xA98BFF, 0xE6DBFF))
        "peach" -> listOf(
            Decor(Corner.BOTTOM_TRAILING, Glyph.BLOSSOM, 19f, 0xFF8A65, 0xFFB59A, rotation = 0.2f),
            Decor(Corner.BOTTOM_LEADING, Glyph.LEAF, 13f, 0x7CB86F, 0x9AD68C, rotation = -0.5f))
        "mint" -> listOf(
            Decor(Corner.BOTTOM_TRAILING, Glyph.LEAF, 17f, 0x2FA77A, 0x7FE0B8, rotation = 0.4f),
            Decor(Corner.TOP_LEADING, Glyph.SPARKLE, 11f, 0x45C493, 0xB6F5DA))
        "cotton" -> listOf(
            Decor(Corner.BOTTOM_TRAILING, Glyph.CLOUD, 20f, 0xA9D4FF, 0xC9E4FF),
            Decor(Corner.TOP_LEADING, Glyph.STAR, 12f, 0xFF9ECF, 0xFFC2E2, rotation = 0.3f))
        "heart" -> listOf(
            Decor(Corner.BOTTOM_TRAILING, Glyph.HEART, 18f, 0xFF3B6E, 0xFF6F91, rotation = 0.3f),
            Decor(Corner.TOP_LEADING, Glyph.HEART, 11f, 0xFF6F91, 0xFF9DB6, rotation = -0.35f))
        "kawaii" -> listOf(
            Decor(Corner.TOP_TRAILING, Glyph.BOW, 24f, 0xFF6FA3, 0xFF8FB8, out = 3f, rotation = 0.15f),
            Decor(Corner.BOTTOM_LEADING, Glyph.STAR, 13f, 0xFFC83D, 0xFFD866, rotation = -0.3f),
            Decor(Corner.BOTTOM_TRAILING, Glyph.SPARKLES, 14f, 0xFFB020, 0xFFD866))
        "glass" -> listOf(Decor(Corner.BOTTOM_TRAILING, Glyph.SPARKLE, 13f, 0x8E8E93, 0xD1D1D6))
        else -> emptyList()
    }

    /**
     * The decorations one bubble draws, or none. They show only when the sender chose them (`bsd`),
     * on a styled text bubble, on the LAST bubble of its run, and not on a failed send (the red
     * retry mark sits there). Theirs mirrors mine so the art stays on the outer side. A top corner
     * only on the run's first bubble, so it never lands on the bubble above. [besideAvatar]: their
     * bubble in a group, whose bottom-leading corner is next to the sender's face; nothing there.
     */
    fun placed(styleId: String?, decorOn: Boolean, isMe: Boolean, dark: Boolean, first: Boolean, last: Boolean,
               failed: Boolean, besideAvatar: Boolean): List<Placed> {
        if (styleId == null || !decorOn || !last || failed) return emptyList()
        return decor(styleId).mapNotNull { d ->
            val c = if (isMe) d.corner else when (d.corner) {
                Corner.TOP_LEADING -> Corner.TOP_TRAILING
                Corner.TOP_TRAILING -> Corner.TOP_LEADING
                Corner.BOTTOM_LEADING -> Corner.BOTTOM_TRAILING
                Corner.BOTTOM_TRAILING -> Corner.BOTTOM_LEADING
            }
            if (!first && (c == Corner.TOP_LEADING || c == Corner.TOP_TRAILING)) return@mapNotNull null
            if (besideAvatar && c == Corner.BOTTOM_LEADING) return@mapNotNull null
            Placed(c, d.glyph, d.size, if (dark) d.dark else d.light, d.out, if (isMe) d.rotation else -d.rotation)
        }
    }

    /** Where the art's centre goes, in dp, inside a bubble [w] by [h] dp (iOS applyDecor). */
    fun centre(p: Placed, w: Float, h: Float): Pair<Float, Float> = when (p.corner) {
        Corner.TOP_LEADING -> (4f - p.out) to (2f - p.out)
        Corner.TOP_TRAILING -> (w - 4f + p.out) to (2f - p.out)
        Corner.BOTTOM_LEADING -> (4f - p.out) to (h - 2f + p.out)
        Corner.BOTTOM_TRAILING -> (w - 4f + p.out) to (h - 2f + p.out)
    }
}
