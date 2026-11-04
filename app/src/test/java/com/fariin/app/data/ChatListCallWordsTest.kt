package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** iOS MainShell call rows: "Missed" only for the person who was called. */
class ChatListCallWordsTest {
    @Test fun unansweredCallIPlacedIsNotMissed() {
        assertEquals("↗ Voice call", ChatList.callWords("📞 Missed call", mine = true))
        assertEquals("↗ Video call", ChatList.callWords("📹 Missed video call", mine = true))
    }

    @Test fun unansweredCallToMeIsMissed() {
        assertEquals("📞 Missed voice call", ChatList.callWords("📞 Missed call", mine = false))
        assertEquals("📹 Missed video call", ChatList.callWords("📹 Declined video call", mine = false))
    }

    @Test fun answeredCallNamesItsKind() {
        assertEquals("📞 Voice call", ChatList.callWords("📞 Call", mine = true))
        assertEquals("📹 Video call", ChatList.callWords("📹 Video call", mine = false))
    }

    @Test fun ordinaryTextIsLeftAlone() {
        assertNull(ChatList.callWords("hi", mine = true))
        assertFalse(ChatList.isCallMarker("Photo"))
        assertTrue(ChatList.isCallMarker("📞 Call"))
    }
}
