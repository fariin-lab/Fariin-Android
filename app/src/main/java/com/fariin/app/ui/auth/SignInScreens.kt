package com.fariin.app.ui.auth

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.R
import com.fariin.app.auth.GoogleSignIn
import com.fariin.app.auth.Passkeys
import com.fariin.app.auth.SheetCancelled
import com.fariin.app.data.AuthFlowError
import com.fariin.app.data.AuthMessages
import com.fariin.app.data.AuthRepository
import com.fariin.app.data.CodePurpose
import com.fariin.app.data.DeviceSessionKeys
import com.fariin.app.data.EmailRules
import com.fariin.app.data.PasswordRules
import com.fariin.app.data.SavedAccount
import com.fariin.app.data.SavedAccounts
import com.fariin.app.data.SignInMethod
import com.fariin.app.ui.theme.Fariin
import kotlinx.coroutines.launch

/**
 * The signed-out flow, a port of Kulan-iOS Views/FirstRunViews.swift (AgreementView) and
 * Views/AuthFlowViews.swift (WelcomeView, AuthMethodView, EmailAuthView), same order and words:
 *
 *  0. Agreement, once per install: logo, "Welcome to Fariin", the Terms line, "Agree and continue".
 *  1. Welcome: logo, "Log in to Fariin", "Private chats, calls and stories.", "Create Account" and
 *     "Log in". A phone that remembers accounts opens straight on their cards instead. A passkey
 *     this phone holds is offered by itself (nothing shows when there is none).
 *  2. The doors: Apple, Google, "or", Email, with "Last used" on the door used last; the foot
 *     button swaps Create / Log in. Accounts face: one card per saved account and "Add account".
 *  3. Email: log in (email, password, "Forgot password?") or create (email, then the code page,
 *     then "Create a password" with the checklist). A taken email asks "Are you trying to log in?".
 *  4. The code page (CodePage.kt): Forgot password, Confirm your email, Check your email.
 * Signing in flips MainActivity to the signed-in side, where AccountGate runs onboarding.
 */
enum class AuthMode { Create, Login }

sealed interface AuthPage {
    data object Welcome : AuthPage
    data class Method(val mode: AuthMode, val showDoors: Boolean = false) : AuthPage
    data class Email(val mode: AuthMode, val prefill: String = "") : AuthPage
    data class Code(val purpose: CodePurpose, val email: String) : AuthPage
}

/** FirstRun.termsAgreedAtKey: the moment "Agree and continue" was tapped, once per install. */
object FirstRun {
    private const val PREFS = "fariin_first_run"
    private const val KEY = "firstRun.termsAgreedAt"
    fun agreedAt(c: Context): Long = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY, 0L)
    fun agree(c: Context) { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY, System.currentTimeMillis() / 1000).apply() }
}

internal fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

@Composable
fun SignInFlow(auth: AuthRepository) {
    val ctx = LocalContext.current
    var agreed by remember { mutableStateOf(FirstRun.agreedAt(ctx) > 0) }
    Box(Modifier.fillMaxSize().background(Fariin.colors.bg)) {
        if (!agreed) AgreementPage { FirstRun.agree(ctx); agreed = true }
        else AuthPages(auth, start = if (SavedAccounts.all(ctx).isEmpty()) AuthPage.Welcome else AuthPage.Method(AuthMode.Login))
    }
}

