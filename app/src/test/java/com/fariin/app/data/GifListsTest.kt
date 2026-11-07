package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class GifListsTest {
    private fun g(n: Int) = StickerGifRules.Gif("u$n", "u$n", 1.0, 1.0)

    @Test fun recentNewestFirstNoTwiceCapped() {
        var l = (1..GifRecents.CAP).map(::g)
        l = GifLists.noted(l, g(5), GifRecents.CAP)
        assertEquals("u5", l.first().url)
        assertEquals(GifRecents.CAP, l.size)
        assertEquals(1, l.count { it.url == "u5" })
        l = GifLists.noted(l, g(999), GifRecents.CAP)
        assertEquals("u999", l.first().url)
        assertEquals(GifRecents.CAP, l.size)
    }

    @Test fun favoritesOldestFirstNoTwiceCapped() {
        var l = listOf(g(1), g(2))
        l = GifLists.added(l, g(1), GifFavorites.CAP)
        assertEquals(listOf("u1", "u2"), l.map { it.url })
        l = GifLists.added(l, g(3), GifFavorites.CAP)
        assertEquals("u3", l.last().url)
        val full = (1..GifFavorites.CAP).map(::g)
        assertEquals(full, GifLists.added(full, g(999), GifFavorites.CAP))
    }
}
