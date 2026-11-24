package com.fariin.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeptChatRulesTest {
    @Test fun aChatInTheStackIsAlwaysComposed() {
        assertTrue(KeptChatRules.composed(inStack = true, moving = false))
        assertTrue(KeptChatRules.composed(inStack = true, moving = true))
    }

    @Test fun aClosedChatShowsOnlyWhileItSlidesAway() {
        assertTrue(KeptChatRules.composed(inStack = false, moving = true))
    }

    @Test fun aClosedStillChatIsNeverLeftOverTheList() {
        assertFalse(KeptChatRules.composed(inStack = false, moving = false))
    }

    @Test fun theStuckWatchdogWaitsLongerThanAnyRealChange() {
        assertTrue(KeptChatRules.STUCK_MS > 200 + 320)
    }
}
