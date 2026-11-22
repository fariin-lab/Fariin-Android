package com.fariin.app.ui.settings

import androidx.compose.ui.graphics.vector.ImageVector
import com.fariin.app.ui.components.cachedVector
import com.fariin.app.R
import com.fariin.app.data.NewBadgeSpots
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.device.DeviceRegistry
import com.fariin.app.push.Push
import com.fariin.app.ui.theme.Fariin
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/*
 * Settings pages after Kulan-iOS SettingsExtras.swift (Devices, Notifications), ChatsSettingsView,
 * StorageDataPages, StorySettingsView and SecurityNotificationsView. On-device choices keep the iOS
 * key names; the ones iOS keeps on the account are written to the same fields.
 */

internal const val PAGE_DEVICES = "devices"
internal const val PAGE_NOTIFS = "notifs"
internal const val PAGE_CHATS = "chats"
internal const val PAGE_QUICK = "chats:quick"
internal const val PAGE_STORAGE = "storage"
internal const val PAGE_LESS_DATA = "storage:lessdata"
internal const val PAGE_AUTODL = "storage:autodl:"
internal const val PAGE_MANAGE_STORAGE = "storage:manage"
internal const val PAGE_SENT_QUALITY = "storage:quality"
internal const val PAGE_STORIES = "storiesSettings"
internal const val PAGE_SEC_NOTIFS = "account:secnotifs"

@Composable
internal fun moreRoute(page: String, push: (String) -> Unit, pop: () -> Unit): Boolean {
    when {
        page == PAGE_DEVICES -> {
            // The checkup is a sheet over Devices (iOS), not a page.
            var checkup by remember { mutableStateOf(false) }
            DevicesPage(pop, onCheckup = { checkup = true })
            if (checkup) SecurityCheckupSheet(onClose = { checkup = false })
        }
        page == PAGE_NOTIFS -> NotificationsPage(pop)
        page == PAGE_CHATS -> ChatsSettingsPage(push, pop)
        page == PAGE_QUICK -> QuickReactionPage(pop)
        page == PAGE_CHAT_BACKUP -> ChatBackupPage(pop)
        page == PAGE_TRANSFER -> TransferChatsPage(pop)
        page == PAGE_STORAGE -> StorageDataPage(push, pop)
        page == PAGE_LESS_DATA -> ChoicePage("Use Less Data", "fariin_calls", "calls.lessData", "never", SettingsRules.lessData,
            "Lowers call quality to save data. Applies from your next call.", pop)
        page.startsWith(PAGE_AUTODL) -> page.removePrefix(PAGE_AUTODL).let { k ->
            val label = SettingsRules.autoKinds.first { it.first == k }.second
            // iOS AutoDownloadPage footer, one line per kind.
            val footer = if (k == "photos") "When off, photos show a blurred preview until you tap them."
                else "When allowed, ${label.lowercase()} download in the background as chats open, so they play instantly."
            ChoicePage(label, MEDIA_PREFS, "autodl.$k", SettingsRules.autoDefault(k), SettingsRules.autoPolicies, footer, pop)
        }
        page == PAGE_MANAGE_STORAGE -> ManageStoragePage(pop)
        // iOS words: the HD button on each send starts from this choice.
        page == PAGE_SENT_QUALITY -> ChoicePage("Sent Media Quality", MEDIA_PREFS, com.fariin.app.media.SentQuality.KEY, "standard", SettingsRules.sentQuality,
            "Standard sends photos up to 1280 px so they go fast. High starts the HD button switched on: photos up to 2560 px and 1080p video, using more data. You can still change it on each send.", pop)
        page == PAGE_STORIES -> StoriesSettingsPage(push, pop)
        page == PAGE_STORY_EVERYONE -> StoryEveryonePage(pop)
        page == PAGE_STORY_MYCHATS -> StoryMyChatsPage(pop)
        page.startsWith(PAGE_STORY_CUSTOM) -> StoryCustomPage(page.removePrefix(PAGE_STORY_CUSTOM), pop)
        page == PAGE_SEC_NOTIFS -> SecurityNotificationsPage(pop)
        else -> return false
    }
    return true
}

internal const val MEDIA_PREFS = "fariin_media"
internal const val CHAT_PREFS = "fariin_chats"

/** Pure words and choices, unit tested. */
object SettingsRules {
    val lessData = listOf("never" to "Never", "cellular" to "Cellular Only", "always" to "Always")
    val sentQuality = listOf("standard" to "Standard", "high" to "High")
    /** Manage storage > Keep Media, iOS keepMediaDays (0 = forever). */
    val keepDays = listOf(0 to "Forever", 30 to "30 days", 90 to "90 days")
    val autoPolicies = listOf("always" to "Wi-Fi and Cellular", "wifi" to "Wi-Fi", "never" to "Never")
    val autoKinds = listOf("photos" to "Photos", "videos" to "Videos", "audio" to "Audio", "documents" to "Documents")
    fun autoDefault(kind: String) = if (kind == "photos" || kind == "audio") "always" else "wifi"
    fun label(choices: List<Pair<String, String>>, v: String) = choices.firstOrNull { it.first == v }?.second ?: choices.first().second

    /** iOS UseLessDataPage.activeNow. */
    fun lessDataNow(v: String?, onWifi: Boolean) = when (v) { "always" -> true; "cellular" -> !onWifi; else -> false }

    /** iOS AutoDownloadPrefs: may this kind download by itself on this network? */
    fun autoDownload(policy: String, onWifi: Boolean) = when (policy) { "always" -> true; "wifi" -> onWifi; else -> false }

    val quickChoices = listOf("❤️", "👍", "👎", "😂", "😮", "😢", "🙏", "🔥", "🎉", "💯")
    const val QUICK_KEY = "chat.quickReaction"

    val autoSignOut = listOf(0, 30, 90, 180, 365)
    fun autoSignOutLabel(d: Int) = when (d) { 0 -> "Never"; 30 -> "1 month"; 90 -> "3 months"; 180 -> "6 months"; 365 -> "1 year"; else -> "$d days" }

