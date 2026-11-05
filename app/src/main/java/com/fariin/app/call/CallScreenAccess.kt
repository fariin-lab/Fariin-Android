package com.fariin.app.call

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Android 14's "full screen notifications" switch for Fariin. Without it an incoming call rings but
 * its screen can not take over a locked phone or open over other apps; the call is only a
 * notification. Calling apps from the Play Store get it by default, a side-loaded build does not.
 */
object CallScreenAccess {
    private const val PREFS = "fariin_calls"
    private const val LATER = "fullScreenAskLater"

    fun allowed(c: Context): Boolean {
        if (Build.VERSION.SDK_INT < 34) return true
        return c.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() ?: true
    }

    fun shouldAsk(c: Context): Boolean =
        !allowed(c) && !c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(LATER, false)

    fun later(c: Context) { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(LATER, true).apply() }

    fun openSettings(c: Context) {
        val i = if (Build.VERSION.SDK_INT >= 34)
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${c.packageName}"))
        else Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, c.packageName)
        runCatching { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { runCatching { c.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${c.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }
}
