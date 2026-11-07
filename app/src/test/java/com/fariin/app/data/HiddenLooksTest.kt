package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** config/features wallpapersHidden / bubblesHidden: the parse and the picker filter. */
class HiddenLooksTest {
    @Test fun parseKeepsStringsOnly() {
        assertEquals(setOf("g-blaze", "pink"), HiddenLooks.parse(listOf("g-blaze", 3, "", null, "pink", "pink")))
        assertEquals(emptySet<String>(), HiddenLooks.parse(null))
        assertEquals(emptySet<String>(), HiddenLooks.parse("g-blaze"))
        assertEquals(emptySet<String>(), HiddenLooks.parse(listOf("x".repeat(65))))
    }

    @Test fun visibleDropsHiddenKeepsOrderIgnoresUnknown() {
        val ids = ChatWallpapers.all.map { it.id }
        val out = HiddenLooks.visible(ChatWallpapers.all, setOf("g-blaze", "no-such-id")) { it.id }
        assertEquals(ids - "g-blaze", out.map { it.id })
        assertEquals(ids, HiddenLooks.visible(ChatWallpapers.all, emptySet()) { it.id }.map { it.id })
        assertEquals(ids, HiddenLooks.visible(ChatWallpapers.all, setOf("unknown")) { it.id }.map { it.id })
    }

    @Test fun pictureStylesAndAnimated() {
        val pics = HiddenLooks.visible(BubbleArt.all, setOf("sakura")) { it.id }
        assertEquals(BubbleArt.all.size - 1, pics.size)
        val anim = HiddenLooks.visible(AnimatedWallpapers.all, setOf("water")) { it.id }
        assertEquals(AnimatedWallpapers.all.map { it.id } - "water", anim.map { it.id })
    }
}
