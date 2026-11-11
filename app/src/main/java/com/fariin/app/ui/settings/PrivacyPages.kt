package com.fariin.app.ui.settings

import com.fariin.app.ui.components.GlassAlert
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.fariin.app.R
import com.fariin.app.data.NewBadgeSpots
import com.fariin.app.ui.components.listPress
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.animation.togetherWith
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
internal fun SwitchRow(title: String, checked: Boolean, enabled: Boolean = true, last: Boolean = false,
    /** The row's NewBadgeSpots id: the green "New" pill after the title while the panel has it on. */
    badge: String? = null, onChange: (Boolean) -> Unit) {
    val c = Fariin.colors
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (com.fariin.app.ui.components.isNew(badge)) Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                color = if (enabled) c.label else c.secondary, modifier = Modifier.weight(1f, fill = false))
            com.fariin.app.ui.components.NewPill(Modifier.padding(start = 8.dp, end = 8.dp))
        } else Text(title, style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
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
    // The rows run under the bar, so its back and action are glass over them (GlassBarPage).
    val scroll = rememberScrollState()
    val under by androidx.compose.runtime.remember { androidx.compose.runtime.derivedStateOf { scroll.value > 0 } }
    com.fariin.app.ui.components.GlassBarPage(under, bar = { PageBar(title, onBack = pop, action = action) }) { top ->
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(bottom = com.fariin.app.ui.shell.bottomBarInset()).padding(top = top + 8.dp)) { content() }
    }
}

internal fun toast(@Suppress("UNUSED_PARAMETER") c: Context, s: String) = com.fariin.app.ui.components.Snack.show(s)

internal const val PAGE_PRIVACY = "privacy"
internal const val PAGE_AUDIENCE = "privacy:aud:"
internal const val PAGE_HIDE_FROM = "privacy:hidefrom"
internal const val PAGE_MESSAGES = "privacy:messages"
/** Account > Username: the Messages page in username mode (iOS MessagesPrivacyPage(usernameMode: true)). */
internal const val PAGE_CONTACT_USERNAME = "privacy:messages:username"
internal const val PAGE_CHAT_KEY = "privacy:chatkey"
internal const val PAGE_BLOCKED = "privacy:blocked"
internal const val PAGE_MY_REPORTS = "privacy:myreports"
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
        page == PAGE_MY_REPORTS -> MyReportsPage(pop)
        page == PAGE_APP_LOCK -> AppLockPage(pop)
        // The default timer for new chats I start (iOS PrivacySettingsView, same key).
        page == PAGE_DISAPPEAR_DEFAULT -> com.fariin.app.ui.people.DisappearingMessagesPage(
            com.fariin.app.data.Disappearing.defaultSeconds, onBack = pop, forNewChats = true) { com.fariin.app.data.Disappearing.defaultSeconds = it; pop() }
        else -> return false
    }
    return true
}

