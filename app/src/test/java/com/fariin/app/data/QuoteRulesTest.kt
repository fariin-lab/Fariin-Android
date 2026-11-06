package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuoteRulesTest {
    @Test fun qidShape() {
        assertTrue(QuoteRules.isQid("abc123def456gh78"))
        assertFalse(QuoteRules.isQid("ABC123def456gh78"))
        assertFalse(QuoteRules.isQid("abc123"))
        assertFalse(QuoteRules.isQid("abc123def456gh78x"))
        assertFalse(QuoteRules.isQid("abc123def456gh7-"))
        assertFalse(QuoteRules.isQid(null))
    }

    @Test fun parseStates() {
        assertEquals(QuoteCard.State.OK, QuoteRules.parse(mapOf("state" to "ok"))?.state)
        assertEquals(QuoteCard.State.DELETED, QuoteRules.parse(mapOf("state" to "deleted"))?.state)
        assertEquals(QuoteCard.State.HIDDEN, QuoteRules.parse(mapOf("state" to "private"))?.state)
        assertEquals(QuoteCard.State.GONE, QuoteRules.parse(mapOf("state" to "gone"))?.state)
        assertNull(QuoteRules.parse(mapOf("state" to "weird")))
        assertNull(QuoteRules.parse(null))
        // createQuote's answer has no state: it is the post as I see it now.
        val made = QuoteRules.parse(mapOf("qid" to "abc123def456gh78", "title" to "News", "kind" to "photo"), forceOk = true)!!
        assertEquals(QuoteCard.State.OK, made.state)
        assertEquals("photo", made.kind)
    }

    @Test fun okWords() {
        val c = QuoteCard(QuoteCard.State.OK, cid = "c", mid = "m", title = "Daily News", username = "daily", text = "Hello", image = "https://x/i.jpg")
        assertEquals("Daily News", QuoteRules.heading(c))
        assertEquals("Hello", QuoteRules.body(c))
        assertEquals("Quoted from @daily", QuoteRules.footer(c))
        assertEquals("https://x/i.jpg", QuoteRules.image(c))
        assertNull(QuoteRules.tapNotice(c))
    }

    @Test fun okWithoutTextSaysItsKind() {
        val base = QuoteCard(QuoteCard.State.OK)
        assertEquals("Photo", QuoteRules.body(base.copy(kind = "photo")))
        assertEquals("Video", QuoteRules.body(base.copy(kind = "video")))
        assertEquals("Album", QuoteRules.body(base.copy(kind = "album")))
        assertEquals("Post", QuoteRules.body(base.copy(kind = "text")))
        assertEquals("Channel", QuoteRules.heading(base))
        assertEquals("Quoted post", QuoteRules.footer(base))
        assertNull(QuoteRules.image(base))
    }

    @Test fun deletedKeepsTheChannelNotThePost() {
        val c = QuoteCard(QuoteCard.State.DELETED, title = "Daily News", username = "daily", image = "https://x/i.jpg")
        assertEquals("Daily News", QuoteRules.heading(c))
        assertEquals("Post deleted", QuoteRules.body(c))
        assertEquals("Quoted post", QuoteRules.footer(c))
        assertNull(QuoteRules.image(c))
        assertEquals("This post was deleted", QuoteRules.tapNotice(c))
    }

    @Test fun privateAndGoneShowNothingOfThePost() {
        val p = QuoteCard(QuoteCard.State.HIDDEN, title = "Secret", text = "words", image = "https://x/i.jpg")
        assertEquals("Channel post", QuoteRules.heading(p))
        assertEquals("This channel is private", QuoteRules.body(p))
        assertNull(QuoteRules.image(p))
        assertEquals("This channel is private", QuoteRules.tapNotice(p))
        val g = QuoteCard(QuoteCard.State.GONE)
        assertEquals("Channel post", QuoteRules.heading(g))
        assertEquals("Content unavailable", QuoteRules.body(g))
        assertEquals("Quoted post", QuoteRules.footer(g))
    }

    @Test fun unknownYetIsQuiet() {
        assertEquals("Channel post", QuoteRules.heading(null))
        assertEquals("Loading…", QuoteRules.body(null))
        assertEquals("Quoted post", QuoteRules.footer(null))
        assertNull(QuoteRules.tapNotice(null))
    }

    @Test fun cacheAge() {
        val now = 1_000_000L
        assertTrue(QuoteRules.isStale(null, now))
        assertFalse(QuoteRules.isStale(now - 60_000, now))
        assertFalse(QuoteRules.isStale(now - 120_000, now))
        assertTrue(QuoteRules.isStale(now - 120_001, now))
        // After a failure the id is asked again about 15 s later.
        val stamp = QuoteRules.retryStamp(now)
        assertFalse(QuoteRules.isStale(stamp, now + 14_000))
        assertTrue(QuoteRules.isStale(stamp, now + 16_000))
    }
}
