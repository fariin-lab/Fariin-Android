package com.fariin.app.data

import com.fariin.app.local.LocalJson
import com.google.firebase.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DisappearingTest {
    private fun msg(id: String = "m", at: Double = 1_000.0, exp: Double? = null, type: String? = null, secs: Int? = null) =
        Message(id = id, authorId = "a", text = "hi", type = type, createdAtMillis = at, expiresAtMillis = exp, disappearSeconds = secs)

    @Test fun labelsMatchIos() {
        assertEquals("Off", Disappearing.label(0))
        assertEquals("1 day", Disappearing.label(86_400))
        assertEquals("1 week", Disappearing.label(604_800))
        assertEquals("4 weeks", Disappearing.label(2_419_200))
        assertEquals("30 days", Disappearing.label(2_592_000))   // iOS "1 Month" is 30 days
        assertEquals("8 hours", Disappearing.label(28_800))
        assertEquals("30 seconds", Disappearing.label(30))
        assertEquals("90 seconds", Disappearing.label(90))
        assertEquals("1d", Disappearing.shortLabel(86_400))
        assertEquals("4w", Disappearing.shortLabel(2_419_200))
        assertEquals("5m", Disappearing.shortLabel(300))
        assertEquals("Off", Disappearing.shortLabel(0))
    }

    @Test fun newGroupChoices() {
        assertEquals(listOf("Off", "1 Day", "1 Week", "1 Month"), Disappearing.newGroupChoices.map { it.first })
        assertEquals("1 Month", Disappearing.newGroupLabel(2_592_000))
        assertEquals("8 Hours", Disappearing.newGroupLabel(28_800))
        assertEquals(8, Disappearing.presets.size)
        assertTrue(Disappearing.isPreset(300)); assertFalse(Disappearing.isPreset(2_592_000))
    }

    @Test fun lines() {
        assertEquals("Amina set disappearing message time to 1 day.", Disappearing.storedLine("Amina", 86_400))
        assertEquals("Someone turned off disappearing messages.", Disappearing.storedLine("Someone", 0))
        assertEquals("You set disappearing message time to 1 week.", Disappearing.noticeLine("You", 604_800))
        assertTrue(Disappearing.confirmText(86_400).contains("after 1 day."))
        for (t in listOf(Disappearing.pageHeader(false), Disappearing.pageHeader(true), Disappearing.NEW_GROUP_FOOTER,
            Disappearing.SETTINGS_FOOTER, Disappearing.confirmText(30))) assertFalse(t.contains('—'))
    }

    @Test fun customSeed() {
        assertEquals("Weeks" to 1, Disappearing.customSeed(604_800).let { it.first.name to it.second })
        assertEquals("Seconds" to 30, Disappearing.customSeed(5).let { it.first.name to it.second })
        assertEquals("Hours" to 8, Disappearing.customSeed(28_800).let { it.first.name to it.second })
        assertEquals("Days" to 30, Disappearing.customSeed(2_592_000).let { it.first.name to it.second })
    }

    @Test fun expiryUsesTheServerStampOnly() {
        assertFalse(Disappearing.isExpired(msg(exp = null), 9e12))
        assertFalse(Disappearing.isExpired(msg(exp = 5_000.0), 4_999.0))
        assertTrue(Disappearing.isExpired(msg(exp = 5_000.0), 5_000.0))
        assertEquals(6_000.0, Disappearing.nextExpiry(listOf(msg(exp = 3_000.0), msg(exp = 6_000.0), msg(exp = 9_000.0)), 4_000.0)!!, 0.0)
        assertNull(Disappearing.nextExpiry(listOf(msg(exp = 3_000.0)), 4_000.0))
    }

    @Test fun timerMarkFromTheFirstFrame() {
        val notice = msg(id = "n", at = 1_000.0, type = "system", secs = 86_400)
        val after = msg(id = "x", at = 2_000.0)
        val before = msg(id = "y", at = 500.0)
        val since = Disappearing.timerSince(listOf(before, notice, after))
        assertEquals(1_000.0, since!!, 0.0)
        assertEquals(2_000.0 + 86_400_000, Disappearing.shownExpiry(after, 86_400, since, 3_000.0)!!, 0.0)
        assertNull(Disappearing.shownExpiry(before, 86_400, since, 3_000.0))
        assertNull(Disappearing.shownExpiry(notice, 86_400, since, 3_000.0))
        assertNull(Disappearing.shownExpiry(after, 0, since, 3_000.0))
        // Turned off last: no since.
        assertNull(Disappearing.timerSince(listOf(notice, msg(id = "o", at = 3_000.0, type = "system", secs = 0))))
        // A server stamp always shows.
        assertEquals(7.0, Disappearing.shownExpiry(msg(exp = 7.0), 0, null, 3_000.0)!!, 0.0)
    }

    @Test fun fieldsReadAndSurviveTheSavedCopy() {
        val m = Message.fromFirestore("id1", mapOf("authorId" to "a", "text" to "", "type" to "system",
            "createdAt" to Timestamp(100, 0), "expiresAt" to Timestamp(200, 0), "disappearSeconds" to 86_400L), "g_x") { r, _ -> r }
        assertEquals(200_000.0, m.expiresAtMillis!!, 0.0)
        assertEquals(86_400, m.disappearSeconds)
        val back = LocalJson.decodeMessage(LocalJson.encodeMessage(m))!!
        assertEquals(m.expiresAtMillis, back.expiresAtMillis)
        assertEquals(86_400, back.disappearSeconds)
        assertNull(Message.fromFirestore("id2", mapOf("authorId" to "a", "text" to ""), "g_x") { r, _ -> r }.expiresAtMillis)
    }

    @Test fun listBurn() {
        val c = Conversation(id = "c", lastMessageCipher = "enc1:x", lastImageUrl = "u", lastExpiresAtMillis = 5_000.0)
        assertEquals("enc1:x", Disappearing.burnedIfDue(c, 4_000.0).lastMessageCipher)
        val b = Disappearing.burnedIfDue(c, 5_000.0)
        assertEquals("", b.lastMessageCipher); assertNull(b.lastImageUrl)
        assertEquals(5_000.0, Conversation.fromFirestore("c", mapOf("lastExpiresAt" to Timestamp(5, 0))).lastExpiresAtMillis!!, 0.0)
    }

    @Test fun whoMayChangeAGroupTimer() {
        val g = mapOf<String, Any?>("createdBy" to "o", "admins" to listOf("o", "a", "b"), "users" to listOf("o", "a", "b", "m"),
            "adminRights" to mapOf("b" to listOf("pinMessages")))
        assertTrue(Disappearing.canChangeInGroup(g, "o"))
        assertTrue(Disappearing.canChangeInGroup(g, "a"))    // legacy admin
        assertFalse(Disappearing.canChangeInGroup(g, "b"))   // admin without Change info
        assertFalse(Disappearing.canChangeInGroup(g, "m"))
        assertEquals("You need the Change info right to change this.", Disappearing.refusal(g, "b"))
        assertEquals("Only admins can change this.", Disappearing.refusal(g, "m"))
        assertFalse(Disappearing.canChangeInGroup(g + ("mod" to mapOf("state" to "ended")), "o"))
    }
}
