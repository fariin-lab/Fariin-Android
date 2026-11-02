package com.fariin.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Delete for Me, as iOS HiddenMessages: the message ids hidden on this phone only, one
 * space-separated list in the app's prefs ("hiddenMessages"). Cleared at sign-out.
 */
object HiddenMessages {
    private const val PREFS = "fariin_hidden"
    private const val KEY = "hiddenMessages"
    private val _ids = MutableStateFlow<Set<String>>(emptySet())
    val ids: StateFlow<Set<String>> = _ids.asStateFlow()
    @Volatile private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""
        _ids.value = raw.split(' ').filter { it.isNotEmpty() }.toSet()
    }

    fun isHidden(id: String) = id in _ids.value

    fun hide(context: Context, id: String) {
        if (id.isEmpty() || id in _ids.value) return
        _ids.value = _ids.value + id
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, _ids.value.joinToString(" ")).apply()
    }

    fun clear(context: Context) {
        _ids.value = emptySet()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