/** The audience rows' "New" spots, by privacy key. */
private val PRIVACY_SPOTS = mapOf(
    "lastSeen" to NewBadgeSpots.PRIVACY_LAST_SEEN, "photo" to NewBadgeSpots.PRIVACY_PHOTO,
    "bio" to NewBadgeSpots.PRIVACY_BIO, "groups" to NewBadgeSpots.PRIVACY_GROUPS,
    "messages" to NewBadgeSpots.PRIVACY_MESSAGES, "calls" to NewBadgeSpots.PRIVACY_CALLS,
)

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
                SettingsRow(PrivacyRules.title(key), value = PrivacyRules.audience(map, key).label(key), last = key == "calls",
                    badge = PRIVACY_SPOTS[key]) {
                    push(if (key == "messages") PAGE_MESSAGES else PAGE_AUDIENCE + key)
                }
            }
        }
        SettingsSection {
            SettingsRow("Blocked Users", value = "${blocked.entries.size}",
                badge = NewBadgeSpots.PRIVACY_BLOCKED) { push(PAGE_BLOCKED) }
            // The reports I sent and what came of them (only I see them).
            SettingsRow("My Reports", last = true) { push(PAGE_MY_REPORTS) }
        }
        SettingsSection(footer = "Send every call through Fariin's server so the people you call can't see your IP address. Call quality may be a little lower.") {
            SwitchRow("Always Relay Calls", relay, last = true, badge = NewBadgeSpots.PRIVACY_ALWAYS_RELAY) { relay = it; calls.edit().putBoolean("calls.alwaysRelay", it).apply() }
        }
        SettingsSection(footer = com.fariin.app.data.Disappearing.SETTINGS_FOOTER) {
            SettingsRow("Disappearing Messages", value = com.fariin.app.data.Disappearing.label(com.fariin.app.data.Disappearing.defaultSeconds), last = true,
                badge = NewBadgeSpots.PRIVACY_DISAPPEARING) {
                push(PAGE_DISAPPEAR_DEFAULT)
            }
        }
        SettingsSection(footer = "Require your fingerprint, face or phone PIN to unlock Fariin.") {
            SettingsRow("App Lock", value = if (AppLock.enabled(ctx)) "On" else "Off", last = true,
                badge = NewBadgeSpots.PRIVACY_APP_LOCK) { push(PAGE_APP_LOCK) }
        }
        SettingsSection(footer = "How Fariin keeps your chats private, and what to do if something feels wrong.") {
            SettingsRow("Safety Tips", trailing = Trailing.OUT_LINK, last = true,
                badge = NewBadgeSpots.PRIVACY_SAFETY_TIPS) { openWeb(ctx, "https://fariin.com/safety") }
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
    // Each change re-publishes my photo under a new name (iOS republishPhoto), so a person just
    // hidden stops getting it from their cache.
    suspend fun add(uid: String) = runCatching { col()!!.document(uid).set(mapOf("at" to FieldValue.serverTimestamp())).await() }.isSuccess
        .also { if (it) com.fariin.app.data.PhotoRepublish.launch() }
    suspend fun remove(uid: String) = runCatching { col()!!.document(uid).delete().await() }.isSuccess
        .also { if (it) com.fariin.app.data.PhotoRepublish.launch() }
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
    // A row whose write is on its way: no second tap, a spinner where the tick goes.
    var working by remember { mutableStateOf(setOf<String>()) }
    ScrollPage("Hide From", pop) {
        SettingsSection(footer = "Checked people will not see your profile photo.") {
            // Until the hidden list has loaded: placeholder rows, not "No chats yet." or wrong ticks.
            val waitRows = people.size.coerceIn(1, 6)
            if (hidden == null) repeat(waitRows) { i -> PlaceholderPersonRow(last = i == waitRows - 1) }
            else if (people.isEmpty()) Text("No chats yet.", color = Fariin.colors.secondary, modifier = Modifier.padding(16.dp))
            else for ((i, p) in people.withIndex()) {
                val on = hidden?.contains(p.first) == true
                val busy = p.first in working
                PersonRow(p.second, p.third, last = i == people.lastIndex, selected = on && !busy,
                    trailing = if (busy) ({ com.fariin.app.ui.components.FariinSpinner(size = 20.dp) }) else null,
                    onClick = if (busy) null else ({
                        if (hidden != null) {
                            working = working + p.first
                            scope.launch {
                                val ok = if (on) PhotoHideFrom.remove(p.first) else PhotoHideFrom.add(p.first)
                                // Against the newest set, so two rows changing at once both stay.
                                if (ok) hidden = hidden?.let { if (on) it - p.first else it + p.first } else toast(ctx, "Check your connection and try again.")
                                working = working - p.first
                            }
                        }
                    }))
            }
        }
    }
}

@Composable
internal fun PlaceholderPersonRow(last: Boolean) {
    val c = Fariin.colors
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(androidx.compose.foundation.shape.CircleShape).background(c.placeholderFill))
            Spacer(Modifier.width(12.dp))
            Box(Modifier.width(140.dp).height(12.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp)).background(c.placeholderFill))
        }
        if (!last) HorizontalDivider(thickness = 0.5.dp, color = c.separator)
    }
}

