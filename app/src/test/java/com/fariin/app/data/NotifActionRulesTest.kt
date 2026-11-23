package com.fariin.app.data

import com.fariin.app.push.NotifBuilder
import com.fariin.app.push.NotifExpiry
import com.fariin.app.push.PushData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotifActionRulesTest {
    private fun refuse(
        isGroup: Boolean = false, isChannel: Boolean = false, blocked: Boolean = false,
        startedBy: String = "", accepted: Boolean = true, reqOff: Boolean = false, lastSender: String = "",
        deletion: Long = 0L, banned: Boolean = false,
    ) = NotifActionRules.replyRefusal(isGroup, isChannel, blocked, startedBy, accepted, reqOff, lastSender,
        me = "me", deletionAtMs = deletion, banned = banned, nowMs = 1_000L)

    @Test fun ordinaryChatsMayReply() {
        assertNull(refuse())
        assertNull(refuse(isGroup = true))
        assertNull(refuse(startedBy = "me", accepted = false, lastSender = "me"))
    }

    @Test fun channelBlockedRequestAndAccountAreRefused() {
        assertEquals(NotifActionRules.CHANNEL, refuse(isGroup = true, isChannel = true))
        assertEquals(NotifActionRules.BLOCKED, refuse(blocked = true))
        assertEquals(NotifActionRules.REQUEST, refuse(startedBy = "x", accepted = false, lastSender = "x"))
        assertEquals(NotifActionRules.ACCOUNT, refuse(deletion = 5_000L))
        assertEquals(NotifActionRules.ACCOUNT, refuse(banned = true))
    }

    @Test fun requestsOffIsAnOrdinaryChat() {
        assertNull(refuse(startedBy = "x", accepted = false, reqOff = true, lastSender = "x"))
        assertNull(refuse(deletion = 500L))   // a deletion date already past
    }

    @Test fun readMarkIsTheShownLineAndOnlyForward() {
        assertEquals(2_000L, NotifActionRules.readMark(2_000L, null))
        assertEquals(2_000L, NotifActionRules.readMark(2_000L, 1_000L))
        assertNull(NotifActionRules.readMark(2_000L, 3_000L))
        assertNull(NotifActionRules.readMark(0L, null))
    }

    @Test fun followerReadGoesToEveryPost() {
        assertEquals(10, NotifActionRules.followerRead(4, 10))
        assertEquals(10, NotifActionRules.followerRead(null, 10))
        assertNull(NotifActionRules.followerRead(10, 10))
    }

    @Test fun reactionsAndAcceptsGetTheirOwnTag() {
        assertEquals("reaction-c1", NotifBuilder.noticeTag("reaction", "c1"))
        assertEquals("requestAccepted-c1", NotifBuilder.noticeTag("requestAccepted", "c1"))
        assertNull(NotifBuilder.noticeTag("text", "c1"))
        val d = NotifBuilder.decide(PushData.parse(mapOf("cid" to "a_b", "aid" to "b", "kind" to "reaction", "sn" to "B")),
            me = "a", chats = { null }, open = { _, _, _ -> null })
        assertTrue(d is com.fariin.app.push.NotifDecision.Show)
        assertEquals("reaction-a_b", (d as com.fariin.app.push.NotifDecision.Show).spec.noticeTag)
    }

    @Test fun requestFlagRidesOnTheSpec() {
        val d = NotifBuilder.decide(PushData.parse(mapOf("cid" to "a_b", "aid" to "b", "req" to "1", "sn" to "B")),
            me = "a", chats = { null }, open = { _, _, _ -> null }) as com.fariin.app.push.NotifDecision.Show
        assertTrue(d.spec.isRequest)
    }

    @Test fun earliestLiveTimer() {
        assertEquals(3_000L, NotifExpiry.earliestLive(listOf(0L, 5_000L, 3_000L, 500L), 1_000L))
        assertNull(NotifExpiry.earliestLive(listOf(0L, 500L), 1_000L))
    }

    @Test fun avatarTrimDropsOldestPastTheCap() {
        val items = listOf(AvatarTrim.Item("a", 10, 1), AvatarTrim.Item("b", 10, 3), AvatarTrim.Item("c", 10, 2))
        assertEquals(listOf("a"), AvatarTrim.toDelete(items, maxBytes = 25, maxFiles = 10))
        assertEquals(listOf("c", "a"), AvatarTrim.toDelete(items, maxBytes = 100, maxFiles = 1))
        assertTrue(AvatarTrim.toDelete(items).isEmpty())
    }
}
