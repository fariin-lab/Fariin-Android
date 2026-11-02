package com.fariin.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Colours from Kulan-iOS/Kulan/DesignSystem/Theme.swift, plus the iOS system colours that file
 * leans on (label, secondaryLabel, separator, secondarySystemFill). Follows the phone's
 * light/dark setting.
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
fun FariinTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (dark) Dark else Light
    val scheme = if (dark) {
        darkColorScheme(
            primary = c.accent, onPrimary = c.onAccent,
            background = c.bg, onBackground = c.label,
            surface = c.bg, onSurface = c.label,
            surfaceVariant = c.card, onSurfaceVariant = c.secondary,
            surfaceContainer = c.bg,
            outline = c.separator, outlineVariant = c.separator,
            error = c.red,
        )
    } else {
        lightColorScheme(
            primary = c.accent, onPrimary = c.onAccent,
            background = c.bg, onBackground = c.label,
            surface = c.bg, onSurface = c.label,
            surfaceVariant = c.bgSecondary, onSurfaceVariant = c.secondary,
            surfaceContainer = c.bg,
            outline = c.separator, outlineVariant = c.separator,
            error = c.red,
        )
    }
    CompositionLocalProvider(LocalFariinColors provides c) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
