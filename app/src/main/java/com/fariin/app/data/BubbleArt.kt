package com.fariin.app.data

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Picture bubble styles (owner 2026-11-07, fariin-bubbles/SPEC.md, the same file iOS builds from).
 * Each one is a stretchable picture in assets/bubbles/<id>.webp, cut into 9 parts by [Spec.slice]:
 * the corners keep their size and the middle stretches, so one picture fits any length of text.
 * The art never changes with light or dark mode; each style carries its own text colour.
 *
 * Ids are the picture file names and stay the same forever (a removed style is never reused).
 * The table below is copied from fariin-bubbles/bubbles_v2/<id>.json (2026-11-07: every body redrawn
 * as a rounded rectangle, about 18 dp corners at display size, [Spec.radius]; side clearance at least
 * 11 dp). [Spec.art] is not in the json: it was measured from the v2 pictures (pixels inside the
 * bubble that are not its fill, past its rim) and checked by eye on all 35.
 *
 * Everything here is plain numbers, so the layout is unit tested without a phone.
 */
object BubbleArt {
    /**
     * Corner art that reaches INSIDE the bubble on one side, in picture px: picture rows [top]..[bottom],
     * reaching [reach] px in from the bubble's [right] or left edge. Art above or below the bubble
     * (Security's peeker, the doctor's head) is not listed: it never meets the words.
     */
    class Art(val right: Boolean, val top: Int, val bottom: Int, val reach: Int)

    /** All in picture pixels. Sides are [top, right, bottom, left]; [bubble] holds edges, not insets. */
    class Spec(
        val id: String,
        val name: String,
        /** The picker section it is listed under. */
        val section: String,
        /** Cap insets: how much of each edge stays fixed. */
        val slice: IntArray,
        val width: Int,
        val height: Int,
        /** Where the bubble's own edges are inside the picture: top, right, bottom, left (art reaches outside it). */
        val bubble: IntArray,
        /** How far the words keep clear of corner art that reaches into the bubble, from the bubble's edge. */
        val ins: IntArray,
        /** Light words on a dark bubble. */
        val lightText: Boolean,
        /** 0xRRGGBB: the bubble's flat colour, the picture's stand-in while it decodes. */
        val fill: Long,
        /** The bubble body's corner radius in picture px. */
        val radius: Int,
        /** Where corner art sits inside the bubble; [ins] applies on a side only where one of these meets the words. */
        val art: List<Art>,
    ) {
        val layout: Layout by lazy { layoutOf(this) }
    }

    /**
     * Where things go at display size, in dp. [scale] is dp per picture pixel. The box drawn is the
     * whole picture stretched; the words sit inside it at [padTop] / [padRight] / [padBottom] / [padLeft].
     * [padLeft] / [padRight] are the widest a side ever needs (all its art beside the words): the room
     * the words are measured in. [sideLeft] / [sideRight] are a side with no art beside the words.
     */
    class Layout(
        val scale: Float,
        val capTop: Float, val capRight: Float, val capBottom: Float, val capLeft: Float,
        val padTop: Float, val padRight: Float, val padBottom: Float, val padLeft: Float,
        val sideLeft: Float, val sideRight: Float,
        val minWidth: Float, val minHeight: Float,
        /** The bubble's own edge, inset from the box: for the dark-mode rim and the plain fill while loading. */
        val edgeTop: Float, val edgeRight: Float, val edgeBottom: Float, val edgeLeft: Float,
        val edgeRadius: Float,
    )

    /** SPEC 6: at most 0.24 dp per picture px... */
    const val MAX_SCALE = 0.24f
    /** ...and the slice parts that sit inside the bubble fit about 44 dp, so one word stays one line tall. */
    const val INSIDE_DP = 44f
    /** The words keep at least 40 px and 11 dp from the bubble's side (the round corners), and 10 dp from its top and bottom. */
    const val SIDE_PX = 40
    const val SIDE_DP = 11f
    /** Beside art that reaches in: 4 dp past the art, nothing more (the json's whole-height `ins` is not used). */
    const val ART_TEXT_DP = 4f
    const val VERTICAL_DP = 10f
    /**
     * Art wholly in a top (or bottom) cap that ends within 12 dp of that edge (hands resting on it):
     * the words move down (or up) to 4 dp past it, at most this far from the edge, instead of
     * taking room beside it.
     */
    const val LIFT_MAX_DP = 16f

