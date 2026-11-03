package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StickerGifRulesTest {
    private val me = "me"
    private val other = "them"
    private val noOpen: (String, String) -> String = { raw, _ -> raw }

    private fun call(caller: String, outcome: String?, video: Boolean = false, duration: Int? = null) =
        Message(id = "call_1", authorId = caller, text = "", type = "call", createdAtMillis = 0.0,
            callerUid = caller, callOutcome = outcome, callVideo = video, callDuration = duration)

    @Test fun stickerLabel() {
        assertEquals("😂 Sticker", StickerGifRules.stickerLabel("😂"))
        assertEquals("Sticker", StickerGifRules.stickerLabel(" "))
        assertEquals("Sticker", StickerGifRules.stickerLabel(null))
    }

    @Test fun stickerBoxFitsIn160() {
        assertEquals(160f to 160f, StickerGifRules.stickerBox(512.0, 512.0))
        assertEquals(160f to 80f, StickerGifRules.stickerBox(1024.0, 512.0))
        assertEquals(40f to 160f, StickerGifRules.stickerBox(100.0, 1000.0))
        assertEquals(160f to 160f, StickerGifRules.stickerBox(null, null))
    }

    @Test fun gifBoxCaps() {
        assertEquals(240f to 240f, StickerGifRules.gifBox(200.0, 200.0))
        assertEquals(170f, StickerGifRules.gifBox(100.0, 200.0).first, 0.01f)
        assertEquals(340f, StickerGifRules.gifBox(100.0, 200.0).second, 0.01f)
        assertEquals(220f to 220f, StickerGifRules.gifBox(0.0, 0.0))
    }

    @Test fun recentsNewestFirstNoDuplicatesCapped() {
        val r = (1..25).fold(emptyList<String>()) { acc, i -> StickerGifRules.noteUsed(acc, "p/$i") }
        assertEquals(20, r.size)
        assertEquals("p/25", r.first())
        assertEquals(listOf("p/3", "p/25"), StickerGifRules.noteUsed(r, "p/3").take(2))
        assertEquals(20, StickerGifRules.noteUsed(r, "p/3").size)
    }

    @Test fun parseGiphyReadsFixedWidth() {
        val json = """{"data":[{"id":"a","images":{"fixed_width":{"url":"https://x/a.gif","width":"200","height":"150"}}},
            {"id":"b","images":{}},{"images":{"fixed_width":{"url":"u"}}}]}"""
        val g = StickerGifRules.parseGiphy(json)
        assertEquals(listOf(StickerGifRules.Gif("a", "https://x/a.gif", 200.0, 150.0)), g)
        assertTrue(StickerGifRules.parseGiphy("not json").isEmpty())
    }

    @Test fun masonryBalancesColumns() {
        val tall = StickerGifRules.Gif("t", "u", 100.0, 300.0)
        val small = { id: String -> StickerGifRules.Gif(id, "u", 100.0, 100.0) }
        val (l, r) = StickerGifRules.masonry(listOf(tall, small("1"), small("2"), small("3")))
        assertEquals(listOf("t"), l.map { it.id })
        assertEquals(listOf("1", "2", "3"), r.map { it.id })
    }

    @Test fun callWords() {
        val missedIn = StickerGifRules.callLook(call(other, "missed"), me, 600.0, "1:20 AM")
        assertEquals("Missed voice call", missedIn.status)
        assertEquals("Call back · 1:20 AM", missedIn.detail)
        assertTrue(missedIn.missedIncoming)
        assertEquals(StickerGifRules.CallIcon.PHONE_IN, missedIn.icon)

        val noAnswer = StickerGifRules.callLook(call(me, "missed"), me, 600.0, "1:23 AM")
        assertEquals("Voice call", noAnswer.status)
        assertEquals("No answer · 1:23 AM", noAnswer.detail)
        assertFalse(noAnswer.missedIncoming)
        assertEquals(StickerGifRules.CallIcon.PHONE_OUT, noAnswer.icon)

        val video = StickerGifRules.callLook(call(me, "answered", video = true, duration = 125), me, 600.0, "1:21 AM")
        assertEquals("Video call", video.status)
        assertEquals("2:05 · 1:21 AM", video.detail)
        assertEquals(StickerGifRules.CallIcon.VIDEO, video.icon)

        val missedVideo = StickerGifRules.callLook(call(other, "declined", video = true), me, 600.0, "t")
        assertEquals("Missed video call", missedVideo.status)
        assertEquals(StickerGifRules.CallIcon.VIDEO_MISSED, missedVideo.icon)

        assertEquals("Incoming · t", StickerGifRules.callLook(call(other, "answered"), me, 600.0, "t").detail)
        assertEquals("45 sec · t", StickerGifRules.callLook(call(other, "answered", duration = 45), me, 600.0, "t").detail)
    }

    @Test fun liveCallRows() {
        val ringing = StickerGifRules.callLook(call(me, "ringing"), me, 30.0, "t")
        assertTrue(ringing.live)
        assertEquals("Calling · t", ringing.detail)
        assertEquals("Ringing · t", StickerGifRules.callLook(call(me, "ringing"), me, 30.0, "t", calleeRinging = true).detail)
        // An orphan ring is an unanswered call.
        val stale = StickerGifRules.callLook(call(other, "ringing"), me, 200.0, "t")
        assertFalse(stale.live)
        assertEquals("Missed voice call", stale.status)
        assertEquals("Ongoing · t", StickerGifRules.callLook(call(other, "ongoing"), me, 60.0, "t").detail)
    }

    @Test fun groupOutcomeIsMine() {
        assertEquals("answered", StickerGifRules.outcomeMine("missed", other, listOf(other, me), null, me))
        assertEquals("missed", StickerGifRules.outcomeMine("answered", other, listOf(other, "x"), listOf(me), me))
        assertEquals(StickerGifRules.NOT_JOINED, StickerGifRules.outcomeMine("answered", other, listOf(other, "x"), listOf("x"), me))
        assertEquals("missed", StickerGifRules.outcomeMine("answered", me, listOf(me), null, me))
        assertEquals("ringing", StickerGifRules.outcomeMine("ringing", other, listOf(), null, me))
        assertEquals("answered", StickerGifRules.outcomeMine("answered", other, null, null, me))
    }

    @Test fun callDurations() {
        assertEquals("59 sec", StickerGifRules.callDuration(59))
        assertEquals("1:00", StickerGifRules.callDuration(60))
        assertEquals("1:02:03", StickerGifRules.callDuration(3723))
    }

    @Test fun callBackWords() {
        assertEquals("Voice call", StickerGifRules.callBackTitle(false))
        assertEquals("Call Alice?", StickerGifRules.callBackQuestion(false, "Alice"))
        assertEquals("Video call Alice?", StickerGifRules.callBackQuestion(true, "Alice"))
    }

    @Test fun hosts() {
        assertEquals("fariin.com", StickerGifRules.host("https://www.fariin.com/help"))
        assertEquals("YouTube", StickerGifRules.cardHost("https://youtu.be/abc"))
        assertEquals("YouTube", StickerGifRules.cardHost("https://m.youtube.com/watch?v=1"))
        assertEquals("example.org", StickerGifRules.cardHost("https://example.org"))
    }

    @Test fun parseStickerDropsOldBuildText() {
        val m = Message.fromFirestore("s1", mapOf("type" to "sticker", "authorId" to other, "text" to "👋",
            "stickerPack" to "kulu", "stickerId" to "hi", "emoji" to "👋", "animated" to true,
            "imageUrl" to "https://fariin.com/stickers/kulu/hi.webp"), "groupcid", 1.0, noOpen)
        assertTrue(m.isSticker)
        assertEquals("", m.text)
        assertEquals("👋 Sticker", m.bodyText)
        assertTrue(m.stickerAnimated)
        assertNull(m.mediaKind)
    }

    @Test fun parseGifCallAndLinkCard() {
        val gif = Message.fromFirestore("g", mapOf("type" to "gif", "authorId" to other, "text" to "",
            "imageUrl" to "https://media.giphy.com/x.gif", "width" to 200.0, "height" to 100.0), "groupcid", 1.0, noOpen)
        assertTrue(gif.isGif)
        assertEquals("GIF", gif.bodyText)
        val c = Message.fromFirestore("call_1", mapOf("type" to "call", "authorId" to other, "callerUid" to other,
            "callOutcome" to "missed", "callVideo" to true, "callDuration" to 0L, "joinedUids" to listOf(other)), "a_b", 1.0, noOpen)
        assertTrue(c.isCall)
        assertEquals(other, c.callerUid)
        assertTrue(c.callVideo)
        assertEquals(listOf(other), c.callJoinedUids)
        val l = Message.fromFirestore("l", mapOf("type" to "text", "authorId" to other, "text" to "hi",
            "linkPreview" to mapOf("url" to "https://fariin.com", "title" to "Fariin", "desc" to "d")), "groupcid", 1.0, noOpen)
        assertEquals("Fariin", l.linkPreview?.title)
        assertEquals("fariin.com", l.linkPreview?.host)
        val dead = Message.fromFirestore("l2", mapOf("type" to "text", "authorId" to other, "text" to "hi",
            "linkPreview" to mapOf("url" to "x")), "groupcid", 1.0) { _, _ -> com.fariin.app.crypto.Wire.LOCKED }
        assertNull(dead.linkPreview)
    }
}
