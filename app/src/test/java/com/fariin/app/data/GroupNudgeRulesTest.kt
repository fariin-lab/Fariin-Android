package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupNudgeRulesTest {
    private val push = mapOf(
        "type" to "groupnudge", "cid" to "g1", "callerUid" to "bob", "callerName" to "Bob",
        "title" to "Family", "video" to "true", "startedAt" to "1700000000000",
    )

    private fun route(
        n: GroupNudge? = GroupNudgeRules.parse(push), me: String = "me", pushOn: Boolean = true,
        blocked: Boolean = false, myCallCid: String? = null, callActive: Boolean? = true,
        mutedUntilMs: Long = 0, inFront: Boolean = true, locked: Boolean = false, preview: Boolean = true,
    ) = GroupNudgeRules.route(n, me, pushOn, blocked, myCallCid, callActive, mutedUntilMs, 1_000L, inFront, locked, preview)

    @Test fun parsesThePush() {
        val n = GroupNudgeRules.parse(push)!!
        assertEquals("g1", n.cid)
        assertEquals("Bob", n.callerName)
        assertEquals("Family", n.title)
        assertTrue(n.video)
        assertEquals(1_700_000_000_000L, n.startedAtMs)
    }

    @Test fun secondsBecomeMillisAndBlanksFallBack() {
        val n = GroupNudgeRules.parse(push + mapOf("startedAt" to "1700000000", "callerName" to "", "title" to "", "video" to "false"))!!
        assertEquals(1_700_000_000_000L, n.startedAtMs)
        assertEquals("Someone", n.callerName)
        assertNull(n.title)
        assertFalse(n.video)
        assertNull(GroupNudgeRules.parse(push + ("startedAt" to "0"))!!.startedAtMs)
    }

    @Test fun refusesOtherTypesAndBadIds() {
        assertNull(GroupNudgeRules.parse(push + ("type" to "linknudge")))
        assertNull(GroupNudgeRules.parse(push + ("cid" to "")))
        assertNull(GroupNudgeRules.parse(push + ("cid" to "a/b")))
        assertNull(GroupNudgeRules.parse(push + ("cid" to "adhoc_x")))
    }

    @Test fun extrasReadBackTheSame() {
        val n = GroupNudgeRules.parse(push)!!
        assertEquals(n, GroupNudgeRules.parse(n.extras()))
    }

    @Test fun skipsWhenNotWanted() {
        assertEquals(NudgeShow.NOTHING, route(n = null))
        assertEquals(NudgeShow.NOTHING, route(me = ""))
        assertEquals(NudgeShow.NOTHING, route(me = "bob"))
        assertEquals(NudgeShow.NOTHING, route(pushOn = false))
        assertEquals(NudgeShow.NOTHING, route(blocked = true))
        assertEquals(NudgeShow.NOTHING, route(myCallCid = "g1"))
        assertEquals(NudgeShow.NOTHING, route(callActive = false))
        assertEquals(NudgeShow.NOTHING, route(mutedUntilMs = 5_000L))
    }

    @Test fun showsByWhereTheAppIs() {
        assertEquals(NudgeShow.BANNER, route())
        assertEquals(NudgeShow.BANNER, route(callActive = null, myCallCid = "other", mutedUntilMs = 500L))
        assertEquals(NudgeShow.NOTIFICATION, route(inFront = false))
        assertEquals(NudgeShow.QUIET_NOTIFICATION, route(locked = true))
        assertEquals(NudgeShow.QUIET_NOTIFICATION, route(preview = false))
    }
}
