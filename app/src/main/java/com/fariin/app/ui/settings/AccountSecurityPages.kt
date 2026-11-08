package com.fariin.app.ui.settings

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.fariin.app.ui.components.underBar

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.R
import com.fariin.app.data.AccountCall
import com.fariin.app.data.AccountCallError
import com.fariin.app.data.AuthRepository
import com.fariin.app.data.EmailChangeRules
import com.fariin.app.data.TwoStepGate
import com.fariin.app.data.TwoStepRules
import com.fariin.app.ui.theme.Fariin
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/*
 * Settings > Account's security pages, after Kulan-iOS AccountSecurityViews.swift (ChangeEmailView,
 * EmailCodeView, SignInAgainSection) and TwoStepVerificationViews.swift (TwoStepVerificationView,
 * SetAdditionalPasswordView, DisableAdditionalPasswordView). The server decides, the page reports:
 * every refusal is the server's own sentence. iOS sheets are pushed pages here (Android back).
 */

internal const val PAGE_EMAIL = "account:email"
internal const val PAGE_EMAIL_CODE = "account:emailcode:"
internal const val PAGE_USERNAME = "account:username"
internal const val PAGE_TWOSTEP = "account:twostep"
internal const val PAGE_TWOSTEP_SET = "account:twostep:set:"
internal const val PAGE_TWOSTEP_OFF = "account:twostep:off"

internal val IosGreen = Color(0xFF34C759)

/** [drop] pops that many pages at once (a finished flow leaves together, as iOS dismiss does). */
@Composable
internal fun accountSecurityRoute(page: String, push: (String) -> Unit, pop: () -> Unit, drop: (Int) -> Unit): Boolean {
    when {
        page == PAGE_EMAIL -> ChangeEmailPage(push, pop)
        page.startsWith(PAGE_EMAIL_CODE) -> EmailCodePage(page.removePrefix(PAGE_EMAIL_CODE), pop, onDone = { drop(2) })
        page == PAGE_TWOSTEP -> TwoStepPage(push, pop)
        page.startsWith(PAGE_TWOSTEP_SET) -> SetAdditionalPasswordPage(page.removePrefix(PAGE_TWOSTEP_SET) == "change", pop)
        page == PAGE_TWOSTEP_OFF -> DisableAdditionalPasswordPage(pop)
        else -> return false
    }
    return true
}

// ---------------------------------------------------------------- shared pieces

/** A text field inside a section card, iOS Form style: placeholder in grey, no outline. */
@Composable
internal fun CardField(
    value: String, placeholder: String, secret: Boolean = false, keyboard: KeyboardType = KeyboardType.Text,
    enabled: Boolean = true, last: Boolean = true, imeDone: (() -> Unit)? = null, onChange: (String) -> Unit,
) {
    val c = Fariin.colors
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)) {
        if (value.isEmpty()) Text(placeholder, fontSize = 17.sp, color = c.tertiary)
        BasicTextField(
            value, onChange, enabled = enabled, singleLine = true,
            textStyle = TextStyle(fontSize = 17.sp, color = c.label),
            cursorBrush = SolidColor(c.accent),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard, autoCorrectEnabled = false,
                imeAction = if (imeDone != null) androidx.compose.ui.text.input.ImeAction.Go else androidx.compose.ui.text.input.ImeAction.Default),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { imeDone?.invoke() }),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (!last) HorizontalDivider(thickness = 0.5.dp, color = c.separator)
}

/** The bar's text action (Send code, Confirm, Save, Turn Off), a spinner while the server works. */
@Composable
internal fun BarAction(text: String, enabled: Boolean, busy: Boolean = false, color: Color? = null, onClick: () -> Unit) {
    val c = Fariin.colors
    // Busy: the ring takes the word's place inside the same button, which keeps its width (iOS).
    TextButton(onClick = { if (!busy) onClick() }, enabled = enabled || busy) {
        com.fariin.app.ui.components.BusyContent(busy, c.label, size = 20.dp) {
            Text(text, fontWeight = FontWeight.SemiBold, color = if (enabled) color ?: c.accent else c.tertiary)
        }
    }
}

/**
 * iOS SignInAgainSection: the door the "sign in again" line asks for, then [onVerified] retries.
 * Android has the password door here; Apple and Google re-auth are not ported yet.
 */
