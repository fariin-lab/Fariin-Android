package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DataPushRulesTest {
    private val adhoc = mapOf("type" to "adhoccall", "roomId" to "adhoc_abc", "t" to "Ann & Bob", "b" to "Ann is inviting you to a voice call")
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
        val r = route(mapOf("type" to "adhoccall", "roomId" to "adhoc_abc"))!!
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
}
