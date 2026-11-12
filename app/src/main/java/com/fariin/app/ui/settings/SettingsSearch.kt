package com.fariin.app.ui.settings

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.R
import com.fariin.app.ui.theme.Fariin

/*
 * Settings search (iOS SettingsSearchPage + SettingsIndex): the field at the top with the round X,
 * the keyboard up, every settings page listed under it; a page inside another page carries that
 * page's name under its own. Typing narrows by title or by the words people type for it.
 */

internal const val PAGE_SETTINGS_SEARCH = "settings:search"

/**
 * ⛔ EVERY SETTING, NOT JUST EVERY CARD (iOS SettingsIndex, owner 2026-11-05 + Android 2026-11-10:
 * search found only the main pages). One list of every page AND every row inside them, each with
 * the path of pages that leads to it, the words people type for it, and the page a tap opens (the
 * page that holds it). A row a page hides is not indexed: the same gates as the pages.
 */
internal object SettingsIndex {
    /** The account has two-step on (set by the Settings page): only then is it findable, as its row. */
    @Volatile var hasTwoStep = false

    data class Entry(val title: String, val icon: String?, val keywords: String, val page: String,
                     val path: List<String> = emptyList(), val isPage: Boolean = false) {
        val id: String get() = (path + title).joinToString("›")
        /** "Appearance > Chat Wallpaper", under the title. */
        val crumb: String? get() = if (path.isEmpty()) null else path.joinToString(" › ")
    }

    private const val ACC = "Account"
    private const val DEV = "Devices"
    private const val NOT = "Notifications"
    private const val AP = "Appearance"
    private const val CH = "Chats"
    private const val ST = "Stories"
    private const val PR = "Privacy & Security"
    private const val SD = "Storage and Data"
    private const val HP = "Help & About"
    private const val AUD = "everyone my contacts nobody who can see audience"