    fun layoutOf(s: Spec): Layout {
        val (t, r, b, l) = s.slice.toList()
        val (bt, br, bb, bl) = s.bubble.toList()
        val insideTb = (t - bt) + (b - (s.height - bb))
        val k = min(MAX_SCALE, INSIDE_DP / max(insideTb, 1))
        val belowEdge = (s.height - bb) * k
        val rightOut = (s.width - br) * k
        val base = ceil(max(SIDE_PX * k, SIDE_DP))
        // The words' distance from the top and bottom edges: 10 dp, or 4 dp past shallow corner art.
        var liftT = VERTICAL_DP
        var liftB = VERTICAL_DP
        for (a in s.art) {
            val e = edgeDepth(s, a, k) ?: continue
            if (e + ART_TEXT_DP > LIFT_MAX_DP) continue
            if (a.bottom <= t) liftT = max(liftT, ceil(e + ART_TEXT_DP)) else liftB = max(liftB, ceil(e + ART_TEXT_DP))
        }
        // Art that can meet the words at some height: not corner art that ends before the words begin.
        fun canMeet(a: Art): Boolean {
            val e = edgeDepth(s, a, k) ?: return true
            return e + ART_TEXT_DP > (if (a.bottom <= t) liftT else liftB)
        }
        fun widest(right: Boolean) = s.art.filter { it.right == right && canMeet(it) }.maxOfOrNull { ceil(artClear(it, k) - 1e-4f) } ?: 0f
        return Layout(
            scale = k,
            capTop = t * k, capRight = r * k, capBottom = b * k, capLeft = l * k,
            padTop = bt * k + liftT,
            padRight = rightOut + max(base, widest(true)),
            padBottom = belowEdge + liftB,
            padLeft = bl * k + max(base, widest(false)),
            sideLeft = bl * k + base, sideRight = rightOut + base,
            minWidth = (l + r) * k, minHeight = (t + b) * k,
            edgeTop = bt * k, edgeRight = rightOut, edgeBottom = belowEdge, edgeLeft = bl * k,
            edgeRadius = s.radius * k,
        )
    }

    /** How far from the bubble's edge (dp) the words stay when [a] is beside them: 4 dp past the art. */
    private fun artClear(a: Art, k: Float): Float = a.reach * k + ART_TEXT_DP

    /**
     * How far (dp) art wholly in the top cap reaches down from the bubble's top edge, or art wholly
     * in the bottom cap up from its bottom edge; null for art that stretches with the middle.
     */
    private fun edgeDepth(s: Spec, a: Art, k: Float): Float? = when {
        a.bottom <= s.slice[0] -> (a.bottom - s.bubble[0]) * k
        a.top >= s.height - s.slice[2] -> (s.bubble[2] - a.top) * k
        else -> null
    }

    /**
     * Where picture row [row] lands in a box [boxH] dp tall: rows in the top or bottom cap keep their
     * place from that edge, rows between stretch with the middle (as [nineSlice] draws them).
     */
    fun rowAt(s: Spec, row: Int, boxH: Float): Float {
        val k = s.layout.scale
        val t = s.slice[0]; val b = s.slice[2]
        return when {
            row <= t -> row * k
            row >= s.height - b -> boxH - (s.height - row) * k
            else -> t * k + (row - t) * (boxH - (t + b) * k) / max(1, s.height - t - b)
        }
    }

    /**
     * The words' clearance on one side, from the box's edge, in dp, for words from [textTop] to
     * [textBottom] (dp, box coordinates) in a box [boxH] tall: 11 dp from the bubble's outline, or
     * 4 dp past art whose rows come within 4 dp of the words (owner 2026-11-07: Security's peeker
     * sits above the bubble, so a one-liner gets no extra room on that side). The room from the
     * outline is a whole number of dp, as iOS (PictureStyle.sideRoom).
     */
    fun sideClearance(s: Spec, right: Boolean, textTop: Float, textBottom: Float, boxH: Float): Float {
        val l = s.layout
        val edge = if (right) l.edgeRight else l.edgeLeft
        var room = (if (right) l.sideRight else l.sideLeft) - edge
        for (a in s.art) {
            if (a.right != right) continue
            val top = rowAt(s, a.top, boxH)
            val bottom = rowAt(s, a.bottom, boxH)
            if (top < textBottom + ART_TEXT_DP && bottom > textTop - ART_TEXT_DP) room = max(room, artClear(a, l.scale))
        }
        return edge + ceil(room - 1e-4f)
    }

