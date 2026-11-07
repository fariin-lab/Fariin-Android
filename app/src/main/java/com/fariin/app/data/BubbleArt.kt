package com.fariin.app.data

import kotlin.math.max
import kotlin.math.min

/**
 * Picture bubble styles (owner 2026-11-07, fariin-bubbles/SPEC.md, the same file iOS builds from).
 * Each one is a stretchable picture in assets/bubbles/<id>.webp, cut into 9 parts by [Spec.slice]:
 * the corners keep their size and the middle stretches, so one picture fits any length of text.
 * The art never changes with light or dark mode; each style carries its own text colour.
 *
 * Ids are the picture file names and stay the same forever (a removed style is never reused).
 * The table below is copied from bubbles/<id>.json; doctor, lawyer, security and manga have 12 px
 * more text clearance on their art's sides than the json (SPEC 6: their corner art touched the words);
 * the doctor's red cross reaches 73 px in at the bottom right, where its json said 0: it clears 161 px
 * on the left and 85 on the right, iOS's numbers.
 *
 * Everything here is plain numbers, so the layout is unit tested without a phone.
 */
object BubbleArt {
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
    ) {
        val layout: Layout by lazy { layoutOf(this) }
    }

    /**
     * Where things go at display size, in dp. [scale] is dp per picture pixel. The box drawn is the
     * whole picture stretched; the words sit inside it at [padTop] / [padRight] / [padBottom] / [padLeft].
     */
    class Layout(
        val scale: Float,
        val capTop: Float, val capRight: Float, val capBottom: Float, val capLeft: Float,
        val padTop: Float, val padRight: Float, val padBottom: Float, val padLeft: Float,
        val minWidth: Float, val minHeight: Float,
        /** The bubble's own edge, inset from the box: for the dark-mode rim and the plain fill while loading. */
        val edgeTop: Float, val edgeRight: Float, val edgeBottom: Float, val edgeLeft: Float,
        val edgeRadius: Float,
    )

    /** SPEC 6: at most 0.24 dp per picture px... */
    const val MAX_SCALE = 0.24f
    /** ...and the slice parts that sit inside the bubble fit about 44 dp, so one word stays one line tall. */
    const val INSIDE_DP = 44f
    /** The words keep at least 40 px from the bubble's side (more where [Spec.ins] says), and 10 dp from its top and bottom. */
    const val SIDE_PX = 40
    const val ART_GAP_PX = 6
    const val VERTICAL_DP = 10f
    /** The pictures' corner radius, about 24 px. */
    const val RADIUS_PX = 24f

    fun layoutOf(s: Spec): Layout {
        val (t, r, b, l) = s.slice.toList()
        val (bt, br, bb, bl) = s.bubble.toList()
        val insideTb = (t - bt) + (b - (s.height - bb))
        val k = min(MAX_SCALE, INSIDE_DP / max(insideTb, 1))
        val belowEdge = (s.height - bb) * k
        val rightOut = (s.width - br) * k
        return Layout(
            scale = k,
            capTop = t * k, capRight = r * k, capBottom = b * k, capLeft = l * k,
            padTop = bt * k + VERTICAL_DP,
            padRight = rightOut + max(SIDE_PX, s.ins[1] + ART_GAP_PX) * k,
            padBottom = belowEdge + VERTICAL_DP,
            padLeft = (bl + max(SIDE_PX, s.ins[3] + ART_GAP_PX)) * k,
            minWidth = (l + r) * k, minHeight = (t + b) * k,
            edgeTop = bt * k, edgeRight = rightOut, edgeBottom = belowEdge, edgeLeft = bl * k,
            edgeRadius = RADIUS_PX * k,
        )
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

    val all: List<Spec> = listOf(
        Spec("sakura", "Sakura", "Anime", intArrayOf(189, 125, 158, 208), 721, 370,
            intArrayOf(108, 710, 350, 1), intArrayOf(0, 104, 0, 177), lightText = false, fill = 0xFED1DA),
        Spec("blush", "Blush", "Anime", intArrayOf(235, 187, 151, 92), 888, 410,
            intArrayOf(124, 886, 384, 22), intArrayOf(0, 175, 0, 60), lightText = false, fill = 0xD8ECFE),
        Spec("manga", "Manga", "Anime", intArrayOf(240, 65, 133, 160), 852, 397,
            intArrayOf(127, 845, 395, 1), intArrayOf(0, 24, 0, 161), lightText = false, fill = 0xFFFFFF),
        Spec("bunny", "Bunny", "Anime", intArrayOf(175, 196, 176, 81), 888, 448,
            intArrayOf(102, 886, 436, 1), intArrayOf(0, 160, 0, 70), lightText = false, fill = 0xFECDE2),
        Spec("catgirl", "Cat Girl", "Anime", intArrayOf(241, 189, 172, 92), 898, 437,
            intArrayOf(113, 892, 435, 0), intArrayOf(0, 173, 0, 82), lightText = false, fill = 0xE6D9FD),
        Spec("baddie", "Baddie", "Anime", intArrayOf(185, 81, 150, 188), 860, 404,
            intArrayOf(125, 858, 402, 1), intArrayOf(0, 69, 0, 177), lightText = false, fill = 0xFEC8D6),
        Spec("skull", "Skull", "Gen Z", intArrayOf(162, 107, 104, 172), 942, 461,
            intArrayOf(86, 925, 433, 0), intArrayOf(0, 80, 0, 162), lightText = true, fill = 0x211F27),
        Spec("coolcamel", "Cool Camel", "Gen Z", intArrayOf(182, 177, 207, 83), 916, 466,
            intArrayOf(113, 914, 430, 1), intArrayOf(0, 0, 0, 72), lightText = true, fill = 0x251D30),
        Spec("fire", "Fire", "Gen Z", intArrayOf(175, 83, 96, 226), 883, 399,
            intArrayOf(89, 882, 397, 32), intArrayOf(0, 72, 0, 184), lightText = true, fill = 0x1C1B20),
        Spec("eyes", "Eyes", "Gen Z", intArrayOf(155, 69, 158, 200), 868, 397,
            intArrayOf(88, 866, 396, 1), intArrayOf(0, 0, 0, 0), lightText = true, fill = 0x0A0A0A),
        Spec("hood", "Hood", "Gen Z", intArrayOf(173, 147, 153, 73), 802, 431,
            intArrayOf(101, 800, 430, 1), intArrayOf(0, 0, 0, 0), lightText = true, fill = 0x13161E),
        Spec("crown", "Crown", "Gen Z", intArrayOf(132, 103, 106, 137), 916, 379,
            intArrayOf(65, 908, 371, 49), intArrayOf(0, 85, 0, 78), lightText = true, fill = 0x181818),
        Spec("camel", "Camel", "Cute", intArrayOf(245, 127, 187, 218), 914, 456,
            intArrayOf(125, 912, 454, 0), intArrayOf(0, 115, 0, 193), lightText = false, fill = 0xFDE2B9),
        Spec("kitten", "Kitten", "Cute", intArrayOf(209, 181, 155, 131), 854, 388,
            intArrayOf(101, 853, 387, 0), intArrayOf(0, 170, 0, 121), lightText = false, fill = 0xFEF9ED),
        Spec("bear", "Bear", "Cute", intArrayOf(167, 98, 107, 171), 862, 383,
            intArrayOf(113, 841, 362, 0), intArrayOf(0, 67, 0, 161), lightText = false, fill = 0xFDD782),
        Spec("strawberry", "Strawberry", "Cute", intArrayOf(122, 122, 168, 101), 885, 387,
            intArrayOf(47, 859, 366, 14), intArrayOf(0, 86, 0, 77), lightText = false, fill = 0xFFC3CA),
        Spec("glam", "Glam", "Cute", intArrayOf(189, 116, 159, 258), 892, 372,
            intArrayOf(41, 858, 360, 33), intArrayOf(0, 72, 0, 206), lightText = false, fill = 0xFFFFFF),
        Spec("doctor", "Doctor", "Careers", intArrayOf(184, 79, 75, 165), 890, 445,
            intArrayOf(111, 884, 443, 0), intArrayOf(0, 79, 0, 155), lightText = false, fill = 0xD0F1EB),
        Spec("nurse", "Nurse", "Careers", intArrayOf(169, 135, 170, 130), 888, 444,
            intArrayOf(97, 886, 426, 1), intArrayOf(0, 123, 0, 0), lightText = false, fill = 0xD4EAFE),
        Spec("lawyer", "Lawyer", "Careers", intArrayOf(182, 131, 115, 123), 877, 437,
            intArrayOf(111, 876, 435, 26), intArrayOf(0, 132, 0, 99), lightText = false, fill = 0xF9EDD5),
        Spec("engineer", "Engineer", "Careers", intArrayOf(247, 123, 91, 87), 893, 454,
            intArrayOf(111, 891, 434, 12), intArrayOf(0, 43, 0, 65), lightText = false, fill = 0xE4E5E9),
        Spec("teacher", "Teacher", "Careers", intArrayOf(169, 144, 74, 159), 875, 427,
            intArrayOf(97, 873, 425, 0), intArrayOf(0, 132, 0, 0), lightText = true, fill = 0x354E41),
        Spec("pilot", "Pilot", "Careers", intArrayOf(195, 152, 191, 182), 924, 462,
            intArrayOf(121, 922, 460, 1), intArrayOf(0, 140, 0, 171), lightText = false, fill = 0xC1E9FE),
        Spec("chef", "Chef", "Careers", intArrayOf(181, 130, 174, 128), 861, 441,
            intArrayOf(119, 847, 404, 0), intArrayOf(0, 106, 0, 69), lightText = false, fill = 0xFEF7EC),
        Spec("graduate", "Graduate", "Careers", intArrayOf(163, 179, 86, 88), 843, 369,
            intArrayOf(108, 827, 362, 6), intArrayOf(0, 0, 0, 72), lightText = true, fill = 0x273973),
        Spec("baller", "Baller", "Careers", intArrayOf(187, 116, 105, 209), 912, 407,
            intArrayOf(127, 906, 401, 5), intArrayOf(0, 100, 0, 0), lightText = true, fill = 0x3B2062),
        Spec("security", "Security", "Careers", intArrayOf(206, 165, 97, 89), 914, 469,
            intArrayOf(133, 913, 467, 0), intArrayOf(0, 166, 0, 91), lightText = true, fill = 0x232834),
        Spec("football", "Football", "Sport", intArrayOf(125, 107, 106, 182), 871, 353,
            intArrayOf(67, 853, 331, 0), intArrayOf(0, 79, 0, 172), lightText = false, fill = 0xB2E387),
        Spec("basketball", "Basketball", "Sport", intArrayOf(117, 117, 160, 110), 839, 346,
            intArrayOf(44, 820, 303, 1), intArrayOf(0, 88, 0, 99), lightText = false, fill = 0xFEA34E),
        Spec("trophy", "Trophy", "Sport", intArrayOf(122, 56, 108, 115), 847, 309,
            intArrayOf(70, 845, 307, 6), intArrayOf(0, 44, 0, 85), lightText = false, fill = 0xFFDC8B),
        Spec("racing", "Racing", "Sport", intArrayOf(239, 154, 162, 138), 868, 425,
            intArrayOf(77, 866, 424, 1), intArrayOf(0, 142, 0, 0), lightText = false, fill = 0xDEDAE0),
        Spec("coffee", "Coffee", "Everyday", intArrayOf(100, 126, 165, 90), 850, 347,
            intArrayOf(35, 836, 334, 25), intArrayOf(0, 102, 0, 0), lightText = false, fill = 0xECCEB2),
        Spec("night", "Night", "Everyday", intArrayOf(131, 94, 102, 127), 856, 359,
            intArrayOf(28, 846, 357, 26), intArrayOf(0, 74, 0, 91), lightText = true, fill = 0x2B395F),
        Spec("leaf", "Leaf", "Everyday", intArrayOf(187, 89, 112, 167), 884, 375,
            intArrayOf(43, 863, 354, 24), intArrayOf(0, 56, 0, 115), lightText = false, fill = 0xD9E6CF),
        Spec("minimal", "Minimal", "Everyday", intArrayOf(140, 68, 82, 107), 792, 306,
            intArrayOf(23, 780, 280, 51), intArrayOf(0, 38, 0, 0), lightText = false, fill = 0xFFFFFF),
    )

    private val byId: Map<String, Spec> = all.associateBy { it.id }
}
