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

private class PlusFeature(val id: String, val icon: Int, val title: String, val detail: String)

private val plusFeatures = listOf(
    PlusFeature("badge", R.drawable.ic_plus_badge, "Plus badge next to your name",
        "A star next to your name in chats, lists and your profile, so people know you have Plus."),
    PlusFeature("storyExpiry", R.drawable.ic_sf_clock, "Choose how long a story stays",
        "Choose how many hours your story stays up, instead of always one day."),
    PlusFeature("appIcons", R.drawable.ic_sf_square_grid, "Custom app icons",
        "Pick a different Fariin icon for your home screen."),
    PlusFeature("morePins", R.drawable.ic_pin_menu, "More pinned chats",
        "Pin more chats to the top of your chat list."),
    PlusFeature("profileVisitors", R.drawable.ic_views, "See who visited your profile",
        "See who opened your profile lately, from the eye button at the top of Settings."),
    PlusFeature("animatedAvatar", R.drawable.ic_sf_person_crop_circle_fill, "Animated profile pictures",
        "Use a short video as your profile picture. It plays in chats and on your profile."),
    PlusFeature("storyRewatch", R.drawable.ic_sf_arrow_clockwise, "See when someone watches your story again",
        "In your story's viewers list, a small number shows who came back and how many times."),
    PlusFeature("bubbleStyles", R.drawable.ic_settings_chats, "Exclusive chat bubble styles",
        "Give your chat bubbles a look that only Plus members have."),
    PlusFeature("sharedWallpaper", R.drawable.ic_wallpaper, "Chat wallpaper both sides see",
        "Set one wallpaper for a chat, and the other person sees it too."),
    PlusFeature("sendEffects", R.drawable.ic_sf_sparkles, "Message send effects",
        "Send a message with a short animation, like hearts or confetti."),
    PlusFeature("chatKey", R.drawable.ic_chat_key, "Choose your own Chat Key",
        "Pick your own Chat Key instead of the random 6-digit one Fariin makes for you."),
    PlusFeature("gifts", R.drawable.ic_gift, "Gift Fariin Plus",
        "Give Fariin Plus to a friend from their profile."),
    PlusFeature("chatFolders", R.drawable.ic_story_folder, "Chat Folders",
        "Sort your chats into folders and switch between them at the top of your chat list."),
)

@Composable
internal fun SubscriptionsPage(pop: () -> Unit) {
    // Plus went away for this account while the page was open: so does the page.
    val popNow by rememberUpdatedState(pop)
    LaunchedEffect(PlusStore.visible) { if (!PlusStore.visible) popNow() }
    var opened by remember { mutableStateOf<String?>(null) }
    val shown = plusFeatures.filter { PlusStore.state(it.id) != PlusStore.State.OFF }
    ScrollPage("Fariin Plus", pop) {
        PlusHeader()
        if (shown.isNotEmpty()) SettingsSection(header = "Features",
            footer = if (!PlusStore.purchasesOn) "Buying Fariin Plus in the app is coming later." else null) {
            shown.forEachIndexed { i, f ->
                val on = PlusStore.state(f.id) == PlusStore.State.ON
                SettingsRow(f.title, painterResource(f.icon), value = if (on) "On" else null,
                    subtitle = if (f.id == "morePins") PlusStore.pinnedChats?.let { "Up to $it pinned chats" } else null,
                    iconTint = if (f.id == "badge") PlusBlue else null,
                    last = i == shown.lastIndex) { opened = f.id }
            }
        }
        PlusBuySections()
        Spacer(Modifier.height(24.dp))
    }
    opened?.let { start -> PlusFeatureSheet(shown, start) { opened = null } }
}

/** The page's top: the star, the name and where this account stands. */
@Composable
private fun PlusHeader() {
    val c = Fariin.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 12.dp, bottom = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PlusBadgeImage(72.dp)
        Text("Fariin Plus", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = c.label)
        val until = PlusStore.untilMs
        val line = when {
            PlusStore.isPlus && until != null -> "Active until " + DateFormat.getDateInstance(DateFormat.LONG).format(Date(until))
            PlusStore.isPlus -> "Active"
            else -> "Not active"
        }
        Text(line, fontSize = 15.sp, color = c.secondary, textAlign = TextAlign.Center)
    }
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
    FariinSheetDialog(motion, fill = c.bgSecondary) { _ ->
        Box(Modifier.fillMaxSize()) {
            val shown = plusFeatures.filter { PlusStore.state(it.id) != PlusStore.State.OFF }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Spacer(Modifier.height(44.dp))
                PlusHeader()
                if (shown.isNotEmpty()) SettingsSection(header = "Features",
                    footer = if (!PlusStore.purchasesOn) "Buying Fariin Plus in the app is coming later." else null) {
                    shown.forEachIndexed { i, f ->
                        val on = PlusStore.state(f.id) == PlusStore.State.ON
                        SettingsRow(f.title, painterResource(f.icon), value = if (on) "On" else null,
                            subtitle = if (f.id == "morePins") PlusStore.pinnedChats?.let { "Up to $it pinned chats" } else null,
                            iconTint = if (f.id == "badge") PlusBlue else null,
                            last = i == shown.lastIndex) { opened = f.id }
                    }
                }
                PlusBuySections()
                Spacer(Modifier.height(32.dp))
            }
            Box(Modifier.fillMaxWidth().height(28.dp).sheetDrag(motion))
            Text("Done", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.accent,
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).clip(CircleShape)
                    .clickable { motion.close() }.padding(horizontal = 8.dp, vertical = 4.dp))
            opened?.let { start -> PlusFeatureSheet(shown, start) { opened = null } }
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
            Box(Modifier.fillMaxWidth().height(28.dp).sheetDrag(motion), contentAlignment = Alignment.Center) {
                Box(Modifier.size(36.dp, 5.dp).clip(CircleShape).background(c.secondary.copy(alpha = 0.4f)))
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
            val label = if (cur != null && PlusStore.state(cur.id) == PlusStore.State.ON) "OK" else "Included in Fariin Plus"
            Box(Modifier.padding(horizontal = 20.dp).padding(bottom = 12.dp).fillMaxWidth().height(50.dp).clip(CircleShape)
                .background(PlusBlue).clickable { motion.close() }, contentAlignment = Alignment.Center) {
                Text(label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            }
        }
    }
}


