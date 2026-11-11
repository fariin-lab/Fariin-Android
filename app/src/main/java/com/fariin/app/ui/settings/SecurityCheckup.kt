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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.collectAsState
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
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateBounds
import kotlinx.coroutines.flow.first
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.alpha
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

    /** A fresh scan (the sheet's open): every row back to Checking, so no answer shows before it exists. */
    fun clearForScan() {
        if (scannedUid != uid()) resetForNewAccount()
        passkey = Status.Checking; password = Status.Checking; devices = Status.Checking; chatKey = Status.Checking
        lastCompleted = null
    }

    /** Asks all four again; each row changes when its own answer lands. */
    suspend fun run(ctx: Context) {
        if (running) { rerun = true; return }
        if (scannedUid != uid()) resetForNewAccount()
        running = true
        rerun = false
        var allLanded = true
        // Each ask has its own limit, and `running` is let go however this ends (owner 2026-11-11: every
        // row spun for good). A scan left mid-way (the sheet closed) used to keep `running` set, so every
        // later scan only marked a rerun and returned, its rows reset to Checking with nobody asking.
        try {
            coroutineScope {
                launch { passkey = limited { checkPasskey() } }
                launch { password = limited { checkPassword() } }
                launch { chatKey = limited { checkChatKey(ctx) } }
                launch {
                    val (s, list) = kotlinx.coroutines.withTimeoutOrNull(CHECK_LIMIT_MS) { checkDevices(ctx) } ?: (Status.Failed to emptyList())
                    // A Yes that landed while this was in flight wins over this older answer; ask again.
                    if (!rerun) { devices = s; unreviewed = list } else allLanded = false
                }
            }
        } finally {
            running = false
            // Cancelled: no row stays on Checking with nobody asking; Try Again is offered.
            if (passkey == Status.Checking) passkey = Status.Failed
            if (password == Status.Checking) password = Status.Failed
            if (chatKey == Status.Checking) chatKey = Status.Failed
            if (devices == Status.Checking) devices = Status.Failed
        }
        val all = listOf(passkey, password, devices, chatKey)
        if (allLanded && !rerun && all.none { it == Status.Checking || it == Status.Failed }) lastCompleted = System.currentTimeMillis()
        if (rerun) run(ctx)
    }

    private const val CHECK_LIMIT_MS = 15_000L
    private suspend fun limited(ask: suspend () -> Status): Status =
        kotlinx.coroutines.withTimeoutOrNull(CHECK_LIMIT_MS) { ask() } ?: Status.Failed

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

/** The Devices page header (iOS DevicesHeader): the padlock art, the line and the Security Checkup button. */
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
        // The wide capsule again (owner 2026-11-09, after trying the row on the Pixel).
        Spacer(Modifier.size(18.dp))
        WideButton("Security Checkup", onCheckup)
    }
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
            CheckupContent(profile, ::open, onRetry = { scope.launch { SecurityCheckup.run(ctx) } })
        }
        Box(Modifier.fillMaxWidth().background(c.bgSecondary).padding(horizontal = 20.dp, vertical = 8.dp)
            .padding(bottom = com.fariin.app.ui.shell.bottomBarInset())) {
            WideButton("Continue", pop)
        }
    }
}


