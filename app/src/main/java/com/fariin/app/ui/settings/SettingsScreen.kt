package com.fariin.app.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.BatterySaver
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.R
import com.fariin.app.data.AppAppearance
import com.fariin.app.data.AuthRepository
import com.fariin.app.data.AppearancePrefs
import com.fariin.app.data.MyProfile
import com.fariin.app.data.MyProfileRepository
import com.fariin.app.ui.components.Avatar
import com.fariin.app.ui.theme.Fariin

/**
 * The Settings tab, in the iOS SettingsView order: my photo, name and @username with Edit
 * profile / Share profile under them, then Account and Devices; Notifications, Appearance,
 * Chats, Stories, Privacy & Security, Storage and Data; Invite Friends, Safety, Help & About.
 *
 * Real pages: Account (Security notifications, Your Account Data, Delete Account, Log out), Devices,
 * Notifications, Appearance > Night Mode / Quick Reaction, Chats, Stories, Privacy & Security
 * (PrivacyPages.kt), Storage and Data (MorePages.kt), Help & About and Safety. Pages outside
 * Settings' scope (Search, Share profile, Invite Friends, Email address, Username, wallpaper,
 * chat colour, app icon) still open a "Coming soon on Android" page.
 *
 * TODO: Subscriptions (iOS shows it to @v only), the admin sections, Settings search, my QR.
 */
@Composable
fun SettingsTab(me: String, demoProfile: MyProfile?, onSignOut: () -> Unit) {
    val profile = if (demoProfile != null) {
        demoProfile
    } else {
        val repo = remember { MyProfileRepository() }
        val flow = remember(me) { repo.listen(me) }
        flow.collectAsState(initial = null).value
    }
    // A small stack of pages inside the tab; the system back pops it.
    var stack by rememberSaveable { mutableStateOf(listOf<String>()) }
    val push: (String) -> Unit = { stack = stack + it }
    val pop: () -> Unit = { stack = stack.dropLast(1) }
    BackHandler(enabled = stack.isNotEmpty(), onBack = pop)
    // A second tap on Settings: back to the first page, then to the top.
    val homeScroll = rememberScrollState()
    com.fariin.app.ui.shell.OnTabReselect {
        if (stack.isNotEmpty()) stack = emptyList() else homeScroll.animateScrollTo(0)
    }

    val page = stack.lastOrNull()
    // The grey page under the white section cards (iOS grouped background).
    Box(Modifier.fillMaxSize().background(Fariin.colors.bgSecondary)) {
        when {
            page == null -> SettingsHome(profile, push, homeScroll)
            page == PAGE_ACCOUNT -> AccountPage(profile, demoProfile != null, push, pop, onSignOut)
            page == PAGE_APPEARANCE -> AppearancePage(push, pop)
            page == PAGE_NIGHT -> NightModePage(pop)
            page == PAGE_POWER -> PowerSavingPage(pop)
            page == PAGE_ABOUT -> HelpAboutPage(pop)
            // Sign-in pages (ui/auth/PasswordPages.kt).
            page == PAGE_PASSWORD -> com.fariin.app.ui.auth.PasswordPage(remember { AuthRepository() }, pop, onForgot = { push(PAGE_RESET + it) }, demoOpen = demoProfile != null)
            page.startsWith(PAGE_RESET) == true -> com.fariin.app.ui.auth.ResetCodePage(remember { AuthRepository() }, page.removePrefix(PAGE_RESET), pop,
                onDone = { stack = stack.dropLast(2) }, demo = demoProfile != null)
            page == PAGE_DELETE -> DeleteAccountPage(profile, pop, onSignOut)
            privacyRoute(page, push, pop) -> Unit
            moreRoute(page, push, pop) -> Unit
            page == PAGE_PASSKEYS -> com.fariin.app.ui.auth.PasskeysPage(remember { AuthRepository() }, pop, demoRows = if (demoProfile != null) emptyList() else null)
            else -> ComingSoonPage(page.removePrefix(SOON), pop)
        }
    }
}

