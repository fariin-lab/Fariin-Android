package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RowKeyRulesTest {
    @Test fun mySendKeysByClientId() {
        assertEquals("c1", RowKeyRules.rowId("doc1", "c1", "me", "me"))
    }

    @Test fun someoneElsesKeysByDocIdEvenWithAClientId() {
        // A member reusing my message's clientId gets their own row, never mine.
        assertEquals("doc2", RowKeyRules.rowId("doc2", "c1", "them", "me"))
    }

    @Test fun noClientIdKeysByDocId() {
        assertEquals("doc3", RowKeyRules.rowId("doc3", null, "me", "me"))
    }

    @Test fun unknownMeKeepsTheOldRule() {
        assertEquals("c1", RowKeyRules.rowId("doc1", "c1", "them", null))
    }

    @Test fun echoOnlyWhenIWroteIt() {
        assertTrue(RowKeyRules.isMyEcho("me", "me"))
        assertFalse(RowKeyRules.isMyEcho("them", "me"))
        assertFalse(RowKeyRules.isMyEcho(null, "me"))
    }
}
