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
}
