package com.fariin.app.ui.settings

import androidx.compose.ui.res.vectorResource
import com.fariin.app.ui.components.underBar

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.R
import com.fariin.app.auth.Passkeys
import com.fariin.app.data.EmailRules
import com.fariin.app.data.MyProfile
import com.fariin.app.device.DeviceRegistry
import com.fariin.app.ui.settings.SecurityCheckupRules.Status
import com.fariin.app.ui.components.Avatar
import com.fariin.app.ui.theme.Fariin
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.DateFormat
import java.util.Date

/*
 * Security Checkup, after Kulan-iOS SecurityCheckupView.swift: passkey, password, where you're
 * signed in and Chat Key, each asked of the server, each opening the page that sets it up. The
 * iOS rules hold: a tick only from a server "yes", an unanswered check is "Couldn't check", the
 * checks run side by side, and a new check keeps the last answers on screen until it lands.
 * Opened from the Devices page header, as on iOS; a pushed page here (Android), with the same
 * Continue button at the bottom.
 */

internal const val PAGE_CHECKUP = "devices:checkup"
internal const val PAGE_CHECKUP_REVIEW = "devices:checkup:review"

/** Pure rules, unit tested: statuses, the lines under each title, what shows where. */
object SecurityCheckupRules {
    sealed class Status {
        object Checking : Status()
        data class Done(val line: String) : Status()   // verified; the line under the title
        object Missing : Status()                      // verified absent
        object Failed : Status()                       // the question could not be answered
    }

    data class Entry(val id: String, val title: String, val missingTitle: String, val why: String, val status: Status)

    data class Device(val id: String, val name: String, val location: String, val createdMs: Long?)

    /** The iOS order: passkey, password, devices, Chat Key. */
    fun entries(passkey: Status, password: Status, devices: Status, chatKey: Status, unreviewedCount: Int): List<Entry> {
        val passwordSet = password != Status.Missing
        return listOf(
            // iOS says Face ID; an Android passkey opens with the screen lock (Account page footer).
            Entry("passkey", "Passkey", "Create a passkey",
                "Sign in with your screen lock. There is no password for anyone to steal.", passkey),
            Entry("password", "Password", if (passwordSet) "Password" else "Set a password",
                "A password lets you sign in again if you lose this phone.", password),
            Entry("devices", "Where you're signed in",
                if (unreviewedCount == 1) "Review 1 new device" else "Review $unreviewedCount new devices",
                "Make sure you recognise every device signed in to your account.", devices),
            Entry("chatKey", "Chat Key", "Set a Chat Key",
                "People who know your Chat Key can start a chat with you.", chatKey),
        )
    }

    data class Groups(val missing: List<Entry>, val failed: List<Entry>, val rest: List<Entry>, val restHeader: String)

    fun groups(entries: List<Entry>): Groups {
        val missing = entries.filter { it.status == Status.Missing }
        val failed = entries.filter { it.status == Status.Failed }
        val rest = entries.filter { it.status != Status.Missing && it.status != Status.Failed }
        // "Everything is set up" only once nothing is still being checked.
        val header = when {
            rest.any { it.status == Status.Checking } -> "Checking your account…"
            missing.isEmpty() && failed.isEmpty() -> "Everything is set up"
            else -> "Set up"
        }
        return Groups(missing, failed, rest, header)
    }

    fun passkeyLine(count: Int, newestAdded: String?): String {
        val c = if (count == 1) "1 passkey" else "$count passkeys"
        return if (newestAdded != null) "$c · added $newestAdded" else c
    }

    fun passwordStatus(providers: List<Pair<String, String?>>): Status {
        val email = providers.firstOrNull { it.first == "password" } ?: return Status.Missing
        // A password on a relay address can never be typed (iOS passwordIsOnRelay): no password.
        return if (email.second?.let { EmailRules.isRelayAddress(it) } == true) Status.Missing else Status.Done("Set")
    }

    /** By device, not by clock: confirmed ids are reviewed; the review time answers for the rest. */
    fun unreviewed(others: List<Device>, reviewedAtMs: Long?, confirmed: Set<String>): List<Device> = others.filter { d ->
        if (d.id in confirmed) false
        else if (reviewedAtMs == null || d.createdMs == null) true
        else d.createdMs > reviewedAtMs
    }

