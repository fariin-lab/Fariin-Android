package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StoryRingsTest {
    private fun story(id: String, by: String, at: Long, group: String? = null) =
        Story(id = id, authorUid = by, createdAtMs = at, expiresAtMs = at + 86_400_000, mediaUrl = "", groupId = group)
    private val dm = Conversation(id = "a_b", users = listOf("a", "b"))
    private val grp = Conversation(id = "g1", users = listOf("a", "b", "c"), convType = "group")

    @Test fun personRingWithSeenSplit() {
        val g = StoryGroup("b", "Bob", null, listOf(story("1", "b", 100), story("2", "b", 200)), lastViewedAtMs = 150)
        val s = StoriesState(loaded = true, others = listOf(g))
        assertEquals(g, StoryRings.forChat(dm, "a", s, emptySet()))
        assertEquals(listOf(true, false), StoryRings.seenFlags(g))
        assertNull(StoryRings.forChat(dm, "a", s, setOf("b")))   // hidden in Stories: no ring
    }

    @Test fun groupRingAndMyUploads() {
        val mine = StoryGroup("a", "You", null, listOf(story("m", "a", 300, group = "g1")))
        val ring = StoryRings.forChat(grp, "a", StoriesState(loaded = true, mine = mine), emptySet())
        assertEquals("g:g1", ring?.key)
        assertNull(StoryRings.forChat(grp, "a", StoriesState(loaded = true), emptySet()))
    }

    @Test fun geometryAsIos() {
        assertEquals(20, StoryRings.condensed(List(25) { false }).size)
        assertEquals(0f, StoryRings.gap(1, 58f, 2f))
        assertEquals((1f / 30) * 0.7f, StoryRings.gap(30, 10f, 2f), 1e-6f)
    }
}
