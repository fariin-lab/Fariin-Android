package com.fariin.app.push

import com.fariin.app.data.OfficialChannelState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialPushTopicsTest {
    @Test fun topics() {
        assertEquals(setOf("sec_all", "sec_c_SO", "ann_all", "ann_c_SO"), OfficialPushTopics.wanted(false, true, true, "SO"))
        // Muted keeps the security topics.
        assertEquals(setOf("sec_all", "sec_c_SO"), OfficialPushTopics.wanted(true, true, true, "SO"))
        assertEquals(setOf("sec_all", "ann_all"), OfficialPushTopics.wanted(false, true, true, null))
        // Signed out or Show Notifications off: nothing.
        assertEquals(emptySet<String>(), OfficialPushTopics.wanted(false, false, true, "SO"))
        assertEquals(emptySet<String>(), OfficialPushTopics.wanted(false, true, false, "SO"))
    }

    @Test fun mutedNow() {
        assertTrue(OfficialChannelState(muted = true).isMutedNow(5.0))
        assertTrue(OfficialChannelState(muted = true, mutedUntil = 10.0).isMutedNow(5.0))
        assertFalse(OfficialChannelState(muted = true, mutedUntil = 10.0).isMutedNow(10.0))
        assertFalse(OfficialChannelState(muted = false).isMutedNow(5.0))
    }
}
