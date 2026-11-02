package com.fariin.app.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.R
import com.fariin.app.data.MessageDecryptor
import com.fariin.app.ui.chats.ChatListScreen
import com.fariin.app.ui.chats.ChatsUiState
import com.fariin.app.ui.chats.unreadChatsBadge
import com.fariin.app.ui.components.Avatar
import com.fariin.app.ui.theme.Fariin

/**
 * The four tabs in the iOS order (MainShell.swift, owner 2026-08-30): Stories, Calls, Chats,
 * Settings. Chats opens first. The Chats tab carries the number of unread chats.
 *
 * TODO: the Calls tab's missed-calls badge; my own photo as the Settings icon (iOS draws it
 * there, with the person circle until it loads); tapping the open tab again to scroll to top.
 */
@Composable
fun MainShell(
    me: String,
    chats: ChatsUiState,
    decryptor: MessageDecryptor,
    onSignOut: () -> Unit,
) {
    val c = Fariin.colors
    var tab by rememberSaveable { mutableIntStateOf(TAB_CHATS) }
    val unread = unreadChatsBadge(chats.chats, me)

    Scaffold(
        containerColor = c.bg,
        bottomBar = {
            Column {
                HorizontalDivider(thickness = 0.5.dp, color = c.separator)
                NavigationBar(containerColor = c.bg, tonalElevation = 0.dp) {
                    val colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = c.label,
                        selectedTextColor = c.label,
                        unselectedIconColor = c.secondary,
                        unselectedTextColor = c.secondary,
                        indicatorColor = c.placeholderFill,
                    )
                    NavigationBarItem(
                        selected = tab == TAB_STORIES, onClick = { tab = TAB_STORIES }, colors = colors,
                        icon = {
                            Icon(painterResource(if (tab == TAB_STORIES) R.drawable.ic_stories_fill else R.drawable.ic_stories),
                                contentDescription = null, modifier = Modifier.size(25.dp))
                        },
                        label = { TabLabel("Stories") },
                    )
                    NavigationBarItem(
                        selected = tab == TAB_CALLS, onClick = { tab = TAB_CALLS }, colors = colors,
                        icon = { Icon(Icons.Filled.Call, contentDescription = null) },
                        label = { TabLabel("Calls") },
                    )
                    NavigationBarItem(
                        selected = tab == TAB_CHATS, onClick = { tab = TAB_CHATS }, colors = colors,
                        icon = {
                            BadgedBox(badge = {
                                if (unread > 0) Badge(containerColor = c.red, contentColor = androidx.compose.ui.graphics.Color.White) {
                                    Text(if (unread > 99) "99+" else "$unread")
                                }
                            }) {
                                Icon(painterResource(R.drawable.ic_chat), contentDescription = null, modifier = Modifier.size(25.dp))
                            }
                        },
                        label = { TabLabel("Chats") },
                    )
                    NavigationBarItem(
                        selected = tab == TAB_SETTINGS, onClick = { tab = TAB_SETTINGS }, colors = colors,
                        icon = { Icon(Icons.Filled.AccountCircle, contentDescription = null) },
                        label = { TabLabel("Settings") },
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(bottom = pad.calculateBottomPadding())) {
            when (tab) {
                TAB_STORIES -> Placeholder("Stories", "Stories are coming to Android soon.", null)
                TAB_CALLS -> Placeholder("Calls", "Calls are coming to Android soon.", Icons.Filled.Call)
                TAB_CHATS -> ChatListScreen(chats, me, decryptor)
                else -> SettingsPlaceholder(onSignOut)
            }
        }
    }
}

@Composable
private fun TabLabel(text: String) = Text(text, fontSize = 11.sp, fontWeight = FontWeight.Medium)

@Composable
private fun Placeholder(title: String, body: String, icon: ImageVector?) {
    val c = Fariin.colors
    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
        LargeTitle(title)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(body, fontSize = 15.sp, color = c.secondary)
        }
    }
}

/** TODO: the real Settings page (profile, privacy, devices, and the rest of iOS SettingsView). */
@Composable
private fun SettingsPlaceholder(onSignOut: () -> Unit) {
    val c = Fariin.colors
    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
        LargeTitle("Settings")
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Avatar(null, 96.dp, modifier = Modifier.padding(top = 32.dp))
            TextButton(onClick = onSignOut, modifier = Modifier.padding(top = 24.dp)) {
                Text("Log Out", fontSize = 17.sp, color = c.red)
            }
        }
    }
}

@Composable
private fun LargeTitle(text: String) {
    Text(
        text, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Fariin.colors.label,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

const val TAB_STORIES = 0
const val TAB_CALLS = 1
const val TAB_CHATS = 2
const val TAB_SETTINGS = 3
