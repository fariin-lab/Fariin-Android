package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/** The official chat's filter, rollout bucket, preview, buttons and unread against Kulan-iOS OfficialChannel. */
class OfficialChannelTest {
    private val now = 1_800_000_000_000.0
    private fun a(id: String = "a1", vararg kv: Pair<String, Any?>) =
        Announcement.from(id, mapOf<String, Any?>("title" to "T", "publishAt" to now - 1000, "createdAt" to now - 1000) + kv.toMap())

    @Test fun bucketMatchesTheHashRule() {
        // Recomputed by hand: SHA-256("salt.uid"), first 8 bytes big-endian, mod 1_000_000.
        val d = MessageDigest.getInstance("SHA-256").digest("a1.u1".toByteArray())
        var v = java.math.BigInteger.ZERO
        for (i in 0 until 8) v = v.shiftLeft(8).or(java.math.BigInteger.valueOf((d[i].toInt() and 0xFF).toLong()))
        assertEquals(v.mod(java.math.BigInteger.valueOf(1_000_000)).toInt(), Announcement.bucket("a1", "u1"))
        assertEquals(Announcement.bucket("x", "y"), Announcement.bucket("x", "y"))
        assertTrue((0 until 50).all { Announcement.bucket("ann$it", "u") in 0 until 1_000_000 })
    }

    @Test fun reachesRules() {
        assertTrue(a().reaches("u", 10, "SO", now))
        assertFalse(a(kv = arrayOf("deleted" to true)).reaches("u", 10, "SO", now))
        assertFalse(a(kv = arrayOf("publishAt" to now + 5)).reaches("u", 10, "SO", now))     // not due yet
        assertFalse(a(kv = arrayOf("expiresAt" to now)).reaches("u", 10, "SO", now))         // expired
        assertTrue(a(kv = arrayOf("expiresAt" to now + 1)).reaches("u", 10, "SO", now))
        // Build limits are iPhone builds: any limit keeps the post off Android, met or not.
        assertFalse(a(kv = arrayOf("audience" to mapOf("minBuild" to 11))).reaches("u", 10, "SO", now))
        assertFalse(a(kv = arrayOf("audience" to mapOf("minBuild" to 5))).reaches("u", 10, "SO", now))
        assertFalse(a(kv = arrayOf("audience" to mapOf("maxBuild" to 99))).reaches("u", 10, "SO", now))
        assertTrue(a(kv = arrayOf("audience" to mapOf("maxBuild" to 0))).reaches("u", 10, "SO", now))
        val countries = a(kv = arrayOf("audience" to mapOf("scope" to "countries", "countries" to listOf("SO", "GB"))))
        assertTrue(countries.reaches("u", 10, "GB", now))
        assertFalse(countries.reaches("u", 10, "US", now))
        assertFalse(countries.reaches("u", 10, null, now))
        assertFalse(a(kv = arrayOf("audience" to mapOf("scope" to "chosen"))).reaches("u", 10, "SO", now))
        // A personal copy skips country, chosen and rollout, but not the build or time checks.
        val personal = Announcement.from("p", mapOf("publishAt" to now - 1, "audience" to mapOf("scope" to "chosen", "ppm" to 0)), personal = true)
        assertTrue(personal.reaches("u", 10, null, now))
        assertFalse(personal.copy(audience = personal.audience.copy(minBuild = 99)).reaches("u", 10, null, now))
        // Rollout: in when the bucket is under ppm.
        val b = Announcement.bucket("a1", "u")
        assertTrue(a(kv = arrayOf("audience" to mapOf("ppm" to b + 1))).reaches("u", 10, "SO", now))
        assertFalse(a(kv = arrayOf("audience" to mapOf("ppm" to b))).reaches("u", 10, "SO", now))
    }

