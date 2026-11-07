package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.pow

/** Picture bubble styles (fariin-bubbles/SPEC.md): the table, the layout numbers, the 9-slice and the run rule. */
class BubbleArtTest {
    @Test fun thirtyFiveStylesUniqueIdsInTheSixSections() {
        assertEquals(35, BubbleArt.all.size)
        assertEquals(35, BubbleArt.all.map { it.id }.toSet().size)
        for (s in BubbleArt.all) {
            assertTrue(s.id, s.section in BubbleArt.sections)
            assertTrue(s.id, s.id.length <= 32 && s.id.matches(Regex("[a-z]+")))
        }
    }

    @Test fun everyStyleHasItsPictureInTheAssets() {
        val dir = listOf(File("src/main/assets/bubbles"), File("app/src/main/assets/bubbles")).first { it.isDirectory }
        for (s in BubbleArt.all) assertTrue(s.id, File(dir, "${s.id}.webp").isFile)
        assertEquals(35, dir.listFiles { f -> f.name.endsWith(".webp") }!!.size)
    }

    @Test fun tableIsSane() {
        for (s in BubbleArt.all) {
            val (t, r, b, l) = s.slice.toList()
            val (bt, br, bb, bl) = s.bubble.toList()
            assertTrue(s.id, t + b < s.height && l + r < s.width)
            assertTrue(s.id, bt in 0 until bb && bb <= s.height && bl in 0 until br && br <= s.width)
            // The fixed caps reach past the bubble's edge into it, so the middle that stretches is plain bubble.
            assertTrue(s.id, t >= bt && b >= s.height - bb && l >= bl && r >= s.width - br)
        }
    }

    @Test fun v2TableIsTheJson() {
        // bubbles_v2/doctor.json: slice [188,82,77,165], bubble [111,884,444,0], ins [0,40,0,155], radius 75.
        val doc = BubbleArt.find("doctor")!!
        assertEquals(listOf(188, 82, 77, 165), doc.slice.toList())
        assertEquals(listOf(111, 884, 444, 0), doc.bubble.toList())
        assertEquals(listOf(0, 40, 0, 155), doc.ins.toList())
        assertEquals(75, doc.radius)
        assertEquals(listOf(0, 120, 0, 87), BubbleArt.find("lawyer")!!.ins.toList())
        assertEquals(listOf(0, 154, 0, 79), BubbleArt.find("security")!!.ins.toList())
        assertEquals(listOf(0, 55, 0, 149), BubbleArt.find("manga")!!.ins.toList())
        assertEquals(listOf(0, 44, 0, 85), BubbleArt.find("trophy")!!.ins.toList())
        assertEquals(132, BubbleArt.find("racing")!!.radius)
    }

    /** The round corners: about 18 dp at display size on every style, drawn from the json's radius. */
    @Test fun cornerRadiusFromTheJson() {
        for (s in BubbleArt.all) {
            val l = s.layout
            assertEquals(s.id, s.radius * l.scale, l.edgeRadius, 1e-4f)
            assertTrue("${s.id} ${l.edgeRadius}", l.edgeRadius in 16f..20f)
        }
    }

    /** Room (dp) from the bubble's outline to a short one-line message ("test" + time), left and right. */
    private fun oneLineGaps(id: String): Pair<Float, Float> {
        val s = BubbleArt.find(id)!!
        val p = BubbleArt.place(s, 70f, 22f, 300f)
        return (p.padLeft - s.layout.edgeLeft) to (p.padRight - s.layout.edgeRight)
    }

    /**
     * The same one-line gaps as iOS (Kulan-iOS 6633d043): 11 dp from the outline unless art really
     * meets the words, then 4 dp past the art, rounded up to whole dp. No mirrored room.
     */
    @Test fun oneLineGapsMatchIos() {
        val want = mapOf(
            "bunny" to (11f to 11f), "baddie" to (11f to 11f), "sakura" to (11f to 23f), "skull" to (11f to 19f),
            "doctor" to (11f to 22f), "security" to (23f to 11f), "football" to (11f to 27f),
        )
        for ((id, lr) in want) {
            val (l, r) = oneLineGaps(id)
            assertEquals("$id left", lr.first, l, 1e-3f)
            assertEquals("$id right", lr.second, r, 1e-3f)
        }
    }

