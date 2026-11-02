package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallsStoriesTest {
    private val me = "me"
    private val now = 1_800_000_000_000L
    private val min = 60_000L

    private fun call(id: String, who: String, mine: Boolean, outcome: String, ago: Long, video: Boolean = false, dur: Int = 0) =
        CallEntry(id, "c-$who", who, null, who, if (mine) me else who, outcome, video, dur, now - ago)

    @Test fun missedIsRedOnlyWhenTheyCalled() {
        assertTrue(call("1", "a", false, "missed", min).missedIncoming(me, now))
        assertFalse(call("2", "a", true, "missed", min).missedIncoming(me, now))
        assertTrue(call("3", "a", false, "declined", min).missed(now))
        // Still "ringing" after two minutes = nobody finalised it.
        assertTrue(call("4", "a", false, "ringing", 3 * min).missed(now))
        assertFalse(call("5", "a", false, "ringing", 1000).missed(now))
    }

    @Test fun missedFilterKeepsOnlyMissedIncoming() {
        val list = listOf(call("1", "a", false, "missed", min), call("2", "b", true, "missed", 2 * min), call("3", "c", false, "answered", 3 * min))
        assertEquals(listOf("1"), CallHistory.filter(list, me, true, now).map { it.id })
        assertEquals(3, CallHistory.filter(list, me, false, now).size)
    }

    @Test fun consecutiveSameKindCallsFold() {
        val list = listOf(
            call("1", "a", false, "missed", min), call("2", "a", false, "missed", 2 * min),
            call("3", "a", true, "answered", 3 * min), call("4", "b", false, "missed", 4 * min),
            call("5", "a", false, "missed", 5 * min),
        )
        assertEquals(listOf(2, 1, 1, 1), CallHistory.runs(list, me, now).map { it.entries.size })
    }

    @Test fun durationAndDirection() {
        assertEquals("Outgoing", CallHistory.directionText(call("1", "a", true, "missed", min), me, now))
        assertEquals("Missed", CallHistory.directionText(call("1", "a", false, "missed", min), me, now))
        assertEquals("Incoming", CallHistory.directionText(call("1", "a", false, "answered", min), me, now))
        assertEquals("42 sec", CallHistory.durationText(call("1", "a", false, "answered", min, dur = 42), 1, now))
        assertEquals("12 min", CallHistory.durationText(call("1", "a", false, "answered", min, dur = 754), 1, now))
        assertEquals("1 hr", CallHistory.durationText(call("1", "a", false, "answered", min, dur = 3600), 1, now))
        assertEquals("3 hr 5 min", CallHistory.durationText(call("1", "a", false, "answered", min, dur = 11_100), 1, now))
        assertNull(CallHistory.durationText(call("1", "a", false, "answered", min, dur = 42), 2, now))
    }

    @Test fun audienceTokenMatchesIos() {
        assertEquals("1f91cd6b1d213d3aac46cbd6d46ae2e7b78584e5a214bdfa29ed9420ab65c103", StoryAudienceToken.token("abc"))
    }

    @Test fun storiesGroupUnseenFirstAndDropExpired() {
        fun s(id: String, who: String, ago: Long, expired: Boolean = false) =
            Story(id, who, now - ago, if (expired) now - 1 else now + 60 * min, "u")
        val stories = listOf(
            s("1", "a", 50 * min), s("2", "a", 10 * min), s("3", "b", 5 * min), s("4", "c", 1 * min, expired = true),
            s("5", me, 2 * min),
        )
        val st = StoryList.build(
            me, stories, emptyList(), mapOf("a" to ("Amina" to null)),
            watermarks = mapOf("b" to now), myName = "Me", myPhoto = null, nowMs = now,
        )
        assertEquals(listOf("a", "b"), st.others.map { it.key })   // b seen, so after a
        assertEquals("Amina", st.others[0].name)
        assertEquals(listOf("1", "2"), st.others[0].stories.map { it.id })
        assertTrue(st.others[0].hasUnseen)
        assertFalse(st.others[1].hasUnseen)
        assertEquals("5", st.mine?.stories?.single()?.id)
    }

    @Test fun ago() {
        assertEquals("Just now", StoryList.ago(now - 30_000, now))
        assertEquals("5m ago", StoryList.ago(now - 5 * min, now))
        assertEquals("3h ago", StoryList.ago(now - 3 * 60 * min, now))
        assertEquals("1d ago", StoryList.ago(now - 25 * 60 * min, now))
    }
}
