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

    @Test fun snapshotKeepsNamesOnly() {
        val s = HomeWidgetRules.snapshot(listOf(chat("a", 2.0, unread = 3, name = "Bobby")), me, locked = false, now = 100)
        assertTrue(s.signedIn); assertFalse(s.locked)
        assertEquals("a", s.rows[0].cid); assertEquals("Bobby", s.rows[0].name)
        // No message words and no counts in what is kept on disk.
        val kept = HomeWidgetRules.encode(s)
        assertFalse(kept.contains("pv")); assertFalse(kept.contains("\"u\""))
    }

    @Test fun appLockKeepsOnlyTheLock() {
        val s = HomeWidgetRules.snapshot(listOf(chat("a", 2.0, unread = 3)), me, locked = true, now = 1)
        assertTrue(s.locked); assertTrue(s.rows.isEmpty())
    }

    @Test fun maskHidesWhatTheLockNowHides() {
        val s = HomeWidgetRules.snapshot(listOf(chat("a", 2.0)), me, false, 1)
        val locked = HomeWidgetRules.masked(s, locked = true)
        assertTrue(locked.locked); assertTrue(locked.rows.isEmpty())
        assertEquals(s, HomeWidgetRules.masked(s, false))
        val keptLocked = HomeWidgetRules.snapshot(listOf(chat("a", 2.0)), me, true, 1)
        assertTrue(HomeWidgetRules.masked(keptLocked, false).rows.isEmpty())
        assertEquals(HomeWidgetRules.Snapshot(), HomeWidgetRules.masked(s.copy(signedIn = false), false))
    }

    @Test fun pushMovesTheChatUpUnlessTheLiveListJustWrote() {
        val s = HomeWidgetRules.snapshot(listOf(chat("a", 5.0), chat("b", 2.0)), me, false, now = 1_000)
        assertNull(HomeWidgetRules.bump(s, "b", now = 1_000 + 2_000))   // live list already has it
        val later = 1_000 + HomeWidgetRules.LIVE_GRACE_MS + 1
        val b = HomeWidgetRules.bump(s, "b", later)!!
        assertEquals(listOf("b", "a"), b.rows.map { it.cid })
        assertNull(HomeWidgetRules.bump(b, "b", later))     // already first
        assertNull(HomeWidgetRules.bump(b, "zzz", later))   // not on the widget
        assertNull(HomeWidgetRules.bump(HomeWidgetRules.snapshot(emptyList(), me, true, 1), "a", later))
    }

    @Test fun rowsFillTheHeight() {
        assertEquals(2, HomeWidgetRules.rowsFor(130f))   // the small size
        assertTrue(HomeWidgetRules.compact(130f))
        assertEquals(3, HomeWidgetRules.rowsFor(213f))
        assertEquals(4, HomeWidgetRules.rowsFor(260f))
        assertEquals(4, HomeWidgetRules.rowsFor(600f))
        assertEquals(2, HomeWidgetRules.rowsFor(40f))
    }

    @Test fun codecRoundTrips() {
        val s = HomeWidgetRules.snapshot(listOf(chat("a", 5.0, unread = 120).copy(convType = "group", title = "Trip")), me, false, 42)
        assertEquals(s, HomeWidgetRules.decode(HomeWidgetRules.encode(s)))
        assertEquals(HomeWidgetRules.Snapshot(), HomeWidgetRules.decode("not json"))
    }
}
