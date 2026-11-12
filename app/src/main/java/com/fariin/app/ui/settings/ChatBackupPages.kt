package com.fariin.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.backup.ChatBackup
import com.fariin.app.backup.Drive
import com.fariin.app.ui.components.GlassAlert
import com.fariin.app.ui.theme.Fariin
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch

internal const val PAGE_CHAT_BACKUP = "chats:backup"

private fun sizeWords(b: Long): String = when {
    b >= 1_000_000 -> "%.1f MB".format(b / 1_000_000.0)
    b >= 1_000 -> "${b / 1_000} KB"
    else -> "$b bytes"
}

/**
 * Settings > Chats > Chat Backup (flow B of backup-flow-study.md, own words): status with Back Up
 * Now, How often, Use mobile data, and the backup password (a password or a 64-digit key).
 * Shown only while [ChatBackup.ENABLED].
 */
@Composable
internal fun ChatBackupPage(pop: () -> Unit) {
    val ctx = LocalContext.current
    val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
    val scope = rememberCoroutineScope()
    val c = Fariin.colors
    var last by remember { mutableStateOf(ChatBackup.lastBackup(ctx, uid)) }
    var size by remember { mutableStateOf(ChatBackup.lastSize(ctx, uid)) }
    var freq by remember { mutableStateOf(ChatBackup.frequency(ctx, uid)) }
    var cell by remember { mutableStateOf(ChatBackup.mobileData(ctx, uid)) }
    var videos by remember { mutableStateOf(ChatBackup.includeVideos(ctx, uid)) }
    var hasPw by remember { mutableStateOf(ChatBackup.hasPassword(ctx, uid)) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var freqOpen by remember { mutableStateOf(false) }
    var pwPage by remember { mutableStateOf(false) }
    var confirmPwOff by remember { mutableStateOf(false) }
    var confirmReplace by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    fun run(token: String? = null) {
        busy = true; note = null
        job = scope.launch {
            when (val r = ChatBackup.backUp(ctx, uid, automatic = false, token = token)) {
                ChatBackup.Result.Done -> {
                    last = ChatBackup.lastBackup(ctx, uid); size = ChatBackup.lastSize(ctx, uid)
                    // The first backup by hand starts the daily ones (both apps).
                    if (freq == "off") { freq = "daily"; ChatBackup.setFrequency(ctx, uid, "daily") }
                }
                is ChatBackup.Result.NeedsGoogle -> { ask.value = r.ask; busy = false; return@launch }
                is ChatBackup.Result.Failed -> note = r.message
                ChatBackup.Result.NothingToBackUp -> note = "Nothing to back up yet."
            }
            busy = false
        }
    }
    val google = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        val act = ctx as? android.app.Activity
        val token = act?.let { Drive.tokenFrom(it, res.data) }
        if (token != null) run(token) else note = "Fariin needs your permission to save to Google Drive."
    }
    androidx.compose.runtime.LaunchedEffect(ask.value) {
        ask.value?.let { a -> ask.value = null; google.launch(IntentSenderRequest.Builder(a.intent).build()) }
    }

    if (pwPage) { BackupPasswordPage(onDone = { set ->
        pwPage = false
        if (set) { hasPw = true; run() }   // a fresh backup carries the new lock (both apps)
    }); return }

    // Owner 2026-11-12 ("too basic", after looking at the reference app on his Pixel): a status card
    // that says what the backup holds, real progress while it runs, the Google Drive it lives in,
    // then the settings and the password in its own section. Our own look and words.
    val live by ChatBackup.progress.collectAsState()
    val what = remember(last) { ChatBackup.contents(ctx, uid) }
    val account = remember(last) { ChatBackup.account(ctx, uid) }
    ScrollPage("Chat Backup", pop) {
        SettingsSection {
            Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                androidx.compose.foundation.layout.Box(Modifier.size(64.dp).clip(androidx.compose.foundation.shape.CircleShape)
                    .background(if (last > 0) c.accent else c.fieldFill), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    androidx.compose.material3.Icon(androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.fariin.app.R.drawable.ic_gs_cloud_up_fill),
                        null, tint = if (last > 0) c.onAccent else c.secondary, modifier = Modifier.size(32.dp))
                }
                Spacer(Modifier.height(2.dp))
                Text(when {
                        last <= 0 -> "No backup yet"
                        System.currentTimeMillis() - last < 60_000 -> "Backed up just now"
                        else -> "Backed up " + android.text.format.DateUtils.getRelativeTimeSpanString(last, System.currentTimeMillis(),
                            android.text.format.DateUtils.MINUTE_IN_MILLIS).toString().replaceFirstChar { it.lowercase() }
                    },
                    fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = c.label, textAlign = TextAlign.Center)
                Text(if (last > 0) android.text.format.DateFormat.getMediumDateFormat(ctx).format(java.util.Date(last)) + ", " +
                        android.text.format.DateFormat.getTimeFormat(ctx).format(java.util.Date(last)) + " · " + sizeWords(size)
                    else "Keep your chats safe if you lose or change this phone.", fontSize = 14.sp, color = c.secondary, textAlign = TextAlign.Center)
                // Counted from this build on: an older backup shows no line rather than "0 messages".
                if (last > 0 && what[0] > 0) {
                    val parts = listOfNotNull(
                        "%,d messages".format(what[0]),
                        what[1].takeIf { it > 0 }?.let { "%,d photos".format(it) },
                        what[2].takeIf { it > 0 }?.let { "%,d voice messages".format(it) },
                        what[3].takeIf { it > 0 }?.let { "%,d videos".format(it) })
                    Text(parts.joinToString(" · "), fontSize = 14.sp, color = c.secondary, textAlign = TextAlign.Center)
                }
                // While it runs: what it is doing and how far, instead of a spinner alone.
                if (busy) {
                    Spacer(Modifier.height(6.dp))
                    androidx.compose.material3.LinearProgressIndicator(progress = { live?.fraction ?: 0f },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(androidx.compose.foundation.shape.CircleShape),
                        color = c.accent, trackColor = c.fieldFill, drawStopIndicator = {})
                    Text((live?.step ?: "Getting ready…") + "  " + ((live?.fraction ?: 0f) * 100).toInt() + "%",
                        fontSize = 13.sp, color = c.secondary, textAlign = TextAlign.Center)
                }
                note?.let { Text(it, fontSize = 14.sp, color = c.red, textAlign = TextAlign.Center) }
                Spacer(Modifier.height(8.dp))
                androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(48.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape).background(if (busy) c.fieldFill else c.accent)
                    .clickable {
                        if (busy) { job?.cancel(); busy = false; return@clickable }
                        if (ChatBackup.restoreWaiting(ctx, uid)) confirmReplace = true else run()
                    }, contentAlignment = androidx.compose.ui.Alignment.Center) {
                    Text(if (busy) "Cancel" else "Back Up Now", fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                        color = if (busy) c.label else c.onAccent)
                }
            }
        }
        SettingsSection(header = "Google Drive", footer = "Your backup sits in a private Fariin folder in this Google account. It counts toward its storage.") {
            SettingsRow("Account", value = account ?: "Chosen at your first backup", trailing = Trailing.NONE)
            SettingsRow("Manage Google Storage", trailing = Trailing.OUT_LINK, last = true) {
                runCatching { com.fariin.app.ui.browser.BrowserActivity.open(ctx, "https://one.google.com/storage") }
            }
        }
        SettingsSection(header = "Backup Settings", footer = "Backups run on Wi-Fi unless you allow mobile data. Videos are the largest part, so they are left out unless you include them.") {
            androidx.compose.foundation.layout.Box {
                SettingsRow("Automatic Backup", value = when (freq) { "daily" -> "Daily"; "weekly" -> "Weekly"; else -> "Off" }) { freqOpen = true }
                com.fariin.app.ui.components.GlassDropdown(freqOpen, onDismissRequest = { freqOpen = false }, containerColor = c.card) {
                    listOf("daily" to "Daily", "weekly" to "Weekly", "off" to "Off").forEach { (k, label) ->
                        DropdownMenuItem(text = { Text(label, color = c.label, fontWeight = if (k == freq) FontWeight.SemiBold else FontWeight.Normal) },
                            onClick = { freqOpen = false; freq = k; ChatBackup.setFrequency(ctx, uid, k) })
                    }
                }
            }
            SwitchRow("Use Mobile Data", cell) { cell = it; ChatBackup.setMobileData(ctx, uid, it) }
            SwitchRow("Include Videos", videos, last = true) { videos = it; ChatBackup.setIncludeVideos(ctx, uid, it) }
        }
        SettingsSection(header = "Security", footer = if (hasPw) "You need this password to restore on a new phone, Android or iPhone."
            else "Your backup is always locked. A password also lets you restore on a new phone without this phone's Google backup, or on an iPhone.") {
            SwitchRow("Backup Password", hasPw, last = !hasPw) { on ->
                if (on) pwPage = true else confirmPwOff = true
            }
            if (hasPw) SettingsRow("Change Password", last = true) { pwPage = true }
        }
    }
    if (confirmPwOff) GlassAlert(onDismissRequest = { confirmPwOff = false },
        title = { Text("Turn off the backup password?") },
        text = { Text("Your next backup can only be restored with this Google account's phone backup.") },
        confirmButton = { TextButton({ confirmPwOff = false; scope.launch { ChatBackup.setPassword(ctx, uid, null); hasPw = false; run() } }) { Text("Turn Off", color = c.red) } },
        dismissButton = { TextButton({ confirmPwOff = false }) { Text("Cancel", color = c.accent) } }, containerColor = c.card)
    if (confirmReplace) GlassAlert(onDismissRequest = { confirmReplace = false },
        title = { Text("Replace your older backup?") },
        text = { Text("You skipped restoring an older backup. Backing up now replaces it with what is on this phone.") },
        confirmButton = { TextButton({ confirmReplace = false; run() }) { Text("Replace", color = c.red) } },
        dismissButton = { TextButton({ confirmReplace = false }) { Text("Cancel", color = c.accent) } }, containerColor = c.card)
}

