package com.fariin.app.data

import com.google.firebase.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryPrivacyTest {
    private val contacts = setOf("a", "b", "c")

    @Test fun everyoneLeavesOutTheHiddenList() {
        val (who, mode) = StoryPrivacyRules.resolve(contacts, StoryAudienceChoice.EVERYONE, emptySet(), StoryPrivacyRules.defaultMyFriends, setOf("b"))
        assertEquals(setOf("a", "c"), who)
        assertEquals("except", mode)
    }

    @Test fun myChatsIgnoresTheHiddenListAndFollowsItsOwn() {
        val all = StoryPrivacyRules.resolve(contacts, StoryAudienceChoice.MY_CHATS, emptySet(), StoryPrivacyRules.defaultMyFriends, setOf("b"))
        assertEquals(contacts to "all", all)
        val except = StoryPrivacyRules.defaultMyFriends.copy(mode = "except", members = listOf("a"))
        assertEquals(setOf("b", "c") to "except", StoryPrivacyRules.resolve(contacts, StoryAudienceChoice.MY_CHATS, emptySet(), except, emptySet()))
        val only = StoryPrivacyRules.defaultMyFriends.copy(mode = "only", members = listOf("a", "zz"))
        assertEquals(setOf("a") to "only", StoryPrivacyRules.resolve(contacts, StoryAudienceChoice.MY_CHATS, emptySet(), only, emptySet()))
    }

    @Test fun aPickedListIsOnlyThosePeopleInMyChats() {
        val r = StoryPrivacyRules.resolve(contacts, StoryAudienceChoice.ONE_TIME, setOf("c", "x"), StoryPrivacyRules.defaultMyFriends, setOf("c"))
        assertEquals(setOf("c") to "only", r)
    }

    @Test fun repliesAndScreenshots() {
        val off = StoryPrivacyRules.defaultMyFriends.copy(allowReplies = false)
        assertFalse(StoryPrivacyRules.allowsReplies(StoryAudienceChoice.MY_CHATS, off))
        assertTrue(StoryPrivacyRules.allowsReplies(StoryAudienceChoice.EVERYONE, off))
        assertTrue(StoryPrivacyRules.captureProtected(StoryAudienceChoice.ONE_TIME, false))
        assertFalse(StoryPrivacyRules.captureProtected(StoryAudienceChoice.MY_CHATS, false))
        assertTrue(StoryPrivacyRules.captureProtected(StoryAudienceChoice.EVERYONE, true))
        assertEquals("Always on for a one-time story.", StoryPrivacyRules.captureFooter(StoryAudienceChoice.ONE_TIME))
    }

    @Test fun encodeAndDecodeTheIosShape() {
        val l = StoryListDoc("myFriends", "myFriends", "except", listOf("a"), allowReplies = false)
        val m = StoryPrivacyRules.encode(l)
        assertEquals(setOf("kind", "name", "mode", "members", "allowReplies", "emoji", "createdAt"), m.keys)
        assertEquals(Timestamp(java.util.Date(0)), m["createdAt"])
        assertEquals(l, StoryPrivacyRules.decode("myFriends", m))
        // Narrowed to nobody heals to all, as iOS.
        assertEquals("all", StoryPrivacyRules.decode("myFriends", mapOf("kind" to "myFriends", "mode" to "only", "members" to emptyList<String>()))!!.mode)
        assertNull(StoryPrivacyRules.decode("x", mapOf("mode" to "all")))
    }

    @Test fun wording() {
        assertEquals("All chats you accepted except 2", StoryPrivacyRules.myChatsSubtitle(StoryListDoc("myFriends", "myFriends", "except", listOf("a", "b"))))
        assertEquals("1 Viewer", StoryPrivacyRules.modeDetail(StoryPrivacyRules.defaultMyFriends, "all", 1))
        assertNull(StoryPrivacyRules.modeDetail(StoryPrivacyRules.defaultMyFriends, "except", 3))
        assertEquals("You can’t reply to this story", StoryPrivacyRules.REPLY_LOCKED)
        assertEquals(7, StoryPrivacyRules.REACTIONS.size)
    }

    private fun story(id: String, author: String = "a", label: String = "friends", oneTime: Boolean = false, replies: Boolean = true) =
        Story(id, author, 1L, 2L, "u", audienceLabel = label, oneTime = oneTime, allowsReplies = replies)

    @Test fun canReplyOnlyToAChatThatAllowsIt() {
        assertTrue(StoryPrivacyRules.canReply(story("1"), contacts))
        assertFalse(StoryPrivacyRules.canReply(story("1", replies = false), contacts))
        assertFalse(StoryPrivacyRules.canReply(story("1", author = "stranger"), contacts))
    }

    @Test fun mutesSplitAndTheIosString() {
        assertEquals(setOf("a", "b"), StoryMutes.parse("a  b "))
        assertEquals("a", StoryMutes.join(setOf("a")))
        val g1 = StoryGroup("a", "A", null, listOf(story("1", "a")))
        val g2 = StoryGroup("b", "B", null, listOf(story("2", "b")))
        val (shown, hidden) = StoryMutes.split(listOf(g1, g2), setOf("b"))
        assertEquals(listOf(g1), shown); assertEquals(listOf(g2), hidden)
    }

    @Test fun postedFoldersAndFilter() {
        val rows = listOf(story("1", label = "everyone"), story("2"), story("3", label = "custom", oneTime = true), story("4", label = "everyone"))
        val f = PostedStoriesRules.folders(rows)
        assertEquals(listOf("Everyone", "My Chats", "View once"), f.map { it.name })
        assertEquals(2, f[0].stories.size)
        assertEquals(3, rows.count { PostedStoriesRules.Filter.FRIENDS.matches(it) })
        assertEquals(1, rows.count { PostedStoriesRules.Filter.ONE_TIME.matches(it) })
    }
}
