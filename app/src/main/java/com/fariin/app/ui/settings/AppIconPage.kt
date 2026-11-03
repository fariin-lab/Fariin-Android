package com.fariin.app.ui.settings

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
import androidx.compose.material3.AlertDialog
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

    /** The alias the launcher shows now: the one enabled, else the default (enabled in the manifest). */
    fun current(c: Context): String = all.firstOrNull { ch ->
        val s = c.packageManager.getComponentEnabledSetting(component(c, ch.alias))
        if (ch.alias == all[0].alias) s != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        else s == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    }?.alias ?: all[0].alias

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
        chosen = alias
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
    Column(Modifier.fillMaxSize()) {
        PageBar("App Icon", onBack = pop)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.card).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)) {
                AppIcons.all.chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        row.forEach { ch ->
                            val sel = ch.alias == current
                            Column(Modifier.weight(1f).clickable {
                                if (ch.alias == current) return@clickable
                                try { AppIcons.choose(ctx, ch.alias); current = ch.alias }
                                catch (e: Exception) { error = e.message ?: "Something went wrong." }
                            }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                val shape = RoundedCornerShape(15.dp)
                                Image(painterResource(ch.art), contentDescription = ch.label, contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(64.dp).clip(shape)
                                        .border(if (sel) 2.5.dp else 1.dp, if (sel) c.accent else c.label.copy(alpha = 0.1f), shape))
                                Text(ch.label, fontSize = 12.sp, color = if (sel) c.accent else c.secondary)
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
    error?.let { msg ->
        AlertDialog(onDismissRequest = { error = null }, title = { Text("Couldn't change the icon") }, text = { Text(msg) },
            confirmButton = { TextButton(onClick = { error = null }) { Text("OK", color = c.label) } },
            containerColor = c.card, titleContentColor = c.label, textContentColor = c.label)
    }
}
