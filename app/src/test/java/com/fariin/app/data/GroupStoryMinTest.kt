package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class GroupStoryMinTest {
    @Test fun theHigherOfCountryAndGlobal() {
        val l = mapOf<String, Any?>("group_story_min_members" to 50L, "group_story_countries" to mapOf("SO" to 80L, "KE" to 20L))
        assertEquals(80, GroupStoryRights.minMembers(l, "so"))
        assertEquals(50, GroupStoryRights.minMembers(l, "KE"))
        assertEquals(50, GroupStoryRights.minMembers(l, "US"))
        assertEquals(50, GroupStoryRights.minMembers(emptyMap(), "SO"))
        assertEquals(50, GroupStoryRights.minMembers(mapOf("group_story_countries" to "bad"), "SO"))
    }
}
