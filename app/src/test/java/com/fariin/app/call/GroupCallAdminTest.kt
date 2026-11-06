package com.fariin.app.call

import com.fariin.app.data.GroupAdmins
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupCallAdminTest {
    private fun member(id: String, role: CallRole = CallRole.PARTICIPANT, micOn: Boolean = true, local: Boolean = false, blocked: Boolean = false) =
        GroupMember(id = id, name = id, speaking = false, micOn = micOn, video = null, role = role, local = local, micBlocked = blocked)

    @Test fun accessTable() {
        assertTrue(GroupCallAdmin.canModerate(CallRole.OWNER, CallRole.MODERATOR))
        assertTrue(GroupCallAdmin.canModerate(CallRole.OWNER, CallRole.PARTICIPANT))
        assertFalse(GroupCallAdmin.canModerate(CallRole.OWNER, CallRole.OWNER))
        assertTrue(GroupCallAdmin.canModerate(CallRole.MODERATOR, CallRole.PARTICIPANT))
        assertFalse(GroupCallAdmin.canModerate(CallRole.MODERATOR, CallRole.MODERATOR))
        assertFalse(GroupCallAdmin.canModerate(CallRole.MODERATOR, CallRole.OWNER))
        CallRole.entries.forEach { assertFalse(GroupCallAdmin.canModerate(CallRole.PARTICIPANT, it)) }
    }

    @Test fun neverMyself() {
        assertFalse(GroupCallAdmin.mayModerate("me", CallRole.OWNER, member("me")))
        assertFalse(GroupCallAdmin.mayModerate("x", CallRole.OWNER, member("me", local = true)))
        assertFalse(GroupCallAdmin.mayModerate("me", CallRole.OWNER, member("")))
        assertTrue(GroupCallAdmin.mayModerate("me", CallRole.OWNER, member("b")))
    }

    @Test fun groupRoleFollowsTheGroup() {
        val t = GroupAdmins.Team.from(mapOf(
            "createdBy" to "o", "users" to listOf("o", "a", "b", "c"), "admins" to listOf("o", "a", "b", "gone"),
            "adminRights" to mapOf("b" to listOf("pinMessages")),
        ))
        assertEquals(CallRole.OWNER, GroupCallAdmin.groupRole(t, "o"))
        assertEquals(CallRole.MODERATOR, GroupCallAdmin.groupRole(t, "a"))       // legacy admin: every right but addAdmins
        assertEquals(CallRole.PARTICIPANT, GroupCallAdmin.groupRole(t, "b"))     // admin without "Video chats"
        assertEquals(CallRole.PARTICIPANT, GroupCallAdmin.groupRole(t, "c"))
        assertEquals(CallRole.PARTICIPANT, GroupCallAdmin.groupRole(t, "gone"))  // left the group: no say
        assertNull(GroupCallAdmin.groupRole(null, "o"))
    }

    @Test fun requestNamesTheRoomAsIosDoes() {
        val g = GroupCallAdmin.room("cid1", null)!!
        assertEquals(mapOf("kind" to "group", "id" to "cid1"), g)
        assertEquals(mapOf("kind" to "link", "id" to "r1"), GroupCallAdmin.room("link:r1", "r1"))
        assertNull(GroupCallAdmin.room("link:r1", null))
        assertNull(GroupCallAdmin.room(null, null))
        assertEquals(mapOf("room" to g, "action" to "remove", "targetUid" to "u"), GroupCallAdmin.request(g, CallAdminAction.REMOVE, "u"))
        assertEquals(mapOf("room" to g, "action" to "muteAll"), GroupCallAdmin.request(g, CallAdminAction.MUTE_ALL, null))
        assertEquals(listOf("mute", "remove", "block", "end", "muteAll", "unmute"), CallAdminAction.entries.map { it.wire })
    }

    @Test fun muteAllAndEndBar() {
        val people = listOf(member("me", CallRole.OWNER, local = true), member("a"), member("b", micOn = false), member("c", CallRole.MODERATOR))
        assertEquals(listOf("a", "c"), GroupCallAdmin.mutable("me", CallRole.OWNER, people).map { it.id })
        assertTrue(GroupCallAdmin.showMuteAll("me", CallRole.OWNER, people))
        assertFalse(GroupCallAdmin.showMuteAll("me", CallRole.MODERATOR, people))   // only "a" left to mute
        assertFalse(GroupCallAdmin.showMuteAll("me", CallRole.PARTICIPANT, people))
        assertTrue(GroupCallAdmin.showEndForAll(CallRole.OWNER, joined = true))
        assertFalse(GroupCallAdmin.showEndForAll(CallRole.OWNER, joined = false))
        assertFalse(GroupCallAdmin.showEndForAll(CallRole.MODERATOR, joined = true))
    }

    @Test fun adminMuteIsNotSelfMute() {
        assertTrue(GroupCallAdmin.adminMuted(member("a", micOn = false, blocked = true)))
        assertFalse(GroupCallAdmin.adminMuted(member("a", micOn = false)))
        assertFalse(GroupCallAdmin.adminMuted(member("a", micOn = true, blocked = true)))
    }

    @Test fun blockOnlyForTheLinksOwner() {
        assertTrue(GroupCallAdmin.ownsLink(true, CallRole.OWNER, false))
        assertTrue(GroupCallAdmin.ownsLink(true, CallRole.PARTICIPANT, true))
        assertFalse(GroupCallAdmin.ownsLink(true, CallRole.MODERATOR, false))
        assertFalse(GroupCallAdmin.ownsLink(false, CallRole.OWNER, true))
        assertEquals("They can ask to join again.", GroupCallAdmin.removeMessage(true))
        assertEquals("They won't be able to rejoin it.", GroupCallAdmin.removeMessage(false))
    }

    @Test fun refusals() {
        assertEquals("Only the host can do that", GroupCallAdmin.errorText("PERMISSION_DENIED"))
        assertEquals("They're no longer in the call", GroupCallAdmin.errorText("NOT_FOUND"))
        assertEquals("Couldn't do that. Check your connection and try again.", GroupCallAdmin.errorText(null))
        assertEquals("Couldn't do that. Try again.", GroupCallAdmin.errorText("INTERNAL"))
        assertEquals("Amina declined this call", GroupCallAdmin.ringRefusal("declined", "Amina"))
        assertEquals("Can't ring Amina now", GroupCallAdmin.ringRefusal("weird", "Amina"))
        assertTrue(GroupCallAdmin.holdsWait("tooSoon"))
        assertFalse(GroupCallAdmin.holdsWait("declined"))
    }

    @Test fun ringAndInviteLines() {
        assertEquals("Ringing Amina", GroupCallAdmin.ringToast(listOf("Amina"), 1, null))
        assertEquals("Ringing 2 people", GroupCallAdmin.ringToast(listOf("A", "B", "C"), 2, "x"))
        assertEquals("x", GroupCallAdmin.ringToast(listOf("A"), 0, "x"))
        assertEquals("The call is full", GroupCallAdmin.inviteToast(2, 1, listOf("unavailable", "full")))
        assertEquals("They can't be added", GroupCallAdmin.inviteToast(1, 0, listOf("unavailable")))
        assertEquals("Already invited", GroupCallAdmin.inviteToast(1, 0, listOf("inCall")))
        assertNull(GroupCallAdmin.inviteToast(1, 1, emptyList()))
    }

    @Test fun addPeopleNotes() {
        val n = GroupCallAdmin.groupAddNotes(here = setOf("a", "me"), joined = setOf("a", "b", "r", "me"), removed = setOf("r"), me = "me",
            rangAt = mapOf("c" to 1_000L, "d" to 0L, "a" to 1_000L), now = 60_500L)
        assertEquals("In this call", n["a"])
        assertEquals("Left this call", n["b"])
        assertEquals("Ringing…", n["c"])
        assertNull(n["d"])   // rung over a minute ago: may be rung again
        assertNull(n["r"])   // removed by me: not "Left"
    }
}
