package com.fariin.app.ui.settings

import com.fariin.app.ui.components.fadeOnChange
import com.fariin.app.ui.components.smoothSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.R
import com.fariin.app.data.PlusStore
import com.fariin.app.ui.components.FariinSheetDialog
import com.fariin.app.ui.components.cachedVector
import com.fariin.app.ui.components.rememberSheetMotion
import com.fariin.app.ui.components.sheetDrag
import com.fariin.app.ui.theme.Fariin
import java.text.DateFormat
import java.util.Date

/*
 * Settings > Subscription > Fariin Plus (iOS PlusView, contract v1). Exists only while the server's
 * plusState says visible; the page closes itself if that turns false. Every feature row comes from
 * the server's states ("off" rows are not listed); nothing here unlocks anything.
 */

internal const val PAGE_SUBSCRIPTIONS = "settings:subscriptions"

/** The one Plus colour (owner): Fariin blue. */
val PlusBlue = Color(0xFF0590F7)

/** A Plus feature as the page lists it: a short [title], a grey one/two-line [subtitle] under it in
 *  the list, and the sheet's longer [detail] (iOS PlusView.all, same words). */
private class PlusFeature(val id: String, val icon: Int, val title: String, val subtitle: String, val detail: String)

private val plusFeatures = listOf(
    PlusFeature("badge", R.drawable.ic_plus_badge, "Plus Badge",
        "A star next to your name in chats, lists and your profile.",
        "A star next to your name in chats, lists and your profile, so people know you have Plus."),
    PlusFeature("storyExpiry", R.drawable.ic_sf_clock, "Story Length",
        "Keep a story up for 6, 12, 24 or 48 hours.",
        "Pick how long your story stays up: 6, 12, 24 or 48 hours. Without Plus, stories stay 24 hours."),
    PlusFeature("appIcons", R.drawable.ic_sf_square_grid, "App Icons",
        "Pick a different Fariin icon for your home screen.",
        "Pick one of the Plus icons for Fariin on your home screen."),
    PlusFeature("morePins", R.drawable.ic_pin_menu, "More Pinned Chats",
        "Pin more chats to the top of your chat list.",
        "Pin more chats to the top of your chat list."),
    PlusFeature("profileVisitors", R.drawable.ic_views, "Profile Visitors",
        "See who looked at your profile in the last 30 days.",
        "See who opened your profile in the last 30 days, from the eye button at the top of Settings."),
    PlusFeature("animatedAvatar", R.drawable.ic_sf_person_crop_circle_fill, "Animated Profile Picture",
        "Use a short video as your profile picture.",
        "Use a short video as your profile picture. It plays in chats and on your profile."),
    PlusFeature("storyRewatch", R.drawable.ic_sf_arrow_clockwise, "Story Rewatches",
        "See when someone watches your story again.",
        "In your story's viewers list, a small number shows who came back and how many times."),
    PlusFeature("pictureBubbles", R.drawable.ic_settings_chats, "Bubble Styles",
        "Picture bubbles like Anime, Gen Z and Cute for your chats.",
        "Dress your messages in picture bubbles from the Anime, Gen Z, Cute, Careers, Sport and Everyday packs. The other person sees them too."),
    PlusFeature("sharedWallpaper", R.drawable.ic_wallpaper, "Shared Wallpaper",
        "Set a chat wallpaper both of you see.",
        "Pick a wallpaper and tap Set for Both: the chat changes for you and for them, and you both see a note that it changed."),
    PlusFeature("sendEffects", R.drawable.ic_sf_sparkles, "Send Effects",
        "Send messages with hearts, confetti, balloons or fireworks.",
        "Send a message with a short animation: hearts, confetti, balloons or fireworks."),
    PlusFeature("chatKey", R.drawable.ic_chat_key, "Own Chat Key",
        "Choose your own Chat Key instead of a random one.",
        "Pick your own Chat Key instead of the random 6-digit one Fariin makes for you."),
    PlusFeature("gifts", R.drawable.ic_gift, "Gift Plus",
        "Give Fariin Plus to a friend.",
        "Give Fariin Plus to a friend from their profile."),
    PlusFeature("chatFolders", R.drawable.ic_story_folder, "Chat Folders",
        "Sort chats into folders at the top of your list.",
        "Sort your chats into folders and switch between them at the top of your chat list."),
    PlusFeature("tagSearch", R.drawable.ic_sf_number, "Tag Search",
        "Tap a #tag to see posts from every channel that uses it.",
        "With Fariin Plus, a tap on a tag shows the posts from all channels that use it."),
)