@Composable
internal fun SignInAgainSection(onVerified: () -> Unit) {
    val c = Fariin.colors
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    SettingsSection(header = "One more check",
        footer = "You have been signed in a while, so we need to confirm it is really you before changing this.") {
        fun verify() {
            if (password.isEmpty() || busy) return
            busy = true; error = null
            scope.launch {
                try { AuthRepository().reauthEmail(password); password = ""; busy = false; onVerified() }
                catch (e: Exception) { busy = false; error = AuthRepository.messageFor(e, "That password is not right.") }
            }
        }
        // The keyboard's Go verifies too: on a small phone the keyboard covers the Verify row.
        CardField(password, "Your current password", secret = true, enabled = !busy, last = false, imeDone = { verify() }) { password = it }
        SettingsRow(if (busy) "Checking…" else "Verify", trailing = Trailing.NONE,
            titleColor = if (password.isEmpty() || busy) c.secondary else c.accent, last = error == null,
            accessory = if (busy) ({ com.fariin.app.ui.components.FariinSpinner(size = 20.dp) }) else null) { verify() }
        error?.let { Text(it, fontSize = 13.sp, color = c.red, modifier = Modifier.padding(16.dp)) }
    }
}

// ---------------------------------------------------------------- Email address

private enum class Door(val providerId: String, val title: String) {
    Apple("apple.com", "Apple"), Google("google.com", "Google"), Email("password", "Password")
}

/** AuthService.connectedIdentifier, Apple first, then Google, then a password address. */
private fun currentDoor(): Pair<Door, String>? {
    val user = FirebaseAuth.getInstance().currentUser ?: return null
    for (d in Door.entries) {
        val info = user.providerData.firstOrNull { it.providerId == d.providerId } ?: continue
        return d to (info.email?.takeIf { it.isNotEmpty() } ?: user.email ?: "")
    }
    return null
}

/** iOS ChangeEmailView: the current door in one card, the new address in another, Send code in the bar. */
@Composable
private fun ChangeEmailPage(push: (String) -> Unit, pop: () -> Unit) {
    val c = Fariin.colors
    val scope = rememberCoroutineScope()
    var newEmail by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var needsReauth by remember { mutableStateOf(false) }
    val door = remember { currentDoor() }
    val canSend = EmailChangeRules.canSend(newEmail, FirebaseAuth.getInstance().currentUser?.email, sending)

    fun send() {
        if (!EmailChangeRules.canSend(newEmail, FirebaseAuth.getInstance().currentUser?.email, sending)) return
        sending = true; error = null
        scope.launch {
            try {
                AccountCall.run("startEmailChange", mapOf("newEmail" to newEmail.trim()))
                push(PAGE_EMAIL_CODE + EmailChangeRules.normalized(newEmail))
            } catch (e: AccountCallError) {
                error = e.message; needsReauth = e.needsReauth
            } finally { sending = false }
        }
    }

    ScrollPage("Change email", pop, action = { BarAction("Send code", canSend, busy = sending) { send() } }) {
        if (door != null) SettingsSection(header = "Current email") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                    when (door.first) {
                        Door.Google -> Image(painterResource(R.drawable.ic_google_g), null, Modifier.size(22.dp))
                        Door.Apple -> Icon(painterResource(R.drawable.ic_account), null, tint = c.label, modifier = Modifier.size(22.dp))
                        Door.Email -> Icon(ImageVector.vectorResource(R.drawable.ic_sf_envelope), null, tint = c.label, modifier = Modifier.size(20.dp))
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(door.first.title, fontSize = 17.sp, color = c.label)
                    Text(door.second, fontSize = 13.sp, color = c.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(8.dp))
                Icon(ImageVector.vectorResource(R.drawable.ic_sf_checkmark_circle_fill), contentDescription = "Confirmed", tint = IosGreen, modifier = Modifier.size(22.dp))
            }
        }
        SettingsSection(header = "New email", footer = "We'll send a 6-digit code to your new address to confirm it's yours.") {
            CardField(newEmail, "you@example.com", keyboard = KeyboardType.Email, enabled = !sending) { newEmail = it }
        }
        ErrorNote(error)
        if (needsReauth) SignInAgainSection { needsReauth = false; error = null; send() }
    }
}

/** iOS EmailCodeView: the code, Confirm in the bar. Pushed only once the server took the address. */
@Composable
private fun EmailCodePage(address: String, pop: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var needsReauth by remember { mutableStateOf(false) }
    val canConfirm = !working && code.length == 6

    fun confirm() {
        if (working || code.length != 6) return
        working = true; error = null
        scope.launch {
            try {
                val before = TwoStepGate.tokenBefore()
                val reply = AccountCall.run("confirmEmailChange", mapOf("code" to code))
                // Every refresh token was revoked, this phone's too: sign in with the session the server
                // minted after it, keep a two-step session through the door, then read the new address.
                if (reply["customToken"] is String) {
                    AccountCall.adoptSession(reply)
                    TwoStepGate.renewAfterReauth(before)
                }
                runCatching { FirebaseAuth.getInstance().currentUser?.reload()?.await() }
                runCatching { FirebaseAuth.getInstance().currentUser?.getIdToken(true)?.await() }
                onDone()
            } catch (e: AccountCallError) {
                error = e.message; needsReauth = e.needsReauth
            } finally { working = false }
        }
    }

    ScrollPage("Change email", pop, action = { BarAction("Confirm", canConfirm, busy = working) { confirm() } }) {
        SettingsSection(header = "Verification code", footer = EmailChangeRules.codeFooter(address)) {
            CardField(code, "6-digit code", keyboard = KeyboardType.NumberPassword, enabled = !working) { code = TwoStepRules.code(it) }
        }
        ErrorNote(error)
        if (needsReauth) SignInAgainSection { needsReauth = false; error = null; confirm() }
    }
}