    @Test fun previewAndTimes() {
        assertEquals("Hello: line one line two", Announcement(id = "x", title = "Hello", body = "line one\nline two").preview)
        assertEquals("Hello", Announcement(id = "x", title = "Hello").preview)
        assertEquals("just body", Announcement(id = "x", body = "just body\n").preview)
        // sortAt falls back to createdAt; arrivedAt is the later of the two.
        assertEquals(5.0, Announcement(id = "x", createdAt = 5.0).sortAt, 0.0)
        assertEquals(9.0, Announcement(id = "x", publishAt = 3.0, createdAt = 9.0).arrivedAt, 0.0)
        assertEquals(3.0, Announcement(id = "x", publishAt = 3.0, createdAt = 9.0).sortAt, 0.0)
    }

    @Test fun buttonUsable() {
        val parsed = Announcement.from("x", mapOf("buttons" to listOf(
            mapOf("label" to "Open", "action" to "link", "value" to "https://fariin.com"),
            mapOf("label" to "", "action" to "link", "value" to "https://x"),          // no label: dropped
            mapOf("label" to "Odd", "action" to "teleport"),                          // unknown: dropped
            mapOf("label" to "Reject", "action" to "rejectOwnership", "value" to "g1", "until" to (now + 60_000).toLong()),
        ))).buttons
        assertEquals(2, parsed.size)
        assertTrue(parsed[0].isUsable(now))
        assertTrue(parsed[1].isUsable(now))
        assertFalse(parsed[1].isUsable(now + 60_000))                                  // window over
        assertFalse(AnnouncementButton("R", "rejectOwnership", "", now + 1).isUsable(now))
        assertFalse(AnnouncementButton("R", "rejectOwnership", "g1", null).isUsable(now))
        assertFalse(AnnouncementButton("L", "link", "fariin.com").isUsable(now))
        assertFalse(AnnouncementButton("U", "appStore", "").isUsable(now))             // iOS only
    }

    @Test fun visibleAndUnread() {
        val old = Announcement(id = "old", publishAt = now - 3000, createdAt = now - 3000)
        val mid = Announcement(id = "mid", publishAt = now - 2000, createdAt = now - 2000)
        val new = Announcement(id = "new", publishAt = now - 1000, createdAt = now - 1000)
        val gone = Announcement(id = "gone", publishAt = now - 500, createdAt = now - 500, deleted = true)
        val mine = Announcement(id = "gone", publishAt = now - 500, createdAt = now - 500, isPersonal = true)
        val st = OfficialChannelState(clearedAt = now - 2500, hiddenIds = setOf("mid"), lastReadAt = 0.0)
        val v = OfficialChannel.visible(listOf(new, old, mid, gone), listOf(mine), st, "u", 1, null, now)
        assertEquals(listOf("new"), v.map { it.id })    // old cleared, mid hidden, gone deleted from the broadcast
        val all = OfficialChannel.visible(listOf(new, old, mid), emptyList(), OfficialChannelState(), "u", 1, null, now)
        assertEquals(listOf("old", "mid", "new"), all.map { it.id })
        assertEquals(3, OfficialChannel.unreadCount(all, OfficialChannelState()))
        assertEquals(1, OfficialChannel.unreadCount(all, OfficialChannelState(lastReadAt = now - 2000)))
        assertEquals(now - 1000, OfficialChannel.readMark(all, OfficialChannelState(lastReadAt = now - 2000))!!, 0.0)
        assertNull(OfficialChannel.readMark(all, OfficialChannelState(lastReadAt = now)))
        // Broadcasts from before the account was made are not for it.
        assertEquals(listOf("new"), OfficialChannel.visible(listOf(new, old), emptyList(), OfficialChannelState(), "u", 1, null, now, joinedAt = now - 1500).map { it.id })
        // Watermarks never move back.
        val merged = OfficialChannelState(lastReadAt = 10.0, clearedAt = 10.0).merged(OfficialChannelState(lastReadAt = 5.0, clearedAt = 20.0, muted = true))
        assertEquals(10.0, merged.lastReadAt, 0.0); assertEquals(20.0, merged.clearedAt, 0.0); assertTrue(merged.muted)
    }
}
