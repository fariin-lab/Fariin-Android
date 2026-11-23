package com.fariin.app.crypto

import com.fariin.app.crypto.KeyBackupRules.Read
import com.fariin.app.crypto.KeyBackupRules.Restore
import com.fariin.app.crypto.KeyBackupRules.Write
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyBackupRulesTest {
    private val pkA = ByteArray(32) { 1 }
    private val pkB = ByteArray(32) { 2 }
    private fun found(pk: ByteArray, made: Long = 100L, valid: Boolean = true) =
        Read.Found(ByteArray(32) { 9 }, pk, made, valid)

    @Test fun aTimeoutOrErrorNeverMakesANewKey() {
        for (pairOk in listOf(true, false)) {
            assertEquals(Restore.TRY_AGAIN, KeyBackupRules.restoreStep(Read.TimedOut, pairOk))
            assertEquals(Restore.TRY_AGAIN, KeyBackupRules.restoreStep(Read.Failed, pairOk))
            assertNotEquals(Restore.MAKE_NEW, KeyBackupRules.restoreStep(Read.TimedOut, pairOk))
            assertNotEquals(Restore.MAKE_NEW, KeyBackupRules.restoreStep(Read.Failed, pairOk))
        }
    }

    @Test fun onlyAnAnsweredEmptyReadMakesANewKey() {
        assertEquals(Restore.MAKE_NEW, KeyBackupRules.restoreStep(Read.Empty, true))
        assertEquals(Restore.MAKE_NEW, KeyBackupRules.restoreStep(Read.NoService, true))
    }

    @Test fun aGoodSavedKeyIsUsed() {
        assertEquals(Restore.USE_BACKUP, KeyBackupRules.restoreStep(found(pkA), pairOk = true))
    }

    @Test fun aBrokenEntryCannotBeUsed() {
        assertEquals(Restore.MAKE_NEW, KeyBackupRules.restoreStep(found(pkA, valid = false), pairOk = true))
        assertEquals(Restore.MAKE_NEW, KeyBackupRules.restoreStep(found(pkA), pairOk = false))
    }

    @Test fun aDifferentSavedKeyIsKept() {
        assertEquals(Write.KEEP_OTHER, KeyBackupRules.writeStep(found(pkA), pkB, 200L, adopted = false))
        assertEquals(Write.KEEP_OTHER, KeyBackupRules.writeStep(found(pkA, made = 1L), pkB, 999L, adopted = false))
    }

    @Test fun aKeyThePersonBroughtBackReplacesIt() {
        assertEquals(Write.WRITE, KeyBackupRules.writeStep(found(pkA), pkB, 200L, adopted = true))
    }

    @Test fun nothingIsWrittenWhileTheSavedEntryIsUnknown() {
        for (r in listOf(Read.TimedOut, Read.Failed, Read.NoService)) {
            assertEquals(Write.SKIP, KeyBackupRules.writeStep(r, pkB, 200L, adopted = false))
            assertEquals(Write.SKIP, KeyBackupRules.writeStep(r, pkB, 200L, adopted = true))
        }
    }

    @Test fun emptyOrBrokenEntryIsWritten() {
        assertEquals(Write.WRITE, KeyBackupRules.writeStep(Read.Empty, pkB, 200L, adopted = false))
        assertEquals(Write.WRITE, KeyBackupRules.writeStep(found(pkA, valid = false), pkB, 200L, adopted = false))
    }

    @Test fun theSameKeyOnlyUpdatesAKnownDate() {
        assertEquals(Write.SKIP, KeyBackupRules.writeStep(found(pkA, made = 100L), pkA, 100L, adopted = false))
        assertEquals(Write.SKIP, KeyBackupRules.writeStep(found(pkA, made = 100L), pkA, 0L, adopted = false))
        assertEquals(Write.WRITE, KeyBackupRules.writeStep(found(pkA, made = 0L), pkA, 100L, adopted = false))
    }

    @Test fun noServiceCodes() {
        assertTrue(KeyBackupRules.noService(17))
        assertTrue(!KeyBackupRules.noService(8))   // INTERNAL_ERROR: a passing failure
        assertTrue(!KeyBackupRules.noService(7))   // NETWORK_ERROR
    }
}