private const val PAGE_ACCOUNT = "account"
private const val PAGE_APPEARANCE = "appearance"
private const val PAGE_NIGHT = "night"
private const val PAGE_ABOUT = "about"
private const val PAGE_PASSWORD = "auth:password"
private const val PAGE_RESET = "auth:reset:"
private const val PAGE_PASSKEYS = "auth:passkeys"
private const val SOON = "soon:"
private fun soon(title: String) = SOON + title

/** Opens a web page in the phone's browser. */
internal fun openWeb(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@Composable
private fun SettingsHome(profile: MyProfile?, push: (String) -> Unit, scroll: androidx.compose.foundation.ScrollState) {
    val ctx = LocalContext.current
    val c = Fariin.colors
    Column(Modifier.fillMaxSize()) {
        PageBar("Settings", onBack = null, action = {
            IconButton(onClick = { push(soon("Search")) }) {
                Icon(Icons.Filled.Search, contentDescription = "Search settings", tint = c.label)
            }
        })
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(bottom = com.fariin.app.ui.shell.bottomBarInset())) {
            val people = com.fariin.app.ui.people.LocalPeopleNav.current
            ProfileHeader(profile, onEdit = people.editProfile, onShare = { push(soon("Share profile")) })
            Spacer(Modifier.height(8.dp))
            SettingsSection {
                SettingsRow("Account", painterResource(R.drawable.ic_account)) { push(PAGE_ACCOUNT) }
                SettingsRow("Devices", painterResource(R.drawable.ic_linked_devices), last = true) { push(PAGE_DEVICES) }
            }
            SettingsSection {
                SettingsRow("Notifications", painterResource(R.drawable.ic_notifications)) { push(PAGE_NOTIFS) }
                SettingsRow("Appearance", vec(Icons.Outlined.Brush)) { push(PAGE_APPEARANCE) }
                SettingsRow("Chats", painterResource(R.drawable.ic_settings_chats)) { push(PAGE_CHATS) }
                SettingsRow("Stories", painterResource(R.drawable.ic_stories_stack)) { push(PAGE_STORIES) }
                SettingsRow("Privacy & Security", painterResource(R.drawable.ic_privacy)) { push(PAGE_PRIVACY) }
                SettingsRow("Storage and Data", vec(Icons.Outlined.Dns)) { push(PAGE_STORAGE) }
                SettingsRow("Power Saving", vec(Icons.Outlined.BatterySaver), last = true) { push(PAGE_POWER) }
            }
            SettingsSection {
                SettingsRow("Invite Friends", painterResource(R.drawable.ic_invite_friends), trailing = Trailing.NONE) {
                    push(soon("Invite Friends"))
                }
                SettingsRow("Safety", vec(Icons.Outlined.VerifiedUser), trailing = Trailing.NONE) {
                    openWeb(ctx, "https://fariin.com/safety")
                }
                SettingsRow("Help & About", vec(Icons.Outlined.HelpOutline), last = true) { push(PAGE_ABOUT) }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** Android profile header: photo at the start, name and @username beside it, then the two
 *  iOS actions (Edit profile, Share profile) as tonal buttons. */
@Composable
private fun ProfileHeader(profile: MyProfile?, onEdit: () -> Unit, onShare: () -> Unit) {
    val c = Fariin.colors
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(profile?.photoUrl, 72.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(profile?.name?.ifEmpty { null } ?: "You", style = androidx.compose.material3.MaterialTheme.typography.titleLarge, color = c.label)
                val h = profile?.handle.orEmpty()
                if (h.isNotEmpty()) Text("@$h", style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, color = c.secondary)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Capsule("Edit profile", onEdit, Modifier.weight(1f))
            Capsule("Share profile", onShare, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Capsule(title: String, onClick: () -> Unit, modifier: Modifier) {
    androidx.compose.material3.FilledTonalButton(onClick = onClick, modifier = modifier) { Text(title) }
}

/** iOS AccountSettingsView: Sign-in, Security, Account (Your Account Data, Log out). */
@Composable
private fun AccountPage(
    profile: MyProfile?, demo: Boolean, push: (String) -> Unit, pop: () -> Unit, onSignOut: () -> Unit,
) {
    val c = Fariin.colors
    val ctx = LocalContext.current
    val exportScope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    // iOS shows Password only when the account has an address a person can type.
    val hasEmail = demo || runCatching {
        !com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.email.isNullOrEmpty()
    }.getOrDefault(false)
    Column(Modifier.fillMaxSize()) {
        PageBar("Account", onBack = pop, action = {
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More", tint = c.label)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Delete Account", color = c.red) },
                        onClick = { menu = false; push(PAGE_DELETE) },
                    )
                }
            }
        })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = com.fariin.app.ui.shell.bottomBarInset()).padding(top = 8.dp)) {
            SettingsSection(header = "Sign-in") {
                SettingsRow("Email address", last = !hasEmail) { push(soon("Email address")) }
                if (hasEmail) SettingsRow("Password", last = true) { push(PAGE_PASSWORD) }
            }
            SettingsSection(
                header = "Security",
                footer = "Passkeys sign you in with your screen lock. Security notifications email you about new sign-ins.",
            ) {
                val handle = profile?.handle
                SettingsRow(
                    "Username",
                    value = when { profile == null -> ""; handle.isNullOrEmpty() -> "Not set"; else -> "@$handle" },
                ) { push(soon("Username")) }
                SettingsRow("Passkeys") { push(PAGE_PASSKEYS) }
                SettingsRow("Security notifications", last = true) { push(PAGE_SEC_NOTIFS) }
            }
            SettingsSection(
                header = "Account",
                footer = "Saves your profile, devices and privacy settings to a text file you can share or keep. Messages are not included.",
            ) {
                SettingsRow("Your Account Data", trailing = Trailing.NONE) {
                    exportScope.launch {
                        try { AccountReport.share(ctx) } catch (e: Exception) { toast(ctx, e.message ?: "Could not load everything. Check your connection and try again.") }
                    }
                }
                SettingsRow("Log out", trailing = Trailing.NONE, titleColor = c.red, last = true) { confirm = true }
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Log out?") },
            text = { Text("You'll need to log in again to use Fariin on this device.") },
            confirmButton = {
                TextButton(onClick = { confirm = false; onSignOut() }) { Text("Log Out", color = c.red) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel", color = c.label) } },
            containerColor = c.card,
            titleContentColor = c.label,
            textContentColor = c.label,
        )
    }
}

/**
 * iOS AppearanceSettingsView. The chat theme preview and cards above these rows are not built
 * yet (TODO); Chat Wallpaper, Chat Color, App Icon and Quick Reaction open "Coming soon".
 */
@Composable
private fun AppearancePage(push: (String) -> Unit, pop: () -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        PageBar("Appearance", onBack = pop)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = com.fariin.app.ui.shell.bottomBarInset()).padding(top = 8.dp)) {
            SettingsSection {
                SettingsRow("Chat Wallpaper") { push(soon("Chat Wallpaper")) }
                SettingsRow("Chat Color") { push(soon("Chat Color")) }
                SettingsRow("App Icon") { push(soon("App Icon")) }
                SettingsRow("Quick Reaction", last = true) { push(PAGE_QUICK) }
            }
            SettingsSection {
                SettingsRow("Night Mode", value = AppearancePrefs.current(ctx).label, last = true) { push(PAGE_NIGHT) }
            }
        }
    }
}

