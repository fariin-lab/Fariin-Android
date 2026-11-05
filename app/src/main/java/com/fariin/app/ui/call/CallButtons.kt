package com.fariin.app.ui.call

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fariin.app.ui.components.floating
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
import com.fariin.app.call.GroupCallWire
import com.google.firebase.firestore.FirebaseFirestore
import com.fariin.app.crypto.Wire
import com.fariin.app.ui.theme.Fariin
import com.google.firebase.auth.FirebaseAuth

/**
 * The chat header's call buttons in Fariin's own drawings (ic_call_video / ic_call_voice), as iOS: video and voice for a 1:1. In a group: Join while the
 * group's call is going; otherwise video and voice that start one, for whoever the rules let start
 * a call (a member; only admins when the group says "Admins only"). Nothing in a debug demo chat
 * (no Firebase there).
 */
@Composable
fun ThreadCallButtons(cid: String, me: String, isGroup: Boolean, title: String, photoUrl: String?) {
    if (cid.isEmpty() || FirebaseAuth.getInstance().currentUser == null) return
    val ctx = LocalContext.current
    val tint = Fariin.colors.accent
    if (!isGroup) {
        val peer = Wire.otherUid(cid, me)
        if (peer.isEmpty() || peer == me) return
        // One floating capsule, video then voice, outline icons (iOS ChatHeaderView call pill).
        Row(Modifier.padding(horizontal = 4.dp)) {
            IconButton(onClick = { CallActivity.startDial(ctx, peer, title, photoUrl, video = true) }) {
                Icon(androidx.compose.ui.res.painterResource(com.fariin.app.R.drawable.ic_call_video), contentDescription = "Video call", tint = tint)
            }
            IconButton(onClick = { CallActivity.startDial(ctx, peer, title, photoUrl, video = false) }) {
                Icon(androidx.compose.ui.res.painterResource(com.fariin.app.R.drawable.ic_call_voice), contentDescription = "Voice call", tint = tint)
            }
        }
        return
    }
    var live by remember(cid) { mutableStateOf(false) }
    var mayStart by remember(cid) { mutableStateOf(false) }
    DisposableEffect(cid) {
        val reg = GroupCall.watchActive(cid) { live = it }
        val conv = FirebaseFirestore.getInstance().collection("conversations").document(cid)
            .addSnapshotListener { snap, _ -> mayStart = GroupCallWire.mayStart(snap?.data, me) }
        onDispose { reg.remove(); conv.remove() }
    }
    if (live) {
        IconButton(onClick = { CallActivity.startGroup(ctx, cid, title, video = false) }, modifier = Modifier) {
            Icon(androidx.compose.ui.res.painterResource(com.fariin.app.R.drawable.ic_call_voice), contentDescription = "Join call", tint = tint)
        }
        return
    }
    if (!mayStart) return
    Row(Modifier.padding(horizontal = 4.dp)) {
        IconButton(onClick = { CallActivity.startGroup(ctx, cid, title, video = true, start = true) }) {
            Icon(androidx.compose.ui.res.painterResource(com.fariin.app.R.drawable.ic_call_video), contentDescription = "Group video call", tint = tint)
        }
        IconButton(onClick = { CallActivity.startGroup(ctx, cid, title, video = false, start = true) }) {
            Icon(androidx.compose.ui.res.painterResource(com.fariin.app.R.drawable.ic_call_voice), contentDescription = "Group voice call", tint = tint)
        }
    }
}
