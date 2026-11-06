package com.fariin.app.ui.auth

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.auth.Passkeys
import com.fariin.app.auth.SheetCancelled
import com.fariin.app.data.AuthRepository
import com.fariin.app.data.CodeEntry
import com.fariin.app.data.PasswordRules
import com.fariin.app.ui.theme.Fariin
import com.google.firebase.auth.FirebaseAuthException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** A top bar like the iOS inline title: back, the title in the middle, one action at the right. */
@Composable
private fun TitleBar(title: String, onBack: () -> Unit, action: String? = null, actionEnabled: Boolean = true, busy: Boolean = false, onAction: () -> Unit = {}) {
    val c = Fariin.colors
    Box(Modifier.fillMaxWidth().height(52.dp)) {
        IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = c.label)
        }
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label, modifier = Modifier.align(Alignment.Center))
        if (action != null) Box(Modifier.align(Alignment.CenterEnd).padding(end = 16.dp)) {
            // Busy: the ring in the word's place, the word kept unseen so the bar does not shift (iOS).
            com.fariin.app.ui.components.BusyContent(busy, c.label, size = 20.dp) {
                Text(action, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = if (actionEnabled) c.accent else c.tertiary,
                    modifier = Modifier.clickable(enabled = actionEnabled && !busy, onClick = onAction).padding(6.dp))
            }
        }
    }
}

@Composable
private fun StateCard(icon: ImageVector, title: String, body: String, button: String, onClick: () -> Unit) {
    val c = Fariin.colors
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = c.secondary, modifier = Modifier.size(48.dp))
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = c.label, modifier = Modifier.padding(top = 14.dp))
        Text(body, fontSize = 15.sp, color = c.secondary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp, bottom = 20.dp))
        Box(Modifier.width(200.dp)) { PrimaryPill(button, onClick) }
    }
}

private enum class Gate { Checking, Open, Refused, NoLock, NeedsReauth }

/**
 * Kulan-iOS Views/PasswordView.swift (Settings > Account > Password): the phone's own lock first
 * (Face ID there, the screen lock here), then current password (unless it is the first one), new,
 * confirm, the checklist, "After changing, all other devices will need to sign in again.", and
 * "Forgot password?" which mails a code (startPasswordReset) for [ResetCodePage].
 */
