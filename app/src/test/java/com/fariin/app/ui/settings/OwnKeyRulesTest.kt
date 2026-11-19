package com.fariin.app.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnKeyRulesTest {
    @Test fun lengthAndDigits() {
        assertFalse(OwnKeyRules.ok("123"))
        assertFalse(OwnKeyRules.ok("1234567"))
        assertFalse(OwnKeyRules.length("12a4"))
        assertTrue(OwnKeyRules.ok("2580"))
        assertTrue(OwnKeyRules.ok("905172"))
    }
    @Test fun repeatsAndRuns() {
        assertFalse(OwnKeyRules.ok("1111"))
        assertFalse(OwnKeyRules.ok("1234"))
        assertFalse(OwnKeyRules.ok("4321"))
        assertFalse(OwnKeyRules.ok("345678"))
        assertTrue(OwnKeyRules.ok("1235"))
        // Under 4 digits the 2nd and 3rd rules are not met yet.
        assertFalse(OwnKeyRules.notRepeated("12"))
        assertFalse(OwnKeyRules.notRun("19"))
    }
}