    /** Worked from the json: doctor's cross reaches 75 px in at 0.24 dp/px: 75 x 0.24 + 4 = 22. */
    @Test fun gapsWorkedFromTheJson() {
        val k = 0.24f   // doctor: (188 - 111) + (77 - 1) = 153 px inside: 44 / 153 is over the 0.24 cap
        assertEquals(k, BubbleArt.find("doctor")!!.layout.scale, 1e-6f)
        assertEquals(kotlin.math.ceil(75 * k + 4f), oneLineGaps("doctor").second, 1e-3f)
        // Security: shield 78 px in on the left (78 x 0.24 + 4 = 22.72, so 23); the peeker sits above the bubble.
        assertEquals(kotlin.math.ceil(78 * 0.24f + 4f), oneLineGaps("security").first, 1e-3f)
        // Lawyer: scales 90 px in at 44 / 196 dp/px.
        assertEquals(kotlin.math.ceil(90 * (44f / 196f) + 4f), oneLineGaps("lawyer").first, 1e-3f)
        assertEquals(11f, oneLineGaps("lawyer").second, 1e-3f)
        // Trophy: confetti 80 px in on the right at 44 / 184; the cup base rests on the top edge.
        assertEquals(kotlin.math.ceil(80 * (44f / 184f) + 4f), oneLineGaps("trophy").second, 1e-3f)
        // Manga: speed lines 94 px in at 44 / 245; the head above the left side takes nothing.
        assertEquals(kotlin.math.ceil(94 * (44f / 245f) + 4f), oneLineGaps("manga").second, 1e-3f)
        assertEquals(11f, oneLineGaps("manga").first, 1e-3f)
    }

    /**
     * Art wholly in a top corner that ends within 12 dp of the edge (trophy's cup base: rows 84..114,
     * the outline's top at row 69, 45 px = 10.8 dp) moves the words down to 4 dp past it (15 dp
     * from the edge) instead of taking room beside them.
     */
    @Test fun handsOnTheEdgeMoveTheWordsDown() {
        val s = BubbleArt.find("trophy")!!
        val l = s.layout
        val k = 44f / ((146 - 69) + (108 - 1))
        val depth = (114 - 69) * k
        assertTrue(depth <= 12f)
        assertEquals(l.edgeTop + kotlin.math.ceil(depth + 4f), l.padTop, 1e-3f)
        assertEquals(11f, oneLineGaps("trophy").first, 1e-3f)
        // Art in the stretching middle (doctor's cross) never moves the words: the plain 10 dp.
        val d = BubbleArt.find("doctor")!!.layout
        assertEquals(d.edgeBottom + 10f, d.padBottom, 1e-3f)
    }

    /** Art rows map onto the box like the 9-slice draws them: top cap from the top, bottom cap from the bottom. */
    @Test fun artRowsFollowTheNineSlice() {
        val s = BubbleArt.find("doctor")!!
        val k = s.layout.scale
        assertEquals(100 * k, BubbleArt.rowAt(s, 100, 90f), 1e-4f)
        assertEquals(90f - 15 * k, BubbleArt.rowAt(s, 430, 90f), 1e-4f)
        // Row 365 is in the stretching middle (188..368): between the two caps.
        val mid = BubbleArt.rowAt(s, 365, 90f)
        assertTrue(mid > 188 * k && mid < 90f - 77 * k)
        // At the minimum height the middle has no room: rows 188 and 368 meet.
        assertEquals(BubbleArt.rowAt(s, 188, s.layout.minHeight), BubbleArt.rowAt(s, 368, s.layout.minHeight), 1e-3f)
    }