/** A person's row in the privacy lists; [PlaceholderPersonRow] stands in while the names load. */
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
            if (selected) Icon(ImageVector.vectorResource(R.drawable.ic_sf_checkmark), contentDescription = "Selected", tint = c.accent, modifier = Modifier.size(22.dp))
            trailing?.invoke()
        }
        if (!last) HorizontalDivider(thickness = 0.5.dp, color = c.separator)
    }
}

/**
 * iOS ChatPinPage.syncMessagesMode (owner 2026-11-05): a key that exists means the Messages mode is
 * the key mode ("People who know my key"); no key means it is not. Run after a key is made or removed.
 */
internal suspend fun syncMessagesMode(keyExists: Boolean) {
    val mode = PrivacyRules.audience(PrivacySettings.map.value, "messages")
    if (keyExists) {
        if (!com.fariin.app.data.MsgPolicy.chatKeyVisible || mode == Audience.CONTACTS) return
        PrivacySettings.setAudience("messages", Audience.CONTACTS)
    } else if (mode == Audience.CONTACTS) {
        PrivacySettings.setAudience("messages", Audience.EVERYONE)
    }
}

/**
 * iOS MessagesPrivacyPage: who can contact me by username, the Chat Key row, the two switches.
 * [usernameMode] (Account > Username): titled "Contact me by username", the Username row on top
 * (opens the editor), and no Read Receipts / Typing switches.
 */
