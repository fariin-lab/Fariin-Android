package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** Tag Search detection (iOS 2026-11-20 rules). */
class TagSearchTest {
    private fun tags(s: String) = TagSearch.find(s).map { it.target }

    @Test fun hashtags() {
        assertEquals(listOf("#fariin", "#somalia_2026"), tags("Hi #Fariin and #somalia_2026!"))
        assertEquals(emptyList<String>(), tags("#123 and a#b and ##x"))
        assertEquals(listOf("#_"), tags("odd #_ one"))
        assertEquals(listOf("#muqdisho"), tags("magaalada #Muqdisho."))
    }

    @Test fun cashtags() {
        assertEquals(listOf("\$btc", "\$eth"), tags("Buy \$BTC and \$eth today"))
        assertEquals(emptyList<String>(), tags("costs \$100, \$abcdefghi, a\$b, \$btc2"))
    }

    @Test fun ranges() {
        val s = "see #news"
        val sp = TagSearch.find(s).single()
        assertEquals("#news", s.substring(sp.start, sp.end))
    }
}
