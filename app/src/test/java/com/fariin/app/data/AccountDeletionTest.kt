package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AccountDeletionTest {
    @Test fun noChoiceSendsNothing() {
        assertEquals(emptyMap<String, Any>(), AccountDeletion.payload(null, "x"))
    }

    @Test fun textOnlyForOther() {
        assertEquals(mapOf("reasonCode" to "not_using"), AccountDeletion.payload("not_using", "ignored"))
        assertEquals(mapOf("reasonCode" to "other", "reasonText" to "too slow"), AccountDeletion.payload("other", "  too slow "))
        assertEquals(mapOf("reasonCode" to "other"), AccountDeletion.payload("other", "   "))
    }

    @Test fun textCappedAt255() {
        val sent = AccountDeletion.payload("other", "a".repeat(400))["reasonText"] as String
        assertEquals(255, sent.length)
    }

    @Test fun dateFromAnswer() {
        assertEquals(5L, AccountDeletion.dueFrom(mapOf("deleteAt" to 5L, "due" to 9L), 1L))
        assertEquals(9L, AccountDeletion.dueFrom(mapOf("due" to 9.0), 1L))
        assertEquals(1L, AccountDeletion.dueFrom(mapOf("ok" to true), 1L))
        assertEquals(1L, AccountDeletion.dueFrom(null, 1L))
    }
}
