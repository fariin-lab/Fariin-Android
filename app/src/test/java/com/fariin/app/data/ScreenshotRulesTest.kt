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
        assertEquals("1 message", ScreenshotRules.countLine(1))
        assertEquals("4 messages", ScreenshotRules.countLine(4))
    }
}
