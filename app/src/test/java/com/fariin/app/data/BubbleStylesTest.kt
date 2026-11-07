package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleStylesTest {
    @Test fun eightStylesFourFreeFourPlus() {
        assertEquals(listOf("pink", "lavender", "peach", "mint", "cotton", "heart", "kawaii", "glass"),
            BubbleStyles.all.map { it.id })
        assertEquals(listOf("cotton", "heart", "kawaii", "glass"), BubbleStyles.all.filter { it.plus }.map { it.id })
    }

    @Test fun unknownOrMissingIsTheNormalBubble() {
        assertNull(BubbleStyles.find(null))
        assertNull(BubbleStyles.find(""))
        assertNull(BubbleStyles.find("rainbow"))
        assertEquals("Mint Cutie", BubbleStyles.find("mint")?.name)
    }

    @Test fun idOnTheWire() {
        assertEquals("peach", BubbleStyles.parseId("peach"))
        assertNull(BubbleStyles.parseId(""))
        assertNull(BubbleStyles.parseId(42))
        assertNull(BubbleStyles.parseId("x".repeat(33)))
        assertEquals("x".repeat(32), BubbleStyles.parseId("x".repeat(32)))
    }

    @Test fun iosColoursAndGradients() {
        val lav = BubbleStyles.find("lavender")!!
        assertEquals(listOf(0xE6D8FFL, 0xCDB6FFL), lav.look(false).fill)
        assertEquals(0xF1E9FFL, lav.look(true).text)
        assertEquals(listOf(0x7A3B66L, 0x2F5D86L), BubbleStyles.find("cotton")!!.dark.fill)
        assertTrue(BubbleStyles.find("glass")!!.light.glass)
        assertFalse(BubbleStyles.find("heart")!!.light.glass)
    }

    /** Words must stay readable: at least 4.5:1 contrast against every fill colour, light and dark. */
    @Test fun textIsReadableOnEveryFill() {
        for (s in BubbleStyles.all) for (look in listOf(s.light, s.dark)) for (f in look.fill) {
            val ratio = contrast(look.text, f)
            assertTrue("${s.id} ${"%06X".format(f)} ratio $ratio", ratio >= 4.5)
        }
    }

    @Test fun decorOnlyWhenChosenOnTheLastBubbleAndNotFailed() {
        assertEquals(0, BubbleStyles.placed(null, true, true, false, true, true, false, false).size)
        assertEquals(0, BubbleStyles.placed("heart", false, true, false, true, true, false, false).size)
        assertEquals(0, BubbleStyles.placed("heart", true, true, false, true, false, false, false).size)
        assertEquals(0, BubbleStyles.placed("heart", true, true, false, true, true, true, false).size)
        assertEquals(0, BubbleStyles.placed("rainbow", true, true, false, true, true, false, false).size)
        assertEquals(2, BubbleStyles.placed("heart", true, true, false, true, true, false, false).size)
        assertTrue(BubbleStyles.parseDecor(true))
        assertFalse(BubbleStyles.parseDecor("true"))
        assertFalse(BubbleStyles.parseDecor(null))
    }

    @Test fun topCornersOnlyOnTheRunsFirstBubble() {
        val mid = BubbleStyles.placed("pink", true, true, false, false, true, false, false)
        assertEquals(listOf(BubbleStyles.Glyph.STRAWBERRY), mid.map { it.glyph })
    }

    @Test fun theirsIsAMirrorOfMine() {
        val mine = BubbleStyles.placed("kawaii", true, true, false, true, true, false, false)
        val theirs = BubbleStyles.placed("kawaii", true, false, false, true, true, false, false)
        assertEquals(listOf(BubbleStyles.Corner.TOP_TRAILING, BubbleStyles.Corner.BOTTOM_LEADING, BubbleStyles.Corner.BOTTOM_TRAILING),
            mine.map { it.corner })
        assertEquals(listOf(BubbleStyles.Corner.TOP_LEADING, BubbleStyles.Corner.BOTTOM_TRAILING, BubbleStyles.Corner.BOTTOM_LEADING),
            theirs.map { it.corner })
        assertEquals(-mine[0].rotation, theirs[0].rotation, 0f)
    }

    @Test fun nothingBesideTheGroupAvatar() {
        // Theirs in a group: mine's bottom-trailing strawberry mirrors to bottom-leading, by the face.
        val g = BubbleStyles.placed("pink", true, false, false, true, true, false, true)
        assertEquals(listOf(BubbleStyles.Corner.TOP_TRAILING), g.map { it.corner })
    }

    @Test fun darkColoursAndCornerPoints() {
        val d = BubbleStyles.placed("glass", true, true, true, true, true, false, false).single()
        assertEquals(0xD1D1D6L, d.color)
        assertEquals(200f to 54f, BubbleStyles.centre(d, 200f, 52f))
        val bow = BubbleStyles.placed("kawaii", true, true, false, true, true, false, false).first()
        assertEquals(99f to -1f, BubbleStyles.centre(bow, 100f, 40f))
    }

    private fun lum(c: Long): Double {
        fun ch(v: Long): Double { val x = v / 255.0; return if (x <= 0.03928) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4) }
        return 0.2126 * ch(c shr 16 and 0xFF) + 0.7152 * ch(c shr 8 and 0xFF) + 0.0722 * ch(c and 0xFF)
    }
    private fun contrast(a: Long, b: Long): Double {
        val (x, y) = lum(a) to lum(b)
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }
}