    fun devicesLine(total: Int, reviewedAgo: String?): String {
        val t = if (total == 1) "Only this device" else "$total devices"
        return if (reviewedAgo != null) "$t · reviewed $reviewedAgo" else t
    }

    /** Says when the shown answers were checked; never "just now" for answers that are not. */
    fun checkedLine(lastMs: Long?, nowMs: Long, ago: String): String = when {
        lastMs == null -> "Checking your account against the server…"
        nowMs - lastMs < 60_000 -> "We checked your account against the server just now."
        else -> "We checked your account against the server $ago."
    }

    /** "Kampala, Uganda · signed in 3 days ago"; the place is left out until the server has one. */
    fun deviceDetail(location: String, ago: String?): String {
        val whenText = if (ago != null) "signed in $ago" else "signed in"
        return if (location.isEmpty()) whenText.replaceFirstChar { it.uppercase() } else "$location · $whenText"
    }
}

/** One check per app run: the last answers stay ready, so the page opens on them, not on spinners. */
internal object SecurityCheckup {
    var passkey by mutableStateOf<Status>(Status.Checking); private set
    var password by mutableStateOf<Status>(Status.Checking); private set
    var devices by mutableStateOf<Status>(Status.Checking); private set
    var chatKey by mutableStateOf<Status>(Status.Checking); private set
    var unreviewed by mutableStateOf<List<SecurityCheckupRules.Device>>(emptyList()); private set
    /** When the last scan finished with every answer in. */
    var lastCompleted by mutableStateOf<Long?>(null); private set
    private var scannedUid: String? = null
    private var running = false
    private var rerun = false

    private fun uid() = FirebaseAuth.getInstance().currentUser?.uid

    private fun resetForNewAccount() {
        scannedUid = uid()
        passkey = Status.Checking; password = Status.Checking; devices = Status.Checking; chatKey = Status.Checking
        unreviewed = emptyList(); lastCompleted = null
    }

    /** Made-up answers for the debug demo account, which has no server. */
    fun demo() {
        passkey = Status.Missing; password = Status.Done("Set"); devices = Status.Done("Only this device"); chatKey = Status.Missing
        lastCompleted = System.currentTimeMillis()
    }

    /** Asks all four again; each row changes when its own answer lands. */
    suspend fun run(ctx: Context) {
        if (running) { rerun = true; return }
        if (scannedUid != uid()) resetForNewAccount()
        running = true
        rerun = false
        var allLanded = true
        coroutineScope {
            launch { passkey = checkPasskey() }
            launch { password = checkPassword() }
            launch { chatKey = checkChatKey(ctx) }
            launch {
                val (s, list) = checkDevices(ctx)
                // A Yes that landed while this was in flight wins over this older answer; ask again.
                if (!rerun) { devices = s; unreviewed = list } else allLanded = false
            }
        }
        val all = listOf(passkey, password, devices, chatKey)
        if (allLanded && !rerun && all.none { it == Status.Checking || it == Status.Failed }) lastCompleted = System.currentTimeMillis()
        running = false
        if (rerun) run(ctx)
    }