/** The page stack. Covered pages keep their state (a sign-up ticket survives the code page). */
@Composable
internal fun AuthPages(auth: AuthRepository, start: AuthPage) {
    var stack by rememberSaveable(stateSaver = PageStackSaver) { mutableStateOf(listOf(start)) }
    val holder = rememberSaveableStateHolder()
    val page = stack.last()
    BackHandler(enabled = stack.size > 1) { stack = stack.dropLast(1) }
    val push: (AuthPage) -> Unit = { stack = stack + it }
    val pop: () -> Unit = { stack = stack.dropLast(1) }
    var ticket by remember { mutableStateOf<Pair<String, String>?>(null) }   // (email, ticket) from the code page
    val ctx = LocalContext.current
    var passkeyOffered by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // WelcomeView.offerPasskey: once, silent on every failure (no passkey, cancelled, offline).
        if (passkeyOffered) return@LaunchedEffect
        passkeyOffered = true
        val act = ctx.findActivity() ?: return@LaunchedEffect
        if (isLocalBackend(ctx)) return@LaunchedEffect
        try { Passkeys.signIn(act, auth, immediateOnly = true) } catch (_: Exception) {}
    }
    holder.SaveableStateProvider("${stack.size}:${pageKey(page)}") {
        Box(Modifier.fillMaxSize().background(Fariin.colors.bg).statusBarsPadding().navigationBarsPadding()) {
            when (page) {
                AuthPage.Welcome -> WelcomePage(
                    onCreate = { push(AuthPage.Method(AuthMode.Create, showDoors = true)) },
                    onLogin = { push(AuthPage.Method(AuthMode.Login, showDoors = true)) },
                )
                is AuthPage.Method -> MethodPage(
                    auth = auth, initialMode = page.mode, pushed = page.showDoors,
                    onBack = if (stack.size > 1) pop else null,
                    onEmail = { mode, prefill -> push(AuthPage.Email(mode, prefill)) },
                    onDoors = { mode -> push(AuthPage.Method(mode, showDoors = true)) },
                )
                is AuthPage.Email -> EmailPage(
                    auth = auth, initialMode = page.mode, prefill = page.prefill, onBack = pop,
                    onCode = { purpose, email -> push(AuthPage.Code(purpose, email)) },
                    ticket = ticket, consumeTicket = { ticket = null },
                )
                is AuthPage.Code -> CodePage(
                    auth = auth, purpose = page.purpose, email = page.email, onBack = pop,
                    onAuthed = {}, onTicket = { t -> ticket = page.email to t; pop() },
                )
            }
        }
    }
}

private fun pageKey(p: AuthPage): String = when (p) {
    AuthPage.Welcome -> "w"
    is AuthPage.Method -> "m:${p.mode.name}:${p.showDoors}"
    is AuthPage.Email -> "e:${p.mode.name}:${p.prefill}"
    is AuthPage.Code -> "c:${p.purpose.name}:${p.email}"
}

private val PageStackSaver = androidx.compose.runtime.saveable.listSaver<List<AuthPage>, String>(
    save = { pages -> pages.map(::pageKey) },
    restore = { keys ->
        keys.map {
            val p = it.split(":", limit = 4)
            when (p[0]) {
                "m" -> AuthPage.Method(AuthMode.valueOf(p[1]), p.getOrNull(2) == "true")
                "e" -> AuthPage.Email(AuthMode.valueOf(p[1]), p.getOrNull(2) ?: "")
                "c" -> AuthPage.Code(CodePurpose.valueOf(p[1]), p.getOrNull(2) ?: "")
                else -> AuthPage.Welcome
            }
        }
    },
)

// ---- 0. Agreement ------------------------------------------------------------------------

@Composable
internal fun AgreementPage(onAgree: () -> Unit) {
    val c = Fariin.colors
    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(40.dp))
        Spacer(Modifier.weight(1f))
        Logo(120)
        Text("Welcome to Fariin", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = c.label, modifier = Modifier.padding(top = 40.dp))
        Spacer(Modifier.weight(2f))
        TermsLine("By continuing, you agree to our ", " and ", ".", fontSize = 15)
        Spacer(Modifier.height(16.dp))
        PrimaryPill("Agree and continue", onClick = onAgree)
        Spacer(Modifier.height(16.dp))
    }
}

/** "... our Terms and Privacy Policy." with both words as links to fariin.com. */
@Composable
internal fun TermsLine(before: String, middle: String, after: String, fontSize: Int) {
    val c = Fariin.colors
    val style = TextLinkStyles(SpanStyle(color = c.label))
    val text = buildAnnotatedString {
        append(before)
        withLink(LinkAnnotation.Url("https://fariin.com/terms", style)) { append("Terms") }
        append(middle)
        withLink(LinkAnnotation.Url("https://fariin.com/privacy", style)) { append("Privacy Policy") }
        append(after)
    }
    Text(text, fontSize = fontSize.sp, color = c.secondary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
}

// ---- 1. Welcome --------------------------------------------------------------------------

@Composable
internal fun WelcomePage(onCreate: () -> Unit, onLogin: () -> Unit) {
    val c = Fariin.colors
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        Logo(120)
        Text("Log in to Fariin", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = c.label, modifier = Modifier.padding(top = 22.dp))
        Text("Private chats, calls and stories.", fontSize = 15.sp, color = c.secondary, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.weight(2f))
        // Black (white on a dark phone) for the main action, the plain one under it, as iOS.
        Box(Modifier.fillMaxWidth().height(50.dp).clip(CircleShape).background(c.label)
            .combinedClickable(onClick = onCreate), contentAlignment = Alignment.Center) {
            Text("Create Account", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.bg)
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(50.dp).clip(CircleShape).background(c.fieldFill)
            .combinedClickable(onClick = onLogin), contentAlignment = Alignment.Center) {
            Text("Log in", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label)
        }
        Spacer(Modifier.height(16.dp))
    }
}