    /** Art that does not come within 4 dp of the words vertically takes no side room; art that does, its full clearance. */
    @Test fun clearanceOnlyWhereArtMeetsTheWords() {
        val s = BubbleArt.find("doctor")!!
        val l = s.layout
        val h = 120f
        val crossTop = BubbleArt.rowAt(s, 365, h)
        // Words ending 5 dp above the cross: plain side.
        assertEquals(l.sideRight, BubbleArt.sideClearance(s, true, 30f, crossTop - 5f, h), 1e-4f)
        // Words ending 3 dp above it: within 4 dp, so the art clearance.
        assertEquals(l.padRight, BubbleArt.sideClearance(s, true, 30f, crossTop - 3f, h), 1e-4f)
    }

    /**
     * Every style, one to five lines: wherever art comes within 4 dp of the words vertically, the
     * words stay 4 dp clear of it sideways; the words stay inside the bubble.
     */
    @Test fun wordsKeepFourDpFromArtInEveryStyle() {
        for (s in BubbleArt.all) for ((cw, ch) in listOf(4f to 22f, 70f to 22f, 160f to 44f, 200f to 66f, 210f to 110f)) {
            val l = s.layout
            val k = l.scale
            val maxW = 300f
            val w0 = minOf(cw, maxW - l.padLeft - l.padRight)
            val p = BubbleArt.place(s, w0, ch, maxW)
            assertTrue(s.id, p.width <= maxW + 1e-3f)
            assertTrue("${s.id} base left", p.x >= l.sideLeft - 1e-3f)
            assertTrue("${s.id} base right", p.x + w0 <= p.width - l.sideRight + 1e-3f)
            for (a in s.art) {
                val top = BubbleArt.rowAt(s, a.top, p.height)
                val bottom = BubbleArt.rowAt(s, a.bottom, p.height)
                if (top >= p.y + ch + BubbleArt.ART_TEXT_DP || bottom <= p.y - BubbleArt.ART_TEXT_DP) continue
                if (a.right) {
                    val artLeft = p.width - l.edgeRight - a.reach * k
                    assertTrue("${s.id} right art", p.x + w0 <= artLeft - BubbleArt.ART_TEXT_DP + 1e-3f)
                } else {
                    val artRight = l.edgeLeft + a.reach * k
                    assertTrue("${s.id} left art", p.x >= artRight + BubbleArt.ART_TEXT_DP - 1e-3f)
                }
            }
        }
    }

    /** Art reaching in sits inside the picture, less than halfway across the bubble. */
    @Test fun artTableIsSane() {
        for (s in BubbleArt.all) for (a in s.art) {
            assertTrue(s.id, a.top in 0..a.bottom && a.bottom <= s.height)
            assertTrue(s.id, a.reach in 1 until (s.bubble[1] - s.bubble[3]) / 2)
        }
        assertTrue(BubbleArt.find("security")!!.art.none { it.right })
        assertTrue(BubbleArt.find("doctor")!!.art.none { !it.right })
    }

    @Test fun scaleFollowsSpec6() {
        val sakura = BubbleArt.find("sakura")!!.layout
        // inside = (207 - 108) + (158 - (370 - 350)) = 237 px; 44 / 237 = 0.1857 < 0.24.
        assertEquals(44f / 237f, sakura.scale, 1e-5f)
        for (s in BubbleArt.all) {
            val k = s.layout.scale
            assertTrue(s.id, k > 0f && k <= BubbleArt.MAX_SCALE)
            val inside = (s.slice[0] - s.bubble[0]) + (s.slice[2] - (s.height - s.bubble[2]))
            assertTrue(s.id, inside * k <= BubbleArt.INSIDE_DP + 1e-3f)
        }
    }

