package com.fariin.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.R
import com.fariin.app.crypto.KeyBackup
import com.fariin.app.ui.components.GlassAlert
import com.fariin.app.ui.theme.Fariin
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch

internal const val PAGE_KEY_BACKUP = "privacy:keybackup"

private const val ABOUT = "Your messages are end-to-end encrypted, so only your phone has the key that opens them. " +
    "With a Backup PIN, that key is saved locked with your PIN. If you delete Fariin or move to a new phone, " +
    "the PIN brings your older messages back. Fariin never sees your PIN and cannot reset it."

private const val MIN_PASSWORD = 8
private const val MAX_SECRET = 64

/** PINs anyone tries first (2026-11-12, both apps): one digit repeated, a straight run up or down, or these. */
private val EASY_PINS = setOf("121212", "112233", "000000", "123123", "696969", "202020", "147258", "159753")

internal fun pinIsEasy(pin: String): Boolean {
    if (pin.isEmpty() || pin.any { !it.isDigit() }) return false
    if (pin.all { it == pin[0] }) return true
    val steps = pin.zipWithNext { a, b -> (b - a + 10) % 10 }.toSet()
    if (steps == setOf(1) || steps == setOf(9)) return true
    return pin in EASY_PINS
}

private fun pinProblem(pin: String, again: String, password: Boolean): String? = when {
    !password && pin.any { !it.isDigit() } -> "Use numbers only."
    !password && pin.length < KeyBackup.MIN_PIN -> "Use at least ${KeyBackup.MIN_PIN} digits."
    password && pin.length < MIN_PASSWORD -> "Use at least $MIN_PASSWORD characters."
    !password && pinIsEasy(pin) -> "This PIN is easy to guess. Pick another."
    again.isNotEmpty() && again != pin -> if (password) "The passwords do not match." else "The PINs do not match."
    else -> null
}

/** "Use a password instead" / "Use a PIN instead" under a PIN field. */
@Composable
private fun SecretKindLink(password: Boolean, onToggle: () -> Unit) {
    val c = Fariin.colors
    Text(if (password) "Use a PIN instead" else "Use a password instead", fontSize = 15.sp, color = c.accent,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onToggle).padding(horizontal = 8.dp, vertical = 6.dp))
}

