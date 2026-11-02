package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormerGroupsTest {
    private val group = mapOf<String, Any?>("type" to "group", "users" to listOf("alice", "bobby"),
        "admins" to listOf("alice", "bobby"), "typing" to mapOf("alice" to "text-1"), "title" to "Weekend Trip")

    @Test fun stripTakesMeOutOfMembersAndAdmins() {
        val s = FormerGroups.strip(group, "bobby")
        assertEquals(listOf("alice"), s["users"])
        assertEquals(listOf("alice"), s["admins"])
        assertEquals("Weekend Trip", s["title"])
        assertFalse(s.containsKey("typing"))
        assertEquals("1 member", GroupRules.memberCount((s["users"] as List<*>).size))
    }

    @Test fun formerOnlyForGroupsWithoutMe() {
        assertFalse(FormerGroups.isFormer("g1", group, "bobby"))
        assertTrue(FormerGroups.isFormer("g1", FormerGroups.strip(group, "bobby"), "bobby"))
        // A 1:1 is never "removed"; nor a channel I only follow; nor an empty copy.
        assertFalse(FormerGroups.isFormer("alice_bobby", mapOf("users" to listOf("alice")), "bobby"))
        assertFalse(FormerGroups.isFormer("c1", mapOf("channel" to true, "users" to listOf("alice")), "bobby"))
        assertFalse(FormerGroups.isFormer("g1", emptyMap(), "bobby"))
    }

    @Test fun serverAnswer() {
        assertTrue(FormerGroups.goneFromServer(false, null, "bobby"))
        assertTrue(FormerGroups.goneFromServer(true, listOf("alice"), "bobby"))
        assertFalse(FormerGroups.goneFromServer(true, listOf("alice", "bobby"), "bobby"))
    }

    @Test fun listedAgainWithMeIsBack() {
        FormerGroups.clear()
        val at = mapOf<String, Any?>("updatedAt" to 1000.0)
        FormerGroups.note("g1", FormerGroups.strip(group + at, "bobby"))
        // The list's old copy (still with me, not newer) is hidden and is not a return.
        val stale = Conversation.fromFirestore("g1", group + at)
        assertTrue(FormerGroups.hides(stale))
        FormerGroups.noteList(listOf(stale), "bobby")
        assertTrue(FormerGroups.get("g1") != null)
        // Added back: a newer copy with me.
        val fresh = Conversation.fromFirestore("g1", group + mapOf("updatedAt" to 2000.0))
        assertFalse(FormerGroups.hides(fresh))
        FormerGroups.noteList(listOf(fresh), "bobby")
        assertTrue(FormerGroups.get("g1") == null)
        assertFalse(FormerGroups.hides(stale))
    }
}

class GroupMembersTest {
    private val g = GroupMembers.Group(owner = "o", users = listOf("me", "zed", "o", "amy", "adm", "9x"),
        admins = listOf("o", "adm"), names = mapOf("o" to "Olga", "zed" to "Zed", "amy" to "Amy", "adm" to "Bert", "9x" to "99", "me" to "Me"),
        banned = emptyList())

    @Test fun ranks() {
        assertEquals("Owner", GroupMembers.rank(g, "o"))
        assertEquals("Admin", GroupMembers.rank(g, "adm"))
        assertEquals(null, GroupMembers.rank(g, "amy"))
    }

    @Test fun infoOrderMeFirstThenAdminsThenNames() {
        assertEquals(listOf("me", "adm", "o", "9x", "amy", "zed"), GroupMembers.infoOrder(g, "me"))
    }

    @Test fun sixShownElseFiveAndSeeAll() {
        val six = listOf("a", "b", "c", "d", "e", "f")
        assertEquals(six to false, GroupMembers.infoShown(six))
        assertEquals(listOf("a", "b", "c", "d", "e") to true, GroupMembers.infoShown(six + "g"))
    }

    @Test fun searchByNameHandleAndSort() {
        assertEquals(listOf("9x", "amy", "adm", "o", "me", "zed"), GroupMembers.search(g, "me", "").let { it })
        assertEquals(listOf("o", "adm", "9x", "amy", "me", "zed").first(), GroupMembers.search(g, "me", "", adminsFirst = true).first())
        assertEquals(listOf("o", "adm"), GroupMembers.search(g, "me", "", adminsFirst = true).take(2))
        assertEquals(listOf("amy"), GroupMembers.search(g, "me", "am"))
        assertEquals(listOf("zed"), GroupMembers.search(g, "me", "@zz", handles = mapOf("zed" to "zzTop")))
        assertEquals(listOf("me"), GroupMembers.search(g, "me", "you"))
    }

    @Test fun lettersAndSections() {
        assertEquals("E", GroupMembers.letter("Émile"))
        assertEquals("#", GroupMembers.letter("99"))
        assertEquals("#", GroupMembers.letter(""))
        val s = GroupMembers.sections(listOf("9x", "amy", "adm", "zed"), { g.names[it] ?: "" }, adminsFirst = false)
        assertEquals(listOf("A", "B", "Z", "#"), s.map { it.first })
        assertEquals(listOf("" to listOf("x")), GroupMembers.sections(listOf("x"), { "x" }, adminsFirst = true))
    }
}