/** The grey line under a feature, with the real pinned-chat limit when there is one (iOS subtitle). */
private fun subtitleOf(f: PlusFeature): String =
    // iOS plusPinLimit: the Plus number (pinnedChatsPlus), never a locked account's free one ("Pin up
    // to 3" read as what Plus gives, Oppo 2026-11-20); from an older server only a member's own limit.
    if (f.id == "morePins") PlusStore.plusPinLimit?.let { "Pin up to $it chats at the top of your chat list." } ?: f.subtitle else f.subtitle

/** This account has Plus now, or a store subscription whose end is still ahead (iOS `subscribed`). */
private fun plusSubscribed(): Boolean =
    PlusStore.isPlus || (PlusStore.subscription?.untilMs?.let { it > System.currentTimeMillis() } == true)

/** The plans and buying: only while the server sells to this account and it has no Plus yet. */
private fun plusSelling(): Boolean = PlusStore.purchasesOn && !plusSubscribed()

@Composable
internal fun SubscriptionsPage(pop: () -> Unit, push: (String) -> Unit = {}) {
    // Plus went away for this account while the page was open: so does the page.
    val popNow by rememberUpdatedState(pop)
    LaunchedEffect(PlusStore.visible) { if (!PlusStore.visible) popNow() }
    var opened by remember { mutableStateOf<String?>(null) }
    var plan by remember { mutableStateOf("yearly") }
    val shown = plusFeatures.filter { PlusStore.state(it.id) != PlusStore.State.OFF }
    Box(Modifier.fillMaxSize()) {
        ScrollPage("Fariin Plus", pop) {
            PlusPageBody(shown, plan, { plan = it }) { id ->
                // iOS: Bubble Styles, when on, opens the bubble style page itself (with its back button).
                if (id == "pictureBubbles" && PlusStore.state(id) == PlusStore.State.ON) push(PAGE_BUBBLE_STYLE) else opened = id
            }
            // Room for the floating Subscribe button, so the last rows scroll up above it.
            Spacer(Modifier.height(if (plusSubscribed()) 24.dp else 96.dp))
        }
        if (!plusSubscribed()) PlusSubscribeButton(plan, Modifier.align(Alignment.BottomCenter)
            .padding(bottom = com.fariin.app.ui.shell.bottomBarInset()))
    }
    opened?.let { start -> PlusFeatureSheet(shown, start) { opened = null } }
}

/** The page's rows, the same on the page and in the sheet (iOS PlusView's List). */
@Composable
private fun PlusPageBody(shown: List<PlusFeature>, plan: String, onPlan: (String) -> Unit, onOpen: (String) -> Unit) {
    PlusHeader()
    // A purchase or restore takes the plans away and brings Manage Subscription: they fold and open
    // with a fade instead of the page rearranging in one frame (iOS pops 81).
    com.fariin.app.ui.components.Appear(plusSelling()) { Column { PlusPlansSection(plan, onPlan) } }
    Column(Modifier.fillMaxWidth().smoothSize()) { PlusManageSection() }
    if (shown.isNotEmpty()) SettingsSection(header = "What's Included") {
        shown.forEachIndexed { i, f ->
            val on = PlusStore.state(f.id) == PlusStore.State.ON
            SettingsRow(f.title, painterResource(f.icon), value = if (on) "On" else null, subtitle = subtitleOf(f),
                iconTint = if (f.id == "badge") PlusBlue else null,
                last = i == shown.lastIndex) { onOpen(f.id) }
        }
    }
    PlusAboutSection()
}

