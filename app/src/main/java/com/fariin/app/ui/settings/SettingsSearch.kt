package com.fariin.app.ui.settings

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.HelpOutline
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
/** "My Profile" opens the profile editor, which is not a Settings page. */
internal const val PAGE_MY_PROFILE = "settings:myprofile"

/** iOS SettingsIndex.entries, same titles, words and order. [icon] is a key for the row's icon. */
internal object SettingsIndex {
    data class Entry(val title: String, val icon: String?, val keywords: String, val page: String, val parent: String? = null)

    val entries: List<Entry> = listOf(
        Entry("Account", "account", "account name username id sign out delete", PAGE_ACCOUNT),
        Entry("My Profile", "account", "profile bio photo edit stories", PAGE_MY_PROFILE),
        Entry("Devices", "devices", "devices sessions linked signed in log out sign out", PAGE_DEVICES),
        Entry("Notifications", "notifications", "notifications push sound vibrate preview", PAGE_NOTIFS),
        Entry("Appearance", "appearance", "appearance theme dark light", PAGE_APPEARANCE),
        Entry("Chats", "chats", "chats save to photos camera roll clear chat history delete everything", PAGE_CHATS),
        Entry("Stories", "stories", "stories status view receipts", PAGE_STORIES),
        Entry("Privacy & Security", "privacy", "privacy security read receipts typing last seen app lock screen", PAGE_PRIVACY),
        Entry("Storage and Data", "storage", "storage data cache download network usage", PAGE_STORAGE),
        // "Chat Color" is the Bubble Style page on its Colours chip (the two pages merged, 2026-11-07).
        Entry("Chat Color", null, "chat color colour bubble", PAGE_CHAT_COLOR, parent = "Appearance"),
        Entry("Bubble Style", null, "bubble style styles colours picture", PAGE_BUBBLE_STYLE, parent = "Appearance"),
        Entry("Chat Key", null, "chat key pin number code private", PAGE_CHAT_KEY, parent = "Chats"),
        Entry("Email address", null, "email address change mail", PAGE_EMAIL, parent = "Account"),
        Entry("Passkeys", null, "passkeys passkey face id sign in key", PAGE_PASSKEYS, parent = "Account"),
        Entry("Two-step verification", null, "two step verification 2fa password code security", PAGE_TWOSTEP, parent = "Account"),
        Entry("Security notifications", null, "security notifications alerts new login", PAGE_SEC_NOTIFS, parent = "Account"),
        Entry("App Lock", null, "app lock face id passcode", PAGE_APP_LOCK, parent = "Privacy & Security"),
        Entry("Blocked Users", null, "blocked block users", PAGE_BLOCKED, parent = "Privacy & Security"),
        Entry("Help & About", "help", "help about version", PAGE_ABOUT),
    )

    /** iOS matching: trimmed, lowercased; the title contains it or the keywords do. */
    fun matching(query: String): List<Entry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return entries
        return entries.filter { it.title.lowercase().contains(q) || it.keywords.contains(q) }
    }
}

@Composable
private fun indexIcon(key: String): Painter = when (key) {
    "account" -> painterResource(R.drawable.ic_account)
    "devices" -> painterResource(R.drawable.ic_linked_devices)
    "notifications" -> painterResource(R.drawable.ic_notifications)
    "appearance" -> vec(Icons.Outlined.Brush)
    "chats" -> painterResource(R.drawable.ic_settings_chats)
    "stories" -> painterResource(R.drawable.ic_stories_stack)
    "privacy" -> painterResource(R.drawable.ic_privacy)
    "storage" -> vec(Icons.Outlined.Dns)
    else -> vec(Icons.Outlined.HelpOutline)
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
    val people = com.fariin.app.ui.people.LocalPeopleNav.current
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
            com.fariin.app.ui.components.FloatingIcon(Icons.Filled.Close, "Close", onClick = pop)
        }
        if (results.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("No Results", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label)
                    Text("There were no results for \"${query.trim()}\".\nTry a new search.", fontSize = 15.sp, color = c.secondary, textAlign = TextAlign.Center)
                }
            }
        } else LazyColumn(Modifier.fillMaxSize()) {
            items(results, key = { it.title }) { e ->
                Row(Modifier.fillMaxWidth().clickable {
                    focusManager.clearFocus()
                    if (e.page == PAGE_MY_PROFILE) people.editProfile() else push(e.page)
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
                        if (e.parent != null) Text(e.parent, style = MaterialTheme.typography.bodySmall, color = c.secondary)
                    }
                }
            }
        }
    }
}
