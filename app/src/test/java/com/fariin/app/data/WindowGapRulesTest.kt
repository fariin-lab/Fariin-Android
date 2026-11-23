package com.fariin.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowGapRulesTest {
    @Test fun aFullWindowWithNothingInCommonIsAGap() {
        assertTrue(WindowGapRules.cutOff(setOf("a", "b"), setOf("x", "y"), full = true))
    }

    @Test fun anOverlapJoinsTheRows() {
        assertFalse(WindowGapRules.cutOff(setOf("a", "b"), setOf("b", "x"), full = true))
    }

    @Test fun aShortWindowIsTheWholeChat() {
        assertFalse(WindowGapRules.cutOff(setOf("a"), setOf("x"), full = false))
    }

    @Test fun nothingHeldNothingToCut() {
        assertFalse(WindowGapRules.cutOff(emptySet(), setOf("x"), full = true))
        assertFalse(WindowGapRules.cutOff(setOf("a"), emptySet(), full = true))
    }
}
