package com.fariin.app.ui.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.data.AuthRepository
import com.fariin.app.data.BlockList
import com.fariin.app.data.CryptoHub
import com.fariin.app.data.MyProfile
import com.fariin.app.data.Presence
import com.fariin.app.device.DeviceRegistry
import com.fariin.app.ui.theme.Fariin
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.DateFormat
import java.util.Date

/**
 * Your Account Data, after Kulan-iOS DataExport.accountReport: profile, sign-in methods, privacy,
 * devices, blocked accounts and the chat count. Never any message. Shared as plain text.
 */
object AccountReport {
    data class Device(val model: String, val os: String, val app: String, val lastSeenMs: Long, val thisDevice: Boolean)
    data class Blocked(val name: String, val handle: String)

    fun providerName(id: String) = when (id) {
        "password" -> "Email and password"; "apple.com" -> "Apple"; "google.com" -> "Google"; else -> id
    }

    fun blockedLine(b: Blocked) = when {
        b.name.isNotEmpty() && b.handle.isNotEmpty() -> "${b.name} (@${b.handle})"
        b.handle.isNotEmpty() -> "@${b.handle}"
        b.name.isNotEmpty() -> b.name
        else -> "An account you blocked"
    }

    /** Pure: the file's text. */
    fun build(
        now: Long, uid: String, name: String, handle: String, about: String, email: String?, joinedMs: Long?, hasPhoto: Boolean,
        providers: List<String>, privacy: Map<String, String>, devices: List<Device>, blocked: List<Blocked>, chats: Int,
        fmt: (Long) -> String,
    ): String = buildString {
        append("Fariin, account information\n")
        append("Created ${fmt(now)}\n\n")
        append("ACCOUNT\n")
        append("Name: $name\n")
        append("Username: @$handle\n")
        if (about.isNotEmpty()) append("Bio: $about\n")
        append("Account ID: $uid\n")
        if (!email.isNullOrEmpty()) append("Email: $email\n")
        if (joinedMs != null) append("Joined: ${fmt(joinedMs)}\n")
        append("Profile photo: ${if (hasPhoto) "Yes" else "No"}\n\n")
        append("SIGN-IN METHODS\n")
        if (providers.isEmpty()) append("  None\n")
        for (p in providers) append("  ${providerName(p)}\n")
        append("\nPRIVACY\n")
        if (privacy.isEmpty()) append("  Defaults\n")
        for ((k, v) in privacy.toSortedMap()) append("  $k: $v\n")
        append("\nDEVICES\n")
        if (devices.isEmpty()) append("  None\n")
        for (d in devices.sortedByDescending { it.lastSeenMs }) {
            append("  ${d.model}, ${d.os}, app ${d.app}, last active ${fmt(d.lastSeenMs)}")
            append(if (d.thisDevice) " (this phone)\n" else "\n")
        }
        append("\nBLOCKED ACCOUNTS\n")
        if (blocked.isEmpty()) append("  None\n")
        for (b in blocked) append("  ${blockedLine(b)}\n")
        append("\nACTIVITY\n")
        append("Chats: $chats\n\n")
        append("Messages are not in this file. One-to-one messages are end-to-end encrypted and only ")
        append("your phones can read them.\n")
    }

