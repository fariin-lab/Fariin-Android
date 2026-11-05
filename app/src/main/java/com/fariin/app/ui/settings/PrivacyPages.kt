package com.fariin.app.ui.settings

import com.fariin.app.ui.components.listPress
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.data.Audience
import com.fariin.app.data.BlockList
import com.fariin.app.data.CryptoHub
import com.fariin.app.data.PrivacyRules
import com.fariin.app.data.PrivacySettings
import com.fariin.app.security.AppLock
import com.fariin.app.ui.components.Avatar
import com.fariin.app.ui.theme.Fariin
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/*
 * Privacy & Security, after Kulan-iOS PrivacySettingsView (SettingsView.swift) and PrivacyPages.swift:
 * the audience rows, Blocked Users, Always Relay Calls, App Lock, Safety Tips. The pages write the
 * iOS fields (data/PrivacySettings.kt) and the rest of the app reads them from there.
 */

internal val SwitchGreen = Color(0xFF34C759)

/** A Material switch row: the title at the start, the switch at the end, the whole row toggles it. */
@Composable
internal fun SwitchRow(title: String, checked: Boolean, enabled: Boolean = true, last: Boolean = false, onChange: (Boolean) -> Unit) {
    val c = Fariin.colors
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
            color = if (enabled) c.label else c.secondary, modifier = Modifier.weight(1f))
        com.fariin.app.ui.components.FariinSwitch(
            checked = checked, onCheckedChange = onChange, enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = com.fariin.app.ui.theme.Fariin.colors.onAccent, checkedBorderColor = c.accent),
        )
    }
    // The same full-width line as [SettingsRow], so a card of switches and arrows reads as one list.
    if (!last && SHOW_ROW_LINES) androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = c.separator)
}

/** A single-choice row, Android style: a radio button at the start (iOS drew a check at the end). */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun CheckRow(title: String, selected: Boolean, last: Boolean = false, onClick: () -> Unit) {
    val c = Fariin.colors
    Row(
        Modifier.fillMaxWidth().listPress(onClick = onClick).heightIn(min = 56.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.RadioButton(selected = selected, onClick = onClick,
            colors = androidx.compose.material3.RadioButtonDefaults.colors(selectedColor = c.accent))
        Spacer(Modifier.width(16.dp))
        Text(title, style = androidx.compose.material3.MaterialTheme.typography.bodyLarge, color = c.label, modifier = Modifier.weight(1f))
    }
}

@Composable
internal fun ErrorNote(text: String?) {
    if (text == null) return
    SettingsSection { Text(text, fontSize = 13.sp, color = Fariin.colors.red, modifier = Modifier.padding(16.dp)) }
}

@Composable
internal fun ScrollPage(title: String, pop: () -> Unit, action: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        PageBar(title, onBack = pop, action = action)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = com.fariin.app.ui.shell.bottomBarInset()).padding(top = 8.dp)) { content() }
    }
}

internal fun toast(@Suppress("UNUSED_PARAMETER") c: Context, s: String) = com.fariin.app.ui.components.Snack.show(s)

internal const val PAGE_PRIVACY = "privacy"
internal const val PAGE_AUDIENCE = "privacy:aud:"
internal const val PAGE_HIDE_FROM = "privacy:hidefrom"
internal const val PAGE_MESSAGES = "privacy:messages"
internal const val PAGE_CHAT_KEY = "privacy:chatkey"
internal const val PAGE_BLOCKED = "privacy:blocked"
internal const val PAGE_APP_LOCK = "privacy:applock"
internal const val PAGE_DISAPPEAR_DEFAULT = "privacy:disappear"

