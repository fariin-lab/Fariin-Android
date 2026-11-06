package com.fariin.app.ui.shell

import androidx.compose.ui.layout.layout
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
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

/**
 * How much room the floating bar takes at the bottom of a tab (the bar, its margin and the system
 * navigation bar). Scrolling lists add this as bottom content padding so the last row can be
 * scrolled clear of the bar; FABs sit above it. 0 outside the tabs.
 */
val LocalBottomBarInset = staticCompositionLocalOf { 0.dp }

/** A page pushed inside a tab hides the tab bar while it is open (iOS hides it on a pushed page). */
object TabBarVisibility {
    var hidden by androidx.compose.runtime.mutableStateOf(false)
}

/** Counts taps on the tab that is already open; the open tab reads it through [OnTabReselect]. */
val LocalTabReselect = compositionLocalOf { 0 }

/**
 * A second tap on the open tab (iOS, and the big Android apps): [action] runs, which goes back to
 * the tab's first page and scrolls to the top. Not run when the tab first shows.
 */
@Composable
fun OnTabReselect(action: suspend () -> Unit) {
    val n = LocalTabReselect.current
    val seen = remember { n }
    LaunchedEffect(n) { if (n != seen) action() }
}

// The reference app's Android sizes (owner 2026-11-03: "exactly like" it): a 56 bar, 6 off the
// gesture bar, 32 in from each side.
private val BarHeight = 56.dp
private val BarMargin = 6.dp
private val BarSide = 32.dp

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
 */
