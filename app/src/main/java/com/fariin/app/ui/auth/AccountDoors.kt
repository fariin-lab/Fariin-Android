package com.fariin.app.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.data.AccountCall
import com.fariin.app.data.AccountCallError
import com.fariin.app.data.AuthRepository
import com.fariin.app.data.CryptoHub
import com.fariin.app.data.RestoreRules
import com.fariin.app.data.TwoStepRules
import com.fariin.app.device.SessionEnd
import com.fariin.app.ui.settings.BarAction
import com.fariin.app.ui.settings.CardField
import com.fariin.app.ui.settings.ErrorNote
import com.fariin.app.ui.settings.SettingsRow
import com.fariin.app.ui.settings.SettingsSection
import com.fariin.app.ui.settings.Trailing
import com.fariin.app.ui.settings.TwoStepRecoveryRows
import com.fariin.app.ui.settings.YesNo
import com.fariin.app.ui.theme.Fariin
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * Two pages the signed-in gate shows INSTEAD of the app (Kulan-iOS RootView .twoStep and .restore):
 * TwoStepSignInView and RestoreAccountView. Both carry Log Out in the bar's leading place, as iOS.
 */

/**
 * The bar of a page that replaces the app (Material: title at the start, the action at the end).
 * With no [action] the end holds Log Out; otherwise the page puts Log Out under its content.
 */
@Composable
private fun GateBar(title: String, onLogOut: () -> Unit, color: androidx.compose.ui.graphics.Color, action: (@Composable () -> Unit)? = null) {
    val c = Fariin.colors
    Box(Modifier.fillMaxWidth().background(color).statusBarsPadding().height(64.dp)) {
        if (title.isNotEmpty()) Text(title, fontSize = 22.sp, color = c.label, modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp))
        Box(Modifier.align(Alignment.CenterEnd).padding(end = 4.dp)) { action?.invoke() ?: LogOutButton(onLogOut) }
    }
}

@Composable
private fun LogOutButton(onLogOut: () -> Unit) = TextButton(onClick = onLogOut) { Text("Log Out", fontSize = 15.sp, color = Fariin.colors.secondary) }

/**
 * iOS TwoStepSignInView: right after a sign-in on an account with two-step on. The password lets
 * this sign-in in (verifyAdditionalPassword stamps its auth_time); the recovery code turns it off.
 */
@Composable
fun TwoStepSignInPage(onPassed: () -> Unit) {
    val ctx = LocalContext.current
    val c = Fariin.colors
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var recoveryCode by remember { mutableStateOf("") }
    var recoverySent by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // The hint is the one thing readable before the password; a failure just leaves it off.
        runCatching { AccountCall.run("twoStepStatus") }.getOrNull()?.let { d ->
            hint = d["hint"] as? String ?: ""
            // Turned off from another device meanwhile: nothing to ask.
            if (d["enabled"] == false || d["passed"] == true) onPassed()
        }
    }

    fun verify() {
        if (password.isEmpty() || busy) return
        busy = true; error = null
        scope.launch {
            try {
                AccountCall.run("verifyAdditionalPassword", mapOf("password" to password))
                // The claim lands on the next refresh; the rules read the token, so fetch it first.
                runCatching { FirebaseAuth.getInstance().currentUser?.getIdToken(true)?.await() }
                onPassed()
            } catch (e: AccountCallError) { error = e.message }
            finally { busy = false }
        }
    }

    Column(Modifier.fillMaxSize().background(c.bgSecondary).navigationBarsPadding()) {
        GateBar("Two-step verification", onLogOut = { scope.launch { SessionEnd.signOut(ctx) } }, c.bgSecondary) {
            BarAction("Continue", password.isNotEmpty() && !busy, busy = busy) { verify() }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
            SettingsSection(footer = "An additional password is required when you sign in on a new device.") {
                CardField(password, "Additional password", secret = true, enabled = !busy,
                    imeDone = { verify() }) { password = it }
            }
            if (hint.isNotEmpty()) SettingsSection { SettingsRow("Hint", value = hint, trailing = Trailing.NONE, last = true) }
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
                        try { AccountCall.adoptSession(AccountCall.run("confirmTwoStepRecovery", mapOf("code" to recoveryCode))); onPassed() }
                        catch (e: AccountCallError) { error = e.message }
                        busy = false
                    }
                })
            ErrorNote(error)
            Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                LogOutButton { scope.launch { SessionEnd.signOut(ctx) } }
            }
        }
    }
}

