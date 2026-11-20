package com.fariin.app.data

import android.content.Context

/**
 * The last chat list line this phone saw per chat (iOS row-preview-1115 44d1d83c3): when the server's
 * line comes back "" with a sender (an old expiry sweep wrote that for calls), the row shows this
 * instead of an empty line while the repair runs. Raw (marker or cipher), as the row reads it.
 * Kept in memory and in one small prefs file, written only when a chat's line changes; cleared at sign-out.
 */
object LastSeenLine {
    private const val PREFS = "fariin_last_line"
    private val lines = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile private var prefs: android.content.SharedPreferences? = null

    private fun prefs(): android.content.SharedPreferences? = prefs ?: runCatching {
        com.google.firebase.FirebaseApp.getInstance().applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .also { p -> prefs = p; p.all.forEach { (k, v) -> (v as? String)?.let { lines.putIfAbsent(k, it) } } }
    }.getOrNull()

    fun note(cid: String, raw: String) {
        if (raw.isEmpty() || lines[cid] == raw) return
        lines[cid] = raw
        prefs()?.edit()?.putString(cid, raw)?.apply()
    }

    fun get(cid: String): String? { if (prefs == null) prefs(); return lines[cid] }

    fun clear(c: Context) {
        lines.clear()
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
