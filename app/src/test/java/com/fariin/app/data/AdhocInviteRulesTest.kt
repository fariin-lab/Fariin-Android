package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdhocInviteRulesTest {
    private val push = mapOf("type" to "adhoccall", "roomId" to "adhoc_abc")
    private val doc = mapOf<String, Any?>(
        "kind" to "adhoc", "active" to true, "startedBy" to "bob", "video" to true,
        "members" to listOf("ann", "me", "bob"),
        "names" to mapOf("ann" to "Ann", "bob" to "Bob", "me" to "Me"),
        "photos" to mapOf("bob" to "https://x/b.jpg", "ann" to ""),
    )

    private fun route(
        p: AdhocCallPush? = AdhocInviteRules.parse(push), me: String = "me", pushOn: Boolean = true,
        myCallId: String? = null, otherCallLive: Boolean = false, inFront: Boolean = true, locked: Boolean = false,
    ) = AdhocInviteRules.route(p, me, pushOn, myCallId, otherCallLive, inFront, locked)

    @Test fun parsesThePush() {
        val p = AdhocInviteRules.parse(push)!!
        assertEquals("adhoc_abc", p.roomId)
        assertEquals(p, AdhocInviteRules.parse(p.extras()))
    }

    @Test fun refusesOtherTypesAndBadIds() {
        assertNull(AdhocInviteRules.parse(push + ("type" to "groupnudge")))
        assertNull(AdhocInviteRules.parse(push + ("roomId" to "")))
        assertNull(AdhocInviteRules.parse(push + ("roomId" to "adhoc_")))
        assertNull(AdhocInviteRules.parse(push + ("roomId" to "g1")))
        assertNull(AdhocInviteRules.parse(push + ("roomId" to "adhoc_a/b")))
    }

    @Test fun skipsWhenNotWanted() {
        assertEquals(AdhocShow.NOTHING, route(p = null))
        assertEquals(AdhocShow.NOTHING, route(me = ""))
        assertEquals(AdhocShow.NOTHING, route(pushOn = false))
        assertEquals(AdhocShow.NOTHING, route(myCallId = "adhoc_abc"))
        assertEquals(AdhocShow.NOTHING, route(myCallId = "adhoc_abc", inFront = false))
    }

    @Test fun busyInFrontOnly() {
        assertEquals(AdhocShow.BUSY, route(myCallId = "g1"))
        assertEquals(AdhocShow.BUSY, route(otherCallLive = true))
        assertEquals(AdhocShow.NOTIFICATION, route(otherCallLive = true, inFront = false))
    }

    @Test fun showsByPlace() {
        assertEquals(AdhocShow.NOTIFICATION, route(inFront = false))
        assertEquals(AdhocShow.NOTIFICATION, route(inFront = false, locked = true))
        assertEquals(AdhocShow.QUIET_NOTIFICATION, route(locked = true))
        assertEquals(AdhocShow.INVITE, route())
    }

    @Test fun inviteHasTheOthersStarterFirst() {
        val i = AdhocInviteRules.invite("adhoc_abc", doc, "me") { false }!!
        assertEquals(listOf("bob", "ann"), i.others.map { it.uid })
        assertEquals("Bob & Ann", i.title)
        assertEquals("https://x/b.jpg", i.others[0].photo)
        assertNull(i.others[1].photo)
        assertTrue(i.video)
        assertEquals("bob", i.startedBy)
    }

    @Test fun noInviteWhenEndedNotMineOrBlocked() {
        assertNull(AdhocInviteRules.invite("adhoc_abc", null, "me") { false })
        assertNull(AdhocInviteRules.invite("adhoc_abc", doc + ("active" to false), "me") { false })
        assertNull(AdhocInviteRules.invite("adhoc_abc", doc, "zed") { false })
        assertNull(AdhocInviteRules.invite("adhoc_abc", doc, "") { false })
        assertNull(AdhocInviteRules.invite("adhoc_abc", doc, "me") { it == "bob" })
    }

    @Test fun joinedStillGetsTheInvite() {
        assertNotNull(AdhocInviteRules.invite("adhoc_abc", doc + ("joined" to listOf("me")), "me") { false })
    }

    @Test fun titlesAndWords() {
        assertEquals("Group call", AdhocInviteRules.title(emptyList()))
        assertEquals("A", AdhocInviteRules.title(listOf("A", " ")))
        assertEquals("A, B & 1 other", AdhocInviteRules.title(listOf("A", "B", "C")))
        assertEquals("A, B & 3 others", AdhocInviteRules.title(listOf("A", "B", "C", "D", "E")))
        assertTrue(AdhocInviteRules.video("Bob is inviting you to a video call"))
        assertFalse(AdhocInviteRules.video("Bob is inviting you to a voice call"))
        assertFalse(AdhocInviteRules.video(null))
    }
}
