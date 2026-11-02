package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Two-step gate test, email change and restore wording against Kulan-iOS (TwoStepGate, ChangeEmailView, RestoreAccountView). */
class AccountSecurityTest {
    private fun claim(vararg kv: Pair<String, Any?>) = mapOf<String, Any?>("twoStep" to mapOf(*kv))

    @Test fun twoStepSessionTest() {
        // No claim, or not required: through.
        assertTrue(TwoStepRules.sessionPassed(emptyMap(), 1_700_000_000))
        assertTrue(TwoStepRules.sessionPassed(claim("required" to false), 1_700_000_000))
        // Required but never answered.
        assertFalse(TwoStepRules.sessionPassed(claim("required" to true, "ok" to false), 1_700_000_000))
        // Per sign-in list: only this auth_time.
        val listed = claim("required" to true, "ok" to true, "at" to 9_999_999_999_999L, "authTimes" to listOf(1_700_000_000L, 1_700_000_500L))
        assertTrue(TwoStepRules.sessionPassed(listed, 1_700_000_500))
        assertFalse(TwoStepRules.sessionPassed(listed, 1_700_000_900))   // a later sign-in is locked even with a big `at`
        // legacyAt keeps older sessions working.
        val legacy = claim("required" to true, "ok" to true, "authTimes" to emptyList<Long>(), "legacyAt" to 1_700_000_100_000L)
        assertTrue(TwoStepRules.sessionPassed(legacy, 1_700_000_000))
        assertFalse(TwoStepRules.sessionPassed(legacy, 1_700_000_200))
        // A claim with no list: `at`.
        val old = claim("required" to true, "ok" to true, "at" to 1_700_000_100_000L)
        assertTrue(TwoStepRules.sessionPassed(old, 1_700_000_000))
        assertFalse(TwoStepRules.sessionPassed(old, 1_700_000_200))
        // Integer types from the token parser.
        assertTrue(TwoStepRules.sessionPassed(claim("required" to true, "ok" to true, "authTimes" to listOf(1_700_000_000)), 1_700_000_000))
        assertTrue(TwoStepRules.isRequired(listed))
        assertFalse(TwoStepRules.isRequired(emptyMap()))
    }

    @Test fun additionalPasswordForm() {
        assertFalse(TwoStepRules.canSave(false, "", "12345", "12345", false))
        assertTrue(TwoStepRules.canSave(false, "", "123456", "123456", false))
        assertFalse(TwoStepRules.canSave(false, "", "123456", "123457", false))
        assertFalse(TwoStepRules.canSave(true, "", "123456", "123456", false))    // a change needs the old one
        assertTrue(TwoStepRules.canSave(true, "old", "123456", "123456", false))
        assertFalse(TwoStepRules.canSave(false, "", "123456", "123456", true))
        assertEquals("123456", TwoStepRules.code("12 34-56 78"))
        assertEquals("", TwoStepRules.code("abc"))
        assertTrue(TwoStepRules.recoveryFooter("").startsWith("No recovery email is set."))
        assertTrue(TwoStepRules.recoveryFooter("c***@x.com").startsWith("If you forget this password"))
    }

    @Test fun emailChange() {
        assertFalse(EmailChangeRules.canSend("carol", "carol@a.com", false))
        assertFalse(EmailChangeRules.canSend(" Carol@A.com ", "carol@a.com", false))   // the same address
        assertTrue(EmailChangeRules.canSend("new@a.com", "carol@a.com", false))
        assertFalse(EmailChangeRules.canSend("new@a.com", "carol@a.com", true))
        assertEquals("new@a.com", EmailChangeRules.normalized(" New@A.com "))
        assertEquals("We sent a code to new@a.com. Enter it below to confirm.", EmailChangeRules.codeFooter("new@a.com"))
    }

    @Test fun restoreWords() {
        val day = 86_400_000L
        assertEquals(0, RestoreRules.daysLeft(10 * day, 9 * day))
        assertEquals(0, RestoreRules.daysLeft(0, day - 1))
        assertEquals(29, RestoreRules.daysLeft(0, 29 * day + 5))
        assertEquals("Today is the last day. Cancel now to keep your account.", RestoreRules.deadline(0))
        assertEquals("1 day left. Cancel any time before then to keep your account as it is.", RestoreRules.deadline(1))
        assertEquals("12 days left. Cancel any time before then to keep your account as it is.", RestoreRules.deadline(12))
        assertEquals("@carol, your chats and your photos. Nothing has been deleted.", RestoreRules.body("carol"))
        assertEquals("Your chats, your photos and your name. Nothing has been deleted.", RestoreRules.body(""))
        for (s in listOf(RestoreRules.FOOT, RestoreRules.deleteNowText("3 December"), AccountCallError.REAUTH, TwoStepRules.recoveryFooter("")))
            assertFalse(s.contains('—'))
    }
}