    /** One line of 17 sp text (22 dp tall) fits without the caps squeezing, and the words sit inside the bubble, clear of the art. */
    @Test fun oneLineFitsAndWordsStayInsideTheBubble() {
        for (s in BubbleArt.all) {
            val l = s.layout
            val k = l.scale
            assertTrue(s.id, l.padTop > 0 && l.padBottom > 0 && l.padLeft > 0 && l.padRight > 0)
            // A one-line bubble's height: the words plus padding, or the caps, whichever is more.
            val h = maxOf(l.padTop + 22f + l.padBottom, l.minHeight)
            assertTrue("${s.id} one line ${h}dp", h <= 80f)
            // Text box inside the bubble edges.
            assertTrue(s.id, l.padTop >= l.edgeTop && l.padBottom >= l.edgeBottom)
            assertTrue(s.id, l.sideLeft >= l.edgeLeft + maxOf(BubbleArt.SIDE_PX * k, BubbleArt.SIDE_DP) - 1e-3f)
            assertTrue(s.id, l.sideRight >= l.edgeRight + maxOf(BubbleArt.SIDE_PX * k, BubbleArt.SIDE_DP) - 1e-3f)
            // The widest a side needs (the room the words are measured in) covers any height of words.
            assertTrue(s.id, l.padLeft >= l.sideLeft && l.padRight >= l.sideRight)
            for (lines in 1..12) {
                val p = BubbleArt.place(s, 50f, 22f * lines, 300f)
                assertTrue("${s.id} $lines", p.padLeft <= l.padLeft + 1e-3f && p.padRight <= l.padRight + 1e-3f)
            }
            assertEquals(s.id, (s.slice[1] + s.slice[3]) * k, l.minWidth, 1e-3f)
            assertEquals(s.id, (s.slice[0] + s.slice[2]) * k, l.minHeight, 1e-3f)
        }
    }

    /**
     * No mirrored room: the box is the content plus each side's own clearance (or the slices'
     * minimum, then centred in the spare width), never widened to give one side the other's gap.
     */
    @Test fun eachSideKeepsOnlyItsOwnRoom() {
        for (s in BubbleArt.all) for ((cw, ch) in listOf(70f to 22f, 4f to 22f, 160f to 22f, 150f to 70f)) {
            val l = s.layout
            val p = BubbleArt.place(s, cw, ch, maxW = 300f)
            val tight = cw + p.padLeft + p.padRight
            assertEquals("${s.id} $cw width", maxOf(l.minWidth, tight), p.width, 1e-3f)
            assertTrue("${s.id} $cw left clearance", p.x >= p.padLeft - 1e-3f)
            assertTrue("${s.id} $cw right clearance", p.x + cw <= p.width - p.padRight + 1e-3f)
            if (tight >= l.minWidth) assertEquals("${s.id} $cw snug", p.padLeft, p.x, 1e-3f)
            else assertEquals("${s.id} $cw centred in the slices' width",
                p.x - l.edgeLeft, p.width - l.edgeRight - (p.x + cw), 0.01f)
            assertTrue("${s.id} top", p.y >= l.padTop - 1e-3f && p.y + ch <= p.height - l.padBottom + 1e-3f)
        }
    }

    /** Words and time are one block: a tall block gets the same room on every row, no band after the time. */
    @Test fun wordsAndTimeAreOneBlock() {
        for (s in BubbleArt.all) {
            val l = s.layout
            val p = BubbleArt.place(s, 150f, 70f, 300f)
            assertEquals(s.id, maxOf(l.minWidth, 150f + p.padLeft + p.padRight), p.width, 1e-3f)
            assertEquals(s.id, p.y - l.edgeTop - (l.padTop - l.edgeTop), p.height - l.edgeBottom - (p.y + 70f) - (l.padBottom - l.edgeBottom), 0.01f)
        }
    }

