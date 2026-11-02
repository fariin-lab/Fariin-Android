package com.fariin.app.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.DonutLarge
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.DonutLarge
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fariin.app.data.MessageDecryptor
import com.fariin.app.perf.PowerSaving
import com.fariin.app.perf.rememberPowerState
import com.fariin.app.ui.calls.CallsTab
import com.fariin.app.ui.chats.ChatListScreen
import com.fariin.app.ui.chats.ChatsUiState
import com.fariin.app.ui.chats.unreadChatsBadge
import com.fariin.app.ui.debug.DemoChats
import com.fariin.app.ui.debug.DemoSettings
import com.fariin.app.ui.settings.SettingsTab
import com.fariin.app.ui.stories.StoriesTab
import com.fariin.app.ui.theme.Fariin
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource

/**
 * How much room the floating bar takes at the bottom of a tab (the bar, its margin and the system
 * navigation bar). Scrolling lists add this as bottom content padding so the last row can be
 * scrolled clear of the bar; FABs sit above it. 0 outside the tabs.
 */
val LocalBottomBarInset = staticCompositionLocalOf { 0.dp }

private val BarHeight = 64.dp
private val BarMargin = 12.dp

/**
 * The four tabs in the Android order (owner 2026-11-02, after the big Android messengers):
 * Chats on the far left and opened first, then Stories, Calls, Settings. The Chats tab carries
 * the number of unread chats.
 *
 * The bar is a floating pill above the content with a margin from the screen edges and the system
 * navigation bar; the content runs to the bottom of the screen and scrolls behind it. Icons only,
 * the open tab filled on a tinted pill. Glass (a blur of only the strip behind the pill) on
 * Android 12+ when the phone is not LOW and Power Saving allows blur; else a solid translucent
 * surface with a hairline border, which costs nothing extra to draw.
 *
 * TODO: the Calls tab's missed-calls badge; my own photo as the Settings icon; tapping the open
 * tab again to scroll to top; the Calls list still stops above the bar (ui/calls owns its list).
 */
@Composable
fun MainShell(
    me: String,
    chats: ChatsUiState,
    decryptor: MessageDecryptor,
    onSignOut: () -> Unit,
) {
    val c = Fariin.colors
    val context = LocalContext.current
    remember { PowerSaving.init(context) }
    val power = rememberPowerState()
    val glass = power.glass()
    var tab by rememberSaveable { mutableIntStateOf(TAB_CHATS) }
    val unread = unreadChatsBadge(chats.chats, me)
    val demo = me == DemoChats.ME   // debug demo mode: made-up data in every tab

    // The chat list's camera button opens the Stories tab straight into its photo picker.
    var storyRequest by rememberSaveable { mutableIntStateOf(0) }
    val haptics = LocalHapticFeedback.current
    val select: (Int) -> Unit = { t ->
        if (t != tab) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        tab = t
    }
    // System back on another tab goes to Chats first, then leaves the app (Android tab apps).
    BackHandler(enabled = tab != TAB_CHATS) { tab = TAB_CHATS }

    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val inset = BarHeight + BarMargin * 2 + navBottom
    val haze = remember { HazeState() }

    Box(Modifier.fillMaxSize().background(c.bg)) {
        Box(Modifier.fillMaxSize().then(if (glass) Modifier.hazeSource(haze) else Modifier)) {
            CompositionLocalProvider(LocalBottomBarInset provides inset) {
                when (tab) {
                    TAB_STORIES -> StoriesTab(me, chats.chats, if (demo) DemoSettings.stories() else null, storyRequest)
                    // ui/calls draws its own list; it stops above the bar until it reads the inset.
                    TAB_CALLS -> Box(Modifier.fillMaxSize().padding(bottom = inset)) {
                        CallsTab(me, chats.chats, if (demo) DemoSettings.calls() else null)
                    }
                    TAB_CHATS -> ChatListScreen(chats, me, decryptor,
                        onSettings = { select(TAB_SETTINGS) },
                        onNewStory = { storyRequest++; select(TAB_STORIES) })
                    else -> SettingsTab(me, if (demo) DemoSettings.profile else null, onSignOut)
                }
            }
        }
        FloatingTabBar(
            tab = tab, unread = unread, glass = glass, haze = haze, animate = power.animations, onSelect = select,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 24.dp, vertical = BarMargin),
        )
    }
}

private class TabSpec(val id: Int, val label: String, val filled: ImageVector, val outlined: ImageVector)

private val Tabs = listOf(
    TabSpec(TAB_CHATS, "Chats", Icons.AutoMirrored.Filled.Chat, Icons.AutoMirrored.Outlined.Chat),
    TabSpec(TAB_STORIES, "Stories", Icons.Filled.DonutLarge, Icons.Outlined.DonutLarge),
    TabSpec(TAB_CALLS, "Calls", Icons.Filled.Call, Icons.Outlined.Call),
    TabSpec(TAB_SETTINGS, "Settings", Icons.Filled.Settings, Icons.Outlined.Settings),
)

@Composable
private fun FloatingTabBar(
    tab: Int, unread: Int, glass: Boolean, haze: HazeState, animate: Boolean,
    onSelect: (Int) -> Unit, modifier: Modifier = Modifier,
) {
    val c = Fariin.colors
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(BarHeight / 2)
    val surface = scheme.surfaceContainer
    val border = if (c.dark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.08f)
    val fill = if (glass) {
        Modifier.hazeEffect(haze) {
            backgroundColor = c.bg
            blurRadius = 24.dp
            noiseFactor = 0f
            tints = listOf(HazeTint(surface.copy(alpha = if (c.dark) 0.62f else 0.70f)))
        }
    } else Modifier.background(surface.copy(alpha = 0.92f))
    Row(
        modifier
            .widthIn(max = 420.dp).fillMaxWidth().height(BarHeight)
            .shadow(if (c.dark) 0.dp else 4.dp, shape, clip = false)
            .clip(shape)
            .then(fill)
            .border(1.dp, border, shape)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tabs.forEach { t ->
            TabItem(t, selected = tab == t.id, badge = if (t.id == TAB_CHATS) unread else 0, animate = animate,
                onClick = { onSelect(t.id) }, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun TabItem(t: TabSpec, selected: Boolean, badge: Int, animate: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val c = Fariin.colors
    val scheme = MaterialTheme.colorScheme
    val spec = if (animate) tween<Color>(180) else snap()
    val pill by animateColorAsState(if (selected) scheme.secondaryContainer else Color.Transparent, spec, label = "tabPill")
    val tint by animateColorAsState(if (selected) scheme.onSecondaryContainer else c.secondary, spec, label = "tabTint")
    val desc = if (badge > 0) "${t.label}, $badge unread" else t.label
    Box(
        modifier.fillMaxHeight()
            .semantics { contentDescription = desc; this.selected = selected }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = false, radius = 28.dp),
                role = Role.Tab, onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(width = 64.dp, height = 40.dp).clip(RoundedCornerShape(20.dp)).background(pill), contentAlignment = Alignment.Center) {
            BadgedBox(badge = {
                if (badge > 0) Badge(containerColor = c.red, contentColor = Color.White) {
                    Text(if (badge > 99) "99+" else "$badge")
                }
            }) {
                Icon(if (selected) t.filled else t.outlined, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
            }
        }
    }
}

/** Bottom room a scrolling page adds so its last row clears the floating bar. */
@Composable
fun bottomBarInset(): Dp = LocalBottomBarInset.current

const val TAB_CHATS = 0
const val TAB_STORIES = 1
const val TAB_CALLS = 2
const val TAB_SETTINGS = 3
