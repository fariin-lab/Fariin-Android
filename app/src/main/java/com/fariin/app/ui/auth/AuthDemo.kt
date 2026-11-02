package com.fariin.app.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.fariin.app.auth.Passkeys
import com.fariin.app.data.AuthRepository
import com.fariin.app.data.CodePurpose
import com.fariin.app.data.HandleState
import com.fariin.app.data.SavedAccount
import com.fariin.app.ui.theme.Fariin

/**
 * Debug builds only (MainActivity `--es authPage <name>`): one sign-in page with made-up state, for
 * screenshots of the steps that need the real server (codes, sign-up, username claim). Buttons
 * that would call the server do nothing useful here; nothing is signed in.
 *
 * Names: agree, welcome, doors_login, doors_create, accounts, email_login, email_error,
 * create_email, create_invalid, create_password, taken, code_email, code_forgot, code_wrong,
 * unproven, newaccount_code, newpassword, onboarding, onboarding_free, onboarding_taken,
 * password, reset_code, passkeys.
 */
@Composable
fun AuthDemo(page: String) {
    val auth = remember { AuthRepository() }
    val people = listOf(
        SavedAccount("demo-a", "Amina Yusuf", "amina", "email", "amina@example.com"),
        SavedAccount("demo-b", "Bashir Ali", "bashir_ali", "google"),
    )
    val noop: () -> Unit = {}
    Box(Modifier.fillMaxSize().background(Fariin.colors.bg).let {
        if (page == "agree" || page == "onboarding" || page.startsWith("onboarding_") || page == "password" || page == "passkeys" || page == "reset_code") it
        else it.statusBarsPadding().navigationBarsPadding()
    }) {
        when (page) {
            "agree" -> AgreementPage(noop)
            "welcome" -> WelcomePage(noop, noop)
            "doors_login" -> MethodPage(auth, AuthMode.Login, pushed = true, onBack = noop, onEmail = { _, _ -> }, onDoors = {}, demoSaved = emptyList())
            "doors_create" -> MethodPage(auth, AuthMode.Create, pushed = true, onBack = noop, onEmail = { _, _ -> }, onDoors = {}, demoSaved = emptyList())
            "accounts" -> MethodPage(auth, AuthMode.Login, pushed = false, onBack = null, onEmail = { _, _ -> }, onDoors = {}, demoSaved = people)
            "email_login" -> EmailPage(auth, AuthMode.Login, "", noop, { _, _ -> }, null, noop, EmailDemo(email = "amina@example.com", password = "secret-pass1"))
            "email_error" -> EmailPage(auth, AuthMode.Login, "", noop, { _, _ -> }, null, noop,
                EmailDemo(email = "amina@example.com", password = "wrong", error = "Wrong email or password."))
            "create_email" -> EmailPage(auth, AuthMode.Create, "", noop, { _, _ -> }, null, noop, EmailDemo(email = "amina@exa"))
            "create_invalid" -> EmailPage(auth, AuthMode.Create, "", noop, { _, _ -> }, null, noop, EmailDemo(email = "amina@example", invalid = true))
            "create_password" -> EmailPage(auth, AuthMode.Create, "", noop, { _, _ -> }, null, noop,
                EmailDemo(email = "amina@example.com", password = "Sahan2026", confirm = "Sahan2026", passwordStep = true))
            "taken" -> EmailPage(auth, AuthMode.Create, "", noop, { _, _ -> }, null, noop,
                EmailDemo(email = "amina@example.com", password = "Sahan2026", confirm = "Sahan2026", passwordStep = true, taken = true))
            "code_email" -> CodePage(auth, CodePurpose.Forgot, "", noop, noop, demo = true)
            "code_forgot" -> CodePage(auth, CodePurpose.Forgot, "amina@example.com", noop, noop, startStep = CodeStep.Code, demo = true, demoCode = "4821")
            "code_wrong" -> CodePage(auth, CodePurpose.Forgot, "amina@example.com", noop, noop, startStep = CodeStep.Code, demo = true,
                demoError = "That code is not right. Check the email and try again.")
            "unproven" -> CodePage(auth, CodePurpose.Unproven, "amina@example.com", null, noop, startStep = CodeStep.Code, demo = true)
            "newaccount_code" -> CodePage(auth, CodePurpose.NewAccount, "amina@example.com", noop, noop, startStep = CodeStep.Code, demo = true, demoCode = "39")
            "newpassword" -> CodePage(auth, CodePurpose.Forgot, "amina@example.com", null, noop, startStep = CodeStep.NewPassword, demo = true)
            "onboarding" -> OnboardingPage(com.fariin.app.ui.debug.DemoPeople, "", null, {}, demoName = "", demoHandle = "", demoState = HandleState.Empty)
            "onboarding_free" -> OnboardingPage(com.fariin.app.ui.debug.DemoPeople, "", null, {}, demoName = "Amina Yusuf", demoHandle = "aminayusuf",
                demoState = HandleState.Free)
            "onboarding_taken" -> OnboardingPage(com.fariin.app.ui.debug.DemoPeople, "", null, {}, demoName = "Amina Yusuf", demoHandle = "amina",
                demoState = HandleState.Taken, demoIdeas = listOf("aminayusuf", "amina_yusuf", "aminayusuf27"))
            "password" -> PasswordPage(auth, noop, {}, demoOpen = true)
            "reset_code" -> ResetCodePage(auth, "a•••a@example.com", noop, noop, demo = true)
            "passkeys" -> PasskeysPage(auth, noop, demoRows = emptyList())
            "passkeys_list" -> PasskeysPage(auth, noop, demoRows = listOf(Passkeys.Row("p1", "Google Password Manager", 1.7614e12, 1.7620e12)))
            else -> WelcomePage(noop, noop)
        }
    }
}
