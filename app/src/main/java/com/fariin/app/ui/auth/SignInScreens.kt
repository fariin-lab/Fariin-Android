package com.fariin.app.ui.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.R
import com.fariin.app.data.AuthRepository
import com.fariin.app.ui.theme.Fariin
import kotlinx.coroutines.launch

/**
 * The first-run sign-in pages, in the iOS order (Kulan-iOS/Kulan/Views/AuthFlowViews.swift):
 *
 *  1. WelcomeView: logo, "Log in to Fariin", "Private chats, calls and stories.",
 *     then "Create Account" (filled) and "Log in" (plain).
 *  2. AuthMethodView: logo, "Log in to Fariin" / "Create your account", the sign-in doors, and
 *     an outlined "Create account" / "Log in" button at the foot that swaps the mode.
 *  3. EmailAuthView: "Log in with email", email, password, "Log in", "Forgot password?".
 *
 * TODO (iOS has these, not ported yet): Sign in with Apple and "Continue with Google" above an
 * "or" line on page 2; the passkey offer when the welcome page opens; saved accounts
 * ("Last used", "Add account") on page 2; email sign-up with a verification code and the
 * password checklist; "Log in with a code" when an email is already taken; the Terms
 * agreement page and the notifications explainer after sign-in.
 */
enum class AuthMode { Create, Login }

private sealed interface AuthPage {
    data object Welcome : AuthPage
    data class Method(val mode: AuthMode) : AuthPage
    data class Email(val mode: AuthMode) : AuthPage
}

@Composable
fun SignInFlow(auth: AuthRepository) {
    var stack by rememberSaveable(stateSaver = PageStackSaver) { mutableStateOf(listOf<AuthPage>(AuthPage.Welcome)) }
    val page = stack.last()
    BackHandler(enabled = stack.size > 1) { stack = stack.dropLast(1) }
    val push: (AuthPage) -> Unit = { stack = stack + it }
    val pop: () -> Unit = { stack = stack.dropLast(1) }

    Box(Modifier.fillMaxSize().background(Fariin.colors.bg)) {
        when (page) {
            AuthPage.Welcome -> WelcomePage(
                onCreate = { push(AuthPage.Method(AuthMode.Create)) },
                onLogin = { push(AuthPage.Method(AuthMode.Login)) },
            )
            is AuthPage.Method -> MethodPage(
                mode = page.mode,
                onBack = pop,
                onEmail = { push(AuthPage.Email(page.mode)) },
                onSwapMode = { stack = stack.dropLast(1) + AuthPage.Method(it) },
            )
            is AuthPage.Email -> EmailPage(
                mode = page.mode,
                auth = auth,
                onBack = pop,
                onSwapMode = { stack = stack.dropLast(2) + AuthPage.Method(it) + AuthPage.Email(it) },
            )
        }
    }
}

// ---- Page 1 -------------------------------------------------------------------------------

@Composable
private fun WelcomePage(onCreate: () -> Unit, onLogin: () -> Unit) {
    val c = Fariin.colors
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Logo(120)
        Text(
            "Log in to Fariin",
            fontSize = 26.sp, fontWeight = FontWeight.Bold, color = c.label,
            modifier = Modifier.padding(top = 22.dp),
        )
        Text(
            "Private chats, calls and stories.",
            fontSize = 15.sp, color = c.secondary, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        Spacer(Modifier.weight(2f))
        PrimaryPill("Create Account", onClick = onCreate)
        Spacer(Modifier.height(12.dp))
        RaisedPill("Log in", onClick = onLogin)
        Spacer(Modifier.height(16.dp))
    }
}

// ---- Page 2 -------------------------------------------------------------------------------