// ---- 2. The doors ------------------------------------------------------------------------

@Composable
internal fun MethodPage(
    auth: AuthRepository,
    initialMode: AuthMode,
    pushed: Boolean,
    onBack: (() -> Unit)?,
    onEmail: (AuthMode, String) -> Unit,
    onDoors: (AuthMode) -> Unit,
    demoSaved: List<SavedAccount>? = null,
    demoError: String? = null,
) {
    val c = Fariin.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by rememberSaveable { mutableStateOf(initialMode) }
    var saved by remember { mutableStateOf(demoSaved ?: SavedAccounts.all(ctx)) }
    val photos = remember(saved) { saved.associate { it.uid to SavedAccounts.photo(ctx, it.uid) } }
    var busy by remember { mutableStateOf(false) }
    var spinning by remember { mutableStateOf<SignInMethod?>(null) }
    var resuming by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf(demoError) }
    val accountsFace = mode == AuthMode.Login && saved.isNotEmpty() && !pushed
    val lastDoor = if (mode == AuthMode.Login) SavedAccounts.lastMethod(ctx) else null

    fun run(door: SignInMethod?, op: suspend (Activity) -> Unit) {
        if (busy) return
        val act = ctx.findActivity() ?: return
        busy = true; error = null; spinning = door
        scope.launch {
            try {
                op(act)
                auth.welcomeBack?.let { words ->
                    auth.welcomeBack = null
                    Handler(Looper.getMainLooper()).postDelayed({ Toast.makeText(ctx.applicationContext, words, Toast.LENGTH_SHORT).show() }, 1200)
                }
            } catch (e: Exception) {
                if (e !is SheetCancelled && !AuthRepository.isCancelled(e)) error = AuthRepository.doorMessageFor(e)
            }
            busy = false; spinning = null; resuming = null
        }
    }

    fun continueAs(a: SavedAccount) {
        if (busy) return
        busy = true; error = null; resuming = a.uid
        scope.launch {
            // One tap: the saved key first (DeviceSessionKeys.resume); a refusal falls back to the door.
            val resumed = try { DeviceSessionKeys.resume(ctx, a.uid) } catch (_: Exception) { false }
            busy = false
            if (resumed) return@launch
            resuming = null
            when (SignInMethod.of(a.method)) {
                SignInMethod.Apple -> { resuming = a.uid; run(SignInMethod.Apple) { auth.signInWithApple(it, requireExisting = true, requireNew = false) } }
                SignInMethod.Google -> { resuming = a.uid; run(SignInMethod.Google) { act -> auth.signInWithGoogleToken(GoogleSignIn.idToken(act), true, false) } }
                SignInMethod.Email -> onEmail(AuthMode.Login, a.email ?: "")
                null -> {}
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            if (onBack != null) Box(Modifier.fillMaxWidth().padding(start = 0.dp)) { Box(Modifier.padding(start = 0.dp)) { BackRow(onBack) } }
            if (accountsFace) {
                Spacer(Modifier.height(if (onBack == null) 120.dp else 68.dp))
                Logo(76, Modifier.padding(bottom = 36.dp))
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    for (a in saved) SavedAccountRow(a, photos[a.uid], lastUsed = SavedAccount.showsLastUsed(saved, a),
                        resuming = resuming == a.uid, enabled = !busy, onTap = { continueAs(a) },
                        onForget = { SavedAccounts.forget(ctx, a.uid); saved = saved.filter { it.uid != a.uid } })
                    Row(Modifier.fillMaxWidth().height(56.dp).clip(CircleShape).background(c.fieldFill)
                        .combinedClickable(enabled = !busy) { onDoors(AuthMode.Login) },
                        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Icon(ImageVector.vectorResource(R.drawable.ic_plus_ios), null, tint = c.label, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Add account", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label)
                    }
                }
            } else {
                Spacer(Modifier.height(if (onBack == null) 96.dp else 44.dp))
                Logo(76)
                Text(if (mode == AuthMode.Create) "Create your account" else "Log in to Fariin", fontSize = 28.sp,
                    fontWeight = FontWeight.Bold, color = c.label, modifier = Modifier.padding(top = 22.dp, bottom = 36.dp))
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    // Android: Google and email only (owner 2026-11-03: no Apple door on Android). An
                    // account made with Apple on an iPhone adds a password there to log in here.
                    DoorPill("Continue with Google", enabled = !busy, busy = spinning == SignInMethod.Google, lastUsed = lastDoor == SignInMethod.Google,
                        icon = { Image(painterResource(R.drawable.ic_google_g), null, Modifier.size(20.dp)) }) {
                        run(SignInMethod.Google) { act -> auth.signInWithGoogleToken(GoogleSignIn.idToken(act), mode == AuthMode.Login, mode == AuthMode.Create) }
                    }
                    OrDivider()
                    RaisedPill("Continue with Email", enabled = !busy, lastUsed = lastDoor == SignInMethod.Email) { onEmail(mode, "") }
                }
            }
            error?.let { Spacer(Modifier.height(12.dp)); ErrorLine(it) }
            Spacer(Modifier.weight(1f).height(24.dp))
            val toCreate = accountsFace || mode == AuthMode.Login
            SecondaryPill(if (toCreate) "Create account" else "Log in", height = 54, strong = true, enabled = !busy) {
                error = null
                if (pushed) mode = if (toCreate) AuthMode.Create else AuthMode.Login
                else onDoors(if (toCreate) AuthMode.Create else AuthMode.Login)
            }
            Spacer(Modifier.height(12.dp))
        }
        if (resuming != null && spinning == null && busy) SigningInOverlay()
    }
}

