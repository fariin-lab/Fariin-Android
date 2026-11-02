package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PinRulesTest {
    private val me = "me"
    private fun msg(id: String = "m", author: String = me, text: String = "hi", type: String? = null, at: Double = 1.0) =
        Message(id = id, authorId = author, text = text, type = type, createdAtMillis = at)

    @Test fun noticeRoundTripsLikeIos() {
        val t = PinRules.markerText("abc", "\"hello | there\"")
        assertEquals("fariin-pinned:abc|\"hello | there\"", t)
        val n = PinRules.notice(t)!!
        assertEquals("abc", n.messageId)
        assertEquals("\"hello | there\"", n.label)
        assertNull(PinRules.notice("fariin-pinned:|x"))
        assertNull(PinRules.notice("fariin-pinned:abc|"))
        assertNull(PinRules.notice("hello"))
    }

    @Test fun labelQuotesTwentyCharactersOrNamesTheMedia() {
        assertEquals("\"hi\"", PinRules.label(msg()))
        assertEquals("\"abcdefghijklmnopqrst…\"", PinRules.label(msg(text = "abcdefghijklmnopqrstuvwxyz")))
        assertEquals("a photo", PinRules.label(msg(type = "image", text = "")))
        assertEquals("a voice message", PinRules.label(msg(type = "audio", text = "")))
        assertEquals("a file", PinRules.label(msg(type = "file", text = "")))
        assertEquals("a message", PinRules.label(msg(text = "  ")))
    }

    @Test fun pinnedIdsFallBackToTheLegacyField() {
        assertEquals(listOf("a", "b"), PinRules.pinnedIds(mapOf("pinnedMessageIds" to listOf("a", "b"), "pinnedMessageId" to "z")))
        assertEquals(listOf("z"), PinRules.pinnedIds(mapOf("pinnedMessageId" to "z")))
        assertEquals(emptyList<String>(), PinRules.pinnedIds(mapOf("pinnedMessageId" to "")))
    }

    @Test fun whoMayPinInAGroup() {
        val g = mapOf<String, Any?>("createdBy" to "owner", "admins" to listOf("owner", "legacy", "limited", "pinner"),
            "adminRights" to mapOf("limited" to listOf("changeInfo"), "pinner" to listOf("pinMessages")))
        assertTrue(PinRules.canPin(g, "owner", true))
        assertTrue(PinRules.canPin(g, "legacy", true))
        assertTrue(PinRules.canPin(g, "pinner", true))
        assertFalse(PinRules.canPin(g, "limited", true))
        assertFalse(PinRules.canPin(g, "member", true))
        assertTrue(PinRules.canPin(emptyMap(), "anyone", false))
    }

    @Test fun pinOfferedOnlyOnDeliveredOrdinaryMessages() {
        assertTrue(PinRules.offers(msg()))
        assertFalse(PinRules.offers(msg().copy(sendState = Message.SendState.SENDING)))
        assertFalse(PinRules.offers(msg().copy(viewOnce = true)))
        assertFalse(PinRules.offers(msg().copy(deleted = true)))
        assertFalse(PinRules.offers(msg(type = "system")))
        assertFalse(PinRules.offers(msg(text = PinRules.markerText("x", "a photo"))))
    }

    @Test fun barCycles() {
        assertEquals(1, PinRules.nextIndex(0, 3))
        assertEquals(0, PinRules.nextIndex(2, 3))
        assertEquals(0, PinRules.nextIndex(0, 1))
        assertEquals("Tap to view", PinRules.snippet(null))
    }

    @Test fun selectionDeletePlanFollowsTheSingleDeleteRules() {
        val mine = msg(id = "1")
        val theirs = msg(id = "2", author = "them")
        val dm = SelectRules.plan(listOf(mine, theirs), everyone = true, me = me, isGroup = false, adminMayDelete = false)
        assertEquals(listOf(mine), dm.forEveryone)
        assertEquals(listOf(theirs), dm.forMe)
        // A group has no Delete for Me: what I may not delete for all stays.
        val grp = SelectRules.plan(listOf(mine, theirs), everyone = true, me = me, isGroup = true, adminMayDelete = false)
        assertEquals(listOf(mine), grp.forEveryone)
        assertTrue(grp.forMe.isEmpty())
        val forMe = SelectRules.plan(listOf(mine, theirs), everyone = false, me = me, isGroup = false, adminMayDelete = false)
        assertEquals(2, forMe.forMe.size)
        assertEquals(SelectRules.Split(1, 1), SelectRules.split(listOf(mine, theirs), me, false))
    }

    @Test fun forwardNeedsEveryPickForwardableAndAtMost32() {
        assertTrue(SelectRules.forwardable(listOf(msg(), msg(id = "2"))))
        assertFalse(SelectRules.forwardable(listOf(msg(), msg(id = "2").copy(deleted = true))))
        assertFalse(SelectRules.forwardable(emptyList()))
        assertFalse(SelectRules.forwardable((1..33).map { msg(id = "$it") }))
    }

    @Test fun copyJoinsInChatOrder() {
        val a = msg(id = "a", text = "first", at = 1.0)
        val b = msg(id = "b", text = "second", at = 2.0)
        assertEquals("first\n\nsecond", SelectRules.copyText(listOf(b, a)))
        assertFalse(SelectRules.selectable(msg(type = "system")))
        assertFalse(SelectRules.selectable(msg(text = PinRules.markerText("x", "a photo"))))
        assertTrue(SelectRules.selectable(msg().copy(deleted = true)))
    }
}
