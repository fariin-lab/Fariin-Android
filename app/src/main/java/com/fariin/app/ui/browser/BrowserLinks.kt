package com.fariin.app.ui.browser

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * A non-web link inside the in-app browser (fb://, intent://, tel:, mailto:). Done the way mobile
 * browsers do it:
 *  - Only a tap leaves the browser. A page that jumps to an app link on its own (a social site's
 *    "open in app" redirect) stays put.
 *  - It leaves only to a real app, never to another browser (FLAG_ACTIVITY_REQUIRE_NON_BROWSER).
 *  - An intent:// link is parsed safely (BROWSABLE only, no explicit component or selector), and when
 *    no app takes it, its own browser_fallback_url (http/https only) loads in our page instead.
 */
object BrowserLinks {
    /** Returns a web address to load in the page instead, or null. */
    fun leave(ctx: Context, url: String, tapped: Boolean): String? {
        val intent = if (url.startsWith("intent:", ignoreCase = true)) parseIntent(url) ?: return null
        else Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
        val fallback = webFallback(intent)
        if (!tapped) return null
        intent.addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 30) intent.addFlags(Intent.FLAG_ACTIVITY_REQUIRE_NON_BROWSER)
        return try { ctx.startActivity(intent); null }
        catch (_: ActivityNotFoundException) { fallback }
        catch (_: SecurityException) { fallback }
    }

    private fun parseIntent(url: String): Intent? = runCatching {
        Intent.parseUri(url, Intent.URI_INTENT_SCHEME).apply {
            // A web page must not aim at a named component of any app (ours included).
            component = null
            selector = null
            // Nor carry its own flags: "launchFlags=" could grant another app read access to a
            // content:// address of ours (our decrypted shared files).
            flags = 0
            clipData = null
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
    }.getOrNull()?.takeUnless {
        val s = it.data?.scheme?.lowercase()
        s == "content" || s == "file"
    }

    /** The intent's S.browser_fallback_url when it is an ordinary web address. */
    internal fun webFallback(intent: Intent): String? =
        intent.getStringExtra("browser_fallback_url")?.takeIf { isWeb(it) }

    internal fun isWeb(url: String): Boolean {
        val s = url.substringBefore(':').lowercase()
        return s == "http" || s == "https"
    }
}
