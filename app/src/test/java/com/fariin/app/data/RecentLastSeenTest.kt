package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** iOS RecentLastSeen: the search page's Recent line for a person. */
class RecentLastSeenTest {
    private val hidden = Presence.Seen("last seen recently", false)
    private val now = 10_000_000.0

    @Test fun onlineAndLastSeenWhenBothSidesAllow() {
        assertEquals(Presence.Seen("online", true), Presence.recentLine(mapOf(), mapOf(), false, true, true, null, now))
        assertEquals(Presence.Seen("last seen 5 min. ago", false),
            Presence.recentLine(mapOf(), mapOf(), false, true, false, now - 5 * 60_000, now))
    }

    @Test fun myLastSeenHiddenMeansRecentlyForEveryone() {
        assertEquals(hidden, Presence.recentLine(mapOf("lastSeen" to "nobody"), mapOf(), true, true, true, null, now))
        assertEquals(hidden, Presence.recentLine(mapOf(), mapOf(), true, true, true, null, now, legacySwitch = false))
    }

    @Test fun theirSettingRefusesMe() {
        assertEquals(hidden, Presence.recentLine(mapOf(), mapOf("lastSeen" to "nobody"), true, true, true, null, now))
        assertEquals(hidden, Presence.recentLine(mapOf(), mapOf("lastSeen" to "contacts"), false, true, true, null, now))
        assertEquals(Presence.Seen("online", true), Presence.recentLine(mapOf(), mapOf("lastSeen" to "contacts"), true, true, true, null, now))
    }

    @Test fun groupAndChannelRecentLineIsTheirCount() {
        // iOS Conversation.memberCountLabel; a channel counts its admins too (subscriberCount).
        assertEquals("1 member", GroupLinkCards.countLine(1, false))
        assertEquals("3 members", GroupLinkCards.countLine(3, false))
        assertEquals("1 follower", GroupLinkCards.countLine(com.fariin.app.channel.ChannelRules.subscriberCount(0, 1), true))
        assertEquals("6 followers", GroupLinkCards.countLine(com.fariin.app.channel.ChannelRules.subscriberCount(4, 1, 1), true))
    }

    @Test fun missingPresenceOrTimeReadsRecently() {
        assertEquals(hidden, Presence.recentLine(mapOf(), mapOf(), false, false, false, null, now))
        assertEquals(hidden, Presence.recentLine(mapOf(), mapOf(), false, true, false, null, now))
    }
}
