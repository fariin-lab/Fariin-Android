package com.fariin.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * iOS StoryPrefs.isOneTimeUsed: a one-time story I have watched leaves my list on this phone at
 * once, without waiting for the server to take my token off it (slow network, offline). Kept with
 * each story's expiry so the list never grows past a day or two.
 */
object StoryOneTimeUsed {
    private const val PREFS = "fariin_stories"
    private const val KEY = "oneTimeUsed"
    private val _ids = MutableStateFlow<Map<String, Long>>(emptyMap())
    val ids: StateFlow<Map<String, Long>> = _ids
    private var prefs: android.content.SharedPreferences? = null

    fun load(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        val now = System.currentTimeMillis()
        _ids.value = runCatching {
            val o = JSONObject(p.getString(KEY, "{}") ?: "{}")
            o.keys().asSequence().associateWith { o.getLong(it) }.filterValues { it > now }
        }.getOrDefault(emptyMap())
    }

    fun mark(ctx: Context, s: Story) {
        if (!s.oneTime || s.id.isEmpty()) return
        load(ctx)
        val now = System.currentTimeMillis()
        val next = (_ids.value + (s.id to s.expiresAtMs)).filterValues { it > now }
        _ids.value = next
        prefs?.edit()?.putString(KEY, JSONObject(next.mapValues { it.value }).toString())?.apply()
    }

    /** Sign-out: the next account starts clean. */
    fun wipe(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
        _ids.value = emptyMap()
        prefs = null
    }
}
