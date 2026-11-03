package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class StickerFavoritesTest {
    private val mine = StickerRef("mine", "A1", "😀", false)
    private val built = StickerRef("kulu", "hi", "👋", true)

    @Test fun jsonIsTheIosFormatBothWays() {
        val json = StickerFavorites.toJson(listOf(mine, built))
        assertEquals(listOf(mine, built), StickerFavorites.fromJson(json))
        // iOS writes {source, packId, stickerId, emoji, animated}; "source" names mine vs bundled.
        val ios = """[{"source":"bundled","packId":"kulu","stickerId":"hi","emoji":"👋","animated":true}]"""
        assertEquals(listOf(built), StickerFavorites.fromJson(ios))
        assert(json.contains("\"source\":\"mine\"") && json.contains("\"source\":\"bundled\""))
    }

    @Test fun newestFirstNoTwice() {
        val l = StickerFavorites.withAdded(listOf(built, mine), mine)
        assertEquals(listOf(mine, built), l)
    }
}