/** iOS NightModePage: three cards, System / Light / Dark, and one line under them. */
@Composable
private fun NightModePage(pop: () -> Unit) {
    val ctx = LocalContext.current
    val c = Fariin.colors
    val current = AppearancePrefs.current(ctx)
    Column(Modifier.fillMaxSize()) {
        PageBar("Night Mode", onBack = pop)
        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                for (mode in AppAppearance.entries) {
                    val sel = mode == current
                    Column(
                        Modifier.weight(1f).clickable { AppearancePrefs.set(ctx, mode) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val shape = RoundedCornerShape(16.dp)
                        Box(
                            Modifier.fillMaxWidth().height(74.dp).clip(shape)
                                .border(if (sel) 2.dp else 1.dp, if (sel) c.accent else c.label.copy(alpha = 0.08f), shape),
                        ) { ModeThumb(mode) }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            mode.label, fontSize = 13.sp, color = if (sel) c.accent else c.secondary,
                            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
            Spacer(Modifier.height(22.dp))
            Text("System follows your device setting.", fontSize = 13.sp, color = c.secondary)
        }
    }
}

/** A tiny chat drawn in the mode's colours; System is split light and dark. */
@Composable
private fun ModeThumb(mode: AppAppearance) {
    @Composable
    fun half(dark: Boolean, modifier: Modifier) {
        val bg = if (dark) Color(0xFF121214) else Color.White
        val them = if (dark) Color(0xFF26262B) else Color(0xFFF2F2F2)
        val mine = if (dark) Color.White else Color.Black
        Column(modifier.background(bg).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.width(34.dp).height(10.dp).clip(CircleShape).background(them))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                Box(Modifier.width(28.dp).height(10.dp).clip(CircleShape).background(mine))
            }
            Box(Modifier.width(22.dp).height(10.dp).clip(CircleShape).background(them))
        }
    }
    when (mode) {
        AppAppearance.LIGHT -> half(false, Modifier.fillMaxSize())
        AppAppearance.DARK -> half(true, Modifier.fillMaxSize())
        AppAppearance.SYSTEM -> Row(Modifier.fillMaxSize()) {
            half(false, Modifier.weight(1f).fillMaxSize())
            half(true, Modifier.weight(1f).fillMaxSize())
        }
    }
}

