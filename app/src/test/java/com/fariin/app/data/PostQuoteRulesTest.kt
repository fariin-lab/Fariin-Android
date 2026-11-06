package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PostQuoteRulesTest {
    @Test fun barWords() {
        assertEquals("1 Quote", PostQuoteRules.barLabel(1))
        assertEquals("2 Quotes", PostQuoteRules.barLabel(2))
        assertEquals("1.2K Quotes", PostQuoteRules.barLabel(1234))
    }

    @Test fun facesTakeThreeAndBlankOdd() {
        assertEquals(listOf("a", "", "c"), PostQuoteRules.faces(listOf("a", 5, "c", "d")))
        assertEquals(emptyList<String>(), PostQuoteRules.faces(null))
        assertEquals(emptyList<String>(), PostQuoteRules.faces("x"))
    }

    @Test fun parseAnswer() {
        val a = PostQuoteRules.parse(mapOf(
            "total" to 5, "hidden" to 2,
            "items" to listOf(
                mapOf("cid" to "c1", "mid" to "m1", "title" to "News", "views" to 1500, "verified" to true, "at" to 1000L),
                mapOf("cid" to "c1", "mid" to "m2", "views" to 10),
                mapOf("cid" to "", "mid" to "m3"),
                "junk",
            ),
        ))
        assertEquals(5, a.total)
        assertEquals(2, a.hidden)
        assertEquals(2, a.items.size)
        assertEquals("Channel", a.items[1].title)
        assertEquals(true, a.items[0].verified)
        assertEquals(1000L, a.items[0].at)
    }

    @Test fun totalNeverUnderItems() {
        val a = PostQuoteRules.parse(mapOf("items" to listOf(mapOf("cid" to "c", "mid" to "m"))))
        assertEquals(1, a.total)
        assertEquals(0, a.hidden)
        assertEquals(0, PostQuoteRules.parse(null).total)
    }

    @Test fun pagesAndTotals() {
        val first = PostQuoteRules.parse(mapOf("total" to 30, "hidden" to 5, "publicTotal" to 25, "next" to "9.q",
            "items" to listOf(mapOf("cid" to "c1", "mid" to "m1"), mapOf("cid" to "c2", "mid" to "m2"))))
        assertEquals(25, first.publicTotal); assertEquals("9.q", first.next)
        val more = PostQuoteRules.parse(mapOf("total" to 31, "hidden" to 5, "publicTotal" to 26, "next" to "",
            "items" to listOf(mapOf("cid" to "c2", "mid" to "m2"), mapOf("cid" to "c3", "mid" to "m3"))))
        val both = PostQuoteRules.appended(first, more)
        assertEquals(listOf("c1", "c2", "c3"), both.items.map { it.cid })
        assertEquals(31, both.total); assertEquals(26, both.publicTotal); assertEquals("", both.next)
        assertEquals(0, PostQuoteRules.parse(null).publicTotal)
    }

    @Test fun summaryAndCard() {
        java.util.Locale.setDefault(java.util.Locale.US)
        // The post's own numbers, never a sum over the page on screen.
        assertEquals("3 quotes · 2 in public channels", PostQuoteRules.summaryLine(PostQuotesAnswer(3, 1, emptyList(), publicTotal = 2)))
        assertEquals("1 quote", PostQuoteRules.summaryLine(PostQuotesAnswer(1, 1, emptyList())))
        assertEquals("2,000 quotes · 1,500 in public channels", PostQuoteRules.summaryLine(PostQuotesAnswer(2000, 500, emptyList(), publicTotal = 1500)))
        val one = PostQuotesAnswer(1, 0, listOf(QuotedIn("c", "m", views = 1)), publicTotal = 1)
        assertEquals("1 follower", PostQuoteRules.followersLine(1))
        assertEquals("0 followers", PostQuoteRules.followersLine(0))
        assertEquals("1.2K followers", PostQuoteRules.followersLine(1_250))
        assertEquals("12K followers", PostQuoteRules.followersLine(12_500))
        assertEquals(true, PostQuoteRules.isEmpty(PostQuotesAnswer(0, 0, emptyList())))
        assertEquals(false, PostQuoteRules.isEmpty(PostQuotesAnswer(2, 2, emptyList())))
        assertEquals(false, PostQuoteRules.isEmpty(one))
        assertEquals("Hello", PostQuoteRules.cardText("  Hello ", "text", false))
        assertEquals("Photo", PostQuoteRules.cardText(" ", "image", false))
        assertEquals("Album", PostQuoteRules.cardText(null, "album", true))
        assertEquals("Video", PostQuoteRules.cardText("", "video", false))
        assertEquals("Post", PostQuoteRules.cardText(null, null, false))
    }

    @Test fun lines() {
        assertEquals("1 view", PostQuoteRules.viewsLine(1))
        assertEquals("2.5M views", PostQuoteRules.viewsLine(2_500_000))
        assertNull(PostQuoteRules.hiddenLine(0))
        java.util.Locale.setDefault(java.util.Locale.US)
        assertEquals("4 in private chats and groups", PostQuoteRules.hiddenLine(4))
        assertEquals("1,204 in private chats and groups", PostQuoteRules.hiddenLine(1204))
        assertEquals("Photo", PostQuoteRules.kindName("image", false))
        assertEquals("Video", PostQuoteRules.kindName("video", false))
        assertEquals("Album", PostQuoteRules.kindName("image", true))
        assertEquals("Post", PostQuoteRules.kindName(null, false))
    }

    @Test fun routes() {
        val pub = QuotedIn("c2", "m", username = "news")
        assertEquals(PostQuoteRules.Route.SAME_CHAT, PostQuoteRules.route(pub, "c2", known = false))
        assertEquals(PostQuoteRules.Route.MY_CHANNEL, PostQuoteRules.route(pub, "c1", known = true))
        assertEquals(PostQuoteRules.Route.PUBLIC_PAGE, PostQuoteRules.route(pub, "c1", known = false))
        assertEquals(PostQuoteRules.Route.MY_CHANNEL, PostQuoteRules.route(QuotedIn("c2", "m"), "c1", known = false))
    }
}