// ---------------------------------------------------------------- Two-step verification

/** iOS TwoStepVerificationView: an explanation and one action until it is on, a list once it is. */
@Composable
private fun TwoStepPage(push: (String) -> Unit, pop: () -> Unit) {
    val c = Fariin.colors
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var loadedOnce by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf("") }
    var masked by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        loading = true
        try {
            val d = AccountCall.run("twoStepStatus")
            enabled = d["enabled"] == true
            hint = d["hint"] as? String ?: ""
            masked = d["recoveryEmail"] as? String ?: ""
            loadedOnce = true; error = null
        } catch (e: AccountCallError) { error = e.message }
        loading = false
    }
    LaunchedEffect(Unit) { load() }

    val barScroll = rememberScrollState()
    BarPage("Two-step verification", onBack = pop, under = enabled && underBar(barScroll)) { top ->
        when {
            loading -> com.fariin.app.ui.components.PageLoading()
            !loadedOnce -> Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("Two-step verification", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = c.label)
                Spacer(Modifier.height(8.dp))
                Text(error ?: "Something went wrong. Try again.", fontSize = 15.sp, color = c.secondary, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { scope.launch { load() } }, colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent)) { Text("Try Again") }
            }
            enabled -> Column(Modifier.fillMaxSize().verticalScroll(barScroll).padding(bottom = com.fariin.app.ui.shell.bottomBarInset()).padding(top = top + 8.dp)) {
                SettingsSection(footer = "An additional password is required when you sign in on a new device.") {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(ImageVector.vectorResource(R.drawable.ic_sf_shield_fill), null, tint = IosGreen, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("Two-step verification is on", fontSize = 17.sp, color = IosGreen)
                    }
                }
                if (hint.isNotEmpty() || masked.isNotEmpty()) SettingsSection(footer = TwoStepRules.recoveryFooter(masked)) {
                    if (hint.isNotEmpty()) SettingsRow("Hint", value = hint, trailing = Trailing.NONE, last = masked.isEmpty())
                    if (masked.isNotEmpty()) SettingsRow("Recovery email", value = masked, trailing = Trailing.NONE, last = true)
                } else Text(TwoStepRules.recoveryFooter(masked), fontSize = 13.sp, color = c.secondary, modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp))
                SettingsSection {
                    SettingsRow("Change Additional Password", trailing = Trailing.NONE, titleColor = c.accent) { push(PAGE_TWOSTEP_SET + "change") }
                    SettingsRow("Turn Off Two-step Verification", trailing = Trailing.NONE, titleColor = c.red, last = true) { push(PAGE_TWOSTEP_OFF) }
                }
                ErrorNote(error)
            }
            else -> Column(Modifier.fillMaxSize().padding(top = top).padding(bottom = com.fariin.app.ui.shell.bottomBarInset())) {
                Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text("🔐", fontSize = 80.sp)
                    Text("Additional Password", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = c.label, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 18.dp))
                    Text("You can set a password that will be required when you sign in to Fariin on a new device, in addition to your usual sign-in.",
                        fontSize = 16.sp, color = c.secondary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
                    error?.let { Text(it, fontSize = 13.sp, color = c.red, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp)) }
                }
                Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).height(54.dp).background(IosGreen, CircleShape)
                    .clickable { push(PAGE_TWOSTEP_SET + "first") }, contentAlignment = Alignment.Center) {
                    Text("Set Additional Password", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                }
            }
        }
    }
}

