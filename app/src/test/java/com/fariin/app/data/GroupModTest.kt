package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupModTest {
    private val group = mapOf<String, Any?>("type" to "group", "createdBy" to "o", "users" to listOf("o", "m"), "admins" to listOf("o"))

    @Test fun endedAtReadsAsEnded() {
        val ended = group + ("endedAt" to 1_000L)
        assertEquals("ended", GroupMod.state(ended))
        assertTrue(GroupMod.blocksSending(ended))
        assertEquals("This group has ended.", GroupMod.sendBlock(ended))
        assertTrue(GroupAdmins.Team.from(ended).closed)
        assertFalse(GroupRules.canEditInfo("o", ended))
        assertFalse(Disappearing.canChangeInGroup(ended, "o"))
    }

    @Test fun openGroupSendsFreely() {
        assertNull(GroupMod.sendBlock(group))
        assertNull(GroupMod.sendBlock(group + ("mod" to mapOf("state" to "limited"))))
    }

    @Test fun suspensionWords() {
        val now = 1_000_000.0
        assertEquals("This group is suspended for spam.",
            GroupMod.sendBlock(group + ("mod" to mapOf("state" to "suspended", "reason" to "spam")), now))
        assertEquals("This group was closed for breaking the Fariin rules.",
            GroupMod.sendBlock(group + ("mod" to mapOf("state" to "disabled")), now))
        assertEquals("Closed by Fariin.", GroupMod.sendBlock(group + ("mod" to mapOf("state" to "disabled", "notice" to "Closed by Fariin.")), now))
        // A timed suspension that is over no longer blocks.
        assertNull(GroupMod.sendBlock(group + ("mod" to mapOf("state" to "suspended", "until" to 10L)), now))
    }

    @Test fun memberRestrictionSaysForYou() {
        val now = 1_000.0
        val d = group + ("restrictedFlags" to mapOf("m" to listOf("sendPhotos", "sendReactions"))) +
            ("restrictedUntil" to mapOf("m" to 5_000.0))
        assertEquals("An admin has turned off photos for you.", GroupAdmins.heldBack(d, "m", GroupAdmins.SendKind.PHOTOS, now))
        assertNull(GroupAdmins.heldBack(d, "m", GroupAdmins.SendKind.FILES, now))
        assertNull(GroupAdmins.heldBack(d, "o", GroupAdmins.SendKind.PHOTOS, now))
        assertTrue(GroupAdmins.reactionsHeldBack(d, "m", now))
        assertEquals("An admin has turned off photos and videos for you.", GroupAdmins.mediaOff(d, "m", now))
    }
}