    /**
     * Where the words go: the box drawn is [width] by [height], the words' top left at [x], [y];
     * [padLeft] / [padRight] the side clearances used (from the box's edges).
     */
    class Placement(val width: Float, val height: Float, val x: Float, val y: Float,
                    val padLeft: Float = 0f, val padRight: Float = 0f)

    /**
     * The box and the words' spot for content measured [contentW] by [contentH] (measured with at
     * most `maxW - padLeft - padRight` of room), all in one unit: [d] units per dp (1 for dp, density
     * for px). The content is the whole block (sender, quote or link card, words, time): one block
     * with the same room on each row.
     *
     * NO MIRRORED ROOM (owner 2026-11-07: short words 40-50 dp in from the left on bunny, sakura,
     * skull, and an empty band after the time): each side keeps only its own clearance, from
     * [sideClearance] at the content's final rows, so the box is the content plus those two. Only a
     * box the corners make wider than that (the left + right slices) centres the content in the
     * spare width. Vertically it is centred between the bubble's edges, inside the top and bottom
     * clearances. [centre] false (a link-card bubble): the content at the left and top clearances.
     */
    fun place(s: Spec, contentW: Float, contentH: Float, maxW: Float = Float.MAX_VALUE, d: Float = 1f,
              centre: Boolean = true): Placement {
        val l = s.layout
        val padT = l.padTop * d; val padB = l.padBottom * d
        val h = max(l.minHeight * d, contentH + padT + padB)
        val edgeT = l.edgeTop * d; val edgeB = h - l.edgeBottom * d
        val y = if (!centre) padT else ((edgeT + edgeB - contentH) / 2f).coerceIn(padT, max(padT, h - padB - contentH))
        val padL = sideClearance(s, false, y / d, (y + contentH) / d, h / d) * d
        val padR = sideClearance(s, true, y / d, (y + contentH) / d, h / d) * d
        val w = max(l.minWidth * d, contentW + padL + padR)
        if (!centre) return Placement(w, h, padL, y, padL, padR)
        val edgeL = l.edgeLeft * d; val edgeR = w - l.edgeRight * d
        val x = ((edgeL + edgeR - contentW) / 2f).coerceIn(padL, max(padL, w - padR - contentW))
        return Placement(w, h, x, y, padL, padR)
    }

    /**
     * The 9 parts: for each, the source rect in a bitmap [bw] by [bh] (the picture decoded at any
     * size) and the destination rect in a box [w] by [h] px, with the caps [capT]..[capL] in px.
     * Written into [out] as 9 x (srcL, srcT, srcR, srcB, dstL, dstT, dstR, dstB); a part with no area
     * is all zeros and skipped by the drawer. Caps wider than the box shrink evenly, never overlap.
     */
    fun nineSlice(s: Spec, bw: Int, bh: Int, w: Int, h: Int,
                  capT: Float, capR: Float, capB: Float, capL: Float, out: IntArray = IntArray(72)): IntArray {
        val fx = bw.toFloat() / s.width
        val fy = bh.toFloat() / s.height
        val sx = intArrayOf(0, Math.round(s.slice[3] * fx), bw - Math.round(s.slice[1] * fx), bw)
        val sy = intArrayOf(0, Math.round(s.slice[0] * fy), bh - Math.round(s.slice[2] * fy), bh)
        val shrinkX = if (capL + capR > w) w / (capL + capR) else 1f
        val shrinkY = if (capT + capB > h) h / (capT + capB) else 1f
        val dl = Math.round(capL * shrinkX)
        val dr = w - Math.round(capR * shrinkX)
        val dt = Math.round(capT * shrinkY)
        val db = h - Math.round(capB * shrinkY)
        val dx = intArrayOf(0, dl, max(dl, dr), w)
        val dy = intArrayOf(0, dt, max(dt, db), h)
        var o = 0
        for (row in 0 until 3) for (col in 0 until 3) {
            val ok = sx[col + 1] > sx[col] && sy[row + 1] > sy[row] && dx[col + 1] > dx[col] && dy[row + 1] > dy[row]
            if (ok) {
                out[o] = sx[col]; out[o + 1] = sy[row]; out[o + 2] = sx[col + 1]; out[o + 3] = sy[row + 1]
                out[o + 4] = dx[col]; out[o + 5] = dy[row]; out[o + 6] = dx[col + 1]; out[o + 7] = dy[row + 1]
            } else for (i in 0 until 8) out[o + i] = 0
            o += 8
        }
        return out
    }

