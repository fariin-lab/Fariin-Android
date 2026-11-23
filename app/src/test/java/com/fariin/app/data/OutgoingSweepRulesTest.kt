package com.fariin.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutgoingSweepRulesTest {
    private val day = OutgoingSweepRules.SAFE_AGE_MS
    private val held = OutgoingSweepRules.heldText(listOf(
        "[{\"path\":\"\/data\/user\/0\/x\/no_backup\/outgoing\/send-abc.jpg\",\"items\":[]}]", null))

    @Test fun youngFileStays() {
        assertFalse(OutgoingSweepRules.deletable("send-zzz.jpg", 1_000, 1_000 + day - 1, held))
    }

    @Test fun oldFileNobodyNamesGoes() {
        assertTrue(OutgoingSweepRules.deletable("send-zzz.jpg", 1_000, 1_000 + day + 1, held))
    }

    @Test fun oldFileAnOutboxNamesStays() {
        assertFalse(OutgoingSweepRules.deletable("send-abc.jpg", 1_000, 1_000 + day + 1, held))
    }

    @Test fun aNameInsideAnotherNameIsNotHeld() {
        assertTrue(OutgoingSweepRules.deletable("abc.jpg", 1_000, 1_000 + day + 1, held))
    }
}
