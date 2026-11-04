package com.fariin.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.fariin.app.perf.PerfClass
import com.fariin.app.perf.PowerSaving
import com.fariin.app.perf.rememberPowerState

internal const val PAGE_POWER = "power"

/**
 * Settings > Power Saving. iOS has no such page (it follows Low Power Mode on its own), so the
 * words are plain. The switches start from the phone's class (perf/DeviceClass): a LOW phone
 * starts with every effect off. While Battery Saver is on and the last switch is on, the effects
 * are off whatever the switches say, and the switches show greyed out.
 */
@Composable
internal fun PowerSavingPage(pop: () -> Unit) {
    val ctx = LocalContext.current
    PowerSaving.init(ctx)
    val s = rememberPowerState()
    val ch = s.choices
    val forced = ch.followBatterySaver && s.batterySaverOn
    val set: (String, Boolean) -> Unit = { k, v -> PowerSaving.set(ctx, k, v) }
    ScrollPage("Power Saving", pop) {
        SettingsSection(
            header = "Battery Saver",
            footer = if (s.batterySaverOn) "Battery Saver is on now." else "Turns off the effects below while Battery Saver is on.",
        ) {
            SwitchRow("Turn on automatically when the phone is in Battery Saver", ch.followBatterySaver, last = true) {
                set(PowerSaving.K_FOLLOW, it)
            }
        }
        SettingsSection(
            header = "Effects",
            footer = when {
                forced -> "These are off while Battery Saver is on."
                s.deviceClass == PerfClass.LOW -> "This phone works best with these off, so they started off."
                else -> "Turning these off saves battery and makes the app smoother on older phones."
            },
        ) {
            SwitchRow("Blur effects", ch.blur, enabled = !forced) { set(PowerSaving.K_BLUR, it) }
            SwitchRow("Animations", ch.animations, enabled = !forced, last = true) { set(PowerSaving.K_ANIM, it) }
        }
        SettingsSection(header = "Media") {
            SwitchRow("Autoplay videos", ch.autoplayVideos, enabled = !forced) { set(PowerSaving.K_VIDEO, it) }
            SwitchRow("Autoplay GIFs and animated stickers", ch.autoplayGifs, enabled = !forced, last = true) { set(PowerSaving.K_GIFS, it) }
        }
    }
}
