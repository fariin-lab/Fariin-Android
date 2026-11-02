package com.fariin.app.data

import com.google.firebase.Timestamp
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadLogicTest {
    private val zone = ZoneId.of("UTC")
    private fun at(y: Int, mo: Int, d: Int, h: Int = 12, mi: Int = 0) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli().toDouble()
    private fun msg(id: String, author: String, t: Double, type: String? = null, state: Message.SendState? = null,
                    clientTs: Double? = null, server: Boolean = true) =
        Message(id = id, authorId = author, text = "x", type = type, createdAtMillis = t, sendState = state,
            clientTs = clientTs, hasServerTime = server)

    // ------------------------------------------------------------ ticks

    @Test fun ticksFollowReceipts() {
        val m = msg("1", "me", 1000.0)
        assertEquals(Tick.SENT, ThreadLogic.tick(m, "me", Receipts()))
        assertEquals(Tick.DELIVERED, ThreadLogic.tick(m, "me", Receipts(otherDeliveredMillis = 1000.0)))
        assertEquals(Tick.READ, ThreadLogic.tick(m, "me", Receipts(otherLastReadMillis = 1000.0, otherDeliveredMillis = 0.0)))
        assertEquals(Tick.SENT, ThreadLogic.tick(m, "me", Receipts(otherLastReadMillis = 999.0)))
    }

    @Test fun ticksSpecialCases() {
        val r = Receipts(otherLastReadMillis = 5000.0, otherDeliveredMillis = 5000.0)
        assertEquals(Tick.NONE, ThreadLogic.tick(msg("1", "them", 1.0), "me", r))
        assertEquals(Tick.NONE, ThreadLogic.tick(msg("1", "me", 1.0).copy(deleted = true), "me", r))
        assertEquals(Tick.SENDING, ThreadLogic.tick(msg("1", "me", 1.0, state = Message.SendState.SENDING), "me", r))
        assertEquals(Tick.FAILED, ThreadLogic.tick(msg("1", "me", 1.0, state = Message.SendState.FAILED), "me", r))
        assertEquals(Tick.SENT, ThreadLogic.tick(msg("1", "me", 1.0).copy(held = true), "me", r))
        // A block hides their receipts.
        assertEquals(Tick.SENT, ThreadLogic.tick(msg("1", "me", 1.0), "me", r.copy(iBlocked = true)))
    }

    @Test fun receiptsFromConversationDoc() {
        val cid = "aaa_bbb"
        val d = mapOf(
            "users" to listOf("aaa", "bbb"),
            "lastRead" to mapOf("bbb" to Timestamp(10, 0), "aaa" to Timestamp(99, 0)),
            "delivered" to mapOf("bbb" to 20_000L),
        )
        val r = Receipts.from(d, "aaa", cid)
        assertEquals(10_000.0, r.otherLastReadMillis, 0.0)
        assertEquals(20_000.0, r.otherDeliveredMillis, 0.0)
        // Group: the slowest other member; one who never read holds it at 0.
        val g = mapOf("users" to listOf("me", "x", "y"), "lastRead" to mapOf("x" to Timestamp(50, 0), "y" to Timestamp(30, 0)))
        assertEquals(30_000.0, Receipts.from(g, "me", "grp1").otherLastReadMillis, 0.0)
        val g2 = mapOf("users" to listOf("me", "x", "y"), "lastRead" to mapOf("x" to Timestamp(50, 0)))
        assertEquals(0.0, Receipts.from(g2, "me", "grp1").otherLastReadMillis, 0.0)
    }

    // ------------------------------------------------------------ dates and runs

    @Test fun dayLabels() {
        val now = at(2026, 11, 2, 15)
        assertEquals("Today", ThreadLogic.dayLabel(at(2026, 11, 2, 0, 5), now, zone, Locale.US))
        assertEquals("Yesterday", ThreadLogic.dayLabel(at(2026, 11, 1, 23), now, zone, Locale.US))
        assertEquals("Oct 20, 2026", ThreadLogic.dayLabel(at(2026, 10, 20), now, zone, Locale.US))
        assertEquals("May 1, 2026", ThreadLogic.dayLabel(at(2026, 5, 1), now, zone, Locale.US))
        assertEquals("March 3, 2019", ThreadLogic.dayLabel(at(2019, 3, 3), now, zone, Locale.US))
    }

    @Test fun runsAndSeparators() {
        val base = at(2026, 11, 2, 10)
        val items = listOf(
            msg("1", "a", base),
            msg("2", "a", base + 60_000),            // same run
            msg("3", "a", base + 7 * 60_000),        // more than 5 min: new run
            msg("4", "b", base + 8 * 60_000),        // other sender
            msg("5", "b", base + 8 * 60_000 + 1, type = "system"),
            msg("6", "b", at(2026, 11, 3, 9)),       // next day
        )
        val g = ThreadLogic.geometry(items, zone)
        assertTrue(g[0].showDate && g[0].first && !g[0].last)
        assertTrue(!g[1].first && g[1].last && g[1].gapDp == 2)
        assertTrue(g[2].first && g[2].last && g[2].gapDp == 14)
        assertTrue(g[3].first && g[3].last)
        assertTrue(g[4].first && g[4].last)
        assertTrue(g[5].showDate && g[5].gapDp == 0)
        assertFalse(g[1].showDate)
    }

    // ------------------------------------------------------------ order

    @Test fun orderIsServerTimeAndStableWhenAckLands() {
        val o = ThreadOrder()
        val a = msg("a", "x", 1000.0, clientTs = 900.0)
        val pending = Message(id = "cid1", clientId = "cid1", authorId = "me", text = "hi",
            createdAtMillis = 5000.0, clientTs = 5000.0, hasServerTime = false, sendState = Message.SendState.SENDING)
        assertEquals(listOf("a", "cid1"), o.sort(listOf(pending, a)).map { it.id })
        // The echo arrives with an earlier server stamp than another person's message that came
        // in meanwhile: the row keeps its place (sticky key).
        val b = msg("b", "x", 6000.0, clientTs = 5900.0)
        val echo = Message(id = "srv", clientId = "cid1", authorId = "me", text = "hi", createdAtMillis = 4000.0, clientTs = 5000.0)
        assertEquals(listOf("a", "srv", "b"), o.sort(listOf(b, echo, a)).map { it.id })
    }

    @Test fun sameAuthorKeepsTapOrder() {
        // A photo tapped first but stamped later stays above the text typed after it.
        val o = ThreadOrder()
        val photo = msg("p", "me", 3000.0, clientTs = 1000.0)
        val text = msg("t", "me", 2000.0, clientTs = 1500.0, server = false)
        assertEquals(listOf("p", "t"), o.sort(listOf(text, photo)).map { it.id })
    }

    // ------------------------------------------------------------ parsing

    @Test fun parseMessage() {
        val d = mapOf(
            "text" to "enc1:aaa:bbb", "authorId" to "u2", "createdAt" to Timestamp(100, 0),
            "clientTs" to 99_500.0, "clientId" to "C1",
            "replyTo" to mapOf("id" to "m0", "authorId" to "u1", "text" to "enc1:q:r"),
        )
        val m = Message.fromFirestore("id1", d, "u1_u2") { raw, author -> "open($raw,$author)" }
        assertEquals("open(enc1:aaa:bbb,u2)", m.text)
        assertEquals(100_000.0, m.createdAtMillis, 0.0)
        assertTrue(m.hasServerTime)
        assertEquals("C1", m.rowId)
        assertEquals("open(enc1:q:r,u2)", m.replyTo?.text)
    }

    @Test fun plainTextInOneToOneIsNotShown() {
        val m = Message.fromFirestore("i", mapOf("text" to "hello", "authorId" to "u2"), "u1_u2", nowMillis = 7.0) { r, _ -> r }
        assertEquals(Message.NOT_ENCRYPTED, m.text)
        assertFalse(m.hasServerTime)
        assertEquals(7.0, m.createdAtMillis, 0.0)
        // System lines are plain on purpose; a plain group sends in the clear.
        assertEquals("A added B", Message.fromFirestore("i", mapOf("text" to "A added B", "type" to "system"), "u1_u2") { r, _ -> r }.text)
        assertEquals("hello", Message.fromFirestore("i", mapOf("text" to "hello"), "grp") { r, _ -> r }.text)
    }

    @Test fun mediaLabels() {
        val base = Message(id = "1", authorId = "a", text = "", createdAtMillis = 0.0)
        assertEquals("Photo", base.copy(type = "image").bodyText)
        assertEquals("Voice message", base.copy(audioUrl = "x").bodyText)
        assertEquals("a.pdf", base.copy(type = "file", fileName = "a.pdf").bodyText)
        assertEquals("", base.copy(text = "hi", deleted = true).bodyText)
    }
}