/** The page's top: the star, the name, where this account stands, and a line for someone without Plus. */
@Composable
private fun PlusHeader() {
    val c = Fariin.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 4.dp, bottom = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PlusBadgeImage(64.dp)
        Text("Fariin Plus", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = c.label)
        Text(statusLine(), fontSize = 15.sp, color = c.secondary, textAlign = TextAlign.Center)
        if (!plusSubscribed()) Text("Unlock exclusive features and support Fariin with Fariin Plus.",
            fontSize = 15.sp, color = c.label, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 2.dp))
    }
}

/** iOS statusLine: Renews <date> for a renewing store plan, else Active until <date>, Active, Not active. */
private fun statusLine(): String {
    if (!plusSubscribed()) return "Not active"
    val sub = PlusStore.subscription
    val fmt = DateFormat.getDateInstance(DateFormat.LONG)
    val renewEnd = sub?.untilMs ?: PlusStore.untilMs
    if (sub?.autoRenew == true && sub.status != "grace" && renewEnd != null) return "Renews " + fmt.format(Date(renewEnd))
    val end = PlusStore.untilMs ?: sub?.untilMs
    return if (end != null) "Active until " + fmt.format(Date(end)) else "Active"
}

/** About Fariin Plus, under the features (iOS aboutSection): what Plus is for, then the footnote. */
@Composable
private fun PlusAboutSection() {
    val c = Fariin.colors
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val subscribed = plusSubscribed()
    SettingsSection(header = "About Fariin Plus") {
        Text(if (subscribed) "Thank you for supporting Fariin. All your Plus features are unlocked, and your support helps keep Fariin free for everyone."
            else "Fariin is free, and it stays free. Fariin Plus adds extras on top for people who want more, and it helps pay for the servers that keep Fariin running for everyone.",
            fontSize = 15.sp, color = c.secondary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp))
    }
    val play = subscribed && PlusStore.subscription?.source == "play"
    if (play) Text("You can change or cancel your plan any time in Google Play under Payments & subscriptions.",
        fontSize = 13.sp, color = c.secondary, modifier = Modifier.padding(horizontal = 32.dp))
    else if (!subscribed && !plusSelling()) Column(Modifier.padding(horizontal = 32.dp)) {
        Text("By subscribing to Fariin Plus you agree to the Terms of Use and Privacy Policy.", fontSize = 13.sp, color = c.secondary)
        PlusLegalLinks(ctx)
    }
}

@Composable
private fun PlusLegalLinks(ctx: android.content.Context) {
    val c = Fariin.colors
    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        listOf("Terms of Use" to "https://fariin.com/terms", "Privacy Policy" to "https://fariin.com/privacy").forEach { (t, u) ->
            Text(t, fontSize = 13.sp, color = c.accent, modifier = Modifier.clickable {
                runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(u))) }
            })
        }
    }
}

/**
 * The capsule at the bottom for someone without Plus (iOS subscribeButton, owner 2026-11-20: always
 * there). While payments are closed to this account a tap says buying is coming soon; while they are
 * open it buys the picked plan, and a tap with no plans in hand loads them again.
 */