/** Routes this file draws; false when the page is not one of them. */
@Composable
internal fun privacyRoute(page: String, push: (String) -> Unit, pop: () -> Unit): Boolean {
    ChatKey.bind(LocalContext.current)
    when {
        page == PAGE_PRIVACY -> PrivacyPage(push, pop)
        page.startsWith(PAGE_AUDIENCE) -> AudiencePage(page.removePrefix(PAGE_AUDIENCE), push, pop)
        page == PAGE_HIDE_FROM -> HideFromPage(pop)
        page == PAGE_MESSAGES -> MessagesPrivacyPage(push, pop)
        page == PAGE_CHAT_KEY -> ChatKeyPage(pop)
        page == PAGE_BLOCKED -> BlockedUsersPage(pop)
        page == PAGE_APP_LOCK -> AppLockPage(pop)
        // The default timer for new chats I start (iOS PrivacySettingsView, same key).
        page == PAGE_DISAPPEAR_DEFAULT -> com.fariin.app.ui.people.DisappearingMessagesPage(
            com.fariin.app.data.Disappearing.defaultSeconds, onBack = pop) { com.fariin.app.data.Disappearing.defaultSeconds = it; pop() }
        else -> return false
    }
    return true
}

@Composable
private fun PrivacyPage(push: (String) -> Unit, pop: () -> Unit) {
    val ctx = LocalContext.current
    val map by PrivacySettings.map.collectAsState()
    val blocked by BlockList.state.collectAsState()
    val calls = remember { ctx.getSharedPreferences("fariin_calls", Context.MODE_PRIVATE) }
    var relay by remember { mutableStateOf(calls.getBoolean("calls.alwaysRelay", false)) }
    ScrollPage("Privacy & Security", pop) {
        SettingsSection {
            for (key in listOf("lastSeen", "photo", "bio", "groups", "messages", "calls")) {
                SettingsRow(PrivacyRules.title(key), value = PrivacyRules.audience(map, key).label(key), last = key == "calls") {
                    push(if (key == "messages") PAGE_MESSAGES else PAGE_AUDIENCE + key)
                }
            }
        }
        SettingsSection { SettingsRow("Blocked Users", value = "${blocked.entries.size}", last = true) { push(PAGE_BLOCKED) } }
        SettingsSection(footer = "Send every call through Fariin's server so the people you call can't see your IP address. Call quality may be a little lower.") {
            SwitchRow("Always Relay Calls", relay, last = true) { relay = it; calls.edit().putBoolean("calls.alwaysRelay", it).apply() }
        }
        SettingsSection(footer = com.fariin.app.data.Disappearing.SETTINGS_FOOTER) {
            SettingsRow("Disappearing Messages", value = com.fariin.app.data.Disappearing.label(com.fariin.app.data.Disappearing.defaultSeconds), last = true) {
                push(PAGE_DISAPPEAR_DEFAULT)
            }
        }
        SettingsSection(footer = "Require your fingerprint, face or phone PIN to unlock Fariin.") {
            SettingsRow("App Lock", value = if (AppLock.enabled(ctx)) "On" else "Off", last = true) { push(PAGE_APP_LOCK) }
        }
        SettingsSection(footer = "How Fariin keeps your chats private, and what to do if something feels wrong.") {
            SettingsRow("Safety Tips", trailing = Trailing.OUT_LINK, last = true) { openWeb(ctx, "https://fariin.com/safety") }
        }
    }
}

/** iOS AudiencePage: the choices with a check, a refused save said in red; Hide From under Photo. */
@Composable
private fun AudiencePage(key: String, push: (String) -> Unit, pop: () -> Unit) {
    val map by PrivacySettings.map.collectAsState()
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var hiddenCount by remember { mutableStateOf<Int?>(null) }
    if (key == "photo") LaunchedEffect(Unit) { hiddenCount = PhotoHideFrom.load().size }
    val selected = PrivacyRules.audience(map, key)
    ScrollPage(PrivacyRules.title(key), pop) {
        ErrorNote(error)
        SettingsSection(header = PrivacyRules.header(key), footer = PrivacyRules.footer(key)) {
            val opts = PrivacyRules.options(key)
            for (a in opts) CheckRow(a.label(key), a == selected, last = a == opts.last()) {
                error = null
                scope.launch { error = PrivacySettings.setAudience(key, a) }
            }
        }
        if (key == "photo") {
            SettingsSection(header = "Exceptions", footer = "These people will not see your profile photo, whatever you choose above.") {
                val n = hiddenCount ?: 0
                SettingsRow("Hide From", value = when (n) { 0 -> "Add People"; 1 -> "1 person"; else -> "$n people" }, last = true) { push(PAGE_HIDE_FROM) }
            }
        }
    }
}

