package com.fariin.app.ui.thread

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalView

/**
 * The light tap iOS plays with UIImpactFeedbackGenerator(style: .light): a reply swipe arming, a
 * double-tap reaction, a poll vote, the + button, the sticker panel, a mention picked, a photo
 * saved. Played through the view without FLAG_IGNORE_GLOBAL_SETTING, so the phone's own
 * "Touch feedback" switch turns it off.
 */
internal fun View.lightTap() {
    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
}

/** The current view's light tap, for a composable's click handler. */
@Composable
internal fun rememberLightTap(): () -> Unit {
    val view = LocalView.current
    return androidx.compose.runtime.remember(view) { { view.lightTap() } }
}
