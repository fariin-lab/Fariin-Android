package com.fariin.app.data

import com.google.firebase.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Custom story lists, Edit Viewers / Share, and Get notifications: the pure parts. */
class StoryListsTest {

    @Test fun aCustomListIsWrittenInTheIosShape() {
        val l = StoryPrivacyRules.newCustom("ID-1", "  Family ", listOf("b", "a"), allowReplies = false, emoji = "🏠", nowMs = 5_000L)
        assertEquals("custom", l.kind)
        assertEquals("only", l.mode)
        assertEquals("Family", l.name)
        val m = StoryPrivacyRules.encode(l)
        assertEquals(setOf("kind", "name", "mode", "members", "allowReplies", "emoji", "createdAt"), m.keys)
        assertEquals("🏠", m["emoji"])
        assertEquals(Timestamp(java.util.Date(5_000L)), m["createdAt"])
        assertEquals(l, StoryPrivacyRules.decode("ID-1", m))
    }

    @Test fun nameLimitAndFooter() {
        assertEquals(25, StoryPrivacyRules.clampName("x".repeat(40)).length)
        assertEquals("Only you can see the name of this story.", StoryPrivacyRules.nameFooter("Family"))
        assertEquals("Only you can see the name of this story. 5 left.", StoryPrivacyRules.nameFooter("x".repeat(20)))
        assertEquals("Custom story · 1 viewer", StoryPrivacyRules.customSubtitle(StoryListDoc("i", "custom", members = listOf("a"))))
        assertEquals("Custom story · 2 viewers", StoryPrivacyRules.customSubtitle(StoryListDoc("i", "custom", members = listOf("a", "b"))))
    }

    @Test fun severalListsAreTheirUnionInMyChatsAndTheStrictestReplies() {
        val a = StoryListDoc("1", "custom", "only", listOf("x", "y"), allowReplies = true)
        val b = StoryListDoc("2", "custom", "only", listOf("y", "z", "gone"), allowReplies = false)
        assertEquals(setOf("x", "y", "z"), StoryPrivacyRules.listsRecipients(listOf(a, b), setOf("x", "y", "z")))
        assertFalse(StoryPrivacyRules.listsAllowReplies(listOf(a, b)))
        assertTrue(StoryPrivacyRules.listsAllowReplies(listOf(a)))
        // A named list beats the Everyone hide list (iOS appliesGlobalHide is Everyone alone).
        val (to, mode) = StoryPrivacyRules.resolve(setOf("x", "y"), StoryAudienceChoice.CUSTOM, setOf("x"),
            StoryPrivacyRules.defaultMyFriends, hidden = setOf("x"))
        assertEquals(setOf("x"), to)
        assertEquals("only", mode)
    }

    @Test fun listsToggleAndTheStoryNameFollowsARename() {
        assertEquals(setOf("1", "2"), StoryPrivacyRules.toggleList(setOf("1"), "2"))
        assertEquals(emptySet<String>(), StoryPrivacyRules.toggleList(setOf("1"), "1"))
        val lists = listOf(StoryListDoc("1", "custom", name = "Work"), StoryListDoc("2", "custom", name = "Home"))
        assertEquals("Work, Home", StoryPrivacyRules.storyName(StoryAudienceLink(listOf("1", "2"), "Old"), lists))
        // A list deleted since: the name at posting.
        assertEquals("Old", StoryPrivacyRules.storyName(StoryAudienceLink(listOf("1", "9"), "Old"), lists))
        assertNull(StoryPrivacyRules.storyName(null, lists))
    }

    @Test fun lettersAToZThenHash() {
        val s = StoryPrivacyRules.letterSections(listOf("bob", "Alice", "1two", "amy", "Émile")) { it }
        assertEquals(listOf("A", "B", "É", "#"), s.map { it.first })
        assertEquals(listOf("Alice", "amy"), s[0].second)
    }

    private fun story(label: String, oneTime: Boolean = false, shots: Boolean = false, group: String? = null) =
        Story("abc123XYZ0", "me", 1L, 2L, "u", audienceLabel = label, oneTime = oneTime, captureProtected = shots, groupId = group)

    @Test fun shareOnlyAnEveryoneStoryAndEditViewersNotOneTime() {
        assertTrue(PostedStoriesRules.canShare(story("everyone")))
        assertFalse(PostedStoriesRules.canShare(story("friends")))
        assertFalse(PostedStoriesRules.canShare(story("custom")))
        assertFalse(PostedStoriesRules.canShare(story("everyone", shots = true)))
        assertFalse(PostedStoriesRules.canShare(story("everyone", oneTime = true)))
        assertTrue(PostedStoriesRules.canEditViewers(story("friends")))
        assertFalse(PostedStoriesRules.canEditViewers(story("oneTime", oneTime = true)))
        assertFalse(PostedStoriesRules.canEditViewers(story("group", group = "g1")))
        assertEquals("https://fariin.com/carol/s/abc123XYZ0", PostedStoriesRules.storyLink("abc123XYZ0", "carol"))
        assertEquals("https://fariin.com/s/abc123XYZ0", PostedStoriesRules.storyLink("abc123XYZ0", " "))
    }

    @Test fun customFoldersByNameOnlyUnderAllCustom() {
        val rows = listOf(story("custom").copy(id = "1"), story("custom").copy(id = "2"))
        val names = mapOf("1" to "Work", "2" to "Home")
        assertEquals(listOf("Custom"), PostedStoriesRules.folders(rows) { names[it.id] }.map { it.name })
        assertEquals(listOf("Work", "Home"), PostedStoriesRules.folders(rows, mergeCustom = false) { names[it.id] }.map { it.name })
    }

    @Test fun getNotificationsWords() {
        assertEquals(mapOf("at" to "T", "author" to "u1"), StoryNotifyRules.doc("u1", "T"))
        assertEquals("Get notifications", StoryNotifyRules.menuTitle(false))
        assertEquals("Mute notifications", StoryNotifyRules.menuTitle(true))
        assertEquals(Triple("You will receive a notification whenever ", "Alice", " posts a story."), StoryNotifyRules.barParts(true, "Alice"))
        assertEquals(Triple("You will no longer receive a notification when ", "Alice", " posts a story."), StoryNotifyRules.barParts(false, "Alice"))
    }

    @Test fun fariinsRowSaysOfficialInsteadOfATime() {
        fun s(author: String) = Story("s", author, createdAtMs = 0L, expiresAtMs = 1L, mediaUrl = "m")
        val now = 2L * 3_600_000L
        assertEquals("Official", StoryList.subtitle(StoryGroup(OfficialChannel.CID, "Fariin", null, listOf(s(OfficialChannel.CID))), now))
        assertEquals("2h ago", StoryList.subtitle(StoryGroup("u1", "Alice", null, listOf(s("u1"))), now))
        assertEquals("", StoryList.subtitle(StoryGroup("u1", "Alice", null, emptyList()), now))
    }
}