    val entries: List<Entry> get() = buildList {
        fun card(t: String, icon: String, kw: String, page: String) = add(Entry(t, icon, kw, page, isPage = true))
        fun page(t: String, path: List<String>, kw: String, page: String) = add(Entry(t, null, kw, page, path, isPage = true))
        fun row(t: String, path: List<String>, kw: String, page: String) = add(Entry(t, null, kw, page, path))
        if (com.fariin.app.data.AppFeatures.myProfileCard) card("My Profile", "account", "profile bio photo edit stories", PAGE_GLOW_PROFILE)
        card(ACC, "account", "account email password username sign in sign out log out delete", PAGE_ACCOUNT)
        card(DEV, "devices", "devices sessions linked signed in log out sign out", PAGE_DEVICES)
        card(NOT, "notifications", "notifications push alerts sound vibrate preview", PAGE_NOTIFS)
        card(AP, "appearance", "appearance theme dark mode light mode night colors look", PAGE_APPEARANCE)
        card(CH, "chats", "chats messages save to photos camera roll history", PAGE_CHATS)
        card(ST, "stories", "stories status story audience who can see", PAGE_STORIES)
        card(PR, "privacy", "privacy security who can see last seen blocked app lock", PAGE_PRIVACY)
        card(SD, "storage", "storage data cache download network usage media", PAGE_STORAGE)
        card(HP, "help", "help about version support faq", PAGE_ABOUT)
        // Account
        val acc = listOf(ACC)
        page("Email address", acc, "email address change mail sign in", PAGE_EMAIL)
        row("Password", acc, "password change set sign in", PAGE_ACCOUNT)
        page("Username", acc, "username handle @ who can find me contact me", PAGE_CONTACT_USERNAME)
        page("Passkeys", acc, "passkeys passkey fingerprint face sign in key", PAGE_PASSKEYS)
        if (hasTwoStep) page("Two-step verification", acc, "two step twostep verification 2fa additional password code security", PAGE_TWOSTEP)
        page("Security notifications", acc, "security notifications alerts new login sign in email", PAGE_SEC_NOTIFS)
        row("Sign-in alerts", acc + "Security notifications", "sign in alerts new login email security", PAGE_SEC_NOTIFS)
        row("Your Account Data", acc, "export download account data copy text file", PAGE_ACCOUNT)
        row("Log out", acc, "log out sign out logout signout exit", PAGE_ACCOUNT)
        row("Delete Account", acc, "delete account remove close erase", PAGE_ACCOUNT)
        // Devices
        val dev = listOf(DEV)
        row("Sign out all other devices", dev, "sign out log out other devices sessions terminate", PAGE_DEVICES)
        row("Automatic sign-out", dev, "automatic sign out after inactive days devices", PAGE_DEVICES)
        page("Security Checkup", dev, "security checkup review safety", PAGE_CHECKUP)
        // Notifications
        val nt = listOf(NOT)
        row("Show Notifications", nt, "show notifications push on off alerts", PAGE_NOTIFS)
        row("Message Preview", nt, "message preview text lock screen banner content", PAGE_NOTIFS)
        row("Sound", nt, "notification sound message tone ringtone tone alert", PAGE_NOTIFS)
        row("In-App Sounds", nt, "in app sounds sound while open", PAGE_NOTIFS)
        row("In-App Vibrate", nt, "in app vibrate vibration haptic", PAGE_NOTIFS)
        row("In-App Preview", nt, "in app preview banner sender message while open", PAGE_NOTIFS)
        row("Sound When Sent", nt, "sound when sent sent sound outgoing tone", PAGE_NOTIFS)
        row("Reset All Notifications", nt, "reset all notifications muted chats sounds default", PAGE_NOTIFS)
        // Appearance
        val ap = listOf(AP)
        row("Chat Theme", ap, "chat theme themes colors preview dark light", PAGE_APPEARANCE)
        page("Chat Wallpaper", ap, "wallpaper background photo image presets chat background", PAGE_WALLPAPER)
        page("Wallpaper Color", ap + "Chat Wallpaper", "wallpaper color colour solid background", PAGE_WALL_COLOR)
        page("Bubble Color", ap, "bubble color colour chat color", PAGE_CHAT_COLOR)
        page("Bubble Styles", ap, "bubble style styles cute aesthetic pink lavender peach mint picture anime", PAGE_BUBBLE_STYLE)
        page("App Icon", ap, "app icon home screen icon alternate logo", PAGE_APP_ICON)
        page("Quick Reaction", ap, "quick reaction double tap emoji react heart like", PAGE_QUICK)
        row("Night Mode", ap, "night mode dark mode light mode theme system appearance", PAGE_APPEARANCE)
        // Chats
        val ch = listOf(CH)
        if (com.fariin.app.data.MsgPolicy.chatKeyVisible) page("Chat Key", ch, "chat key pin number code private contact me", PAGE_CHAT_KEY)
        row("Save to Photos", ch, "save to photos gallery camera roll auto save media", PAGE_CHATS)
        row("Link Previews", ch, "link previews url card website", PAGE_CHATS)
        row("Automatically Archive", ch, "automatically archive archived new chats chat key muted", PAGE_CHATS)
        row("Clear Chat History", ch, "clear chat history delete everything all messages erase", PAGE_CHATS)
        // Stories
        val st = listOf(ST)
        page("Everyone", st, "story audience everyone public", PAGE_STORY_EVERYONE)
        page("My Chats", st, "story audience my chats friends", PAGE_STORY_MYCHATS)
        row("View Receipts", st, "view receipts story views seen viewers", PAGE_STORIES)
        // Privacy & Security
        val pr = listOf(PR)
        page("Last seen & online", pr, "last seen online status presence $AUD", PAGE_AUDIENCE + "lastSeen")
        page("Profile photo", pr, "profile photo picture avatar $AUD", PAGE_AUDIENCE + "photo")
        page("Hide From", pr + "Profile photo", "hide from exceptions profile photo people", PAGE_HIDE_FROM)
        page("Bio", pr, "bio about description $AUD", PAGE_AUDIENCE + "bio")
        if (com.fariin.app.data.AppFeatures.groups) page("Groups & Channels", pr, "groups channels add me invite $AUD", PAGE_AUDIENCE + "groups")
        page("Messages", pr, "messages who can message me new people requests $AUD", PAGE_MESSAGES)
        row("Read Receipts", pr + "Messages", "read receipts blue ticks seen checks", PAGE_MESSAGES)
        row("Typing Indicators", pr + "Messages", "typing indicators is typing", PAGE_MESSAGES)
        page("Calls", pr, "calls who can call me voice video $AUD", PAGE_AUDIENCE + "calls")
        page("Blocked Users", pr, "blocked block users blocklist unblock", PAGE_BLOCKED)
        row("Block User", pr + "Blocked Users", "block user person add blocked", PAGE_BLOCKED)
        row("Always Relay Calls", pr, "always relay calls ip address hide server", PAGE_PRIVACY)
        page("Disappearing Messages", pr, "disappearing messages timer auto delete default", PAGE_DISAPPEAR_DEFAULT)
        if (com.fariin.app.crypto.KeyBackup.ENABLED) page("Backup PIN", pr, "backup pin restore messages reinstall new phone key", PAGE_KEY_BACKUP)
        page("App Lock", pr, "app lock fingerprint face unlock passcode pin lock", PAGE_APP_LOCK)
        row("Auto-Lock", pr + "App Lock", "auto lock timer immediately minutes", PAGE_APP_LOCK)
        row("Screen Security", pr + "App Lock", "screen security screenshot recent apps hide", PAGE_APP_LOCK)
        page("My Reports", pr, "my reports reported report status", PAGE_MY_REPORTS)
        row("Safety Tips", pr, "safety tips help private", PAGE_PRIVACY)
        // Storage and Data
        val sd = listOf(SD)
        page("Manage storage", sd, "manage storage space usage cache free up", PAGE_MANAGE_STORAGE)
        row("Keep Media", sd + "Manage storage", "keep media forever days auto delete old photos", PAGE_MANAGE_STORAGE)
        row("Clear Media", sd + "Manage storage", "clear media delete videos voice notes free space", PAGE_MANAGE_STORAGE)
        row("Clear Cache", sd + "Manage storage", "clear cache free space temporary", PAGE_MANAGE_STORAGE)
        for ((k, label) in SettingsRules.autoKinds)
            page(label, sd + "Media auto-download", "auto download automatic media wifi wi-fi mobile data cellular ${label.lowercase()}", PAGE_AUTODL + k)
        row("Reset Auto-Download Settings", sd, "reset auto download settings media", PAGE_STORAGE)
        page("Sent Media Quality", sd, "sent media quality hd high standard photo video resolution", PAGE_SENT_QUALITY)
        page("Use Less Data", sd, "use less data calls data saver low data mobile", PAGE_LESS_DATA)
        // Help & About
        val hp = listOf(HP)
        row("Help Center", hp, "help center support faq questions", PAGE_ABOUT)
        row("Version", hp, "version build number app version", PAGE_ABOUT)
        row("Privacy Policy", hp, "privacy policy legal", PAGE_ABOUT)
        row("Terms & Conditions", hp, "terms conditions service legal", PAGE_ABOUT)
    }

