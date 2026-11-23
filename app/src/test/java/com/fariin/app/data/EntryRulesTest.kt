package com.fariin.app.data

import com.fariin.app.security.EntryRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EntryRulesTest {
    @Test fun percentDecode() {
        assertEquals("abc", EntryRules.percentDecode("abc"))
        assertEquals("a/b", EntryRules.percentDecode("a%2Fb"))
        assertEquals("a/b", EntryRules.percentDecode("a%2fb"))
        assertEquals("a+b", EntryRules.percentDecode("a+b"))
        assertEquals("é", EntryRules.percentDecode("%C3%A9"))
        assertNull(EntryRules.percentDecode("a%2"))
        assertNull(EntryRules.percentDecode("a%zz"))
        assertNull(EntryRules.percentDecode("%"))
    }

    @Test fun wchatOpensOnlyAWidgetChat() {
        val listed = setOf("c1", "c2")
        assertEquals("c1", EntryRules.wchatCid("c1", listed))
        assertNull(EntryRules.wchatCid("c3", listed))                 // not on the widget
        assertNull(EntryRules.wchatCid("c1", emptySet()))             // no widget list
        assertNull(EntryRules.wchatCid("a%2Fb", setOf("a/b", "a%2Fb"))) // decoded slash refused
        assertNull(EntryRules.wchatCid("..", setOf("..")))
        assertNull(EntryRules.wchatCid("%2E%2E", setOf("..")))
        assertNull(EntryRules.wchatCid("__x__", setOf("__x__")))
        assertNull(EntryRules.wchatCid("", setOf("")))
        assertNull(EntryRules.wchatCid("c%", listed))
        assertEquals("c2", EntryRules.wchatCid("%63%32", listed))
    }

    @Test fun launchIntentOnlyOnAFreshStart() {
        assertTrue(EntryRules.takeLaunchIntent(restored = false, fromHistory = false))
        assertFalse(EntryRules.takeLaunchIntent(restored = true, fromHistory = false))
        assertFalse(EntryRules.takeLaunchIntent(restored = false, fromHistory = true))
        assertFalse(EntryRules.takeLaunchIntent(restored = true, fromHistory = true))
    }

    @Test fun configChangeIsNotLeaving() {
        assertTrue(EntryRules.leftApp(0, 0, callInPip = false, stoppedIsCall = false, changingConfig = false))
        assertFalse(EntryRules.leftApp(0, 0, callInPip = false, stoppedIsCall = false, changingConfig = true))
        assertFalse(EntryRules.leftApp(1, 1, callInPip = false, stoppedIsCall = false, changingConfig = false))
        assertTrue(EntryRules.leftApp(1, 0, callInPip = true, stoppedIsCall = false, changingConfig = false))
        assertFalse(EntryRules.leftApp(1, 0, callInPip = true, stoppedIsCall = true, changingConfig = false))
        assertFalse(EntryRules.leftApp(1, 0, callInPip = true, stoppedIsCall = false, changingConfig = true))
    }

    @Test fun appLockGates() {
        assertTrue(EntryRules.chatShortcuts(appLockOn = false))
        assertFalse(EntryRules.chatShortcuts(appLockOn = true))
        assertTrue(EntryRules.mayStartLobby(appLockOn = false, unlocked = false))
        assertTrue(EntryRules.mayStartLobby(appLockOn = true, unlocked = true))
        assertFalse(EntryRules.mayStartLobby(appLockOn = true, unlocked = false))
        assertTrue(EntryRules.callExtrasShown(keyguardLocked = false))
        assertFalse(EntryRules.callExtrasShown(keyguardLocked = true))
    }

    @Test fun linkNudgeNeedsTheServerMark() {
        val now = 1_000_000_000L
        assertTrue(EntryRules.nudgeVerified(mapOf("u1" to now - 5_000L), "u1", now))
        assertTrue(EntryRules.nudgeVerified(mapOf("u1" to (now - 5_000L).toDouble()), "u1", now))
        assertFalse(EntryRules.nudgeVerified(mapOf("u2" to now), "u1", now))            // someone else's mark
        assertFalse(EntryRules.nudgeVerified(null, "u1", now))                           // no invite doc
        assertFalse(EntryRules.nudgeVerified(mapOf("u1" to "x"), "u1", now))
        assertFalse(EntryRules.nudgeVerified(mapOf("u1" to now), "", now))
        assertFalse(EntryRules.nudgeVerified(mapOf("u1" to now - EntryRules.NUDGE_MAX_AGE_MS - 1), "u1", now))  // too old
        assertTrue(EntryRules.nudgeVerified(mapOf("u1" to now + 30_000L), "u1", now))   // clock a little ahead
        assertFalse(EntryRules.nudgeVerified(mapOf("u1" to now + 120_000L), "u1", now))
    }
}
