package com.fariin.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView

/**
 * A page with nothing to type opens without the keyboard (owner 2026-11-19, Oppo: a profile and its
 * All Media opened with the keyboard up). A chat's message field kept its focus under the page laid
 * over it and called the keyboard back; this takes the focus away once, as the page opens.
 */
@Composable
fun NoKeyboardOnOpen() {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    LaunchedEffect(Unit) {
        focus.clearFocus(force = true); keyboard?.hide()
        // Android 8 brings it back when the page's slide ends and input restarts; close it again
        // through the window itself then.
        repeat(3) {
            kotlinx.coroutines.delay(150)
            (view.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                ?.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }
}
