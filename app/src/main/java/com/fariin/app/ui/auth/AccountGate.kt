package com.fariin.app.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.fariin.app.data.AuthRepository
import com.fariin.app.data.CodePurpose
import com.fariin.app.data.CryptoHub
import com.fariin.app.data.DeviceSessionKeys
import com.fariin.app.data.FirestorePeople
import com.fariin.app.data.MyProfile
import com.fariin.app.data.MyProfileRepository
import com.fariin.app.data.SavedAccounts
import com.fariin.app.data.TwoStepGate
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.tasks.await

/**
 * The signed-in half of Kulan-iOS RootView.route(), before the app proper starts:
 *  1. an email-only account that never proved its address: "Confirm your email" (once);
 *  2. after "Forgot password?" logged in with a code: "Create a new password" (or Not now);
 *  2b. a two-step account whose sign-in has not entered the additional password: its page;
 *  2c. an account scheduled for deletion: the restore page;
 *  3. no name or no username yet (a new account): "Create your profile";
 *  4. ready: [content], and this account's card on the Log In page is refreshed
 *     (LastAccount.remember) with its one-tap key (DeviceSessionKeys.ensureIssued).
 */
/** [onOwnPage]: the gate is drawing one of its own pages (the launch screen may go). */
@Composable
fun AccountGate(me: String, auth: AuthRepository, onOwnPage: () -> Unit = {}, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val newPasswordFor by AuthHandoff.newPasswordFor.collectAsState()
    // An account this phone has opened before opens at once (owner 2026-11-03: Fariin took 1.5 to
    // 3.5 s to show anything, others under 1 s; the profile was fetched from the SERVER on every
    // launch before the first frame). The same checks still run, in the background, and move the
    // page if their answer differs. A fresh sign-in has no mark, so it waits for them as before.
    val opened = remember(me) { GateMemory.wasReady(ctx, me) }
    // True once the app is open for this account: a check that gets no answer then keeps the page.
    var trusted by remember(me) { mutableStateOf(opened) }
    var phase by remember(me) { mutableStateOf<Phase>(if (opened) Phase.Ready else Phase.Loading) }
    var verified by remember(me) { mutableStateOf(!opened) }
    val attempts = remember(me) { com.fariin.app.device.BootAttempts() }
    // The watchdog: the logo page never stays past 8 s. The app opens and the check runs once more
    // behind it (a stalled Firestore client after two account changes left iOS on the logo for good).
    LaunchedEffect(me, phase == Phase.Loading) {
        if (phase != Phase.Loading) return@LaunchedEffect
        kotlinx.coroutines.delay(com.fariin.app.device.BootWatch.WATCHDOG_MS)
        if (phase == Phase.Loading && auth.currentUser?.uid == me) {
            android.util.Log.w(com.fariin.app.device.BootWatch.TAG, "account checks gave no answer in ${com.fariin.app.device.BootWatch.WATCHDOG_MS} ms, opening the app")
            trusted = true; verified = false; phase = Phase.Ready
        }
    }
    var recheckTwoStep by remember(me) { mutableStateOf(false) }
    LaunchedEffect(me, recheckTwoStep, phase == Phase.Ready) {
        if (recheckTwoStep && phase == Phase.Ready && TwoStepGate.needsPassword(20_000) == true) { recheckTwoStep = false; phase = Phase.TwoStep }
    }

    LaunchedEffect(me, phase) {
        if (phase != Phase.Loading && verified) return@LaunchedEffect
        verified = true
        // This attempt's answers count only while it is the newest one for the signed-in account.
        val attempt = attempts.begin(me)
        fun current() = attempts.isCurrent(attempt, me, auth.currentUser?.uid)
        // The email check runs on a Cloud Function, which the local test backend does not have:
        // there it could never finish, so fake accounts skip it. Real builds always ask.
        if (!isLocalBackend(ctx)) auth.unprovenEmailCached?.let { phase = Phase.ProveEmail(it); return@LaunchedEffect }
        // iOS decision D1: a sign-in on a two-step account that has not entered the additional
        // password stops here (the rules refuse its token everything). A short local token read;
        // null = the token did not come in time, asked again below once the app is open.
        val twoStep = TwoStepGate.needsPassword()
        if (!current()) return@LaunchedEffect
        if (twoStep == true) { phase = Phase.TwoStep; return@LaunchedEffect }
        if (twoStep == null) recheckTwoStep = true
        // Both reads have a limit: a stalled client must not hold the logo page.
        val ref = FirebaseFirestore.getInstance().collection("users").document(me)
        val doc = com.fariin.app.device.BootWatch.capped(com.fariin.app.device.BootWatch.SERVER_READ_MS) { ref.get(Source.SERVER).await() }
            ?: com.fariin.app.device.BootWatch.capped(com.fariin.app.device.BootWatch.CACHE_READ_MS) { ref.get(Source.CACHE).await() }
        if (!current()) { android.util.Log.w(com.fariin.app.device.BootWatch.TAG, "late account check dropped"); return@LaunchedEffect }
        // No answer at all on a fresh sign-in: open the app and ask once more behind it (the same
        // way out as the watchdog), never "Create your profile" for an account that has one.
        if (doc == null && !trusted) {
            android.util.Log.w(com.fariin.app.device.BootWatch.TAG, "profile read gave no answer, opening the app")
            trusted = true; verified = false; phase = Phase.Ready; return@LaunchedEffect
        }
        val p = doc?.data?.let { MyProfile.fromFirestore(me, it) } ?: MyProfile(me)
        // Scheduled for deletion: the restore page, never the app. No phone-clock test (iOS audit):
        // the profile still existing is the server's word that the purge has not run.
        (doc?.get("deletionScheduledFor") as? com.google.firebase.Timestamp)?.let {
            phase = Phase.Restore(p.handle, it.toDate().time); return@LaunchedEffect
        }
        // A background check that found nothing (offline, cache empty) keeps the page as it is.
        if (trusted && doc == null) return@LaunchedEffect
        phase = if (p.name.isNotBlank() && p.handle.isNotEmpty()) Phase.Ready else Phase.Onboarding(p.handle)
    }
    LaunchedEffect(me, phase) { if (phase == Phase.Ready) GateMemory.markReady(ctx, me) else if (phase != Phase.Loading) GateMemory.clear(ctx) }

    val ownPage = newPasswordFor != null || (phase != Phase.Ready && phase != Phase.Loading)
    LaunchedEffect(ownPage) { if (ownPage) onOwnPage() }
    val box = Modifier.fillMaxSize().background(com.fariin.app.ui.theme.Fariin.colors.bg)
    val np = newPasswordFor
    when {
        np != null -> Box(box.statusBarsPadding().navigationBarsPadding()) {
            CodePage(auth, CodePurpose.Forgot, np, onBack = null, onAuthed = { AuthHandoff.newPasswordFor.value = null }, startStep = CodeStep.NewPassword)
        }
        phase is Phase.ProveEmail -> Box(box.statusBarsPadding().navigationBarsPadding()) {
            CodePage(auth, CodePurpose.Unproven, (phase as Phase.ProveEmail).address, onBack = null, onAuthed = { phase = Phase.Loading })
            // iOS: the way out of a page that replaces the app is Log Out (the gate stays the gate).
            TextLink("Log Out", color = com.fariin.app.ui.theme.Fariin.colors.accent) { auth.signOut() }
        }
        phase == Phase.TwoStep -> Box(box) { TwoStepSignInPage(onPassed = { phase = Phase.Loading }) }
        phase is Phase.Restore -> Box(box) {
            val r = phase as Phase.Restore
            RestoreAccountPage(r.handle, r.dueMs, onRestored = { phase = Phase.Loading })
        }
        phase is Phase.Onboarding -> {
            val crypto = remember { CryptoHub.get(ctx) }
            val api = remember(me) { FirestorePeople(me, crypto) }
            OnboardingPage(api, currentHandle = (phase as Phase.Onboarding).handle, prefillName = auth.pendingDisplayName,
                onSaved = { auth.pendingDisplayName = null; phase = Phase.Ready })
        }
        phase == Phase.Ready -> {
            RememberThisAccount(me)
            content()
        }
        else -> Box(box, contentAlignment = Alignment.Center) { Logo(120) }
    }
}

