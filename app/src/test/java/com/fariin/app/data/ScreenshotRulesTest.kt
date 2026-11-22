package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotRulesTest {
    private fun msg(id: String, author: String = "them", text: String = "hi", type: String? = null, at: Double = 1_000.0) =
        Message(id = id, authorId = author, text = text, type = type, createdAtMillis = at)

    @Test fun offeredOnlyInPlainChats() {
        assertTrue(ScreenshotRules.offers(isChannel = false, isOfficial = false, isGuest = false, restricted = false, windowSecure = false))
        assertFalse(ScreenshotRules.offers(isChannel = true, isOfficial = false, isGuest = false, restricted = false, windowSecure = false))
        assertFalse(ScreenshotRules.offers(isChannel = false, isOfficial = true, isGuest = false, restricted = false, windowSecure = false))
        assertFalse(ScreenshotRules.offers(isChannel = false, isOfficial = false, isGuest = true, restricted = false, windowSecure = false))
        assertFalse(ScreenshotRules.offers(isChannel = false, isOfficial = false, isGuest = false, restricted = true, windowSecure = false))
        assertFalse(ScreenshotRules.offers(isChannel = false, isOfficial = false, isGuest = false, restricted = false, windowSecure = true))
    }

    @Test fun keptOutOfThePicture() {
        assertNull(ScreenshotRules.kindOf(msg("a").copy(viewOnce = true)))
        assertNull(ScreenshotRules.kindOf(msg("a").copy(deleted = true)))
        assertNull(ScreenshotRules.kindOf(msg("a", type = "call")))
        assertNull(ScreenshotRules.kindOf(msg("a", text = "🔒")))
        assertNull(ScreenshotRules.kindOf(msg("a", text = "")))
        assertEquals(ScreenshotRules.Kind.TEXT, ScreenshotRules.kindOf(msg("a")))
        assertEquals(ScreenshotRules.Kind.SYSTEM, ScreenshotRules.kindOf(msg("a", type = "system", text = "X joined")))
        assertEquals(ScreenshotRules.Kind.MEDIA, ScreenshotRules.kindOf(msg("a", type = "image", text = "").copy(imageUrl = "u")))
    }

    @Test fun snapshotTakesOnlyRowsOnScreenInOrder() {
        val list = listOf(msg("1", at = 1.0), msg("2", author = "me", at = 2.0), msg("3", at = 3.0))
        val shots = ScreenshotRules.snapshot(list, setOf("3", "2"), "me", isGroup = true, receipts = Receipts(),
            name = { "Ana" }, day = { "Today" })
        assertEquals(listOf("2", "3"), shots.map { it.id })
        assertNull(shots[0].senderName)
        assertEquals("Ana", shots[1].senderName)
        assertTrue(shots[0].isMe)
    }

    @Test fun removeUndoAndHide() {
        val shots = ScreenshotRules.snapshot(listOf(msg("1"), msg("2"), msg("3")), setOf("1", "2", "3"), "me",
            isGroup = false, receipts = Receipts(), name = { "" }, day = { "Today" })
        var e = ScreenshotRules.Edits().remove("1").remove("3")
        assertEquals(listOf("2"), e.rows(shots).map { it.id })
        e = e.undo()
        assertEquals(listOf("2", "3"), e.rows(shots).map { it.id })
        e = e.toggleHidden("2")
        assertTrue(e.isHidden("2"))
        assertFalse(e.toggleHidden("2").isHidden("2"))
        assertEquals(e, e.remove("1"))
    }

    @Test fun placementsShowDayAndNameAtRunStart() {
        val list = listOf(msg("1", at = 1.0), msg("2", at = 2.0), msg("3", author = "b", at = 3.0))
        val shots = ScreenshotRules.snapshot(list, setOf("1", "2", "3"), "me", isGroup = true, receipts = Receipts(),
            name = { it }, day = { if (it < 3.0) "Monday" else "Today" })
        val p = ScreenshotRules.placements(shots)
        assertEquals(listOf(true, false, true), p.map { it.showDay })
        assertEquals(listOf(true, false, true), p.map { it.showName })
    }

    @Test fun coveredWordsNeverDrawn() {
        val c = ScreenshotRules.coverText("secret plan")
        assertFalse(c.contains("secret"))
        assertEquals(11, c.length)
        assertEquals(3, ScreenshotRules.coverText("a").length)
        assertEquals("1 picture · 1 message", ScreenshotRules.countLine(1, 1))
        assertEquals("2 pictures · 4 messages", ScreenshotRules.countLine(4, 2))
    }

    @Test fun planSplitsBetweenMessagesUnderTheScreenHeight() {
        // Budget 100, pill 10: [10+40, 40] fills 90, the next 30 opens a picture with its own pill.
        val p = ScreenshotRules.plan(listOf(40, 40, 30, 20), listOf(true, false, false, false), dayPx = 10, budget = 100)
        assertEquals(listOf(listOf(0, 1), listOf(2, 3)), p)
        // All fit: one picture.
        assertEquals(listOf(listOf(0, 1, 2)), ScreenshotRules.plan(listOf(10, 10, 10), listOf(true, false, false), 10, 100))
        // A row taller than a picture stands alone, never cut.
        assertEquals(listOf(listOf(0), listOf(1), listOf(2)), ScreenshotRules.plan(listOf(20, 300, 20), listOf(true, false, false), 10, 100))
        assertTrue(ScreenshotRules.plan(emptyList(), emptyList(), 10, 100).isEmpty())
    }

    @Test fun laterPicturesStartWithDayAndName() {
        val list = listOf(msg("1", at = 1.0), msg("2", at = 2.0), msg("3", at = 3.0))
        val shots = ScreenshotRules.snapshot(list, setOf("1", "2", "3"), "me", isGroup = true, receipts = Receipts(),
            name = { it }, day = { "Today" })
        val p = ScreenshotRules.placements(shots, pageStarts = setOf(2))
        assertEquals(listOf(true, false, true), p.map { it.showDay })
        assertEquals(listOf(true, false, true), p.map { it.showName })
    }

    @Test fun mediaRowsCarryTheirPictureNotTheLabelAsWords() {
        val photo = msg("p", type = "image", text = "at the beach").copy(imageUrl = "u1", width = 400.0, height = 200.0)
        val video = msg("v", type = "video", text = "").copy(videoUrl = "v1", thumbUrl = "t1")
        val sticker = msg("s", type = "sticker", text = "").copy(stickerPack = "cats", stickerId = "c1", stickerEmoji = "😺")
        assertEquals(ScreenshotRules.Pic("u1", aspect = 2f), ScreenshotRules.picOf(photo))
        assertEquals(ScreenshotRules.Pic("t1", video = true), ScreenshotRules.picOf(video))
        assertEquals("c1", ScreenshotRules.picOf(sticker)?.stickerId)
        assertNull(ScreenshotRules.picOf(msg("a", type = "audio", text = "").copy(audioUrl = "a1")))
        val shots = ScreenshotRules.snapshot(listOf(photo, sticker), setOf("p", "s"), "me", isGroup = false,
            receipts = Receipts(), name = { "" }, day = { "Today" })
        assertEquals("at the beach", shots[0].text)
        assertEquals("Photo", shots[0].label)
        assertEquals("", shots[1].text)
        assertEquals("😺", shots[1].label)
    }

    @Test fun wordAtFindsTheWholeWord() {
        val t = "meet at don't go"
        assertEquals(ScreenshotRules.Span(0, 4), ScreenshotRules.wordAt(t, 2))
        assertEquals(ScreenshotRules.Span(0, 4), ScreenshotRules.wordAt(t, 4))
        assertEquals(ScreenshotRules.Span(8, 13), ScreenshotRules.wordAt(t, 9))
        assertNull(ScreenshotRules.wordAt("a  b", 2))
        assertNull(ScreenshotRules.wordAt("", 0))
    }

    @Test fun coverMergesTouchingSpansAndReveals() {
        var c = ScreenshotRules.Cover().cover(ScreenshotRules.Span(5, 8)).cover(ScreenshotRules.Span(0, 4))
        assertEquals(listOf(ScreenshotRules.Span(0, 4), ScreenshotRules.Span(5, 8)), c.spans)
        c = c.cover(ScreenshotRules.Span(4, 5))
        assertEquals(listOf(ScreenshotRules.Span(0, 8)), c.spans)
        assertTrue(c.hides(3))
        assertFalse(c.hides(8))
        assertTrue(c.reveal(2).isEmpty)
        assertEquals(c, c.reveal(9))
        assertEquals(c, c.cover(ScreenshotRules.Span(3, 3)))
    }

    @Test fun hiddenWordsBecomeDotsAndKeepPlaces() {
        val t = "the code is 4471"
        val c = ScreenshotRules.Cover().cover(ScreenshotRules.Span(12, 16)).cover(ScreenshotRules.Span(4, 8))
        val d = ScreenshotRules.drawnText(t, c)
        assertEquals("the •••• is ••••", d)
        assertEquals(t.length, d.length)
        assertFalse(d.contains("4471"))
        assertFalse(d.contains("code"))
        assertEquals(t, ScreenshotRules.drawnText(t, ScreenshotRules.Cover()))
        assertFalse(ScreenshotRules.drawnText(t, ScreenshotRules.Cover(whole = true)).contains("the"))
    }

    @Test fun tapWordHidesThenShowsAgain() {
        val t = "call me at noon"
        var e = ScreenshotRules.Edits().tapWord("1", t, 12)
        assertEquals(listOf(ScreenshotRules.Span(11, 15)), e.cover("1").spans)
        e = e.tapWord("1", t, 0)
        assertEquals(2, e.cover("1").spans.size)
        e = e.tapWord("1", t, 13).tapWord("1", t, 1)
        assertTrue(e.cover("1").isEmpty)
        assertTrue(e.covers.isEmpty())
        assertEquals(e, e.tapWord("1", "hi !! yo", 4))
        e = e.tapWord("1", t, 0).toggleHidden("1")
        assertTrue(e.isHidden("1"))
        assertTrue(e.toggleHidden("1").cover("1").isEmpty)
    }
}