    /**
     * How far to shrink the picture when it is decoded: screen pixels per picture pixel at display
     * size ([Layout.scale] times [density]), 0.75 of that on a low-end phone, never above 1.
     */
    fun decodeScale(s: Spec, density: Float, lowEnd: Boolean): Float =
        min(1f, s.layout.scale * density * (if (lowEnd) 0.75f else 1f))

    /** Words on a picture bubble: white on the dark ones, a warm near-black on the light ones (the tested previews). */
    fun textColor(s: Spec): Long = if (s.lightText) 0xFFFFFF else 0x2A1E18

    /**
     * SPEC 3 and 5. What one bubble draws for a style: [Look.ART] on EVERY message that carries it
     * (owner 2026-11-07: with last-of-run only, a new message made the one before lose its picture),
     * whatever comes before or after it; [Look.NONE] when styles are off here (a channel, or a group
     * whose owner or admin turned them off), or the id is unknown.
     */
    enum class Look { NONE, ART }

    fun look(id: String?, stylesOff: Boolean): Look =
        if (stylesOff || find(id) == null) Look.NONE else Look.ART

    /** The asset path of a style's picture. */
    fun asset(id: String) = "bubbles/$id.webp"

    fun find(id: String?): Spec? = if (id.isNullOrEmpty()) null else byId[id]

    val sections: List<String> = listOf("Anime", "Gen Z", "Cute", "Careers", "Sport", "Everyday")

    /** Art inside the bubble on its left / right: picture rows [top]..[bottom], [reach] px in from that edge. */
    private fun L(top: Int, bottom: Int, reach: Int) = Art(false, top, bottom, reach)
    private fun R(top: Int, bottom: Int, reach: Int) = Art(true, top, bottom, reach)

