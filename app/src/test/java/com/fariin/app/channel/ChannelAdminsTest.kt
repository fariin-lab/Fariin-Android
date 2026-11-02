package com.fariin.app.channel

import com.fariin.app.channel.ChannelAdmins.Right
import com.fariin.app.channel.ChannelAdmins.Team
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelAdminsTest {
    private val doc = mapOf<String, Any?>(
        "createdBy" to "owner",
        "users" to listOf("owner", "legacy", "limited", "deputy", "byDeputy"),
        "admins" to listOf("owner", "legacy", "limited", "deputy", "byDeputy"),
        "adminRights" to mapOf(
            "limited" to listOf("deleteMessages"),
            "deputy" to listOf("changeInfo", "banUsers", "addAdmins"),
            "byDeputy" to listOf("changeInfo"),
        ),
        "adminBy" to mapOf("deputy" to "owner", "byDeputy" to "deputy", "limited" to "owner"),
        "names" to mapOf("owner" to "Zed", "legacy" to "bob", "limited" to "Amy", "deputy" to "Carl", "byDeputy" to "Dan"),
    )
    private val t = Team.from(doc)

    @Test fun rightsFollowTheModel() {
        // The owner can do everything; a legacy admin (no entry) all but Add admins; others what was granted.
        assertTrue(Right.entries.all { t.can("owner", it) })
        assertTrue(t.can("legacy", Right.BAN_USERS))
        assertFalse(t.can("legacy", Right.ADD_ADMINS))
        assertTrue(t.can("limited", Right.DELETE_MESSAGES))
        assertFalse(t.can("limited", Right.CHANGE_INFO))
        assertFalse(t.can("follower", Right.CHANGE_INFO))
        assertEquals(listOf(Right.CHANGE_INFO, Right.BAN_USERS, Right.ADD_ADMINS), t.rightsOf("deputy"))
    }

    @Test fun defaultsAreTheServersChannelRights() {
        // functions-groups lib/channels.js CHANNEL_ADMIN_RIGHTS, in order.
        assertEquals(listOf("changeInfo", "deleteMessages", "banUsers", "inviteUsers", "pinMessages", "postStories"),
            ChannelAdmins.defaults.map { it.slug })
        assertEquals("Remove followers", Right.BAN_USERS.label)
        assertEquals("Change channel info", Right.CHANGE_INFO.label)
    }

    @Test fun dismissMatchesTheServer() {
        assertTrue(t.canDismiss("owner", "legacy"))
        assertFalse(t.canDismiss("owner", "owner"))          // never myself
        assertFalse(t.canDismiss("deputy", "owner"))         // never the owner
        assertTrue(t.canDismiss("deputy", "byDeputy"))       // the one they brought in
        assertFalse(t.canDismiss("deputy", "limited"))       // brought in by the owner
        assertFalse(t.canDismiss("legacy", "byDeputy"))      // no Add admins
        assertFalse(t.canDismiss("owner", "follower"))       // not an admin
    }

    @Test fun rightsEditingFollowsTheRules() {
        assertTrue(t.canEditRights("owner", "limited"))
        assertTrue(t.canEditRights("deputy", "byDeputy"))
        assertFalse(t.canEditRights("deputy", "limited"))
        assertFalse(t.canEditRights("deputy", "deputy"))
        assertFalse(t.canEditRights("owner", "owner"))
        // A deputy hands out only what they hold.
        assertEquals(listOf(Right.CHANGE_INFO, Right.BAN_USERS, Right.ADD_ADMINS), t.grantable("deputy"))
    }

    @Test fun writeKeepsOrderAndOnlyGrantable() {
        val shown = t.grantable("deputy")
        assertEquals(listOf("changeInfo", "banUsers"), ChannelAdmins.toWrite(setOf("banUsers", "pinMessages", "changeInfo"), shown))
        assertTrue(ChannelAdmins.allOn(setOf("changeInfo", "banUsers", "addAdmins"), shown))
        assertFalse(ChannelAdmins.allOn(setOf("changeInfo"), shown))
        assertFalse(ChannelAdmins.allOn(emptySet(), emptyList()))
    }

    @Test fun ownerFirstThenByName() {
        assertEquals(listOf("owner", "limited", "legacy", "deputy", "byDeputy"), t.sorted())
    }

    @Test fun adminsFallBackToUsers() {
        val old = Team.from(mapOf("createdBy" to "o", "users" to listOf("o", "a")))
        assertEquals(listOf("o", "a"), old.admins)
        assertTrue(old.can("a", Right.CHANGE_INFO))
    }

    @Test fun followersHeaderCount() {
        assertEquals(4, ChannelAdmins.shownCount(3, 0, 1))
        assertEquals(3, ChannelAdmins.shownCount(3, 1, 1))
        assertEquals(1, ChannelAdmins.shownCount(0, 2, 1))
    }
}