/** The checkup's header, groups and safety link: the same on the old page and the sheet. */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.CheckupContent(profile: MyProfile?, open: (String) -> Unit, onRetry: () -> Unit) {
    val c = Fariin.colors
    val ctx = LocalContext.current
    val chk = SecurityCheckup
    val entries = SecurityCheckupRules.entries(chk.passkey, chk.password, chk.devices, chk.chatKey, chk.unreviewed.size)
    val g = SecurityCheckupRules.groups(entries)
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
            onRetry()
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

/**
 * The Security Checkup as iOS draws it (owner 2026-11-09): a bottom sheet over Devices, as tall as
 * its rows plus the Continue bar (scrolling only when they do not fit). Each setup page opens as its
 * own sheet with an X; closing one asks the checkup again.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun SecurityCheckupSheet(onClose: () -> Unit) {
    val c = Fariin.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val me = FirebaseAuth.getInstance().currentUser?.uid
    val demo = me == null
    val profile = if (me != null) remember(me) { com.fariin.app.data.MyProfileRepository().listen(me) }.collectAsState(initial = null).value else null
    val state = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var sub by remember { mutableStateOf<String?>(null) }
    // The first scan is ScanCheckup's. Back from a setup sheet: no new scan, the rows refresh in place.
    var subSeen by remember { mutableStateOf(false) }
    LaunchedEffect(sub) { if (sub != null) subSeen = true else if (subSeen && !demo) SecurityCheckup.run(ctx) }
    fun close() { scope.launch { state.hide() }.invokeOnCompletion { onClose() } }
    fun open(id: String) {
        when (id) {
            "passkey" -> sub = "passkey"
            "password" -> sub = "password"
            "chatKey" -> sub = "chatKey"
            // New devices get "Is this you?"; nothing new: back to the Devices list.
            else -> if (SecurityCheckup.unreviewed.isEmpty()) close() else sub = "review"
        }
    }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onClose, sheetState = state, containerColor = c.bgSecondary) {
        ScanCheckup(profile, demo, ::open, onRetry = { scope.launch { SecurityCheckup.run(ctx) } }, onContinue = { close() })
    }
    val page = sub
    if (page != null) {
        val subState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
        fun closeSub(then: () -> Unit = {}) { scope.launch { subState.hide() }.invokeOnCompletion { sub = null; then() } }
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { sub = null }, sheetState = subState,
            containerColor = c.bgSecondary, dragHandle = null) {
            androidx.compose.runtime.CompositionLocalProvider(com.fariin.app.ui.components.LocalBackIsClose provides true) {
                Box(Modifier.fillMaxWidth().fillMaxHeight(0.94f)) {
                    val auth = remember { com.fariin.app.data.AuthRepository() }
                    when {
                        page == "passkey" -> com.fariin.app.ui.auth.PasskeysPage(auth, { closeSub() }, demoRows = if (demo) emptyList() else null)
                        page == "password" -> com.fariin.app.ui.auth.PasswordPage(auth, { closeSub() }, onForgot = { sub = "reset:$it" }, demoOpen = demo)
                        page.startsWith("reset:") -> com.fariin.app.ui.auth.ResetCodePage(auth, page.removePrefix("reset:"), { sub = "password" },
                            onDone = { closeSub() }, demo = demo)
                        page == "chatKey" -> ChatKeyPage { closeSub() }
                        page == "review" -> DeviceReviewPage(onClose = { closeSub() },
                            onYes = { SecurityCheckup.confirmDevices(it); closeSub() },
                            // "No, it's not me": both sheets close, back to the Devices list to sign it out.
                            onNo = { closeSub { close() } })
                    }
                }
            }
        }
    }
}


/**
 * THE SCAN (owner 2026-11-09, "a real bank-style scan"). Honest: every open asks the server again
 * and a row resolves only when ITS answer lands; the only thing added is pacing (the first row no
 * sooner than 450 ms, 280 ms between rows, 1.5 s at least in all). Then the ring closes, the header
 * says the result, and the one card re-forms into the final groups with the rows sliding (no pops).
 */
@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
private fun ScanCheckup(profile: MyProfile?, demo: Boolean, open: (String) -> Unit, onRetry: () -> Unit, onContinue: () -> Unit) {
    val c = Fariin.colors
    val ctx = LocalContext.current
    val view = androidx.compose.ui.platform.LocalView.current
    val moving = com.fariin.app.ui.thread.systemAnimationsOn()
    val chk = SecurityCheckup
    val entries = SecurityCheckupRules.entries(chk.passkey, chk.password, chk.devices, chk.chatKey, chk.unreviewed.size)
    val ids = entries.map { it.id }
    // How far the scan has got: rows shown, rows resolved (and what to), the finish, the settle.
    var shown by remember { mutableStateOf(0) }
    var resolved by remember { mutableStateOf<Map<String, SecurityCheckupRules.Status>>(emptyMap()) }
    var finished by remember { mutableStateOf(false) }
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val t0 = System.currentTimeMillis()
        if (demo) SecurityCheckup.demo() else { SecurityCheckup.clearForScan(); launch { SecurityCheckup.run(ctx) } }
        var lastAt = t0
        for (i in ids.indices) {
            // Shown in order: the first at 450 ms, then 280 ms apart.
            val showAt = maxOf(t0 + 450, lastAt + 280)
            kotlinx.coroutines.delay(maxOf(0L, showAt - System.currentTimeMillis()))
            shown = i + 1
            lastAt = System.currentTimeMillis()
        }
        // Each resolves in order once its answer exists, 280 ms after the one before at least.
        for (id in ids) {
            val st = androidx.compose.runtime.snapshotFlow {
                SecurityCheckupRules.entries(chk.passkey, chk.password, chk.devices, chk.chatKey, chk.unreviewed.size).first { it.id == id }.status
            }.first { it != SecurityCheckupRules.Status.Checking }
            kotlinx.coroutines.delay(maxOf(0L, lastAt + 280 - System.currentTimeMillis()))
            resolved = resolved + (id to st)
            lastAt = System.currentTimeMillis()
            view.performHapticFeedback(if (st is SecurityCheckupRules.Status.Done)
                android.view.HapticFeedbackConstants.CLOCK_TICK else android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        }
        kotlinx.coroutines.delay(maxOf(0L, t0 + 1500 - System.currentTimeMillis()))
        finished = true
        val allGood = resolved.values.all { it is SecurityCheckupRules.Status.Done }
        if (android.os.Build.VERSION.SDK_INT >= 30) view.performHapticFeedback(
            if (allGood) android.view.HapticFeedbackConstants.CONFIRM else android.view.HapticFeedbackConstants.REJECT)
        kotlinx.coroutines.delay(450L + 300L)   // the ring closes (250) and fades (200), then 300 ms
        settled = true
    }
    // During the scan a row shows what it resolved to; once settled, the live answer (a refresh in place).
    fun statusOf(e: SecurityCheckupRules.Entry) = if (settled) e.status else resolved[e.id] ?: SecurityCheckupRules.Status.Checking
    val shownEntries = entries.take(shown).map { it.copy(status = statusOf(it)) }
    val missingCount = if (finished) resolved.values.count { it == SecurityCheckupRules.Status.Missing } else 0
    val allSet = finished && missingCount == 0

    Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(top = 12.dp, bottom = 8.dp)) {
            // HEADER: the photo with its scan ring, the title, the line that turns into the result.
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                ScanAvatar(profile?.photoUrl, finished, allSet, moving)
                Spacer(Modifier.size(10.dp))
                Text("Security Checkup", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = c.label)
                Spacer(Modifier.size(4.dp))
                val result = if (!finished) "Checking your account…" else when (missingCount) {
                    0 -> "Everything is set up"
                    1 -> "1 thing to set up"
                    else -> "$missingCount things to set up"
                }
                androidx.compose.animation.Crossfade(targetState = result, label = "result") { t ->
                    Text(t, fontSize = 15.sp, fontWeight = if (finished) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (finished) c.label else c.secondary, textAlign = TextAlign.Center)
                }
                val note by androidx.compose.animation.core.animateFloatAsState(if (finished) 1f else 0f, label = "note")
                Text("We checked your account against the server just now.", fontSize = 13.sp, color = c.secondary,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = 2.dp).alpha(note))
            }
            // ⛔ ROWS: ONE CARD, ONE ORDER, BEFORE AND AFTER (owner 2026-11-09, second Pixel video):
            // regrouping at the end (the missing row into its own card, a "Set up" heading, the rest
            // moving down) read as the whole list jumping, crossfade or not. The list the scan builds
            // IS the result: a blue "!" marks what is missing. At the end only the note below fades in.
            val finalRoom = (entries.size * 68 + 120).dp
            Column(Modifier.fillMaxWidth().heightIn(min = finalRoom)) {
                ScanCard { for ((i, e) in shownEntries.withIndex()) androidx.compose.runtime.key(e.id) { ScanRow(e, i == shownEntries.lastIndex, moving, Modifier, open, onRetry) } }
                val anyMissing = settled && shownEntries.any { it.status == SecurityCheckupRules.Status.Missing }
                val noteAlpha by androidx.compose.animation.core.animateFloatAsState(if (anyMissing) 1f else 0f, androidx.compose.animation.core.tween(250), label = "missingNote")
                if (anyMissing) Text("Set these up to keep your account safe if your phone is lost or someone learns your password.",
                    fontSize = 13.sp, color = c.secondary, modifier = Modifier.padding(horizontal = 32.dp).padding(top = 6.dp, bottom = 8.dp).alpha(noteAlpha))
            }
            val tail by androidx.compose.animation.core.animateFloatAsState(if (settled) 1f else 0f, androidx.compose.animation.core.tween(200), label = "tail")
            Text("How Fariin keeps you safe", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.accent,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 16.dp).alpha(tail)
                    .clickable(enabled = settled) { openWeb(ctx, "https://fariin.com/safety") }.padding(8.dp))
        }
        val cont by androidx.compose.animation.core.animateFloatAsState(if (settled) 1f else 0.4f, androidx.compose.animation.core.tween(200), label = "cont")
        Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).alpha(cont)) {
            WideButton("Done") { if (settled) onContinue() }   // owner 2026-11-09: it closes back to Devices
        }
    }
}