@Composable
private fun PlusSubscribeButton(plan: String, modifier: Modifier = Modifier) {
    val c = Fariin.colors
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val billing = com.fariin.app.data.PlusBilling
    var notOnSale by remember { mutableStateOf(false) }
    val plans = billing.plans
    val picked = plans.firstOrNull { it.basePlanId == plan } ?: plans.firstOrNull()
    val waiting = billing.busy || (PlusStore.purchasesOn && picked == null && !billing.loaded)
    val title = picked?.let { "Subscribe for ${it.price} / ${if (it.period.endsWith("Y")) "year" else "month"}" } ?: "Subscribe"
    val activity = ctx as? android.app.Activity ?: (ctx as? android.content.ContextWrapper)?.baseContext as? android.app.Activity
    Box(modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp).height(54.dp).clip(CircleShape)
        .background(PlusBlue).clickable(enabled = !waiting) {
            when {
                !PlusStore.purchasesOn -> notOnSale = true
                !plusSelling() -> {}
                picked == null -> billing.loadPlans(ctx)
                activity != null -> billing.buy(activity, picked)
            }
        }.semantics { contentDescription = if (billing.busy) "Subscribing" else title },
        contentAlignment = Alignment.Center) {
        if (waiting) com.fariin.app.ui.components.FariinSpinner(size = 22.dp, stroke = 2.5.dp, color = Color.White)
        else Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1)
    }
    if (notOnSale) com.fariin.app.ui.components.GlassAlert(onDismissRequest = { notOnSale = false },
        title = { Text("Not Available Yet") }, text = { Text("Buying Fariin Plus in the app is coming soon.") },
        confirmButton = { androidx.compose.material3.TextButton({ notOnSale = false }) { Text("OK", color = c.accent) } },
        containerColor = c.card)
}

/** The star alone, in the Plus colour, whatever the uid (the Plus page, sheets). */
@Composable
fun PlusBadgeImage(size: Dp, modifier: Modifier = Modifier, tint: Color = PlusBlue) {
    Icon(ImageVector.cachedVector(R.drawable.ic_plus_badge), contentDescription = null, tint = tint, modifier = modifier.size(size))
}

/**
 * The Plus badge beside a name: draws nothing unless Plus is visible to this account and [uid] has
 * it, so it can sit after any name without an `if`. [size] = the verified mark's size beside it.
 * [opensPage]: a tap opens the Plus page as a sheet (a profile, where the tap means nothing else).
 */
@Composable
fun PlusBadge(uid: String, size: Dp, modifier: Modifier = Modifier, opensPage: Boolean = false) {
    if (!PlusStore.showsBadge(uid)) return
    var page by remember { mutableStateOf(false) }
    Icon(ImageVector.cachedVector(R.drawable.ic_plus_badge), contentDescription = null, tint = PlusBlue,
        modifier = modifier.size(size).semantics { contentDescription = "Fariin Plus" }
            .then(if (opensPage) Modifier.clickable(interactionSource = null, indication = null) { page = true } else Modifier))
    if (page) PlusPageSheet { page = false }
}

/** The Plus page in a sheet with Done (a tap on someone's badge, or a locked feature's row). */
@Composable
fun PlusPageSheet(onDismiss: () -> Unit) {
    val c = Fariin.colors
    val motion = rememberSheetMotion { onDismiss() }
    LaunchedEffect(PlusStore.visible) { if (!PlusStore.visible) motion.close() }
    var opened by remember { mutableStateOf<String?>(null) }
    var plan by remember { mutableStateOf("yearly") }
    FariinSheetDialog(motion, fill = c.bgSecondary) { _ ->
        Box(Modifier.fillMaxSize()) {
            val shown = plusFeatures.filter { PlusStore.state(it.id) != PlusStore.State.OFF }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Spacer(Modifier.height(44.dp))
                PlusPageBody(shown, plan, { plan = it }) { opened = it }
                Spacer(Modifier.height(if (plusSubscribed()) 32.dp else 96.dp))
            }
            Box(Modifier.fillMaxWidth().height(28.dp).sheetDrag(motion))
            Text("Done", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.accent,
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).clip(CircleShape)
                    .clickable { motion.close() }.padding(horizontal = 8.dp, vertical = 4.dp))
            if (!plusSubscribed()) PlusSubscribeButton(plan, Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp))
            opened?.let { start -> PlusFeatureSheet(shown, start) { opened = null } }
        }
    }
}

