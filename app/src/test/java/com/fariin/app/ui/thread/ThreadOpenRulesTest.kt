package com.fariin.app.ui.thread

import com.fariin.app.push.PushOpen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadOpenRulesTest {
    @Test fun aPlainReopenGoesBackToItsPlace() = assertTrue(ThreadOpenRules.restoresPlace(0, false, null, false))

    @Test fun unreadWinsBeforeTheServerAnswers() {
        // The phone's copy is up, the divider is not placed yet: the saved place must not flash first.
        assertFalse(ThreadOpenRules.restoresPlace(2, hasDivider = false, pushedMid = null, userDragged = false))
        assertFalse(ThreadOpenRules.restoresPlace(0, hasDivider = true, pushedMid = null, userDragged = false))
    }

    @Test fun aTappedNotificationOpensAtTheNewest() =
        // Even when the chat list's count did not have the new message yet (count 0).
        assertFalse(ThreadOpenRules.restoresPlace(0, false, pushedMid = "m9", userDragged = false))

    @Test fun theReadersDragWins() = assertFalse(ThreadOpenRules.restoresPlace(0, false, null, userDragged = true))

    @Test fun aPushOpenCountsOnlyForItsChatWhileFresh() {
        val p = PushOpen("c1", "m9", atMs = 1_000)
        assertEquals("m9", p.midFor("c1", 1_000))
        assertEquals("m9", p.midFor("c1", 1_000 + PushOpen.FRESH_MS))
        assertNull(p.midFor("c2", 1_000))                              // another chat
        assertNull(p.midFor("c1", 1_000 + PushOpen.FRESH_MS + 1))      // its chat never opened in time
        assertNull(p.midFor("c1", 999))                                // a clock from before the tap
    }
}
