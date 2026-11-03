package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatLookTest {
    private fun reader(vararg kv: Pair<String, String>): (String) -> String? { val m = mapOf(*kv); return { m[it] } }

    @Test fun storedStringsMatchIos() {
        assertEquals("none", ChatWallpaper.None.stored)
        assertEquals("g:ocean", ChatWallpaper.Gradient("ocean").stored)
        assertEquals("c:0A0A0A", ChatWallpaper.Solid(0x0A0A0A).stored)
        assertEquals(ChatWallpaper.Photo("x1"), ChatWallpaper.parse("p:x1"))
        assertEquals(ChatWallpaper.Solid(0xE9E9EE), ChatWallpaper.parse("c:E9E9EE"))
        assertEquals(ChatWallpaper.None, ChatWallpaper.parse("photo"))
        assertEquals("g:0552F0,2C6BED", ChatColors.presets[0].stored)
        assertEquals(ChatColorSpec(listOf(0xCF163E)), ChatColorSpec.parse("s:CF163E"))
    }

    @Test fun legacyPresetResolvesToTheme() {
        assertEquals("sunset", ChatWallpaper.Preset("wp-paper").theme?.id)
        assertEquals("mono", ChatWallpaper.Preset("wp-graphite").theme?.id)
    }

    @Test fun chatPickBeatsSettingsAndOfficialHasNone() {
        val r = reader("wallpaper.__default__" to "g:ocean", "wallpaper.a" to "none")
        assertEquals(ChatWallpaper.None, ChatLookRules.wallpaper("a", r))
        assertEquals(ChatWallpaper.Gradient("ocean"), ChatLookRules.wallpaper("b", r))
        assertEquals(ChatWallpaper.None, ChatLookRules.wallpaper(OfficialChannel.CID, r))
    }

    @Test fun autoColourFollowsIosOrder() {
        // Settings wallpaper Blaze, nothing chosen: Auto = Ember.
        val r1 = reader("wallpaper.__default__" to "g:g-blaze")
        assertEquals(ChatColorSpec(listOf(0xE57C00, 0x5E0000)), ChatLookRules.color("x", r1))
        // A colour chosen in Settings beats the Settings wallpaper's pairing.
        val r2 = reader("wallpaper.__default__" to "g:g-blaze", "chatColor.__default__" to "s:CF163E")
        assertEquals(ChatColorSpec(listOf(0xCF163E)), ChatLookRules.color("x", r2))
        // The chat's own patterned theme pairs with its bubbleHex.
        val r3 = reader("wallpaper.y" to "g:ocean", "chatColor.__default__" to "s:CF163E")
        assertEquals(ChatColorSpec(listOf(0x2E8BF0)), ChatLookRules.color("y", r3))
        // The chat's own photo stops at the Settings colour, never the Settings wallpaper's pairing.
        val r4 = reader("wallpaper.__default__" to "g:g-blaze", "wallpaper.z" to "p:1")
        assertNull(ChatLookRules.color("z", r4))
        // The chat's own colour always wins; the old "__none__" marker reads as Auto.
        val r5 = reader("chatColor.q" to "s:3B7845", "chatColor.w" to "__none__")
        assertEquals(ChatColorSpec(listOf(0x3B7845)), ChatLookRules.color("q", r5))
        assertNull(ChatLookRules.color("w", r5))
    }

    @Test fun meshAndMix() {
        val g = ChatWallpapers.gradient("g-aqua")!!
        val m = g.meshColors(false)
        assertEquals(9, m.size)
        assertEquals(0x0093E9L, m[0])
        assertEquals(mix(0x0093E9, 0x80D0C7), m[3])
        assertEquals(15, ChatWallpapers.all.size)
        assertEquals(22, ChatColors.presets.size)
    }

    @Test fun hsbAndAngles() {
        assertEquals(0xDB0000L, ChatColorSpec.hsb(0.0, 1.0))
        assertEquals(168.0, ChatColors.presets[13].angleDegrees, 0.0)
        assertEquals(192.0, ChatColors.presets[15].angleDegrees, 0.0)
        assertTrue(ChatWallpapers.isDark(0x0A0A0A))
        assertEquals(mix(0xE57C00, 0x5E0000), ChatLookRules.flatHex(ChatColors.presets[13]))
    }
}
