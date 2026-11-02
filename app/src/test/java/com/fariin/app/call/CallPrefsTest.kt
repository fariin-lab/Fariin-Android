package com.fariin.app.call

import org.junit.Assert.assertEquals
import org.junit.Test

/** The Always Relay key must stay the one iOS uses, and the settings page writes the same file. */
class CallPrefsTest {
    @Test fun keyMatchesIos() {
        assertEquals("calls.alwaysRelay", CallPrefs.ALWAYS_RELAY)
        assertEquals("fariin_calls", CallPrefs.FILE)
    }
}
