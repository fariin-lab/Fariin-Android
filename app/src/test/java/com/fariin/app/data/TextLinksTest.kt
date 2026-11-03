package com.fariin.app.data

import com.fariin.app.data.TextLinks.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextLinksTest {
    private fun runs(t: String) = TextLinks.find(t).map { Triple(t.substring(it.start, it.end), it.kind, it.target) }

    @Test fun webLinks() {
        assertEquals(listOf(Triple("https://youtu.be/abc", Kind.WEB, "https://youtu.be/abc")), runs("look https://youtu.be/abc"))
        assertEquals(listOf(Triple("www.bbc.com/news", Kind.WEB, "https://www.bbc.com/news")), runs("www.bbc.com/news"))
        assertEquals(listOf(Triple("fariin.com/u/kream", Kind.WEB, "https://fariin.com/u/kream")), runs("add me fariin.com/u/kream"))
        assertEquals(listOf(Triple("HTTP://Example.com", Kind.WEB, "HTTP://Example.com")), runs("HTTP://Example.com"))
    }

    @Test fun trailingPunctuationIsNotPartOfALink() {
        assertEquals("https://a.com/x", runs("see https://a.com/x.")[0].first)
        assertEquals("https://a.com/x", runs("(see https://a.com/x)")[0].first)
        assertEquals("https://en.wikipedia.org/wiki/Foo_(bar)", runs("https://en.wikipedia.org/wiki/Foo_(bar),")[0].first)
        assertEquals("https://a.com", runs("is it https://a.com?!")[0].first)
    }

    @Test fun notALink() {
        assertTrue(runs("https:// and www. alone").isEmpty())
        assertTrue(runs("xhttps://a.com").isEmpty())   // starts inside a word
        assertTrue(runs("plain words only").isEmpty())
    }

    @Test fun usernames() {
        assertEquals(listOf(Triple("@kream", Kind.HANDLE, "kream")), runs("ask @kream."))
        assertEquals(listOf(Triple("@a_b1", Kind.HANDLE, "a_b1")), runs("@a_b1"))
        assertTrue(runs("@ab is too short").isEmpty())
        assertTrue(runs("me@kream inside a word").isEmpty())
        assertTrue(runs("@" + "a".repeat(25) + " too long").isEmpty())
    }

    @Test fun emails() {
        assertEquals(listOf(Triple("amina@mail.so", Kind.EMAIL, "amina@mail.so")), runs("write amina@mail.so."))
        assertEquals(listOf(Triple("a.b+c@x.co.uk", Kind.EMAIL, "a.b+c@x.co.uk")), runs("(a.b+c@x.co.uk)"))
        // The domain is not also a username.
        assertEquals(1, runs("amina@mail.so").size)
    }

    @Test fun phonesAreNotLinkedAsOnIos() {
        assertTrue(runs("call +252 61 234 5678.").isEmpty())
        assertTrue(runs("0612345678").isEmpty())
        assertTrue(runs("on 2026-11-03 at 12:30").isEmpty())   // a date and a time
        assertTrue(runs("10 20 30 40 50").isEmpty())           // a list of numbers
        assertTrue(runs("code 123456").isEmpty())              // too short without +
        assertTrue(runs("abc0612345678").isEmpty())            // inside a word
        assertTrue(runs("v1.2.3.4567890").isEmpty())
    }

    @Test fun mixedRunsDoNotOverlap() {
        val r = runs("https://fariin.com/@kream 0612345678 @amina x@y.com")
        assertEquals(listOf(Kind.WEB, Kind.HANDLE, Kind.EMAIL), r.map { it.second })
        assertEquals("https://fariin.com/@kream", r[0].first)
    }

    @Test fun phoneInsideALinkStaysTheLink() {
        val r = runs("https://a.com/0612345678")
        assertEquals(1, r.size)
        assertEquals(Kind.WEB, r[0].second)
    }
}