/** Things you see: their gate button reads "Unlock with Fariin Plus"; the rest "Learn About Fariin Plus" (iOS PlusGate). */
private val plusVisualFeatures = setOf("appIcons", "pictureBubbles", "bubbleStyles", "sendEffects", "badge", "animatedAvatar", "sharedWallpaper")

/**
 * THE LOCKED PLUS FEATURE GATE (iOS PlusGate, owner 2026-11-20): an account that sees Fariin Plus
 * but does not have it taps a Plus feature where a member would; this sheet is about THAT feature
 * (its picture, title and words, a round X), and its one button closes it and opens the Fariin Plus
 * page. Nothing is bought here; the server still refuses a locked account on its own.
 */
@Composable
fun PlusGateSheet(feature: String, onDismiss: () -> Unit) {
    var page by remember { mutableStateOf(false) }
    if (page) { PlusPageSheet(onDismiss); return }
    val f = plusFeatures.firstOrNull { it.id == feature }
    if (f == null) { PlusPageSheet(onDismiss); return }
    val c = Fariin.colors
    var toPage by remember { mutableStateOf(false) }
    val motion = rememberSheetMotion { if (toPage) page = true else onDismiss() }
    val density = LocalDensity.current
    val windowH = LocalWindowInfo.current.containerSize.height
    val want = with(density) { (windowH - WindowInsets.statusBars.getTop(density) - 10.dp.toPx() - 470.dp.toPx() - WindowInsets.navigationBars.getBottom(density)).coerceAtLeast(0f) }
    if (motion.mid <= 0f) motion.mid = want
    androidx.compose.runtime.SideEffect { motion.fitMid(want) }
    FariinSheetDialog(motion, fill = c.bgSecondary) { _ ->
        Box(Modifier.fillMaxWidth().height(470.dp)) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().height(28.dp).sheetDrag(motion))
                Box(Modifier.padding(horizontal = 20.dp).padding(top = 30.dp).fillMaxWidth().height(170.dp)
                    .clip(RoundedCornerShape(28.dp)).background(PlusBlue.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                    Icon(ImageVector.cachedVector(f.icon), contentDescription = null,
                        tint = if (f.id == "badge") PlusBlue else c.label, modifier = Modifier.size(56.dp))
                }
                Text(f.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = c.label, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp).padding(top = 18.dp))
                Text(f.detail, fontSize = 17.sp, color = c.secondary, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp).padding(top = 10.dp))
                Spacer(Modifier.weight(1f))
                Box(Modifier.padding(horizontal = 20.dp).padding(bottom = 16.dp).fillMaxWidth().height(50.dp).clip(CircleShape)
                    .background(PlusBlue).clickable { toPage = true; motion.close() }, contentAlignment = Alignment.Center) {
                    Text(if (f.id in plusVisualFeatures) "Unlock with Fariin Plus" else "Learn About Fariin Plus",
                        fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                }
            }
            com.fariin.app.ui.components.FloatingIcon(ImageVector.cachedVector(R.drawable.ic_sf_xmark), "Close",
                onClick = { motion.close() }, circle = true, modifier = Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 12.dp))
        }
    }
}

/**
 * "Limit Reached" for pinned chats (iOS PlusLimitSheet): a bar with the free part in grey and the
 * Plus part in Plus blue, a round badge with the [count] on it, the words, and one button. It opens
 * the Plus page; a Plus member already at the Plus limit gets "Got It", which only closes.
 */
