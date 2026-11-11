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
        applySplash(context, mode)
    }

    private const val APPLIED_KEY = "appearance_splash_applied"

    /**
     * The launch screen follows Fariin's own Night Mode, not only the phone's (owner 2026-11-11:
     * the Pixel in dark mode showed a black splash, then a light app). Android 12+ draws the
     * splash in the app's night mode once it is told; told again only when the choice changes.
     */
    fun applySplash(context: Context, mode: AppAppearance = current(context)) {
        if (android.os.Build.VERSION.SDK_INT < 31) return
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (prefs.getString(APPLIED_KEY, null) == mode.raw) return
        runCatching {
            val ui = context.getSystemService(android.app.UiModeManager::class.java) ?: return
            ui.setApplicationNightMode(when (mode) {
                AppAppearance.SYSTEM -> android.app.UiModeManager.MODE_NIGHT_AUTO
                AppAppearance.LIGHT -> android.app.UiModeManager.MODE_NIGHT_NO
                AppAppearance.DARK -> android.app.UiModeManager.MODE_NIGHT_YES
            })
            prefs.edit().putString(APPLIED_KEY, mode.raw).apply()
        }
    }

    /** Whether the app draws dark right now; recomposes when the choice changes. */
    @Composable
    fun isDark(): Boolean = when (current(LocalContext.current)) {
        AppAppearance.SYSTEM -> isSystemInDarkTheme()
        AppAppearance.LIGHT -> false
        AppAppearance.DARK -> true
    }
}