    /** DevicesView.subtitle: "Online" inside 6 minutes, else "Last active ..." (+ the city when known). */
    fun deviceLine(thisDevice: Boolean, lastSeenMs: Long, nowMs: Long, location: String): String {
        val base = if (thisDevice || nowMs - lastSeenMs < 360_000) "Online"
            else "Last active " + com.fariin.app.data.Presence.relative(lastSeenMs.toDouble(), nowMs.toDouble())
        return if (location.isNotEmpty()) "$base · $location" else base
    }

    /** Hours left before a fresh sign-in may sign out other devices (24 h rule); 0 when it may. */
    fun freshHoursLeft(authMs: Long, nowMs: Long): Int {
        val left = authMs + 24 * 3600_000L - nowMs
        return if (left <= 0) 0 else ((left + 3599_999) / 3600_000).toInt()
    }

    /** iOS GroupInfoView.bytes: "None" for nothing, else ByteCountFormatter (decimal units). */
    fun bytes(n: Long): String = if (n == 0L) "None" else com.fariin.app.media.MediaRules.fileSizeLabel(n)
}

/** One list of choices kept in prefs, with a check on the chosen one. */
@Composable
private fun ChoicePage(title: String, prefsName: String, key: String, def: String, choices: List<Pair<String, String>>, footer: String?, pop: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE) }
    var v by remember { mutableStateOf(prefs.getString(key, def) ?: def) }
    ScrollPage(title, pop) {
        SettingsSection(footer = footer) {
            for ((raw, label) in choices) CheckRow(label, raw == v, last = raw == choices.last().first) { v = raw; prefs.edit().putString(key, raw).apply() }
        }
    }
}

// ---------------------------------------------------------------- Devices

/** A sign-out refusal said in iOS DeviceSignOutError's words, shown as it is. */
private class DeviceSignOutProblem(message: String) : Exception(message)

/** iOS DeviceSignOutError.tooNew: why a new sign-in cannot sign others out, with the hours left when known. */
private fun tooNewLine(hours: Int?): String {
    val base = "For your security, a device logged in less than 24 hours ago can't log out other devices. Use a device you have been logged in on longer"
    if (hours == null || hours <= 0) return "$base, or try again later."
    return "$base, or try again in ${if (hours == 1) "1 hour" else "$hours hours"}."
}

private data class DeviceRow(val id: String, val model: String, val os: String, val app: String, val lastSeen: Long, val created: Long?, val location: String, val fcm: String?, val voip: String?)

