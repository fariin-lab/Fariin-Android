package com.fariin.app.ui.chats

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp

/**
 * The chat list's round photos that move (iOS ListAvatarVideos, owner 2026-11-20: a video profile
 * picture showed a still in the list). One place decides which rows may hold a player:
 * - only rows the list has built (on screen, or about to be), the first to come in first;
 * - at most [max] at once: 4, or 2 on a LOW phone (each clip is its own decoder and player);
 * - no NEW player while the list is scrolling (making one costs the main thread); running ones go on;
 * - none with Power Saving's video autoplay off (Battery Saver included) or system animations off,
 *   and none in the background (the clip layer pauses there by itself).
 * The file is the same cache the chat header and profile use, so a person is fetched once.
 */
object ListAvatarClips {
    private val order = mutableStateListOf<String>()
    /** The list is moving under a finger or a fling (set by the chat list). */
    var scrolling by mutableStateOf(false)

    private fun max(): Int = if (com.fariin.app.perf.DeviceClass.peek() == com.fariin.app.perf.PerfClass.LOW) 2 else 4

    fun allowed(key: String): Boolean {
        val i = order.indexOf(key)
        return i in 0 until max()
    }

    internal fun enter(key: String) { if (key !in order) order.add(key) }
    internal fun leave(key: String) { order.remove(key) }
}

/** A 1:1 row's photo clip over its still, when its person has one and the list lets it play. */
@Composable
fun ListAvatarClip(key: String, uid: String, size: Dp) {
    val video = com.fariin.app.data.AvatarVideoIndex.playable(uid) ?: return
    val power = com.fariin.app.perf.rememberPowerState()
    if (!power.autoplayVideos || !com.fariin.app.ui.thread.systemAnimationsOn()) return
    DisposableEffect(key) {
        ListAvatarClips.enter(key)
        onDispose { ListAvatarClips.leave(key) }
    }
    val allowed = ListAvatarClips.allowed(key)
    var started by remember(key) { mutableStateOf(false) }
    if (allowed && !started && !ListAvatarClips.scrolling) started = true
    if (!allowed) started = false
    if (started) com.fariin.app.ui.people.AvatarVideoLayer(uid, video, Modifier.size(size).clip(CircleShape))
}