/** users/{me}/photoHiddenFrom/{uid} {at} (iOS PhotoPrivacy). */
internal object PhotoHideFrom {
    private fun col() = FirebaseAuth.getInstance().currentUser?.uid?.let {
        FirebaseFirestore.getInstance().collection("users").document(it).collection("photoHiddenFrom")
    }
    suspend fun load(): List<String> = try { col()?.get()?.await()?.documents?.map { it.id } ?: emptyList() } catch (_: Exception) { emptyList() }
    suspend fun add(uid: String) = runCatching { col()!!.document(uid).set(mapOf("at" to FieldValue.serverTimestamp())).await() }.isSuccess
    suspend fun remove(uid: String) = runCatching { col()!!.document(uid).delete().await() }.isSuccess
}

/** The people I share a 1:1 with, from the chat list (uid, name, photo). */
internal fun myChatPeople(): List<Triple<String, String, String?>> {
    val me = FirebaseAuth.getInstance().currentUser?.uid ?: return emptyList()
    return CryptoHub.listedAll().filter { !it.isGroup && it.otherUid(me).isNotEmpty() }
        .map { Triple(it.otherUid(me), it.displayName(me), it.displayPhoto(me)) }
        .distinctBy { it.first }.sortedBy { it.second.lowercase() }
}

@Composable
private fun HideFromPage(pop: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var hidden by remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(Unit) { hidden = PhotoHideFrom.load().toSet() }
    val people = remember { myChatPeople() }
    ScrollPage("Hide From", pop) {
        SettingsSection(footer = "Checked people will not see your profile photo.") {
            if (people.isEmpty()) Text("No chats yet.", color = Fariin.colors.secondary, modifier = Modifier.padding(16.dp))
            for ((i, p) in people.withIndex()) {
                val on = hidden?.contains(p.first) == true
                PersonRow(p.second, p.third, last = i == people.lastIndex, selected = on) {
                    val h = hidden ?: return@PersonRow
                    scope.launch {
                        val ok = if (on) PhotoHideFrom.remove(p.first) else PhotoHideFrom.add(p.first)
                        if (ok) hidden = if (on) h - p.first else h + p.first else toast(ctx, "Check your connection and try again.")
                    }
                }
            }
        }
    }
}

@Composable
internal fun PersonRow(name: String, photo: String?, sub: String? = null, last: Boolean, selected: Boolean = false, trailing: (@Composable () -> Unit)? = null, onClick: (() -> Unit)?) {
    val c = Fariin.colors
    Column {
        Row(
            Modifier.fillMaxWidth().then(if (onClick != null) Modifier.listPress(onClick = onClick) else Modifier)
                .heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(photo, 36.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, fontSize = 17.sp, color = c.label, maxLines = 1)
                if (sub != null) Text(sub, fontSize = 13.sp, color = c.secondary, maxLines = 1)
            }
            if (selected) Icon(Icons.Filled.Check, contentDescription = "Selected", tint = c.accent, modifier = Modifier.size(22.dp))
            trailing?.invoke()
        }
        if (!last) HorizontalDivider(thickness = 0.5.dp, color = c.separator)
    }
}

