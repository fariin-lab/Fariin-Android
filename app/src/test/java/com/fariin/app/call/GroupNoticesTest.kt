package com.fariin.app.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupNoticesTest {
    private fun p(key: String, name: String = key, photo: String? = null) = NoticePerson(key, name, photo)
    private fun room(vararg ps: NoticePerson) = ps.associateBy { it.key }

    @Test fun wording() {
        assertEquals("Ana joined", GroupNoticeQueue.make(listOf(p("1", "Ana")), "joined").text)
        assertEquals("Ana and bilan left", GroupNoticeQueue.make(listOf(p("2", "bilan"), p("1", "Ana")), "left").text)   // A to Z, any case
        assertEquals("Ana, Bilan and 1 other joined", GroupNoticeQueue.make(listOf(p("3", "Cali"), p("2", "Bilan"), p("1", "Ana")), "joined").text)
        assertEquals("Ana, Bilan and 3 others joined",
            GroupNoticeQueue.make(listOf("Ana", "Bilan", "Cali", "Dahir", "Eebla").mapIndexed { i, n -> p("$i", n) }, "joined").text)
        assertEquals("Someone joined", GroupNoticeQueue.make(listOf(p("1", "  ")), "joined").text)
        // The photo only for a notice about one person.
        assertEquals("1", GroupNoticeQueue.make(listOf(p("1", "Ana", "u")), "joined").avatar?.key)
        assertNull(GroupNoticeQueue.make(listOf(p("1"), p("2")), "joined").avatar)
    }

    @Test fun peopleAlreadyHereAreNotJoined() {
        val q = GroupNoticeQueue()
        assertFalse(q.update(room(p("a"), p("b")), connected = true))
        assertNull(q.next())
        assertTrue(q.update(room(p("a"), p("b"), p("c", "Cali")), connected = true))
        assertEquals("Cali joined", q.next()?.text)
        assertNull(q.next())
    }

    @Test fun joinsBeforeLeavesAndCancelOut() {
        val q = GroupNoticeQueue()
        q.update(room(p("a", "Ana")), true)
        q.update(room(p("b", "Bilan")), true)            // Ana left, Bilan joined
        assertEquals("Bilan joined", q.next()?.text)
        assertEquals("Ana left", q.next()?.text)
        assertNull(q.next())
        // Left and came back before the notice: nothing.
        q.update(room(), true)
        q.update(room(p("b", "Bilan")), true)
        assertNull(q.next())
    }

    @Test fun reconnectIsNobodyComingOrGoing() {
        val q = GroupNoticeQueue()
        q.update(room(p("a"), p("b")), true)
        assertFalse(q.update(room(), connected = false))
        assertFalse(q.update(room(p("a"), p("b"), p("c")), connected = true))   // back: the new baseline
        assertNull(q.next())
    }

    @Test fun freshNameForTheJoined() {
        val q = GroupNoticeQueue()
        q.update(room(), true)
        q.update(room(p("a", "Member")), true)
        q.update(room(p("a", "Ana")), true)
        assertEquals("Ana joined", q.next()?.text)
    }

    @Test fun ringBuzzFollowsRingerAndDoNotDisturb() {
        val all = android.app.NotificationManager.INTERRUPTION_FILTER_ALL
        assertTrue(RingVibration.wanted(android.media.AudioManager.RINGER_MODE_NORMAL, all))
        assertTrue(RingVibration.wanted(android.media.AudioManager.RINGER_MODE_VIBRATE, all))
        assertFalse(RingVibration.wanted(android.media.AudioManager.RINGER_MODE_SILENT, all))
        assertFalse(RingVibration.wanted(android.media.AudioManager.RINGER_MODE_NORMAL, android.app.NotificationManager.INTERRUPTION_FILTER_NONE))
        assertFalse(RingVibration.wanted(android.media.AudioManager.RINGER_MODE_NORMAL, android.app.NotificationManager.INTERRUPTION_FILTER_ALARMS))
    }

    @Test fun presenceTones() {
        val a = setOf("a"); val ab = setOf("a", "b")
        assertNull(CallToneRules.presenceTone(a, ab, 1000))                 // the room I walked into
        assertEquals(CallTone.GROUP_JOINED, CallToneRules.presenceTone(a, ab, 5000))
        assertEquals(CallTone.GROUP_LEFT, CallToneRules.presenceTone(ab, a, 5000))
        assertEquals(CallTone.GROUP_JOINED, CallToneRules.presenceTone(setOf("x"), a, 5000))   // one sound, the arrival
        assertNull(CallToneRules.presenceTone(a, a, 5000))
        assertNull(CallToneRules.presenceTone(emptySet(), (1..20).map { "$it" }.toSet(), 5000))   // past 20 people
    }
}
