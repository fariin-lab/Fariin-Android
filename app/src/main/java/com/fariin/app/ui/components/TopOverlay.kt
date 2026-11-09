package com.fariin.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * One full-screen page drawn by MainShell above everything, the tab bar included, in the activity's
 * own window: no Dialog window, so no system dim or window animation (the story camera rose twice).
 */
object TopOverlay {
    var content by mutableStateOf<(@Composable () -> Unit)?>(null)
}
