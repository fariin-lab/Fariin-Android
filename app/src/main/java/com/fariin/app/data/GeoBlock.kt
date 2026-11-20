package com.fariin.app.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Country restrictions (server 2026-11-16, iOS 394c178fb). getAppConfig answers `geoBlocked:
 * {message}` while this account is used from a blocked country (the server also puts a claim on the
 * account, so the rules refuse it), `geoRefresh: true` once allowed again. The app then covers
 * everything with one screen, kept per account so the next launch shows it from the first frame.
 */
object GeoBlock {
    /** The account it is for and the owner's words; null = not blocked. */
    var blocked by mutableStateOf<Pair<String, String>?>(null); private set
    @Volatile private var prefs: android.content.SharedPreferences? = null

    fun init(c: Context) {
        if (prefs == null) prefs = c.applicationContext.getSharedPreferences("geo_block", Context.MODE_PRIVATE)
    }

    /** At sign-in: the saved screen for this account, before the server answers. */
    fun load(uid: String) {
        val m = prefs?.getString(key(uid), null)
        blocked = if (m != null) uid to m else null
    }

    /** Sign-out or an account switch: the screen belonged to the account it was for. */
    fun clear() { blocked = null }

    /** Every getAppConfig answer for [uid]. */
    fun answer(uid: String, reply: Map<*, *>) {
        val geo = reply["geoBlocked"] as? Map<*, *>
        if (geo != null) {
            val msg = (geo["message"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                ?: "Fariin isn't available in your region."
            refreshToken()
            prefs?.edit()?.putString(key(uid), msg)?.apply()
            blocked = uid to msg
        } else if (blocked?.first == uid || reply["geoRefresh"] == true || prefs?.contains(key(uid)) == true) {
            // Allowed (again): the claim is gone, so a new token, and the screen goes.
            refreshToken()
            prefs?.edit()?.remove(key(uid))?.apply()
            if (blocked?.first == uid) blocked = null
        }
    }

    private fun refreshToken() { runCatching { com.fariin.app.Fb.auth.currentUser?.getIdToken(true) } }

    private fun key(uid: String) = "msg-$uid"
}