/** Privacy > Backup PIN: make, change or turn off the PIN that locks the chat key backup. */
@Composable
internal fun KeyBackupPage(pop: () -> Unit) {
    val ctx = LocalContext.current
    val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
    val scope = rememberCoroutineScope()
    var on by remember { mutableStateOf(KeyBackup.cachedOn(ctx, uid)) }
    var editing by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var password by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmOff by remember { mutableStateOf(false) }
    var confirmReplace by remember { mutableStateOf(false) }
    val c = Fariin.colors
    var other by remember { mutableStateOf<KeyBackup.Status?>(null) }
    LaunchedEffect(Unit) { KeyBackup.status(ctx, uid)?.let { on = it.exists }; other = KeyBackup.restorable(ctx, uid) }

    if (editing) {
        BackHandler { editing = false; pin = ""; again = ""; error = null }
        val ok = pin.length >= (if (password) MIN_PASSWORD else KeyBackup.MIN_PIN) && again == pin && pinProblem(pin, again, password) == null
        fun save() {
            if (!ok || busy) return
            busy = true; error = null
            scope.launch {
                val e = KeyBackup.save(ctx, uid, pin)
                busy = false
                if (e == null) { on = true; editing = false; pin = ""; again = "" } else error = e
            }
        }
        ScrollPage(if (on == true) "Change PIN" else "Backup PIN", { editing = false; pin = ""; again = ""; error = null },
            action = { BarAction("Save", ok, busy = busy) { save() } }) {
            SettingsSection(header = if (password) "New password" else "New PIN",
                footer = (if (password) "At least $MIN_PASSWORD characters." else "At least ${KeyBackup.MIN_PIN} digits.") +
                    " Write it down somewhere safe: if you forget it, your older messages cannot be brought back.") {
                val first = remember { androidx.compose.ui.focus.FocusRequester() }
                LaunchedEffect(password) { runCatching { first.requestFocus() } }
                // key(): the keyboard type changes with the kind, so each kind gets fresh fields.
                androidx.compose.runtime.key(password) {
                    val kb = if (password) KeyboardType.Password else KeyboardType.NumberPassword
                    val clean: (String) -> String = { v -> (if (password) v else v.filter { it.isDigit() }).take(MAX_SECRET) }
                    CardField(pin, if (password) "Password" else "PIN", secret = true, keyboard = kb, last = false, focus = first) { v -> pin = clean(v) }
                    CardField(again, if (password) "Confirm password" else "Confirm PIN", secret = true, keyboard = kb, imeDone = { save() }) { v -> again = clean(v) }
                }
            }
            SecretKindLink(password) { password = !password; pin = ""; again = ""; error = null }
            ErrorNote(error ?: pinProblem(pin, again, password).takeIf {
                pin.length >= (if (password) MIN_PASSWORD else KeyBackup.MIN_PIN) || again.isNotEmpty() })
        }
        return
    }

    ScrollPage("Backup PIN", pop) {
        SettingsSection(footer = ABOUT) {
            when (on) {
                true -> {
                    // The backup holds a key this phone does not have (Skip was tapped after sign-in).
                    other?.let { st -> SettingsRow("Restore Messages", trailing = Trailing.NONE, titleColor = c.accent) { KeyBackup.ask.value = st } }
                    // The backup still holds an older key: replacing it loses those messages for good.
                    SettingsRow("Change PIN", trailing = Trailing.NONE, titleColor = c.accent) { if (other != null) confirmReplace = true else editing = true }
                    SettingsRow("Turn Off", trailing = Trailing.NONE, titleColor = c.red, last = true,
                        accessory = if (busy) ({ com.fariin.app.ui.components.FariinSpinner(size = 20.dp) }) else null) { if (!busy) confirmOff = true }
                }
                false -> SettingsRow("Create Backup PIN", trailing = Trailing.NONE, titleColor = c.accent, last = true) { editing = true }
                null -> SettingsRow("Checking...", trailing = Trailing.NONE, last = true,
                    accessory = { com.fariin.app.ui.components.FariinSpinner(size = 20.dp) }) {}
            }
        }
        ErrorNote(error)
    }
    if (confirmReplace) GlassAlert(onDismissRequest = { confirmReplace = false },
        title = { Text("Replace your backup?") },
        text = { Text("Your backup still holds the key to your older messages. A new PIN saves this phone's key instead, and those messages can never be restored. Restore them first if you can.") },
        confirmButton = { TextButton({ confirmReplace = false; editing = true }) { Text("Replace", color = c.red) } },
        dismissButton = { TextButton({ confirmReplace = false }) { Text("Cancel", color = c.accent) } }, containerColor = c.card)
    if (confirmOff) GlassAlert(onDismissRequest = { confirmOff = false },
        title = { Text("Turn off Backup PIN?") },
        text = { Text("The saved key is deleted. If you delete Fariin or change phones, your older messages will not come back.") },
        confirmButton = { TextButton({
            confirmOff = false; busy = true
            scope.launch { error = KeyBackup.delete(ctx, uid); if (error == null) on = false; busy = false }
        }) { Text("Turn Off", color = c.red) } },
        dismissButton = { TextButton({ confirmOff = false }) { Text("Cancel", color = c.accent) } }, containerColor = c.card)
}

/**
 * After sign-in on a phone without the backed-up key (a reinstall, a new phone): enter the PIN to
 * bring older messages back, or Skip (asked again only for a newer backup). [onRestored] runs once
 * the key is in place.
 */
