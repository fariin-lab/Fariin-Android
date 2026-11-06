package com.fariin.app.push

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.fariin.app.ui.components.IllustratedSheet
import com.fariin.app.ui.components.LineArt
import com.fariin.app.ui.components.PermissionDenied

/**
 * The notifications ask, as a sheet over the chat list (Android 13+). Turn On slides the sheet away
 * and then asks Android's question; once Android will no longer ask (said no for good), Turn On
 * opens Fariin's notification settings instead. Not now asks nothing. Whatever the answer, the
 * first-run ask is answered for good on this install (Push.markExplainerSeen). [onDone] runs after
 * Android's answer, so the caller keeps this in the composition until then.
 */
@Composable
fun NotificationsSheet(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var showing by remember { mutableStateOf(true) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        Push.register(ctx)
        onDone()
    }
    fun notNow() { Push.markExplainerSeen(ctx); Push.register(ctx); onDone() }
    if (showing) IllustratedSheet(
        art = LineArt.Bell,
        title = "Turn on notifications",
        body = "So your messages and calls reach you, even when Fariin is closed.",
        primary = "Turn On",
        onPrimary = {
            showing = false
            Push.markExplainerSeen(ctx)
            if (Push.askBlocked(ctx)) {
                runCatching {
                    ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure { PermissionDenied.openAppSettings(ctx) }
                onDone()
            } else {
                Push.markAsked(ctx)
                ask.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        },
        onDismiss = { showing = false; notNow() },
        secondary = "Not now",
        onSecondary = { showing = false; notNow() },
    )
}
