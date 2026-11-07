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

    /** Doctor, one line: the red cross (bottom right, 75 px in) is beside the words; the head is above the bubble. */
    @Test fun doctorOneLiner() {
        val s = BubbleArt.find("doctor")!!
        val k = 0.24f   // (188 - 111) + (77 - 1) = 153 px inside: 44 / 153 is over the 0.24 cap
        assertEquals(k, s.layout.scale, 1e-6f)
        val p = BubbleArt.place(s, 70f, 22f, 300f)
        // Right: 6 px outside the edge, then max((40 + 6) px, 75 px + 4 dp).
        assertEquals(6 * k + maxOf(46 * k, 75 * k + 4f), p.padRight, 1e-3f)
        // Left: nothing beside the words (ins 155 was for the head above): the plain 11 dp.
        assertEquals(11f, p.padLeft, 1e-3f)
        assertTrue(p.padLeft < 155 * k)
    }

    /** Security, one line: the peeker sits ABOVE the bubble, so no extra room on the right; the shield (bottom left) is beside the words. */
    @Test fun securityOneLiner() {
        val s = BubbleArt.find("security")!!
        val k = 0.24f   // (209 - 132) + (97 - 1) = 173 px: 44 / 173 > 0.24
        val p = BubbleArt.place(s, 70f, 22f, 300f)
        assertEquals(1 * k + 11f, p.padRight, 1e-3f)
        assertTrue("json ins 154 not applied", p.padRight < (154 + 6) * k)
        assertEquals(maxOf((79 + 6) * k, 78 * k + 4f), p.padLeft, 1e-3f)
        // Short line: centred with the larger clearance on both sides.
        val gapL = p.x - s.layout.edgeLeft
        val gapR = p.width - s.layout.edgeRight - (p.x + 70f)
        assertEquals(gapL, gapR, 0.01f)
    }

    @Test fun lawyerOneLiner() {
        val s = BubbleArt.find("lawyer")!!
        val k = 44f / ((192 - 110) + (115 - 1))
        assertEquals(k, s.layout.scale, 1e-6f)
        val p = BubbleArt.place(s, 70f, 22f, 300f)
        // Scales bottom left (rows 331..409, 90 px in) beside the words; bubble edge 26 px in from the box.
        assertEquals(26 * k + maxOf((87 + 6) * k, 90 * k + 4f), p.padLeft, 1e-3f)
        assertEquals(1 * k + 11f, p.padRight, 1e-3f)
    }

    @Test fun trophyOneLiner() {
        val s = BubbleArt.find("trophy")!!
        val k = 44f / ((146 - 69) + (108 - 1))
        val p = BubbleArt.place(s, 70f, 22f, 300f)
        // Cup base over the top left (rows 84..114, 89 px in), confetti bottom right (rows 233..287, 80 px in).
        assertEquals(5 * k + maxOf((85 + 6) * k, 89 * k + 4f), p.padLeft, 1e-3f)
        assertEquals(1 * k + maxOf((44 + 6) * k, 80 * k + 4f), p.padRight, 1e-3f)
    }

    @Test fun mangaOneLiner() {
        val s = BubbleArt.find("manga")!!
        val k = 44f / ((240 - 127) + (133 - 1))
        val p = BubbleArt.place(s, 70f, 22f, 300f)
        // Speed lines bottom right (rows 337..379, 94 px in); the head above the left side takes nothing.
        assertEquals(2 * k + maxOf((55 + 6) * k, 94 * k + 4f), p.padRight, 1e-3f)
        assertEquals(11f, p.padLeft, 1e-3f)
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
     * words stay 4 dp clear of it sideways (and past ins + 6 px); the words stay inside the bubble.
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
                    assertTrue("${s.id} right ins", p.x + w0 <= p.width - l.edgeRight - (s.ins[1] + BubbleArt.ART_GAP_PX) * k + 1e-3f)
                } else {
                    val artRight = l.edgeLeft + a.reach * k
                    assertTrue("${s.id} left art", p.x >= artRight + BubbleArt.ART_TEXT_DP - 1e-3f)
                    assertTrue("${s.id} left ins", p.x >= l.edgeLeft + (s.ins[3] + BubbleArt.ART_GAP_PX) * k - 1e-3f)
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
     * A short line ("test" + time + ticks, about 70 dp by 22 dp) sits in the middle between the
     * bubble's own edges, both ways, and outside both art clearances; the box is never under the slices.
     */
    @Test fun shortLineIsCentredBetweenTheBubbleEdgesAndClearOfTheArt() {
        for (s in BubbleArt.all) for ((cw, ch) in listOf(70f to 22f, 4f to 22f, 160f to 22f)) {
            val l = s.layout
            val p = BubbleArt.place(s, cw, ch, maxW = 300f)
            val edgeL = l.edgeLeft
            val edgeR = p.width - l.edgeRight
            val gapL = p.x - edgeL
            val gapR = edgeR - (p.x + cw)
            // Same rule as iOS: left and right within 1 unit (1 dp; iOS 1 pt).
            assertEquals("${s.id} $cw centred", gapL, gapR, 1f)
            assertEquals("${s.id} $cw centred exactly", gapL, gapR, 0.01f)
            assertTrue("${s.id} $cw left clearance", p.x >= p.padLeft - 1e-3f)
            assertTrue("${s.id} $cw right clearance", p.x + cw <= p.width - p.padRight + 1e-3f)
            assertTrue("${s.id} min width", p.width >= l.minWidth - 1e-3f)
            assertTrue("${s.id} not wider than needed", p.width <= maxOf(l.minWidth,
                cw + l.edgeLeft + l.edgeRight + 2 * maxOf(p.padLeft - l.edgeLeft, p.padRight - l.edgeRight)) + 1e-3f)
            val gapT = p.y - l.edgeTop
            val gapB = (p.height - l.edgeBottom) - (p.y + ch)
            assertEquals("${s.id} vertical", gapT, gapB, 0.01f)
            assertTrue("${s.id} top", p.y >= l.padTop - 1e-3f && p.y + ch <= p.height - l.padBottom + 1e-3f)
        }
    }

    /**
     * Long words (measured in the widest room, the box at its widest): inside the side clearances
     * used; where all the art is beside them, the old layout at the left clearance.
     */
    @Test fun longWordsKeepTheOldSnugLayout() {
        for (s in BubbleArt.all) {
            val l = s.layout
            val maxW = 300f
            val cw = maxW - l.padLeft - l.padRight
            val p = BubbleArt.place(s, cw, 66f, maxW)
            assertEquals(s.id, maxW, p.width, 1e-3f)
            assertTrue(s.id, p.x >= p.padLeft - 1e-3f && p.x + cw <= maxW - p.padRight + 1e-3f)
            if (kotlin.math.abs(p.padLeft - l.padLeft) < 1e-4f && kotlin.math.abs(p.padRight - l.padRight) < 1e-4f) assertEquals(s.id, l.padLeft, p.x, 1e-3f)
            assertEquals(s.id, l.padTop, p.y, 1e-3f)
            assertEquals(s.id, l.padTop + 66f + l.padBottom, p.height, 1e-3f)
        }
    }

    /** A whole block (words, time, a row under them, about 150 by 70 dp) is centred as one, both ways, inside the bubble. */
    @Test fun wholeBlockIsCentred() {
        for (s in BubbleArt.all) {
            val l = s.layout
            val p = BubbleArt.place(s, 150f, 70f, 300f)
            assertEquals(s.id, p.x - l.edgeLeft, p.width - l.edgeRight - (p.x + 150f), 0.01f)
            assertEquals(s.id, p.y - l.edgeTop, p.height - l.edgeBottom - (p.y + 70f), 0.01f)
            assertTrue(s.id, p.x >= p.padLeft - 1e-3f && p.x + 150f <= p.width - p.padRight + 1e-3f)
        }
    }

    /** Too wide to centre fully: the box takes the max width and the words go as near the middle as the clearances allow. */
    @Test fun nearlyFullLineCentresAsFarAsItCan() {
        for (s in BubbleArt.all) {
            val l = s.layout
            val maxW = 300f
            val room = maxW - l.padLeft - l.padRight
            val cw = room - 0.5f * kotlin.math.abs((l.padLeft - l.edgeLeft) - (l.padRight - l.edgeRight))
            val p = BubbleArt.place(s, cw, 22f, maxW)
            val gap = maxOf(p.padLeft - l.edgeLeft, p.padRight - l.edgeRight)
            assertTrue(s.id, p.width <= maxW + 1e-3f)
            assertTrue(s.id, p.x >= p.padLeft - 1e-3f && p.x + cw <= p.width - p.padRight + 1e-3f)
            val gapL = p.x - l.edgeLeft
            val gapR = p.width - l.edgeRight - (p.x + cw)
            // Less lopsided than the old snug layout (which was off by the clearances' difference).
            val oldDiff = kotlin.math.abs((p.padLeft - l.edgeLeft) - (p.padRight - l.edgeRight))
            assertTrue("${s.id} ${gapL - gapR} vs $oldDiff", kotlin.math.abs(gapL - gapR) <= oldDiff * 0.5f + 1e-3f)
            assertTrue(s.id, minOf(gapL, gapR) <= gap + 1e-3f)
        }
    }

    /** A link-card bubble keeps the old layout. */
    @Test fun linkCardBubbleUnchanged() {
        for (s in BubbleArt.all) {
            val l = s.layout
            val p = BubbleArt.place(s, 70f, 22f, 300f, centre = false)
            assertEquals(s.id, p.padLeft, p.x, 1e-3f)
            assertEquals(s.id, l.padTop, p.y, 1e-3f)
            assertEquals(s.id, maxOf(l.minWidth, 70f + p.padLeft + p.padRight), p.width, 1e-3f)
        }
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