/** iOS MessagesPrivacyPage: who can contact me by username, the Chat Key row, the two switches. */
@Composable
private fun MessagesPrivacyPage(push: (String) -> Unit, pop: () -> Unit) {
    val map by PrivacySettings.map.collectAsState()
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var explain by remember { mutableStateOf(false) }
    var keySet by remember { mutableStateOf(ChatKey.cachedSet()) }
    LaunchedEffect(Unit) {
        val s = ChatKey.status()
        if (s != null) keySet = s
        // iOS healIfKeyless: "People who know my key" with no key would shut everyone out.
        if (s == false && PrivacyRules.audience(PrivacySettings.map.value, "messages") == Audience.CONTACTS) {
            PrivacySettings.setAudience("messages", Audience.EVERYONE)
        }
    }
    val sel = PrivacyRules.audience(map, "messages")
    ScrollPage("Messages", pop) {
        ErrorNote(error)
        SettingsSection(header = "Who can contact me by username", footer = "Who new people can message. Your chats are never affected.") {
            for (a in PrivacyRules.options("messages")) CheckRow(a.label("messages"), a == sel, last = a == Audience.CONTACTS) {
                if (a == Audience.CONTACTS && keySet != true) { explain = true; return@CheckRow }
                scope.launch { error = PrivacySettings.setAudience("messages", a) }
            }
        }
        SettingsSection(footer = if (keySet == true) "Anyone who knows your Chat Key can message and call you directly, whatever you choose above."
            else "You haven't set a Chat Key, so nobody new can message you yet.") {
            SettingsRow("Chat Key", value = if (keySet == true) "On" else "Off", last = true) { push(PAGE_CHAT_KEY) }
        }
        SettingsSection(footer = "Turning one off stops you sending it. You will still see read receipts and typing from other people.") {
            SwitchRow("Read Receipts", PrivacyRules.flag(map, "readReceipts")) { on -> scope.launch { error = PrivacySettings.setFlag("readReceipts", on) } }
            SwitchRow("Typing Indicators", PrivacyRules.flag(map, "typingIndicators"), last = true) { on -> scope.launch { error = PrivacySettings.setFlag("typingIndicators", on) } }
        }
    }
    if (explain) {
        val c = Fariin.colors
        AlertDialog(
            onDismissRequest = { explain = false },
            title = { Text("Set a Chat Key first") },
            text = { Text("Only people who know your Chat Key will be able to message you. Make your key now, then choose this again.") },
            confirmButton = { TextButton(onClick = { explain = false; push(PAGE_CHAT_KEY) }) { Text("Set Chat Key", color = c.accent) } },
            dismissButton = { TextButton(onClick = { explain = false }) { Text("Cancel", color = c.label) } },
            containerColor = c.card, titleContentColor = c.label, textContentColor = c.label,
        )
    }
}

/**
 * The Chat Key, after Kulan-iOS ChatPin.swift: the server makes and keeps it (callables
 * generateChatPin, setChatPin {pin: ""} to remove, chatPinStatus {set, updatedAt}); this phone
 * keeps a copy of the digits to show and share, per account.
 */
internal object ChatKey {
    private fun fns() = FirebaseFunctions.getInstance(com.fariin.app.data.AuthRepository.FUNCTIONS_REGION)
    private fun uid() = FirebaseAuth.getInstance().currentUser?.uid ?: ""
    private var app: Context? = null
    private fun prefs() = app?.getSharedPreferences("fariin_chatkey", Context.MODE_PRIVATE)
    fun bind(c: Context) { app = c.applicationContext }
    fun cachedSet(): Boolean? = prefs()?.let { if (it.contains("set.${uid()}")) it.getBoolean("set.${uid()}", false) else null }
    fun mine(): String? = prefs()?.getString("pin.${uid()}", null)
    private fun save(set: Boolean, pin: String?) {
        prefs()?.edit()?.putBoolean("set.${uid()}", set)?.apply { if (pin != null) putString("pin.${uid()}", pin) else remove("pin.${uid()}") }?.apply()
    }

