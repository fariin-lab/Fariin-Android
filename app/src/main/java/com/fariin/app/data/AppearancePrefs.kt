package com.fariin.app.data

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext

/** Night Mode, as iOS AppAppearance (Theme.swift): System follows the device. */
enum class AppAppearance(val raw: String, val label: String) {
    SYSTEM("system", "System"), LIGHT("light", "Light"), DARK("dark", "Dark");

    companion object {
        fun from(raw: String?): AppAppearance = entries.firstOrNull { it.raw == raw } ?: SYSTEM
    }
}

/** The Night Mode choice, kept on this device under the same key iOS uses ("appearance"). */
object AppearancePrefs {
    private const val FILE = "fariin_prefs"
    private const val KEY = "appearance"
    private val state = mutableStateOf<AppAppearance?>(null)

    fun current(context: Context): AppAppearance {
        state.value?.let { return it }
        val v = AppAppearance.from(context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null))
        state.value = v
        return v
    }

    fun set(context: Context, mode: AppAppearance) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, mode.raw).apply()
        state.value = mode
    }

    /** Whether the app draws dark right now; recomposes when the choice changes. */
    @Composable
    fun isDark(): Boolean = when (current(LocalContext.current)) {
        AppAppearance.SYSTEM -> isSystemInDarkTheme()
        AppAppearance.LIGHT -> false
        AppAppearance.DARK -> true
    }
}