    /** Long words measured in the widest room fit the max width. */
    @Test fun longWordsFitTheMaxWidth() {
        for (s in BubbleArt.all) {
            val l = s.layout
            val maxW = 300f
            val cw = maxW - l.padLeft - l.padRight
            val p = BubbleArt.place(s, cw, 66f, maxW)
            assertTrue(s.id, p.width <= maxW + 1e-3f)
            assertEquals(s.id, p.padLeft, p.x, 1e-3f)
            assertEquals(s.id, l.padTop, p.y, 1e-3f)
            assertEquals(s.id, l.padTop + 66f + l.padBottom, p.height, 1e-3f)
        }
    }

    /** A link or quote card bubble uses the same insets, at the left and top clearances. */
    @Test fun linkCardBubbleSameInsets() {
        for (s in BubbleArt.all) {
            val l = s.layout
            val p = BubbleArt.place(s, 70f, 22f, 300f, centre = false)
            assertEquals(s.id, p.padLeft, p.x, 1e-3f)
            assertEquals(s.id, l.padTop, p.y, 1e-3f)
            assertEquals(s.id, maxOf(l.minWidth, 70f + p.padLeft + p.padRight), p.width, 1e-3f)
        }
        // Football's quote card: 11 dp on the left (was about 43 with the mirrored room).
        val f = BubbleArt.find("football")!!
        val p = BubbleArt.place(f, 200f, 90f, 300f, centre = false)
        assertEquals(11f, p.padLeft - f.layout.edgeLeft, 1e-3f)
    }

    @Test fun nineSliceTilesTheBoxAndThePicture() {
        for (s in BubbleArt.all) for ((bw, bh) in listOf(s.width to s.height, s.width / 2 to s.height / 2, 337 to 151)) {
            val l = s.layout
            val d = 2.75f
            for ((w, h) in listOf(Math.round(l.minWidth * d) to Math.round(l.minHeight * d), 900 to 400, 1200 to 260)) {
                val p = BubbleArt.nineSlice(s, bw, bh, w, h, l.capTop * d, l.capRight * d, l.capBottom * d, l.capLeft * d)
                var srcArea = 0L
                var dstArea = 0L
                for (i in 0 until 9) {
                    val o = i * 8
                    if (p[o + 2] == 0) continue
                    assertTrue(s.id, p[o] >= 0 && p[o + 1] >= 0 && p[o + 2] <= bw && p[o + 3] <= bh)
                    assertTrue(s.id, p[o + 4] >= 0 && p[o + 5] >= 0 && p[o + 6] <= w && p[o + 7] <= h)
                    srcArea += (p[o + 2] - p[o]).toLong() * (p[o + 3] - p[o + 1])
                    dstArea += (p[o + 6] - p[o + 4]).toLong() * (p[o + 7] - p[o + 5])
                }
                // At the minimum size the middle has no room and is skipped; above it the whole picture is used.
                if (w > Math.round(l.minWidth * d) + 2 && h > Math.round(l.minHeight * d) + 2)
                    assertEquals("${s.id} $bw x $bh into $w x $h", bw.toLong() * bh, srcArea)
                assertEquals("${s.id} $bw x $bh into $w x $h", w.toLong() * h, dstArea)
            }
        }
    }

    @Test fun cornersKeepTheirSizeWhenTheBoxGrows() {
        val s = BubbleArt.find("skull")!!
        val l = s.layout
        val d = 3f
        val small = BubbleArt.nineSlice(s, 600, 293, 400, 220, l.capTop * d, l.capRight * d, l.capBottom * d, l.capLeft * d)
        val big = BubbleArt.nineSlice(s, 600, 293, 1000, 500, l.capTop * d, l.capRight * d, l.capBottom * d, l.capLeft * d)
        // Top-left corner: same destination size; bottom-right: same size, moved.
        for (i in 4..7) assertEquals(small[i], big[i])
        val br = 8 * 8
        assertEquals(small[br + 6] - small[br + 4], big[br + 6] - big[br + 4])
        assertEquals(small[br + 7] - small[br + 5], big[br + 7] - big[br + 5])
        assertEquals(1000, big[br + 6])
        assertEquals(500, big[br + 7])
    }

