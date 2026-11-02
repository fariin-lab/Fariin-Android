package com.fariin.app.call

import android.content.Context

/**
 * The call settings this phone keeps, under the same key names iOS uses in UserDefaults.
 * Settings > Privacy > "Always Relay Calls" writes [ALWAYS_RELAY] into [FILE]; the calls only read it.
 */
object CallPrefs {
    const val FILE = "fariin_calls"
    /** iOS `@AppStorage("calls.alwaysRelay")`: relay every 1:1 call, contacts too, so no peer sees my IP. */
    const val ALWAYS_RELAY = "calls.alwaysRelay"

    fun alwaysRelay(c: Context): Boolean =
        try { c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(ALWAYS_RELAY, false) } catch (_: Exception) { false }

    fun setAlwaysRelay(c: Context, on: Boolean) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(ALWAYS_RELAY, on).apply()
    }
}
