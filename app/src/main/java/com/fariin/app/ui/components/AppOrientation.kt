package com.fariin.app.ui.components

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Which way the main screen may turn (iOS OrientationLock, owner 2026-11-16: "the iPhone turns like
 * the reference app"). The chat list, chats, Settings and their sheets turn with the phone when its
 * auto-rotate is on (never upside down). Anything full screen on top (stories, cameras, editors,
 * the photo and video viewers) stays upright, built that way, and opening one while the phone is
 * sideways turns the app upright first. The video viewer's Full Screen keeps its own rule.
 * Server switch `phoneLandscape` (config/features; off = upright everywhere, as before).
 */
object AppOrientation {
    private var upright by mutableIntStateOf(0)
    /** A screen's own rule while it is shown (the video viewer's Full Screen); null = none. */
    var special by mutableStateOf<Int?>(null)

    /** What the activity should ask for now. */
    fun wanted(): Int = special ?: if (com.fariin.app.data.AppFeatures.phoneLandscape && upright == 0)
        ActivityInfo.SCREEN_ORIENTATION_USER else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

    /** Marks a full-screen layer that is built upright: the app stays (or turns) upright while it shows. */
    @Composable
    fun KeepUpright() {
        DisposableEffect(Unit) {
            upright++
            onDispose { upright-- }
        }
    }

    /** The activity follows [wanted] (called once, at the root of its content). */
    @Composable
    fun Follow(activity: Activity) {
        val want = wanted()
        SideEffect { if (activity.requestedOrientation != want) activity.requestedOrientation = want }
    }
}