@Composable
fun KeyRestoreScreen(status: KeyBackup.Status, onRestored: () -> Unit, onSkip: () -> Unit) {
    val ctx = LocalContext.current
    val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
    val scope = rememberCoroutineScope()
    val c = Fariin.colors
    var pin by remember { mutableStateOf("") }
    var password by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(
        if (status.lockedUntil > System.currentTimeMillis()) lockedWords(status.lockedUntil) else null) }
    var gone by remember { mutableStateOf(false) }
    BackHandler(onBack = onSkip)

    fun restore() {
        if (busy || pin.length < (if (password) MIN_PASSWORD else KeyBackup.MIN_PIN)) return
        busy = true; note = null
        scope.launch {
            when (val r = KeyBackup.restore(ctx, uid, pin)) {
                KeyBackup.Restore.Done -> { busy = false; onRestored(); return@launch }
                is KeyBackup.Restore.WrongPin -> note = if (r.lockedUntil > 0) "Wrong PIN. ${lockedWords(r.lockedUntil)}"
                    else "Wrong PIN. ${r.triesLeft} ${if (r.triesLeft == 1) "try" else "tries"} left before the backup is deleted."
                is KeyBackup.Restore.Locked -> note = lockedWords(r.until)
                KeyBackup.Restore.Wiped -> { note = "Too many wrong PINs. The backup was deleted for your safety."; gone = true }
                KeyBackup.Restore.None -> { note = "There is no backup for this account any more."; gone = true }
                is KeyBackup.Restore.Failed -> note = r.message
            }
            pin = ""; busy = false
        }
    }

    // The keyboard opens only when the PIN box is tapped, and a tap anywhere else puts it away
    // (owner 2026-11-12: it came up by itself with no way to close it).
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    Box(Modifier.fillMaxSize().background(c.bgSecondary)
        .pointerInput(Unit) { detectTapGestures { focus.clearFocus() } }) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Spacer(Modifier.height(72.dp))
            Box(Modifier.size(84.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
                Icon(ImageVector.vectorResource(R.drawable.ic_sf_lock_fill), null, tint = c.onAccent, modifier = Modifier.size(38.dp))
            }
            Text("Restore your messages", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = c.label, textAlign = TextAlign.Center)
            Text("Enter your Backup PIN to open the messages you had before on this account.",
                fontSize = 16.sp, color = c.secondary, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            if (!gone) Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.card)) {
                // Digits by default; the link below switches to a full keyboard for a password backup.
                androidx.compose.runtime.key(password) {
                    CardField(pin, if (password) "Backup password" else "Backup PIN", secret = true,
                        keyboard = if (password) KeyboardType.Password else KeyboardType.NumberPassword, enabled = !busy, imeDone = { restore() }) { v ->
                        pin = (if (password) v else v.filter { it.isDigit() }).take(MAX_SECRET)
                    }
                }
            }
            if (!gone) SecretKindLink(password) { password = !password; pin = ""; note = null }
            note?.let { Text(it, fontSize = 14.sp, color = if (gone) c.secondary else c.red, textAlign = TextAlign.Center) }
            Spacer(Modifier.height(4.dp))
            val can = !gone && pin.length >= (if (password) MIN_PASSWORD else KeyBackup.MIN_PIN)
            if (!gone) Box(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(26.dp))
                .background(if (can || busy) c.accent else c.fieldFill).clickable(enabled = can && !busy) { restore() },
                contentAlignment = Alignment.Center) {
                com.fariin.app.ui.components.BusyContent(busy, c.onAccent, size = 22.dp) {
                    Text("Restore", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = if (can) c.onAccent else c.tertiary)
                }
            }
            TextButton(onClick = onSkip, enabled = !busy) {
                Text(if (gone) "Continue" else "Skip", fontSize = 17.sp, color = c.accent)
            }
            if (!gone) Text("If you skip, older messages stay closed on this phone. You can set a new Backup PIN later in Privacy.",
                fontSize = 13.sp, color = c.tertiary, textAlign = TextAlign.Center)
        }
    }
}

private fun lockedWords(until: Long): String {
    val mins = ((until - System.currentTimeMillis()) / 60_000L).coerceAtLeast(1)
    return if (mins < 90) "Too many wrong PINs. Try again in $mins minute${if (mins == 1L) "" else "s"}."
    else "Too many wrong PINs. Try again in ${(mins + 30) / 60} hours."
}
