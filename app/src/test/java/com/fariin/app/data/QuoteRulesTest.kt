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

    @Test fun pictureSource() {
        assertEquals("img", QuoteRules.pictureUrl("img", null, null, null))
        // A video gives its poster, never the clip.
        assertEquals("poster", QuoteRules.pictureUrl(null, "clip", "poster", null))
        assertNull(QuoteRules.pictureUrl(null, "clip", null, null))
        // An album: its first tile.
        assertEquals("tile0", QuoteRules.pictureUrl(null, null, null, "tile0"))
        assertNull(QuoteRules.pictureUrl(null, null, null, null))
        assertNull(QuoteRules.pictureUrl("", null, null, null))
    }

    @Test fun pictureSizeCapsLongSide() {
        assertEquals(900 to 675, QuoteRules.pictureSize(4000, 3000))
        assertEquals(506 to 900, QuoteRules.pictureSize(1080, 1920))
        assertEquals(640 to 480, QuoteRules.pictureSize(640, 480))
        assertEquals(0 to 0, QuoteRules.pictureSize(0, 100))
        assertEquals(listOf(72, 60, 48, 36, 24), QuoteRules.PICTURE_QUALITIES)
    }

    @Test fun tapInTheOpenChatJumps() {
        val c = QuoteCard(QuoteCard.State.OK, cid = "ch1", mid = "m1")
        assertTrue(QuoteRules.jumpsInPlace(c, "ch1"))
        assertFalse(QuoteRules.jumpsInPlace(c, "other"))
        assertFalse(QuoteRules.jumpsInPlace(c.copy(state = QuoteCard.State.DELETED), "ch1"))
        assertFalse(QuoteRules.jumpsInPlace(c.copy(mid = ""), "ch1"))
        assertFalse(QuoteRules.jumpsInPlace(null, "ch1"))
    }

    @Test fun tokenIsNeverAQid() {
        val t = QuoteRules.newToken("0f8e7d6c-1234-4abc-9def-001122334455")
        assertTrue(QuoteRules.isToken(t))
        assertFalse(QuoteRules.isQid(t))
        assertFalse(QuoteRules.isToken("abc123def456gh78"))
        assertFalse(QuoteRules.isToken(QuoteRules.TOKEN_PREFIX))
        assertFalse(QuoteRules.isToken(null))
        // Even a token built around a qid-shaped string is not a qid.
        assertFalse(QuoteRules.isQid(QuoteRules.newToken("abc123def456gh78")))
    }

    @Test fun localCardFromThePost() {
        val c = QuoteRules.localCard("ch1", "m1", "Daily News", "https://x/p.jpg", "  Hello  ", "image", false)
        assertEquals(QuoteCard.State.OK, c.state)
        assertEquals("ch1", c.cid); assertEquals("m1", c.mid)
        assertEquals("Daily News", QuoteRules.heading(c))
        assertEquals("Hello", QuoteRules.body(c))
        assertEquals("photo", c.kind)
        assertEquals("https://x/p.jpg", c.photoUrl)
        assertNull(QuoteRules.image(c))   // the server's picture comes with its answer
        val bare = QuoteRules.localCard("ch1", "m1", "", null, null, "video", false)
        assertEquals("Channel", QuoteRules.heading(bare))
        assertEquals("Video", QuoteRules.body(bare))
        assertEquals("", bare.photoUrl)
        assertEquals("album", QuoteRules.kindOf("image", true))
        assertEquals("text", QuoteRules.kindOf(null, false))
    }

    @Test fun draftWaitsUntilItsQidLands() {
        val tk = QuoteRules.newToken("u1")
        val local = QuoteRules.localCard("ch1", "m1", "News", "", "Hi", null, false)
        val d = QuoteStore.Draft("dm1", "", local, tk)
        assertTrue(QuoteRules.isWaiting(d))
        assertNull(QuoteRules.sendableQid(d))
        assertEquals(tk, QuoteRules.pictureKey(d))
        assertEquals(tk, QuoteRules.draftKey(d))

        val server = local.copy(username = "news", image = "https://x/i.jpg")
        val ready = QuoteRules.landed(d, tk, "abc123def456gh78", server)!!
        assertFalse(QuoteRules.isWaiting(ready))
        assertEquals("abc123def456gh78", QuoteRules.sendableQid(ready))
        assertEquals(server, ready.card)
        assertEquals("abc123def456gh78", QuoteRules.pictureKey(ready))
        assertEquals(tk, QuoteRules.draftKey(ready))   // the bar and the focus keep their key

        // Another quote's answer, or a bad id, changes nothing.
        assertEquals(d, QuoteRules.landed(d, QuoteRules.newToken("u2"), "abc123def456gh78", server))
        assertEquals(d, QuoteRules.landed(d, tk, tk, server))
        assertNull(QuoteRules.landed(null, tk, "abc123def456gh78", server))
    }

    @Test fun refusalTakesOnlyItsOwnDraft() {
        val tk = QuoteRules.newToken("u1")
        val d = QuoteStore.Draft("dm1", "", QuoteCard(QuoteCard.State.OK), tk)
        assertNull(QuoteRules.refused(d, tk))
        assertEquals(d, QuoteRules.refused(d, QuoteRules.newToken("u2")))
        assertNull(QuoteRules.refused(null, tk))
    }

    @Test fun oldStyleDraftSendsAtOnce() {
        val d = QuoteStore.Draft("dm1", "abc123def456gh78", QuoteCard(QuoteCard.State.OK))
        assertFalse(QuoteRules.isWaiting(d))
        assertEquals("abc123def456gh78", QuoteRules.sendableQid(d))
        assertEquals("abc123def456gh78", QuoteRules.draftKey(d))
        // No token and no qid: nothing to send and nothing to wait for.
        val empty = QuoteStore.Draft("dm1", "", QuoteCard(QuoteCard.State.OK))
        assertNull(QuoteRules.sendableQid(empty))
        assertFalse(QuoteRules.isWaiting(empty))
    }
}