@Composable
fun PlusLimitSheet(count: Int, free: Int, plus: Int, onDismiss: () -> Unit) {
    var page by remember { mutableStateOf(false) }
    if (page) { PlusPageSheet(onDismiss); return }
    val c = Fariin.colors
    val hasPlus = PlusStore.isPlus
    var toPage by remember { mutableStateOf(false) }
    val motion = rememberSheetMotion { if (toPage) page = true else onDismiss() }
    val density = LocalDensity.current
    val windowH = LocalWindowInfo.current.containerSize.height
    val want = with(density) { (windowH - WindowInsets.statusBars.getTop(density) - 10.dp.toPx() - 380.dp.toPx() - WindowInsets.navigationBars.getBottom(density)).coerceAtLeast(0f) }
    if (motion.mid <= 0f) motion.mid = want
    androidx.compose.runtime.SideEffect { motion.fitMid(want) }
    // Where the free part ends, and where the badge sits, along the bar (0..1).
    val freeShare = if (plus > 0) (free.toFloat() / plus).coerceIn(0.2f, 1f) else 1f
    val countShare = if (plus > 0) (count.toFloat() / plus).coerceIn(0f, 1f) else 1f
    FariinSheetDialog(motion, fill = c.bgSecondary) { _ ->
        Box(Modifier.fillMaxWidth().height(380.dp)) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().height(28.dp).sheetDrag(motion))
                Text("Limit Reached", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = c.label,
                    modifier = Modifier.padding(top = 46.dp))
                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.padding(horizontal = 30.dp).padding(top = 64.dp).fillMaxWidth().height(30.dp)) {
                    val w = maxWidth
                    Row(Modifier.fillMaxSize().clip(RoundedCornerShape(15.dp))) {
                        Box(Modifier.width(w * freeShare).fillMaxHeight().background(c.secondary.copy(alpha = 0.18f)), contentAlignment = Alignment.CenterStart) {
                            Text("Free $free", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.secondary, modifier = Modifier.padding(start = 12.dp))
                        }
                        Box(Modifier.weight(1f).fillMaxHeight().background(PlusBlue), contentAlignment = Alignment.CenterEnd) {
                            Text("Plus $plus", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.padding(end = 12.dp))
                        }
                    }
                    // The badge with the count, riding above the bar at where the count is.
                    val share = if (hasPlus) countShare else freeShare
                    val x = (w * share).coerceIn(24.dp, (w - 24.dp).coerceAtLeast(24.dp)) - 24.dp
                    Row(Modifier.offset(x = x, y = (-54).dp).size(48.dp).clip(CircleShape).background(PlusBlue)
                        .semantics { contentDescription = "$count in use" },
                        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Icon(ImageVector.cachedVector(R.drawable.ic_pin_menu), contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                        Text("$count", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(start = 2.dp))
                    }
                }
                Text("You can pin up to $free chats. Fariin Plus lets you pin up to $plus.", fontSize = 17.sp, color = c.secondary,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp).padding(top = 20.dp))
                Spacer(Modifier.weight(1f))
                Box(Modifier.padding(horizontal = 20.dp).padding(bottom = 16.dp).fillMaxWidth().height(50.dp).clip(CircleShape)
                    .background(PlusBlue).clickable { if (!hasPlus) toPage = true; motion.close() }, contentAlignment = Alignment.Center) {
                    Text(if (hasPlus) "Got It" else "Raise the Limit with Fariin Plus",
                        fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                }
            }
            com.fariin.app.ui.components.FloatingIcon(ImageVector.cachedVector(R.drawable.ic_sf_xmark), "Close",
                onClick = { motion.close() }, circle = true, modifier = Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 12.dp))
        }
    }
}

