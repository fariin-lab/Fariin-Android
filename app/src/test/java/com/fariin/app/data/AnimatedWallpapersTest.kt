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
        assertEquals(ChatWallpaper.None, ChatWallpaper.parse("a:stars"))
        assertEquals(listOf("liquid-metal", "water", "liquid-glow", "liquid-edge", "aurora-green", "aurora-purple", "aurora-pink"),
            AnimatedWallpapers.all.map { it.id })
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
        assertTrue(AnimatedWallpapers.moves(setting = true, powerAnimations = true, systemAnimations = true))
        assertFalse(AnimatedWallpapers.moves(setting = false, powerAnimations = true, systemAnimations = true))
        assertFalse(AnimatedWallpapers.moves(setting = true, powerAnimations = false, systemAnimations = true))
        assertFalse(AnimatedWallpapers.moves(setting = true, powerAnimations = true, systemAnimations = false))
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