@Composable
private fun DevicesPage(pop: () -> Unit, onCheckup: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val me = com.fariin.app.Fb.auth.currentUser?.uid
    val thisId = remember { DeviceRegistry.thisDeviceId(ctx) }
    var rows by remember { mutableStateOf<List<DeviceRow>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var hoursLeft by remember { mutableStateOf(0) }
    var auto by remember { mutableStateOf<Int?>(null) }
    var pending by remember { mutableStateOf<DeviceRow?>(null) }
    var info by remember { mutableStateOf<Pair<DeviceRow, Boolean>?>(null) }
    var all by remember { mutableStateOf(false) }
    val db = com.fariin.app.Fb.db
    if (me != null) DisposableEffect(me) {
        val reg = db.collection("users").document(me).collection("devices").addSnapshotListener { s, e ->
            if (s == null) { if (e != null) error = "Your devices could not be loaded. Check your connection and try again."; return@addSnapshotListener }
            rows = s.documents.map { d ->
                fun ms(k: String) = (d.get(k) as? Timestamp)?.toDate()?.time ?: (d.get(k) as? Number)?.toLong()
                DeviceRow(d.id, d.getString("model") ?: "Phone", d.getString("os") ?: "", d.getString("appVersion") ?: "",
                    ms("lastSeenAt") ?: 0L, ms("createdAt"), d.getString("location") ?: "", d.getString("fcmToken"), d.getString("voipToken"))
            }.sortedByDescending { it.lastSeen }
        }
        onDispose { reg.remove() }
    }
    LaunchedEffect(me) {
        val auth = try { com.fariin.app.Fb.auth.currentUser?.getIdToken(false)?.await()?.authTimestamp } catch (_: Exception) { null }
        if (auth != null) hoursLeft = SettingsRules.freshHoursLeft(auth * 1000, System.currentTimeMillis())
        auto = try { (db.collection("users").document(me!!).get().await().get("deviceAutoSignOutDays") as? Number)?.toInt() ?: 180 } catch (_: Exception) { 180 }
    }
    /** iOS DeviceRegistry.signOut(deviceId:): its tokens off both homes and its record deleted, in one batch. */
    suspend fun signOut(d: DeviceRow) {
        val user = db.collection("users").document(me!!)
        val batch = db.batch()
        val removals = HashMap<String, Any>()
        d.fcm?.let { removals["fcmTokens"] = FieldValue.arrayRemove(it) }
        d.voip?.let { removals["voipTokens"] = FieldValue.arrayRemove(it) }
        if (removals.isNotEmpty()) {
            batch.update(user, removals)
            val pushRemovals = HashMap(removals)
            d.fcm?.let { pushRemovals[Push.ANDROID_FIELD] = FieldValue.arrayRemove(it) }
            d.voip?.let { pushRemovals["groupRingTokens"] = FieldValue.arrayRemove(it); pushRemovals["voipCancelTokens"] = FieldValue.arrayRemove(it) }
            batch.set(user.collection("push").document("tokens"), pushRemovals, SetOptions.merge())
        }
        batch.delete(user.collection("devices").document(d.id))
        // Its one-tap key goes FIRST, as iOS DeviceRegistry.signOut: three tries, and if the server
        // never confirms, nothing is removed. Else the row is gone but that phone signs straight back in.
        var revoked = false
        for (attempt in 0 until 3) {
            if (revoked) break
            if (attempt > 0) kotlinx.coroutines.delay(attempt * 1000L)
            revoked = com.fariin.app.data.DeviceSessionKeys.revoke(listOf(d.id))
        }
        if (!revoked) throw DeviceSignOutProblem("That device could not be logged out right now. Check your connection and try again.")
        try { batch.commit().await() }
        catch (e: com.google.firebase.firestore.FirebaseFirestoreException) {
            // The rules refuse a sign-in under 24 hours old (iOS tooNew(nil)).
            if (e.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) throw DeviceSignOutProblem(tooNewLine(null))
            throw e
        }
    }
    fun run(work: suspend () -> Unit) {
        error = null
        scope.launch {
            if (hoursLeft > 0) { error = tooNewLine(hoursLeft); return@launch }
            try { work() }
            catch (e: DeviceSignOutProblem) { error = e.message }
            catch (e: Exception) { error = "Couldn't sign out that device. ${e.localizedMessage ?: ""}".trim() }
        }
    }
    val now = System.currentTimeMillis()
    val mine = rows?.firstOrNull { it.id == thisId }
    val others = rows?.filter { it.id != thisId }.orEmpty()
    ScrollPage("Devices", pop) {
        // The shield, the line and the Security Checkup button (iOS DevicesHeader).
        DevicesCheckupHeader(onCheckup)
        ErrorNote(error)
        SettingsSection(header = "This device") {
            if (mine == null && rows == null) com.fariin.app.ui.components.LoadingLine("Loading…", Modifier.padding(16.dp), fontSize = 17.sp)
            else if (mine == null) Text("This device is not registered yet. Check your connection.",
                color = Fariin.colors.secondary, modifier = Modifier.padding(16.dp))
            else Box(Modifier.clickable { info = mine to true }) { DeviceLine(mine, true, now, true) }
        }
        SettingsSection(header = "Other devices", footer = if (hoursLeft > 0 && others.isNotEmpty()) "You can sign out other devices in about $hoursLeft h."
            else "Sign out any device you don't recognise.") {
            // Text only (owner 2026-11-09: the blue shield went).
            if (rows != null && others.isEmpty()) Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("No other devices", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Fariin.colors.label)
                    Text("Only this device is signed in to your account.", fontSize = 15.sp, color = Fariin.colors.secondary)
                }
            }
            for ((i, d) in others.withIndex()) Box(Modifier.clickable { info = d to false }) { DeviceLine(d, false, now, i == others.lastIndex) }
        }
        // iOS shows it as soon as there is any other device, one included.
        if (others.isNotEmpty()) SettingsSection { SettingsRow("Sign out all other devices", trailing = Trailing.NONE, titleColor = Fariin.colors.red, last = true) { all = true } }
        SettingsSection(header = "Automatic sign-out", footer = "A device not opened for this long signs itself out. The one you are using stays signed in.") {
            var menu by remember { mutableStateOf(false) }
            Box {
                Row(Modifier.fillMaxWidth().clickable { menu = true }.heightIn(min = 56.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Sign out after", fontSize = 17.sp, color = Fariin.colors.label, modifier = Modifier.weight(1f))
                    Text(auto?.let { SettingsRules.autoSignOutLabel(it) } ?: "", fontSize = 17.sp, color = Fariin.colors.label)
                    androidx.compose.material3.Icon(ImageVector.cachedVector(R.drawable.ic_sf_chevron_up_down), null,
                        tint = Fariin.colors.label, modifier = Modifier.padding(start = 4.dp).size(20.dp))
                }
                // Anchored at the right under the value (a modifier on the menu itself does not move it).
                Box(Modifier.align(Alignment.BottomEnd).padding(end = 16.dp)) {
                com.fariin.app.ui.components.GlassDropdown(menu, onDismissRequest = { menu = false }, containerColor = Fariin.colors.card) {
                    for (d in SettingsRules.autoSignOut) androidx.compose.material3.DropdownMenuItem(
                        text = { Text(SettingsRules.autoSignOutLabel(d), color = Fariin.colors.label) },
                        trailingIcon = { if (auto == d) androidx.compose.material3.Icon(ImageVector.cachedVector(R.drawable.ic_sf_checkmark), null, tint = Fariin.colors.accent) },
                        onClick = {
                            menu = false; val was = auto; auto = d
                            scope.launch {
                                val ok = runCatching { db.collection("users").document(me!!).set(mapOf("deviceAutoSignOutDays" to d), SetOptions.merge()).await() }.isSuccess
                                if (!ok) { auto = was; com.fariin.app.ui.components.Snack.show("Couldn't save. Check your connection.") }
                            }
                        })
                }
                }
            }
        }
    }
    // A device tapped (iOS DeviceDetailSheet): its details; another device's sheet signs it out.
    info?.let { (d, thisOne) ->
        DeviceInfoSheet(d, thisOne, now, onDismiss = { info = null }, onSignOut = if (thisOne) null else ({ pending = d }))
    }
    pending?.let { d ->
        YesNo("Sign out this device?", "It will stop receiving messages and calls, and will be signed out the next time it is opened.", "Sign out",
            onYes = { pending = null; run { signOut(d) } }, onNo = { pending = null })
    }
    if (all) YesNo("Sign out all other devices?", "This device stays signed in.", "Sign out all",
        onYes = { all = false; run { others.forEach { signOut(it) }; com.fariin.app.data.DeviceSessionKeys.revokeAllOthers(ctx) } }, onNo = { all = false })
}

/**
 * One device's details (iOS DeviceDetailSheet, owner 2026-11-10: tapping my own device did nothing):
 * the phone in its blue circle, the model, its status, then Model, System, Fariin, Location and
 * Signed in. Another device's sheet ends with Sign Out, which closes the sheet and asks first.
 */