/** One page per shown feature, swiped sideways, opening on the tapped one; one button closes it. */
@Composable
private fun PlusFeatureSheet(features: List<PlusFeature>, start: String, onDismiss: () -> Unit) {
    if (features.isEmpty()) { onDismiss(); return }
    val c = Fariin.colors
    val motion = rememberSheetMotion { onDismiss() }
    val density = LocalDensity.current
    val windowH = LocalWindowInfo.current.containerSize.height
    val want = with(density) { (windowH - WindowInsets.statusBars.getTop(density) - 10.dp.toPx() - 500.dp.toPx() - WindowInsets.navigationBars.getBottom(density)).coerceAtLeast(0f) }
    if (motion.mid <= 0f) motion.mid = want
    androidx.compose.runtime.SideEffect { motion.fitMid(want) }
    val pager = rememberPagerState(initialPage = features.indexOfFirst { it.id == start }.coerceAtLeast(0)) { features.size }
    FariinSheetDialog(motion, fill = c.bgSecondary) { _ ->
        Column(Modifier.fillMaxWidth().height(500.dp)) {
            Box(Modifier.fillMaxWidth().height(56.dp).sheetDrag(motion)) {
                // iOS: a round X at the top left closes it.
                com.fariin.app.ui.components.FloatingIcon(ImageVector.cachedVector(R.drawable.ic_sf_xmark), "Close",
                    onClick = { motion.close() }, circle = true, modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp))
            }
            HorizontalPager(pager, Modifier.fillMaxWidth().weight(1f)) { i ->
                val f = features[i]
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.padding(horizontal = 20.dp).padding(top = 8.dp).fillMaxWidth().height(190.dp)
                        .clip(RoundedCornerShape(28.dp)).background(PlusBlue.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center) {
                        Icon(ImageVector.cachedVector(f.icon), contentDescription = null,
                            tint = if (f.id == "badge") PlusBlue else c.label, modifier = Modifier.size(60.dp))
                    }
                    Text(f.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = c.label, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp).padding(top = 22.dp))
                    Text(f.detail, fontSize = 17.sp, color = c.secondary, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 28.dp).padding(top = 14.dp))
                }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 18.dp), horizontalArrangement = Arrangement.Center) {
                features.indices.forEach { i ->
                    Box(Modifier.padding(horizontal = 3.5.dp).size(7.dp).clip(CircleShape)
                        .background(if (i == pager.currentPage) c.label else c.secondary.copy(alpha = 0.35f)))
                }
            }
            val cur = features.getOrNull(pager.currentPage)
            @Suppress("UNUSED_VARIABLE") val shownNow = cur
            val label = "Got It"
            Box(Modifier.padding(horizontal = 20.dp).padding(bottom = 12.dp).fillMaxWidth().height(50.dp).clip(CircleShape)
                .background(PlusBlue).clickable { motion.close() }, contentAlignment = Alignment.Center) {
                Text(label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            }
        }
    }
}


/**
 * Buying Fariin Plus on Android (Google Play, PlusBilling; iOS PlusView's plansSection): the plans,
 * only while the server sells to this account and it has no Plus. The floating Subscribe button buys
 * the picked one; the renewal terms and the links sit under the plans.
 */
