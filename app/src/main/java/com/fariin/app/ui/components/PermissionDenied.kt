package com.fariin.app.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.app.ActivityCompat

/**
 * What to say after a permission request came back "no". Called from the request's result, so no
 * state is kept: when Android will no longer show its own dialog (the person chose "Don't allow"
 * twice, or "Don't ask again"), the message carries an "Open Settings" action to the app's page.
 */
object PermissionDenied {
    /** Right after a "no": true when Android will not show its dialog again for [permission]. */
    fun isBlocked(ctx: Context, permission: String): Boolean {
        val act = ctx.activity() ?: return false
        return !ActivityCompat.shouldShowRequestPermissionRationale(act, permission)
    }

    fun explain(ctx: Context, permission: String, why: String) {
        if (isBlocked(ctx, permission)) {
            Snack.show("$why Turn it on in Settings.", action = "Open Settings", long = true) { openAppSettings(ctx) }
        } else {
            Snack.show(why, long = true)
        }
    }

    fun openAppSettings(ctx: Context) {
        runCatching {
            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private tailrec fun Context.activity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.activity()
        else -> null
    }
}