@Composable
private fun DeviceInfoSheet(d: DeviceRow, thisDevice: Boolean, now: Long, onDismiss: () -> Unit, onSignOut: (() -> Unit)?) {
    val c = Fariin.colors
    val then = remember { booleanArrayOf(false) }
    val motion = com.fariin.app.ui.components.rememberSheetMotion {
        onDismiss()
        if (then[0]) onSignOut?.invoke()
    }
    sheetRestAt(motion, 560.dp)
    com.fariin.app.ui.components.FariinSheetDialog(motion, fill = c.bgSecondary) { _ ->
        AccountSheetFrame(motion, closeEnabled = true, onClose = { motion.close() }) {
            Box(Modifier.size(72.dp).clip(CircleShape).background(c.blue), contentAlignment = Alignment.Center) {
                androidx.compose.material3.Icon(ImageVector.cachedVector(R.drawable.ic_sf_iphone), null,
                    tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(38.dp))
            }
            Text(d.model, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = c.label,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
            Text(SettingsRules.deviceLine(thisDevice, d.lastSeen, now, ""), fontSize = 17.sp, color = c.secondary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            val rows = listOfNotNull(
                "Model" to d.model,
                d.os.takeIf { it.isNotEmpty() }?.let { "System" to it },
                d.app.takeIf { it.isNotEmpty() }?.let { "Fariin" to it },
                d.location.takeIf { it.isNotEmpty() }?.let { "Location" to it },
                d.created?.takeIf { it > 0 }?.let { "Signed in" to java.text.SimpleDateFormat("MMM d, yyyy 'at' h:mm a", java.util.Locale.US).format(java.util.Date(it)) },
            )
            Column(Modifier.fillMaxWidth().padding(top = 24.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(26.dp)).background(c.card)) {
                rows.forEachIndexed { i, (k, v) ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(k, fontSize = 17.sp, color = c.label)
                        Spacer(Modifier.width(16.dp))
                        Text(v, fontSize = 17.sp, color = c.secondary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.weight(1f))
                    }
                    if (i < rows.lastIndex) androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = c.separator, modifier = Modifier.padding(start = 16.dp))
                }
            }
            if (onSignOut != null) SheetPill("Sign Out This Device", c.red, modifier = Modifier.padding(top = 20.dp)) {
                then[0] = true; motion.close()
            }
        }
    }
}

@Composable
private fun DeviceLine(d: DeviceRow, thisDevice: Boolean, now: Long, last: Boolean) {
    val c = Fariin.colors
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        // iOS: the device in a blue circle.
        Box(Modifier.size(46.dp).clip(CircleShape).background(c.blue), contentAlignment = Alignment.Center) {
            androidx.compose.material3.Icon(ImageVector.cachedVector(R.drawable.ic_sf_iphone), null,
                tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(d.model, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label)
            Text(listOf(d.os, d.app.takeIf { it.isNotEmpty() }?.let { "Fariin $it" }).filterNotNull().filter { it.isNotEmpty() }.joinToString(" · "),
                fontSize = 15.sp, color = c.secondary)
            Text(SettingsRules.deviceLine(thisDevice, d.lastSeen, now, d.location), fontSize = 15.sp, color = c.secondary)
        }
        }
        if (!last) androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = c.separator)
    }
}

// ---------------------------------------------------------------- Notifications