/** iOS AboutView, titled "Help & About". */
@Composable
private fun HelpAboutPage(pop: () -> Unit) {
    val ctx = LocalContext.current
    val version = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "1.0"
    }
    Column(Modifier.fillMaxSize()) {
        PageBar("Help & About", onBack = pop)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = com.fariin.app.ui.shell.bottomBarInset()).padding(top = 8.dp)) {
            SettingsSection(
                header = "Help",
                footer = "Fariin has zero tolerance for objectionable content or abusive behavior. Reports are reviewed within 24 hours.",
            ) {
                SettingsRow("Help Center", trailing = Trailing.OUT_LINK, last = true) { openWeb(ctx, "https://fariin.com/help") }
            }
            SettingsSection(
                header = "About",
                footer = "One-to-one messages and calls are end-to-end encrypted. Made for Somalia.",
            ) {
                SettingsRow("Version", value = version, trailing = Trailing.NONE)
                SettingsRow("Privacy Policy", trailing = Trailing.OUT_LINK) { openWeb(ctx, "https://fariin.com/privacy") }
                SettingsRow("Terms & Conditions", trailing = Trailing.OUT_LINK, last = true) { openWeb(ctx, "https://fariin.com/terms") }
            }
        }
    }
}

/** The stand-in for a page the Android app does not have yet. */
@Composable
internal fun ComingSoonPage(title: String, pop: () -> Unit) {
    val c = Fariin.colors
    Column(Modifier.fillMaxSize()) {
        PageBar(title, onBack = pop)
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Coming soon on Android", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = c.label)
                Spacer(Modifier.height(8.dp))
                Text(
                    "This page is on iPhone already and is being built for Android.",
                    fontSize = 15.sp, color = c.secondary, textAlign = TextAlign.Center,
                )
            }
        }
    }
}

