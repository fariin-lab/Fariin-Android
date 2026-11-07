package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLimitsTest {
    @Test fun wholeNumbersAboveZeroOnly() {
        assertEquals(5, AppLimits.number(emptyMap(), "pinned_chats_max", 5))
        assertEquals(8, AppLimits.number(mapOf("pinned_chats_max" to 8L), "pinned_chats_max", 5))
        assertEquals(5, AppLimits.number(mapOf("pinned_chats_max" to "8"), "pinned_chats_max", 5))
        assertEquals(5, AppLimits.number(mapOf("pinned_chats_max" to 8.0), "pinned_chats_max", 5))
        assertEquals(5, AppLimits.number(mapOf("pinned_chats_max" to 0L), "pinned_chats_max", 5))
        assertEquals(5, AppLimits.number(mapOf("pinned_chats_max" to -3L), "pinned_chats_max", 5))
    }

    @Test fun membersMaxClampedLikeTheServer() {
        assertEquals(30, AppLimits.membersMax(emptyMap()))
        assertEquals(2, AppLimits.membersMax(mapOf("group_members_max" to 1L)))
        assertEquals(500, AppLimits.membersMax(mapOf("group_members_max" to 9000L)))
        assertEquals(120, AppLimits.membersMax(mapOf("group_members_max" to 120L)))
    }

    @Test fun fallbacksWithoutFirebase() {
        assertEquals(5, AppLimits.pinnedChatsMax)
        assertEquals(10, AppLimits.groupsOwnedMax)
        assertEquals(30, AppLimits.groupMembersMax)
        assertEquals(200, AppLimits.groupDescMax)
    }

    @Test fun ownedCheckRefusesOnlyOnExplicitNo() {
        assertNull(AppLimits.ownedFull(null, 10))
        assertNull(AppLimits.ownedFull(mapOf("ok" to true, "count" to 3L, "max" to 10L), 10))
        assertNull(AppLimits.ownedFull(mapOf("count" to 3L), 10))
        assertEquals(12, AppLimits.ownedFull(mapOf("ok" to false, "count" to 12L, "max" to 12L), 10))
        assertEquals(10, AppLimits.ownedFull(mapOf("ok" to false), 10))
    }

    @Test fun sentencesAsIos() {
        assertEquals("You can own up to 10 groups. Delete one, or make someone else its owner, to create a new one.",
            AppLimits.ownedGroupsSentence(10))
        assertEquals("You can own up to 10 groups and channels. Delete one to create a new one.", AppLimits.ownedChannelsSentence(10))
        assertEquals("A group can have at most 30 members.", AppLimits.membersCapSentence(30))
    }

    @Test fun groupFullButNeverAChannel() {
        assertTrue(AppLimits.groupIsFull(false, 30, 30))
        assertFalse(AppLimits.groupIsFull(false, 29, 30))
        assertFalse(AppLimits.groupIsFull(true, 900, 30))
    }

    @Test fun refusedNamesJoinedAsIos() {
        assertEquals("Ali can't be added because of their privacy settings. You can send them the group link instead.",
            GroupRules.refusedSentence(listOf("Ali")))
        assertTrue(GroupRules.refusedSentence(listOf("Ali", "Sara")).startsWith("Ali and Sara can't"))
        assertTrue(GroupRules.refusedSentence(listOf("Ali", "Sara", "Omar", "Hodan")).startsWith("Ali and 3 others can't"))
    }

    @Test fun chatKeyLockout() {
        val now = 1_000_000L
        assertEquals("Too many attempts. Try again in 1 minute.", ChatKey.lockSentence(now + 10_000, now))
        assertEquals("Too many attempts. Try again in 1 minute.", ChatKey.lockSentence(now - 5, now))
        assertEquals("Too many attempts. Try again in 2 minutes.", ChatKey.lockSentence(now + 60_001, now))
        assertEquals("Too many attempts. Try again in 12 minutes.", ChatKey.lockSentence(now + 12 * 60_000, now))
        // lockDeadline is not tested here: FirebaseFunctionsException.Code needs Android to load.
        ChatKey.lockedUntilMs = now + 120_000
        assertEquals("Too many attempts. Try again in 2 minutes.", ChatKey.lockedNow(now))
        assertNull(ChatKey.lockedNow(now + 200_000))
        ChatKey.lockedUntilMs = 0L
    }
}