@Composable
private fun NotificationsPage(pop: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val p = remember { ctx.getSharedPreferences("fariin_push", Context.MODE_PRIVATE) }
    fun get(k: String) = p.getBoolean(k, true)
    var push by remember { mutableStateOf(get(Push.PUSH_ON)) }
    var preview by remember { mutableStateOf(get("notif.preview")) }
    var inSound by remember { mutableStateOf(get("notif.inAppSound")) }
    var inVibrate by remember { mutableStateOf(get("notif.inAppVibrate")) }
    var inPreview by remember { mutableStateOf(get("notif.inAppPreview")) }
    var sent by remember { mutableStateOf(get("chat.sentSound")) }
    var askOn by remember { mutableStateOf(false) }
    // Read again on every return to the page (back from the system settings) and after the ask,
    // or "Notifications are off" stayed up after the person turned them on.
    var permitted by remember { mutableStateOf(Push.permitted(ctx)) }
    if (askOn) com.fariin.app.push.NotificationsSheet { askOn = false; permitted = Push.permitted(ctx) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) permitted = Push.permitted(ctx)
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    var reset by remember { mutableStateOf(false) }
    val me = com.fariin.app.Fb.auth.currentUser?.uid
    // Sign-out clears the local switch, but the server keeps sending with users.notifPreview. With no
    // choice on this phone, show the account's (iOS adoptServerPrefs); a choice made here always wins.
    androidx.compose.runtime.LaunchedEffect(me) {
        if (me == null || p.contains("notif.preview")) return@LaunchedEffect
        val v = runCatching { com.fariin.app.Fb.db.collection("users").document(me).get().await().getBoolean("notifPreview") }.getOrNull()
        if (v != null && !p.contains("notif.preview")) { preview = v; p.edit().putBoolean("notif.preview", v).apply() }
    }
    var resetting by remember { mutableStateOf(false) }
    suspend fun saveAccount(field: String, v: Boolean): Boolean = me != null &&
        runCatching { com.fariin.app.Fb.db.collection("users").document(me).set(mapOf(field to v), SetOptions.merge()).await() }.isSuccess
    /** Saves the switch on the account; if that fails the switch goes back and the user is told. */
    fun account(field: String, v: Boolean, undo: () -> Unit) {
        me ?: return
        scope.launch {
            if (!saveAccount(field, v)) { undo(); com.fariin.app.ui.components.Snack.show("Couldn't save. Check your connection.") }
        }
    }
    fun openSystem(channel: String? = null) {
        val i = if (channel != null) Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_CHANNEL_ID, channel)
            else Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        runCatching { ctx.startActivity(i.putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    ScrollPage("Notifications", pop) {
        if (!permitted) SettingsSection(header = "Notifications are off", footer = "Turn them on to be told about new messages and calls.") {
            SettingsRow("Turn On Notifications", last = true) { if (android.os.Build.VERSION.SDK_INT >= 33) askOn = true else openSystem() }
        }
        SettingsSection(footer = "Everything below depends on this. With it off, Fariin does not notify you at all, on the lock screen or inside the app.") {
            SwitchRow("Show Notifications", push, last = true, badge = NewBadgeSpots.NOTIFICATIONS_SHOW) { on ->
                push = on; p.edit().putBoolean(Push.PUSH_ON, on).apply()
                if (on) Push.register(ctx) else scope.launch { Push.unregister(ctx) }
                account("notifPush", on) {
                    push = !on; p.edit().putBoolean(Push.PUSH_ON, !on).apply()
                    if (!on) Push.register(ctx) else scope.launch { Push.unregister(ctx) }
                }
            }
        }
        SettingsSection(header = "Message Notifications", footer = "Preview shows the sender and the message. Sound and vibration are set by Android for each kind of notification.") {
            SwitchRow("Message Preview", preview, enabled = push, badge = NewBadgeSpots.NOTIFICATIONS_PREVIEW) { on ->
                preview = on; p.edit().putBoolean("notif.preview", on).apply()
                account("notifPreview", on) { preview = !on; p.edit().putBoolean("notif.preview", !on).apply() }
            }
            SettingsRow("Message Sound", trailing = Trailing.OUT_LINK, badge = NewBadgeSpots.NOTIFICATIONS_SOUND) { openSystem(com.fariin.app.push.NotifChannel.MESSAGES.id) }
            SettingsRow("Call Ringtone", trailing = Trailing.OUT_LINK, last = true) { openSystem(com.fariin.app.push.NotifChannel.CALLS.id) }
        }
        SettingsSection(header = "In-App Notifications", footer = "Alerts while Fariin is open. Preview shows the sender and message in the banner.") {
            SwitchRow("In-App Sounds", inSound, enabled = push, badge = NewBadgeSpots.NOTIFICATIONS_IN_APP_SOUNDS) { inSound = it; p.edit().putBoolean("notif.inAppSound", it).apply() }
            SwitchRow("In-App Vibrate", inVibrate, enabled = push, badge = NewBadgeSpots.NOTIFICATIONS_IN_APP_VIBRATE) { inVibrate = it; p.edit().putBoolean("notif.inAppVibrate", it).apply() }
            SwitchRow("In-App Preview", inPreview, enabled = push, last = true, badge = NewBadgeSpots.NOTIFICATIONS_IN_APP_PREVIEW) { inPreview = it; p.edit().putBoolean("notif.inAppPreview", it).apply() }
        }
        SettingsSection(footer = "A short sound when your message reaches Fariin.") {
            SwitchRow("Sound When Sent", sent, last = true, badge = NewBadgeSpots.NOTIFICATIONS_SENT_SOUND) { sent = it; p.edit().putBoolean("chat.sentSound", it).apply() }
        }
        // Only when there is something to undo (iOS hasAnythingToReset): grey and still otherwise.
        val now = System.currentTimeMillis().toDouble()
        val canReset = !push || !preview || !inSound || !inVibrate || !inPreview || !sent
            || com.fariin.app.push.MessageTones.hasAnyCustom(ctx)
            || (me != null && com.fariin.app.data.CryptoHub.listedAll().any { it.isMuted(me, now) })
        SettingsSection(footer = "Undo all custom notification settings, including per-chat sounds and muted chats.") {
            SettingsRow("Reset All Notifications", trailing = Trailing.NONE,
                titleColor = if (canReset && !resetting) Fariin.colors.red else Fariin.colors.tertiary, last = true,
                badge = NewBadgeSpots.NOTIFICATIONS_RESET_ALL,
                accessory = { if (resetting) com.fariin.app.ui.components.FariinSpinner(size = 20.dp) }) { if (!resetting && canReset) reset = true }
        }
    }
    if (reset) YesNo("Reset all notifications?", "Every muted chat goes back to normal notifications, and custom chat sounds return to the default.", "Reset", onYes = {
        reset = false
        p.edit().putBoolean(Push.PUSH_ON, true).putBoolean("notif.preview", true).putBoolean("notif.inAppSound", true)
            .putBoolean("notif.inAppVibrate", true).putBoolean("notif.inAppPreview", true).putBoolean("chat.sentSound", true).apply()
        push = true; preview = true; inSound = true; inVibrate = true; inPreview = true; sent = true
        com.fariin.app.push.MessageTones.clearAll(ctx)   // per-chat sounds (iOS SoundStore.clearAllCustom)
        Push.register(ctx)
        if (me != null) {
            resetting = true
            scope.launch {
                // The account's message tone back to Note too, as iOS Reset writes it (the server picks
                // the push sound from users.notifSound).
                val toneSaved = runCatching { com.fariin.app.Fb.db.collection("users").document(me)
                    .set(mapOf("notifSound" to com.fariin.app.push.MessageTones.NOTE), SetOptions.merge()).await() }.isSuccess
                val savedAccount = saveAccount("notifPush", true) and saveAccount("notifPreview", true) and toneSaved
                var unmuteFailed = 0
                // isMuted + setMute, as iOS: a channel I only follow keeps its mute on my follow
                // entry, not in mutedBy, so the old mutedBy-only loop left those muted.
                val at = System.currentTimeMillis().toDouble()
                for (c in com.fariin.app.data.CryptoHub.listedAll()) if (c.isMuted(me, at)) {
                    val ok = runCatching { com.fariin.app.ui.chats.ChatListActions.setMute(me, c, 0.0) }.getOrDefault(false)
                    if (!ok) unmuteFailed++
                }
                resetting = false
                com.fariin.app.ui.components.Snack.show(when {
                    unmuteFailed > 0 -> "Some chats couldn't be unmuted. Check your connection and try again."
                    !savedAccount -> "Couldn't save. Check your connection."
                    else -> "Notifications reset"
                })
            }
        }
    }, onNo = { reset = false })
}

// ---------------------------------------------------------------- Chats

@Composable
private fun ChatsSettingsPage(push: (String) -> Unit, pop: () -> Unit) {
    val ctx = LocalContext.current
    val p = remember { ctx.getSharedPreferences(CHAT_PREFS, Context.MODE_PRIVATE) }
    var links by remember { mutableStateOf(p.getBoolean("linkPreviewsEnabled", true)) }
    var savePhotos by remember { mutableStateOf(p.getBoolean(com.fariin.app.media.AutoSaveMedia.KEY, false)) }
    var archive by remember { mutableStateOf(p.getBoolean("unknownChats.autoArchive", false)) }
    var confirmClear by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    val clearScope = rememberCoroutineScope()
    // iOS ChatsSettingsView: the key itself when this phone knows it, else On / Off.
    var keySet by remember { mutableStateOf(ChatKey.cachedSet()) }
    LaunchedEffect(Unit) { ChatKey.status()?.let { keySet = it } }
    val keyValue = ChatKey.mine() ?: if (keySet == true) "On" else if (keySet == false) "Off" else null
    // Same rows as iOS (owner 2026-11-08): Message Text Size, Quick Reaction and Night Mode are gone from
    // here; the last two are in Appearance, as on iOS, and iOS has no text size of its own (the phone's).
    ScrollPage("Chats", pop) {
        // Hidden with the Chat Key itself (admin panel chatKeyVisible, iOS ChatsSettingsView).
        if (com.fariin.app.data.MsgPolicy.chatKeyVisible) SettingsSection(footer = "A private number you can share. Anyone who knows it can message and call you directly.") {
            SettingsRow("Chat Key", value = keyValue, last = true, badge = NewBadgeSpots.CHATS_CHAT_KEY) { push(PAGE_CHAT_KEY) }
        }
        // Fariin Plus chatFolders: only while Plus is visible and the feature is not off.
        if (com.fariin.app.data.ChatFoldersStore.settingsShown) SettingsSection(footer = "Sort your chats into folders and switch between them at the top of your chat list.") {
            SettingsRow("Chat Folders", value = com.fariin.app.data.ChatFoldersStore.customFolders.size.takeIf { it > 0 }?.toString(),
                accessory = { PlusBadgeImage(18.dp) }, last = true) { push(PAGE_CHAT_FOLDERS) }
        }
        if (com.fariin.app.backup.ChatTransfer.ENABLED) SettingsSection(footer = "Move your chats to a new phone with a QR code.") {
            SettingsRow("Transfer Chats", last = true) { push(PAGE_TRANSFER) }
        }
        // iOS: saves received photos and videos, from the moment it is turned on (never the history).
        SettingsSection(footer = "Automatically save received photos and videos to your gallery.") {
            SwitchRow("Save to Photos", savePhotos, last = true, badge = NewBadgeSpots.CHATS_SAVE_TO_PHOTOS) {
                savePhotos = it; p.edit().putBoolean(com.fariin.app.media.AutoSaveMedia.KEY, it).apply()
                if (it) com.fariin.app.media.AutoSaveMedia.noteTurnedOn(ctx)
            }
        }
        SettingsSection(footer = "Show a preview card for links you type or paste. Your phone contacts the site to build it, before the message is sent.") {
            SwitchRow("Link Previews", links, last = true, badge = NewBadgeSpots.CHATS_LINK_PREVIEWS) { links = it; p.edit().putBoolean("linkPreviewsEnabled", it).apply() }
        }
        // Swipe Actions (iOS ChatsSettingsView): off by default; on shows the two picks.
        SwipeActionsSection()
        SettingsSection(header = "New Chats from Your Chat Key", footer = "When someone uses your Chat Key to message you, the chat goes to Archived, muted.") {
            SwitchRow("Automatically Archive", archive, last = true, badge = NewBadgeSpots.CHATS_AUTO_ARCHIVE) { archive = it; com.fariin.app.data.UnknownChatArchiver.setOn(p, it) }
        }
        // iOS ChatsSettingsView: the last row, red, with what it does written under it.
        // iOS: greyed and dead while it runs or when there is no chat to clear.
        // listedAll() is a plain map, not state: look again each second until the list has loaded,
        // or the row stays grey when the page was drawn first.
        var hasChats by remember { mutableStateOf(com.fariin.app.data.CryptoHub.listedAll().isNotEmpty()) }
        LaunchedEffect(Unit) {
            while (!hasChats) { kotlinx.coroutines.delay(1000); hasChats = com.fariin.app.data.CryptoHub.listedAll().isNotEmpty() }
        }
        val canClear = !clearing && hasChats
        SettingsSection(footer = "Deletes every message, photo, video and call from all your devices. It cannot be undone.") {
            SettingsRow("Clear Chat History", trailing = Trailing.NONE,
                titleColor = if (canClear) Fariin.colors.red else Fariin.colors.red.copy(alpha = 0.4f), last = true,
                badge = NewBadgeSpots.CHATS_CLEAR_HISTORY) { if (canClear) confirmClear = true }
        }
    }
    if (confirmClear) YesNo("Clear Chat History?",
        "Are you sure you want to permanently delete all chat history, including messages, photos, videos, documents, voice messages, and call history, from all of your devices? This action cannot be undone.",
        "Delete Everything", onYes = {
            confirmClear = false
            val me = com.fariin.app.Fb.auth.currentUser?.uid ?: return@YesNo
            clearing = true
            clearScope.launch {
                // Every chat in the list, as iOS clearEverything (ChatService.deleteForMe each): my own
                // clearedAt mark, written on the server so every device of mine follows; the other
                // person keeps theirs. A channel I only follow is left as it is (no clear mark for a follower). The Fariin chat
                // is not in the list, so iOS leaves it as it is ([ClearHistoryRules]).
                val ok = com.fariin.app.data.CryptoHub.listedAll().map { c ->
                    when (com.fariin.app.data.ClearHistoryRules.step(me, c)) {
                        com.fariin.app.data.ClearHistoryRules.Step.CLEAR_MARK -> com.fariin.app.ui.chats.ChatListActions.deleteForMe(me, c)
                        com.fariin.app.data.ClearHistoryRules.Step.SKIP -> true
                        com.fariin.app.data.ClearHistoryRules.Step.CLEAR_OFFICIAL -> { com.fariin.app.data.OfficialChannel.clearHistory(); true }
                    }
                }.all { it }
                clearing = false
                com.fariin.app.ui.components.Snack.show(if (ok) "Chat history cleared" else "Couldn't clear every chat. Check your connection and try again.")
            }
        }, onNo = { confirmClear = false })
}

@Composable
private fun QuickReactionPage(pop: () -> Unit) {
    val ctx = LocalContext.current
    val p = remember { ctx.getSharedPreferences(CHAT_PREFS, Context.MODE_PRIVATE) }
    var v by remember { mutableStateOf(p.getString(SettingsRules.QUICK_KEY, "❤️") ?: "❤️") }
    val c = Fariin.colors
    ScrollPage("Quick Reaction", pop) {
        // iOS QuickReactionView: a sample message wearing the chosen reaction, then the choices.
        Text("Double tap on a message to react", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.secondary,
            modifier = Modifier.padding(start = 28.dp, top = 12.dp, bottom = 8.dp))
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 18.dp)) {
            Text("I hope you're enjoying your day as much as I am.", fontSize = 17.sp, color = c.label,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(c.card)
                    .padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 16.dp))
            Box(Modifier.align(Alignment.BottomEnd).offset(x = 6.dp, y = 14.dp).size(36.dp).clip(CircleShape).background(c.card),
                contentAlignment = Alignment.Center) { com.fariin.app.ui.thread.Emoji3D(v, 20.sp, 24.dp) }
        }
        Text("Choose your quick reaction", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.secondary,
            modifier = Modifier.padding(start = 28.dp, top = 8.dp, bottom = 10.dp))
        for (row in SettingsRules.quickChoices.chunked(5)) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.SpaceEvenly) {
            for (e in row) {
                val on = e == v
                Box(
                    Modifier.size(60.dp).clip(CircleShape).background(if (on) c.label.copy(alpha = 0.16f) else c.card)
                        .then(if (on) Modifier.border(2.5.dp, c.label, CircleShape) else Modifier)
                        .clickable { v = e; p.edit().putString(SettingsRules.QUICK_KEY, e).apply() },
                    contentAlignment = Alignment.Center,
                ) { com.fariin.app.ui.thread.Emoji3D(e, 30.sp, 36.dp) }
            }
        }
        Text("Double tap any message to send this reaction.", fontSize = 14.sp, color = c.secondary,
            modifier = Modifier.padding(start = 28.dp, top = 12.dp))
    }
}

