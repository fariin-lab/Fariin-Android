package com.fariin.app.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.launch
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
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
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
 * navigation bar; the content runs to the bottom of the screen and scrolls behind it. Fariin's own
 * icons with their labels under them (as iOS), the open tab on a soft grey pill. Glass (a blur of only the strip behind the pill) on
 * Android 12+ when the phone is not LOW and Power Saving allows blur; else a solid translucent
 * surface with a hairline border, which costs nothing extra to draw.
 *
 * TODO: the Calls tab's missed-calls badge; tapping the open tab again to scroll to top.
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
    // The launcher's New Story shortcut (system/SystemRouter): the same as the camera button.
    val launcherStory by com.fariin.app.system.SystemRouter.storyRequests.collectAsState()
    var launcherSeen by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(launcherStory) { if (launcherStory > launcherSeen) { launcherSeen = launcherStory; storyRequest++; tab = TAB_STORIES } }
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
                    // The calls list scrolls under the bar and lifts its New Call button by the inset.
                    TAB_CALLS -> CallsTab(me, chats.chats, if (demo) DemoSettings.calls() else null)
                    TAB_CHATS -> ChatListScreen(chats, me, decryptor,
                        onSettings = { select(TAB_SETTINGS) },
                        onNewStory = { storyRequest++; select(TAB_STORIES) })
                    else -> SettingsTab(me, if (demo) DemoSettings.profile else null, onSignOut)
                }
            }
        }
        // My own photo is the Settings tab's icon (iOS settingsTabLabel).
        val myPhoto = if (demo) DemoSettings.profile.photoUrl else {
            val repo = remember { com.fariin.app.data.MyProfileRepository() }
            val flow = remember(me) { repo.listen(me) }
            flow.collectAsState(initial = null).value?.photoUrl
        }
        FloatingTabBar(
            tab = tab, unread = unread, myPhoto = myPhoto, glass = glass, haze = haze, animate = power.animations, onSelect = select,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 24.dp, vertical = BarMargin),
        )
    }
}

/**
 * Fariin's own tab glyphs, the same drawings iOS uses (owner 2026-11-02: not the stock icons).
 * Like iOS, each is drawn filled whether or not its tab is open; the open tab is marked by the
 * soft pill behind icon and label. Settings has no glyph: it is my own photo (null = [Avatar]'s
 * grey silhouette).
 */
private class TabSpec(val id: Int, val label: String, @androidx.annotation.DrawableRes val icon: Int?)

private val Tabs = listOf(
    TabSpec(TAB_CHATS, "Chats", com.fariin.app.R.drawable.ic_chat),
    TabSpec(TAB_STORIES, "Stories", com.fariin.app.R.drawable.ic_stories_stack_fill),
    TabSpec(TAB_CALLS, "Calls", com.fariin.app.R.drawable.ic_tab_calls),
    TabSpec(TAB_SETTINGS, "Settings", null),
)

@Composable
private fun FloatingTabBar(
    tab: Int, unread: Int, myPhoto: String?, glass: Boolean, haze: HazeState, animate: Boolean,
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
    } else Modifier.background(surface.copy(alpha = 0.97f))
    // One pill slides under the open tab. Its two edges follow on different springs, so it
    // stretches toward the new tab and then settles, the liquid look. Only the draw phase reads
    // the edges: no recomposition while it moves. Snaps when Power Saving turns animations off.
    val target = Tabs.indexOfFirst { it.id == tab }.coerceAtLeast(0).toFloat()
    val lead = remember { Animatable(target) }
    val trail = remember { Animatable(target) }
    LaunchedEffect(target, animate) {
        if (!animate) { lead.snapTo(target); trail.snapTo(target); return@LaunchedEffect }
        launch { lead.animateTo(target, spring(dampingRatio = 0.72f, stiffness = 700f)) }
        launch { trail.animateTo(target, spring(dampingRatio = 0.86f, stiffness = 260f)) }
    }
    val pillColor = if (c.dark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.06f)
    Row(
        modifier
            .widthIn(max = 420.dp).fillMaxWidth().height(BarHeight)
            .shadow(if (c.dark) 0.dp else 4.dp, shape, clip = false)
            .clip(shape)
            .then(fill)
            .border(1.dp, border, shape)
            .drawBehind {
                val pad = 6.dp.toPx(); val gap = 4.dp.toPx(); val h = 52.dp.toPx()
                val slot = (size.width - pad * 2) / Tabs.size
                val from = minOf(lead.value, trail.value); val to = maxOf(lead.value, trail.value)
                drawRoundRect(pillColor,
                    topLeft = Offset(pad + from * slot + gap, (size.height - h) / 2),
                    size = Size((to - from + 1) * slot - gap * 2, h),
                    cornerRadius = CornerRadius(h / 2))
            }
            .padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tabs.forEach { t ->
            TabItem(t, selected = tab == t.id, badge = if (t.id == TAB_CHATS) unread else 0, photo = myPhoto, animate = animate,
                onClick = { onSelect(t.id) }, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun TabItem(t: TabSpec, selected: Boolean, badge: Int, photo: String?, animate: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val c = Fariin.colors
    val spec = if (animate) tween<Color>(180) else snap()
    // iOS: the open tab's icon and label in the text colour, the others in grey (the pill behind
    // it is drawn once by the bar and slides). The icon gives a small pop when its tab opens.
    val tint by animateColorAsState(if (selected) c.label else c.secondary, spec, label = "tabTint")
    val pop = remember { Animatable(1f) }
    LaunchedEffect(selected) {
        if (selected && animate) { pop.snapTo(0.86f); pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 500f)) }
    }
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
        androidx.compose.foundation.layout.Column(
            Modifier.widthIn(min = 64.dp).height(52.dp).padding(horizontal = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            BadgedBox(modifier = Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value }, badge = {
                if (badge > 0) Badge(containerColor = c.red, contentColor = Color.White) {
                    Text(if (badge > 99) "99+" else "$badge")
                }
            }) {
                if (t.icon != null) {
                    Icon(androidx.compose.ui.res.painterResource(t.icon), contentDescription = null, tint = tint, modifier = Modifier.size(25.dp))
                } else {
                    com.fariin.app.ui.components.Avatar(photo, 26.dp,
                        modifier = if (selected) Modifier.border(1.5.dp, c.label, androidx.compose.foundation.shape.CircleShape) else Modifier)
                }
            }
            Text(t.label, fontSize = 11.sp, lineHeight = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = tint, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
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