/** Google's permission screen, asked for by whichever backup screen needed it. */
private val ask = mutableStateOf<Drive.Auth.Ask?>(null)

/** The backup password: a password (8+ characters) or a 64-digit key shown once to write down. */
@Composable
private fun BackupPasswordPage(onDone: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
    val scope = rememberCoroutineScope()
    val c = Fariin.colors
    var kind by remember { mutableStateOf<String?>(null) }
    var pw by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val digits = remember { (1..64).map { (java.security.SecureRandom().nextInt(10)) }.joinToString("") }
    androidx.activity.compose.BackHandler { if (kind != null) kind = null else onDone(false) }
    fun save(secret: String) {
        busy = true
        scope.launch { ChatBackup.setPassword(ctx, uid, secret); busy = false; onDone(true) }
    }
    when (kind) {
        null -> ScrollPage("Backup Password", { onDone(false) }) {
            SettingsSection(footer = "Fariin never sees it and can't reset it. If you lose it, the backup can't be opened.") {
                SettingsRow("Use a Password") { kind = "password" }
                SettingsRow("Use a 64-Digit Key", last = true) { kind = "key" }
            }
        }
        "password" -> {
            val ok = pw.length >= 8 && pw == again
            ScrollPage("Backup Password", { kind = null }, action = { BarAction("Save", ok, busy = busy) { if (ok) save(pw) } }) {
                SettingsSection(footer = "At least 8 characters.") {
                    CardField(pw, "Password", secret = true, last = false) { pw = it.take(64) }
                    CardField(again, "Confirm password", secret = true, keyboard = KeyboardType.Password) { again = it.take(64) }
                }
                if (again.isNotEmpty() && again != pw) ErrorNote("The passwords do not match.")
            }
        }
        else -> ScrollPage("64-Digit Key", { kind = null }) {
            SettingsSection(footer = "Write it down or keep it somewhere safe. You'll need it to restore on a new phone.") {
                Text(digits.chunked(4).chunked(4).joinToString("\n") { it.joinToString("  ") }, fontSize = 18.sp,
                    fontWeight = FontWeight.Medium, color = c.label, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(16.dp))
                Row(Modifier.fillMaxWidth()) {
                    TextButton({
                        (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                            .setPrimaryClip(android.content.ClipData.newPlainText("Backup key", digits).apply {
                                description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                            })
                    }, Modifier.weight(1f)) { Text("Copy", color = c.accent) }
                    TextButton({ if (!busy) save(digits) }, Modifier.weight(1f)) { Text("I Saved It", color = c.accent, fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }
}

/**
 * After sign-in (flows A and C): a phone with no history for this account (decided from META, before
 * inbox sync or the history pull file anything) is asked once to look for a backup in Google Drive;
 * a backup found is offered with Restore / Skip (and the password when the store key is not here);
 * none found, or after a restore, the backup card asks to turn backup on. Nothing while OFF.
 */
@Composable
fun ChatBackupGate(me: String) {
    if (!ChatBackup.ENABLED) return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = Fariin.colors
    val db = com.fariin.app.local.LocalDb
    var step by remember { mutableStateOf(when {
        db.meta(me, "restore.checked") == null && db.meta(me, "restored").isNullOrEmpty() && !db.hasSyncedMarks(me) -> "look"
        ChatBackup.frequency(ctx, me) == "off" && db.meta(me, "backup.asked") == null -> "card"
        else -> "" }) }
    var found by remember { mutableStateOf<ChatBackup.Found?>(null) }
    var token by remember { mutableStateOf<String?>(null) }
    var pw by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    // The Restore circle: look again, even though this backup was checked before.
    val reopen by ChatBackup.reopen.collectAsState()
    androidx.compose.runtime.LaunchedEffect(reopen) { if (reopen > 0) step = "look" }

    suspend fun search(t: String) {
        token = t
        found = runCatching { ChatBackup.find(ctx, me, t) }.getOrNull()
        if (found == null) { db.setMeta(me, "restore.checked", "none"); step = "card" } else step = "restore"
    }
    val google = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        val t = (ctx as? android.app.Activity)?.let { Drive.tokenFrom(it, res.data) }
        if (t != null) scope.launch { search(t) } else { db.setMeta(me, "restore.checked", "denied"); step = "card" }
    }
    when (step) {
        "look" -> GlassAlert(onDismissRequest = {},
            title = { Text("Look for a backup?") },
            text = { Text("If you backed up your chats to Google Drive before, Fariin can bring them back.") },
            confirmButton = { TextButton({ scope.launch {
                when (val a = Drive.authorize(ctx)) {
                    is Drive.Auth.Token -> search(a.value)
                    is Drive.Auth.Ask -> google.launch(IntentSenderRequest.Builder(a.intent).build())
                    Drive.Auth.Failed -> { db.setMeta(me, "restore.checked", "failed"); step = "card" }
                }
            } }) { Text("Continue", color = c.accent) } },
            dismissButton = { TextButton({ db.setMeta(me, "restore.checked", "skipped"); step = "card" }) { Text("Not Now", color = c.accent) } },
            containerColor = c.card)
        "restore" -> found?.let { f ->
            GlassAlert(onDismissRequest = {},
                title = { Text("Restore your chats?") },
                text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Backup from " + android.text.format.DateUtils.getRelativeDateTimeString(ctx, f.made,
                        android.text.format.DateUtils.MINUTE_IN_MILLIS, android.text.format.DateUtils.WEEK_IN_MILLIS, 0) + " · " + sizeWords(f.size))
                    if (f.needsPassword) Column(Modifier.fillMaxWidth()) {
                        CardField(pw, "Backup password or 64-digit key", secret = true) { pw = it.take(64) }
                    }
                    if (busy) Text("Restoring…", color = c.secondary)
                    note?.let { Text(it, color = c.red) }
                } },
                confirmButton = { TextButton({
                    if (busy) return@TextButton
                    busy = true; note = null
                    scope.launch {
                        note = ChatBackup.restore(ctx, me, token ?: return@launch, f, pw.ifEmpty { null })
                        busy = false
                        if (note == null) step = "card"
                    }
                }) { Text("Restore", color = c.accent) } },
                dismissButton = { TextButton({
                    // Skipped: the restore still waits (no automatic backup over it) and stays offered.
                    db.setMeta(me, "restore.checked", f.made.toString()); db.setMeta(me, "restore.waiting", f.made.toString())
                    step = "card"
                }) { Text("Skip", color = c.accent) } },
                containerColor = c.card)
        }
        "card" -> if (ChatBackup.frequency(ctx, me) == "off" && db.meta(me, "backup.asked") == null) GlassAlert(onDismissRequest = {},
            title = { Text("Keep a copy of your chats") },
            text = { Text("Saved to your Google Drive, locked so only you can open it.") },
            confirmButton = { TextButton({
                db.setMeta(me, "backup.asked", System.currentTimeMillis().toString()); step = ""
                ChatBackup.setFrequency(ctx, me, "daily")
                scope.launch { ChatBackup.backUp(ctx, me, automatic = true, token = token) }
            }) { Text("Turn On", color = c.accent) } },
            dismissButton = { TextButton({ db.setMeta(me, "backup.asked", "later:" + System.currentTimeMillis()); step = "" }) { Text("Not Now", color = c.accent) } },
            containerColor = c.card)
        else -> {}
    }
}
