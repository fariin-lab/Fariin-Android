package com.fariin.app.ui.settings

import androidx.compose.animation.togetherWith
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
import androidx.compose.material.icons.outlined.Search
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import com.fariin.app.ui.components.floating
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import com.fariin.app.ui.components.brush
import androidx.compose.ui.graphics.drawscope.clipPath
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
 * (PrivacyPages.kt), Storage and Data (MorePages.kt), Help & About and Safety, Settings search
 * (SettingsSearch.kt), Share profile = my QR code and the scanner, Invite Friends (QrPages.kt),
 * Chat Wallpaper and Chat Color (LookPages.kt), App Icon (AppIconPage.kt).
 *
 * TODO: Subscriptions (iOS shows it to @v only), the admin sections.
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
    // A page asked for from outside Settings (the chat list's Things you can do > Chat color).
    val asked by SettingsLink.requests.collectAsState()
    LaunchedEffect(asked) { asked?.let { stack = listOf(it); SettingsLink.requests.value = null } }
    // A second tap on Settings: back to the first page, then to the top.
    val homeScroll = rememberScrollState()
    com.fariin.app.ui.shell.OnTabReselect {
        if (stack.isNotEmpty()) stack = emptyList() else homeScroll.animateScrollTo(0)
    }

    val page = stack.lastOrNull()
    // A Settings page hides the tab bar, the home list shows it (iOS).
    androidx.compose.runtime.DisposableEffect(page == null) {
        com.fariin.app.ui.shell.TabBarVisibility.hidden = page != null
        onDispose { com.fariin.app.ui.shell.TabBarVisibility.hidden = false }
    }
    // The grey page under the white section cards (iOS grouped background).
    Box(Modifier.fillMaxSize().background(Fariin.colors.bgSecondary)) {
      // Pages move as the chats do (Motion): a short slide and a crossfade, so the tapped row's
      // grey is still there while the next page arrives (it used to swap with no motion at all).
      androidx.compose.animation.AnimatedContent(targetState = stack, label = "settings", transitionSpec = {
        val m = com.fariin.app.ui.components.Motion
        val forward = targetState.size >= initialState.size
        when {
            !m.on -> androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None
            // Opening: the new page comes in on top, the one under it stands still.
            forward -> m.pageIn() togetherWith m.pageStill()
            // Back: the top page leaves, drawn above the page it uncovers.
            else -> (androidx.compose.animation.EnterTransition.None togetherWith m.pageOut()).apply { targetContentZIndex = -1f }
        }
      }) { shownStack ->
        val page = shownStack.lastOrNull()
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
            page == PAGE_USERNAME -> UsernamePage(me, profile, pop)
            accountSecurityRoute(page, push, pop, drop = { n -> stack = stack.dropLast(n) }) -> Unit
            privacyRoute(page, push, pop) -> Unit
            securityCheckupRoute(page, profile, demoProfile != null, push, pop, drop = { n -> stack = stack.dropLast(n) }) -> Unit
            moreRoute(page, push, pop) -> Unit
            lookRoute(page, push, pop) -> Unit
            page == PAGE_SETTINGS_SEARCH -> SettingsSearchPage(push, pop)
            page == PAGE_APP_ICON -> AppIconPage(pop)
            page == PAGE_GLOW_PROFILE -> com.fariin.app.ui.glow.GlowProfilePage(me, profile,
                onEdit = com.fariin.app.ui.people.LocalPeopleNav.current.editProfile, onBack = pop)
            page == PAGE_SCAN -> ScanQrPage(onMyCode = { pop(); myQrOpen.value = true }, onClose = pop, onDone = pop)
            page == PAGE_PASSKEYS -> com.fariin.app.ui.auth.PasskeysPage(remember { AuthRepository() }, pop, demoRows = if (demoProfile != null) emptyList() else null)
            else -> ComingSoonPage(page.removePrefix(SOON), pop)
        }
        }
      }
    }
}