@Composable
fun MainShell(
    me: String,
    chats: ChatsUiState,
    decryptor: MessageDecryptor,
    onSignOut: () -> Unit,
    /** Retry on the chat list's error page. */
    onRetryChats: () -> Unit = {},
) {
    val c = Fariin.colors
    val context = LocalContext.current
    remember { PowerSaving.init(context) }
    val power = rememberPowerState()
    val glass = power.glass()
    var tab by rememberSaveable { mutableIntStateOf(TAB_CHATS) }
    val unread = unreadChatsBadge(chats.chats, me)
    // The call history, read here so the Calls tab's missed badge counts from any tab (iOS
    // MainShell callsRepo). Each chat's calls are re-read only when that chat moves.
    val callsRepo = remember { com.fariin.app.data.CallsRepository() }
    var calls by remember { mutableStateOf<List<com.fariin.app.data.CallEntry>?>(null) }
    // A failed read (a plain reason) and Retry's counter: one more read only when tapped.
    var callsError by remember { mutableStateOf<String?>(null) }
    var callsRetry by remember { mutableIntStateOf(0) }
    if (me != DemoChats.ME) {
        val callKey = chats.chats.joinToString(",") { it.id + ":" + it.updatedAtMillis }
        LaunchedEffect(me, callKey, callsRetry) {
            if (!chats.loading) { calls = callsRepo.load(me, chats.chats); callsError = callsRepo.failed }
        }
    }
    var callsMark by remember { mutableStateOf(com.fariin.app.data.CallsSeen.load(context)) }
    // On the Calls tab every loaded missed call is seen (iOS markCallsSeen).
    LaunchedEffect(tab, calls) {
        val list = calls
        if (tab == TAB_CALLS && list != null) {
            val m = com.fariin.app.data.CallsSeen.seen(list, me, callsMark)
            if (m != callsMark) { callsMark = m; com.fariin.app.data.CallsSeen.save(context, m) }
        }
    }
    val missed = calls?.let { com.fariin.app.data.CallsSeen.count(it, me, callsMark) } ?: 0
    val demo = me == DemoChats.ME   // debug demo mode: made-up data in every tab
    // One stories feed for the app: the Stories tab and the rings on chat-list photos (iOS storiesRepo).
    val storiesOwn: com.fariin.app.data.StoriesState = if (demo) DemoSettings.stories().second else {
        val prof = remember(me) { com.fariin.app.data.MyProfileRepository().listen(me) }.collectAsState(initial = null).value
        val convs by androidx.compose.runtime.rememberUpdatedState(chats.chats)
        val profNow by androidx.compose.runtime.rememberUpdatedState(prof)
        val repo = remember { com.fariin.app.data.StoriesRepository() }
        remember(me) { repo.listen(me, { convs }, { profNow?.name?.ifEmpty { null } ?: "You" }, { profNow?.photoUrl }) }
            .collectAsState(initial = com.fariin.app.data.StoriesState()).value
    }
    // Fariin's own stories join the list as one more ring (iOS OfficialStories): first while unseen.
    val officialList by com.fariin.app.data.OfficialStories.stories.collectAsState()
    LaunchedEffect(me) { if (!demo) com.fariin.app.data.OfficialStories.refresh(context) }
    val officialSeen by com.fariin.app.data.OfficialStories.seenTick.collectAsState()
    val officialGroup = if (demo) null else remember(officialList, officialSeen) { com.fariin.app.data.OfficialStories.group(context, officialList) }
    val stories = officialGroup?.let { g ->
        storiesOwn.copy(others = if (g.hasUnseen) listOf(g) + storiesOwn.others else storiesOwn.others + g)
    } ?: storiesOwn
    // The same feed for screens outside the tabs (a chat's story mention card asks if a story is live).
    LaunchedEffect(stories) { com.fariin.app.data.LiveStories.state.value = stories }
    LaunchedEffect(chats.chats) { com.fariin.app.data.ChatsNow.list = chats.chats }
    // A story post the app died in the middle of (StoryOutbox): finished once the chat list is here.
    var outboxRan by remember(me) { mutableStateOf(false) }
    LaunchedEffect(me, chats.chats.isNotEmpty()) {
        if (!demo && !outboxRan && chats.chats.isNotEmpty()) {
            outboxRan = true
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.fariin.app.data.StoryOutbox.resume(context, me, com.fariin.app.ui.stories.storyConvs(chats.chats))
            }
        }
    }

    // The chat list's camera button opens the Stories tab straight into its photo picker.
    var storyRequest by rememberSaveable { mutableIntStateOf(0) }
    // The launcher's New Story shortcut (system/SystemRouter): the same as the camera button.
    val launcherStory by com.fariin.app.system.SystemRouter.storyRequests.collectAsState()
    var launcherSeen by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(launcherStory) { if (launcherStory > launcherSeen) { launcherSeen = launcherStory; storyRequest++; tab = TAB_STORIES } }
    // A story notification tapped: the Stories tab, without the picker.
    val storiesAsk by com.fariin.app.system.SystemRouter.storiesTabRequests.collectAsState()
    var storiesSeen by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(storiesAsk) { if (storiesAsk > storiesSeen) { storiesSeen = storiesAsk; tab = TAB_STORIES } }
    var reselect by rememberSaveable { mutableIntStateOf(0) }
    // No vibration on a tab tap (owner 2026-11-03: remove it).
    val select: (Int) -> Unit = { t ->
        if (t == tab) reselect++
        tab = t
    }
    // System back on another tab goes to Chats first, then leaves the app (Android tab apps).
    BackHandler(enabled = tab != TAB_CHATS) { tab = TAB_CHATS }

    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val inset = BarHeight + BarMargin * 2 + navBottom
    val haze = remember { HazeState() }
    // Android 13+ with glass allowed: real liquid glass (lens refraction at the rim, colour vibrancy,
    // a specular edge) from the backdrop library; Android 12 keeps the frosted blur; below, solid.
    val liquid = glass && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
    val backdrop = com.kyant.backdrop.backdrops.rememberLayerBackdrop()

    Box(Modifier.fillMaxSize().background(c.bg)) {
        Box(Modifier.fillMaxSize().then(when {
            liquid -> Modifier.layerBackdrop(backdrop)
            glass -> Modifier.hazeSource(haze)
            else -> Modifier
        })) {
            CompositionLocalProvider(LocalBottomBarInset provides inset, LocalTabReselect provides reselect) {
                // The chat list stays alive once shown: coming back to Chats rebuilt it every time
                // (one 65 ms frame per switch on the Pixel 5). Behind another tab it is neither
                // measured nor drawn nor touchable, and its Back handlers are off.
                var chatsBorn by remember { mutableStateOf(tab == TAB_CHATS) }
                if (tab == TAB_CHATS) chatsBorn = true
                if (chatsBorn) {
                    val shown = tab == TAB_CHATS
                    Box(Modifier.fillMaxSize().layout { m, cs ->
                        if (!shown) layout(cs.maxWidth, cs.maxHeight) {}
                        else { val p = m.measure(cs); layout(p.width, p.height) { p.place(0, 0) } }
                    }) {
                        ChatListScreen(chats, me, decryptor, stories = stories, onRetry = onRetryChats,
                            onSettings = { select(TAB_SETTINGS) },
                            onNewStory = { storyRequest++; select(TAB_STORIES) },
                            active = shown)
                    }
                }
                when (tab) {
                    TAB_STORIES -> StoriesTab(me, chats.chats, if (demo) DemoSettings.stories() else null, storyRequest, shared = if (demo) null else stories)
                    // The calls list scrolls under the bar and lifts its New Call button by the inset.
                    TAB_CALLS -> CallsTab(me, chats.chats, if (demo) DemoSettings.calls() else null, shared = calls,
                        error = callsError, onRetry = { callsRetry++ })
                    TAB_CHATS -> {}
                    // My Profile (Settings) reads the same feed for its Posted stories card.
                    else -> com.fariin.app.ui.glow.ProvideMyStories(stories, chats.chats) {
                        SettingsTab(me, if (demo) DemoSettings.profile else null, onSignOut)
                    }
                }
            }
        }
        // My own photo is the Settings tab's icon (iOS settingsTabLabel).
        val myPhoto = if (demo) DemoSettings.profile.photoUrl else {
            val repo = remember { com.fariin.app.data.MyProfileRepository() }
            val flow = remember(me) { repo.listen(me) }
            flow.collectAsState(initial = null).value?.photoUrl
        }
        androidx.compose.animation.AnimatedVisibility(!TabBarVisibility.hidden, modifier = Modifier.align(Alignment.BottomCenter),
            enter = androidx.compose.animation.slideInVertically { it } + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.slideOutVertically { it } + androidx.compose.animation.fadeOut()) {
            FloatingTabBar(
                tab = tab, unread = unread, missed = missed, myPhoto = myPhoto, glass = glass, haze = haze, backdrop = if (liquid) backdrop else null, animate = power.animations, onSelect = select,
                modifier = Modifier.navigationBarsPadding().padding(horizontal = BarSide, vertical = BarMargin),
            )
        }
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
    tab: Int, unread: Int, missed: Int, myPhoto: String?, glass: Boolean, haze: HazeState, backdrop: com.kyant.backdrop.backdrops.LayerBackdrop?, animate: Boolean,
    onSelect: (Int) -> Unit, modifier: Modifier = Modifier,
) {
    val c = Fariin.colors
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(BarHeight / 2)
    val surface = scheme.surfaceContainer
    // The shine: a white rim, brightest along the top edge, as frosted glass catches the light.
    val border = androidx.compose.ui.graphics.Brush.verticalGradient(
        if (c.dark) listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.06f))
        else listOf(Color.White, Color.White.copy(alpha = 0.55f)))
    val fill = if (backdrop != null) {
        // Liquid glass: what passes under bends at the rounded rim (lens), its colours are lifted
        // (vibrancy), lightly blurred, under a thin frosted surface; the library draws the shine.
        Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy()
                lens(14.dp.toPx(), 28.dp.toPx())
            },
            shadow = null,
            // No blur pass (measured on the Pixel: the blur took the median frame from 13 to 18 ms
            // while scrolling); a slightly thicker frosted surface keeps the labels readable instead.
            onDrawSurface = { drawRect(if (c.dark) surface.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.55f)) },
        )
    } else if (glass) {
        Modifier.hazeEffect(haze) {
            backgroundColor = c.bg
            blurRadius = 24.dp
            noiseFactor = 0f
            // Blurred from a third-size copy: the same look under a 24 dp blur, a fraction of the GPU work.
            inputScale = HazeInputScale.Fixed(1f / 3)
            // Frosted white by day, the dark surface at night, both thin enough to show what passes under.
            tints = listOf(HazeTint(if (c.dark) surface.copy(alpha = 0.60f) else Color.White.copy(alpha = 0.72f)))
        }
    } else Modifier.background(surface.copy(alpha = 0.97f))
    Row(
        modifier
            .widthIn(max = 420.dp).fillMaxWidth().height(BarHeight)
            .shadow(if (c.dark) 0.dp else 16.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.14f), spotColor = Color.Black.copy(alpha = 0.28f))
            .clip(shape)
            .then(fill)
            // A glass edge you can see (owner 2026-11-03, "ours is invisible"): a thin grey outer
            // line, then the bright inner rim.
            .border(0.5.dp, if (c.dark) Color.White.copy(alpha = 0.16f) else Color.Black.copy(alpha = 0.12f), shape)
            .padding(0.5.dp)
            .border(1.dp, border, shape)
            .padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tabs.forEach { t ->
            TabItem(t, selected = tab == t.id, badge = when (t.id) { TAB_CHATS -> unread; TAB_CALLS -> missed; else -> 0 }, photo = myPhoto, animate = animate,
                onClick = { onSelect(t.id) }, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun TabItem(t: TabSpec, selected: Boolean, badge: Int, photo: String?, animate: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val c = Fariin.colors
    val spec = if (animate) tween<Color>(180) else snap()
    // iOS: the open tab's icon and label in the text colour, the others in grey. Its pill grows
    // in place from 60% while it fades in (decelerating, 320 ms) and the old tab's pill shrinks
    // away, so a tap never smears across the tabs in between. Drawn in drawBehind: the draw phase
    // alone reads the value. The icon gives a small pop when its tab opens.
    // The open tab in the text colour, the others a darker grey than before so the bar does not read
    // as washed out (owner 2026-11-03: no blue pill, "all looks light").
    val tint by animateColorAsState(if (selected) c.label else c.label.copy(alpha = 0.62f), spec, label = "tabTint")
    val on by animateFloatAsState(if (selected) 1f else 0f,
        if (animate) tween(320, easing = LinearOutSlowInEasing) else snap(), label = "tabPill")
    val pillColor = if (c.dark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.08f)
    val pop = remember { Animatable(1f) }
    LaunchedEffect(selected) {
        if (selected && animate) { pop.snapTo(0.86f); pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 500f)) }
    }
    val desc = if (badge > 0) "${t.label}, $badge ${if (t.id == TAB_CALLS) "missed" else "unread"}" else t.label
    Box(
        modifier.fillMaxHeight()
            .semantics { contentDescription = desc; this.selected = selected }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                // No ripple: the sliding pill is the tap's feedback (a ripple left a second blob).
                indication = null,
                role = Role.Tab, onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.layout.Column(
            Modifier.widthIn(min = 64.dp).height(48.dp)
                .drawBehind {
                    if (on <= 0f) return@drawBehind
                    val s = 0.6f + 0.4f * on
                    val w = size.width * s; val h = size.height * s
                    drawRoundRect(pillColor.copy(alpha = pillColor.alpha * on),
                        topLeft = Offset((size.width - w) / 2, (size.height - h) / 2),
                        size = Size(w, h), cornerRadius = CornerRadius(h / 2))
                }
                // The grey pill spans the whole cell anyway; the side padding only squeezed the label
                // ("Storie", "Settin" on a 320 dp phone, Android 8.1 emulator, 2026-11-05).
                .padding(horizontal = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            BadgedBox(modifier = Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value }, badge = {
                if (badge > 0) Badge(containerColor = c.red, contentColor = Color.White) {
                    Text(if (badge > 99) "99+" else "$badge")
                }
            }) {
                if (t.icon != null) {
                    Icon(androidx.compose.ui.res.painterResource(t.icon), contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
                } else {
                    com.fariin.app.ui.components.Avatar(photo, 24.dp,
                        modifier = if (selected) Modifier.border(1.5.dp, c.label, androidx.compose.foundation.shape.CircleShape) else Modifier)
                }
            }
            // The phone's text size grows the label up to 115% only (iOS tab bars cap it too): at
            // 130% "Settings" was cut to "Setting" on a small screen.
            val fs = androidx.compose.ui.platform.LocalDensity.current.fontScale
            Text(t.label, fontSize = (12f * kotlin.math.min(fs, 1.15f) / fs).sp, lineHeight = (14f * kotlin.math.min(fs, 1.15f) / fs).sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
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
