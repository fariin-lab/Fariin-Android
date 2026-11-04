package com.fariin.app.ui.theme

import android.app.Activity
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.fariin.app.data.AppearancePrefs
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Colours from Kulan-iOS/Kulan/DesignSystem/Theme.swift, plus the iOS system colours that file
 * leans on (label, secondaryLabel, separator, secondarySystemFill). Light or dark follows
 * Settings > Appearance > Night Mode (System by default).
 */
@Immutable
data class FariinColors(
    val dark: Boolean,
    /** Theme.bg */
    val bg: Color,
    /** Theme.bgSecondary */
    val bgSecondary: Color,
    /** Theme.card */
    val card: Color,
    /** Theme.accent: black in light, white in dark (buttons, unread badge). */
    val accent: Color,
    /** Theme.onAccent */
    val onAccent: Color,
    /** UIColor.label */
    val label: Color,
    /** Theme.secondary (#8E8E93, systemGray) */
    val secondary: Color,
    /** UIColor.tertiaryLabel */
    val tertiary: Color,
    /** UIColor.separator */
    val separator: Color,
    /** AvatarPalette.placeholderFill (secondarySystemFill) */
    val placeholderFill: Color,
    /** Theme.defaultBubble (system blue) */
    val blue: Color,
    val red: Color,
    /** The text field fill on the email page (tertiarySystemFill). */
    val fieldFill: Color,
)

private val Light = FariinColors(
    dark = false,
    bg = Color(0xFFFFFFFF),
    bgSecondary = Color(0xFFF2F2F7),
    card = Color(0xFFFFFFFF),
    accent = Color.Black,
    onAccent = Color.White,
    label = Color.Black,
    secondary = Color(0xFF8E8E93),
    tertiary = Color(0x4D3C3C43),
    separator = Color(0x4A3C3C43),
    placeholderFill = Color(0x29787880),
    blue = Color(0xFF007AFF),
    red = Color(0xFFFF3B30),
    fieldFill = Color(0x1F767680),
)

private val Dark = FariinColors(
    dark = true,
    bg = Color(0xFF121214),
    bgSecondary = Color(0xFF121214),
    card = Color(0xFF26262B),
    accent = Color.White,
    onAccent = Color.Black,
    label = Color.White,
    secondary = Color(0xFF8E8E93),
    tertiary = Color(0x4DEBEBF5),
    separator = Color(0x99545458),
    placeholderFill = Color(0x52787880),
    blue = Color(0xFF0A84FF),
    red = Color(0xFFFF453A),
    fieldFill = Color(0x3D767680),
)

val LocalFariinColors = staticCompositionLocalOf { Light }

object Fariin {
    val colors: FariinColors
        @Composable get() = LocalFariinColors.current
}

@Composable
fun FariinTheme(dark: Boolean = AppearancePrefs.isDark(), content: @Composable () -> Unit) {
    val c = if (dark) Dark else Light
    val scheme = if (dark) {
        darkColorScheme(
            primary = c.accent, onPrimary = c.onAccent,
            // No Material purple anywhere: secondary, the inverse (snackbar) action and the tint
            // Material lays over raised surfaces all follow the app's black and white.
            secondary = c.accent, onSecondary = c.onAccent, inversePrimary = c.onAccent, surfaceTint = Color.Transparent,
            background = c.bg, onBackground = c.label,
            surface = c.bg, onSurface = c.label,
            surfaceVariant = c.card, onSurfaceVariant = c.secondary,
            surfaceContainer = c.bg,
            // Material 3 surfaces: menus, sheets, dialogs and the selection bar sit one step up.
            surfaceContainerLowest = c.bg, surfaceContainerLow = Color(0xFF1A1A1D),
            surfaceContainerHigh = Color(0xFF232327), surfaceContainerHighest = Color(0xFF2C2C31),
            // Neutral, not blue: the app is black and white like iOS (owner 2026-11-04).
            secondaryContainer = Color(0xFF2C2C31), onSecondaryContainer = c.label,
            primaryContainer = Color(0xFF2C2C31), onPrimaryContainer = c.label,
            tertiary = c.blue, onTertiary = Color.White,
            outline = c.separator, outlineVariant = c.separator,
            error = c.red,
        )
    } else {
        lightColorScheme(
            primary = c.accent, onPrimary = c.onAccent,
            // No Material purple anywhere: secondary, the inverse (snackbar) action and the tint
            // Material lays over raised surfaces all follow the app's black and white.
            secondary = c.accent, onSecondary = c.onAccent, inversePrimary = c.onAccent, surfaceTint = Color.Transparent,
            background = c.bg, onBackground = c.label,
            surface = c.bg, onSurface = c.label,
            surfaceVariant = c.bgSecondary, onSurfaceVariant = c.secondary,
            surfaceContainer = c.bg,
            surfaceContainerLowest = c.bg, surfaceContainerLow = Color(0xFFF7F7FA),
            surfaceContainerHigh = Color(0xFFF0F0F4), surfaceContainerHighest = Color(0xFFE8E8ED),
            secondaryContainer = Color(0xFFE8E8ED), onSecondaryContainer = c.label,
            primaryContainer = Color(0xFFE8E8ED), onPrimaryContainer = c.label,
            tertiary = c.blue, onTertiary = Color.White,
            outline = c.separator, outlineVariant = c.separator,
            error = c.red,
        )
    }
    // Night Mode can differ from the phone's setting, so the status and navigation bar icons
    // follow the app's choice rather than the system's.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            (view.context as? Activity)?.window?.let { w ->
                WindowCompat.getInsetsController(w, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
    }
    CompositionLocalProvider(LocalFariinColors provides c) {
        MaterialTheme(colorScheme = scheme, shapes = RoundShapes, content = content)
    }
}

/*
 * Material's corner set, rounded up to the iOS look app-wide: menus and text fields (extraSmall,
 * 4dp by default) get 14dp, so a DropdownMenu or a field reads as a rounded card rather than a
 * square box; dialogs and sheets keep their 28dp.
 */
private val RoundShapes = androidx.compose.material3.Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
)
