package com.fariin.app.ui.stories

import android.view.HapticFeedbackConstants
import android.view.View

/**
 * The three iOS UIImpactFeedbackGenerator styles the story camera and library use, each on one
 * Android tap: light = the app's light tap (KEYBOARD_TAP, as Haptics.kt), medium = VIRTUAL_KEY,
 * rigid = the short sharp CONTEXT_CLICK. Played through the view, so the phone's own "Touch
 * feedback" switch turns them off.
 */
internal enum class StoryHaptic { LIGHT, MEDIUM, RIGID }

internal fun storyHaptic(view: View, kind: StoryHaptic) {
    view.performHapticFeedback(
        when (kind) {
            StoryHaptic.LIGHT -> HapticFeedbackConstants.KEYBOARD_TAP
            StoryHaptic.MEDIUM -> HapticFeedbackConstants.VIRTUAL_KEY
            StoryHaptic.RIGID -> HapticFeedbackConstants.CONTEXT_CLICK
        },
    )
}