@Composable
private fun OrDivider() {
    val c = Fariin.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(0.5.dp).background(c.label.copy(alpha = 0.12f)))
        Text("or", fontSize = 15.sp, color = c.secondary, modifier = Modifier.padding(horizontal = 12.dp))
        Box(Modifier.weight(1f).height(0.5.dp).background(c.label.copy(alpha = 0.12f)))
    }
}

@Composable
private fun SigningInOverlay() {
    // iOS BusyOverlay: the page turns plain, the big ring and the words in the middle.
    com.fariin.app.ui.components.BusyOverlay("Signing In…")
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SavedAccountRow(a: SavedAccount, photo: Bitmap?, lastUsed: Boolean, resuming: Boolean, enabled: Boolean,
                            onTap: () -> Unit, onForget: () -> Unit) {
    val c = Fariin.colors
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(22.dp)).background(c.bg)
                .border(BorderStroke(1.dp, c.label.copy(alpha = 0.12f)), RoundedCornerShape(22.dp))
                .combinedClickable(enabled = enabled, onClick = onTap, onLongClick = { menu = true })
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (photo != null) Image(photo.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.size(48.dp).clip(CircleShape))
            else InitialAvatar(a.title, 48)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(a.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (lastUsed) Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(c.accent))
                    Spacer(Modifier.width(6.dp))
                    Text("Last used", fontSize = 15.sp, color = c.secondary)
                } else Text("@${a.handle}", fontSize = 15.sp, color = c.secondary, maxLines = 1)
            }
            Spacer(Modifier.width(8.dp))
            if (resuming) Busy(c.label, 20)
            else Icon(ImageVector.vectorResource(R.drawable.ic_sf_chevron_right), null, tint = c.tertiary)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Remove from This Phone", color = c.red) }, onClick = { menu = false; onForget() })
        }
    }
}

/** AvatarView with no photo: the first letter on a soft disc. */
@Composable
internal fun InitialAvatar(name: String, side: Int) {
    val c = Fariin.colors
    val letter = name.trimStart('@').firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    Box(Modifier.size(side.dp).clip(CircleShape).background(c.placeholderFill), contentAlignment = Alignment.Center) {
        Text(letter, fontSize = (side * 0.42f).sp, fontWeight = FontWeight.SemiBold, color = c.secondary)
    }
}

