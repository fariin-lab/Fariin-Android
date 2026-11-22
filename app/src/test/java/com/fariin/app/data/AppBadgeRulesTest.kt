package com.fariin.app.data

import com.fariin.app.data.AppBadgeRules.Event
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

    @Test fun livelistNeverCountsAPushTwice() {
        // List live: the list already says 2 for Maya's 2 texts; her 2 pushes add nothing.
        var t = 2
        repeat(2) { t = AppBadgeRules.afterPush(t, countsHere = true, listLive = true) }
        assertEquals(2, t)
        // No list (the push started the app): 2 pushes from 0 make 2.
        var u = 0
        repeat(2) { u = AppBadgeRules.afterPush(u, countsHere = true, listLive = false) }
        assertEquals(2, u)
    }

    // Phone 2026-11-22: unread 0, Home, 8 s, a friend sends 2. The listener stays up behind Home,
    // so its snapshots set the number and the pushes add nothing: 2, not 3.
    @Test fun twoPushesWhileLiveStayAtListTotal() {
        var n = 0
        n = AppBadgeRules.next(n, Event.Push(countsHere = true, live = true))
        n = AppBadgeRules.next(n, Event.Snapshot(AppBadgeRules.total(listOf(chat("a", 1)), me, incoming = none)))
        n = AppBadgeRules.next(n, Event.Push(countsHere = true, live = true))
        n = AppBadgeRules.next(n, Event.Snapshot(AppBadgeRules.total(listOf(chat("a", 2)), me, incoming = none)))
        assertEquals(2, n)
        // A push that lands after its snapshot adds nothing either.
        assertEquals(2, AppBadgeRules.next(n, Event.Push(countsHere = true, live = true)))
    }

    @Test fun twoPushesWithNoListenerFromZeroMakeTwo() {
        var n = 0
        repeat(2) { n = AppBadgeRules.next(n, Event.Push(countsHere = true, live = false)) }
        assertEquals(2, n)
        // A push that does not count here (open chat, channel) adds nothing.
        assertEquals(2, AppBadgeRules.next(n, Event.Push(countsHere = false, live = false)))
    }

    @Test fun snapshotReplacesBumpedNumber() {
        var n = 0
        repeat(2) { n = AppBadgeRules.next(n, Event.Push(countsHere = true, live = false)) }
        // The app opens: the list says 1 (one was read elsewhere), and that wins.
        assertEquals(1, AppBadgeRules.next(n, Event.Snapshot(AppBadgeRules.total(listOf(chat("a", 1)), me, incoming = none))))
        assertEquals(3, AppBadgeRules.next(n, Event.Snapshot(3)))
        assertEquals(0, AppBadgeRules.next(n, Event.Snapshot(-1)))
    }
}