    private fun date(ms: Long) = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))
    internal fun ago(ms: Long) = DateUtils.getRelativeTimeSpanString(ms, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

    /** `listPasskeys`, the same server call the Passkeys page draws from. */
    private suspend fun checkPasskey(): Status {
        val list = try { Passkeys.list() } catch (_: Exception) { return Status.Failed }
        if (list.isEmpty()) return Status.Missing
        val newest = list.mapNotNull { it.createdMs }.maxOrNull()?.toLong()
        return Status.Done(SecurityCheckupRules.passkeyLine(list.size, newest?.let { date(it) }))
    }

    /** The sign-in methods, reloaded from the server first: a cached user can list a removed password. */
    private suspend fun checkPassword(): Status {
        val user = FirebaseAuth.getInstance().currentUser ?: return Status.Failed
        try { user.reload().await() } catch (_: Exception) { return Status.Failed }
        val fresh = FirebaseAuth.getInstance().currentUser ?: return Status.Failed
        return SecurityCheckupRules.passwordStatus(fresh.providerData.map { it.providerId to it.email })
    }

    /** `chatPinStatus` on the server; null means it did not answer. */
    private suspend fun checkChatKey(ctx: Context): Status {
        ChatKey.bind(ctx)
        val set = ChatKey.status() ?: return Status.Failed
        return if (set) Status.Done("Set") else Status.Missing
    }

    /** The device list straight from the server; other devices signed in after the last review are new. */
    private suspend fun checkDevices(ctx: Context): Pair<Status, List<SecurityCheckupRules.Device>> {
        val me = uid() ?: return Status.Failed to emptyList()
        val snap = try {
            FirebaseFirestore.getInstance().collection("users").document(me).collection("devices").get(Source.SERVER).await()
        } catch (_: Exception) { return Status.Failed to emptyList() }
        val thisId = DeviceRegistry.thisDeviceId(ctx)
        val list = snap.documents.map { d ->
            val created = (d.get("createdAt") as? Timestamp)?.toDate()?.time ?: (d.get("createdAt") as? Number)?.toLong()
            SecurityCheckupRules.Device(d.id, d.getString("model") ?: "Phone", d.getString("location") ?: "", created)
        }
        val reviewed = reviewedAt(ctx)
        val fresh = SecurityCheckupRules.unreviewed(list.filter { it.id != thisId }, reviewed, reviewedIds(ctx))
        if (fresh.isNotEmpty()) return Status.Missing to fresh
        return Status.Done(SecurityCheckupRules.devicesLine(list.size, reviewed?.let { ago(it) })) to emptyList()
    }

    // Device review, kept on this phone per account (the iOS key names).
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("fariin_security", Context.MODE_PRIVATE)
    private fun reviewedAt(ctx: Context): Long? = uid()?.let { prefs(ctx).getLong("security.devicesReviewedAt.$it", 0L).takeIf { t -> t > 0 } }
    private fun reviewedIds(ctx: Context): Set<String> = uid()?.let { prefs(ctx).getStringSet("security.devicesReviewedIds.$it", null) }.orEmpty()

    /** "Yes, these are mine": confirmed by id, and the row turns green now; the next run asks again. */
    fun confirmDevices(ctx: Context) {
        val me = uid() ?: return
        val ids = reviewedIds(ctx) + unreviewed.map { it.id }
        prefs(ctx).edit().putStringSet("security.devicesReviewedIds.$me", ids)
            .putLong("security.devicesReviewedAt.$me", System.currentTimeMillis()).apply()
        unreviewed = emptyList()
        devices = Status.Done("Reviewed just now")
        rerun = running
    }
}

/** Routes this file draws; false when the page is not one of them. */
@Composable
internal fun securityCheckupRoute(page: String, profile: MyProfile?, demo: Boolean, push: (String) -> Unit, pop: () -> Unit, drop: (Int) -> Unit): Boolean {
    when (page) {
        PAGE_CHECKUP -> SecurityCheckupPage(profile, demo, push, pop)
        PAGE_CHECKUP_REVIEW -> DeviceReviewPage(
            onClose = pop,
            onYes = { SecurityCheckup.confirmDevices(it); pop() },   // back to the checkup, which asks again
            onNo = { drop(2) },                                      // to the Devices page, to sign it out
        )
        else -> return false
    }
    return true
}

/** The Devices page header (iOS DevicesHeader): the padlock art, the line, then Security Checkup as a
 *  grouped row (owner 2026-11-09: no big black button). */
@Composable
internal fun DevicesCheckupHeader(onCheckup: () -> Unit) {
    val c = Fariin.colors
    val ctx = LocalContext.current
    val demo = FirebaseAuth.getInstance().currentUser == null   // the debug demo account has no Firebase user
    // The answers are fetched while he looks at Devices, so the checkup opens already answered.
    LaunchedEffect(Unit) { if (demo) SecurityCheckup.demo() else SecurityCheckup.run(ctx) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        SecurityArt()
        Spacer(Modifier.size(18.dp))
        Text(buildAnnotatedString {
            append("Review your ")
            withStyle(SpanStyle(color = c.accent)) { append("account security") }
            append(" and check active devices")
        }, fontSize = 17.sp, color = c.label, textAlign = TextAlign.Center, modifier = Modifier.clickable(onClick = onCheckup))
    }
    SettingsSection { SettingsRow("Security Checkup", subtitle = "Review your account", last = true, onClick = onCheckup) }
}

@Composable
private fun WideButton(title: String, onClick: () -> Unit) {
    val c = Fariin.colors
    Box(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(CircleShape).background(c.accent).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.onAccent)
    }
}

private val Green = Color(0xFF34C759)
private val Orange = Color(0xFFFF9500)

