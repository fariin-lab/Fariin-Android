package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The group page menu's link against Kulan-iOS GroupInfoView (mayShareLink, inviteLink). */
class GroupShareLinkTest {
    private val conv = mapOf<String, Any?>("createdBy" to "o", "admins" to listOf("a"), "inviteCode" to "old")

    @Test fun adminsAndOwnerShare() {
        assertEquals("https://fariin.com/g/new", GroupShareLink.of("a", conv, "new"))
        assertEquals("https://fariin.com/g/new", GroupShareLink.of("o", conv, "new"))
    }

    @Test fun memberOnlyWhileSharingIsOn() {
        assertNull(GroupShareLink.of("m", conv, "new"))
        assertEquals("https://fariin.com/g/new", GroupShareLink.of("m", conv + ("membersCanShareLink" to true), "new"))
    }

    @Test fun serverCodeFirstElseDocCode() {
        // nil from the server (not answered / failed) keeps the doc's code; "" means the group has none.
        assertEquals("https://fariin.com/g/old", GroupShareLink.of("a", conv, null))
        assertNull(GroupShareLink.of("a", conv, ""))
        assertNull(GroupShareLink.of("a", conv - "inviteCode", null))
        assertNull(GroupShareLink.of("a", conv, "a/b"))
    }

    @Test fun publicGroupMemberSharesThePublicLink() {
        val pub = conv + mapOf("isPublic" to true, "username" to "beach", "title" to "Beach Trip")
        assertEquals("https://fariin.com/beach", GroupShareLink.of("m", pub, "new"))
        assertEquals("https://fariin.com/g/new", GroupShareLink.of("a", pub, "new"))
        assertEquals("Join Beach Trip on Fariin: https://fariin.com/beach", GroupShareLink.text(pub, "https://fariin.com/beach"))
        assertEquals("https://fariin.com/g/new", GroupShareLink.text(pub, "https://fariin.com/g/new"))
        // Not public, or no name: a member still gets nothing.
        assertNull(GroupShareLink.of("m", conv + ("username" to "beach"), "new"))
        assertNull(GroupShareLink.of("m", conv + ("isPublic" to true), "new"))
    }
}
