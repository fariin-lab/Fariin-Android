package com.fariin.app.ui.call

import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.fariin.app.call.GroupCall
import com.fariin.app.crypto.Wire
import com.fariin.app.ui.theme.Fariin
import com.google.firebase.auth.FirebaseAuth

/**
 * The chat header's call buttons, as iOS: video and voice for a 1:1. In a group they show only
 * while the group's call is going, and join it (starting one is not on Android yet). Nothing in a
 * debug demo chat (no Firebase there).
 */
@Composable
fun ThreadCallButtons(cid: String, me: String, isGroup: Boolean, title: String, photoUrl: String?) {
    if (cid.isEmpty() || FirebaseAuth.getInstance().currentUser == null) return
    val ctx = LocalContext.current
    val tint = Fariin.colors.accent
    if (!isGroup) {
        val peer = Wire.otherUid(cid, me)
        if (peer.isEmpty() || peer == me) return
        Row {
            IconButton(onClick = { CallActivity.startDial(ctx, peer, title, photoUrl, video = true) }) {
                Icon(Icons.Filled.Videocam, contentDescription = "Video call", tint = tint)
            }
            IconButton(onClick = { CallActivity.startDial(ctx, peer, title, photoUrl, video = false) }) {
                Icon(Icons.Filled.Call, contentDescription = "Voice call", tint = tint)
            }
        }
        return
    }
    var live by remember(cid) { mutableStateOf(false) }
    DisposableEffect(cid) {
        val reg = GroupCall.watchActive(cid) { live = it }
        onDispose { reg.remove() }
    }
    if (!live) return
    IconButton(onClick = { CallActivity.startGroup(ctx, cid, title, video = false) }) {
        Icon(Icons.Filled.Call, contentDescription = "Join call", tint = tint)
    }
}
