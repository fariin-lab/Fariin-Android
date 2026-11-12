package com.fariin.app.ui.settings

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.fariin.app.ui.components.frostedTopBar
import com.fariin.app.ui.photo.profilePhotoSource
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.fariin.app.data.NewBadgeSpots
import androidx.compose.animation.togetherWith
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import androidx.compose.foundation.layout.statusBarsPadding
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
import com.fariin.app.ui.components.underBar
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
 * Subscriptions (SubscriptionsPage.kt): a preview for @v only, as iOS.
 * TODO: the admin sections.
 */
@Composable
fun SettingsTab(me: String, demoProfile: MyProfile?, onSignOut: () -> Unit,
                /** False while another tab is open (MainShell keeps this one alive behind it). */
                active: Boolean = true) {
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
    BackHandler(enabled = active && stack.isNotEmpty(), onBack = pop)
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
    // Hidden behind another tab, it leaves the bar alone.
    androidx.compose.runtime.DisposableEffect(page == null, active) {
        if (active) com.fariin.app.ui.shell.TabBarVisibility.hidden = page != null
        onDispose { com.fariin.app.ui.shell.TabBarVisibility.hidden = false }
    }
    // The grey page under the white section cards (iOS grouped background).
    Box(Modifier.fillMaxSize().background(Fariin.colors.bgSecondary)) {
      // Pages move as the chats do (Motion): the iOS push, solid pages, parallax underneath.
      androidx.compose.animation.AnimatedContent(targetState = stack, label = "settings", transitionSpec = {
        val m = com.fariin.app.ui.components.Motion
        val forward = targetState.size >= initialState.size
        when {
            !m.on -> androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None
            // Opening: the new page comes in on top, the one under it stands still.
            forward -> m.pageIn() togetherWith m.pageUnder()
            // Back: the top page slides off, drawn above the page it uncovers, which drifts back in.
            else -> (m.pageBackUnder() togetherWith m.pageOut()).apply { targetContentZIndex = -1f }
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
            page == PAGE_USERNAME -> UsernamePage(me, profile, pop)
            page == PAGE_CONTACT_USERNAME -> MessagesPrivacyPage(push, pop, usernameMode = true, handle = profile?.handle)
            accountSecurityRoute(page, push, pop, drop = { n -> stack = stack.dropLast(n) }) -> Unit
            privacyRoute(page, push, pop) -> Unit
            securityCheckupRoute(page, profile, demoProfile != null, push, pop, drop = { n -> stack = stack.dropLast(n) }) -> Unit
            moreRoute(page, push, pop) -> Unit
            lookRoute(page, push, pop) -> Unit
            page == PAGE_SETTINGS_SEARCH -> SettingsSearchPage(push, pop)
            page == PAGE_APP_ICON -> AppIconPage(pop)
            page == PAGE_SUBSCRIPTIONS -> SubscriptionsPage(pop)
            page == PAGE_BUBBLE_STYLE -> BubbleStylePage(onClose = pop)
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
internal const val PAGE_GLOW_PROFILE = "glow:profile"
internal const val PAGE_APPEARANCE = "appearance"
private const val PAGE_NIGHT = "night"
internal const val PAGE_ABOUT = "about"
internal const val PAGE_PASSWORD = "auth:password"
private const val PAGE_RESET = "auth:reset:"
internal const val PAGE_PASSKEYS = "auth:passkeys"
private const val SOON = "soon:"
private fun soon(title: String) = SOON + title

/** Opens a web page in Fariin's own browser (as iOS: links stay in the app). */
internal fun openWeb(context: Context, url: String) {
    runCatching { com.fariin.app.ui.browser.BrowserActivity.open(context, url) }
}

@Composable
private fun SettingsHome(profile: MyProfile?, push: (String) -> Unit, scroll: androidx.compose.foundation.ScrollState) {
    val ctx = LocalContext.current
    val c = Fariin.colors
    // iOS SettingsView (owner 2026-11-05): the title centred, the search in its floating circle, and
    // no solid bar: the page scrolls under a soft fade instead of being cut by a hard edge.
    val haze = androidx.compose.runtime.remember { dev.chrisbanes.haze.HazeState() }
    // The glass only once the page is under the bar (iOS scroll edge), faded in and out.
    // derivedStateOf: the page is not recomposed for every pixel scrolled, only when this flips.
    val underBar by androidx.compose.runtime.remember { androidx.compose.runtime.derivedStateOf { scroll.value > 0 } }
    val glassAlpha by com.fariin.app.ui.components.rememberEdgeAlpha(underBar)
    val glass = com.fariin.app.perf.rememberPowerState().glass()
    // My photo, opened from the circle (iOS SettingsView: ProfilePhotoViewer landsSquare): it grows
    // out of the circle and lands as the whole picture, square. Drawn over everything (TopOverlay),
    // the tab bar hidden while it is up and the bar reading "Profile photo" with its X.
    val photo = com.fariin.app.ui.photo.rememberProfilePhotoState()
    val shownPhotoUrl by androidx.compose.runtime.rememberUpdatedState(profile?.photoUrl)
    if (photo.open) androidx.compose.runtime.DisposableEffect(Unit) {
        val page: @Composable () -> Unit = {
            com.fariin.app.ui.photo.ProfilePhotoViewer(photo, shownPhotoUrl,
                style = com.fariin.app.ui.photo.ProfilePhotoStyle(landsSquare = true),
                bar = com.fariin.app.ui.photo.ProfilePhotoBar(height = 48.dp, title = "Profile photo"))
        }
        com.fariin.app.ui.components.TopOverlay.content = page
        com.fariin.app.ui.shell.TabBarVisibility.hidden = true
        onDispose {
            if (com.fariin.app.ui.components.TopOverlay.content === page) com.fariin.app.ui.components.TopOverlay.content = null
            com.fariin.app.ui.shell.TabBarVisibility.hidden = false
        }
    }
    Box(Modifier.fillMaxSize().background(c.bgSecondary)) {
        val barH = 48.dp   // a bit shorter (owner 2026-11-08)
        Column(Modifier.fillMaxSize().then(if (glass) Modifier.hazeSource(haze) else Modifier)
            .verticalScroll(scroll).statusBarsPadding().padding(top = barH)
            .padding(bottom = com.fariin.app.ui.shell.bottomBarInset())) {
            val people = com.fariin.app.ui.people.LocalPeopleNav.current
            ProfileHeader(profile, photo, onEdit = people.editProfile, onShare = { myQrOpen.value = true })
            if (myQrOpen.value) MyQrSheet(profile, onClose = { myQrOpen.value = false }, onScan = { push(PAGE_SCAN) })
            Spacer(Modifier.height(8.dp))
            // The admin panel's "New" pills (config/features newBadges).
            // My Profile, its own card under the header (iOS; hidden unless the switch is on).
            if (com.fariin.app.data.AppFeatures.myProfileCard) SettingsSection {
                SettingsRow("My Profile", painterResource(R.drawable.ic_account), last = true,
                    badge = NewBadgeSpots.MY_PROFILE) { push(PAGE_GLOW_PROFILE) }
            }
            SettingsSection {
                // Subscriptions, first in this card, for @v alone while it is being built (iOS,
                // owner 2026-10-27). The page is a preview: nothing on it is sold or switched on.
                if (showsSubscriptions(profile?.handle)) SettingsRow("Subscriptions", painterResource(R.drawable.ic_star_outline),
                    badge = NewBadgeSpots.SUBSCRIPTIONS) { push(PAGE_SUBSCRIPTIONS) }
                SettingsRow("Account", painterResource(R.drawable.ic_account),
                    badge = NewBadgeSpots.ACCOUNT) { push(PAGE_ACCOUNT) }
                SettingsRow("Devices", painterResource(R.drawable.ic_linked_devices), last = true,
                    badge = NewBadgeSpots.DEVICES) { push(PAGE_DEVICES) }
            }
            SettingsSection {
                SettingsRow("Notifications", painterResource(R.drawable.ic_notifications),
                    badge = NewBadgeSpots.NOTIFICATIONS) { push(PAGE_NOTIFS) }
                SettingsRow("Appearance", painterResource(R.drawable.ic_set_appearance),
                    badge = NewBadgeSpots.APPEARANCE) { push(PAGE_APPEARANCE) }
                SettingsRow("Chats", painterResource(R.drawable.ic_settings_chats),
                    badge = NewBadgeSpots.CHATS) { push(PAGE_CHATS) }
                // Its own row under Chats (owner 2026-11-12, as iOS).
                if (com.fariin.app.backup.ChatBackup.ENABLED) SettingsRow("Chat Backup", painterResource(R.drawable.ic_set_backup)) { push(PAGE_CHAT_BACKUP) }
                SettingsRow("Stories", painterResource(R.drawable.ic_stories_stack),
                    badge = NewBadgeSpots.STORIES) { push(PAGE_STORIES) }
                SettingsRow("Privacy & Security", painterResource(R.drawable.ic_privacy),
                    badge = NewBadgeSpots.PRIVACY) { push(PAGE_PRIVACY) }
                SettingsRow("Storage and Data", painterResource(R.drawable.ic_set_storage),
                    badge = NewBadgeSpots.STORAGE) { push(PAGE_STORAGE) }
                SettingsRow("Power Saving", painterResource(R.drawable.ic_set_power), last = true) { push(PAGE_POWER) }
            }
            SettingsSection {
                SettingsRow("Invite Friends", painterResource(R.drawable.ic_invite_friends), trailing = Trailing.NONE,
                    badge = NewBadgeSpots.INVITE) {
                    shareInvite(ctx, profile?.handle)
                }
                SettingsRow("Safety", painterResource(R.drawable.ic_set_safety), trailing = Trailing.NONE,
                    badge = NewBadgeSpots.SAFETY) {
                    openWeb(ctx, "https://fariin.com/safety")
                }
                SettingsRow("Help & About", painterResource(R.drawable.ic_set_help), last = true,
                    badge = NewBadgeSpots.HELP) { push(PAGE_ABOUT) }
            }
            Spacer(Modifier.height(12.dp))
        }
        // The bar (owner 2026-11-05 "high end, smooth"): frosted glass over the rows, the same blur as
        // the Chats bar, strongest at the top and melting away at its lower edge, so there is no line.
        // Power Saving without glass: the page colour fading to clear instead.
        // Owner 2026-11-05 (his screenshot): the blur's lower end showed as a line, and the glass
        // switching on at the first scroll made a hitch. Now the glass is always on (nothing switches
        // while scrolling), and the whole effect, blur and tint together, fades out over a long eased
        // tail under the title, so the eye can not find where it ends.
        val fade = androidx.compose.ui.graphics.Brush.verticalGradient(
            0f to Color.Black, 0.50f to Color.Black, 0.62f to Color.Black.copy(alpha = 0.85f),
            0.74f to Color.Black.copy(alpha = 0.55f), 0.85f to Color.Black.copy(alpha = 0.25f),
            0.93f to Color.Black.copy(alpha = 0.08f), 1f to Color.Transparent)
        // The tab pages' one bar (owner 2026-11-08): frosted, melting away below its edge.
        // Solid like the Chats tab's (owner 2026-11-11 left Settings to me: the same bar on both).
        Box(Modifier.fillMaxWidth().then(Modifier.frostedTopBar(haze, c.bgSecondary, glass = false))
            .statusBarsPadding().height(barH)) {
            // The title and Search step aside while my photo is open (the viewer's bar is there).
            Box(Modifier.fillMaxWidth().height(barH).padding(horizontal = 8.dp)
                .graphicsLayer { alpha = if (photo.chromeHidden) 0f else 1f }) {
                Text(com.fariin.app.ui.chats.rememberConnectionTitle("Settings"), fontSize = 18.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = c.label,
                    modifier = Modifier.align(Alignment.Center))
                com.fariin.app.ui.components.FloatingIcon(ImageVector.vectorResource(R.drawable.ic_search_ios), "Search settings",
                    onClick = { openSettingsSearch(push) }, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp))
            }
        }
    }
}

/** iOS SettingsView header (owner 2026-11-05, his iPhone beside the Pixel): the photo large and
 *  centred, the name bold under it with the verified mark, @username under that, then the two
 *  actions (Edit profile, Share profile). */
@Composable
private fun ProfileHeader(profile: MyProfile?, photo: com.fariin.app.ui.photo.ProfilePhotoState,
                          onEdit: () -> Unit, onShare: () -> Unit) {
    val c = Fariin.colors
    Column(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Named for screen readers (it was a nameless button): the photo, and what a tap does.
            // iOS: a photo on screen opens it; no photo is not a dead tap, it opens the editor.
            Box(Modifier.clip(androidx.compose.foundation.shape.CircleShape)
                .clickable(onClickLabel = if (photo.hasPhoto) "View photo" else "Edit profile") {
                    if (!photo.show()) onEdit()
                }
                .semantics { contentDescription = "Your profile photo" }) {
                Avatar(profile?.photoUrl, 120.dp, onResolved = photo::resolved,
                    modifier = Modifier.profilePhotoSource(photo))
            }
            Spacer(Modifier.height(12.dp))
            // iOS: the picture opens the photo, the name and @username under it open Edit.
            Column(Modifier.clickable(onClickLabel = "Edit profile", onClick = onEdit),
                horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(profile?.name?.ifEmpty { null } ?: "You", fontSize = 26.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = c.label, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (profile?.verified == true) {
                    Spacer(Modifier.width(6.dp))
                    com.fariin.app.ui.components.VerifiedSeal(c.blue, Modifier.size(22.dp), "Verified")
                }
            }
            val h = profile?.handle.orEmpty()
            if (h.isNotEmpty()) Text("@$h", fontSize = 17.sp, color = c.secondary, modifier = Modifier.padding(top = 2.dp))
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
    // A solid capsule in the cards' colour, dimmed while pressed (iOS ProfileCapsuleButtonStyle).
    // No glass (owner 2026-11-11: "remove the liquid glass" on these two).
    val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val down by press.collectIsPressedAsState()
    val dim by androidx.compose.animation.core.animateFloatAsState(if (down) 0.55f else 1f,
        androidx.compose.animation.core.tween(150), label = "capsulePress")
    Box(modifier.height(44.dp).graphicsLayer { alpha = dim }
        .clip(androidx.compose.foundation.shape.CircleShape).background(Fariin.colors.card)
        .clickable(press, null, onClick = onClick),
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
    var deleting by remember { mutableStateOf(false) }
    val hasTwoStep = !demo && rememberHasTwoStep()
    androidx.compose.runtime.SideEffect { SettingsIndex.hasTwoStep = hasTwoStep }
    // iOS shows Password only when the account has an address a person can type.
    val hasEmail = demo || runCatching {
        !com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.email.isNullOrEmpty()
    }.getOrDefault(false)
    val barScroll = rememberScrollState()
    // Delete Account lives in the ••• menu at the top (iOS SettingsView toolbar Menu), not as a red row
    // beside Log out, where one could be taken for the other (owner 2026-11-08).
    var more by remember { mutableStateOf(false) }
    BarPage("Account", onBack = pop, under = underBar(barScroll), action = if (demo) null else ({
        Box {
            IconButton(onClick = { more = true }, modifier = com.fariin.app.ui.components.barGlass()) {
                Icon(ImageVector.vectorResource(R.drawable.ic_more_ios), contentDescription = "More", tint = c.label)
            }
            com.fariin.app.ui.components.GlassDropdown(expanded = more, onDismissRequest = { more = false },
                shape = com.fariin.app.ui.components.FariinMenu.shape, border = com.fariin.app.ui.components.FariinMenu.border,
                containerColor = com.fariin.app.ui.components.FariinMenu.container) {
                androidx.compose.material3.DropdownMenuItem(text = { Text("Delete Account", color = c.red) },
                    leadingIcon = { Icon(ImageVector.vectorResource(R.drawable.ic_sf_trash), null, tint = c.red) },
                    onClick = { more = false; deleting = true })
            }
        }
    })) { top ->
        Column(Modifier.fillMaxSize().verticalScroll(barScroll).padding(bottom = com.fariin.app.ui.shell.bottomBarInset()).padding(top = top + 8.dp)) {
            SettingsSection(header = "Sign-in") {
                SettingsRow("Email address", last = !hasEmail, badge = NewBadgeSpots.ACCOUNT_EMAIL) { push(PAGE_EMAIL) }
                if (hasEmail) SettingsRow("Password", last = true, badge = NewBadgeSpots.ACCOUNT_PASSWORD) { push(PAGE_PASSWORD) }
            }
            SettingsSection(
                header = "Security",
                footer = "Passkeys sign you in with your screen lock. Security notifications email you about new sign-ins.",
            ) {
                val handle = profile?.handle
                SettingsRow(
                    "Username",
                    value = when { profile == null -> ""; handle.isNullOrEmpty() -> "Not set"; else -> "@$handle" },
                    badge = NewBadgeSpots.ACCOUNT_USERNAME,
                ) { push(PAGE_CONTACT_USERNAME) }   // iOS: the "Contact me by username" page first
                SettingsRow("Passkeys", badge = NewBadgeSpots.ACCOUNT_PASSKEYS) { push(PAGE_PASSKEYS) }
                // iOS: hidden while the feature is off (Flags.twoStepEnabled), except for an account
                // that already has it on, so it can be turned off again.
                if (hasTwoStep) SettingsRow("Two-step verification", badge = NewBadgeSpots.ACCOUNT_TWO_STEP) { push(PAGE_TWOSTEP) }
                SettingsRow("Security notifications", last = true, badge = NewBadgeSpots.ACCOUNT_SECURITY_NOTIFICATIONS) { push(PAGE_SEC_NOTIFS) }
            }
            SettingsSection(
                header = "Account",
                footer = "Saves your profile, devices and privacy settings to a text file you can share or keep. Messages are not included.",
            ) {
                val exporting by AccountReport.exporting.collectAsState()
                SettingsRow("Your Account Data", trailing = Trailing.NONE, last = true,
                    badge = NewBadgeSpots.ACCOUNT_ACCOUNT_DATA,
                    accessory = if (exporting) ({ com.fariin.app.ui.components.FariinSpinner(size = 20.dp) }) else null) {
                    if (exporting) return@SettingsRow
                    exportScope.launch {
                        try { AccountReport.share(ctx) } catch (e: Exception) { toast(ctx, e.message ?: "Could not load everything. Check your connection and try again.") }
                    }
                }
            }
            // Alone at the bottom, no note under it; the note above belongs to Your Account Data only.
            SettingsSection {
                SettingsRow("Log out", icon = androidx.compose.ui.res.painterResource(com.fariin.app.R.drawable.ic_log_out), trailing = Trailing.NONE, titleColor = c.red, last = true,
                    badge = NewBadgeSpots.ACCOUNT_LOG_OUT) { confirm = true }
            }
        }
    }
    if (deleting) DeleteAccountFlow(profile, onDone = { deleting = false }, onSignOut = onSignOut)
    if (confirm) LogOutSheet(profile, onDismiss = { confirm = false }, onSignOut = onSignOut)
}

/**
 * iOS AppearanceSettingsView. The chat theme preview and cards above these rows are not built
 * yet (TODO).
 */
@Composable
private fun AppearancePage(push: (String) -> Unit, pop: () -> Unit) {
    val ctx = LocalContext.current
    val barScroll = rememberScrollState()
    BarPage("Appearance", onBack = pop, under = underBar(barScroll)) { top ->
        Column(Modifier.fillMaxSize().verticalScroll(barScroll).padding(bottom = com.fariin.app.ui.shell.bottomBarInset()).padding(top = top + 8.dp)) {
            SettingsSection(header = "Chat Theme") { ChatThemeBlock() }
            SettingsSection {
                SettingsRow("Chat Wallpaper", badge = NewBadgeSpots.APPEARANCE_CHAT_WALLPAPER) { push(PAGE_WALLPAPER) }
                // No Bubble Color row (owner 2026-11-07, as iOS): colours are picked on Bubble Style's
                // Color chip and in the chat's wallpaper sheet.
                if (com.fariin.app.data.BubbleStyles.SHOWN) {
                    val bubbleV = com.fariin.app.data.BubbleStyles.version.intValue
                    val bubbleName = remember(bubbleV) { com.fariin.app.data.BubbleStyles.init(ctx); com.fariin.app.data.BubbleStyles.name(com.fariin.app.data.BubbleStyles.current()) }
                    SettingsRow("Bubble Style", value = bubbleName, badge = NewBadgeSpots.APPEARANCE_BUBBLE_STYLES) { push(PAGE_BUBBLE_STYLE) }
                }
                SettingsRow("App Icon", badge = NewBadgeSpots.APPEARANCE_APP_ICON) { push(PAGE_APP_ICON) }
                val quick = remember { ctx.getSharedPreferences(CHAT_PREFS, android.content.Context.MODE_PRIVATE).getString(SettingsRules.QUICK_KEY, "❤️") ?: "❤️" }
                SettingsRow("Quick Reaction", value = quick, last = true, badge = NewBadgeSpots.APPEARANCE_QUICK_REACTION) { push(PAGE_QUICK) }
            }
            SettingsSection {
                SettingsRow("Night Mode", value = AppearancePrefs.current(ctx).label, last = true,
                    badge = NewBadgeSpots.APPEARANCE_NIGHT_MODE) { push(PAGE_NIGHT) }
            }
            // Moving wallpapers have no switch and no amount setting (owner 2026-11-06: the big apps have
            // none): a moving one always moves; to stop it, pick another. Only the phone's "Remove animations" stills it.
        }
    }
}

/** iOS NightModePage: three cards, System / Light / Dark, and one line under them. */
@Composable
private fun NightModePage(pop: () -> Unit) {
    val ctx = LocalContext.current
    val c = Fariin.colors
    val current = AppearancePrefs.current(ctx)
    BarPage("Night Mode", onBack = pop) { top ->
        Column(Modifier.fillMaxWidth().padding(top = top).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                for (mode in AppAppearance.entries) {
                    val sel = mode == current
                    // No grey ripple square on tap (owner 2026-11-05 photo); the tile dips a little
                    // under the finger instead, as iOS.
                    val press = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    val down by press.collectIsPressedAsState()
                    val k by androidx.compose.animation.core.animateFloatAsState(if (down) 0.95f else 1f, label = "modePress")
                    Column(
                        Modifier.weight(1f).graphicsLayer { scaleX = k; scaleY = k }
                            .clickable(interactionSource = press, indication = null) { AppearancePrefs.set(ctx, mode) },
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
    var appInfo by remember { mutableStateOf(false) }
    val version = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "1.0"
    }
    if (appInfo) { AppInfoPage(version) { appInfo = false }; return }
    val barScroll = rememberScrollState()
    BarPage("Help & About", onBack = pop, under = underBar(barScroll)) { top ->
        Column(Modifier.fillMaxSize().verticalScroll(barScroll).padding(bottom = com.fariin.app.ui.shell.bottomBarInset()).padding(top = top + 8.dp)) {
            SettingsSection(
                header = "Help",
                footer = "Fariin has zero tolerance for objectionable content or abusive behavior. Reports are reviewed within 24 hours.",
            ) {
                SettingsRow("Help Center", trailing = Trailing.OUT_LINK, last = true, badge = NewBadgeSpots.HELP_HELP_CENTER) { openWeb(ctx, "https://fariin.com/help") }
            }
            SettingsSection(
                header = "About",
                footer = "One-to-one messages and calls are end-to-end encrypted. Made for Somalia.",
            ) {
                SettingsRow("App info") { appInfo = true }
                SettingsRow("Privacy Policy", trailing = Trailing.OUT_LINK, badge = NewBadgeSpots.HELP_PRIVACY_POLICY) { openWeb(ctx, "https://fariin.com/privacy") }
                SettingsRow("Terms & Conditions", trailing = Trailing.OUT_LINK, last = true,
                    badge = NewBadgeSpots.HELP_TERMS) { openWeb(ctx, "https://fariin.com/terms") }
            }
        }
    }
}

/** The stand-in for a page the Android app does not have yet. */
@Composable
internal fun ComingSoonPage(title: String, pop: () -> Unit) {
    val c = Fariin.colors
    BarPage(title, onBack = pop) { top ->
        Box(Modifier.fillMaxSize().padding(top = top).padding(32.dp), contentAlignment = Alignment.Center) {
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