@Composable
fun PasswordPage(auth: AuthRepository, onBack: () -> Unit, onForgot: (String) -> Unit, demoOpen: Boolean = false) {
    val c = Fariin.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val isFirst = !auth.hasPassword && !demoOpen
    val address = auth.passwordAddress ?: ""
    var gate by remember { mutableStateOf(if (demoOpen) Gate.Open else Gate.Checking) }
    var current by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var sendingCode by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }
    var saveAfterReauth by remember { mutableStateOf(false) }
    val rules = PasswordRules(password, confirm)
    val canSave = rules.allMet && !busy && (isFirst || current.isNotEmpty())
    val lock = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        gate = if (r.resultCode == Activity.RESULT_OK) Gate.Open else Gate.Refused
    }

    fun prove() {
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!km.isDeviceSecure) { gate = Gate.NoLock; return }
        @Suppress("DEPRECATION")
        val i = km.createConfirmDeviceCredentialIntent(if (isFirst) "Set your Fariin password" else "Change your Fariin password", null)
        if (i == null) gate = Gate.Open else { gate = Gate.Checking; lock.launch(i) }
    }
    LaunchedEffect(Unit) { if (!demoOpen) prove() }

    fun save() {
        busy = true; error = null
        scope.launch {
            try {
                if (!isFirst) {
                    try { auth.reauthEmail(current) } catch (e: Exception) {
                        error = AuthRepository.messageFor(e, credentialHint = "That password is not right."); busy = false; return@launch
                    }
                }
                auth.setPassword(password, isFirst)
                done = true
            } catch (e: FirebaseAuthException) {
                if (e.errorCode == "ERROR_REQUIRES_RECENT_LOGIN") { saveAfterReauth = true; gate = Gate.NeedsReauth }
                else error = AuthRepository.messageFor(e)
            } catch (e: Exception) { error = AuthRepository.messageFor(e) }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().background(c.bgSecondary).statusBarsPadding().navigationBarsPadding().imePadding()) {
        TitleBar("Password", onBack, action = if (gate == Gate.Open) (if (isFirst) "Create" else "Save") else null,
            actionEnabled = canSave, busy = busy) { save() }
        when (gate) {
            Gate.Checking -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Busy(c.label, 40) }
            Gate.Refused -> StateCard(Icons.Outlined.LockOpen, "Not verified",
                "Unlock with your fingerprint, face or screen lock to change your password.", "Try Again") { prove() }
            Gate.NoLock -> StateCard(Icons.Outlined.Lock, "Lock your phone first",
                "Set a screen lock (PIN, pattern or password) in your phone's settings first, so nobody else holding it can change your password.",
                "Open Settings") { ctx.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            Gate.NeedsReauth -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("One more check", fontSize = 13.sp, color = c.secondary, modifier = Modifier.padding(start = 4.dp))
                AuthField(current, { current = it }, "Your current password", Icons.Outlined.Lock, secret = true, reveal = reveal, onReveal = { reveal = !reveal })
                ActionPill("Verify", enabled = current.isNotEmpty(), busy = busy) {
                    busy = true; error = null
                    scope.launch {
                        try { auth.reauthEmail(current); gate = Gate.Open; if (saveAfterReauth) { saveAfterReauth = false; if (rules.allMet) save() } }
                        catch (e: Exception) { error = AuthRepository.messageFor(e) }
                        busy = false
                    }
                }
                Text("You have been signed in a while, so we need to confirm it is really you before changing this.", fontSize = 13.sp, color = c.secondary)
                ErrorLine(error)
            }
            Gate.Open -> Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!isFirst) AuthField(current, { current = it }, "Current password", Icons.Outlined.Lock, secret = true, reveal = reveal, onReveal = { reveal = !reveal })
                AuthField(password, { password = it }, "New password", Icons.Outlined.Lock, secret = true, reveal = reveal, onReveal = { reveal = !reveal })
                AuthField(confirm, { confirm = it }, "Confirm new password", Icons.Outlined.Lock, secret = true, reveal = reveal, onReveal = { reveal = !reveal })
                PasswordChecklist(rules)
                Text("After changing, all other devices will need to sign in again.", fontSize = 13.sp, color = c.secondary, modifier = Modifier.padding(horizontal = 4.dp))
                // The wait shows ON the link (spinner + "Sending code..."), not as a spinner in the
                // bar's corner where Save sits (owner 2026-11-05: "looks cheap").
                if (!isFirst) Row(
                    Modifier.padding(horizontal = 4.dp, vertical = 8.dp).clickable(enabled = !busy && !sendingCode) {
                        sendingCode = true; error = null
                        scope.launch {
                            try { onForgot(auth.startPasswordReset() ?: address) } catch (e: Exception) { error = AuthRepository.codeMessageFor(e) }
                            sendingCode = false
                        }
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (sendingCode) {
                        com.fariin.app.ui.components.LoadingLine("Sending code…", fontSize = 17.sp)
                    } else Text("Forgot password?", fontSize = 17.sp, color = c.label, textDecoration = TextDecoration.Underline)
                }
                ErrorLine(error)
            }
        }
    }
    if (done) AlertDialog(onDismissRequest = { done = false; onBack() },
        title = { Text("Password saved") },
        text = { Text(if (isFirst) "You can now sign in with $address and this password." else "Your old password no longer works.") },
        confirmButton = { TextButton(onClick = { done = false; onBack() }) { Text("Done") } })
}

/** PasswordResetCodeView: the code from startPasswordReset, then the new password (confirmPasswordReset). */
@Composable
fun ResetCodePage(auth: AuthRepository, address: String, onBack: () -> Unit, onDone: () -> Unit, demo: Boolean = false) {
    val c = Fariin.colors
    val scope = rememberCoroutineScope()
    var passwordStep by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var resendIn by remember { mutableIntStateOf(CodeEntry.RESEND_SECONDS) }
    var saved by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val rules = PasswordRules(password, confirm)
    LaunchedEffect(resendIn) { if (resendIn > 0 && !demo) { delay(1000); resendIn -= 1 } }

    fun check() {
        if (!CodeEntry.isComplete(code) || working || demo) return
        working = true; error = null
        scope.launch {
            try { auth.checkPasswordResetCode(code); passwordStep = true } catch (e: Exception) { error = AuthRepository.codeMessageFor(e); code = "" }
            working = false
        }
    }
    fun saveIt() {
        working = true; error = null
        scope.launch {
            try {
                auth.confirmPasswordReset(code, password)
                try { auth.reauthEmail(password) } catch (_: Exception) {}
                saved = true
            } catch (e: Exception) { error = AuthRepository.codeMessageFor(e); passwordStep = false; code = "" }
            working = false
        }
    }

    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
        BackRow(onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            PageTitle(if (!passwordStep) "Check your email" else "Create a new password",
                if (!passwordStep) "Enter the 6-digit code we sent to $address. It expires in 10 minutes." else "", top = 12)
            if (!passwordStep) {
                CodeBoxes(code, { code = it; if (it.isNotEmpty()) error = null; if (CodeEntry.isComplete(it)) check() },
                    invalid = error != null && code.isEmpty(), focusRequester = focus, autoFocus = !demo)
                ActionPill("Continue", enabled = CodeEntry.isComplete(code), busy = working) { check() }
                TextLink(CodeEntry.resendLabel(resendIn), strong = resendIn == 0, enabled = resendIn == 0 && !working) {
                    scope.launch { try { auth.startPasswordReset(); code = ""; resendIn = CodeEntry.RESEND_SECONDS } catch (e: Exception) { error = AuthRepository.codeMessageFor(e) } }
                }
            } else {
                AuthField(password, { password = it }, "New password", Icons.Outlined.Lock, secret = true, reveal = reveal, onReveal = { reveal = !reveal })
                AuthField(confirm, { confirm = it }, "Confirm new password", Icons.Outlined.Lock, secret = true, reveal = reveal, onReveal = { reveal = !reveal })
                PasswordChecklist(rules)
                Text("After changing, all other devices will need to log in again.", fontSize = 13.sp, color = c.secondary)
                ActionPill("Save", enabled = rules.allMet, busy = working) { saveIt() }
            }
            ErrorLine(error)
        }
    }
    if (saved) AlertDialog(onDismissRequest = onDone, title = { Text("Password saved") },
        text = { Text("Your old password no longer works.") }, confirmButton = { TextButton(onClick = onDone) { Text("Done") } })
}

