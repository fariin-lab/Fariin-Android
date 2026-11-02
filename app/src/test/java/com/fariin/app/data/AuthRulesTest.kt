package com.fariin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The sign-up / sign-in rules against the iOS behaviour (PasswordView, AuthFlowViews, LoginCodeView, RootView). */
class AuthRulesTest {
    @Test fun passwordChecklistSteps() {
        assertNull(PasswordRules("", "").hint)
        assertEquals("", PasswordRules("", "").word)
        assertEquals("5 more characters", PasswordRules("abc", "").hint!!.text)
        assertEquals("1 more character", PasswordRules("abcdxy7", "").hint!!.text)
        assertEquals("Too short", PasswordRules("abc", "").word)
        assertEquals("Add a number", PasswordRules("sahanbeer", "").hint!!.text)
        assertEquals("Add a letter", PasswordRules("90817263", "").hint!!.text)
        assertNull(PasswordRules("Sahan2026x", "").hint)                  // fine, confirm still empty
        assertEquals(PasswordRules.Hint("Passwords match", ok = true, bad = false), PasswordRules("Sahan2026x", "Sahan2026x").hint)
        assertEquals(PasswordRules.Hint("Passwords don't match", ok = false, bad = true), PasswordRules("Sahan2026x", "Sahan2026").hint)
        assertTrue(PasswordRules("Sahan2026x", "Sahan2026x").allMet)
        assertFalse(PasswordRules("Sahan2026x", "").allMet)
    }

    @Test fun guessablePasswords() {
        assertTrue(PasswordRules("password1", "").guessable)        // a common word with a digit
        assertTrue(PasswordRules("123password", "").guessable)
        assertTrue(PasswordRules("qwerty12", "").guessable)          // a keyboard run
        assertTrue(PasswordRules("abcdefg1", "").guessable)          // mostly one-step neighbours
        assertTrue(PasswordRules("aaaa1111", "").guessable)          // three or fewer different characters
        assertTrue(PasswordRules("Fariin2026", "").guessable)        // the app's own name
        assertFalse(PasswordRules("Sahan2026x", "").guessable)
        assertEquals("Too easy to guess", PasswordRules("password1", "").hint!!.text)
        assertFalse(PasswordRules("password1", "password1").allMet)
    }

    @Test fun strengthLevels() {
        assertEquals(0, PasswordRules("ab1", "").level)
        assertEquals(1, PasswordRules("password1", "").level)
        assertEquals(2, PasswordRules("Sahan2026x", "").level)       // under 12
        assertEquals(3, PasswordRules("sahanxmoqe2026", "").level)    // 12+, no mix, no symbol
        assertEquals(4, PasswordRules("SahanXmoqe2026", "").level)    // mixed case
        assertEquals(4, PasswordRules("sahan-xmoqe2026", "").level)   // a symbol
        assertEquals("Strong", PasswordRules("SahanXmoqe2026", "").word)
        // The server's own floor (completeSignup) is never stricter than the checklist.
        assertTrue(PasswordRules.serverAccepts("Sahan2026x"))
        assertFalse(PasswordRules.serverAccepts("sahanbeer"))
    }

    @Test fun emailCheckAndChips() {
        assertTrue(EmailRules.looksValid("amina@example.com"))
        assertTrue(EmailRules.looksValid("  amina@example.co.uk "))
        assertFalse(EmailRules.looksValid("amina@example"))
        assertFalse(EmailRules.looksValid("@example.com"))
        assertFalse(EmailRules.looksValid("amina@example."))
        assertFalse(EmailRules.looksValid("amina@ex@ample.com"))
        assertFalse(EmailRules.looksValid("amina"))
        assertEquals(EmailRules.DOMAINS, EmailRules.domainChips("amina", focused = true))
        assertEquals(listOf("hotmail.com"), EmailRules.domainChips("amina@ho", true))
        assertEquals(emptyList<String>(), EmailRules.domainChips("amina@gmail.com", true))  // already typed in full
        assertEquals(emptyList<String>(), EmailRules.domainChips("amina", focused = false))
        assertEquals(emptyList<String>(), EmailRules.domainChips("@gm", true))
        assertEquals("amina@gmail.com", EmailRules.applyDomain("amina@gm", "gmail.com"))
        assertEquals("amina@icloud.com", EmailRules.applyDomain("amina", "icloud.com"))
        assertTrue(EmailRules.isRelayAddress("x1y2@PrivateRelay.AppleID.com"))
        assertFalse(EmailRules.isRelayAddress("amina@example.com"))
    }