@Composable
private fun PlusPlansSection(plan: String, onPlan: (String) -> Unit) {
    val c = Fariin.colors
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val billing = com.fariin.app.data.PlusBilling
    LaunchedEffect(PlusStore.purchasesOn) { if (PlusStore.purchasesOn && !billing.loaded) billing.loadPlans(ctx) }
    billing.notice?.let { n ->
        com.fariin.app.ui.components.GlassAlert(onDismissRequest = { billing.notice = null }, title = { Text("Fariin Plus") }, text = { Text(n) },
            confirmButton = { androidx.compose.material3.TextButton({ billing.notice = null }) { Text("OK", color = c.accent) } }, containerColor = c.card)
    }
    val plans = billing.plans
    val picked = plans.firstOrNull { it.basePlanId == plan } ?: plans.firstOrNull()
    SettingsSection(header = "Choose a plan") {
        // The card grows from the spinner to the plan rows on the spring (iOS pops 80).
        Column(Modifier.fillMaxWidth().smoothSize()) {
        if (plans.isEmpty()) Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
            if (billing.loaded) Text("Subscriptions are not available right now.", color = c.secondary)
            else com.fariin.app.ui.components.FariinSpinner(size = 24.dp, stroke = 2.5.dp, color = c.secondary)
        } else plans.forEachIndexed { i, p ->
            val on = picked?.basePlanId == p.basePlanId
            Row(Modifier.fillMaxWidth().clickable { onPlan(p.basePlanId) }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                // The check fades between the rows (iOS pops 79).
                Icon(ImageVector.cachedVector(if (on) R.drawable.ic_sf_checkmark_circle_fill else R.drawable.ic_sf_circle), null,
                    tint = if (on) PlusBlue else c.secondary, modifier = Modifier.size(24.dp).fadeOnChange(on))
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (p.basePlanId == "yearly") "Yearly" else "Monthly", fontSize = 17.sp, color = c.label)
                    Text("${p.price} ${per(p.period)}", fontSize = 13.sp, color = c.secondary)
                }
                saving(p, plans)?.let { Text("Save $it%", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = PlusBlue) }
            }
            if (i < plans.lastIndex) Box(Modifier.fillMaxWidth().padding(start = 52.dp).height(0.5.dp).background(c.separator))
        }
        }
    }
    Text("Payment is charged to your Google Play account when you confirm. The subscription renews automatically at the same price and for the same period until you cancel it. You can manage or cancel it any time in Google Play under Payments & subscriptions.",
        fontSize = 13.sp, color = c.secondary, modifier = Modifier.padding(horizontal = 32.dp, vertical = 6.dp))
    Box(Modifier.padding(horizontal = 32.dp)) { PlusLegalLinks(ctx) }
}

/** A paid store subscription running now: Manage Subscription for a Play one; a word about an App Store one. */
@Composable
private fun PlusManageSection() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val sub = PlusStore.subscription
    val paidRunning = sub != null && (sub.untilMs?.let { it > System.currentTimeMillis() } ?: PlusStore.isPlus)
    when {
        paidRunning && sub?.source == "play" -> SettingsSection(footer = renewLine(sub)) {
            SettingsRow("Manage Subscription", last = true, trailing = Trailing.OUT_LINK) {
                runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(com.fariin.app.data.PlusBilling.manageUrl(ctx.packageName)))) }
            }
        }
        paidRunning && sub?.source == "appstore" -> SettingsSection(footer = "Your Fariin Plus subscription is managed in the App Store on your iPhone.") {}
    }
}

private fun renewLine(sub: PlusStore.Subscription?): String {
    val end = sub?.untilMs ?: PlusStore.untilMs ?: return "Your subscription is active."
    val day = DateFormat.getDateInstance(DateFormat.LONG).format(Date(end))
    return when {
        sub?.status == "grace" -> "There is a problem with your payment. Update it in Google Play before $day to keep Fariin Plus."
        sub?.autoRenew == false -> "Your subscription ends on $day and will not renew."
        else -> "Renews on $day."
    }
}

/** "a month", "a year", from Play's ISO period (P1M, P1Y, P3M...). */
private fun per(iso: String): String {
    val m = Regex("P(\\d+)([DWMY])").find(iso) ?: return ""
    val n = m.groupValues[1].toIntOrNull() ?: return ""
    val unit = when (m.groupValues[2]) { "D" -> "day"; "W" -> "week"; "M" -> "month"; else -> "year" }
    return if (n == 1) "a $unit" else "every $n ${unit}s"
}

/** The yearly plan's saving against twelve monthly payments, from Play's own prices (5% or more). */
private fun saving(p: com.fariin.app.data.PlusBilling.Plan, all: List<com.fariin.app.data.PlusBilling.Plan>): Int? {
    if (p.basePlanId != "yearly") return null
    val m = all.firstOrNull { it.basePlanId == "monthly" } ?: return null
    val full = m.micros * 12
    if (full <= 0 || p.micros >= full) return null
    val pct = ((full - p.micros) * 100 / full).toInt()
    return pct.takeIf { it >= 5 }
}
