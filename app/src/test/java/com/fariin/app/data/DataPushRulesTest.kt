package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DataPushRulesTest {
    private val adhoc = mapOf("type" to "adhoccall", "roomId" to "adhoc_abc", "callerUid" to "bob", "t" to "Ann & Bob", "b" to "Ann is inviting you to a voice call")
    private val nudge = mapOf("type" to "groupnudge", "cid" to "g1", "callerUid" to "bob", "callerName" to "Bob",
        "title" to "Team", "t" to "Team", "b" to "Bob wants you back in the call")
    private val join = mapOf("type" to "joinrequest", "cid" to "g1", "t" to "Team", "b" to "Ann asked to join")
    private val bigCall = mapOf("type" to "groupcall", "cid" to "g1", "kind" to "groupcall", "t" to "Team", "b" to "Bob started a call")
    private val ring = mapOf("type" to "groupcall", "cid" to "g1", "kind" to "groupring")

    private fun route(d: Map<String, String?>, title: String? = null, body: String? = null, pushOn: Boolean = true) =
        DataPushRules.route(d, title, body, pushOn)

    @Test fun adhocRingsWithTheDataWords() {
        val r = route(adhoc)!!
        assertEquals(DataPushPost.ADHOC_RING, r.post)
        assertEquals("adhoc_abc", r.id)
        assertEquals("Ann & Bob", r.title)
        assertEquals("Ann is inviting you to a voice call", r.body)
    }

    @Test fun adhocWithNoWordsHasTheFallback() {
        val r = route(mapOf("type" to "adhoccall", "roomId" to "adhoc_abc", "kind" to "groupring"))!!
        assertEquals(AdhocInviteRules.FALLBACK_TITLE, r.title)
        assertEquals(AdhocInviteRules.FALLBACK_BODY, r.body)
    }

    @Test fun nudgeTakesDataWordsAndNotificationFirst() {
        val r = route(nudge)!!
        assertEquals(DataPushPost.GROUP_NUDGE, r.post)
        assertEquals("g1", r.id)
        assertEquals("Bob wants you back in the call", r.body)
        val n = route(nudge, title = "Team call", body = "Hi")!!
        assertEquals("Team call", n.title)
        assertEquals("Hi", n.body)
        val bare = route(nudge - "t" - "b")!!
        assertEquals("Team", bare.title)
        assertEquals("👋 Bob wants you back in the call", bare.body)
    }

    @Test fun joinRequestPostsItsOwnNotice() {
        val r = route(join)!!
        assertEquals(DataPushPost.JOIN_REQUEST, r.post)
        assertEquals("g1", r.id)
        assertEquals("Team", r.title)
        assertEquals("Ann asked to join", r.body)
        assertEquals("Request to join", route(join - "t")!!.title)
    }

    @Test fun groupCallRingsOnlyForTheRing() {
        assertEquals(DataPushPost.GROUP_RING, route(ring)!!.post)
        assertEquals(DataPushPost.GROUP_RING, route(ring, pushOn = false)!!.post)
        val r = route(bigCall)!!
        assertEquals(DataPushPost.GROUP_CALL_BANNER, r.post)
        assertEquals("Team", r.title)
        assertEquals("Bob started a call", r.body)
        assertEquals(DataPushPost.GROUP_CALL_BANNER, route(bigCall - "kind")!!.post)
    }

    @Test fun pushesOffShowNoNotice() {
        assertEquals(DataPushPost.IGNORE, route(join, pushOn = false)!!.post)
        assertEquals(DataPushPost.IGNORE, route(bigCall, pushOn = false)!!.post)
    }

    @Test fun badIdsAreIgnored() {
        assertEquals(DataPushPost.IGNORE, route(adhoc + ("roomId" to "g1"))!!.post)
        assertEquals(DataPushPost.IGNORE, route(nudge + ("cid" to "a/b"))!!.post)
        assertEquals(DataPushPost.IGNORE, route(join + ("cid" to ""))!!.post)
        assertEquals(DataPushPost.IGNORE, route(join - "cid")!!.post)
        assertEquals(DataPushPost.IGNORE, route(bigCall + ("cid" to "adhoc_x"))!!.post)
        assertEquals(DataPushPost.IGNORE, route(ring + ("cid" to "x/y"))!!.post)
    }

    @Test fun neverTheMessagePath() {
        // Every routed type gives a route (the caller returns after it), even broken or switched off.
        for (d in listOf(adhoc, nudge, join, bigCall, ring)) {
            for (bad in listOf(d, d - "cid" - "roomId", d + ("cid" to "a/b") + ("roomId" to "a/b"))) {
                for (on in listOf(true, false)) assertTrue(DataPushRules.route(bad, null, null, on) != null)
            }
            assertTrue(DataPushRules.owns(d["type"]))
        }
        assertNull(route(mapOf("cid" to "g1", "kind" to "message")))
        assertNull(route(mapOf("type" to "linkring")))
        assertFalse(DataPushRules.owns(null))
    }

    @Test fun silentAndRequestFlags() {
        for (d in listOf(adhoc, nudge, join, bigCall, ring)) {
            val plain = route(d)!!
            assertFalse(plain.silent)
            assertFalse(plain.request)
            val flagged = route(d + ("silent" to "1") + ("req" to "1"))!!
            assertTrue(flagged.silent)
            assertTrue(flagged.request)
            val other = route(d + ("silent" to "0") + ("req" to "true"))!!
            assertFalse(other.silent)
            assertFalse(other.request)
        }
        assertTrue(DataPushRules.route(join + ("silent" to "1") + ("cid" to ""), null, null, true)!!.silent)
        assertNotEquals(DataPushPost.IGNORE, route(join + ("req" to "1"))!!.post)
    }

    // The server's exact data-only payloads (all strings, no notification part).

    private fun ringKeys(cid: String, roomKind: String, callId: String) = mapOf(
        "kind" to "groupring", "cid" to cid, "roomKind" to roomKind, "roomId" to cid, "callId" to callId,
        "callerUid" to "bob", "callerName" to "Bob", "title" to "Team", "video" to "false",
        "startedAt" to "1763900000000", "sentAt" to "1763900120000")

    @Test fun shape1AdhocRing() {
        val d = ringKeys("adhoc_abc", "adhoc", "") + mapOf("type" to "adhoccall", "video" to "true",
            "t" to "Fariin", "b" to "Someone is inviting you to a call")
        val r = route(d)!!
        assertEquals(DataPushPost.ADHOC_RING, r.post)
        assertEquals("adhoc_abc", r.id)
        assertEquals("Fariin", r.title)
        assertEquals("Someone is inviting you to a call", r.body)
    }

    @Test fun shape2AdhocFallbackIsAPlainBannerNeverARing() {
        val r = route(mapOf("type" to "adhoccall", "roomId" to "adhoc_abc", "t" to "Ann & Bob", "b" to "Ann is inviting you to a call"))!!
        assertEquals(DataPushPost.ADHOC_NOTICE, r.post)
        assertEquals("adhoc_abc", r.id)
        assertEquals("Ann & Bob", r.title)
        assertEquals("Ann is inviting you to a call", r.body)
    }

    @Test fun shape3RingAgainRings() {
        val group = route(ringKeys("g1", "group", "rec9") + ("type" to "groupcall"))!!
        assertEquals(DataPushPost.GROUP_RING, group.post)
        assertEquals("g1", group.id)
        assertEquals(1763900120000L, group.sentAtMs)
        val multi = route(ringKeys("adhoc_abc", "adhoc", "rec9") + ("type" to "groupcall"))!!
        assertEquals(DataPushPost.ADHOC_RING, multi.post)
        assertEquals("adhoc_abc", multi.id)
        // A link room's ring is "linkring" (with its key); this push cannot ring it.
        assertEquals(DataPushPost.IGNORE, route(ringKeys("room1", "link", "rec9") + ("type" to "groupcall"))!!.post)
    }

    @Test fun shape4GroupNudgeTitleIsTheGroupsBannerUsesTB() {
        val d = mapOf("type" to "groupnudge", "cid" to "g1", "callerUid" to "bob", "callerName" to "Bob", "title" to "Team",
            "video" to "false", "startedAt" to "1763900000000", "t" to "Bob", "b" to "wants you back in the call")
        val r = route(d)!!
        assertEquals(DataPushPost.GROUP_NUDGE, r.post)
        assertEquals("g1", r.id)
        assertEquals("Bob", r.title)
        assertEquals("wants you back in the call", r.body)
        assertEquals("Team", GroupNudgeRules.parse(d)!!.title)
    }

    @Test fun shape5JoinRequest() {
        val r = route(mapOf("type" to "joinrequest", "cid" to "g1", "t" to "Team", "b" to "Ann asked to join"))!!
        assertEquals(DataPushPost.JOIN_REQUEST, r.post)
        assertEquals("g1", r.id)
        assertEquals("Ann asked to join", r.body)
    }

    @Test fun shape6SmallGroupRing() {
        val r = route(ringKeys("g1", "group", "rec9") + ("type" to "groupcall"))!!
        assertEquals(DataPushPost.GROUP_RING, r.post)
        // Told from a banner by the caller or room even without kind "groupring".
        assertEquals(DataPushPost.GROUP_RING, route(mapOf("type" to "groupcall", "cid" to "g1", "callerUid" to "bob"))!!.post)
        assertEquals(DataPushPost.GROUP_RING, route(mapOf("type" to "groupcall", "cid" to "g1", "roomId" to "g1"))!!.post)
    }

    @Test fun shape7BigGroupNoticeNeverRings() {
        val r = route(mapOf("type" to "groupcall", "cid" to "g1", "t" to "Team", "b" to "Bob started a call"))!!
        assertEquals(DataPushPost.GROUP_CALL_BANNER, r.post)
        assertEquals("g1", r.id)
        assertEquals("Bob started a call", r.body)
    }

    @Test fun shape8MessagePushGoesDownTheMessagePath() {
        val d = mapOf("mid" to "m1", "cid" to "a_b", "aid" to "a", "ackUid" to "b", "ack" to "tok", "expiresAt" to "0",
            "sn" to "Ann", "kind" to "text", "g" to "0", "sp" to "x", "ct" to "zz", "t" to "Ann", "b" to "Hi",
            "req" to "1", "silent" to "1")
        assertNull(route(d))
        assertFalse(DataPushRules.owns(d["type"]))
    }

    @Test fun shape9ChannelPostGoesDownTheMessagePath() {
        assertNull(route(mapOf("cid" to "ch1", "channel" to "1", "aid" to "ch1", "sn" to "News", "sp" to "x",
            "t" to "News", "b" to "Post", "silent" to "1")))
    }

    @Test fun clashKindIsTheMessageTypeWithNoTypeKey() {
        assertNull(route(mapOf("cid" to "g1", "kind" to "text", "callerUid" to "bob")))
        assertNull(route(mapOf("cid" to "g1", "kind" to "groupring")))
        assertEquals(DataPushPost.GROUP_CALL_BANNER, route(mapOf("type" to "groupcall", "cid" to "g1", "kind" to "text"))!!.post)
    }
}
