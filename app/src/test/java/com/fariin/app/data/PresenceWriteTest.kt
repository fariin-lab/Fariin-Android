package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PresenceWriteTest {
    @Test fun sharingStampsNow() {
        assertEquals(mapOf("online" to true, "lastActive" to Presence.Stamp.NOW), Presence.fields(true, true, false))
        assertEquals(mapOf("online" to false, "lastActive" to Presence.Stamp.NOW), Presence.fields(false, true, true))
    }

    @Test fun switchToNobodyDeletesOnce() {
        assertEquals(mapOf("online" to false, "lastActive" to Presence.Stamp.DELETE), Presence.fields(true, false, true))
        assertEquals(mapOf("online" to false, "lastActive" to Presence.Stamp.DELETE), Presence.fields(true, false, null))
        assertEquals(mapOf("online" to false), Presence.fields(true, false, false))
    }

    @Test fun sharedBeforeReadsKey() {
        assertEquals(null, Presence.sharedBefore(null, "a"))
        assertEquals(null, Presence.sharedBefore("b|true|true", "a"))
        assertEquals(true, Presence.sharedBefore("a|false|true", "a"))
        assertEquals(false, Presence.sharedBefore("a|false|false", "a"))
    }
}
