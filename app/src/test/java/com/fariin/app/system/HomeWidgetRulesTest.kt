package com.fariin.app.system

import com.fariin.app.data.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeWidgetRulesTest {
    private val me = "me"
    private fun chat(id: String, at: Double, unread: Int = 0, name: String = "Name $id", other: String = "u$id") = Conversation(
        id = id, users = listOf(me, other), names = mapOf(other to name), lastMessageCipher = "x",
        updatedAtMillis = at, unreadCount = mapOf(me to unread),
    )

    @Test fun newestFirstCappedAndFiltered() {
        val convs = listOf(
            chat("a", 1.0), chat("b", 5.0, unread = 2), chat("c", 3.0),
            chat("arch", 9.0).copy(archivedBy = mapOf(me to true)),
            chat("blk", 8.0).copy(blockedBy = mapOf(me to true)),
            chat("req", 7.0).copy(startedBy = "ureq", accepted = false, lastSender = "ureq"),
            chat("d", 4.0), chat("e", 2.0),
        )
        assertEquals(listOf("b", "d", "c", "e"), HomeWidgetRules.choose(convs, me).map { it.id })
        assertEquals(listOf("b", "d"), HomeWidgetRules.choose(convs, me, HomeWidgetRules.SMALL_ROWS).map { it.id })
    }

    @Test fun unreadChatsCountsChatsNotMessages() {
        val convs = listOf(chat("a", 1.0, unread = 5), chat("b", 2.0, unread = 1), chat("c", 3.0),
            chat("blk", 4.0, unread = 3).copy(blockedBy = mapOf(me to true)))
        assertEquals(2, HomeWidgetRules.unreadChats(convs, me))
    }

    @Test fun snapshotKeepsNamesWordsAndCounts() {
        val s = HomeWidgetRules.snapshot(listOf(chat("a", 2.0, unread = 3, name = "Bobby")), me, locked = false,
            showPreview = true, now = 100) { "hello\nthere" }
        assertFalse(s.locked)
        assertEquals(1, s.unreadChats)
        assertEquals("Bobby", s.rows[0].name)
        assertEquals("hello there", s.rows[0].preview)
        assertEquals(3, s.rows[0].unread)
    }

    @Test fun appLockKeepsOnlyTheCountAndNeverOpensWords() {
        var opened = 0
        val s = HomeWidgetRules.snapshot(listOf(chat("a", 2.0, unread = 3)), me, locked = true, showPreview = true, now = 1) { opened++; "secret" }
        assertTrue(s.locked)
        assertTrue(s.rows.isEmpty())
        assertEquals(1, s.unreadChats)
        assertEquals(0, opened)
        assertFalse(HomeWidgetRules.encode(s).contains("Name"))
    }

    @Test fun previewOffKeepsNamesDropsWords() {
        var opened = 0
        val s = HomeWidgetRules.snapshot(listOf(chat("a", 2.0)), me, locked = false, showPreview = false, now = 1) { opened++; "secret" }
        assertEquals("Name a", s.rows[0].name)
        assertNull(s.rows[0].preview)
        assertEquals(0, opened)
    }

    @Test fun maskHidesWhatASwitchNowHides() {
        val s = HomeWidgetRules.snapshot(listOf(chat("a", 2.0, unread = 1)), me, false, true, 1) { "words" }
        val locked = HomeWidgetRules.masked(s, locked = true, showPreview = true)
        assertTrue(locked.locked); assertTrue(locked.rows.isEmpty()); assertEquals(1, locked.unreadChats)
        assertNull(HomeWidgetRules.masked(s, locked = false, showPreview = false).rows[0].preview)
        assertEquals(s, HomeWidgetRules.masked(s, false, true))
        // Written while locked: turning the lock off later does not bring back what was never kept.
        val keptLocked = s.copy(locked = true, rows = emptyList())
        assertTrue(HomeWidgetRules.masked(keptLocked, false, true).rows.isEmpty())
        assertEquals(HomeWidgetRules.Snapshot(), HomeWidgetRules.masked(s.copy(signedIn = false), false, true))
    }

    @Test fun pushBumpsTheRowUnlessTheLiveListJustWrote() {
        val s = HomeWidgetRules.snapshot(listOf(chat("a", 5.0), chat("b", 2.0, unread = 1)), me, false, true, now = 1_000) { "old" }
        assertNull(HomeWidgetRules.bump(s, "b", "new", true, now = 1_000 + 2_000))   // live list already has it
        val later = 1_000 + HomeWidgetRules.LIVE_GRACE_MS + 1
        val b = HomeWidgetRules.bump(s, "b", "new", true, later)!!
        assertEquals(listOf("b", "a"), b.rows.map { it.cid })
        assertEquals(2, b.rows[0].unread)
        assertEquals("new", b.rows[0].preview)
        assertEquals(1, b.unreadChats)   // b was already unread
        val a = HomeWidgetRules.bump(b, "a", null, showPreview = false, now = later)!!
        assertEquals(2, a.unreadChats)
        assertNull(a.rows[0].preview)
        assertNull(HomeWidgetRules.bump(b, "zzz", "x", true, later))
    }

    @Test fun rowsFillTheHeight() {
        assertEquals(2, HomeWidgetRules.rowsFor(130f))   // the small size
        assertTrue(HomeWidgetRules.compact(130f))
        assertEquals(3, HomeWidgetRules.rowsFor(213f))
        assertEquals(4, HomeWidgetRules.rowsFor(260f))
        assertEquals(4, HomeWidgetRules.rowsFor(600f))
        assertEquals(2, HomeWidgetRules.rowsFor(40f))
    }

    @Test fun codecRoundTripsAndLabelCaps() {
        val s = HomeWidgetRules.snapshot(listOf(chat("a", 5.0, unread = 120).copy(convType = "group", title = "Trip")), me, false, true, 42) { "hi" }
        assertEquals(s, HomeWidgetRules.decode(HomeWidgetRules.encode(s)))
        assertEquals(HomeWidgetRules.Snapshot(), HomeWidgetRules.decode("not json"))
        assertEquals("99+", HomeWidgetRules.countLabel(120))
        assertEquals("7", HomeWidgetRules.countLabel(7))
    }
}