@Composable
private fun ScanCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(22.dp))
        .background(Fariin.colors.card), content = content)
}

/** One check: in from 8 dp lower; a spinner until its answer, then the icon springs in. */
@Composable
private fun ScanRow(e: SecurityCheckupRules.Entry, last: Boolean, moving: Boolean, modifier: Modifier, open: (String) -> Unit, onRetry: () -> Unit) {
    val c = Fariin.colors
    val s = e.status
    val appear = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, androidx.compose.animation.core.tween(220, easing = androidx.compose.animation.core.LinearOutSlowInEasing)) }
    val title = if (s == SecurityCheckupRules.Status.Missing) e.missingTitle else e.title
    val sub = when (s) {
        SecurityCheckupRules.Status.Checking -> "Checking…"
        SecurityCheckupRules.Status.Missing -> e.why
        SecurityCheckupRules.Status.Failed -> "Check your connection and try again."
        is SecurityCheckupRules.Status.Done -> s.line
    }
    val tap: (() -> Unit)? = when (s) {
        SecurityCheckupRules.Status.Checking -> null
        SecurityCheckupRules.Status.Failed -> onRetry
        else -> ({ open(e.id) })
    }
    val kind = when (s) {
        SecurityCheckupRules.Status.Checking -> 0
        is SecurityCheckupRules.Status.Done -> 1
        SecurityCheckupRules.Status.Missing -> 2
        SecurityCheckupRules.Status.Failed -> 3
    }
    Column(modifier.graphicsLayer { alpha = appear.value; if (moving) translationY = (1f - appear.value) * 8.dp.toPx() }) {
        Row(Modifier.fillMaxWidth().then(if (tap != null) Modifier.clickable(onClick = tap) else Modifier)
            .heightIn(min = 60.dp).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(38.dp), contentAlignment = Alignment.CenterStart) {
                androidx.compose.animation.AnimatedContent(targetState = kind, label = "icon", transitionSpec = {
                    (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(150)) +
                        androidx.compose.animation.scaleIn(androidx.compose.animation.core.spring(dampingRatio = 0.6f), initialScale = 0.6f)) togetherWith
                        androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120))
                }) { k ->
                    when (k) {
                        0 -> if (moving) com.fariin.app.ui.components.FariinSpinner(size = 18.dp) else Spacer(Modifier.size(18.dp))
                        1 -> Icon(ImageVector.vectorResource(R.drawable.ic_sf_checkmark_circle), null, tint = Green, modifier = Modifier.size(24.dp))
                        2 -> Icon(ImageVector.vectorResource(R.drawable.ic_sf_exclamation_circle), null, tint = c.blue, modifier = Modifier.size(24.dp))
                        else -> Icon(ImageVector.vectorResource(R.drawable.ic_sf_exclamation_triangle), null, tint = Orange, modifier = Modifier.size(24.dp))
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 17.sp, color = c.label)
                androidx.compose.animation.Crossfade(sub, label = "sub") { Text(it, fontSize = 15.sp, color = c.secondary) }
            }
            if (tap != null && kind != 3) Icon(ImageVector.vectorResource(R.drawable.ic_sf_chevron_right), null,
                tint = c.secondary.copy(alpha = 0.55f), modifier = Modifier.padding(start = 4.dp).size(20.dp))
        }
        if (!last) HorizontalDivider(Modifier.padding(start = 54.dp), thickness = 0.5.dp, color = c.separator)
    }
}