    /** null when the server could not be asked. */
    suspend fun status(): Boolean? = try {
        val r = fns().getHttpsCallable("chatPinStatus").call(emptyMap<String, Any>()).await().getData() as? Map<*, *>
        (r?.get("set") as? Boolean)?.also { if (!it) save(false, null) else prefs()?.edit()?.putBoolean("set.${uid()}", true)?.apply() }
    } catch (_: Exception) { null }

    suspend fun generate(): String {
        val r = try { fns().getHttpsCallable("generateChatPin").call(emptyMap<String, Any>()).await().getData() as? Map<*, *> }
        catch (_: Exception) { throw IllegalStateException("Couldn't make your Chat Key. Try again.") }
        val pin = r?.get("pin") as? String
        if (pin == null || !valid(pin)) throw IllegalStateException("Couldn't make your Chat Key. Try again.")
        save(true, pin)
        return pin
    }

    suspend fun remove() {
        try { fns().getHttpsCallable("setChatPin").call(mapOf("pin" to "")).await() }
        catch (_: Exception) { throw IllegalStateException("Couldn't remove your Chat Key. Try again.") }
        save(false, null)
    }

    fun valid(pin: String) = pin.length in 4..6 && pin.all { it in '0'..'9' }
}

/** iOS ChatPinPage / ChatPinSetSheet: make a key, show and share it, make a new one, or turn it off. */
@Composable
private fun ChatKeyPage(pop: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    remember { ChatKey.bind(ctx) }
    var set by remember { mutableStateOf(ChatKey.cachedSet()) }
    var pin by remember { mutableStateOf(ChatKey.mine()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { ChatKey.status()?.let { set = it; if (!it) pin = null } }
    fun run(work: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        scope.launch { try { work() } catch (e: Exception) { error = e.message } finally { busy = false } }
    }
    val c = Fariin.colors
    ScrollPage("Chat Key", pop) {
        ErrorNote(error)
        if (set == true) {
            SettingsSection(header = "Your Chat Key", footer = "Share it only with people you want to hear from. Anyone who knows it can message and call you directly.") {
                Text(pin?.chunked(1)?.joinToString(" ") ?: "Hidden on this phone", fontSize = if (pin != null) 32.sp else 17.sp,
                    color = if (pin != null) c.label else c.secondary, modifier = Modifier.padding(20.dp))
            }
            SettingsSection {
                if (pin != null) SettingsRow("Share Chat Key", trailing = Trailing.NONE) {
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(android.content.Intent.EXTRA_TEXT, "My Fariin Chat Key is $pin")
                    ctx.startActivity(android.content.Intent.createChooser(send, null))
                }
                SettingsRow("Make a New Key", trailing = Trailing.NONE) { confirmReset = true }
                SettingsRow("Turn Off Chat Key", trailing = Trailing.NONE, titleColor = c.red, last = true) { confirmRemove = true }
            }
        } else {
            SettingsSection(footer = "A private number you can share. Anyone who knows it can message and call you directly.") {
                SettingsRow(if (busy) "Making your key..." else "Make My Chat Key", trailing = Trailing.NONE, titleColor = c.accent, last = true) {
                    run { pin = ChatKey.generate(); set = true }
                }
            }
        }
    }
    if (confirmReset) YesNo("Make a new Chat Key?", "Your old key stops working. People who had it will need the new one.", "Make New Key",
        onYes = { confirmReset = false; run { pin = ChatKey.generate(); set = true } }, onNo = { confirmReset = false })
    if (confirmRemove) YesNo("Turn off your Chat Key?", "Nobody will be able to use your old key to message you.", "Turn Off",
        onYes = { confirmRemove = false; run { ChatKey.remove(); set = false; pin = null } }, onNo = { confirmRemove = false })
}

@Composable
internal fun YesNo(title: String, text: String, yes: String, destructive: Boolean = true, onYes: () -> Unit, onNo: () -> Unit) {
    val c = Fariin.colors
    AlertDialog(
        onDismissRequest = onNo,
        title = { Text(title) }, text = { Text(text) },
        confirmButton = { TextButton(onClick = onYes) { Text(yes, color = if (destructive) c.red else c.accent) } },
        dismissButton = { TextButton(onClick = onNo) { Text("Cancel", color = c.label) } },
        containerColor = c.card, titleContentColor = c.label, textContentColor = c.label,
    )
}

/** iOS BlockedUsersView: newest block first, name and @username, Unblock. */
@Composable
private fun BlockedUsersPage(pop: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snap by BlockList.state.collectAsState()
    var people by remember { mutableStateOf<Map<String, Triple<String, String, String?>>>(emptyMap()) }
    var confirm by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(snap.entries.keys) {
        val db = FirebaseFirestore.getInstance()
        val out = HashMap(people)
        for (u in snap.entries.keys) if (u !in out) {
            val d = try { db.collection("users").document(u).get().await().data } catch (_: Exception) { null }
            out[u] = Triple(d?.get("name") as? String ?: "", d?.get("handle") as? String ?: "", (d?.get("photoUrl") as? String)?.ifEmpty { null })
        }
        people = out
    }
    val rows = snap.entries.entries.sortedByDescending { it.value }.map { it.key }
    ScrollPage("Blocked Users", pop) {
        SettingsSection(footer = "Blocked people can't call you or send you messages, and you won't see their stories.") {
            if (rows.isEmpty()) Text("No blocked users", color = Fariin.colors.secondary, modifier = Modifier.padding(16.dp))
            for ((i, u) in rows.withIndex()) {
                val p = people[u]
                val name = p?.first?.ifEmpty { null } ?: p?.second?.takeIf { it.isNotEmpty() }?.let { "@$it" } ?: "An account you blocked"
                PersonRow(name, p?.third, sub = p?.second?.takeIf { it.isNotEmpty() && p.first.isNotEmpty() }?.let { "@$it" }, last = i == rows.lastIndex,
                    trailing = { TextButton(onClick = { confirm = u }) { Text("Unblock", color = Fariin.colors.accent) } }, onClick = null)
            }
        }
    }
    confirm?.let { u ->
        YesNo("Unblock?", "They will be able to message and call you again. Messages sent while blocked stay hidden.", "Unblock", destructive = false,
            onYes = {
                confirm = null
                scope.launch {
                    val ok = com.fariin.app.data.FirestorePeople(FirebaseAuth.getInstance().currentUser!!.uid, CryptoHub.get(ctx)).setBlocked(u, false)
                    if (!ok) toast(ctx, "Check your connection and try again.")
                }
            }, onNo = { confirm = null })
    }
}

/** iOS AppLockPage: Require screen lock, Auto-Lock delay, Screen Security. */
@Composable
private fun AppLockPage(pop: () -> Unit) {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(AppLock.enabled(ctx)) }
    var delay by remember { mutableStateOf(AppLock.delaySeconds(ctx)) }
    var screen by remember { mutableStateOf(AppLock.screenSecurity(ctx)) }
    ScrollPage("App Lock", pop) {
        SettingsSection(footer = "Require your fingerprint, face or phone PIN to unlock Fariin.") {
            SwitchRow("Require Screen Lock", on, last = !on) { v ->
                if (v && !AppLock.deviceSecure(ctx)) { toast(ctx, "Set a screen lock on your phone first."); return@SwitchRow }
                on = v; AppLock.setEnabled(ctx, v)
            }
        }
        if (on) SettingsSection(header = "Auto-Lock") {
            for ((s, label) in AppLock.delays) CheckRow(label, s == delay, last = s == AppLock.delays.last().first) { delay = s; AppLock.setDelay(ctx, s) }
        }
        SettingsSection(footer = "Hide Fariin's content in the app switcher and block screenshots. Always on while App Lock is on.") {
            SwitchRow("Screen Security", on || screen, enabled = !on, last = true) { screen = it; AppLock.setScreenSecurity(ctx, it) }
        }
    }
}