// ---- 3. Email ----------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EmailPage(
    auth: AuthRepository,
    initialMode: AuthMode,
    prefill: String,
    onBack: () -> Unit,
    onCode: (CodePurpose, String) -> Unit,
    ticket: Pair<String, String>?,
    consumeTicket: () -> Unit,
    demo: EmailDemo? = null,
) {
    val c = Fariin.colors
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var mode by rememberSaveable { mutableStateOf(initialMode) }
    var email by rememberSaveable { mutableStateOf(demo?.email ?: prefill) }
    var password by rememberSaveable { mutableStateOf(demo?.password ?: "") }
    var confirm by rememberSaveable { mutableStateOf(demo?.confirm ?: "") }
    var reveal by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(demo?.error) }
    var emailInvalid by remember { mutableStateOf(demo?.invalid == true) }
    var emailFocused by remember { mutableStateOf(false) }
    var showPassword by rememberSaveable { mutableStateOf(demo?.passwordStep == true) }
    var signupTicket by rememberSaveable { mutableStateOf<String?>(null) }
    var showTaken by remember { mutableStateOf(demo?.taken == true) }
    val emailFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }
    val confirmFocus = remember { FocusRequester() }

    val onEmailStep = mode == AuthMode.Create && !showPassword
    val rules = PasswordRules(password, confirm)
    val canSubmit = email.isNotEmpty() && password.isNotEmpty() && (mode == AuthMode.Login || rules.allMet)
    val primaryEnabled = onEmailStep || canSubmit
    val title = when { mode == AuthMode.Login -> "Log in with email"; onEmailStep -> "Create account"; else -> "Create a password" }
    val subtitle = when { mode == AuthMode.Login -> ""; onEmailStep -> "We’ll send a verification code to your email."
        else -> "Use at least 8 characters, with a letter and a number." }

    // The code page proved the address: on to the password step (EmailAuthView.ticketArrived).
    LaunchedEffect(ticket) {
        val t = ticket ?: return@LaunchedEffect
        if (t.first.trim() == email.trim()) { signupTicket = t.second; showPassword = true }
        consumeTicket()
        try { passwordFocus.requestFocus() } catch (_: Exception) {}
    }
    LaunchedEffect(Unit) {
        if (demo != null) return@LaunchedEffect
        try { if (mode == AuthMode.Login && email.isNotEmpty()) passwordFocus.requestFocus() else if (!showPassword) emailFocus.requestFocus() } catch (_: Exception) {}
    }

    fun backToEmail() {
        error = null; signupTicket = null; password = ""; confirm = ""; reveal = false; showPassword = false
        try { emailFocus.requestFocus() } catch (_: Exception) {}
    }
    fun advance() {
        error = null
        if (!EmailRules.looksValid(email)) { emailInvalid = true; try { emailFocus.requestFocus() } catch (_: Exception) {}; return }
        email = email.trim()
        focus.clearFocus()
        onCode(CodePurpose.NewAccount, email)
    }
    fun switchMode() {
        error = null; password = ""; confirm = ""; reveal = false
        mode = if (mode == AuthMode.Login) AuthMode.Create else AuthMode.Login
        showPassword = false
    }
    fun submit() {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try {
                if (mode == AuthMode.Create) {
                    val t = signupTicket
                    if (t == null) { backToEmail(); busy = false; return@launch }
                    try { auth.completeSignup(t, password) } catch (e: AuthFlowError) {
                        if (e.kind == AuthFlowError.Kind.SignupExpired) { backToEmail(); error = e.kind.message; busy = false; return@launch }
                        throw e
                    }
                } else {
                    // The signed-in side asks "Confirm your email" when this account never proved it.
                    auth.signInEmail(email.trim(), password)
                }
            } catch (e: AuthFlowError) {
                if (e.kind == AuthFlowError.Kind.EmailTaken && mode == AuthMode.Create) { focus.clearFocus(); showTaken = true }
                else error = e.kind.message
            } catch (e: Exception) {
                error = AuthRepository.messageFor(e)
            }
            busy = false
        }
    }

    val chips = EmailRules.domainChips(email, emailFocused)
    Column(Modifier.fillMaxSize().imePadding()) {
        BackRow(onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Logo(64, Modifier.padding(bottom = 14.dp))
                PageTitle(title, subtitle, top = 0)
            }
            if (mode == AuthMode.Create && showPassword) {
                Row(Modifier.fillMaxWidth().combinedClickable { backToEmail() }.padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(email, fontSize = 15.sp, color = c.secondary, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(8.dp))
                    Text("Change", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.label)
                }
            } else {
                AuthField(email, { email = it; emailInvalid = false }, "Email", ImageVector.vectorResource(R.drawable.ic_sf_envelope),
                    keyboard = EmailKeyboard, invalid = emailInvalid, focusRequester = emailFocus, onFocus = { emailFocused = it },
                    actions = KeyboardActions(onNext = { if (mode == AuthMode.Create) advance() else try { passwordFocus.requestFocus() } catch (_: Exception) {} }))
                if (emailInvalid) Text("Enter a correct email address, like name@example.com", fontSize = 13.sp, color = c.red,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp))
            }
            if (!onEmailStep) {
                AuthField(password, { password = it }, "Password", ImageVector.vectorResource(R.drawable.ic_sf_lock), secret = true, reveal = reveal, onReveal = { reveal = !reveal },
                    keyboard = passwordKeyboard(last = mode == AuthMode.Login), focusRequester = passwordFocus,
                    actions = KeyboardActions(onNext = { try { confirmFocus.requestFocus() } catch (_: Exception) {} }, onGo = { if (canSubmit) submit() }))
                if (mode == AuthMode.Create) {
                    AuthField(confirm, { confirm = it }, "Confirm password", ImageVector.vectorResource(R.drawable.ic_sf_lock), secret = true, reveal = reveal, onReveal = { reveal = !reveal },
                        keyboard = passwordKeyboard(last = true), focusRequester = confirmFocus,
                        actions = KeyboardActions(onGo = { if (canSubmit) submit() }))
                    PasswordChecklist(rules)
                }
            }
            Box(Modifier.padding(top = 4.dp)) {
                ActionPill(if (onEmailStep) "Continue" else if (mode == AuthMode.Create) "Create account" else "Log in",
                    enabled = primaryEnabled, busy = busy) { if (onEmailStep) advance() else submit() }
            }
            if (mode == AuthMode.Login) TextLink("Forgot password?", strong = false, color = c.label) { onCode(CodePurpose.Forgot, email.trim()) }
            ErrorLine(error)
            Spacer(Modifier.weight(1f).height(24.dp))
            SwapLine(if (mode == AuthMode.Login) "Don’t have an account?" else "Already have an account?",
                if (mode == AuthMode.Login) "Sign up" else "Log in") { switchMode() }
            Spacer(Modifier.height(12.dp))
        }
        if (chips.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().background(c.bg).horizontalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (d in chips) Box(Modifier.height(36.dp).clip(CircleShape).border(BorderStroke(1.dp, c.label.copy(alpha = 0.18f)), CircleShape)
                    .combinedClickable { email = EmailRules.applyDomain(email, d) }.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                    Text("@$d", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = c.label)
                }
            }
        }
    }

    if (showTaken) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        var choice by remember { mutableStateOf<String?>(null) }
        ModalBottomSheet(onDismissRequest = {
            showTaken = false
            when (choice) { "code" -> onCode(CodePurpose.Forgot, email.trim()); "other" -> backToEmail() }
        }, sheetState = sheet, containerColor = c.bg) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 12.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Are you trying to log in?", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = c.label)
                Text("This email already has an account. We can send a code to it so you can log in.", fontSize = 15.sp, color = c.secondary)
                Spacer(Modifier.height(6.dp))
                PrimaryPill("Log in with a code") { choice = "code"; scope.launch { sheet.hide(); showTaken = false; onCode(CodePurpose.Forgot, email.trim()) } }
                SecondaryPill("Use a different email") { choice = "other"; scope.launch { sheet.hide(); showTaken = false; backToEmail() } }
            }
        }
    }
}

/** Debug demo only: a starting state for the email page's screenshots. */
data class EmailDemo(val email: String = "", val password: String = "", val confirm: String = "", val passwordStep: Boolean = false,
                     val invalid: Boolean = false, val taken: Boolean = false, val error: String? = null)

internal val OfflineMessage = AuthMessages.OFFLINE

/** Debug builds pointed at the Firebase emulators (FariinApp's flag file): no callables there. */
internal fun isLocalBackend(c: Context): Boolean =
    (c.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 &&
        java.io.File(c.filesDir, com.fariin.app.FariinApp.LOCAL_FLAG).exists()