    @Test fun codeEntry() {
        assertEquals("123456", CodeEntry.clean("12 34-56 7"))
        assertEquals("482", CodeEntry.clean("4a8b2"))
        assertEquals("", CodeEntry.clean("code"))
        assertTrue(CodeEntry.isComplete("123456"))
        assertFalse(CodeEntry.isComplete("12345"))
        assertFalse(CodeEntry.isComplete("12345a"))
        assertEquals(0, CodeEntry.caretIndex(""))
        assertEquals(4, CodeEntry.caretIndex("4821"))
        assertNull(CodeEntry.caretIndex("482193"))
        assertEquals("Send another code in 9s", CodeEntry.resendLabel(9))
        assertEquals("Send another code", CodeEntry.resendLabel(0))
    }

    @Test fun codePurposesSpeakIos() {
        assertEquals("Enter your code", CodePurpose.Forgot.title)
        assertEquals("Log in", CodePurpose.Forgot.action)
        assertEquals("Confirm your email", CodePurpose.Unproven.title)
        assertEquals("Confirm", CodePurpose.Unproven.action)
        assertEquals("Check your email", CodePurpose.NewAccount.title)
        assertEquals("Continue", CodePurpose.NewAccount.action)
        assertEquals("Enter the 6-digit code we sent to a@b.co. It expires in 10 minutes.", CodePurpose.Forgot.codeBlurb(" a@b.co "))
    }

    @Test fun handleStatesAndWords() {
        assertEquals(HandleState.Empty, HandleRules.localState(""))
        assertEquals(HandleState.Empty, HandleRules.localState("!!"))          // nothing allowed survives
        assertEquals(HandleState.Invalid, HandleRules.localState("ami"))       // under 5
        assertEquals(HandleState.Checking, HandleRules.localState("amina"))
        assertEquals(HandleState.Free, HandleRules.fromServer(true, null))
        assertEquals(HandleState.Taken, HandleRules.fromServer(false, null))
        assertEquals(HandleState.Taken, HandleRules.fromServer(false, "taken"))
        assertEquals(HandleState.Invalid, HandleRules.fromServer(false, "reserved"))
        assertEquals("@amina is available.", HandleRules.note(HandleState.Free, "amina", false))
        assertEquals("@amina is taken. Try adding a number.", HandleRules.note(HandleState.Taken, "amina", false))
        assertEquals("@amina is taken. These are free:", HandleRules.note(HandleState.Taken, "amina", true))
        assertEquals("Letters, numbers and _ only, 5–30 characters.", HandleRules.note(HandleState.Empty, "", false))
        assertEquals("Couldn't check @amina. Check your connection.", HandleRules.note(HandleState.Unknown, "am!ina", false))
        assertTrue(HandleRules.canContinue("Amina", HandleState.Free, saving = false))
        assertTrue(HandleRules.canContinue("Amina", HandleState.Unknown, saving = false))   // the server decides
        assertFalse(HandleRules.canContinue("  ", HandleState.Free, saving = false))
        assertFalse(HandleRules.canContinue("Amina", HandleState.Taken, saving = false))
        assertFalse(HandleRules.canContinue("Amina", HandleState.Free, saving = true))
    }

    @Test fun saveChecks() {
        assertEquals("Enter your name", HandleRules.saveProblem(" ", "amina", ""))
        assertEquals("Username: letters, numbers and _ only, 5–30 characters", HandleRules.saveProblem("Amina", "ami", ""))
        assertNull(HandleRules.saveProblem("Amina", "amina_y", ""))
        assertNull(HandleRules.saveProblem("Amina", "Abc", "abc"))   // an existing (older, shorter) handle kept as is
    }

    @Test fun usernameIdeasFromAName() {
        val ideas = HandleRules.ideas("Amína Yúsuf", Random(7))
        assertEquals("aminayusuf", ideas[0])
        assertEquals("amina_yusuf", ideas[1])
        assertTrue(ideas.all { Usernames.isValid(it) && Usernames.shapeProblem(it) == null })
        assertEquals(ideas.size, ideas.toSet().size)
        // Short names are padded to five with digits; a name with no letters gives nothing.
        val short = HandleRules.ideas("Al", Random(1))
        assertTrue(short.all { it.length >= 5 && it.startsWith("al") })
        assertEquals(emptyList<String>(), HandleRules.ideas("1234", Random(1)))
        assertEquals(emptyList<String>(), HandleRules.ideas("  ", Random(1)))
        // Never longer than 30.
        assertTrue(HandleRules.ideas("a".repeat(40) + " b", Random(3)).all { it.length <= 30 })
    }

