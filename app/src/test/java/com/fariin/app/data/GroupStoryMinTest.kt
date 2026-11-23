package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class GroupStoryMinTest {
    @Test fun onlyTheGlobalNumber() {
        val l = mapOf<String, Any?>("group_story_min_members" to 50L, "group_story_countries" to mapOf("SO" to 80L, "KE" to 20L))
        assertEquals(50, GroupStoryRights.minMembers(l))
        assertEquals(30, GroupStoryRights.minMembers(mapOf("group_story_min_members" to 30L, "group_story_countries" to mapOf("SO" to 80L))))
        assertEquals(50, GroupStoryRights.minMembers(emptyMap()))
        assertEquals(50, GroupStoryRights.minMembers(mapOf("group_story_countries" to mapOf("SO" to 20L))))
    }
}
