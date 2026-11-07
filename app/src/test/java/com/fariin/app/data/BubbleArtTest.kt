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

    @Test fun spec6FixesGaveTheFourStylesMoreClearance() {
        // bubbles/*.json: doctor [0,0,0,155] (its cross at the bottom right reaches 73 px in: right 85), lawyer [0,120,0,87], security [0,154,0,79], manga [0,12,0,149].
        assertEquals(listOf(0, 79, 0, 155), BubbleArt.find("doctor")!!.ins.toList())
        // As iOS: the words clear 161 px on the left and 85 on the cross side.
        val doc = BubbleArt.find("doctor")!!
        val k = doc.layout.scale
        assertEquals(161 * k, doc.layout.padLeft, 0.001f)
        assertEquals((doc.width - doc.bubble[1]) * k + 85 * k, doc.layout.padRight, 0.001f)
        assertEquals(listOf(0, 132, 0, 99), BubbleArt.find("lawyer")!!.ins.toList())
        assertEquals(listOf(0, 166, 0, 91), BubbleArt.find("security")!!.ins.toList())
        assertEquals(listOf(0, 24, 0, 161), BubbleArt.find("manga")!!.ins.toList())
        assertEquals(listOf(0, 104, 0, 177), BubbleArt.find("sakura")!!.ins.toList())
    }

    @Test fun scaleFollowsSpec6() {
        val sakura = BubbleArt.find("sakura")!!.layout
        // inside = (189 - 108) + (158 - (370 - 350)) = 219 px; 44 / 219 = 0.2009 < 0.24.
        assertEquals(44f / 219f, sakura.scale, 1e-5f)
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
            assertTrue(s.id, l.padLeft >= l.edgeLeft + BubbleArt.SIDE_PX * k - 1e-3f)
            assertTrue(s.id, l.padRight >= l.edgeRight + BubbleArt.SIDE_PX * k - 1e-3f)
            // ...and clear of corner art reaching in (ins, from the bubble's edge).
            assertTrue(s.id, l.padLeft >= (s.bubble[3] + s.ins[3]) * k)
            assertTrue(s.id, l.padRight >= (s.width - s.bubble[1] + s.ins[1]) * k)
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
            assertTrue("${s.id} $cw left clearance", p.x >= l.padLeft - 1e-3f)
            assertTrue("${s.id} $cw right clearance", p.x + cw <= p.width - l.padRight + 1e-3f)
            assertTrue("${s.id} min width", p.width >= l.minWidth - 1e-3f)
            assertTrue("${s.id} not wider than needed", p.width <= maxOf(l.minWidth,
                cw + l.edgeLeft + l.edgeRight + 2 * maxOf(l.padLeft - l.edgeLeft, l.padRight - l.edgeRight)) + 1e-3f)
            val gapT = p.y - l.edgeTop
            val gapB = (p.height - l.edgeBottom) - (p.y + ch)
            assertEquals("${s.id} vertical", gapT, gapB, 0.01f)
            assertTrue("${s.id} top", p.y >= l.padTop - 1e-3f && p.y + ch <= p.height - l.padBottom + 1e-3f)
        }
    }

    /** Long words (the box at its widest) keep the old layout: at the left clearance, the box ending at the right one. */
    @Test fun longWordsKeepTheOldSnugLayout() {
        for (s in BubbleArt.all) {
            val l = s.layout
            val maxW = 300f
            val cw = maxW - l.padLeft - l.padRight
            val p = BubbleArt.place(s, cw, 66f, maxW)
            assertEquals(s.id, maxW, p.width, 1e-3f)
            assertEquals(s.id, l.padLeft, p.x, 1e-3f)
            assertEquals(s.id, l.padTop, p.y, 1e-3f)
            assertEquals(s.id, l.padTop + 66f + l.padBottom, p.height, 1e-3f)
        }
    }

    /** Too wide to centre fully: the box takes the max width and the words go as near the middle as the clearances allow. */
    @Test fun nearlyFullLineCentresAsFarAsItCan() {
        for (s in BubbleArt.all) {
            val l = s.layout
            val maxW = 300f
            val gap = maxOf(l.padLeft - l.edgeLeft, l.padRight - l.edgeRight)
            val room = maxW - l.padLeft - l.padRight
            val cw = room - 0.5f * kotlin.math.abs((l.padLeft - l.edgeLeft) - (l.padRight - l.edgeRight))
            val p = BubbleArt.place(s, cw, 22f, maxW)
            assertEquals(s.id, maxW, p.width, 1e-3f)
            assertTrue(s.id, p.x >= l.padLeft - 1e-3f && p.x + cw <= maxW - l.padRight + 1e-3f)
            val gapL = p.x - l.edgeLeft
            val gapR = maxW - l.edgeRight - (p.x + cw)
            // Less lopsided than the old snug layout (which was off by the clearances' difference).
            val oldDiff = kotlin.math.abs((l.padLeft - l.edgeLeft) - (l.padRight - l.edgeRight))
            assertTrue("${s.id} ${gapL - gapR} vs $oldDiff", kotlin.math.abs(gapL - gapR) <= oldDiff * 0.5f + 1e-3f)
            assertTrue(s.id, minOf(gapL, gapR) <= gap + 1e-3f)
        }
    }

    /** A link-card bubble keeps the old layout. */
    @Test fun linkCardBubbleUnchanged() {
        for (s in BubbleArt.all) {
            val l = s.layout
            val p = BubbleArt.place(s, 70f, 22f, 300f, centre = false)
            assertEquals(s.id, l.padLeft, p.x, 1e-3f)
            assertEquals(s.id, l.padTop, p.y, 1e-3f)
            assertEquals(s.id, maxOf(l.minWidth, 70f + l.padLeft + l.padRight), p.width, 1e-3f)
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