@Composable
private fun MethodPage(mode: AuthMode, onBack: () -> Unit, onEmail: () -> Unit, onSwapMode: (AuthMode) -> Unit) {
    val c = Fariin.colors
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BackRow(onBack)
        Column(
            Modifier.weight(1f).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(48.dp))
            Logo(76)
            Text(
                if (mode == AuthMode.Create) "Create your account" else "Log in to Fariin",
                fontSize = 28.sp, fontWeight = FontWeight.Bold, color = c.label,
                modifier = Modifier.padding(top = 22.dp, bottom = 36.dp),
            )
            // TODO: Sign in with Apple, "Continue with Google", then an "or" line, as on iOS.
            RaisedPill("Continue with Email", onClick = onEmail)
            Spacer(Modifier.weight(1f))
            val toCreate = mode == AuthMode.Login
            OutlinePill(if (toCreate) "Create account" else "Log in") {
                onSwapMode(if (toCreate) AuthMode.Create else AuthMode.Login)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

// ---- Page 3 -------------------------------------------------------------------------------

@Composable
private fun EmailPage(mode: AuthMode, auth: AuthRepository, onBack: () -> Unit, onSwapMode: (AuthMode) -> Unit) {
    val c = Fariin.colors
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var busy by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var note by rememberSaveable { mutableStateOf<String?>(null) }
    val passwordFocus = androidx.compose.runtime.remember { FocusRequester() }

    val emailOk = EMAIL_RE.matches(email.trim())
    val canSubmit = mode == AuthMode.Login && emailOk && password.isNotEmpty() && !busy

    fun submit() {
        if (!canSubmit) return
        focus.clearFocus()
        busy = true; error = null; note = null
        scope.launch {
            try {
                auth.signInEmail(email, password)
                // The auth listener in MainActivity moves on to the app.
            } catch (e: Exception) {
                error = AuthRepository.messageFor(e)
            } finally {
                busy = false
            }
        }
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding(),
    ) {
        BackRow(onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Text(
                if (mode == AuthMode.Login) "Log in with email" else "Create account",
                fontSize = 28.sp, fontWeight = FontWeight.Bold, color = c.label,
            )
            if (mode == AuthMode.Create) {
                Text(
                    "We’ll send a verification code to your email.",
                    fontSize = 15.sp, color = c.secondary, modifier = Modifier.padding(top = 8.dp),
                )
            }
            Spacer(Modifier.height(24.dp))

            if (mode == AuthMode.Login) {
                Field(
                    value = email, onChange = { email = it; error = null }, placeholder = "Email",
                    keyboard = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                    actions = KeyboardActions(onNext = { passwordFocus.requestFocus() }),
                )
                if (email.isNotBlank() && !emailOk && email.contains("@") && email.substringAfter("@").contains(".")) {
                    Text("Enter a correct email address, like name@example.com",
                        fontSize = 13.sp, color = c.red, modifier = Modifier.padding(top = 6.dp))
                }
                Spacer(Modifier.height(12.dp))
                Field(
                    value = password, onChange = { password = it; error = null }, placeholder = "Password",
                    keyboard = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                    actions = KeyboardActions(onGo = { submit() }),
                    visual = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    modifier = Modifier.focusRequester(passwordFocus),
                    trailing = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (showPassword) "Hide password" else "Show password",
                                tint = c.secondary,
                            )
                        }
                    },
                )
                Spacer(Modifier.height(20.dp))
                PrimaryPill("Log in", enabled = canSubmit, busy = busy, onClick = ::submit)
                Text(
                    "Forgot password?",
                    fontSize = 15.sp, fontWeight = FontWeight.Medium, color = c.label,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 12.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = !busy) {
                            if (!emailOk) {
                                error = "Type your email first."
                                return@clickable
                            }
                            busy = true; error = null; note = null
                            scope.launch {
                                try {
                                    auth.resetPassword(email)
                                    note = "If this email has an account, we sent a link to reset the password."
                                } catch (e: Exception) {
                                    error = "Could not send the email. Try again."
                                } finally {
                                    busy = false
                                }
                            }
                        }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
            } else {
                // TODO: email sign-up (iOS AuthService.createEmailAccount): email, verification
                // code, then "Create a password" with the 8 characters / letter / number checklist.
                Text(
                    "Creating an account is not ready in the Android app yet.",
                    fontSize = 15.sp, color = c.secondary,
                )
            }

            error?.let {
                Text(it, fontSize = 13.sp, color = c.red, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
            }
            note?.let {
                Text(it, fontSize = 13.sp, color = c.secondary, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 28.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    if (mode == AuthMode.Login) "Don’t have an account?" else "Already have an account?",
                    fontSize = 15.sp, color = c.secondary,
                )
                Text(
                    if (mode == AuthMode.Login) "Sign up" else "Log in",
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.label,
                    modifier = Modifier.padding(start = 5.dp).clickable {
                        onSwapMode(if (mode == AuthMode.Login) AuthMode.Create else AuthMode.Login)
                    },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ---- Pieces -------------------------------------------------------------------------------

@Composable
private fun Logo(side: Int) {
    Image(
        painterResource(R.drawable.launch_logo),
        contentDescription = "Fariin",
        modifier = Modifier.size(side.dp),
    )
}

@Composable
private fun BackRow(onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(52.dp).padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Fariin.colors.label)
        }
    }
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    keyboard: KeyboardOptions,
    actions: KeyboardActions,
    modifier: Modifier = Modifier,
    visual: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = Fariin.colors
    TextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text(placeholder, color = c.secondary) },
        singleLine = true,
        keyboardOptions = keyboard,
        keyboardActions = actions,
        visualTransformation = visual,
        trailingIcon = trailing,
        shape = RoundedCornerShape(14.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = c.fieldFill,
            unfocusedContainerColor = c.fieldFill,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            cursorColor = c.label,
            focusedTextColor = c.label,
            unfocusedTextColor = c.label,
        ),
        modifier = modifier.fillMaxWidth().heightIn(min = 54.dp),
    )
}