/** Kulan-iOS PasskeysView: the explainer and "Create Passkey" with none, else the list and "Add Passkey". */
@Composable
fun PasskeysPage(auth: AuthRepository, onBack: () -> Unit, demoRows: List<Passkeys.Row>? = null) {
    val c = Fariin.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf(demoRows ?: emptyList()) }
    var loading by remember { mutableStateOf(demoRows == null) }
    var error by remember { mutableStateOf<String?>(null) }
    var toDelete by remember { mutableStateOf<Passkeys.Row?>(null) }
    suspend fun load() { try { rows = Passkeys.list(); error = null } catch (e: Exception) { error = AuthRepository.codeMessageFor(e) }; loading = false }
    LaunchedEffect(Unit) {
        if (demoRows != null) return@LaunchedEffect
        // The challenge for Add, fetched beside the list so the sheet opens at once on the tap.
        launch { Passkeys.prefetchRegistration() }
        load()
    }
    fun add() {
        val act = ctx.findActivity() ?: return
        error = null
        scope.launch {
            try { Passkeys.register(act, auth); load() } catch (e: Exception) { if (e !is SheetCancelled) error = AuthRepository.codeMessageFor(e) }
        }
    }
    val f = DateFormat.getDateInstance(DateFormat.MEDIUM)
    Column(Modifier.fillMaxSize().background(c.bgSecondary).statusBarsPadding().navigationBarsPadding()) {
        TitleBar("Passkeys", onBack)
        if (loading) com.fariin.app.ui.components.PageLoading()
        else if (rows.isEmpty()) Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("Sign in securely and protect your account", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = c.label)
            Point(Icons.Outlined.Shield, "Create a passkey for a secure, easy way to sign in to your account.")
            Point(Icons.Outlined.Fingerprint, "Sign in to Fariin with your fingerprint, face or screen lock.")
            Point(Icons.Outlined.Key, "Your passkey is stored safely in your password manager, such as Google Password Manager.")
            Spacer(Modifier.weight(1f))
            ErrorLine(error)
            PrimaryPill("Create Passkey") { add() }
        } else Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Passkeys", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = c.label)
            Text("Sign in with your screen lock. No password needed.", fontSize = 15.sp, color = c.secondary)
            Column(Modifier.clip(RoundedCornerShape(12.dp)).background(c.card)) {
                for (r in rows) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Key, null, tint = c.secondary, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.name, fontSize = 17.sp, color = c.label)
                        Text(listOfNotNull(r.createdMs?.let { "created " + f.format(Date(it.toLong())) }, r.lastUsedMs?.let { "used " + f.format(Date(it.toLong())) })
                            .joinToString(" · "), fontSize = 15.sp, color = c.secondary)
                    }
                    IconButton(onClick = { toDelete = r }) { Icon(Icons.Outlined.Delete, "Delete", tint = c.red) }
                }
            }
            Text("Add Passkey", fontSize = 17.sp, color = c.accent, modifier = Modifier.clickable { add() }.padding(vertical = 8.dp))
            Text("Your passkeys are stored securely in your password manager.", fontSize = 13.sp, color = c.secondary)
            ErrorLine(error)
        }
    }
    toDelete?.let { r ->
        AlertDialog(onDismissRequest = { toDelete = null }, title = { Text("Delete passkey?") },
            text = { Text("You will no longer be able to sign in to Fariin with this passkey.") },
            confirmButton = { TextButton(onClick = { toDelete = null; scope.launch { try { Passkeys.delete(r.id); load() } catch (e: Exception) { error = AuthRepository.codeMessageFor(e) } } }) { Text("Delete", color = c.red) } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } })
    }
}

@Composable
private fun Point(icon: ImageVector, text: String) {
    val c = Fariin.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = c.accent, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(14.dp))
        Text(text, fontSize = 17.sp, color = c.label)
    }
}