@Composable
internal fun MessagesPrivacyPage(push: (String) -> Unit, pop: () -> Unit, usernameMode: Boolean = false, handle: String? = null) {
    val map by PrivacySettings.map.collectAsState()
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var explain by remember { mutableStateOf(false) }
    var keySet by remember { mutableStateOf(ChatKey.cachedSet()) }
    // Leaving the key mode removes the key (iOS, owner 2026-11-02): asked first, nothing changes on Cancel.
    var confirmRemoveKey by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val s = ChatKey.status()
        if (s != null) keySet = s
        // iOS healIfKeyless: "People who know my key" with no key would shut everyone out.
        if (s == false && PrivacyRules.audience(PrivacySettings.map.value, "messages") == Audience.CONTACTS) {
            PrivacySettings.setAudience("messages", Audience.EVERYONE)
        }
    }
    val sel = PrivacyRules.audience(map, "messages")
    ScrollPage(if (usernameMode) "Contact me by username" else "Messages", pop) {
        ErrorNote(error)
        if (usernameMode) SettingsSection {
            SettingsRow("Username", value = if (handle.isNullOrEmpty()) "Not set" else "@$handle", last = true) { push(PAGE_USERNAME) }
        }
        SettingsSection(header = "Who can contact me by username", footer = "Who new people can message. Your chats are never affected.") {
            for (a in PrivacyRules.options("messages")) CheckRow(a.label("messages"), a == sel, last = a == Audience.CONTACTS) {
                if (a == Audience.CONTACTS && keySet != true) { explain = true; return@CheckRow }
                if (a == Audience.EVERYONE && sel == Audience.CONTACTS && keySet == true && com.fariin.app.data.MsgPolicy.chatKeyVisible) {
                    confirmRemoveKey = true; return@CheckRow
                }
                scope.launch { error = PrivacySettings.setAudience("messages", a) }
            }
        }
        SettingsSection(footer = if (keySet == true) "Anyone who knows your Chat Key can message and call you directly, whatever you choose above."
            else "You haven't set a Chat Key, so nobody new can message you yet.") {
            SettingsRow("Chat Key", value = if (keySet == true) "On" else "Off", last = true) { push(PAGE_CHAT_KEY) }
        }
        if (!usernameMode) SettingsSection(footer = "Turning one off stops you sending it. You will still see read receipts and typing from other people.") {
            SwitchRow("Read Receipts", PrivacyRules.flag(map, "readReceipts")) { on -> scope.launch { error = PrivacySettings.setFlag("readReceipts", on) } }
            SwitchRow("Typing Indicators", PrivacyRules.flag(map, "typingIndicators"), last = true) { on -> scope.launch { error = PrivacySettings.setFlag("typingIndicators", on) } }
        }
    }
    // iOS: choosing "People who know my key" with no key shows what a Chat Key is; Got it opens the
    // Chat Key page, which makes the key and then switches this setting. Back or a failure leaves it.
    if (explain) ChatKeyIntroSheet(onDismiss = { explain = false }) {
        ChatKey.makeThenKeyOnly = true
        push(PAGE_CHAT_KEY)
    }
    // Everyone while the key mode is on: the key goes first, on the server, then the setting.
    if (confirmRemoveKey) com.fariin.app.ui.people.PersonConfirmSheet(null, "", "Are you sure you want to remove your Chat Key?",
        "Anyone will be able to message you, and your Chat Key will stop working.",
        listOf(com.fariin.app.ui.people.PersonAction("Remove") {
            try {
                ChatKey.remove(); keySet = false
                error = PrivacySettings.setAudience("messages", Audience.EVERYONE)
            } catch (e: Exception) { error = "Couldn't remove your Chat Key. ${e.message ?: ""}".trim() }
            true
        }),
        onDismiss = { confirmRemoveKey = false }, face = false)
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
    /** Set by the Messages page's intro sheet: the Chat Key page makes a key at once, then sets
     *  messages to "People who know my key" (iOS ChatPinPage autoCreate). Read once on entry. */
    var makeThenKeyOnly = false
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
internal fun ChatKeyPage(pop: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    remember { ChatKey.bind(ctx) }
    var set by remember { mutableStateOf(ChatKey.cachedSet()) }
    var pin by remember { mutableStateOf(ChatKey.mine()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var intro by remember { mutableStateOf(false) }
    // The digits rolling while a key is made (iOS ChatKeySlotView); rollingTo = the key once it is here.
    var rolling by remember { mutableStateOf(false) }
    var rollingTo by remember { mutableStateOf<String?>(null) }
    /** Makes a key on the server with the six digits rolling meanwhile; the card shows it when they stop. */
    suspend fun makeKey() {
        rollingTo = null; rolling = true
        try { rollingTo = ChatKey.generate() } catch (e: Exception) { rolling = false; throw e }
    }
    // Taken once, so coming back to this page later does not make another key.
    val autoMake = remember { ChatKey.makeThenKeyOnly.also { ChatKey.makeThenKeyOnly = false } }
    fun run(work: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        scope.launch { try { work() } catch (e: Exception) { error = e.message } finally { busy = false } }
    }
    LaunchedEffect(Unit) {
        val s = ChatKey.status()
        s?.let { set = it; if (!it) pin = null }
        // From the Messages page's intro sheet: make the key, then the mode follows the key that exists.
        if (autoMake) run {
            if (set != true) makeKey()
            PrivacySettings.setAudience("messages", Audience.CONTACTS)?.let { throw IllegalStateException(it) }
        }
    }
    val c = Fariin.colors
    ScrollPage("Chat Key", pop) {
        ErrorNote(error)
        val footer = "Anyone with your Chat Key can message and call you. Share it only with people you trust."
        if (rolling) {
            SettingsSection(footer = footer) {
                Column(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    ChatKeySlot(rollingTo) { pin = rollingTo; set = true; rolling = false; rollingTo = null }
                    // Say what is happening (iOS): the zeroes alone read as a frozen screen.
                    Text(if (rollingTo == null) "Creating your Chat Key…" else "Your new Chat Key", fontSize = 13.sp,
                        color = c.secondary, modifier = Modifier.padding(top = 8.dp))
                }
            }
        } else if (set == true) {
            // iOS ChatPinPage: the key in large digits with Copy and Share under it, in one card.
            SettingsSection(header = "Your Chat Key", footer = footer) {
                val p = pin
                if (p != null) Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(p.chunked(1).joinToString(" "), fontSize = 34.sp, fontWeight = FontWeight.SemiBold, color = c.label,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                    Spacer(Modifier.height(10.dp))
                    var copied by remember { mutableStateOf(false) }
                    LaunchedEffect(copied) { if (copied) { kotlinx.coroutines.delay(2000); copied = false } }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        KeyPill(if (copied) "Copied" else "Copy", if (copied) com.fariin.app.R.drawable.ic_sf_checkmark else com.fariin.app.R.drawable.ic_sf_doc_on_doc, Modifier.weight(1f)) {
                            (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                                .setPrimaryClip(android.content.ClipData.newPlainText("Chat Key", p).apply {
                                    // Kept out of the keyboard's clipboard preview (Android 13+).
                                    description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                                })
                            copied = true
                        }
                        KeyPill("Share", com.fariin.app.R.drawable.ic_link_share, Modifier.weight(1f)) {
                            val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(android.content.Intent.EXTRA_TEXT, "My Chat Key on Fariin is $p. Enter it to message me directly.")
                            ctx.startActivity(android.content.Intent.createChooser(send, null))
                        }
                    }
                }
                // Set from another phone: the server keeps only a scrambled copy (iOS ChatPinPage).
                else Text("Your Chat Key was set on another device.", fontSize = 15.sp, color = c.secondary, modifier = Modifier.padding(16.dp))
            }
            SettingsSection(footer = "Resetting gives you a new key and the old one stops working. People who already used it stay in your chats.") {
                SettingsRow("Reset Chat Key", trailing = Trailing.NONE) { confirmReset = true }
                SettingsRow("Remove Chat Key", trailing = Trailing.NONE, titleColor = c.red, last = true) { confirmRemove = true }
            }
        } else {
            SettingsSection(footer = footer) {
                SettingsRow("Create Chat Key", trailing = Trailing.NONE, titleColor = c.accent, last = true,
                    accessory = if (busy) ({ com.fariin.app.ui.components.FariinSpinner(size = 20.dp) }) else null) {
                    // What a Chat Key is, first (iOS); Got it makes the key.
                    if (!busy) intro = true
                }
            }
        }
    }
    if (intro) ChatKeyIntroSheet(onDismiss = { intro = false }) { run { makeKey(); syncMessagesMode(true) } }
    // The contact page's bottom sheet (no photo: it is my own key). Same work as run(), awaited so the
    // button spins; a failure still shows on the page.
    suspend fun runNow(done: String, work: suspend () -> Unit): Boolean {
        if (busy) return false
        busy = true; error = null
        val ok = try { work(); true } catch (e: Exception) { error = e.message; false } finally { busy = false }
        if (ok) com.fariin.app.ui.components.Snack.show(done)
        return ok
    }
    if (confirmReset) com.fariin.app.ui.people.PersonConfirmSheet(null, "", "Reset your Chat Key?", "You'll get a new key and the old one will stop working.",
        listOf(com.fariin.app.ui.people.PersonAction("Reset") { runNow("Your new Chat Key is ready") { makeKey(); syncMessagesMode(true) } }),
        onDismiss = { confirmReset = false }, face = false)
    if (confirmRemove) com.fariin.app.ui.people.PersonConfirmSheet(null, "", "Remove your Chat Key?", "Nobody will be able to use it to message you. People who already did stay in your chats.",
        listOf(com.fariin.app.ui.people.PersonAction("Remove") { runNow("Chat Key removed") { ChatKey.remove(); set = false; pin = null; syncMessagesMode(false) } }),
        onDismiss = { confirmRemove = false }, face = false)
}

/**
 * The key counted up to like a meter (iOS ChatKeySlotView): six zeroes while the server chooses, a
 * beat, then the whole number counts from 000000 to the key in 40 steps, fast first and slowing at
 * the end, every digit rolling upward and all six stopping together; a light tick every fourth
 * step, the success buzz at the end, then [onDone].
 */
@Composable
private fun ChatKeySlot(final: String?, onDone: () -> Unit) {
    val view = androidx.compose.ui.platform.LocalView.current
    var shown by remember { mutableStateOf(List(6) { 0 }) }
    LaunchedEffect(final) {
        shown = List(6) { 0 }
        val target = final?.takeIf { it.length == 6 }?.toIntOrNull() ?: return@LaunchedEffect
        kotlinx.coroutines.delay(300)
        val steps = 40
        for (st in 1..steps) {
            val t = st / steps.toDouble()
            val eased = 1 - Math.pow(1 - t, 3.0)
            val v = if (st == steps) target else (target * eased).toInt()
            shown = v.toString().padStart(6, '0').map { it - '0' }
            if (st % 4 == 0) view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            kotlinx.coroutines.delay(60)
        }
        view.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
        kotlinx.coroutines.delay(450)
        onDone()
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.semantics { contentDescription = if (final == null) "Making your Chat Key" else "Your Chat Key" }) {
        shown.forEach { d ->
            androidx.compose.animation.AnimatedContent(d, transitionSpec = {
                // Upward, as iOS numericText: the new digit comes from below, the old one leaves above.
                (androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(180)) { it } +
                    androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180))) togetherWith
                    (androidx.compose.animation.slideOutVertically(androidx.compose.animation.core.tween(180)) { -it } +
                        androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(180)))
            }, modifier = Modifier.width(22.dp).clipToBounds(), label = "keyDigit") { n ->
                Text("$n", fontSize = 34.sp, fontWeight = FontWeight.SemiBold, color = Fariin.colors.label,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
internal fun YesNo(title: String, text: String, yes: String, destructive: Boolean = true, onYes: () -> Unit, onNo: () -> Unit) {
    val c = Fariin.colors
    GlassAlert(
        onDismissRequest = onNo,
        title = { Text(title) }, text = { Text(text) },
        confirmButton = { TextButton(onClick = onYes) { Text(yes, color = if (destructive) c.red else c.accent) } },
        dismissButton = { TextButton(onClick = onNo) { Text("Cancel", color = c.label) } },
        containerColor = c.card, titleContentColor = c.label, textContentColor = c.label,
    )
}

/** iOS AppLockPage: Require screen lock, Auto-Lock delay, Screen Security. */
@Composable
private fun AppLockPage(pop: () -> Unit) {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(AppLock.enabled(ctx)) }
    var delay by remember { mutableStateOf(AppLock.delaySeconds(ctx)) }
    var screen by remember { mutableStateOf(AppLock.screenSecurity(ctx)) }
    // Opened with App Lock off: a short intro first. Set Up turns it on, or sends the person to set
    // a phone screen lock first when there is none.
    var intro by remember { mutableStateOf(!on) }
    if (intro) com.fariin.app.ui.components.IllustratedSheet(
        art = com.fariin.app.ui.components.LineArt.Lock,
        title = "App Lock",
        body = "Keep your chats private. Fariin asks for your fingerprint, face or phone PIN when you open it.",
        primary = "Set Up",
        onPrimary = {
            intro = false
            if (AppLock.deviceSecure(ctx)) { on = true; AppLock.setEnabled(ctx, true) }
            else {
                toast(ctx, "Set a screen lock on your phone first, then come back.")
                runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        },
        onDismiss = { intro = false },
        secondary = "Not now",
    )
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

/** iOS ChatPinPage's Copy / Share capsules under the key. */
@Composable
private fun KeyPill(label: String, icon: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Fariin.colors
    Row(modifier.height(40.dp).clip(androidx.compose.foundation.shape.CircleShape).background(c.fieldFill).clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Icon(androidx.compose.ui.graphics.vector.ImageVector.vectorResource(icon), null, tint = c.label, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.label)
    }
}