/** iOS `authPrimaryPill`: filled black (white in dark), 50 high. */
@Composable
private fun PrimaryPill(text: String, enabled: Boolean = true, busy: Boolean = false, onClick: () -> Unit) {
    val c = Fariin.colors
    Box(
        Modifier.fillMaxWidth().height(50.dp).clip(CircleShape)
            .background(if (enabled || busy) c.accent else c.accent.copy(alpha = 0.3f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(20.dp), color = c.onAccent, strokeWidth = 2.dp)
        } else {
            Text(text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.onAccent)
        }
    }
}

/** iOS `authRaisedPill` (a glass capsule there): a soft grey capsule here. */
@Composable
private fun RaisedPill(text: String, onClick: () -> Unit) {
    val c = Fariin.colors
    Box(
        Modifier.fillMaxWidth().height(50.dp).clip(CircleShape).background(c.fieldFill).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = c.label)
    }
}

/** The foot button on page 2: outlined capsule, 54 high. */
@Composable
private fun OutlinePill(text: String, onClick: () -> Unit) {
    val c = Fariin.colors
    Box(
        Modifier.fillMaxWidth().height(54.dp).clip(CircleShape)
            .border(BorderStroke(1.5.dp, c.label), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label)
    }
}

private val EMAIL_RE = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$")

private val PageStackSaver = androidx.compose.runtime.saveable.listSaver<List<AuthPage>, String>(
    save = { pages ->
        pages.map {
            when (it) {
                AuthPage.Welcome -> "w"
                is AuthPage.Method -> "m:" + it.mode.name
                is AuthPage.Email -> "e:" + it.mode.name
            }
        }
    },
    restore = { keys ->
        keys.map {
            when {
                it.startsWith("m:") -> AuthPage.Method(AuthMode.valueOf(it.removePrefix("m:")))
                it.startsWith("e:") -> AuthPage.Email(AuthMode.valueOf(it.removePrefix("e:")))
                else -> AuthPage.Welcome
            }
        }
    },
)
