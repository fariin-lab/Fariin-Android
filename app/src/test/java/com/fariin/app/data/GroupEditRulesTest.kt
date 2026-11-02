package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupEditRulesTest {
    private val now = 1_000_000_000.0
    private fun group(vararg extra: Pair<String, Any?>): Map<String, Any?> =
        mapOf("users" to listOf("own", "adm", "mem"), "admins" to listOf("own", "adm"), "createdBy" to "own") + extra

    @Test fun ownerAndLegacyAdminMayEditMemberMayNot() {
        assertTrue(GroupRules.canEditInfo("own", group(), now))
        assertTrue(GroupRules.canEditInfo("adm", group(), now))
        assertFalse(GroupRules.canEditInfo("mem", group(), now))
        assertFalse(GroupRules.canEditInfo("stranger", group("membersCanEditInfo" to true), now))
        assertFalse(GroupRules.canEditInfo("", group(), now))
    }

    @Test fun adminNeedsChangeInfoWhenRightsAreWrittenOut() {
        assertFalse(GroupRules.canEditInfo("adm", group("adminRights" to mapOf("adm" to listOf("pinMessages"))), now))
        assertTrue(GroupRules.canEditInfo("adm", group("adminRights" to mapOf("adm" to listOf("changeInfo"))), now))
        // The owner holds every right whatever the map says.
        assertTrue(GroupRules.canEditInfo("own", group("adminRights" to mapOf("own" to emptyList<String>())), now))
    }

    @Test fun membersMayEditWhenTheSwitchIsOnUnlessRestricted() {
        val open = group("membersCanEditInfo" to true)
        assertTrue(GroupRules.canEditInfo("mem", open, now))
        val restricted = open + mapOf("restrictedFlags" to mapOf("mem" to listOf("changeInfo")),
            "restrictedUntil" to mapOf("mem" to now + 60_000))
        assertFalse(GroupRules.canEditInfo("mem", restricted, now))
        val expired = open + mapOf("restrictedFlags" to mapOf("mem" to listOf("changeInfo")),
            "restrictedUntil" to mapOf("mem" to now - 1))
        assertTrue(GroupRules.canEditInfo("mem", expired, now))
        val otherFlag = open + mapOf("restrictedFlags" to mapOf("mem" to listOf("sendText")),
            "restrictedUntil" to mapOf("mem" to now + 60_000))
        assertTrue(GroupRules.canEditInfo("mem", otherFlag, now))
    }

    @Test fun closedGroupsTakeNoEdits() {
        assertFalse(GroupRules.canEditInfo("own", group("mod" to mapOf("state" to "disabled")), now))
        assertFalse(GroupRules.canEditInfo("own", group("mod" to mapOf("state" to "ended")), now))
        assertFalse(GroupRules.canEditInfo("own", group("mod" to mapOf("state" to "suspended")), now))
        assertTrue(GroupRules.canEditInfo("own", group("mod" to mapOf("state" to "limited")), now))
    }

    @Test fun titleAndSystemLines() {
        assertEquals("Team", GroupRules.cleanTitle("  Team  "))
        assertEquals(100, GroupRules.cleanTitle("x".repeat(150)).length)
        assertEquals("Alice renamed the group to “Team”", GroupRules.renamedLine("Alice", "Team"))
        assertEquals("Someone removed the group photo", GroupRules.photoRemovedLine(""))
    }
}
