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

    @Test fun numbersRow() {
        val a = PostQuotesAnswer(3, 1, listOf(QuotedIn("c1", "m1", views = 1200), QuotedIn("c1", "m2", views = 100), QuotedIn("c2", "m3", views = 0)))
        val s = PostQuoteRules.stats(a)
        assertEquals(PostQuoteRules.Stat("3", "quotes"), s[0])
        assertEquals(PostQuoteRules.Stat("2", "channels"), s[1])
        assertEquals(PostQuoteRules.Stat("1.3K", "views"), s[2])
        val one = PostQuoteRules.stats(PostQuotesAnswer(1, 0, listOf(QuotedIn("c", "m", views = 1))))
        assertEquals(listOf("quote", "channel", "view"), one.map { it.label })
    }

    @Test fun summaryAndCard() {
        val a = PostQuotesAnswer(3, 1, listOf(QuotedIn("c1", "m1", views = 1200), QuotedIn("c1", "m2", views = 100), QuotedIn("c2", "m3")))
        assertEquals("3 quotes · 2 channels · 1.3K views", PostQuoteRules.summaryLine(a))
        val one = PostQuotesAnswer(1, 0, listOf(QuotedIn("c", "m", views = 1)))
        assertEquals("1 quote · 1 channel · 1 view", PostQuoteRules.summaryLine(one))
        assertEquals("2K quotes · 0 channels · 0 views", PostQuoteRules.summaryLine(PostQuotesAnswer(2000, 2000, emptyList())))
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
        assertEquals("4 more in private chats and groups", PostQuoteRules.hiddenLine(4))
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