@Composable
private fun SecurityCheckupPage(profile: MyProfile?, demo: Boolean, push: (String) -> Unit, pop: () -> Unit) {
    val c = Fariin.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // Every visit asks again (coming back from a setup page included); the old answers stay up meanwhile.
    LaunchedEffect(Unit) { if (demo) SecurityCheckup.demo() else SecurityCheckup.run(ctx) }
    val chk = SecurityCheckup
    val entries = SecurityCheckupRules.entries(chk.passkey, chk.password, chk.devices, chk.chatKey, chk.unreviewed.size)
    val g = SecurityCheckupRules.groups(entries)
    fun open(id: String) = when (id) {
        "passkey" -> push(PAGE_PASSKEYS)
        "password" -> push(PAGE_PASSWORD)
        "chatKey" -> push(PAGE_CHAT_KEY)
        // New devices get "Is this you?"; a tick row has nothing new to confirm, so back to the list.
        else -> if (chk.unreviewed.isEmpty()) pop() else push(PAGE_CHECKUP_REVIEW)
    }
    val barScroll = rememberScrollState()
    BarPage("Security Checkup", onBack = pop, under = underBar(barScroll)) { top ->
        Column(Modifier.weight(1f).verticalScroll(barScroll).padding(top = top + 8.dp, bottom = 16.dp)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Avatar(profile?.photoUrl, 96.dp)
                Spacer(Modifier.size(10.dp))
                Text("Security Checkup", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = c.label)
                Spacer(Modifier.size(4.dp))
                val last = chk.lastCompleted
                Text(SecurityCheckupRules.checkedLine(last, System.currentTimeMillis(), last?.let { SecurityCheckup.ago(it) } ?: ""),
                    fontSize = 15.sp, color = c.secondary, textAlign = TextAlign.Center)
            }
            if (g.missing.isNotEmpty()) SettingsSection(footer = "Set these up to keep your account safe if your phone is lost or someone learns your password.") {
                for (e in g.missing) CheckupRow(ImageVector.vectorResource(R.drawable.ic_sf_exclamation_circle), c.blue, e.missingTitle, e.why, e === g.missing.last()) { open(e.id) }
            }
            if (g.failed.isNotEmpty()) SettingsSection(header = "Couldn't check") {
                for (e in g.failed) CheckupRow(ImageVector.vectorResource(R.drawable.ic_sf_exclamation_triangle), Orange, e.title, "Check your connection and try again.", e === g.failed.last()) {
                    scope.launch { SecurityCheckup.run(ctx) }
                }
            }
            if (g.rest.isNotEmpty()) SettingsSection(header = g.restHeader) {
                for (e in g.rest) {
                    val s = e.status
                    if (s is SecurityCheckupRules.Status.Done) CheckupRow(ImageVector.vectorResource(R.drawable.ic_sf_checkmark_circle), Green, e.title, s.line, e === g.rest.last()) { open(e.id) }
                    else CheckupRow(null, c.secondary, e.title, "Checking…", e === g.rest.last(), null)
                }
            }
            // The rest of the tools (requests, block, report, scams) live on fariin.com/safety.
            Text("How Fariin keeps you safe", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.accent,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 16.dp).clickable { openWeb(ctx, "https://fariin.com/safety") }.padding(8.dp))
        }
        Box(Modifier.fillMaxWidth().background(c.bgSecondary).padding(horizontal = 20.dp, vertical = 8.dp)
            .padding(bottom = com.fariin.app.ui.shell.bottomBarInset())) {
            WideButton("Continue", pop)
        }
    }
}

/** A row like SettingsRow, but the icon keeps its own colour (green tick, blue mark) and the title stays plain. */
@Composable
private fun CheckupRow(icon: ImageVector?, tint: Color, title: String, subtitle: String, last: Boolean, onClick: (() -> Unit)?) {
    val c = Fariin.colors
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(40.dp), contentAlignment = Alignment.CenterStart) {
            if (icon != null) Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
            else com.fariin.app.ui.components.FariinSpinner(size = 20.dp)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 17.sp, color = c.label)
            Text(subtitle, fontSize = 15.sp, color = c.secondary)
        }
    }
    if (!last) HorizontalDivider(thickness = 0.5.dp, color = c.separator)
}