    @Test fun capsShrinkInsteadOfOverlappingInATinyBox() {
        val s = BubbleArt.find("sakura")!!
        val p = BubbleArt.nineSlice(s, s.width, s.height, 50, 30, 100f, 100f, 100f, 100f)
        for (i in 0 until 9) {
            val o = i * 8
            if (p[o + 2] == 0) continue
            assertTrue(p[o + 6] <= 50 && p[o + 7] <= 30 && p[o + 4] <= p[o + 6])
        }
    }

    @Test fun decodedAtScreenSizeAndSmallerOnALowEndPhone() {
        val s = BubbleArt.find("sakura")!!
        assertEquals(s.layout.scale * 2.75f, BubbleArt.decodeScale(s, 2.75f, lowEnd = false), 1e-5f)
        assertEquals(s.layout.scale * 2.75f * 0.75f, BubbleArt.decodeScale(s, 2.75f, lowEnd = true), 1e-5f)
        assertEquals(1f, BubbleArt.decodeScale(s, 10f, lowEnd = false), 0f)
        // About 0.75 MB or less per style at 3x (SPEC 7), 6 of them about 5 MB.
        val worst = BubbleArt.all.maxOf { a ->
            val k = BubbleArt.decodeScale(a, 3f, false)
            (a.width * k).toLong() * (a.height * k).toLong() * 4
        }
        assertTrue("worst $worst bytes", worst <= 900_000)
    }

    @Test fun everyStyledMessageShowsTheArt() {
        // Every message with a picture style draws its picture, wherever it sits in a run.
        assertEquals(BubbleArt.Look.ART, BubbleArt.look("camel", stylesOff = false))
        // Off in a group (bubbleStylesOff) or a channel: normal bubble.
        assertEquals(BubbleArt.Look.NONE, BubbleArt.look("camel", stylesOff = true))
        // Unknown or missing id (an older or newer build): normal bubble.
        assertEquals(BubbleArt.Look.NONE, BubbleArt.look("rainbow", false))
        assertEquals(BubbleArt.Look.NONE, BubbleArt.look(null, false))
        assertEquals(BubbleArt.Look.NONE, BubbleArt.look("", false))
    }

    @Test fun pictureWinsTheSharedIdAndThePickerListsEachOnce() {
        assertNotNull(BubbleArt.find("strawberry"))
        assertFalse(BubbleStyles.colours.any { it.id == "strawberry" })
        // The pink colour has its own id (iOS "pink") and is offered with the others.
        assertTrue(BubbleStyles.colours.any { it.id == "pink" })
        assertEquals(8, BubbleStyles.colours.size)
        assertEquals("Strawberry", BubbleStyles.name("strawberry"))
        assertEquals("Mint Cutie", BubbleStyles.name("mint"))
        assertEquals("Default", BubbleStyles.name(null))
        assertTrue(BubbleStyles.known("skull") && BubbleStyles.known("mint"))
        assertFalse(BubbleStyles.known("rainbow"))
        assertTrue(BubbleStyles.mayUse("skull") && BubbleStyles.mayUse("mint") && BubbleStyles.mayUse(null))
        assertFalse(BubbleStyles.mayUse("glass"))
        assertNull(BubbleArt.find("mint"))
    }

    /** The run's earlier bubbles are the flat fill with the style's text colour: it must read. */
    @Test fun textReadsOnEveryFill() {
        for (s in BubbleArt.all) {
            val ratio = contrast(BubbleArt.textColor(s), s.fill)
            assertTrue("${s.id} ratio $ratio", ratio >= 4.5)
        }
    }

    private fun contrast(a: Long, b: Long): Double {
        fun lum(c: Long): Double {
            fun ch(v: Long): Double { val x = v / 255.0; return if (x <= 0.03928) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4) }
            return 0.2126 * ch((c shr 16) and 0xFF) + 0.7152 * ch((c shr 8) and 0xFF) + 0.0722 * ch(c and 0xFF)
        }
        val (x, y) = lum(a) to lum(b)
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }

}
