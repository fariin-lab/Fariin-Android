package com.fariin.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

/**
 * Call first inside a full-screen Dialog that shows an ordinary app page (settings, lists). A dialog's
 * window starts with white status icons, which vanish on a light page (Story privacy, Pixel
 * 2026-11-06): the icons follow the app's theme here, as on the main window.
 */
@Composable
fun AppPageDialogWindow() {
    val view = LocalView.current
    val dark = com.fariin.app.ui.theme.Fariin.colors.dark
    SideEffect {
        (view.parent as? DialogWindowProvider)?.window?.let { w ->
            WindowCompat.getInsetsController(w, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
}

/**
 * Call first inside a full-screen Dialog whose page is dark (camera, viewers): the dialog's window
 * otherwise stops at the status bar and the page behind shows there as a grey band. Lays the
 * window out edge to edge (into the cutout too) under clear bars with light icons.
 */
@Composable
fun EdgeToEdgeDarkDialogWindow() {
    val view = LocalView.current
    SideEffect {
        (view.parent as? DialogWindowProvider)?.window?.let { w ->
            w.setDimAmount(0f)
            w.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
            WindowCompat.setDecorFitsSystemWindows(w, false)
            @Suppress("DEPRECATION") run {
                w.statusBarColor = android.graphics.Color.TRANSPARENT
                w.navigationBarColor = android.graphics.Color.TRANSPARENT
            }
            if (android.os.Build.VERSION.SDK_INT >= 29) { w.isStatusBarContrastEnforced = false; w.isNavigationBarContrastEnforced = false }
            if (android.os.Build.VERSION.SDK_INT >= 28) w.attributes = w.attributes.apply {
                layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            WindowCompat.getInsetsController(w, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }
}