/** The photo with the scan ring: an arc spinning while checking, which sweeps shut and fades; then a check badge if all is set. */
@Composable
private fun ScanAvatar(photo: String?, finished: Boolean, allSet: Boolean, moving: Boolean) {
    val c = Fariin.colors
    val spinner = androidx.compose.animation.core.rememberInfiniteTransition(label = "scan")
    val spin = if (moving && !finished) spinner.animateFloat(0f, 360f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(900, easing = androidx.compose.animation.core.LinearEasing)), label = "spin").value
        else 0f
    val sweep by androidx.compose.animation.core.animateFloatAsState(if (finished) 360f else 100f, androidx.compose.animation.core.tween(250), label = "sweep")
    val ringAlpha by androidx.compose.animation.core.animateFloatAsState(if (finished) 0f else 1f,
        androidx.compose.animation.core.tween(200, delayMillis = 250), label = "ringAlpha")
    val badge by androidx.compose.animation.core.animateFloatAsState(if (finished && allSet) 1f else 0f,
        androidx.compose.animation.core.spring(dampingRatio = 0.6f), label = "badge")
    Box(Modifier.size(106.dp), contentAlignment = Alignment.Center) {
        Avatar(photo, 96.dp)
        Canvas(Modifier.matchParentSize()) {
            if (ringAlpha <= 0f) return@Canvas
            val w = 3.dp.toPx()
            drawArc(c.accent.copy(alpha = ringAlpha), startAngle = spin - 90f, sweepAngle = sweep, useCenter = false,
                topLeft = Offset(w / 2, w / 2), size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
                style = Stroke(width = w, cap = StrokeCap.Round))
        }
        if (badge > 0.01f) Box(Modifier.align(Alignment.BottomEnd).graphicsLayer { val k = 0.6f + 0.4f * badge; scaleX = k; scaleY = k; alpha = badge.coerceIn(0f, 1f) }
            .size(28.dp).clip(CircleShape).background(c.bgSecondary).padding(2.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
            Icon(ImageVector.vectorResource(R.drawable.ic_sf_checkmark), null, tint = c.onAccent, modifier = Modifier.size(14.dp))
        }
    }
}