    /** Lowercased, accents folded, "&" read as "and". */
    internal fun fold(s: String): String =
        java.text.Normalizer.normalize(s.lowercase().replace("&", " and "), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").replace(Regex("\\s+"), " ").trim()

    private fun words(s: String) = s.split(Regex("[^\\p{L}\\p{N}@]+")).filter { it.isNotEmpty() }
    private fun startsAWord(q: String, text: String) = q.isNotEmpty() && words(text).any { it.startsWith(q) }

    /** iOS score: the title first (exact, start, a word's start, inside), then the words and path. */
    private fun score(e: Entry, q: String): Int {
        val title = fold(e.title)
        val rest = fold(e.keywords + " " + e.path.joinToString(" "))
        if (title == q) return 1000
        if (title.startsWith(q)) return 900
        if (startsAWord(q, title)) return 800
        if (title.contains(q)) return 600
        if (startsAWord(q, rest)) return 400
        if (rest.contains(q)) return 200
        val tokens = words(q)
        if (tokens.size <= 1) return 0
        val allWords = words("$title $rest")
        val all = "$title $rest"
        var s = 0
        for (t in tokens) {
            if (allWords.any { it.startsWith(t) }) s += 2
            else if (all.contains(t)) s += 1
            else return 0
        }
        return 100 + s
    }

    /** Empty: the pages one step deep. Typed: every page and row that matches, best first, a
     *  shallower one first among equals. */
    fun matching(query: String): List<Entry> {
        val all = entries
        val q = fold(query)
        if (q.isEmpty()) return all.filter { it.isPage && it.path.size <= 1 }
        return all.mapIndexedNotNull { i, e -> score(e, q).takeIf { it > 0 }?.let { Triple(it, i, e) } }
            .sortedWith(compareByDescending<Triple<Int, Int, Entry>> { it.first }.thenBy { it.third.path.size }.thenBy { it.second })
            .map { it.third }
    }
}

@Composable
private fun indexIcon(key: String): Painter = when (key) {
    "account" -> painterResource(R.drawable.ic_account)
    "devices" -> painterResource(R.drawable.ic_linked_devices)
    "notifications" -> painterResource(R.drawable.ic_notifications)
    "appearance" -> vec(ImageVector.vectorResource(R.drawable.ic_sf_paintbrush))
    "chats" -> painterResource(R.drawable.ic_settings_chats)
    "stories" -> painterResource(R.drawable.ic_stories_stack)
    "privacy" -> painterResource(R.drawable.ic_privacy)
    "storage" -> vec(ImageVector.vectorResource(R.drawable.ic_sf_server))
    else -> vec(ImageVector.vectorResource(R.drawable.ic_set_help))
}

/** Kept while a found page is open on top, so back returns to the same results (iOS). */
private val searchQuery = mutableStateOf("")

/** The Settings search button: a fresh search each time. */
internal fun openSettingsSearch(push: (String) -> Unit) { searchQuery.value = ""; push(PAGE_SETTINGS_SEARCH) }

@Composable
internal fun SettingsSearchPage(push: (String) -> Unit, pop: () -> Unit) {
    val c = Fariin.colors
    var query by searchQuery
    val results = SettingsIndex.matching(query)
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) {
        // Up with the page (iOS waits 0.15 s): a search page opened to be typed into.
        kotlinx.coroutines.delay(150)
        runCatching { focus.requestFocus() }
    }
    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            com.fariin.app.ui.components.FloatingSearchField(query, { query = it }, "Search",
                Modifier.weight(1f).focusRequester(focus))
            com.fariin.app.ui.components.FloatingIcon(ImageVector.vectorResource(R.drawable.ic_sf_xmark), "Close", onClick = pop)
        }
        if (results.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("No Results", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label)
                    Text("There were no results for \"${query.trim()}\".\nTry a new search.", fontSize = 15.sp, color = c.secondary, textAlign = TextAlign.Center)
                }
            }
        } else LazyColumn(Modifier.fillMaxSize()) {
            items(results, key = { it.id }) { e ->
                Row(Modifier.fillMaxWidth().clickable {
                    focusManager.clearFocus()
                    push(e.page)   // My Profile opens the My Profile page, as its row does
                }
                    .heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (e.icon != null) {
                        Box(Modifier.width(40.dp), contentAlignment = Alignment.CenterStart) {
                            Icon(indexIcon(e.icon), contentDescription = null, tint = c.secondary, modifier = Modifier.size(24.dp))
                        }
                        Spacer(Modifier.width(16.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(e.title, style = MaterialTheme.typography.bodyLarge, color = c.label)
                        e.crumb?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.secondary) }
                    }
                }
            }
        }
    }
}
