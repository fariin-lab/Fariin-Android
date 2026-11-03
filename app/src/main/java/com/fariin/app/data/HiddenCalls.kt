package com.fariin.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The Calls tab's own "deleted for me" set, as iOS HiddenCalls: call record ids hidden on this
 * phone only, one space-separated list in the app's prefs. Separate from HiddenMessages, so
 * deleting a call here leaves its bubble in the chat. Cleared at sign-out.
 */
object HiddenCalls {
    private const val PREFS = "fariin_hidden"
    private const val KEY = "hiddenCalls"
    private val _ids = MutableStateFlow<Set<String>>(emptySet())
    val ids: StateFlow<Set<String>> = _ids.asStateFlow()
    @Volatile private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""
        _ids.value = raw.split(' ').filter { it.isNotEmpty() }.toSet()
    }

    fun hide(context: Context, ids: Collection<String>) {
        val add = ids.filter { it.isNotEmpty() && it !in _ids.value }
        if (add.isEmpty()) return
        _ids.value = _ids.value + add
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, _ids.value.joinToString(" ")).apply()
    }

    fun clear(context: Context) {
        _ids.value = emptySet()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }

    /** iOS deleteSelectionTitle: says what is going, calls, links, or both. */
    fun deleteTitle(calls: Int, links: Int): String {
        val n = calls + links
        return when {
            links == 0 -> "Delete $n call${if (n == 1) "" else "s"}?"
            calls == 0 -> "Delete $n call link${if (n == 1) "" else "s"}?"
            else -> "Delete $n items?"
        }
    }
}