internal const val PAGE_ACCOUNT = "account"
/** My Profile (iOS GlowProfileView), behind the myProfileCard switch. */
private const val PAGE_GLOW_PROFILE = "glow:profile"
internal const val PAGE_APPEARANCE = "appearance"
private const val PAGE_NIGHT = "night"
internal const val PAGE_ABOUT = "about"
internal const val PAGE_PASSWORD = "auth:password"
private const val PAGE_RESET = "auth:reset:"
internal const val PAGE_PASSKEYS = "auth:passkeys"
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
        PageBar(com.fariin.app.ui.chats.rememberConnectionTitle("Settings"), onBack = null, action = {
            // In its own floating circle, as the other tabs' bar buttons.
            com.fariin.app.ui.components.FloatingIcon(androidx.compose.material.icons.Icons.Outlined.Search, "Search settings",
                onClick = { openSettingsSearch(push) }, modifier = Modifier.padding(end = 8.dp))
        })
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(bottom = com.fariin.app.ui.shell.bottomBarInset())) {
            val people = com.fariin.app.ui.people.LocalPeopleNav.current
            ProfileHeader(profile, onEdit = people.editProfile, onShare = { myQrOpen.value = true })
            if (myQrOpen.value) MyQrSheet(profile, onClose = { myQrOpen.value = false }, onScan = { push(PAGE_SCAN) })
            Spacer(Modifier.height(8.dp))
            // My Profile, its own card under the header (iOS; hidden unless the switch is on).
            if (com.fariin.app.data.AppFeatures.myProfileCard) SettingsSection {
                SettingsRow("My Profile", painterResource(R.drawable.ic_account), last = true) { push(PAGE_GLOW_PROFILE) }
            }
            SettingsSection {
                SettingsRow("Account", painterResource(R.drawable.ic_account)) { push(PAGE_ACCOUNT) }
                SettingsRow("Devices", painterResource(R.drawable.ic_linked_devices), last = true) { push(PAGE_DEVICES) }
            }
            SettingsSection {
                SettingsRow("Notifications", painterResource(R.drawable.ic_notifications)) { push(PAGE_NOTIFS) }
                SettingsRow("Appearance", painterResource(R.drawable.ic_set_appearance)) { push(PAGE_APPEARANCE) }
                SettingsRow("Chats", painterResource(R.drawable.ic_settings_chats)) { push(PAGE_CHATS) }
                SettingsRow("Stories", painterResource(R.drawable.ic_stories_stack)) { push(PAGE_STORIES) }
                SettingsRow("Privacy & Security", painterResource(R.drawable.ic_privacy)) { push(PAGE_PRIVACY) }
                SettingsRow("Storage and Data", painterResource(R.drawable.ic_set_storage)) { push(PAGE_STORAGE) }
                SettingsRow("Power Saving", painterResource(R.drawable.ic_set_power), last = true) { push(PAGE_POWER) }
            }
            SettingsSection {
                SettingsRow("Invite Friends", painterResource(R.drawable.ic_invite_friends), trailing = Trailing.NONE) {
                    shareInvite(ctx, profile?.handle)
                }
                SettingsRow("Safety", painterResource(R.drawable.ic_set_safety), trailing = Trailing.NONE) {
                    openWeb(ctx, "https://fariin.com/safety")
                }
                SettingsRow("Help & About", painterResource(R.drawable.ic_set_help), last = true) { push(PAGE_ABOUT) }
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
    // A floating white capsule with the blue title (iOS glass buttons), not Material's tonal fill.
    Box(modifier.height(44.dp).floating(androidx.compose.foundation.shape.CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) {
        Text(title, style = androidx.compose.material3.MaterialTheme.typography.labelLarge, color = Fariin.colors.accent)
    }
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
    val hasTwoStep = !demo && rememberHasTwoStep()
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
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, shape = com.fariin.app.ui.components.FariinMenu.shape, border = com.fariin.app.ui.components.FariinMenu.border, containerColor = com.fariin.app.ui.components.FariinMenu.container) {
                    DropdownMenuItem(
                        text = { Text("Delete Account", color = c.red) },
                        onClick = { menu = false; push(PAGE_DELETE) },
                    )
                }
            }
        })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = com.fariin.app.ui.shell.bottomBarInset()).padding(top = 8.dp)) {
            SettingsSection(header = "Sign-in") {
                SettingsRow("Email address", last = !hasEmail) { push(PAGE_EMAIL) }
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
                ) { push(PAGE_USERNAME) }
                SettingsRow("Passkeys") { push(PAGE_PASSKEYS) }
                // iOS: hidden while the feature is off (Flags.twoStepEnabled), except for an account
                // that already has it on, so it can be turned off again.
                if (hasTwoStep) SettingsRow("Two-step verification") { push(PAGE_TWOSTEP) }
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
 * yet (TODO).
 */
@Composable
private fun AppearancePage(push: (String) -> Unit, pop: () -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        PageBar("Appearance", onBack = pop)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = com.fariin.app.ui.shell.bottomBarInset()).padding(top = 8.dp)) {
            SettingsSection(header = "Chat Theme") { ChatThemeBlock() }
            SettingsSection {
                SettingsRow("Chat Wallpaper") { push(PAGE_WALLPAPER) }
                val lookV = com.fariin.app.data.ChatLookStore.version.intValue
                val myColor = remember(lookV) { com.fariin.app.data.ChatLookStore.globalColor(ctx) }
                val blue = com.fariin.app.ui.theme.Fariin.colors.blue
                SettingsRow("Chat Color", accessory = {
                    Box(Modifier.size(26.dp).clip(androidx.compose.foundation.shape.CircleShape)
                        .background(myColor?.brush() ?: androidx.compose.ui.graphics.SolidColor(blue)))
                }) { push(PAGE_CHAT_COLOR) }
                SettingsRow("App Icon") { push(PAGE_APP_ICON) }
                val quick = remember { ctx.getSharedPreferences(CHAT_PREFS, android.content.Context.MODE_PRIVATE).getString(SettingsRules.QUICK_KEY, "❤️") ?: "❤️" }
                SettingsRow("Quick Reaction", value = quick, last = true) { push(PAGE_QUICK) }
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
                            Modifier.fillMaxWidth().height(84.dp).clip(shape)
                                .border(if (sel) 2.5.dp else 1.dp, if (sel) c.label else c.label.copy(alpha = 0.08f), shape),
                        ) { ModeThumb(mode) }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            mode.label, fontSize = 15.sp, color = if (sel) c.label else c.secondary,
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

/** iOS NightModeView's tiles: a grey or black card, a bubble bar and a blue bar; System split on a diagonal. */
@Composable
private fun ModeThumb(mode: AppAppearance) {
    val blue = Fariin.colors.blue
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        fun scene(dark: Boolean) {
            drawRect(if (dark) Color(0xFF1A1A1A) else Color(0xFFEDEDED))
            val r = androidx.compose.ui.geometry.CornerRadius(h * 0.09f)
            drawRoundRect(if (dark) Color(0xFF3A3A3C) else Color.White,
                androidx.compose.ui.geometry.Offset(w * 0.11f, h * 0.28f), androidx.compose.ui.geometry.Size(w * 0.49f, h * 0.18f), r)
            drawRoundRect(blue, androidx.compose.ui.geometry.Offset(w * 0.40f, h * 0.56f), androidx.compose.ui.geometry.Size(w * 0.49f, h * 0.18f), r)
        }
        when (mode) {
            AppAppearance.LIGHT -> scene(false)
            AppAppearance.DARK -> scene(true)
            AppAppearance.SYSTEM -> {
                scene(false)
                val cut = androidx.compose.ui.graphics.Path().apply {
                    moveTo(w * 0.62f, 0f); lineTo(w, 0f); lineTo(w, h); lineTo(w * 0.38f, h); close()
                }
                clipPath(cut) { scene(true) }
            }
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

/** Settings > Account > Username: the people module's username page (claimUsername). */
@Composable
private fun UsernamePage(me: String, profile: MyProfile?, pop: () -> Unit) {
    val ctx = LocalContext.current
    val api = remember(me) { com.fariin.app.data.FirestorePeople(me, com.fariin.app.data.CryptoHub.get(ctx)) }
    Box(Modifier.fillMaxSize().padding(bottom = com.fariin.app.ui.shell.bottomBarInset())) {
        com.fariin.app.ui.people.UsernameScreen(api, profile?.handle.orEmpty(), onBack = pop)
    }
}

/** Opens one Settings page from elsewhere in the app; the Settings tab picks it up when it shows. */
object SettingsLink {
    val requests = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    fun request(page: String) { requests.value = page }
}
