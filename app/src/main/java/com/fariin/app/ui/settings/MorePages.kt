package com.fariin.app.ui.settings

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
        page == PAGE_DEVICES -> DevicesPage(pop, onCheckup = { push(PAGE_CHECKUP) })
        page == PAGE_NOTIFS -> NotificationsPage(pop)
        page == PAGE_CHATS -> ChatsSettingsPage(push, pop)
        page == PAGE_QUICK -> QuickReactionPage(pop)
        page == PAGE_STORAGE -> StorageDataPage(push, pop)
        page == PAGE_LESS_DATA -> ChoicePage("Use Less Data", "fariin_calls", "calls.lessData", "never", SettingsRules.lessData,
            "Lowers call quality to save data. Applies from your next call.", pop)
        page.startsWith(PAGE_AUTODL) -> page.removePrefix(PAGE_AUTODL).let { k ->
            ChoicePage(SettingsRules.autoKinds.first { it.first == k }.second, MEDIA_PREFS, "autodl.$k", SettingsRules.autoDefault(k), SettingsRules.autoPolicies, null, pop)
        }
        page == PAGE_MANAGE_STORAGE -> ManageStoragePage(pop)
        // iOS words, less the HD button line: Android has no per-send HD switch yet.
        page == PAGE_SENT_QUALITY -> ChoicePage("Sent Media Quality", MEDIA_PREFS, com.fariin.app.media.SentQuality.KEY, "standard", SettingsRules.sentQuality,
            "Standard sends photos up to 1280 px so they go fast. High sends photos up to 2560 px, using more data.", pop)
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

    fun bytes(n: Long): String = when {
        n < 1024 -> "$n B"
        n < 1024 * 1024 -> "${n / 1024} KB"
        n < 1024L * 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", n / 1048576.0)
        else -> String.format(java.util.Locale.US, "%.2f GB", n / 1073741824.0)
    }
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

private data class DeviceRow(val id: String, val model: String, val os: String, val app: String, val lastSeen: Long, val created: Long?, val location: String, val fcm: String?, val voip: String?)

@Composable
private fun DevicesPage(pop: () -> Unit, onCheckup: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val me = FirebaseAuth.getInstance().currentUser?.uid
    val thisId = remember { DeviceRegistry.thisDeviceId(ctx) }
    var rows by remember { mutableStateOf<List<DeviceRow>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var hoursLeft by remember { mutableStateOf(0) }
    var auto by remember { mutableStateOf<Int?>(null) }
    var pending by remember { mutableStateOf<DeviceRow?>(null) }
    var all by remember { mutableStateOf(false) }
    val db = FirebaseFirestore.getInstance()
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
        val auth = try { FirebaseAuth.getInstance().currentUser?.getIdToken(false)?.await()?.authTimestamp } catch (_: Exception) { null }
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
        batch.commit().await()
    }
    fun run(work: suspend () -> Unit) {
        error = null
        scope.launch {
            if (hoursLeft > 0) { error = "You can sign out other devices in about $hoursLeft h."; return@launch }
            try { work() } catch (e: Exception) { error = "Couldn't sign out that device. ${e.localizedMessage ?: ""}".trim() }
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
            if (mine == null) Text(if (rows == null) "Loading..." else "This device is not registered yet. Check your connection.",
                color = Fariin.colors.secondary, modifier = Modifier.padding(16.dp))
            else DeviceLine(mine, true, now, true)
        }
        SettingsSection(header = "Other devices", footer = if (hoursLeft > 0 && others.isNotEmpty()) "You can sign out other devices in about $hoursLeft h."
            else "Sign out any device you don't recognise.") {
            if (rows != null && others.isEmpty()) Column(Modifier.padding(16.dp)) {
                Text("No other devices", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Fariin.colors.label)
                Text("Only this device is signed in to your account.", fontSize = 15.sp, color = Fariin.colors.secondary)
            }
            for ((i, d) in others.withIndex()) Box(Modifier.clickable { pending = d }) { DeviceLine(d, false, now, i == others.lastIndex) }
        }
        if (others.size > 1) SettingsSection { SettingsRow("Sign out all other devices", trailing = Trailing.NONE, titleColor = Fariin.colors.red, last = true) { all = true } }
        SettingsSection(header = "Automatic sign-out", footer = "A device not opened for this long signs itself out. The one you are using stays signed in.") {
            for (d in SettingsRules.autoSignOut) CheckRow(SettingsRules.autoSignOutLabel(d), auto == d, last = d == 365) {
                auto = d
                scope.launch { runCatching { db.collection("users").document(me!!).set(mapOf("deviceAutoSignOutDays" to d), SetOptions.merge()).await() } }
            }
        }
    }
    pending?.let { d ->
        YesNo("Sign out this device?", "It will stop receiving messages and calls, and will be signed out the next time it is opened.", "Sign out",
            onYes = { pending = null; run { signOut(d) } }, onNo = { pending = null })
    }
    if (all) YesNo("Sign out all other devices?", "This device stays signed in.", "Sign out all",
        onYes = { all = false; run { others.forEach { signOut(it) } } }, onNo = { all = false })
}