/** iOS SetAdditionalPasswordView: set it, or change it (the old one is demanded, as the server does). */
@Composable
private fun SetAdditionalPasswordPage(isChange: Boolean, pop: () -> Unit) {
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf("") }
    var recovery by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var needsReauth by remember { mutableStateOf(false) }
    val canSave = TwoStepRules.canSave(isChange, current, password, confirm, busy)

    fun save() {
        if (!TwoStepRules.canSave(isChange, current, password, confirm, busy)) return
        busy = true; error = null
        scope.launch {
            val payload = mutableMapOf<String, Any?>("password" to password, "hint" to hint, "recoveryEmail" to recovery)
            if (isChange) payload["current"] = current
            try {
                AccountCall.run("setAdditionalPassword", payload)
                // Turning it on revokes every refresh token; pick up the new one before anything reads.
                runCatching { FirebaseAuth.getInstance().currentUser?.getIdToken(true)?.await() }
                pop()
            } catch (e: AccountCallError) { error = e.message; needsReauth = e.needsReauth }
            finally { busy = false }
        }
    }

    ScrollPage(if (isChange) "Change Password" else "Additional Password", pop, action = { BarAction("Save", canSave, busy = busy) { save() } }) {
        if (isChange) SettingsSection(header = "Current") { CardField(current, "Current additional password", secret = true) { current = it } }
        SettingsSection(header = if (isChange) "New" else "Additional password",
            footer = "At least 6 characters. You will be asked for this when you sign in on a new device.") {
            CardField(password, "New password", secret = true, last = false) { password = it }
            CardField(confirm, "Confirm", secret = true) { confirm = it }
        }
        SettingsSection(footer = "Anyone holding your signed-in phone can read the hint, so it must not be the password itself.") {
            CardField(hint, "Hint (optional)") { hint = it }
        }
        SettingsSection(footer = "The only way back if you forget this password. Without one, it cannot be turned off.") {
            CardField(recovery, "Recovery email (optional)", keyboard = KeyboardType.Email) { recovery = it }
        }
        ErrorNote(error)
        if (needsReauth) SignInAgainSection { needsReauth = false; error = null; save() }
    }
}

/** iOS DisableAdditionalPasswordView: the password turns it off; the recovery code is the way back. */
@Composable
private fun DisableAdditionalPasswordPage(pop: () -> Unit) {
    val c = Fariin.colors
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var recoveryCode by remember { mutableStateOf("") }
    var recoverySent by remember { mutableStateOf(false) }
    var needsReauth by remember { mutableStateOf(false) }

    fun turnOff() {
        if (busy) return
        busy = true; error = null; needsReauth = false
        scope.launch {
            try {
                // Ends every other session; this phone goes on with the session minted after the revoke.
                AccountCall.adoptSession(AccountCall.run("disableAdditionalPassword", mapOf("password" to password)))
                pop()
            } catch (e: AccountCallError) { needsReauth = e.needsReauth; error = e.message }
            finally { busy = false }
        }
    }

    ScrollPage("Turn Off", pop, action = { BarAction("Turn Off", password.isNotEmpty(), busy = busy, color = c.red) { turnOff() } }) {
        SettingsSection(footer = "Enter your additional password to turn two-step verification off.") {
            CardField(password, "Additional password", secret = true) { password = it }
        }
        if (needsReauth) SignInAgainSection { needsReauth = false; error = null; turnOff() }
        TwoStepRecoveryRows(busy, recoverySent, recoveryCode, onCode = { recoveryCode = TwoStepRules.code(it) },
            onStart = {
                busy = true; error = null
                scope.launch {
                    try { AccountCall.run("startTwoStepRecovery"); recoverySent = true } catch (e: AccountCallError) { error = e.message }
                    busy = false
                }
            },
            onFinish = {
                busy = true; error = null
                scope.launch {
                    try { AccountCall.adoptSession(AccountCall.run("confirmTwoStepRecovery", mapOf("code" to recoveryCode))); pop() }
                    catch (e: AccountCallError) { error = e.message }
                    busy = false
                }
            })
        ErrorNote(error)
    }
}

/** "I forgot this password", then the emailed code and "Turn Off With Code" (iOS, both pages). */
@Composable
internal fun TwoStepRecoveryRows(busy: Boolean, sent: Boolean, code: String, onCode: (String) -> Unit, onStart: () -> Unit, onFinish: () -> Unit) {
    val c = Fariin.colors
    SettingsSection {
        SettingsRow("I forgot this password", trailing = Trailing.NONE, titleColor = if (busy) c.tertiary else c.accent, last = true) { if (!busy) onStart() }
    }
    if (sent) SettingsSection(footer = "We sent a code to your recovery email.") {
        CardField(code, "6-digit code", keyboard = KeyboardType.NumberPassword, last = false, onChange = onCode)
        val ready = code.length == 6 && !busy
        SettingsRow("Turn Off With Code", trailing = Trailing.NONE, titleColor = if (ready) c.accent else c.tertiary, last = true) { if (ready) onFinish() }
    }
}

/** For the Account page: show the Two-step row only for an account that has it on (iOS, flag off). */
@Composable
internal fun rememberHasTwoStep(): Boolean {
    var has by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { has = TwoStepGate.accountHasTwoStep() }
    return has
}

