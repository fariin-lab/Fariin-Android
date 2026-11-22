package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OnceStubTest {
    private val key = com.fariin.app.crypto.EncMeta.fromMap(mapOf("v" to 1, "k" to "a2V5", "n" to "bm9uY2U=", "kn" to "a24="))
    private fun once(consumed: Boolean, type: String = "image", author: String = "them") = Message(
        id = "m1", authorId = author, text = "secret caption", type = type, createdAtMillis = 5_000.0,
        imageUrl = if (type == "image") "https://x/once.jpg" else null, audioUrl = if (type == "audio") "https://x/once.m4a" else null,
        enc = key, width = 800.0, height = 600.0, blurhash = "LEHV6n", thumb = "dGh1bWI=", thumbUrl = "https://x/t.jpg",
        duration = 4.0, waveform = listOf(1, 2, 3), localPath = "/data/x.jpg", localUri = "content://x",
        viewOnce = true, consumed = consumed, reactions = mapOf("u" to "👍"), clientId = "c1",
    )

    @Test fun stubKeepsWhatTheOpenedBubbleDraws() {
        val s = OnceStub.stub(once(consumed = true))
        assertEquals("m1", s.id); assertEquals("them", s.authorId); assertEquals(5_000.0, s.createdAtMillis, 0.0)
        assertEquals("image", s.type); assertTrue(s.viewOnce); assertTrue(s.consumed)
        assertEquals(Message.MediaKind.VIEW_ONCE, s.mediaKind)
        assertEquals(mapOf("u" to "👍"), s.reactions); assertEquals("c1", s.clientId)
    }

    @Test fun stubHasNoContent() {
        val s = OnceStub.stub(once(consumed = true))
        assertEquals("", s.text)
        assertNull(s.imageUrl); assertNull(s.videoUrl); assertNull(s.audioUrl); assertNull(s.fileUrl)
        assertNull(s.enc); assertNull(s.thumbEnc); assertNull(s.thumb); assertNull(s.thumbUrl); assertNull(s.blurhash)
        assertNull(s.width); assertNull(s.height); assertNull(s.duration); assertTrue(s.waveform.isEmpty())
        assertNull(s.localPath); assertNull(s.localUri)
        val body = com.fariin.app.local.LocalJson.encodeMessage(s).toString()
        for (k in listOf("\"img\"", "\"aud\"", "\"enc\"", "\"th\"", "\"thu\"", "\"bh\"", "secret")) assertFalse(k, body.contains(k))
        // Read back from the phone's file it is still the same stub (the chat opens with "Opened").
        val back = com.fariin.app.local.LocalJson.decodeMessage(org.json.JSONObject(body))!!
        assertTrue(OnceStub.isStub(back)); assertEquals(Message.MediaKind.VIEW_ONCE, back.mediaKind)
    }

    @Test fun voiceStubStaysAVoiceOnce() {
        val s = OnceStub.stub(once(consumed = true, type = "audio"))
        assertEquals(Message.MediaKind.VOICE, s.mediaKind); assertTrue(s.viewOnce); assertNull(s.audioUrl)
    }

    @Test fun notYetOpenedIsNotStubbed() {
        val m = once(consumed = false)
        assertFalse(OnceStub.isSpent(m, seenHere = false))
        assertSame(m, OnceStub.forStore(m, seenHere = false))
        assertTrue(OnceStub.migrate(listOf(m), me = "me") { false }.isEmpty())
    }

    @Test fun openedOnThisPhoneIsStubbed() {
        assertTrue(OnceStub.forStore(once(consumed = false), seenHere = true).consumed)
    }

    @Test fun myOwnGoesByTheServerOnly() {
        val mine = once(consumed = false, author = "me")
        assertTrue(OnceStub.migrate(listOf(mine), me = "me") { true }.isEmpty())
    }

    @Test fun normalPhotoIsNeverTouched() {
        val photo = once(consumed = true).copy(viewOnce = false)
        assertSame(photo, OnceStub.forStore(photo, seenHere = true))
        assertTrue(OnceStub.migrate(listOf(photo), me = "me") { true }.isEmpty())
    }

    @Test fun migrationIsIdempotent() {
        val rows = listOf(once(consumed = true), once(consumed = false).copy(id = "m2"), once(consumed = true).copy(id = "m3", viewOnce = false))
        val first = OnceStub.migrate(rows, me = "me") { false }
        assertEquals(listOf("m1"), first.map { it.id })
        assertTrue(OnceStub.isStub(first[0]))
        val after = rows.map { r -> first.firstOrNull { it.id == r.id } ?: r }
        assertTrue(OnceStub.migrate(after, me = "me") { false }.isEmpty())
        assertEquals(first[0], OnceStub.stub(first[0]))
    }

    @Test fun mediaUrlsAreThisMessagesOnly() {
        assertEquals(listOf("https://x/once.jpg", "https://x/t.jpg"), OnceStub.mediaUrls(once(consumed = true)))
    }
}
