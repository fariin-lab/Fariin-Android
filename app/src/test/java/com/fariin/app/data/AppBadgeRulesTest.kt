package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AppBadgeRulesTest {
    private val me = "me"
    private val none: (Conversation) -> Boolean = { false }
    private fun chat(id: String, n: Int) =
        Conversation(id = id, users = listOf(me, "x$id"), lastSender = "x$id", lastMessageCipher = "m",
            updatedAtMillis = 100.0, unreadCount = mapOf(me to n))

    @Test fun sumsMessagesNotChats() {
        assertEquals(7, AppBadgeRules.total(listOf(chat("a", 3), chat("b", 4), chat("c", 0)), me, incoming = none))
    }

    @Test fun mutedChatsCount() {
        val muted = chat("a", 2).copy(mutedBy = mapOf(me to Double.MAX_VALUE))
        assertEquals(2, AppBadgeRules.total(listOf(muted), me, incoming = none))
    }

    @Test fun archivedClearedBlockedLeftOut() {
        val archived = chat("a", 2).copy(archivedBy = mapOf(me to true))
        val cleared = chat("b", 3).copy(clearedAt = mapOf(me to 100.0))
        val blocked = chat("c", 5).copy(blockedBy = mapOf(me to true))
        assertEquals(1, AppBadgeRules.total(listOf(archived, cleared, blocked, chat("d", 1)), me, incoming = none))
    }

    @Test fun incomingRequestLeftOut() {
        val req = chat("r", 4)
        assertEquals(1, AppBadgeRules.total(listOf(req, chat("d", 1)), me, incoming = { it.id == "r" }))
    }

    @Test fun groupsOnlyWhileGroupsAreOn() {
        val g = Conversation(id = "g", users = listOf(me, "y"), convType = "group", updatedAtMillis = 1.0, unreadCount = mapOf(me to 6))
        assertEquals(6, AppBadgeRules.total(listOf(g), me, incoming = none))
        assertEquals(0, AppBadgeRules.total(listOf(g), me, groupsOn = false, incoming = none))
    }

    @Test fun markedUnreadAddsNothing() {
        val marked = chat("a", 0).copy(markedUnread = mapOf(me to true))
        val oldFlag = chat("b", -1)
        assertEquals(0, AppBadgeRules.total(listOf(marked, oldFlag), me, incoming = none))
    }

    @Test fun officialChatCountsOnce() {
        val off = chat("official", 2)
        assertEquals(3, AppBadgeRules.total(listOf(chat("a", 1)), me, official = off, incoming = none))
        assertEquals(3, AppBadgeRules.total(listOf(chat("a", 1), off), me, official = off, incoming = none))
    }

    @Test fun excludedChatAndSignedOut() {
        assertEquals(1, AppBadgeRules.total(listOf(chat("a", 1), chat("b", 9)), me, excluding = "b", incoming = none))
        assertEquals(0, AppBadgeRules.total(listOf(chat("a", 1)), "", incoming = none))
    }

    @Test fun pushAddsOneToLastKnown() {
        assertEquals(5, AppBadgeRules.afterPush(4, countsHere = true))
        assertEquals(4, AppBadgeRules.afterPush(4, countsHere = false))
        assertEquals(1, AppBadgeRules.afterPush(-3, countsHere = true))
    }
}