/**
 * iOS RestoreAccountView: shown instead of the app for an account scheduled for deletion. A dead
 * end until a choice: Cancel Deletion (clears the two fields, as ProfileStore.restoreAccount), or
 * "Delete it now" (the server's purgeAccountNow), or Log Out.
 */
@Composable
fun RestoreAccountPage(handle: String, dueMs: Long, onRestored: () -> Unit) {
    val ctx = LocalContext.current
    val c = Fariin.colors
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val dueText = remember(dueMs) { SimpleDateFormat("d MMMM", Locale.getDefault()).format(Date(dueMs)) }
    val days = RestoreRules.daysLeft(System.currentTimeMillis(), dueMs)

    fun act(work: suspend () -> Unit) {
        working = true; error = null
        scope.launch {
            try { work() } catch (e: Exception) { error = AuthRepository.messageFor(e, "Something went wrong. Try again.") }
            working = false
        }
    }

    Column(Modifier.fillMaxSize().background(c.bg).navigationBarsPadding()) {
        GateBar("", onLogOut = { scope.launch { SessionEnd.signOut(ctx) } }, c.bg)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(40.dp))
            Icon(Icons.Outlined.History, null, tint = c.secondary, modifier = Modifier.size(44.dp))
            Text("Scheduled for deletion on $dueText", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.secondary,
                modifier = Modifier.padding(top = 16.dp))
            Text("Everything is still here", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = c.label, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp))
            Text(RestoreRules.body(handle), fontSize = 15.sp, color = c.secondary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp))
            Spacer(Modifier.height(34.dp))
            Box(Modifier.fillMaxWidth().height(52.dp).clip(CircleShape).background(c.blue)
                .clickable(enabled = !working) {
                    act {
                        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: error("You are logged out.")
                        FirebaseFirestore.getInstance().collection("users").document(uid)
                            .set(mapOf("deletionScheduledFor" to FieldValue.delete(), "isHidden" to FieldValue.delete()), SetOptions.merge()).await()
                        onRestored()
                    }
                }, contentAlignment = Alignment.Center) {
                if (working) CircularProgressIndicator(color = androidx.compose.ui.graphics.Color.White, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                else Text("Cancel Deletion", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = androidx.compose.ui.graphics.Color.White)
            }
            Text(RestoreRules.deadline(days), fontSize = 13.sp, color = c.secondary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 14.dp))
            error?.let { Text(it, fontSize = 13.sp, color = c.red, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 14.dp)) }
            TextButton(onClick = { confirmDelete = true }, enabled = !working, modifier = Modifier.padding(top = 20.dp)) {
                Text("Delete it now", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = c.red)
            }
            Text(RestoreRules.FOOT, fontSize = 12.sp, color = c.secondary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 26.dp, bottom = 48.dp))
        }
    }
    if (confirmDelete) YesNo("Delete now?", RestoreRules.deleteNowText(dueText), "Delete", onNo = { confirmDelete = false }, onYes = {
        confirmDelete = false
        act {
            val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return@act
            com.fariin.app.device.DeviceRegistry.stopWatching()
            try {
                FirebaseFunctions.getInstance(AuthRepository.FUNCTIONS_REGION).getHttpsCallable("purgeAccountNow").call(emptyMap<String, Any>()).await()
            } catch (e: FirebaseFunctionsException) {
                if ((e.details as? Map<*, *>)?.get("reauth") == true)
                    throw IllegalStateException("For your security, log out and log in again, then choose Delete it now.")
                throw IllegalStateException(e.message)
            }
            // The server removed the account; this phone's keys and copies go too.
            CryptoHub.get(ctx).destroyIdentity(uid)
            SessionEnd.deletedNow(ctx)
        }
    })
}