    @Test fun doorsAndProviders() {
        assertEquals(SignInMethod.Email, SignInMethod.forProvider("password"))
        assertEquals(SignInMethod.Google, SignInMethod.forProvider("google.com"))
        assertEquals(SignInMethod.Apple, SignInMethod.forProvider("apple.com"))
        assertNull(SignInMethod.forProvider("phone"))
        assertEquals(SignInMethod.Google, SignInMethod.of("google"))
        // The account's own door: the phone's last one only when this account has it.
        assertEquals(SignInMethod.Apple, SignInMethod.doorOf(setOf("password", "apple.com"), SignInMethod.Apple))
        assertEquals(SignInMethod.Email, SignInMethod.doorOf(setOf("password", "apple.com"), SignInMethod.Google))
        assertEquals(SignInMethod.Google, SignInMethod.doorOf(setOf("google.com", "apple.com"), null))
        assertNull(SignInMethod.doorOf(emptySet(), SignInMethod.Email))
    }

    @Test fun savedAccountList() {
        val a = SavedAccount("a", "Alice", "alice", "email", "alice@x.io")
        val b = SavedAccount("b", "", "bobby", "google")
        val (one, _) = SavedAccount.upsert(emptyList(), a)
        assertEquals(listOf(a), one)
        val (two, _) = SavedAccount.upsert(one, b)
        assertEquals(listOf(b, a), two)                              // newest first
        val a2 = a.copy(name = "Alice T")
        val (moved, _) = SavedAccount.upsert(two, a2)
        assertEquals(listOf(a2, b), moved)                           // refreshed and moved up, never twice
        assertEquals("@bobby", b.title)
        assertEquals("Alice", a.title)
        var list = emptyList<SavedAccount>()
        var dropped = emptyList<SavedAccount>()
        for (i in 1..6) { val r = SavedAccount.upsert(list, SavedAccount("u$i", "N$i", "user$i", "email")); list = r.first; dropped = r.second }
        assertEquals(5, list.size)
        assertEquals(listOf("u1"), dropped.map { it.uid })           // the oldest falls off (photo and key go with it)
        assertTrue(SavedAccount.showsLastUsed(list, list[0]))
        assertFalse(SavedAccount.showsLastUsed(list, list[1]))
        assertFalse(SavedAccount.showsLastUsed(listOf(a), a))        // one card shows its @handle
    }

    @Test fun errorWords() {
        assertEquals("Wrong email or password.", AuthMessages.forAuthCode("ERROR_INVALID_CREDENTIAL"))
        assertEquals("That password is not right.", AuthMessages.forAuthCode("ERROR_WRONG_PASSWORD", "That password is not right."))
        assertEquals("No account with that email. Create one instead.", AuthMessages.forAuthCode("ERROR_USER_NOT_FOUND"))
        assertEquals("Too many attempts. Wait a moment and try again.", AuthMessages.forAuthCode("ERROR_TOO_MANY_REQUESTS"))
        assertNull(AuthMessages.forAuthCode("ERROR_SOMETHING_NEW"))
        assertEquals("Couldn't sign in. Please try again.", AuthMessages.forDoorCode("ERROR_INTERNAL_ERROR"))
        assertEquals("Something went wrong. Try again.", AuthMessages.forServer("INTERNAL"))
        assertEquals("Something went wrong. Try again.", AuthMessages.forServer(null))
        assertEquals("Enter the six-digit code from your email.", AuthMessages.forServer("Enter the six-digit code from your email."))
        assertEquals("You haven't signed up with this account before. Go back and choose Sign Up to continue.",
            AuthFlowError(AuthFlowError.Kind.NoAccount).message)
        // No em dashes anywhere in the words the pages show.
        val all = AuthFlowError.Kind.entries.map { it.message } + CodePurpose.entries.flatMap { listOf(it.title, it.blurb, it.action) } +
            listOf("ERROR_INVALID_EMAIL", "ERROR_WEAK_PASSWORD", "ERROR_USER_DISABLED", "ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL")
                .mapNotNull { AuthMessages.forAuthCode(it) }
        assertTrue(all.none { it.contains('—') })
    }
}
