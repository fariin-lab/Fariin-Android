package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimatedWallpapersTest {
    @Test fun storedAndParsedAsIos() {
        assertEquals("a:liquid-metal", ChatWallpaper.Animated("liquid-metal").stored)
        for (a in AnimatedWallpapers.all) assertEquals(ChatWallpaper.Animated(a.id), ChatWallpaper.parse("a:${a.id}"))
        // An id this build does not know reads as none.
        assertEquals(ChatWallpaper.None, ChatWallpaper.parse("a:comets"))
        assertEquals(listOf("liquid-metal", "water", "liquid-glow", "liquid-edge", "aurora-green", "aurora-purple", "aurora-pink",
            "stars", "snow", "rain", "glow-dots", "doodles-1", "doodles-2", "doodles-3"),
            AnimatedWallpapers.all.map { it.id })
        assertEquals(listOf("Stars", "Snow", "Rain", "Glowing Dots", "Doodles", "Doodles 2", "Doodles 3"),
            AnimatedWallpapers.all.drop(7).map { it.name })
    }

    @Test fun loopLengthsAsIos() {
        fun p(id: String) = AnimatedWallpapers.find(id)!!.period
        assertEquals(60f, p("stars"), 0f)
        assertEquals(160f, p("snow"), 0f)
        assertEquals(20f, p("rain"), 0f)
        assertEquals(480f, p("glow-dots"), 0f)
        assertEquals(40f, p("doodles-2"), 0f)
    }

    @Test fun topIsDarkPerLook() {
        val stars = AnimatedWallpapers.find("stars")!!
        assertTrue(stars.topIsDark(false))
        assertTrue(stars.topIsDark(true))
        for (id in listOf("snow", "rain", "glow-dots", "doodles-1", "doodles-3", "water")) {
            val a = AnimatedWallpapers.find(id)!!
            assertFalse(id, a.topIsDark(false))
            assertTrue(id, a.topIsDark(true))
        }
        assertTrue(AnimatedWallpapers.find("aurora-green")!!.topIsDark(false))
    }

    @Test fun doodlesDrawTheFirstThreePatternedThemes() {
        val patterned = ChatWallpapers.all.filter { it.pattern }
        assertEquals(patterned[0], AnimatedWallpapers.find("doodles-1")!!.doodleTheme)
        assertEquals(patterned[1], AnimatedWallpapers.find("doodles-2")!!.doodleTheme)
        assertEquals(patterned[2], AnimatedWallpapers.find("doodles-3")!!.doodleTheme)
        assertNull(AnimatedWallpapers.find("stars")!!.doodleTheme)
    }

    @Test fun particlesLoopWithNoSeam() {
        assertEquals(24, AnimatedWallpapers.particleRows(0))
        assertEquals(48, AnimatedWallpapers.particleRows(1))
        assertEquals(16, AnimatedWallpapers.particleRows(2))
        // A whole loop moves every layer by whole repeats of its rows, so every cell reads the same row.
        for (kind in 0..2) for (layer in 0..2) {
            val rows = AnimatedWallpapers.particleRows(kind)
            val fall = AnimatedWallpapers.particleFall(kind, layer)
            assertEquals(0, fall % rows)
            for (row in -3..30) {
                val r = row.toFloat()
                assertEquals(AnimatedWallpapers.wrapRow(r, rows), AnimatedWallpapers.wrapRow(r - fall, rows), 0f)
                assertTrue(AnimatedWallpapers.wrapRow(r, rows) in 0f..(rows - 1).toFloat())
            }
        }
    }

    @Test fun intensityLayers() {
        assertEquals(1, AnimatedWallpapers.particleLayers(0))
        assertEquals(2, AnimatedWallpapers.particleLayers(1))
        assertEquals(3, AnimatedWallpapers.particleLayers(2))
        assertEquals(3, AnimatedWallpapers.particleLayers(9))
        assertEquals(1, AnimatedWallpapers.particleLayers(-1))
        assertEquals("animatedWallpapers.intensity", AnimatedWallpapers.INTENSITY_KEY)
    }

    @Test fun doodleBandRunsFullyOffBothSides() {
        assertEquals(-1.4f, AnimatedWallpapers.doodleBandX(0f), 1e-6f)
        assertEquals(1.6f, AnimatedWallpapers.doodleBandX(1f), 1e-6f)
        // Its right edge (0.8 wide) is off the left at the start; its left edge off the right at the end.
        assertTrue(AnimatedWallpapers.doodleBandX(0f) + 0.8f < 0f)
        assertTrue(AnimatedWallpapers.doodleBandX(1f) > 1f)
    }

    @Test fun resolutionAndRatePerLook() {
        val stars = AnimatedWallpapers.find("stars")!!
        val water = AnimatedWallpapers.find("water")!!
        val doodles = AnimatedWallpapers.find("doodles-1")!!
        assertEquals(1f, stars.scale(false), 0f)
        assertEquals(2f, stars.scale(true), 0f)
        assertEquals(2f, water.scale(false), 0f)   // soft looks at half
        assertEquals(1f, doodles.scale(false), 0f)
        assertEquals(15, water.fps(false))
        assertEquals(12, water.fps(true))
        assertEquals(12, doodles.fps(false))
        assertEquals(24, AnimatedWallpapers.find("rain")!!.fps(false))
        assertEquals(15, AnimatedWallpapers.find("snow")!!.fps(false))
        assertEquals(2f, AnimatedWallpapers.find("snow")!!.scale(false), 0f)
    }

    @Test fun clockCountsOnlyWhileMoving() {
        // The first frame after a start takes no step: a pause never makes it jump on.
        assertEquals(0f, AnimatedWallpapers.step(0L, 5_000_000_000L), 0f)
        assertEquals(0.033f, AnimatedWallpapers.step(1_000_000_000L, 1_033_000_000L), 1e-5f)
        // A stalled frame moves on a little at most.
        assertEquals(AnimatedWallpapers.MAX_STEP, AnimatedWallpapers.step(1_000_000_000L, 9_000_000_000L), 0f)
        assertEquals(0f, AnimatedWallpapers.step(2L, 1L), 0f)
    }

    @Test fun animatedHasNoPairedColour() {
        assertNull(ChatWallpaper.Animated("water").pairedColor)
        assertNull(ChatWallpaper.Animated("water").theme)
    }

    @Test fun groupValueDrawsAnimated() {
        val w = ChatLookRules.groupWallpaper("a:aurora-pink", { false })
        assertEquals(ChatWallpaper.Animated("aurora-pink"), w)
        assertEquals(ChatWallpaper.Animated("aurora-pink"), ChatLookRules.wallpaper("grp", { null }, w))
    }

    @Test fun movesOnlyWhenEverythingAllows() {
        // Only the phone's "Remove animations" stills a moving wallpaper (no app switch, not Battery Saver).
        assertTrue(AnimatedWallpapers.moves(systemAnimations = true))
        assertFalse(AnimatedWallpapers.moves(systemAnimations = false))
    }

    @Test fun stillFrameLooksRunNoClockWithoutShaders() {
        // Below Android 13 a shader look is one still picture: no frame loop for it. Doodles still moves.
        assertFalse(AnimatedWallpapers.hasMotion(AnimatedLook.WATER, shaders = false))
        assertFalse(AnimatedWallpapers.hasMotion(AnimatedLook.STARS, shaders = false))
        assertTrue(AnimatedWallpapers.hasMotion(AnimatedLook.DOODLES, shaders = false))
        assertTrue(AnimatedWallpapers.hasMotion(AnimatedLook.WATER, shaders = true))
        assertTrue(AnimatedWallpapers.hasMotion(AnimatedLook.DOODLES, shaders = true))
    }

    @Test fun phaseGoesRoundOncePerLoop() {
        val twoPi = (2 * Math.PI).toFloat()
        assertEquals(twoPi / 4, AnimatedWallpapers.advance(0f, 15f, 60f), 1e-4f)
        assertEquals(0f, AnimatedWallpapers.advance(0f, 60f, 60f), 1e-3f)
        assertEquals(1f, AnimatedWallpapers.advance(1f, 0f, 60f), 0f)
        assertTrue(AnimatedWallpapers.advance(6f, 30f, 60f) in 0f..twoPi)
    }

    @Test fun framesCappedToTheRate() {
        assertTrue(AnimatedWallpapers.frameDue(0L, 5L, 30))
        // 60 Hz vsync: every second frame at 30.
        assertFalse(AnimatedWallpapers.frameDue(1_000_000_000L, 1_016_666_667L, 30))
        assertTrue(AnimatedWallpapers.frameDue(1_000_000_000L, 1_033_333_333L, 30))
    }
}
