package com.fariin.app.ui.people

import com.fariin.app.ui.components.glassMenuAnchor
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.kyant.backdrop.backdrops.layerBackdrop
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.fariin.app.data.GroupInfoCircles
import com.fariin.app.ui.settings.ScrollPage
import com.fariin.app.ui.settings.SettingsRow
import com.fariin.app.ui.settings.SettingsSection
import com.fariin.app.ui.theme.Fariin
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await

/** iOS ContactInfoView's "Sounds & Notifications" row value: "Muted" while a mute runs, else "On". */
internal fun contactNotifValue(untilMs: Double, nowMs: Double = System.currentTimeMillis().toDouble()): String =
    if (untilMs > nowMs) "Muted" else "On"

/** iOS ChatService.setMute on a 1:1: `mutedBy.<me>` = the end in epoch ms (0 = unmute, far future = Always). */
internal suspend fun writeContactMute(cid: String, me: String, untilMs: Double): Boolean = try {
    FirebaseFirestore.getInstance().collection("conversations").document(cid)
        .set(mapOf("mutedBy" to mapOf(me to untilMs)), SetOptions.merge()).await(); true
} catch (_: Exception) { false }

/**
 * iOS SoundsNotificationsView for a person: under "Messages", Mute with when it ends ("Until
 * 3:40 PM", "Always"), a menu of Unmute, 1 hour, 8 hours, 1 day, 1 week, Always; the footer says
 * "Muted until …" while a mute runs. [untilMs] is the live end; [onMute] writes a new one.
 */
@Composable
internal fun ContactSoundsPage(untilMs: Double, onMute: (Double) -> Unit, header: String = "Messages",
                               /** The chat's id: its own Message Sound row (iOS); empty hides the row. */ cid: String = "",
                               onBack: () -> Unit) {
    val c = Fariin.colors
    val ctx = LocalContext.current
    BackHandler(onBack = onBack)
    val now = System.currentTimeMillis()
    val muted = untilMs > now
    val is24h = android.text.format.DateFormat.is24HourFormat(ctx)
    val endLabel = if (muted) GroupInfoCircles.mutedLabel(untilMs, now, is24h) else null
    // "Muted until 3:40 PM" -> "Until 3:40 PM"; "Muted always" -> "Always" (iOS muteLabel).
    val value = endLabel?.removePrefix("Muted ")?.replaceFirstChar { it.uppercase() } ?: "Off"
    var menu by remember { mutableStateOf(false) }
    var toneSheet by remember { mutableStateOf(false) }
    var tone by remember(cid) { mutableStateOf(com.fariin.app.push.MessageTones.of(ctx, cid)) }
    if (toneSheet) MessageSoundSheet(tone.id, onClose = { toneSheet = false }) { id ->
        com.fariin.app.push.MessageTones.set(ctx, cid, id); tone = com.fariin.app.push.MessageTones.of(ctx, cid)
    }
    // The Mute choices are the glass menu growing out of the row, as on the profile's Mute button
    // (it was the old dropdown, pinned left with a grey box behind it, owner 2026-11-10).
    val anchor = remember { com.fariin.app.ui.components.GlassMenuAnchor() }
    val backdrop = com.kyant.backdrop.backdrops.rememberLayerBackdrop()
    val glass = com.fariin.app.perf.rememberPowerState().glass() && android.os.Build.VERSION.SDK_INT >= 33
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().then(if (glass) Modifier.layerBackdrop(backdrop) else Modifier)) {
            ScrollPage("Sounds & Notifications", onBack) {
                SettingsSection(header = header, footer = endLabel) {
                    Box(Modifier.glassMenuAnchor(anchor)) {
                        SettingsRow("Mute", icon = painterResource(if (muted) com.fariin.app.R.drawable.ic_bell_off else com.fariin.app.R.drawable.ic_notifications),
                            value = value, last = cid.isEmpty()) { menu = true }
                    }
                    if (cid.isNotEmpty()) SettingsRow("Message Sound", icon = painterResource(com.fariin.app.R.drawable.ic_speaker_on),
                        value = tone.name.removeSuffix(" (Default)"), last = true) { toneSheet = true }
                }
            }
        }
        com.fariin.app.ui.components.GlassMenu(menu, anchor, buildList {
            if (muted) add(com.fariin.app.ui.components.GlassMenuItem("Unmute", null) { onMute(0.0) })
            GroupInfoCircles.MUTE_CHOICES.forEach { (label, hours) ->
                add(com.fariin.app.ui.components.GlassMenuItem(label, null) { onMute(GroupInfoCircles.muteUntil(hours)) })
            }
        }, backdrop = if (glass) backdrop else null, caption = "Mute this chat for…", onDismiss = { menu = false })
    }
}

/** iOS sound picker: a sheet with X, the title and a tick; each row plays its tone, the tick keeps it. */
@Composable
private fun MessageSoundSheet(current: String, onClose: () -> Unit, onPick: (String) -> Unit) {
    val ctx = LocalContext.current
    val c = com.fariin.app.ui.theme.Fariin.colors
    var picked by remember { mutableStateOf(current) }
    val motion = com.fariin.app.ui.components.rememberSheetMotion(onClose)
    com.fariin.app.ui.components.FariinSheetDialog(motion, fill = c.bgSecondary) {
        com.fariin.app.ui.components.SheetPage(motion, "Message Sound", onDone = { onPick(picked); motion.close() }) {
            androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(androidx.compose.ui.unit.Dp(12f)))
            com.fariin.app.ui.settings.SettingsSection {
                val list = com.fariin.app.push.MessageTones.all
                list.forEachIndexed { i, t ->
                    com.fariin.app.ui.settings.CheckRow(t.name, picked == t.id, last = i == list.lastIndex) {
                        picked = t.id; com.fariin.app.push.MessageTones.preview(ctx, t)
                    }
                }
            }
        }
    }
}
