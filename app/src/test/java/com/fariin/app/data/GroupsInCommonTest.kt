package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupsInCommonTest {
    private fun group(id: String, title: String, vararg users: String, channel: Boolean = false) =
        Conversation(id = id, users = users.toList(), convType = "group", title = title, isChannel = channel)

    @Test fun keepsOnlyGroupsWithBothOfUs() {
        val chats = listOf(
            group("a", "Both", "me", "them", "x"),
            group("b", "Only me", "me", "x"),
            group("c", "Only them", "them", "x"),
            Conversation(id = "d", users = listOf("me", "them")),
            group("e", "A channel", "me", "them", channel = true),
        )
        assertEquals(listOf("a"), GroupsInCommon.shared(chats, "me", "them").map { it.id })
    }

    @Test fun clearedGroupStillCounts() {
        val g = group("a", "Quiet", "me", "them").copy(clearedAt = mapOf("me" to 5.0), archivedBy = mapOf("me" to true))
        assertEquals(1, GroupsInCommon.shared(listOf(g), "me", "them").size)
    }

    @Test fun sortsByNameIgnoringCase() {
        val chats = listOf(group("1", "zebra", "me", "them"), group("2", "Apple", "me", "them"), group("3", "banana", "me", "them"))
        assertEquals(listOf("Apple", "banana", "zebra"), GroupsInCommon.shared(chats, "me", "them").map { it.title })
    }

    @Test fun nothingOnMyOwnPage() {
        assertTrue(GroupsInCommon.shared(listOf(group("a", "G", "me")), "me", "me").isEmpty())
    }

    @Test fun headerWords() {
        assertEquals("No Groups in Common", GroupsInCommon.header(0))
        assertEquals("1 Group in Common", GroupsInCommon.header(1))
        assertEquals("4 Groups in Common", GroupsInCommon.header(4))
    }

    @Test fun memberWords() {
        assertEquals("1 member", GroupsInCommon.memberLabel(group("a", "G", "me")))
        assertEquals("3 members", GroupsInCommon.memberLabel(group("a", "G", "me", "x", "y")))
    }

    @Test fun sixShownThenFiveAndSeeAll() {
        val (six, moreSix) = GroupsInCommon.shown((1..6).toList())
        assertEquals(6, six.size); assertFalse(moreSix)
        val (seven, moreSeven) = GroupsInCommon.shown((1..7).toList())
        assertEquals(listOf(1, 2, 3, 4, 5), seven); assertTrue(moreSeven)
    }
}