// ---------------------------------------------------------------- Storage and Data

/** The auto-download rows' "New" spots, by media kind. */
private val AUTO_DOWNLOAD_SPOTS = mapOf(
    "photos" to NewBadgeSpots.STORAGE_AUTO_DOWNLOAD_PHOTOS, "videos" to NewBadgeSpots.STORAGE_AUTO_DOWNLOAD_VIDEOS,
    "audio" to NewBadgeSpots.STORAGE_AUTO_DOWNLOAD_AUDIO, "documents" to NewBadgeSpots.STORAGE_AUTO_DOWNLOAD_DOCUMENTS,
)

@Composable
private fun StorageDataPage(push: (String) -> Unit, pop: () -> Unit) {
    val ctx = LocalContext.current
    val media = remember { ctx.getSharedPreferences(MEDIA_PREFS, Context.MODE_PRIVATE) }
    val calls = remember { ctx.getSharedPreferences("fariin_calls", Context.MODE_PRIVATE) }
    var tick by remember { mutableStateOf(0) }
    val isDefault = SettingsRules.autoKinds.all { (k, _) -> (media.getString("autodl.$k", null) ?: SettingsRules.autoDefault(k)) == SettingsRules.autoDefault(k) }
    // iOS StorageDataView: Manage storage, Media auto-download (+ reset), Sent Media, Calls.
    ScrollPage("Storage and Data", pop) {
        SettingsSection(header = "Storage") {
            SettingsRow("Manage storage", last = true, badge = NewBadgeSpots.STORAGE_MANAGE) { push(PAGE_MANAGE_STORAGE) }
        }
        SettingsSection(header = "Media auto-download", footer = "When media downloads by itself. Anything else downloads when you open it.") {
            for ((k, label) in SettingsRules.autoKinds) {
                val v = media.getString("autodl.$k", SettingsRules.autoDefault(k)) ?: ""
                SettingsRow(label, value = SettingsRules.label(SettingsRules.autoPolicies, v) + if (tick < 0) "" else "",
                    badge = AUTO_DOWNLOAD_SPOTS[k]) { push(PAGE_AUTODL + k) }
            }
            SettingsRow("Reset Auto-Download Settings", trailing = Trailing.NONE, titleColor = if (isDefault) Fariin.colors.secondary else Fariin.colors.label, last = true) {
                if (!isDefault) { media.edit().apply { SettingsRules.autoKinds.forEach { (k, _) -> remove("autodl.$k") } }.apply(); tick++ }
            }
        }
        SettingsSection(header = "Sent Media", footer = "Choose the quality you send photos and videos.") {
            SettingsRow("Sent Media Quality", value = SettingsRules.label(SettingsRules.sentQuality, media.getString(com.fariin.app.media.SentQuality.KEY, "standard") ?: "standard"),
                last = true, badge = NewBadgeSpots.STORAGE_SENT_MEDIA_QUALITY) { push(PAGE_SENT_QUALITY) }
        }
        SettingsSection(header = "Calls", footer = "Using less data may improve calls on bad networks.") {
            SettingsRow("Use Less Data", value = SettingsRules.label(SettingsRules.lessData, calls.getString("calls.lessData", "never") ?: "never"), last = true,
                badge = NewBadgeSpots.STORAGE_USE_LESS_DATA) { push(PAGE_LESS_DATA) }
        }
    }
}