@Composable
private fun DeviceLine(d: DeviceRow, thisDevice: Boolean, now: Long, last: Boolean) {
    val c = Fariin.colors
    Column {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(d.model, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label)
            Text(listOf(d.os, d.app.takeIf { it.isNotEmpty() }?.let { "Fariin $it" }).filterNotNull().filter { it.isNotEmpty() }.joinToString(" · "),
                fontSize = 15.sp, color = c.secondary)
            Text(SettingsRules.deviceLine(thisDevice, d.lastSeen, now, d.location), fontSize = 15.sp, color = c.secondary)
        }
        if (!last) androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = c.separator, modifier = Modifier.padding(start = 16.dp))
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
    var reset by remember { mutableStateOf(false) }
    val me = FirebaseAuth.getInstance().currentUser?.uid
    fun account(field: String, v: Boolean) {
        me ?: return
        scope.launch { runCatching { FirebaseFirestore.getInstance().collection("users").document(me).set(mapOf(field to v), SetOptions.merge()).await() } }
    }
    fun openSystem(channel: String? = null) {
        val i = if (channel != null) Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_CHANNEL_ID, channel)
            else Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        runCatching { ctx.startActivity(i.putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    ScrollPage("Notifications", pop) {
        if (!Push.permitted(ctx)) SettingsSection(header = "Notifications are off", footer = "Allow notifications in Settings to be told about new messages.") {
            SettingsRow("Open Settings", trailing = Trailing.OUT_LINK, last = true) { openSystem() }
        }
        SettingsSection(footer = "Everything below depends on this. With it off, Fariin does not notify you at all, on the lock screen or inside the app.") {
            SwitchRow("Show Notifications", push, last = true) { on ->
                push = on; p.edit().putBoolean(Push.PUSH_ON, on).apply(); account("notifPush", on)
                if (on) Push.register(ctx) else scope.launch { Push.unregister(ctx) }
            }
        }
        SettingsSection(header = "Message Notifications", footer = "Preview shows the sender and the message. Sound and vibration are set by Android for each kind of notification.") {
            SwitchRow("Message Preview", preview, enabled = push) { on -> preview = on; p.edit().putBoolean("notif.preview", on).apply(); account("notifPreview", on) }
            SettingsRow("Message Sound", trailing = Trailing.OUT_LINK) { openSystem(com.fariin.app.push.NotifChannel.MESSAGES.id) }
            SettingsRow("Call Ringtone", trailing = Trailing.OUT_LINK, last = true) { openSystem(com.fariin.app.push.NotifChannel.CALLS.id) }
        }
        SettingsSection(header = "In-App Notifications", footer = "Alerts while Fariin is open. Preview shows the sender and message in the banner.") {
            SwitchRow("In-App Sounds", inSound, enabled = push) { inSound = it; p.edit().putBoolean("notif.inAppSound", it).apply() }
            SwitchRow("In-App Vibrate", inVibrate, enabled = push) { inVibrate = it; p.edit().putBoolean("notif.inAppVibrate", it).apply() }
            SwitchRow("In-App Preview", inPreview, enabled = push, last = true) { inPreview = it; p.edit().putBoolean("notif.inAppPreview", it).apply() }
        }
        SettingsSection(footer = "A short sound when your message reaches Fariin.") {
            SwitchRow("Sound When Sent", sent, last = true) { sent = it; p.edit().putBoolean("chat.sentSound", it).apply() }
        }
        SettingsSection(footer = "Undo all custom notification settings, including muted chats.") {
            SettingsRow("Reset All Notifications", trailing = Trailing.NONE, titleColor = Fariin.colors.red, last = true) { reset = true }
        }
    }
    if (reset) YesNo("Reset all notifications?", "Every muted chat goes back to normal notifications.", "Reset", onYes = {
        reset = false
        p.edit().putBoolean(Push.PUSH_ON, true).putBoolean("notif.preview", true).putBoolean("notif.inAppSound", true)
            .putBoolean("notif.inAppVibrate", true).putBoolean("notif.inAppPreview", true).putBoolean("chat.sentSound", true).apply()
        push = true; preview = true; inSound = true; inVibrate = true; inPreview = true; sent = true
        account("notifPush", true); account("notifPreview", true)
        Push.register(ctx)
        if (me != null) scope.launch {
            val db = FirebaseFirestore.getInstance()
            for (c in com.fariin.app.data.CryptoHub.listedAll()) if ((c.mutedBy[me] ?: 0.0) > System.currentTimeMillis()) {
                runCatching { db.collection("conversations").document(c.id).set(mapOf("mutedBy" to mapOf(me to 0.0)), SetOptions.merge()).await() }
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
    var size by remember { mutableStateOf(p.getString("chat.textSize", "medium") ?: "medium") }
    val quick = p.getString(SettingsRules.QUICK_KEY, "❤️") ?: "❤️"
    ScrollPage("Chats", pop) {
        SettingsSection(footer = "A private number you can share. Anyone who knows it can message and call you directly.") {
            SettingsRow("Chat Key", last = true) { push(PAGE_CHAT_KEY) }
        }
        SettingsSection(header = "Message Text Size") {
            val sizes = listOf("small" to "Small", "medium" to "Medium", "large" to "Large")
            for ((raw, label) in sizes) CheckRow(label, raw == size, last = raw == "large") { size = raw; p.edit().putString("chat.textSize", raw).apply() }
        }
        SettingsSection {
            SettingsRow("Quick Reaction", value = quick) { push(PAGE_QUICK) }
            SettingsRow("Night Mode", value = com.fariin.app.data.AppearancePrefs.current(ctx).label, last = true) { push("night") }
        }
        SettingsSection(footer = "Automatically save received photos to your gallery.") {
            SwitchRow("Save to Photos", savePhotos, last = true) { savePhotos = it; p.edit().putBoolean(com.fariin.app.media.AutoSaveMedia.KEY, it).apply() }
        }
        SettingsSection(footer = "Show a preview card for links you type or paste. Your phone contacts the site to build it, before the message is sent.") {
            SwitchRow("Link Previews", links, last = true) { links = it; p.edit().putBoolean("linkPreviewsEnabled", it).apply() }
        }
        SettingsSection(header = "New Chats from Your Chat Key", footer = "When someone uses your Chat Key to message you, the chat goes to Archived, muted.") {
            SwitchRow("Automatically Archive", archive, last = true) { archive = it; p.edit().putBoolean("unknownChats.autoArchive", it).apply() }
        }
    }
}

@Composable
private fun QuickReactionPage(pop: () -> Unit) {
    val ctx = LocalContext.current
    val p = remember { ctx.getSharedPreferences(CHAT_PREFS, Context.MODE_PRIVATE) }
    var v by remember { mutableStateOf(p.getString(SettingsRules.QUICK_KEY, "❤️") ?: "❤️") }
    ScrollPage("Quick Reaction", pop) {
        SettingsSection(header = "Choose your quick reaction", footer = "Double tap a message to react with it.") {
            for (row in SettingsRules.quickChoices.chunked(5)) Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                for (e in row) Box(
                    Modifier.size(52.dp).then(if (e == v) Modifier.padding(0.dp) else Modifier)
                        .clickable { v = e; p.edit().putString(SettingsRules.QUICK_KEY, e).apply() },
                    contentAlignment = Alignment.Center,
                ) { Text(e, fontSize = if (e == v) 34.sp else 26.sp) }
            }
        }
    }
}

// ---------------------------------------------------------------- Storage and Data

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
            SettingsRow("Manage storage", last = true) { push(PAGE_MANAGE_STORAGE) }
        }
        SettingsSection(header = "Media auto-download", footer = "When media downloads by itself. Anything else downloads when you open it.") {
            for ((k, label) in SettingsRules.autoKinds) {
                val v = media.getString("autodl.$k", SettingsRules.autoDefault(k)) ?: ""
                SettingsRow(label, value = SettingsRules.label(SettingsRules.autoPolicies, v) + if (tick < 0) "" else "") { push(PAGE_AUTODL + k) }
            }
            SettingsRow("Reset Auto-Download Settings", trailing = Trailing.NONE, titleColor = if (isDefault) Fariin.colors.secondary else Fariin.colors.label, last = true) {
                if (!isDefault) { media.edit().apply { SettingsRules.autoKinds.forEach { (k, _) -> remove("autodl.$k") } }.apply(); tick++ }
            }
        }
        SettingsSection(header = "Sent Media", footer = "Choose the quality you send photos and videos.") {
            SettingsRow("Sent Media Quality", value = SettingsRules.label(SettingsRules.sentQuality, media.getString(com.fariin.app.media.SentQuality.KEY, "standard") ?: "standard"),
                last = true) { push(PAGE_SENT_QUALITY) }
        }
        SettingsSection(header = "Calls", footer = "Using less data may improve calls on bad networks.") {
            SettingsRow("Use Less Data", value = SettingsRules.label(SettingsRules.lessData, calls.getString("calls.lessData", "never") ?: "never"), last = true) { push(PAGE_LESS_DATA) }
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
        SettingsSection(header = "Keep Media", footer = "Older media is removed from this phone and downloads again when you open it.") {
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
    if (confirm) YesNo("Clear cache?", "Photos, videos and voice messages you opened are removed from this phone. They download again when you open them. Your chats stay.", "Clear", onYes = {
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
    val me = FirebaseAuth.getInstance().currentUser?.uid
    val ref = me?.let { FirebaseFirestore.getInstance().collection("users").document(it).collection("private").document("storyPrefs") }
    LaunchedEffect(me) { ref?.let { r -> runCatching { r.get().await().getBoolean("receipts") }.getOrNull()?.let { on = it; p.edit().putBoolean("storyViewReceipts", it).apply() } } }
    ScrollPage("Stories", pop) {
        // iOS StorySettingsView: the audiences first, then View Receipts.
        StoryAudienceRows(push)
        SettingsSection(footer = "See and share when stories are viewed. If disabled, you won't see when others view your stories.") {
            SwitchRow("View Receipts", on, last = true) { v ->
                on = v; p.edit().putBoolean("storyViewReceipts", v).apply()
                scope.launch { runCatching { ref?.set(mapOf("receipts" to v, "offAt" to if (v) null else FieldValue.serverTimestamp()))?.await() } }
            }
        }
    }
}

// ---------------------------------------------------------------- Security notifications

/** iOS SecurityNotificationsView: users/{me}.securityAlerts (absent = off). */
@Composable
private fun SecurityNotificationsPage(pop: () -> Unit) {
    val scope = rememberCoroutineScope()
    val me = FirebaseAuth.getInstance().currentUser?.uid
    var on by remember { mutableStateOf<Boolean?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val ref = me?.let { FirebaseFirestore.getInstance().collection("users").document(it) }
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
