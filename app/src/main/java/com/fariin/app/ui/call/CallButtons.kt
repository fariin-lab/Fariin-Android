package com.fariin.app.ui.call

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fariin.app.ui.components.floating
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Icon
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
    if (!isGroup) {
        val peer = Wire.otherUid(cid, me)
        if (peer.isEmpty() || peer == me) return
        // One glass capsule, video then voice (iOS 26 bar button group: 44 high, 22 icons in the label colour).
        CallCapsule {
            CallSlot(com.fariin.app.R.drawable.ic_call_video, "Video call") { CallActivity.startDial(ctx, peer, title, photoUrl, video = true) }
            CallSlot(com.fariin.app.R.drawable.ic_call_voice, "Voice call") { CallActivity.startDial(ctx, peer, title, photoUrl, video = false) }
        }
        return
    }
    var live by remember(cid) { mutableStateOf(false) }
    // Seeded from the chat list's copy so the buttons are there on the first frame of the open,
    // not a beat later when the listener answers (Pixel recording 2026-11-09).
    var mayStart by remember(cid) { mutableStateOf(com.fariin.app.data.CryptoHub.listedConversation(cid)?.mayStartCall(me) == true) }
    DisposableEffect(cid) {
        val reg = GroupCall.watchActive(cid) { live = it }
        val conv = FirebaseFirestore.getInstance().collection("conversations").document(cid)
            .addSnapshotListener { snap, _ -> mayStart = GroupCallWire.mayStart(snap?.data, me) }
        onDispose { reg.remove(); conv.remove() }
    }
    if (live) {
        CallCapsule {
            CallSlot(com.fariin.app.R.drawable.ic_call_voice, "Join call", width = 44.dp) { CallActivity.startGroup(ctx, cid, title, video = false) }
        }
        return
    }
    // The admin panel can switch group calls off (iOS CallFeatures.groupCalls): no start button then.
    if (!mayStart || !com.fariin.app.data.AppFeatures.groupCalls) return
    // One video button; a tap drops down Voice Call and Video Call (iOS ChatNavigationItem, groups).
    var menu by remember(cid) { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        CallCapsule {
            CallSlot(com.fariin.app.R.drawable.ic_call_video, "Call", width = 44.dp) { menu = true }
        }
        com.fariin.app.ui.components.GlassDropdown(expanded = menu, onDismissRequest = { menu = false }) {
            androidx.compose.material3.DropdownMenuItem(
                text = { androidx.compose.material3.Text("Voice Call") },
                leadingIcon = { Icon(androidx.compose.ui.res.painterResource(com.fariin.app.R.drawable.ic_call_voice), null, tint = Fariin.colors.label, modifier = Modifier.size(22.dp)) },
                onClick = { menu = false; CallActivity.startGroup(ctx, cid, title, video = false, start = true) })
            androidx.compose.material3.DropdownMenuItem(
                text = { androidx.compose.material3.Text("Video Call") },
                leadingIcon = { Icon(androidx.compose.ui.res.painterResource(com.fariin.app.R.drawable.ic_call_video), null, tint = Fariin.colors.label, modifier = Modifier.size(22.dp)) },
                onClick = { menu = false; CallActivity.startGroup(ctx, cid, title, video = true, start = true) })
        }
    }
}

/** The calls' glass capsule, 44 high (one button alone is a 44 circle). */
@Composable
private fun CallCapsule(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(Modifier.height(44.dp).floating(CircleShape), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, content = content)
}

/** One call button in the capsule: a 48 x 44 slot (the recording's spacing), a 22 icon. */
@Composable
private fun CallSlot(icon: Int, label: String, width: androidx.compose.ui.unit.Dp = 48.dp, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Box(
        Modifier.size(width, 44.dp).clickable(onClick = onClick).semantics { role = Role.Button },
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        Icon(androidx.compose.ui.res.painterResource(icon), contentDescription = label, tint = Fariin.colors.label, modifier = Modifier.size(22.dp))
    }
}
