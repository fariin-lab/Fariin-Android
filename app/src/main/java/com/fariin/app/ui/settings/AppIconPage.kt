package com.fariin.app.ui.settings

import com.fariin.app.ui.components.GlassAlert
import com.fariin.app.ui.components.underBar

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fariin.app.R
import com.fariin.app.ui.theme.Fariin

/*
 * Settings > Appearance > App Icon (iOS AppIconPage): the seven icons in a four-column card, the
 * chosen one ringed, a line under the card. Android swaps the launcher entry: each icon is an
 * activity-alias in the manifest and only the chosen one is enabled.
 */

internal const val PAGE_APP_ICON = "look:appicon"

/** iOS AppIconPage.icons, same order and names; [alias] is the manifest activity-alias. */
internal object AppIcons {
    data class Choice(val alias: String, val label: String, val art: Int)

    val all = listOf(
        Choice(".IconDefault", "Black", R.drawable.app_icon_black),
        Choice(".IconBlue", "Blue", R.drawable.app_icon_bubble_blue),
        Choice(".IconGraphite", "Graphite", R.drawable.app_icon_graphite),
        Choice(".IconIndigo", "Indigo", R.drawable.app_icon_indigo),
        Choice(".IconEmerald", "Emerald", R.drawable.app_icon_emerald),
        Choice(".IconCrimson", "Crimson", R.drawable.app_icon_crimson),
        Choice(".IconSand", "Sand", R.drawable.app_icon_sand),
    )

    private fun component(c: Context, alias: String) = ComponentName(c.packageName, "com.fariin.app$alias")

    private fun enabled(c: Context, alias: String): Boolean {
        val s = c.packageManager.getComponentEnabledSetting(component(c, alias))
        return if (alias == all[0].alias) s != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            else s == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    }

    /** The last pick, kept on disk so a pick the app died before finishing can be finished later. */
    private fun saved(c: Context): String? =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CHOSEN, null)?.takeIf { a -> all.any { it.alias == a } }
    private const val PREFS = "fariin_appicon"
    private const val KEY_CHOSEN = "chosen"

    /** The alias the launcher shows now: the one enabled, else the default (enabled in the manifest).
     *  With more than one enabled (a pick not finished yet), the last pick. */
    fun current(c: Context): String {
        val on = all.filter { enabled(c, it.alias) }.map { it.alias }
        if (on.size > 1) saved(c)?.takeIf { it in on }?.let { return it }
        return on.firstOrNull() ?: all[0].alias
    }

    /**
     * App start: if the app was stopped (force-stop, killed) after a pick but before the old icon
     * went off, two launcher icons are left. The last pick is kept and the rest go off the next time
     * the app leaves the screen, the same moment as a normal pick (never while it is open).
     */
    fun heal(c: Context) {
        val app = c.applicationContext
        val keep = saved(app) ?: return
        val extra = runCatching { all.count { it.alias != keep && enabled(app, it.alias) } }.getOrDefault(0)
        if (extra == 0 || !enabled(app, keep)) return
        chosen = keep
        watch(app)
    }

    @Volatile private var chosen: String? = null
    private var watching = false

    /** The icon the page shows as chosen (a pick waiting for the app to leave the screen counts). */
    fun shown(c: Context): String = chosen ?: current(c)

    /**
     * Turns [alias] on at once; the others go off when the app leaves the screen. Turning off the
     * entry the app was opened from closes the app on the spot, which iOS never does.
     */
    fun choose(c: Context, alias: String) {
        val app = c.applicationContext
        app.packageManager.setComponentEnabledSetting(component(app, alias), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_CHOSEN, alias).commit()
        chosen = alias
        watch(app)
    }

    private fun watch(app: Context) {
        if (watching) return
        watching = true
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
                val keep = chosen ?: return
                for (ch in all) if (ch.alias != keep) runCatching {
                    app.packageManager.setComponentEnabledSetting(component(app, ch.alias), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
                }
                chosen = null
            }
        })
    }
}

@Composable
internal fun AppIconPage(pop: () -> Unit) {
    val ctx = LocalContext.current
    val c = Fariin.colors
    var current by remember { mutableStateOf(AppIcons.shown(ctx)) }
    var error by remember { mutableStateOf<String?>(null) }
    // iOS says so after a change: "You have changed the icon for "Fariin"." with the new icon.
    var changed by remember { mutableStateOf<AppIcons.Choice?>(null) }
    val barScroll = rememberScrollState()
    BarPage("App Icon", onBack = pop, under = underBar(barScroll)) { top ->
        Column(Modifier.fillMaxSize().verticalScroll(barScroll).padding(top = top).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.card).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)) {
                AppIcons.all.chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        row.forEach { ch ->
                            val sel = ch.alias == current
                            // No grey press shade (owner 2026-11-10): the ring and the alert say it.
                            Column(Modifier.weight(1f).clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {
                                if (ch.alias == current) return@clickable
                                try { AppIcons.choose(ctx, ch.alias); current = ch.alias; changed = ch }
                                catch (e: Exception) { error = e.message ?: "Something went wrong." }
                            }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                val shape = RoundedCornerShape(15.dp)
                                Image(painterResource(ch.art), contentDescription = ch.label, contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(64.dp).clip(shape)
                                        // iOS: the chosen icon ringed in the text colour, its name in the text colour.
                                        .border(if (sel) 3.dp else 1.dp, if (sel) c.label else c.label.copy(alpha = 0.1f), shape))
                                Text(ch.label, fontSize = 13.sp, color = if (sel) c.label else c.secondary)
                            }
                        }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            // iOS: "...on your Home Screen, in notifications and in the App Library." Android has no
            // App Library and keeps its own small notification icon, so the line names what changes here.
            Text("The icon changes on your Home screen and in the app list.", fontSize = 13.sp, color = c.secondary,
                modifier = Modifier.padding(horizontal = 4.dp))
            Spacer(Modifier.size(com.fariin.app.ui.shell.bottomBarInset()))
        }
    }
    changed?.let { ch ->
        GlassAlert(onDismissRequest = { changed = null },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(ch.art), contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(56.dp).clip(RoundedCornerShape(13.dp)))
                    Spacer(Modifier.size(14.dp))
                    Text("You have changed the icon for “Fariin”.", fontSize = 17.sp, color = c.label)
                }
            },
            confirmButton = { TextButton(onClick = { changed = null }) { Text("OK", color = c.label) } },
            containerColor = c.card, titleContentColor = c.label, textContentColor = c.label)
    }
    error?.let { msg ->
        GlassAlert(onDismissRequest = { error = null }, title = { Text("Couldn't change the icon") }, text = { Text(msg) },
            confirmButton = { TextButton(onClick = { error = null }) { Text("OK", color = c.label) } },
            containerColor = c.card, titleContentColor = c.label, textContentColor = c.label)
    }
}
