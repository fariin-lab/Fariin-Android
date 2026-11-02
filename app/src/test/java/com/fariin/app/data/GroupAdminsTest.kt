package com.fariin.app.data

import com.fariin.app.data.GroupAdmins.Right
import com.fariin.app.data.GroupAdmins.SendKind
import com.fariin.app.data.GroupAdmins.Team
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupAdminsTest {
    private fun group(extra: Map<String, Any?> = emptyMap()): Map<String, Any?> = mapOf(
        "type" to "group", "createdBy" to "o", "users" to listOf("o", "a", "b", "m"),
        "admins" to listOf("o", "a", "b"),
        "adminRights" to mapOf("a" to listOf("changeInfo", "addAdmins", "inviteUsers"), "b" to listOf("pinMessages")),
        "adminBy" to mapOf("b" to "a"),
        "names" to mapOf("o" to "Olga", "a" to "Ali", "b" to "Bea", "m" to "Mo"),
    ) + extra

    @Test fun ownerHoldsEverythingLegacyAdminTheDefaults() {
        val t = Team.from(group(mapOf("admins" to listOf("o", "a", "l"), "users" to listOf("o", "a", "l"))))
        assertTrue(Right.entries.all { t.can("o", it) })
        assertTrue(GroupAdmins.defaults.all { t.can("l", it) })
        assertFalse(t.can("l", Right.ADD_ADMINS))
        assertFalse(t.can("m", Right.CHANGE_INFO))
    }

    @Test fun grantedAdminOnlyWhatWasGranted() {
        val t = Team.from(group())
        assertTrue(t.can("b", Right.PIN_MESSAGES))
        assertFalse(t.can("b", Right.BAN_USERS))
        assertEquals(listOf(Right.CHANGE_INFO, Right.INVITE_USERS, Right.ADD_ADMINS), t.grantable("a"))
    }

    @Test fun whoMayManageWhom() {
        val t = Team.from(group())
        assertTrue(t.canManage("o", "a"))
        assertFalse(t.canManage("o", "o"))
        assertFalse(t.canManage("a", "o"))
        assertTrue(t.canManage("a", "m"))      // a member, to promote
        assertTrue(t.canManage("a", "b"))      // an admin a promoted
        assertFalse(t.canManage("b", "m"))     // no Add admins
        val legacy = Team.from(group(mapOf("adminRights" to mapOf<String, Any>())))
        assertFalse(legacy.canManage("a", "m"))   // Add admins needs an entry
    }

    @Test fun removeNeedsTheRightAndOnlyTheOwnerRemovesAdmins() {
        val t = Team.from(group(mapOf("adminRights" to mapOf("a" to listOf("banUsers")))))
        assertTrue(t.canRemove("a", "m"))
        assertFalse(t.canRemove("a", "b"))
        assertTrue(t.canRemove("o", "b"))
        assertFalse(t.canRemove("o", "o"))
        assertFalse(t.canRemove("m", "b"))
    }

    @Test fun closedGroupOffersNothing() {
        val t = Team.from(group(mapOf("mod" to mapOf("state" to "ended"))))
        assertFalse(t.may("o", Right.CHANGE_INFO))
        assertFalse(t.canManage("o", "a"))
    }

    @Test fun writesOnlyGrantableInModelOrder() {
        val w = GroupAdmins.toWrite(setOf("addAdmins", "banUsers", "changeInfo"), listOf(Right.CHANGE_INFO, Right.ADD_ADMINS))
        assertEquals(listOf("changeInfo", "addAdmins"), w)
        assertEquals(setOf("changeInfo", "inviteUsers"), GroupAdmins.startRights(listOf(Right.CHANGE_INFO, Right.INVITE_USERS, Right.ADD_ADMINS)))
    }

    @Test fun adminsOwnerFirst() {
        assertEquals(listOf("o", "a", "b"), Team.from(group()).sortedAdmins())
        assertEquals(listOf("m"), Team.from(group()).others())
    }

    @Test fun memberSendKindsAndTheOldMediaKey() {
        val off = group(mapOf("memberSend" to mapOf("media" to false, "files" to false)))
        assertFalse(GroupAdmins.maySend(off, "m", SendKind.PHOTOS))
        assertFalse(GroupAdmins.maySend(off, "m", SendKind.VIDEOS))
        assertFalse(GroupAdmins.maySend(off, "m", SendKind.FILES))
        assertTrue(GroupAdmins.maySend(off, "m", SendKind.VOICE))
        assertTrue(GroupAdmins.maySend(off, "b", SendKind.PHOTOS))   // admins always
        assertEquals("Admins turned off files in this group.", GroupAdmins.heldBack(off, "m", SendKind.FILES))
        assertEquals("Photos and videos are off in this group.", GroupAdmins.mediaOff(off, "m"))
        val newKey = group(mapOf("memberSend" to mapOf("media" to false, "photos" to true)))
        assertTrue(GroupAdmins.maySend(newKey, "m", SendKind.PHOTOS))
        assertNull(GroupAdmins.mediaOff(newKey, "m"))
        assertEquals(6, GroupAdmins.mediaOnCount(off))
    }

    @Test fun announcementModeAndCounts() {
        val d = group(mapOf("onlyAdminsSend" to true, "membersCanAdd" to true))
        assertEquals("Only admins can send messages", GroupAdmins.announcementBlock(d, "m"))
        assertNull(GroupAdmins.announcementBlock(d, "b"))
        assertEquals(2 to 4, GroupAdmins.permissionCount(d))
        assertEquals(1 to 3, GroupAdmins.permissionCount(d, groupCalls = false))
    }

    @Test fun slowMode() {
        val d = group(mapOf("slowMode" to 30))
        assertNull(GroupAdmins.slowWait(d, "a", 0.0, 1000.0))
        assertEquals("Slow mode is on. You can send again in 20 seconds.", GroupAdmins.slowWait(d, "m", 0.0, 10_000.0))
        assertNull(GroupAdmins.slowWait(d, "m", 0.0, 31_000.0))
        val h = group(mapOf("slowMode" to 900))
        assertEquals("Slow mode is on. You can send again in 14:59.", GroupAdmins.slowWait(h, "m", 0.0, 1000.0))
        assertEquals("set slow mode to 1 minute", GroupAdmins.slowNotice(60))
        assertEquals("set slow mode to 15 minutes", GroupAdmins.slowNotice(900))
        assertEquals("turned off slow mode", GroupAdmins.slowNotice(0))
        assertEquals(3, GroupAdmins.slowIndex(25))
        assertTrue(GroupAdmins.hasLink("see https://x.y"))
        assertFalse(GroupAdmins.hasLink("plain words"))
    }

    @Test fun linkStatusLines() {
        val now = 1_000_000_000.0
        val l = GroupLink("abcDEF2345", usageLimit = 10, usedCount = 3, expireAt = now + 2 * 3_600_000)
        assertEquals("3 of 10 joined · 2 hours left", l.statusLine(now))
        assertEquals(GroupLink.State.USED_UP, l.copy(usedCount = 10).state(now))
        assertEquals("No one joined · Revoked", GroupLink("abc", revoked = true).statusLine(now))
        assertEquals("fariin.com/g/abc", GroupLink("abc").shortUrl)
        assertNull(GroupLink.from(mapOf("code" to "bad/../x")))
        val list = GroupLinkList.from(mapOf("primary" to mapOf("code" to "p1", "isPrimary" to true), "links" to listOf(mapOf("code" to "x1")), "isOwner" to true))
        assertEquals("p1", list.primary?.code)
        assertEquals(1, list.links.size)
    }
}
