package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupStoryRepliesTest {
    private val group = Conversation(id = "g1", users = listOf("own", "adm", "mem"), convType = "group",
        admins = listOf("adm"), createdBy = "own")
    private val channel = Conversation(id = "c1", users = listOf("own"), convType = "group", isChannel = true, createdBy = "own")
    private val convs = listOf(group, channel)

    private fun story(gid: String? = "g1", author: String = "own", replies: Boolean = true) =
        Story("s1", author, 0, 1, "u", groupId = gid, allowsReplies = replies)

    @Test fun payloadHoldsOnlyTheRulesFields() {
        assertEquals(mapOf("uid" to "me", "text" to "hi"), GroupStoryReplies.payload("me", "  hi  ", ""))
        assertEquals(mapOf("uid" to "me", "emoji" to "🔥"), GroupStoryReplies.payload("me", "", "🔥"))
        assertNull(GroupStoryReplies.payload("me", "   ", ""))
        assertNull(GroupStoryReplies.payload("", "hi", ""))
        assertEquals(1000, (GroupStoryReplies.payload("me", "x".repeat(1200), "")!!["text"] as String).length)
    }

    @Test fun ownerAndAdminsSeeViewsMembersReply() {
        assertTrue(GroupStoryRules.canSeeViews(story(), "own", convs))
        assertTrue(GroupStoryRules.canSeeViews(story(author = "x"), "adm", convs))
        assertFalse(GroupStoryRules.canSeeViews(story(), "mem", convs))
        assertTrue(GroupStoryRules.memberCanReply(story(), "mem", convs))
        assertFalse(GroupStoryRules.memberCanReply(story(), "adm", convs))
        assertFalse(GroupStoryRules.memberCanReply(story(replies = false), "mem", convs))
        assertFalse(GroupStoryRules.memberCanReply(story(gid = "c1"), "mem", convs))
    }

    @Test fun repliesTabOnlyOnAGroupWithRepliesOn() {
        assertTrue(GroupStoryRules.hasRepliesTab(story(), convs))
        assertFalse(GroupStoryRules.hasRepliesTab(story(replies = false), convs))
        assertFalse(GroupStoryRules.hasRepliesTab(story(gid = "c1"), convs))
        assertFalse(GroupStoryRules.hasRepliesTab(story(gid = null), convs))
    }

    @Test fun writerOrStaffDeletes() {
        val r = GroupStoryReply("r", "mem", "hi", "", 0)
        assertTrue(GroupStoryRules.canDelete(r, "g1", "mem", convs))
        assertTrue(GroupStoryRules.canDelete(r, "g1", "own", convs))
        assertTrue(GroupStoryRules.canDelete(r, "g1", "adm", convs))
        assertFalse(GroupStoryRules.canDelete(r, "g1", "other", convs))
    }
}