    /** Reads what the file needs and opens the share sheet. Throws a sentence to show. */
    suspend fun share(context: Context) {
        val user = FirebaseAuth.getInstance().currentUser ?: throw IllegalStateException("You are logged out.")
        val uid = user.uid
        val db = FirebaseFirestore.getInstance()
        val me = try { db.collection("users").document(uid).get().await().data ?: emptyMap() }
            catch (_: Exception) { throw IllegalStateException("Could not load everything. Check your connection and try again.") }
        val thisId = DeviceRegistry.thisDeviceId(context)
        val devices = try {
            db.collection("users").document(uid).collection("devices").get().await().documents.map { d ->
                Device(d.getString("model") ?: "", d.getString("os") ?: "", d.getString("appVersion") ?: "",
                    (d.get("lastSeenAt") as? Timestamp)?.toDate()?.time ?: 0L, d.id == thisId)
            }
        } catch (_: Exception) { throw IllegalStateException("Could not load everything. Check your connection and try again.") }
        val blocked = BlockList.snapshot.entries.keys.sorted().map { b ->
            val d = try { db.collection("users").document(b).get().await().data } catch (_: Exception) { null }
            Blocked(d?.get("name") as? String ?: "", d?.get("handle") as? String ?: "")
        }
        val profile = MyProfile.fromFirestore(uid, me)
        val joined = (me["joinedAt"] as? Timestamp)?.toDate()?.time ?: user.metadata?.creationTimestamp
        val df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        val text = build(
            System.currentTimeMillis(), uid, profile.name, profile.handle, profile.about, user.email, joined, profile.photoUrl != null,
            user.providerData.map { it.providerId }.filter { it != "firebase" }, Presence.privacyOf(me), devices, blocked,
            CryptoHub.listedAll().count { !it.isCleared(uid) }, fmt = { df.format(Date(it)) },
        )
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Fariin-Account-Information.txt").putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, "Your Account Data").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

internal const val PAGE_DELETE = "account:delete"

/** iOS LeaveReason raw values (only the reason is kept, not who gave it). */
val leaveReasons = listOf(
    "" to "Prefer not to say", "not-using" to "I don't use it any more", "privacy" to "Privacy worries",
    "other-app" to "I use another app", "unwanted-messages" to "Unwanted messages", "problems" to "Something isn't working",
    "other" to "Something else",
)

/**
 * iOS DeleteAccountView: what happens and Download My Data First on the page; Continue opens the
 * careful sheet: a softer way out first (Turn On App Lock, Log Out Instead), then the optional
 * reason, then the last confirm with the password again for an email account, and the
 * `scheduleAccountDeletion` callable {reason} (30 days hidden, then deleted; signing in before then
 * restores it). Done logs out.
 */
@Composable
internal fun DeleteAccountPage(profile: MyProfile?, pop: () -> Unit, onSignOut: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = Fariin.colors
    var reason by remember { mutableStateOf("") }
    var step by remember { mutableStateOf("confirm") }    // confirm, done
    var sheet by remember { mutableStateOf(false) }
    var sheetStep by remember { mutableStateOf(0) }       // 0 softer choice, 1 reason, 2 final
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var sheetError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var due by remember { mutableStateOf(System.currentTimeMillis() + 30L * 86_400_000) }
    val hasPassword = remember { FirebaseAuth.getInstance().currentUser?.providerData?.any { it.providerId == "password" } == true }
    val dueText = DateFormat.getDateInstance(DateFormat.LONG).format(Date(due))
    val handle = profile?.handle.orEmpty()

    // The last step: the password again for an email account ("Confirm it's you"), then the callable.
    fun deleteNow() {
        if (busy) return
        if (hasPassword && password.isEmpty()) { sheetError = "Enter your password."; return }
        busy = true; sheetError = null
        scope.launch {
            try {
                if (hasPassword) {
                    try { AuthRepository().reauthEmail(password) }
                    catch (e: Exception) { sheetError = AuthRepository.messageFor(e, "That password is not right."); return@launch }
                }
                val fns = FirebaseFunctions.getInstance(AuthRepository.FUNCTIONS_REGION)
                runCatching { fns.getHttpsCallable("notifyAccountDeleted").call(emptyMap<String, Any>()).await() }
                val r = fns.getHttpsCallable("scheduleAccountDeletion").call(if (reason.isEmpty()) emptyMap() else mapOf("reason" to reason)).await().getData() as? Map<*, *>
                (r?.get("due") as? Number)?.toLong()?.let { due = it }
                sheet = false
                step = "done"
            } catch (e: Exception) {
                sheetError = AuthRepository.messageFor(e)
            } finally { busy = false }
        }
    }

    ScrollPage(if (step == "done") "Deletion Scheduled" else "Delete Account", if (step == "done") ({}) else pop) {
        ErrorNote(error)
        when (step) {
            "done" -> {
                SettingsSection(footer = "Until then nobody can find you or see your profile. Changed your mind? Sign in before that day and choose Cancel Deletion.") {
                    Text("Your account will be deleted on $dueText.", fontSize = 17.sp, color = c.label, modifier = Modifier.padding(16.dp))
                }
                SettingsSection { SettingsRow("Done", trailing = Trailing.NONE, titleColor = c.accent, last = true) { onSignOut() } }
            }
            else -> {
                SettingsSection {
                    Column(Modifier.padding(16.dp)) {
                        Text("Delete Your Account", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = c.label)
                        Spacer(Modifier.height(6.dp))
                        Text("Read what happens before you go on. Nothing is deleted on this page.", fontSize = 15.sp, color = c.secondary)
                    }
                }
                SettingsSection(header = "What happens") {
                    Fact("Hidden right away", "You are logged out. Nobody can find you, see your profile or your stories.")
                    Fact("Deleted for good on $dueText", "Your profile, photo, username, stories and group memberships are removed. Your username becomes free for others after a while.")
                    Fact("You can change your mind", "Sign in before that day and choose Cancel Deletion. Everything comes back as it was.")
                    Fact("What stays", "Messages you already sent stay with the people who got them. Your name there becomes Deleted Account.", last = true)
                }
                SettingsSection(footer = "A file with your account details, before they are gone.") {
                    SettingsRow("Download My Data First", trailing = Trailing.NONE, titleColor = c.accent, last = true) {
                        scope.launch { try { AccountReport.share(ctx) } catch (e: Exception) { error = e.message } }
                    }
                }
                if (handle.isNotEmpty()) SettingsSection(header = "Account to delete") {
                    PersonRow(profile?.name?.ifEmpty { null } ?: "You", profile?.photoUrl, sub = "@$handle", last = true, onClick = null)
                }
                SettingsSection {
                    SettingsRow("Continue", trailing = Trailing.NONE, titleColor = c.accent, last = true) {
                        error = null; sheetError = null; sheetStep = 0; sheet = true
                    }
                }
            }
        }
    }
    if (sheet) {
        val lockOff = !com.fariin.app.security.AppLock.enabled(ctx)
        com.fariin.app.ui.components.CarefulSheet(onDismiss = { if (!busy) sheet = false }, step = sheetStep, steps = listOf(
            com.fariin.app.ui.components.CarefulStep(
                title = "Need a break instead?",
                body = if (lockOff) "You can log out and come back any time, or lock Fariin so nobody else on this phone can open it."
                    else "You can log out and come back any time. Your chats wait for you.",
                buttons = listOfNotNull(
                    if (lockOff) com.fariin.app.ui.components.CarefulButton("Turn On App Lock", com.fariin.app.ui.components.CarefulKind.SOFT) {
                        if (com.fariin.app.security.AppLock.deviceSecure(ctx)) {
                            com.fariin.app.security.AppLock.setEnabled(ctx, true)
                            com.fariin.app.ui.components.Snack.show("App Lock is on.")
                        } else com.fariin.app.ui.components.Snack.show("Set a screen lock on your phone first.")
                    } else null,
                    com.fariin.app.ui.components.CarefulButton("Log Out Instead", com.fariin.app.ui.components.CarefulKind.SOFT) { onSignOut() },
                    com.fariin.app.ui.components.CarefulButton("Continue", closes = false) { sheetStep = 1 },
                ),
            ),
            com.fariin.app.ui.components.CarefulStep(
                title = "Why are you leaving?",
                body = "Optional. Only the reason is kept, not who gave it.",
                options = leaveReasons, selected = reason, onSelect = { reason = it },
                buttons = listOf(com.fariin.app.ui.components.CarefulButton("Continue", closes = false) { sheetStep = 2 }),
            ),
            com.fariin.app.ui.components.CarefulStep(
                title = if (handle.isEmpty()) "Delete your account?" else "Delete @$handle?",
                body = "You are logged out and your account is hidden right away. It is deleted for good on $dueText, 30 days from now. Log in before then to keep it.",
                error = sheetError,
                extra = if (hasPassword) ({
                    Column(Modifier.fillMaxWidth()) {
                        Text("Confirm it's you", fontSize = 13.sp, color = c.secondary, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
                        OutlinedTextField(password, { password = it; sheetError = null }, label = { Text("Password") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth())
                    }
                }) else null,
                buttons = listOf(com.fariin.app.ui.components.CarefulButton("Delete Account", com.fariin.app.ui.components.CarefulKind.DESTRUCTIVE,
                    enabled = !hasPassword || password.isNotEmpty(), busy = busy, closes = false) { deleteNow() }),
            ),
        ))
    }
}

@Composable
private fun Fact(title: String, detail: String, last: Boolean = false) {
    val c = Fariin.colors
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Spacer(Modifier.width(2.dp))
            Column {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.label)
                Text(detail, fontSize = 13.sp, color = c.secondary)
            }
        }
        if (!last) androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = c.separator)
    }
}