private sealed interface Phase {
    data object Loading : Phase
    data object Ready : Phase
    data object TwoStep : Phase
    data class Restore(val handle: String, val dueMs: Long) : Phase
    data class ProveEmail(val address: String) : Phase
    data class Onboarding(val handle: String) : Phase
}

/** LastAccount.remember on every profile change, with a small copy of the photo, and the one-tap key. */
@Composable
private fun RememberThisAccount(me: String) {
    val ctx = LocalContext.current
    LaunchedEffect(me) {
        if (!isLocalBackend(ctx)) try { DeviceSessionKeys.ensureIssued(ctx) } catch (_: Exception) {}
        var lastPhoto: String? = "unset"
        MyProfileRepository().listen(me).collect { p ->
            if (p == null) return@collect
            var jpeg: ByteArray? = null
            if (p.photoUrl != lastPhoto) {
                lastPhoto = p.photoUrl
                jpeg = when {
                    p.photoUrl.isNullOrEmpty() -> ByteArray(0)
                    p.photoUrl.startsWith("fariin-photo://") -> try {
                        val path = p.photoUrl.removePrefix("fariin-photo://").substringBefore('?')
                        FirebaseStorage.getInstance().reference.child(path).getBytes(4L * 1024 * 1024).await()
                            .let { SavedAccounts.cardJpeg(it) }
                    } catch (_: Exception) { null }
                    else -> null
                }
            }
            SavedAccounts.remember(ctx, p, photoJpeg = jpeg)
        }
    }
}

/** Which account last opened all the way on this phone (the fast path above). Cleared at sign-out. */
object GateMemory {
    private const val PREFS = "fariin_gate"
    fun wasReady(c: android.content.Context, uid: String) =
        c.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getString("ready", null) == uid
    fun markReady(c: android.content.Context, uid: String) =
        c.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().putString("ready", uid).apply()
    fun clear(c: android.content.Context) =
        c.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().remove("ready").apply()
}