/**
 * Manage storage (iOS ManageStoragePage). Android keeps every opened photo, video and voice note in
 * one folder, so it is one row; the image cache and the rest of the app's scratch files are the
 * other two. Everything here downloads again when it is opened: the server still has it.
 */
@Composable
private fun ManageStoragePage(pop: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val media = remember { ctx.getSharedPreferences(MEDIA_PREFS, Context.MODE_PRIVATE) }
    var keep by remember { mutableStateOf(media.getInt(com.fariin.app.media.StorageSweep.KEEP_KEY, 0)) }
    var sizes by remember { mutableStateOf<com.fariin.app.media.StorageSweep.Sizes?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(tick) { sizes = withContext(Dispatchers.IO) { com.fariin.app.media.StorageSweep.sizes(ctx) } }
    fun shown(n: Long?) = n?.let { if (it == 0L) "Zero KB" else SettingsRules.bytes(it) } ?: "..."
    ScrollPage("Storage", pop) {
        SettingsSection(header = "Storage by Type") {
            SettingsRow("Chat Media", value = shown(sizes?.media), trailing = Trailing.NONE)
            SettingsRow("Image Cache", value = shown(sizes?.images), trailing = Trailing.NONE)
            SettingsRow("Temporary Files", value = shown(sizes?.temp), trailing = Trailing.NONE, last = true)
        }
        SettingsSection(header = "Keep Media", footer = "Downloaded photos older than this are removed from this phone and download again when you open them. Videos and voice notes are kept.") {
            SettingsRules.keepDays.forEachIndexed { i, (d, label) ->
                CheckRow(label, keep == d, last = i == SettingsRules.keepDays.lastIndex) {
                    keep = d
                    media.edit().putInt(com.fariin.app.media.StorageSweep.KEEP_KEY, d).apply()
                    scope.launch { withContext(Dispatchers.IO) { com.fariin.app.media.StorageSweep.sweep(ctx) }; tick++ }
                }
            }
        }
        SettingsSection {
            val empty = sizes?.let { it.media + it.images + it.temp == 0L } ?: true
            SettingsRow("Clear Cache", trailing = Trailing.NONE, titleColor = if (empty) Fariin.colors.secondary else Fariin.colors.red, last = true) { if (!empty) confirm = true }
        }
    }
    if (confirm) YesNo("Clear cache?", "Photos, videos and voice messages you opened are removed from this phone. They download again when you open them, unless they are no longer on the server. Your chats stay.", "Clear", onYes = {
        confirm = false
        scope.launch {
            withContext(Dispatchers.IO) { com.fariin.app.media.StorageSweep.clear(ctx) }
            runCatching { coil3.SingletonImageLoader.get(ctx).memoryCache?.clear() }
            com.fariin.app.ui.thread.DecodedPictures.clear()
            tick++
        }
    }, onNo = { confirm = false })
}

// ---------------------------------------------------------------- Stories

/** iOS StorySettingsView: Everyone / My Chats (StoryPrivacyPages.kt), then View Receipts (StoryReceiptPrefs): users/{me}/private/storyPrefs {receipts, offAt}. */
@Composable
private fun StoriesSettingsPage(push: (String) -> Unit, pop: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val p = remember { ctx.getSharedPreferences("fariin_stories", Context.MODE_PRIVATE) }
    var on by remember { mutableStateOf(p.getBoolean("storyViewReceipts", true)) }
    val me = com.fariin.app.Fb.auth.currentUser?.uid
    val ref = me?.let { com.fariin.app.Fb.db.collection("users").document(it).collection("private").document("storyPrefs") }
    LaunchedEffect(me) { ref?.let { r -> runCatching { r.get().await().getBoolean("receipts") }.getOrNull()?.let { on = it; com.fariin.app.data.StoryReceiptPrefs.set(ctx, it) } } }
    ScrollPage("Stories", pop) {
        // iOS StorySettingsView: the audiences first, then View Receipts.
        StoryAudienceRows(push)
        SettingsSection(footer = "See and share when stories are viewed. If disabled, you won't see when others view your stories.") {
            SwitchRow("View Receipts", on, last = true, badge = NewBadgeSpots.STORIES_VIEW_RECEIPTS) { v ->
                on = v; com.fariin.app.data.StoryReceiptPrefs.set(ctx, v)
                scope.launch {
                    val ok = runCatching { ref?.set(mapOf("receipts" to v, "offAt" to if (v) null else FieldValue.serverTimestamp()))?.await() }.isSuccess
                    if (!ok) {
                        on = !v; com.fariin.app.data.StoryReceiptPrefs.set(ctx, !v)
                        com.fariin.app.ui.components.Snack.show("Couldn't save. Check your connection.")
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Security notifications

/** iOS SecurityNotificationsView: users/{me}.securityAlerts (absent = off). */
@Composable
private fun SecurityNotificationsPage(pop: () -> Unit) {
    val scope = rememberCoroutineScope()
    val me = com.fariin.app.Fb.auth.currentUser?.uid
    var on by remember { mutableStateOf<Boolean?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val ref = me?.let { com.fariin.app.Fb.db.collection("users").document(it) }
    LaunchedEffect(me) { on = runCatching { ref?.get()?.await()?.getBoolean("securityAlerts") }.getOrNull() ?: false }
    ScrollPage("Security notifications", pop) {
        ErrorNote(error)
        SettingsSection(footer = "Email me when my account signs in on a new device.") {
            SwitchRow("Sign-in alerts", on == true, enabled = on != null, last = true) { v ->
                val was = on; on = v; error = null
                scope.launch {
                    try { ref!!.set(mapOf("securityAlerts" to v), SetOptions.merge()).await() }
                    catch (e: Exception) { on = was; error = "This setting could not be saved. ${e.localizedMessage ?: ""}".trim() }
                }
            }
        }
    }
}

/** Settings > Chats: the Swipe Actions switch and, when on, Slide Up For / Slide Down For. */
@Composable
private fun SwipeActionsSection() {
    val ctx = LocalContext.current
    val me = com.fariin.app.Fb.auth.currentUser?.uid ?: ""
    val sa = com.fariin.app.ui.thread.SwipeActions
    if (!sa.allowed(me)) return
    var on by remember { mutableStateOf(sa.on(ctx, me)) }
    var up by remember { mutableStateOf(sa.up(ctx, me)) }
    var down by remember { mutableStateOf(sa.down(ctx, me)) }
    SettingsSection(footer = if (on) sa.FOOTER_ON else sa.FOOTER_OFF) {
        SwitchRow("Swipe Actions", on, last = !on) { on = it; sa.setOn(ctx, me, it) }
        if (on) {
            PickRow("Slide Up For", up, last = false) { up = it; sa.setUp(ctx, me, it) }
            PickRow("Slide Down For", down, last = true) { down = it; sa.setDown(ctx, me, it) }
        }
    }
}

@Composable
private fun PickRow(title: String, value: com.fariin.app.ui.thread.SwipeActions.Pick, last: Boolean,
                    onPick: (com.fariin.app.ui.thread.SwipeActions.Pick) -> Unit) {
    var open by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        SettingsRow(title, value = value.label, last = last) { open = true }
        com.fariin.app.ui.components.GlassDropdown(expanded = open, onDismissRequest = { open = false },
            offset = androidx.compose.ui.unit.DpOffset(200.dp, 0.dp)) {
            com.fariin.app.ui.thread.SwipeActions.Pick.entries.forEach { p ->
                androidx.compose.material3.DropdownMenuItem(text = { Text(p.label) }, onClick = { open = false; onPick(p) },
                    trailingIcon = if (p == value) ({ androidx.compose.material3.Icon(androidx.compose.ui.graphics.vector.ImageVector.cachedVector(com.fariin.app.R.drawable.ic_sf_checkmark), null) }) else null)
            }
        }
    }
}
