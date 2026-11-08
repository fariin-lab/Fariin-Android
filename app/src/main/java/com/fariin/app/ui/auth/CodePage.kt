package com.fariin.app.ui.auth

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.fariin.app.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.data.AuthRepository
import com.fariin.app.data.CodeEntry
import com.fariin.app.data.CodePurpose
import com.fariin.app.data.PasswordRules
import com.fariin.app.ui.theme.Fariin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * A step the signed-in side must still show, because signing in (a custom token) flips
 * MainActivity away from the signed-out pages before the page that asked can show it:
 * after "Forgot password?" logs in with a code, iOS offers "Create a new password" next.
 */
object AuthHandoff {
    val newPasswordFor = MutableStateFlow<String?>(null)
}

enum class CodeStep { Email, Code, NewPassword }

/**
 * Kulan-iOS Views/LoginCodeView.swift: type the email (skipped when it came filled in, the code
 * is then sent on open), then the six boxes (the sixth digit checks by itself), "Send another
 * code" after 30 s. Purposes: Forgot (logs in, then offers a new password), Unproven (the one-time
 * confirm-your-email check), NewAccount (sign-up: gives a ticket for the password step).
 */
@Composable
fun CodePage(
    auth: AuthRepository,
    purpose: CodePurpose,
    email: String,
    onBack: (() -> Unit)?,
    onAuthed: () -> Unit,
    onTicket: ((String) -> Unit)? = null,
    startStep: CodeStep? = null,
    demo: Boolean = false,
    demoCode: String = "",
    demoError: String? = null,
) {
    val c = Fariin.colors
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var step by rememberSaveable { mutableStateOf(startStep ?: CodeStep.Email) }
    var address by rememberSaveable { mutableStateOf(email) }
    var code by rememberSaveable { mutableStateOf(demoCode) }
    var newPassword by rememberSaveable { mutableStateOf("") }
    var confirmPassword by rememberSaveable { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(demoError) }
    var resendIn by remember { mutableIntStateOf(if (demo && step == CodeStep.Code) 24 else 0) }
    var autoSent by rememberSaveable { mutableStateOf(false) }
    val codeFocus = remember { FocusRequester() }
    val emailFocus = remember { FocusRequester() }
    val newFocus = remember { FocusRequester() }
    val confirmFocus = remember { FocusRequester() }
    val trimmed = address.trim()
    val rules = PasswordRules(newPassword, confirmPassword)

    fun send() {
        busy = true; error = null
        scope.launch {
            try {
                if (purpose == CodePurpose.NewAccount) auth.requestSignupCode(trimmed)
                else auth.requestLoginCode(trimmed, confirm = purpose != CodePurpose.Forgot)
                step = CodeStep.Code; code = ""; resendIn = CodeEntry.RESEND_SECONDS
            } catch (e: Exception) { error = AuthRepository.codeMessageFor(e) }
            busy = false
        }
    }

    fun verify() {
        if (busy || demo) return
        busy = true; error = null
        scope.launch {
            try {
                if (purpose == CodePurpose.NewAccount) {
                    val ticket = auth.verifySignupCode(trimmed, code)
                    busy = false
                    onTicket?.invoke(ticket)
                    return@launch
                }
                // Forgot: the new-password step is shown by the signed-in side (see AuthHandoff).
                if (purpose == CodePurpose.Forgot) AuthHandoff.newPasswordFor.value = trimmed
                try { auth.signInWithLoginCode(trimmed, code) } catch (e: Exception) { AuthHandoff.newPasswordFor.value = null; throw e }
                if (purpose == CodePurpose.Forgot && !auth.hasTypableAddress) AuthHandoff.newPasswordFor.value = null
                onAuthed()
            } catch (e: Exception) { error = AuthRepository.codeMessageFor(e); code = "" }
            busy = false
        }
    }

    fun saveNewPassword() {
        if (!rules.allMet || busy) return
        busy = true; error = null
        scope.launch {
            try { auth.setPassword(newPassword, isFirst = !auth.hasPassword); onAuthed() }
            catch (e: Exception) { error = AuthRepository.messageFor(e) }
            busy = false
        }
    }

    LaunchedEffect(Unit) {
        if (demo || step != CodeStep.Email) return@LaunchedEffect
        if (!autoSent && trimmed.isNotEmpty()) { autoSent = true; send() }
        else try { emailFocus.requestFocus() } catch (_: Exception) {}
    }
    LaunchedEffect(resendIn) { if (resendIn > 0 && !demo) { delay(1000); resendIn -= 1 } }
    LaunchedEffect(step) { if (step == CodeStep.NewPassword && !demo) try { newFocus.requestFocus() } catch (_: Exception) {} }

    Column(Modifier.fillMaxSize().background(c.bg).imePadding()) {
        if (onBack != null && step != CodeStep.NewPassword) BackRow(onBack) else Spacer(Modifier.height(52.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            PageTitle(
                when (step) { CodeStep.Email -> CodePurpose.EMAIL_STEP_TITLE; CodeStep.NewPassword -> CodePurpose.NEW_PASSWORD_TITLE; CodeStep.Code -> purpose.title },
                when (step) { CodeStep.Email -> purpose.blurb; CodeStep.NewPassword -> CodePurpose.NEW_PASSWORD_BLURB; CodeStep.Code -> purpose.codeBlurb(trimmed) },
                top = 36,
            )
            when (step) {
                CodeStep.NewPassword -> {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.fieldFill).padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(ImageVector.vectorResource(R.drawable.ic_sf_person_crop_circle_fill), null, tint = c.secondary, modifier = Modifier.size(26.dp))
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("Logged in as", fontSize = 13.sp, color = c.secondary)
                            Text(trimmed, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = c.label, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                        }
                    }
                    AuthField(newPassword, { newPassword = it }, "New password", ImageVector.vectorResource(R.drawable.ic_sf_lock), secret = true, reveal = reveal,
                        onReveal = { reveal = !reveal }, keyboard = passwordKeyboard(false), focusRequester = newFocus,
                        actions = KeyboardActions(onNext = { try { confirmFocus.requestFocus() } catch (_: Exception) {} }))
                    AuthField(confirmPassword, { confirmPassword = it }, "Confirm password", ImageVector.vectorResource(R.drawable.ic_sf_lock), secret = true, reveal = reveal,
                        onReveal = { reveal = !reveal }, keyboard = passwordKeyboard(true), focusRequester = confirmFocus,
                        actions = KeyboardActions(onGo = { saveNewPassword() }))
                    PasswordChecklist(rules)
                    ActionPill("Save password", enabled = rules.allMet, busy = busy) { saveNewPassword() }
                    TextLink("Not now", enabled = !busy) { onAuthed() }
                    Text("Think someone else got in? Once you are in, open Settings › Devices and log out any phone you do not know.",
                        fontSize = 13.sp, color = c.secondary, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 8.dp))
                }
                CodeStep.Email -> {
                    AuthField(address, { address = it }, "Email", ImageVector.vectorResource(R.drawable.ic_sf_envelope), focusRequester = emailFocus,
                        keyboard = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Send, autoCorrectEnabled = false),
                        actions = KeyboardActions(onSend = { if (trimmed.contains("@")) send() }))
                    ActionPill("Send code", enabled = trimmed.contains("@"), busy = busy) { send() }
                    if (purpose == CodePurpose.Forgot) TextLink("Someone else is in my account", color = c.label) {
                        uri.openUri("https://help.fariin.com/security/if-you-think-someone-else-is-in-your-account")
                    }
                }
                CodeStep.Code -> {
                    CodeBoxes(code, { v ->
                        code = v
                        if (v.isNotEmpty()) error = null
                        if (CodeEntry.isComplete(v) && !busy) verify()
                    }, invalid = error != null && code.isEmpty(), focusRequester = codeFocus, autoFocus = !demo)
                    ActionPill(purpose.action, enabled = CodeEntry.isComplete(code), busy = busy) { verify() }
                    TextLink(CodeEntry.resendLabel(resendIn), strong = resendIn == 0, enabled = resendIn == 0 && !busy) { send() }
                    if (purpose == CodePurpose.Forgot) TextLink("Use a different email", strong = false) { step = CodeStep.Email; code = ""; error = null }
                    else if (purpose == CodePurpose.NewAccount && onBack != null) TextLink("Use a different email", strong = false) { onBack() }
                }
            }
            ErrorLine(error)
            Spacer(Modifier.height(24.dp))
        }
    }
}
