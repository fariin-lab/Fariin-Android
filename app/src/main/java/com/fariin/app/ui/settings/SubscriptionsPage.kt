package com.fariin.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.R
import com.fariin.app.ui.components.FariinSheetDialog
import com.fariin.app.ui.components.FloatingIcon
import com.fariin.app.ui.components.rememberSheetMotion
import com.fariin.app.ui.components.sheetDrag
import com.fariin.app.ui.theme.Fariin

/*
 * Settings > Subscriptions (iOS SubscriptionsView): A PREVIEW, shown only to @v (owner 2026-10-27,
 * the same gate as iOS). Nothing here is real yet: no purchase, no feature switched on. The button
 * says "Coming Soon" and does nothing, and the line under it says so. A tap on a feature opens a
 * sheet that explains it.
 */

internal const val PAGE_SUBSCRIPTIONS = "settings:subscriptions"

/** iOS: the row is there for the account whose username is "v", and for nobody else. */
internal fun showsSubscriptions(handle: String?) = handle?.lowercase() == "v"

private class PlusFeature(val icon: Int, val title: String, val detail: String, val more: String)

private val plusFeatures = listOf(
    PlusFeature(R.drawable.ic_sf_arrow_up, "Bigger Files", "Send files and videos up to 4 GB each.",
        "Share long videos, big documents and whole folders in one go, in any chat. Everyone can still open what you send, whether they have Plus or not."),
    PlusFeature(R.drawable.ic_sf_bolt_fill, "Faster Downloads", "Photos, videos and files arrive at full speed.",
        "Media you open downloads at the full speed of your connection, so long videos and large files are ready sooner."),
    PlusFeature(R.drawable.ic_mic, "Voice to Text", "Read a voice message when you can't listen.",
        "Tap a voice message to see what was said as text. Useful in a meeting, on the bus, or anywhere you can't play sound."),
    PlusFeature(R.drawable.ic_sf_globe, "Translate Chats", "Read any message in your own language.",
        "Messages in another language show a Translate button. Your own messages are sent as you wrote them."),
    PlusFeature(R.drawable.ic_story_folder, "More Folders and Pins", "Up to 20 chat folders and 10 pinned chats.",
        "Sort your chats into more folders and keep more of them pinned at the top of your list."),
    PlusFeature(R.drawable.ic_sf_face_smiling, "Extra Stickers and Reactions", "Packs and reactions only members get.",
        "New sticker packs and reactions that only Plus members can send. Everyone in the chat still sees them."),
    PlusFeature(R.drawable.ic_sf_person, "Video Profile Photo", "A short moving clip on your profile.",
        "Set a few seconds of video as your profile picture. It plays when someone opens your profile."),
    PlusFeature(R.drawable.ic_set_appearance, "App Icons", "Pick an icon for Fariin on your Home Screen.",
        "Choose from a set of extra app icons for your Home Screen."),
    PlusFeature(R.drawable.ic_eye_off, "Quiet Story Viewing", "Watch stories without showing up in the views.",
        "Watch stories without appearing in the list of who has seen them, for as long as you leave it on."),
    PlusFeature(R.drawable.ic_star_outline, "Member Badge", "A star beside your name in chats and lists.",
        "A small star next to your name shows that you support Fariin."),
)

@Composable
internal fun SubscriptionsPage(pop: () -> Unit) {
    val c = Fariin.colors
    var opened by remember { mutableStateOf<PlusFeature?>(null) }
    Box(Modifier.fillMaxSize()) {
        ScrollPage("Subscriptions", pop) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 12.dp, bottom = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // The star alone, in the iOS gradient.
                val gradient = Brush.linearGradient(listOf(Color(0xFF7B61FF), Color(0xFF0A84FF), Color(0xFF2BD2C9)))
                Icon(ImageVector.vectorResource(R.drawable.ic_sf_star_fill), contentDescription = null, tint = Color.White,
                    modifier = Modifier.size(72.dp).graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                        .drawWithContent { drawContent(); drawRect(gradient, blendMode = BlendMode.SrcIn) })
                Text("Fariin Plus", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = c.label)
                Text("More of Fariin, and it helps keep the app free for everyone.", fontSize = 15.sp,
                    color = c.secondary, textAlign = TextAlign.Center)
            }
            SettingsSection(header = "What's included") {
                plusFeatures.forEachIndexed { i, f ->
                    SettingsRow(f.title, painterResource(f.icon), subtitle = f.detail, last = i == plusFeatures.lastIndex) { opened = f }
                }
            }
            // Room for the button bar pinned below.
            Spacer(Modifier.height(130.dp))
        }
        // Always on screen, at the foot (owner 2026-10-27: "no need to scroll for it").
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(c.bgSecondary)
            .padding(bottom = com.fariin.app.ui.shell.bottomBarInset()).padding(horizontal = 20.dp).padding(top = 10.dp, bottom = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth().height(50.dp).clip(CircleShape).background(c.accent.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center) {
                Text("Coming Soon", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.onAccent)
            }
            Text("A preview. These features aren't available yet and nothing can be bought here.",
                fontSize = 12.sp, color = c.secondary, textAlign = TextAlign.Center)
        }
    }
    opened?.let { f -> PlusFeatureSheet(f) { opened = null } }
}

/** One feature, explained: its icon, its name and a few lines on what it does (iOS FeatureSheet). */
@Composable
private fun PlusFeatureSheet(f: PlusFeature, onDismiss: () -> Unit) {
    val c = Fariin.colors
    val motion = rememberSheetMotion { onDismiss() }
    val density = LocalDensity.current
    val windowH = LocalWindowInfo.current.containerSize.height
    val statusTop = WindowInsets.statusBars.getTop(density)
    val navBottom = WindowInsets.navigationBars.getBottom(density)
    var contentH by remember { mutableIntStateOf(0) }
    val restH = if (contentH > 0) contentH.toFloat() else with(density) { 400.dp.toPx() }
    val wantMid = with(density) { windowH - statusTop - 10.dp.toPx() - restH - navBottom }.coerceAtLeast(0f)
    if (motion.mid <= 0f) motion.mid = wantMid
    androidx.compose.runtime.SideEffect { motion.fitMid(wantMid) }
    FariinSheetDialog(motion, fill = c.bgSecondary) { _ ->
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .onSizeChanged { if (it.height > 0) contentH = it.height }.padding(horizontal = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(ImageVector.vectorResource(f.icon), contentDescription = null, tint = c.label,
                    modifier = Modifier.padding(top = 64.dp).size(52.dp))
                Text(f.title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = c.label,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp))
                Text(f.more, fontSize = 17.sp, color = c.secondary, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 10.dp))
                Text("Coming with Fariin Plus", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.secondary,
                    modifier = Modifier.padding(top = 32.dp, bottom = 20.dp))
            }
            Box(Modifier.fillMaxWidth().height(28.dp).sheetDrag(motion))
            FloatingIcon(ImageVector.vectorResource(R.drawable.ic_sf_xmark), contentDescription = "Close", onClick = { motion.close() },
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp), circle = true)
        }
    }
}
