package com.fariin.app.data

import com.fariin.app.data.GroupInfoCircles.CallTap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale

class GroupInfoCirclesTest {
    @Test fun muteChoicesFollowIos() {
        assertEquals(listOf("1 hour", "8 hours", "1 day", "1 week", "Always"), GroupInfoCircles.MUTE_CHOICES.map { it.first })
        assertEquals(1_000.0 + 8 * 3_600_000, GroupInfoCircles.muteUntil(8.0, 1_000), 0.0)
        assertEquals(GroupInfoCircles.FOREVER, GroupInfoCircles.muteUntil(null, 1_000), 0.0)
    }

    @Test fun mutedLabel() {
        val now = Calendar.getInstance().apply { set(2026, 10, 3, 10, 0, 0) }.timeInMillis
        assertEquals("Muted always", GroupInfoCircles.mutedLabel(GroupInfoCircles.FOREVER, now, true))
        assertEquals("Muted until 11:00", GroupInfoCircles.mutedLabel(now + 3_600_000.0, now, true, Locale.US))
        assertEquals("Muted until Nov 10, 10:00", GroupInfoCircles.mutedLabel(now + 7 * 86_400_000.0, now, true, Locale.US))
    }

    @Test fun mutedUntilReadsMyEntry() {
        val conv = mapOf<String, Any?>("mutedBy" to mapOf("me" to 5.0, "x" to 9.0))
        assertEquals(5.0, GroupInfoCircles.mutedUntil(conv, "me"), 0.0)
        assertEquals(0.0, GroupInfoCircles.mutedUntil(emptyMap(), "me"), 0.0)
    }

    @Test fun callTapOrder() {
        fun tap(mine: String? = null, other: Boolean = false, ended: Boolean = false, may: Boolean = true, live: Boolean = false, n: Int = 3) =
            GroupInfoCircles.callTap(mine, other, "g", ended, may, live, n)
        assertEquals(CallTap.Restore, tap(mine = "g", other = true))
        assertEquals(CallTap.Refuse("You're already on a call"), tap(mine = "h"))
        assertEquals(CallTap.Refuse("You're already on a call"), tap(other = true))
        assertEquals(CallTap.Refuse("This group has ended."), tap(ended = true))
        assertEquals(CallTap.Refuse("Only admins can start a call"), tap(may = false))
        assertEquals(CallTap.Go(start = false), tap(may = false, live = true))
        assertEquals(CallTap.Solo, tap(n = 1))
        assertEquals(CallTap.Go(start = true), tap(n = 0))
        assertEquals(CallTap.Go(start = true), tap())
    }

    @Test fun callReady() {
        assertTrue(GroupInfoCircles.callReady(null, false, "g", false, true, false))
        assertTrue(GroupInfoCircles.callReady("g", false, "g", false, false, true))
        assertFalse(GroupInfoCircles.callReady("h", false, "g", false, true, false))
        assertFalse(GroupInfoCircles.callReady(null, false, "g", true, true, false))
        assertFalse(GroupInfoCircles.callReady(null, false, "g", false, false, false))
    }
}