/** "Is this you?" for devices that signed in since the last review. Yes marks them reviewed; No goes to Devices. */
@Composable
private fun DeviceReviewPage(onClose: () -> Unit, onYes: (Context) -> Unit, onNo: () -> Unit) {
    val c = Fariin.colors
    val ctx = LocalContext.current
    val devices = SecurityCheckup.unreviewed
    val one = devices.size == 1
    val barScroll = rememberScrollState()
    BarPage("", onBack = onClose, under = underBar(barScroll)) { top ->
        Column(Modifier.weight(1f).verticalScroll(barScroll).padding(top = top + 8.dp, bottom = 16.dp)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (one) "Is this you?" else "Are these you?", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = c.label)
                Spacer(Modifier.size(8.dp))
                Text(if (one) "This device signed in to your account. Check that you recognise it."
                    else "These devices signed in to your account. Check that you recognise each one.",
                    fontSize = 15.sp, color = c.secondary, textAlign = TextAlign.Center)
            }
            SettingsSection {
                for ((i, d) in devices.withIndex()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp).clip(CircleShape).background(c.fieldFill), contentAlignment = Alignment.Center) {
                            Icon(painterResource(R.drawable.ic_linked_devices), contentDescription = null, tint = c.secondary, modifier = Modifier.size(22.dp))
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(d.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label)
                            Text(SecurityCheckupRules.deviceDetail(d.location, d.createdMs?.let { SecurityCheckup.ago(it) }), fontSize = 15.sp, color = c.secondary)
                        }
                    }
                    if (i != devices.lastIndex) HorizontalDivider(thickness = 0.5.dp, color = c.separator)
                }
            }
        }
        Column(Modifier.fillMaxWidth().background(c.bgSecondary).padding(horizontal = 20.dp, vertical = 8.dp)
            .padding(bottom = com.fariin.app.ui.shell.bottomBarInset()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            WideButton(if (one) "Yes, it's me" else "Yes, these are mine") { onYes(ctx) }
            Box(Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(CircleShape).clickable(onClick = onNo), contentAlignment = Alignment.Center) {
                Text("No, it's not me", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.red)
            }
        }
    }
}

/** The iOS SecurityShieldIcon, drawn: a shield with an "i", and a round check badge cut into its corner. */
@Composable
private fun SecurityShieldIcon(modifier: Modifier) {
    val c = Fariin.colors
    Canvas(modifier) {
        val s = minOf(size.width, size.height)
        val line = s * 0.062f
        val stroke = Stroke(width = line, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val x0 = s * 0.04f; val y0 = s * 0.02f; val w = s * 0.78f; val h = s * 0.9f
        val shield = Path().apply {
            moveTo(x0 + w * 0.5f, y0)
            quadraticTo(x0 + w * 0.76f, y0 + h * 0.12f, x0 + w, y0 + h * 0.14f)
            lineTo(x0 + w, y0 + h * 0.46f)
            cubicTo(x0 + w, y0 + h * 0.74f, x0 + w * 0.78f, y0 + h * 0.9f, x0 + w * 0.5f, y0 + h)
            cubicTo(x0 + w * 0.22f, y0 + h * 0.9f, x0, y0 + h * 0.74f, x0, y0 + h * 0.46f)
            lineTo(x0, y0 + h * 0.14f)
            quadraticTo(x0 + w * 0.24f, y0 + h * 0.12f, x0 + w * 0.5f, y0)
            close()
        }
        drawPath(shield, c.label, style = stroke)
        // The "i": a dot and a short stem.
        drawCircle(c.label, radius = line * 0.625f, center = Offset(s * 0.24f, s * 0.27f))
        drawLine(c.label, Offset(s * 0.24f, s * 0.46f - s * 0.1f + line / 2), Offset(s * 0.24f, s * 0.46f + s * 0.1f - line / 2), strokeWidth = line, cap = StrokeCap.Round)
        // The badge: the page colour first, so it cuts the shield's line where they overlap.
        val badge = Offset(s * 0.72f, s * 0.72f)
        drawCircle(c.bgSecondary, radius = s * 0.23f, center = badge)
        drawCircle(c.label, radius = s * 0.18f, center = badge, style = Stroke(width = line))
        val cw = s * 0.17f; val ch = s * 0.12f; val cx = s * 0.72f - cw / 2; val cy = s * 0.725f - ch / 2
        val check = Path().apply {
            moveTo(cx, cy + ch * 0.5f)
            lineTo(cx + cw * 0.36f, cy + ch)
            lineTo(cx + cw, cy)
        }
        drawPath(check, c.blue, style = stroke)
    }
}
