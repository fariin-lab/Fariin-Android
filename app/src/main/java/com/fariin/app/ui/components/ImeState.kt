package com.fariin.app.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity

/**
 * Whether the keyboard is up, as a state that changes only when that answer does. Reading
 * `WindowInsets.ime.getBottom(..) > 0` straight in composition rebuilt the screen on every frame of
 * the keyboard sliding in and out (Android 11+ animates the inset), though the answer moved once.
 */
@Composable
fun rememberImeUp(): State<Boolean> {
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    return remember(ime, density) { derivedStateOf { ime.getBottom(density) > 0 } }
}