    val all: List<Spec> = listOf(
        Spec("sakura", "Sakura", "Anime", intArrayOf(207, 125, 158, 208), 721, 370,
            intArrayOf(108, 711, 350, 0), intArrayOf(0, 104, 0, 177), lightText = false, fill = 0xFED1DA, radius = 97,
            art = listOf(R(285, 336, 99))),
        Spec("blush", "Blush", "Anime", intArrayOf(235, 187, 151, 121), 888, 410,
            intArrayOf(123, 887, 385, 22), intArrayOf(0, 175, 0, 60), lightText = false, fill = 0xD8ECFE, radius = 97,
            art = listOf(L(319, 385, 60))),
        Spec("manga", "Manga", "Anime", intArrayOf(240, 103, 133, 160), 852, 397,
            intArrayOf(127, 850, 396, 0), intArrayOf(0, 55, 0, 149), lightText = false, fill = 0xFFFFFF, radius = 100,
            art = listOf(R(295, 307, 35), R(337, 379, 94))),
        Spec("bunny", "Bunny", "Anime", intArrayOf(218, 196, 176, 117), 888, 448,
            intArrayOf(101, 887, 437, 0), intArrayOf(0, 160, 0, 70), lightText = false, fill = 0xFECDE2, radius = 115,
            art = listOf(L(376, 409, 66), R(115, 167, 164))),
        Spec("catgirl", "Cat Girl", "Anime", intArrayOf(241, 189, 172, 124), 898, 437,
            intArrayOf(113, 892, 436, 0), intArrayOf(0, 173, 0, 82), lightText = false, fill = 0xE6D9FD, radius = 122,
            art = emptyList()),
        Spec("baddie", "Baddie", "Anime", intArrayOf(231, 106, 150, 188), 860, 404,
            intArrayOf(125, 859, 403, 0), intArrayOf(0, 69, 0, 177), lightText = false, fill = 0xFEC8D6, radius = 104,
            art = listOf(L(139, 145, 166), R(339, 377, 70))),
        Spec("skull", "Skull", "Gen Z", intArrayOf(163, 107, 104, 172), 942, 461,
            intArrayOf(86, 925, 434, 0), intArrayOf(0, 80, 0, 162), lightText = true, fill = 0x211F27, radius = 75,
            art = listOf(R(371, 418, 62))),
        Spec("coolcamel", "Cool Camel", "Gen Z", intArrayOf(234, 177, 207, 121), 916, 466,
            intArrayOf(113, 915, 430, 0), intArrayOf(0, 67, 0, 72), lightText = true, fill = 0x251D30, radius = 119,
            art = listOf(L(393, 399, 66))),
        Spec("fire", "Fire", "Gen Z", intArrayOf(175, 83, 96, 226), 883, 399,
            intArrayOf(89, 882, 398, 32), intArrayOf(0, 72, 0, 184), lightText = true, fill = 0x1C1B20, radius = 75,
            art = listOf(R(308, 379, 76))),
        Spec("eyes", "Eyes", "Gen Z", intArrayOf(199, 112, 158, 200), 868, 397,
            intArrayOf(87, 867, 396, 0), intArrayOf(0, 61, 0, 61), lightText = true, fill = 0x0A0A0A, radius = 110,
            art = listOf(L(103, 110, 253), R(355, 361, 69))),
        Spec("hood", "Hood", "Gen Z", intArrayOf(208, 147, 153, 108), 802, 431,
            intArrayOf(100, 801, 430, 0), intArrayOf(0, 59, 0, 59), lightText = true, fill = 0x13161E, radius = 106,
            art = emptyList()),
        Spec("crown", "Crown", "Gen Z", intArrayOf(135, 103, 106, 137), 916, 379,
            intArrayOf(58, 915, 378, 42), intArrayOf(0, 85, 0, 78), lightText = true, fill = 0x181818, radius = 75,
            art = listOf(L(72, 111, 79), R(285, 347, 90))),
        Spec("camel", "Camel", "Cute", intArrayOf(256, 132, 187, 218), 914, 456,
            intArrayOf(124, 913, 455, 0), intArrayOf(0, 115, 0, 193), lightText = false, fill = 0xFDE2B9, radius = 130,
            art = listOf(L(138, 158, 210), R(330, 431, 128))),
        Spec("kitten", "Kitten", "Cute", intArrayOf(210, 181, 155, 131), 854, 388,
            intArrayOf(100, 853, 387, 0), intArrayOf(0, 170, 0, 121), lightText = false, fill = 0xFEF9ED, radius = 108,
            art = listOf(L(305, 373, 123), R(114, 119, 170))),
        Spec("bear", "Bear", "Cute", intArrayOf(190, 98, 107, 171), 862, 383,
            intArrayOf(113, 841, 363, 0), intArrayOf(0, 67, 0, 161), lightText = false, fill = 0xFDD782, radius = 75,
            art = listOf(L(127, 133, 160), R(282, 348, 72))),
        Spec("strawberry", "Strawberry", "Cute", intArrayOf(152, 130, 168, 119), 885, 387,
            intArrayOf(46, 860, 367, 13), intArrayOf(0, 86, 0, 77), lightText = false, fill = 0xFFC3CA, radius = 104,
            art = listOf(L(63, 116, 82), R(290, 351, 92))),
        Spec("glam", "Glam", "Cute", intArrayOf(189, 155, 159, 258), 892, 372,
            intArrayOf(41, 859, 361, 33), intArrayOf(0, 72, 0, 206), lightText = false, fill = 0xFFFFFF, radius = 121,
            art = listOf(L(55, 102, 214), L(243, 324, 74), R(243, 325, 75))),
        Spec("doctor", "Doctor", "Careers", intArrayOf(188, 82, 77, 165), 890, 445,
            intArrayOf(111, 884, 444, 0), intArrayOf(0, 40, 0, 155), lightText = false, fill = 0xD0F1EB, radius = 75,
            art = listOf(R(365, 430, 75))),
        Spec("nurse", "Nurse", "Careers", intArrayOf(205, 135, 170, 130), 888, 444,
            intArrayOf(97, 887, 426, 0), intArrayOf(0, 123, 0, 59), lightText = false, fill = 0xD4EAFE, radius = 106,
            art = listOf(R(398, 412, 126))),
        Spec("lawyer", "Lawyer", "Careers", intArrayOf(192, 131, 115, 123), 877, 437,
            intArrayOf(110, 876, 436, 26), intArrayOf(0, 120, 0, 87), lightText = false, fill = 0xF9EDD5, radius = 80,
            art = listOf(L(331, 409, 90))),
        Spec("engineer", "Engineer", "Careers", intArrayOf(247, 123, 115, 109), 893, 454,
            intArrayOf(110, 891, 435, 12), intArrayOf(0, 52, 0, 65), lightText = false, fill = 0xE4E5E9, radius = 95,
            art = listOf(L(316, 357, 18), L(417, 421, 151))),
        Spec("teacher", "Teacher", "Careers", intArrayOf(173, 144, 77, 159), 875, 427,
            intArrayOf(96, 873, 426, 0), intArrayOf(0, 132, 0, 40), lightText = true, fill = 0x354E41, radius = 75,
            art = listOf(R(357, 410, 135))),
        Spec("pilot", "Pilot", "Careers", intArrayOf(255, 152, 191, 182), 924, 462,
            intArrayOf(120, 923, 461, 0), intArrayOf(0, 140, 0, 171), lightText = false, fill = 0xC1E9FE, radius = 133,
            art = listOf(L(348, 437, 172))),
        Spec("chef", "Chef", "Careers", intArrayOf(217, 130, 174, 128), 861, 441,
            intArrayOf(118, 847, 405, 0), intArrayOf(0, 106, 0, 69), lightText = false, fill = 0xFEF7EC, radius = 97,
            art = listOf(R(343, 391, 109))),
        Spec("graduate", "Graduate", "Careers", intArrayOf(179, 179, 86, 88), 843, 369,
            intArrayOf(102, 833, 368, 0), intArrayOf(0, 40, 0, 72), lightText = true, fill = 0x273973, radius = 75,
            art = listOf(L(266, 273, 23), L(293, 352, 113))),
        Spec("baller", "Baller", "Careers", intArrayOf(199, 116, 105, 209), 912, 407,
            intArrayOf(122, 911, 406, 0), intArrayOf(0, 100, 0, 40), lightText = true, fill = 0x3B2062, radius = 75,
            art = listOf(R(312, 384, 105))),
        Spec("security", "Security", "Careers", intArrayOf(209, 165, 97, 89), 914, 469,
            intArrayOf(132, 913, 468, 0), intArrayOf(0, 154, 0, 79), lightText = true, fill = 0x232834, radius = 75,
            art = listOf(L(382, 445, 78))),
        Spec("football", "Football", "Sport", intArrayOf(143, 107, 106, 182), 871, 353,
            intArrayOf(66, 853, 331, 0), intArrayOf(0, 79, 0, 172), lightText = false, fill = 0xB2E387, radius = 75,
            art = listOf(L(80, 112, 74), R(251, 317, 92))),
        Spec("basketball", "Basketball", "Sport", intArrayOf(129, 117, 160, 110), 839, 346,
            intArrayOf(44, 821, 304, 0), intArrayOf(0, 88, 0, 99), lightText = false, fill = 0xFEA34E, radius = 83,
            art = listOf(L(274, 290, 102), R(58, 110, 93))),
        Spec("trophy", "Trophy", "Sport", intArrayOf(146, 77, 108, 115), 847, 309,
            intArrayOf(69, 846, 308, 5), intArrayOf(0, 44, 0, 85), lightText = false, fill = 0xFFDC8B, radius = 75,
            art = listOf(L(84, 114, 89), R(233, 287, 80))),
        Spec("racing", "Racing", "Sport", intArrayOf(239, 154, 162, 138), 868, 425,
            intArrayOf(77, 867, 424, 0), intArrayOf(0, 142, 0, 75), lightText = false, fill = 0xDEDAE0, radius = 132,
            art = listOf(R(337, 410, 129))),
        Spec("coffee", "Coffee", "Everyday", intArrayOf(151, 141, 165, 142), 850, 347,
            intArrayOf(34, 837, 346, 25), intArrayOf(0, 102, 0, 64), lightText = false, fill = 0xECCEB2, radius = 115,
            art = listOf(L(262, 320, 60), R(52, 84, 107))),
        Spec("night", "Night", "Everyday", intArrayOf(131, 95, 102, 127), 856, 359,
            intArrayOf(27, 846, 358, 25), intArrayOf(0, 74, 0, 91), lightText = true, fill = 0x2B395F, radius = 84,
            art = listOf(L(71, 120, 91), R(267, 326, 74))),
        Spec("leaf", "Leaf", "Everyday", intArrayOf(187, 122, 123, 167), 884, 375,
            intArrayOf(42, 864, 354, 24), intArrayOf(0, 56, 0, 115), lightText = false, fill = 0xD9E6CF, radius = 101,
            art = listOf(L(59, 86, 112), L(99, 129, 67), R(316, 323, 51))),
        Spec("minimal", "Minimal", "Everyday", intArrayOf(140, 94, 107, 134), 792, 306,
            intArrayOf(23, 780, 281, 51), intArrayOf(0, 44, 0, 44), lightText = false, fill = 0xFFFFFF, radius = 81,
            art = emptyList()),
    )

    private val byId: Map<String, Spec> = all.associateBy { it.id }
}