/**
 * Buying Fariin Plus on Android (Google Play, PlusBilling; iOS PlusView's buy sections): the plans,
 * Subscribe, the renewal terms and the Terms / Privacy links appear ONLY while the server's purchases
 * switch is on for this account. A Play subscription running now shows Manage Subscription instead;
 * one bought on an iPhone says where it is managed.
 */
@Composable
internal fun PlusBuySections() {
    val c = Fariin.colors
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val billing = com.fariin.app.data.PlusBilling
    val sub = PlusStore.subscription
    val paidActive = PlusStore.isPlus && sub != null && (sub.untilMs == null || sub.untilMs > System.currentTimeMillis())
    LaunchedEffect(PlusStore.purchasesOn) { if (PlusStore.purchasesOn && !billing.loaded) billing.loadPlans(ctx) }
    billing.notice?.let { n ->
        com.fariin.app.ui.components.GlassAlert(onDismissRequest = { billing.notice = null }, title = { Text("Fariin Plus") }, text = { Text(n) },
            confirmButton = { androidx.compose.material3.TextButton({ billing.notice = null }) { Text("OK", color = c.accent) } }, containerColor = c.card)
    }
    when {
        paidActive && sub?.source == "play" -> SettingsSection(footer = renewLine(sub)) {
            SettingsRow("Manage Subscription", last = true, trailing = Trailing.OUT_LINK) {
                runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(billing.manageUrl(ctx.packageName)))) }
            }
        }
        paidActive && sub?.source == "appstore" -> SettingsSection(footer = "Your Fariin Plus subscription is managed in the App Store on your iPhone.") {}
        PlusStore.purchasesOn -> {
            var plan by remember { mutableStateOf("yearly") }
            val plans = billing.plans
            val picked = plans.firstOrNull { it.basePlanId == plan } ?: plans.firstOrNull()
            SettingsSection(header = "Choose a plan") {
                if (plans.isEmpty()) Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                    if (billing.loaded) Text("Subscriptions are not available right now.", color = c.secondary)
                    else com.fariin.app.ui.components.FariinSpinner(size = 24.dp, stroke = 2.5.dp, color = c.secondary)
                } else plans.forEachIndexed { i, p ->
                    val on = picked?.basePlanId == p.basePlanId
                    Row(Modifier.fillMaxWidth().clickable { plan = p.basePlanId }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(ImageVector.cachedVector(if (on) R.drawable.ic_sf_checkmark_circle_fill else R.drawable.ic_sf_circle), null,
                            tint = if (on) PlusBlue else c.secondary, modifier = Modifier.size(24.dp))
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
            val activity = ctx as? android.app.Activity ?: (ctx as? android.content.ContextWrapper)?.baseContext as? android.app.Activity
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(52.dp).clip(CircleShape)
                .background(if (picked != null && !billing.busy) PlusBlue else PlusBlue.copy(alpha = 0.5f))
                .clickable(enabled = picked != null && !billing.busy && activity != null) { if (picked != null && activity != null) billing.buy(activity, picked) },
                contentAlignment = Alignment.Center) {
                if (billing.busy) com.fariin.app.ui.components.FariinSpinner(size = 22.dp, stroke = 2.5.dp, color = Color.White)
                else Text(picked?.let { "Subscribe for ${it.price} ${per(it.period)}" } ?: "Subscribe", fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold, color = Color.White)
            }
            Text("Payment is charged to your Google Play account when you confirm. The subscription renews automatically at the same price and for the same period until you cancel it. You can manage or cancel it any time in Google Play under Payments & subscriptions.",
                fontSize = 13.sp, color = c.secondary, modifier = Modifier.padding(horizontal = 32.dp, vertical = 6.dp))
            Row(Modifier.padding(horizontal = 32.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                listOf("Terms of Use" to "https://fariin.com/terms", "Privacy Policy" to "https://fariin.com/privacy").forEach { (t, u) ->
                    Text(t, fontSize = 13.sp, color = c.accent, modifier = Modifier.clickable {
                        runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(u))) }
                    })
                }
            }
        }
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
